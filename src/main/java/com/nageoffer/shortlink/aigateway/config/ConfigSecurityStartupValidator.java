package com.nageoffer.shortlink.aigateway.config;

import com.nageoffer.shortlink.aigateway.upstream.OutboundUrlValidator;
import com.nageoffer.shortlink.aigateway.upstream.UpstreamUrlSupport;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动期的出站地址校验（SSRF）。
 * <p>
 * 为什么需要它：{@code provider-base-url} / {@code provider-chat-path} / {@code sync.price.url}
 * 都只来自 yml，<b>没有写入口</b>，所以运行期的写入口校验（见 {@code ProviderRoutingService}）
 * 管不到它们。数据面虽然也会在拼地址时拦一道，但那是"每个请求都发现一次配置是错的"，
 * 而且分不清"配置错"和"上游挂"（都表现为 502）。启动时集中报一次，运维才知道要改哪个键。
 * <p>
 * <b>prod 抛、非 prod 只 warn</b>：prod 带一个指向内网/元数据的上游地址就是可被利用的跳板，
 * 宁可起不来；dev 里改 yml 试错是常态，不该因为一个还没配好的渠道就卡死启动。
 * <p>
 * <b>一处例外</b>：解析失败（DNS 不可用 / 域名拼错）在 prod 也<b>只 warn</b>。
 * "解析不了"不等于"不安全"，而启动瞬间的 DNS 抖动会把一次网络抖动放大成一次全站不可用 ——
 * 数据面在这个域名上失败仍然是 502，并不会变成安全事件。真正危险的形态
 * （内网地址、元数据主机名、userinfo）走的都是不依赖 DNS 的判定，prod 一律硬拒。
 * <p>
 * <b>dev 默认不下探 DNS</b>：{@code allow-private-addresses=true} 时，解析结果里唯一还会被拒的
 * 只剩链路本地/多播/CGNAT/保留网段，这些地址没有任何合法上游会用，运行期也照样拒。
 * 为了这点收益让每次本地启动（含全部上下文测试）都打一轮 DNS 不划算，
 * 因此这一档只跑不碰 DNS 的 {@link OutboundUrlValidator#requireWellFormed}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigSecurityStartupValidator {

    private final AiGatewayProperties properties;

    private final Environment environment;

    @PostConstruct
    void validate() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        boolean allowPrivateAddresses = properties.getSecurity().getSsrf().isAllowPrivateAddresses();
        // 只有"私网不放行"或 prod 时，DNS 判定才可能改变结论
        boolean resolveDns = prod || !allowPrivateAddresses;

        Map<String, String> candidates = new LinkedHashMap<>();
        properties.getUpstream().getProviderBaseUrl().forEach((provider, baseUrl) ->
                candidates.put("upstream.provider-base-url." + provider, baseUrl));
        properties.getUpstream().getProviderChatPath().forEach((provider, chatPath) -> {
            String baseUrl = properties.getUpstream().getProviderBaseUrl().get(provider);
            if (StringUtils.hasText(baseUrl)) {
                candidates.put("upstream.provider-chat-path." + provider,
                        UpstreamUrlSupport.join(baseUrl, chatPath));
            }
        });
        candidates.put("sync.price.url", properties.getSync().getPrice().getUrl());

        candidates.forEach((key, url) -> check(key, url, resolveDns, allowPrivateAddresses, prod));
    }

    private void check(String key, String url, boolean resolveDns, boolean allowPrivateAddresses, boolean prod) {
        if (!StringUtils.hasText(url)) {
            return;
        }
        try {
            if (resolveDns) {
                OutboundUrlValidator.requireSafe(url, allowPrivateAddresses);
            } else {
                OutboundUrlValidator.requireWellFormed(url);
            }
        } catch (IllegalArgumentException ex) {
            if (ex.getCause() instanceof UnknownHostException) {
                log.warn("{} 无法解析（{}），请确认 DNS 可用且域名拼写正确；"
                        + "数据面对该地址的请求会失败，但不影响其他渠道", key, ex.getMessage());
                return;
            }
            if (prod) {
                throw new IllegalStateException("出站地址校验未通过，" + key + "：" + ex.getMessage()
                        + "；如果这是内网自建上游，请显式设置 security.ssrf.allow-private-addresses=true", ex);
            }
            log.warn("出站地址校验未通过，{}：{}（非 prod 仅告警，prod 会拒绝启动）", key, ex.getMessage());
        }
    }
}
