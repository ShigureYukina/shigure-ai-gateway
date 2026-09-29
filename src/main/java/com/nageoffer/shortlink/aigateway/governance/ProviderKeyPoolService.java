package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayUpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 渠道 Key 池：同一渠道多把 Key 轮换，并把"打坏的 Key"暂时摘出去。
 * <p>
 * 为什么需要：单 Key 一旦被限流或额度耗尽，整个渠道就只剩等待。
 * 多把 Key + 熔断降级，能把"这个 Key 不行"和"这个渠道不行"区分开——
 * 前者换一把继续，后者才走通道回退。
 * <p>
 * 熔断状态放在本实例内存里（各实例独立计数）。这是刻意的：选 Key 在请求热路径上，
 * 为它引入一次跨实例的同步往返得不偿失；代价是某个 Key 被限流后，
 * 其他实例最多在冷静期结束后、各自重新踩一次坑。
 */
@Slf4j
@Service
public class ProviderKeyPoolService {

    /**
     * 连续失败到该次数就冷却该 Key。
     */
    private static final int FAILURE_THRESHOLD = 2;

    /**
     * 冷却时长：足够让限流窗口过去，又不至于长期少一把 Key。
     */
    private static final long COOLDOWN_MILLIS = 60_000L;

    private final Map<String, KeyState> keyStates = new ConcurrentHashMap<>();

    /**
     * 从候选 Key 中加权随机挑一把。<b>优先跳过冷却中的 Key</b>；
     * 全都冷却时退化为在冷却池里挑，避免整个渠道被误判为不可用。
     */
    public AiGatewayProperties.ProviderApiKey select(String tenantId,
                                                     String provider,
                                                     List<AiGatewayProperties.ProviderApiKey> candidates) {
        List<AiGatewayProperties.ProviderApiKey> usable = new ArrayList<>();
        if (candidates != null) {
            for (AiGatewayProperties.ProviderApiKey candidate : candidates) {
                if (candidate != null && candidate.isEnabled() && StringUtils.hasText(candidate.getApiKey())) {
                    usable.add(candidate);
                }
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        List<AiGatewayProperties.ProviderApiKey> healthy = usable.stream()
                .filter(key -> !isCooling(stateKey(tenantId, provider, keyIdOf(key))))
                .toList();
        return weightedPick(healthy.isEmpty() ? usable : healthy);
    }

    /**
     * 一把 Key 的标识：优先用配置里的 keyId，缺省用 Key 的短哈希——
     * 既能在日志里定位，又不会把 Key 本身写进日志。
     */
    public String keyIdOf(AiGatewayProperties.ProviderApiKey key) {
        if (key == null) {
            return null;
        }
        if (StringUtils.hasText(key.getKeyId())) {
            return key.getKeyId().trim();
        }
        String apiKey = key.getApiKey();
        return apiKey == null ? null : "key-" + Integer.toHexString(apiKey.hashCode());
    }

    public void reportSuccess(String tenantId, String provider, String keyId) {
        KeyState state = keyStates.get(stateKey(tenantId, provider, keyId));
        if (state != null) {
            state.reset();
        }
    }

    /**
     * 上报一次失败。只有"换一把 Key 可能就好了"的失败才计数：
     * 参数不对、模型不存在这类问题，换 Key 一样会失败，不该把好 Key 关进冷却。
     */
    public void reportFailure(String tenantId, String provider, String keyId, Throwable error) {
        if (!StringUtils.hasText(keyId) || !isKeyRelated(error)) {
            return;
        }
        KeyState state = keyStates.computeIfAbsent(stateKey(tenantId, provider, keyId), key -> new KeyState());
        int failures = state.recordFailure();
        if (failures >= FAILURE_THRESHOLD) {
            log.warn("provider key cooling down: provider={}, keyId={}, failures={}, reason={}",
                    provider, keyId, failures, error == null ? null : error.getMessage());
        }
    }

    /**
     * 观察用：当前各 Key 的失败次数与冷却截止时间。
     */
    public Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        keyStates.forEach((key, state) -> {
            if (state.failureCount() == 0) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("failures", state.failureCount());
            row.put("cooling", state.isCooling());
            row.put("coolingUntil", state.coolingUntil());
            result.put(key, row);
        });
        return result;
    }

    private boolean isCooling(String stateKey) {
        KeyState state = keyStates.get(stateKey);
        return state != null && state.isCooling();
    }

    private AiGatewayProperties.ProviderApiKey weightedPick(List<AiGatewayProperties.ProviderApiKey> keys) {
        int total = keys.stream().mapToInt(key -> Math.max(key.getWeight() == null ? 1 : key.getWeight(), 1)).sum();
        int cursor = ThreadLocalRandom.current().nextInt(total);
        for (AiGatewayProperties.ProviderApiKey key : keys) {
            cursor -= Math.max(key.getWeight() == null ? 1 : key.getWeight(), 1);
            if (cursor < 0) {
                return key;
            }
        }
        return keys.get(keys.size() - 1);
    }

    private String stateKey(String tenantId, String provider, String keyId) {
        return (StringUtils.hasText(tenantId) ? tenantId : "*") + "|" + provider + "|" + keyId;
    }

    /**
     * 是否属于"这把 Key 的问题"。
     */
    private boolean isKeyRelated(Throwable error) {
        if (error instanceof AiGatewayUpstreamException upstreamException) {
            Integer status = upstreamException.getStatus();
            if (status == null) {
                return true;
            }
            return status == 401 || status == 403 || status == 408 || status == 429 || status >= 500;
        }
        if (error instanceof AiGatewayClientException clientException) {
            AiGatewayErrorCode code = clientException.getErrorCode();
            return code == AiGatewayErrorCode.UPSTREAM_CREDENTIAL_MISSING
                    || code == AiGatewayErrorCode.UPSTREAM_RETRY_EXHAUSTED
                    || code == AiGatewayErrorCode.PROVIDER_RATE_LIMITED;
        }
        return true;
    }

    /**
     * 单把 Key 的运行状态。
     */
    private static final class KeyState {

        private int failureCount;

        private long coolingUntilMillis;

        private synchronized int recordFailure() {
            failureCount++;
            if (failureCount >= FAILURE_THRESHOLD) {
                coolingUntilMillis = System.currentTimeMillis() + COOLDOWN_MILLIS;
            }
            return failureCount;
        }

        private synchronized void reset() {
            failureCount = 0;
            coolingUntilMillis = 0L;
        }

        private synchronized boolean isCooling() {
            return coolingUntilMillis > System.currentTimeMillis();
        }

        private synchronized int failureCount() {
            return failureCount;
        }

        private synchronized long coolingUntil() {
            return coolingUntilMillis;
        }
    }
}
