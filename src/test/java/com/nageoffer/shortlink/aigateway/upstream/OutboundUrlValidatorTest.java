package com.nageoffer.shortlink.aigateway.upstream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 出站地址安全校验（SSRF）。
 * <p>
 * 用例只用<b>字面量地址</b>或注入的 DNS 解析结果，不依赖真实 DNS：
 * 要证明的是"判定逻辑对各类绕过手法是否成立"，而不是"某天某域名的 A 记录是什么"。
 * <p>
 * 判定表（{@code allowPrivate=false} 是 prod 姿态，{@code true} 是 dev 默认）：
 * <pre>
 *   地址                          允许私网   拒绝私网
 *   8.8.8.8（公网）               放行       放行
 *   127.0.0.1 / 10.x / 192.168.x  放行       拒
 *   169.254.169.254（云元数据）    拒         拒
 *   100.64.x / 0.x / 240.x        拒         拒
 *   fc00::1 / 2001:db8::1         拒         拒
 *   metadata.* / user:pw@         拒         拒   ← 与开关无关
 * </pre>
 */
class OutboundUrlValidatorTest {

    @Test
    void shouldAllowPublicAddressRegardlessOfTheSwitch() {
        Assertions.assertDoesNotThrow(() -> OutboundUrlValidator.requireSafe("https://8.8.8.8/v1", false));
        Assertions.assertDoesNotThrow(() -> OutboundUrlValidator.requireSafe("https://8.8.8.8/v1", true));
    }

