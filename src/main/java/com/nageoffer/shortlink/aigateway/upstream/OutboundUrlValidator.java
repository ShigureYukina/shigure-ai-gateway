package com.nageoffer.shortlink.aigateway.upstream;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 出站地址的安全校验（SSRF 收口）。
 * <p>
 * 与 {@link UpstreamUrlSupport#requireHttpBaseUrl(String)} 是<b>两层</b>，不是把后者改严：
 * <ul>
 *   <li>{@code requireHttpBaseUrl} 只回答"这串字符像一个 http(s) 地址吗"（语法层），
 *       它必须继续放行 {@code http://localhost:8080/v1}，否则 {@code UpstreamUrlSupport} 的
 *       拼装规则会跟着变，波及所有调用方；</li>
 *   <li>{@link #requireWellFormed} 在语法层之上补"与 DNS 无关的硬规则"（userinfo、元数据主机名）；</li>
 *   <li>{@link #requireSafe} 再补"解析到了哪些地址"（要 DNS），是唯一的完整校验入口，内部先调前两层。</li>
 * </ul>
 * {@link #requireSafe} 是<b>阻塞</b>的，调用方必须在 {@code boundedElastic} 上执行；
 * {@link #requireWellFormed} 不阻塞，任何线程都能用。
 * <p>
 * <b>拒绝顺序</b>（任一步失败即抛 {@link IllegalArgumentException}）：
 * <ol>
 *   <li>语法层；</li>
 *   <li>{@code userinfo}（{@code http://user:pass@host} 是经典的 host 混淆写法，合法上游不需要它）；</li>
 *   <li>云元数据主机名 —— <b>与开关无关，永远拒</b>。元数据端点的危害不是"内网可达"，
 *       而是"任意 SSRF 都能换到实例凭证"，没有任何部署需要把它当 LLM 上游；</li>
 *   <li>{@code getAllByName} 取<b>全部</b> A/AAAA 记录逐条判定，<b>任一命中即拒</b>。
 *       只看第一条是最常见的绕过口子（"第一条公网、第二条 127.0.0.1"）；</li>
 *   <li>单条判定前先 {@link #unwrapIpv4Mapped}：{@code ::ffff:10.0.0.1} 在 JDK 里是
 *       {@link Inet6Address}，{@code isLoopbackAddress()} 等一律返回 false，必须显式解包。
 *       十进制/十六进制写法（{@code 2130706433}、{@code 0177.0.0.1}）不需要额外处理 ——
 *       {@code getAllByName} 会先把它们归一成标准地址，这里判的是归一化之后的结果。</li>
 * </ol>
 * {@code allowPrivateAddresses=true}（dev 默认）只额外放行<b>私网与回环</b>两类
 * —— 本地把上游指向 {@code http://localhost:11434}（Ollama）是正常用法；
 * 链路本地（含 {@code 169.254.169.254}）、多播、CGNAT、保留网段、元数据主机名、
 * {@code userinfo}、非 http(s) 一律照拒。
 * <p>
 * <b>残余风险（必须知情）</b>：校验发生在"解析时"，真正的连接由 Netty 在之后<b>重新解析</b>一次 DNS，
 * 两次解析之间可以做手脚（DNS TOCTOU / DNS rebinding）：校验时答公网、连接时答 {@code 127.0.0.1}。
 * 要彻底堵住得给 Netty 装 {@code AddressResolverGroup} 做连接期过滤，本轮<b>不做</b>。
 * 因此这里的定位是"把误配置与顺手可得的 SSRF 挡在写入口"，不是"对抗能控制 DNS 的攻击者"。
 * 缓解程度取决于上游地址的可写面：管理面写入需要写角色，且写入值会落库、有审计。
 */
public final class OutboundUrlValidator {

    /**
     * 云元数据主机名，与 {@code allowPrivateAddresses} 无关，任何环境都拒。
     */
    private static final Set<String> METADATA_HOSTNAMES = Set.of(
            "metadata",
            "metadata.google.internal",
            "instance-data",
            "instance-data.ec2.internal"
    );

    /**
     * 私网专用顶级域（ICANN 2024 为内网保留）。它只可能解析到内网地址，
     * 所以跟着 {@code allowPrivateAddresses} 走而不是硬拒 ——
     * 否则 on-prem 把上游配成 {@code ollama.internal} 的部署会被无解地拒掉，
     * 而这恰恰是"私网开关"想表达的场景。
     */
    private static final String INTERNAL_TLD_SUFFIX = ".internal";

    private OutboundUrlValidator() {
    }

    /**
     * DNS 解析的注入点：生产用 {@code InetAddress::getAllByName}；
     * 测试注入固定答案，这样"多 A 记录夹一个私网"这类用例可以稳定复现，不依赖真实 DNS。
     */
    @FunctionalInterface
    interface HostResolver {

        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * 完整校验：语法 + userinfo + 元数据主机名 + 解析结果。
     * <b>阻塞</b>（要解析 DNS），调用方必须调度到 {@code boundedElastic} 上执行。
     */
    public static String requireSafe(String url, boolean allowPrivateAddresses) {
        return requireSafe(url, allowPrivateAddresses, InetAddress::getAllByName);
    }

    static String requireSafe(String url, boolean allowPrivateAddresses, HostResolver hostResolver) {
        String normalized = requireWellFormed(url);
        String host = URI.create(normalized).getHost();

        if (canonicalHost(host).endsWith(INTERNAL_TLD_SUFFIX) && !allowPrivateAddresses) {
            throw new IllegalArgumentException("baseUrl是私网专用域名，当前未放行私网：" + host);
        }

        InetAddress[] resolved;
        try {
            resolved = hostResolver.resolve(host);
        } catch (UnknownHostException ex) {
            // 解析不了就无从判断是不是内网地址；"证明不了安全"按不安全处理（fail closed）
            throw new IllegalArgumentException("baseUrl主机名无法解析：" + host, ex);
        }
        for (InetAddress address : resolved) {
            if (isBlocked(address, allowPrivateAddresses)) {
                throw new IllegalArgumentException("baseUrl解析到禁止访问的地址 " + address.getHostAddress()
                        + "（主机 " + host + "）");
            }
        }
        return normalized;
    }

    /**
     * 不碰 DNS 的廉价防线：语法层 + userinfo + 元数据主机名。
     * <p>
     * 供热路径与"写入口已经完整校验过"的调用方使用（每请求零 DNS 开销）。
     * 它拦不住"域名解析到内网"这一层，那层由 {@link #requireSafe} 在写入口与启动期兜住。
     */
    public static String requireWellFormed(String url) {
        String normalized = UpstreamUrlSupport.requireHttpBaseUrl(url);
        URI uri = URI.create(normalized);
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl不允许携带 userinfo（user:pass@host）");
        }
        String host = uri.getHost();
        if (METADATA_HOSTNAMES.contains(canonicalHost(host))) {
            throw new IllegalArgumentException("baseUrl指向云元数据主机，禁止访问：" + host);
        }
        return normalized;
    }

    /**
     * 单条地址判定：{@code true} 表示拒绝。
     */
    static boolean isBlocked(InetAddress address, boolean allowPrivateAddresses) {
        InetAddress target = unwrapIpv4Mapped(address);
        // 0.0.0.0 / :: —— Linux 上连 0.0.0.0 实际就是连本机
        if (target.isAnyLocalAddress()) {
            return true;
        }
        if (target.isMulticastAddress()) {
            return true;
        }
        // 私网与回环是同一个开关的两面：本地把上游指到 localhost 是 dev 的正常用法
        if (target.isLoopbackAddress() || target.isSiteLocalAddress()) {
            return !allowPrivateAddresses;
        }
        // 链路本地恒拒：169.254.169.254 就是云元数据端点，没有任何理由放行
        if (target.isLinkLocalAddress()) {
            return true;
        }
        return isInReservedRange(target);
    }

    /**
     * 解包 IPv4-mapped IPv6（{@code ::ffff:a.b.c.d}）。非该形态原样返回。
     */
    static InetAddress unwrapIpv4Mapped(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] bytes = address.getAddress();
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return address;
            }
        }
        if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) {
            return address;
        }
        try {
            return InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
        } catch (UnknownHostException ex) {
            // 长度固定为 4，走到这里不可能；保守起见按原地址继续判定
            return address;
        }
    }

    /**
     * JDK 的 {@code isXxxAddress()} 覆盖不到的保留网段。
     * <p>
     * 逐条说明为什么留着：{@code 0.0.0.0/8} 是"本网络"（Linux 上路由到本机）；
     * {@code 100.64.0.0/10} 是运营商级 NAT，阿里云元数据 {@code 100.100.100.200} 落在里面；
     * {@code 192.0.0.0/24}、{@code 198.18.0.0/15}、{@code 240.0.0.0/4} 是 IETF 保留，
     * 现实中不存在合法上游；{@code fc00::/7} 是 IPv6 唯一本地地址（JDK 的
     * {@code isSiteLocalAddress} 只认已废弃的 {@code fec0::/10}）；{@code 2001:db8::/32} 是文档网段。
     */
    private static boolean isInReservedRange(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            int b2 = bytes[2] & 0xFF;
            return b0 == 0
                    || (b0 == 100 && (b1 & 0xC0) == 64)
                    || (b0 == 192 && b1 == 0 && b2 == 0)
                    || (b0 == 198 && (b1 & 0xFE) == 18)
                    || (b0 & 0xF0) == 240;
        }
        int h0 = bytes[0] & 0xFF;
        int h1 = bytes[1] & 0xFF;
        return (h0 & 0xFE) == 0xFC
                || (h0 == 0x20 && h1 == 0x01 && (bytes[2] & 0xFF) == 0x0D && (bytes[3] & 0xFF) == 0xB8);
    }

    /**
     * 主机名归一化：大小写不敏感，尾部点等价（{@code metadata.google.internal.} 与
     * {@code metadata.google.internal} 是同一个名字）。
     */
    private static String canonicalHost(String host) {
        String canonical = host.toLowerCase(Locale.ROOT);
        while (canonical.endsWith(".")) {
            canonical = canonical.substring(0, canonical.length() - 1);
        }
        return canonical;
    }
}