    @Test
    void shouldRejectPrivateAndLoopbackWhenPrivateAddressesAreNotAllowed() {
        for (String host : new String[]{"127.0.0.1", "10.1.1.1", "172.16.0.1", "192.168.1.1"}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> OutboundUrlValidator.requireSafe("http://" + host + ":11434/v1", false),
                    host + " 属于私网/回环，prod 姿态下必须拒");
        }
    }

    @Test
    void shouldAllowPrivateAndLoopbackWhenPrivateAddressesAreAllowed() {
        // dev 的真实用法：本地把上游指向 Ollama / vLLM
        Assertions.assertDoesNotThrow(() -> OutboundUrlValidator.requireSafe("http://127.0.0.1:11434/v1", true));
        Assertions.assertDoesNotThrow(() -> OutboundUrlValidator.requireSafe("http://10.1.1.1:8000/v1", true));
    }

    @Test
    void shouldAlwaysRejectLinkLocalMetadataAddress() {
        // 169.254.169.254 是 AWS/Azure/GCP 共用的元数据端点。
        // 放行私网是为了本地调试，不是为了让网关能读实例凭证 —— 这个开关对它无效。
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("http://169.254.169.254/latest/meta-data", false));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("http://169.254.169.254/latest/meta-data", true));
    }

    @Test
    void shouldAlwaysRejectCgnatAndReservedRanges() {
        // 100.64.0.0/10 覆盖阿里云元数据 100.100.100.200；
        // 其余是 IETF 保留段，现实中不存在合法 LLM 上游。
        for (String host : new String[]{"100.64.0.1", "100.100.100.200", "192.0.0.1", "198.18.0.1", "240.0.0.1"}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> OutboundUrlValidator.requireSafe("http://" + host + "/v1", false), host);
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> OutboundUrlValidator.requireSafe("http://" + host + "/v1", true),
                    host + " 恒拒，与开关无关");
        }
    }

    @Test
    void shouldRejectAnyLocalAddressBecauseItRoutesToLocalhost() {
        // Linux 上连 0.0.0.0 实际就是连本机，是最容易漏掉的一条
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("http://0.0.0.0:8080/v1", true));
    }

    @Test
    void shouldRejectIpv4MappedPrivateAddress() {
        // ::ffff:10.0.0.1 是绕过 IP 判定的标准手法：JDK 里它是 Inet6Address，
        // isSiteLocalAddress() 返回 false，必须显式解包。这两种断言在
        // "JDK 保留为 Inet6Address" 与 "JDK 归一化成 Inet4Address" 两种行为下都成立。
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("http://[::ffff:10.0.0.1]/v1", false));
        Assertions.assertDoesNotThrow(() -> OutboundUrlValidator.requireSafe("http://[::ffff:10.0.0.1]/v1", true),
                "解包后就是 10.0.0.1，属于'私网'那一类，dev 放行");
    }

    @Test
    void shouldUnwrapIpv4MappedAddressToInet4Address() throws UnknownHostException {
        // 构造而非解析：确保拿到的一定是 Inet6Address，才能验证解包本身
        InetAddress mapped = Inet6Address.getByAddress(null, new byte[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 10, 0, 0, 1}, -1);
        Assertions.assertInstanceOf(Inet6Address.class, mapped);

        InetAddress unwrapped = OutboundUrlValidator.unwrapIpv4Mapped(mapped);
        Assertions.assertInstanceOf(Inet4Address.class, unwrapped);
        Assertions.assertEquals("10.0.0.1", unwrapped.getHostAddress());
        // 这就是绕过点：不显式解包的话，这条 10.x 地址在 JDK 眼里"什么都不是"
        Assertions.assertFalse(mapped.isSiteLocalAddress(), "Inet6Address 不认 IPv4-mapped 里的私网段");
        Assertions.assertFalse(mapped.isLoopbackAddress());
        // isBlocked 内部先解包，所以仍然拦得住
        Assertions.assertTrue(OutboundUrlValidator.isBlocked(mapped, false));
        Assertions.assertFalse(OutboundUrlValidator.isBlocked(mapped, true), "解包后属私网，dev 放行");
    }

    @Test
    void shouldRejectIpv6UniqueLocalLinkLocalAndDocumentationRanges() {
        for (String host : new String[]{"fc00::1", "fd00::1", "fe80::1", "2001:db8::1"}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> OutboundUrlValidator.requireSafe("http://[" + host + "]/v1", true), host);
        }
    }

    @Test
    void shouldRejectNonIpv4MappedIpv6AddressWithoutUnwrapping() throws UnknownHostException {
        // 非 ::ffff: 前缀的 v6 地址不能被"误解包"（前 10 字节非 0 就要原样返回）
        InetAddress globalUnicast = InetAddress.getByName("2606:4700::1111");
        Assertions.assertSame(globalUnicast, OutboundUrlValidator.unwrapIpv4Mapped(globalUnicast));
        Assertions.assertFalse(OutboundUrlValidator.isBlocked(globalUnicast, false));
    }

    @Test
    void shouldRejectUserInfoBecauseItCanDisguiseTheHost() {
        // http://api.openai.com@127.0.0.1 的 host 其实是 127.0.0.1，人和正则都容易看错
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("http://user:pw@8.8.8.8/v1", true));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("http://api.openai.com@127.0.0.1/v1", true));
    }

    @Test
    void shouldRejectMetadataHostnamesRegardlessOfTheSwitch() {
        // 主机名黑名单在解析之前就拦下了，所以这几条不依赖 DNS
        for (String host : new String[]{"metadata", "metadata.google.internal", "instance-data",
                "instance-data.ec2.internal"}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> OutboundUrlValidator.requireSafe("http://" + host + "/v1", true), host);
        }
    }

    @Test
    void shouldRejectMetadataHostnameWrittenWithATrailingDot() {
        // metadata.google.internal. 与不带点的是同一个名字，归一化必须覆盖
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("http://metadata.google.internal./v1", true));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("http://METADATA.Google.Internal/v1", true),
                "主机名判定大小写不敏感");
    }

    @Test
    void shouldRejectNonHttpScheme() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("ftp://8.8.8.8/v1", true));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("file:///etc/passwd", true));
    }

    @Test
    void shouldRejectHostThatResolvesToMixedPublicAndPrivateRecords() {
        // 标准绕过手法：一条 A 记录指公网骗过校验，另一条指内网。
        // 只看第一条的实现会放行，所以必须逐条判定、任一命中即拒。
        String url = "http://mixed.example.com/v1";
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe(url, false, hostResolver("8.8.8.8", "127.0.0.1")));
    }

    @Test
    void shouldRejectMixedRecordsEvenWhenPrivateAddressesAreAllowed() {
        // 换成链路本地：这类地址恒拒，所以"任一命中即拒"在放行私网时同样成立
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe(
                        "http://mixed.example.com/v1", true, hostResolver("8.8.8.8", "169.254.169.254")));
    }

    @Test
    void shouldRejectUnresolvableHostBecauseItCannotBeProvenSafe() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe("http://no-such-host.example/v1", true,
                        unresolvableResolver()));

        Assertions.assertTrue(ex.getMessage().contains("无法解析"), ex.getMessage());
        // 启动期校验器据此把"解析失败"与"地址危险"分开处理（前者在 prod 也只 warn）
        Assertions.assertInstanceOf(UnknownHostException.class, ex.getCause());
    }

    @Test
    void shouldGateInternalTldOnThePrivateSwitch() {
        // *.internal 是私网专用 TLD，只可能解析到内网地址 —— 归到"私网"那一桶，
        // 而不是像云元数据那样与开关无关地硬拒（否则 on-prem 配 ollama.internal 无解）
        String url = "http://ollama.internal/v1";
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireSafe(url, false, hostResolver("8.8.8.8")));
        Assertions.assertDoesNotThrow(
                () -> OutboundUrlValidator.requireSafe(url, true, hostResolver("8.8.8.8")));
    }

    @Test
    void shouldNotTouchDnsInTheCheapLayer() {
        // 廉价层是给热路径用的（每个请求一次 DNS 不可接受），所以它必须"看起来合法就放行"。
        // 用 .invalid 保留域名保证真实 DNS 永远解析不了 —— 若这里发起了 DNS，这条用例就会失败。
        Assertions.assertDoesNotThrow(
                () -> OutboundUrlValidator.requireWellFormed("http://no-such-host.invalid:8080/v1"));
        // 但硬规则仍然生效
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireWellFormed("http://metadata.google.internal/v1"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlValidator.requireWellFormed("http://user:pw@8.8.8.8/v1"));
    }

    @Test
    void shouldNormalizeUrlByTrimmingTrailingSlash() {
        Assertions.assertEquals("https://8.8.8.8/v1",
                OutboundUrlValidator.requireSafe("  https://8.8.8.8/v1/  ", false));
        Assertions.assertEquals("http://127.0.0.1:11434",
                OutboundUrlValidator.requireWellFormed("http://127.0.0.1:11434/"));
    }

    /**
     * 注入固定解析结果：多 A 记录这类用例必须可稳定复现，不能依赖真实 DNS。
     */
    private static OutboundUrlValidator.HostResolver hostResolver(String... literals) {
        return host -> {
            InetAddress[] resolved = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                resolved[i] = InetAddress.getByName(literals[i]);
            }
            return resolved;
        };
    }

    private static OutboundUrlValidator.HostResolver unresolvableResolver() {
        return host -> {
            throw new UnknownHostException(host);
        };
    }
}
