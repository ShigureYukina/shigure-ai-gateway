package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "short-link.ai-gateway.cache", name = "semantic-cache-enabled", havingValue = "true")
public class RedisSemanticCacheService implements SemanticCacheService {

    private static final String SEMANTIC_CACHE_PREFIX = "short-link:ai-gateway:semantic:";

    private static final String SEMANTIC_INDEX_PREFIX = "short-link:ai-gateway:semantic:index:";

    private static final String INDEX_SEPARATOR = "::";

    private final StringRedisTemplate stringRedisTemplate;

    private final AiGatewayProperties properties;

    @Override
    public Optional<String> find(String provider, String model, AiChatCompletionReqDTO request) {
        if (!properties.getCache().isSemanticCacheEnabled()) {
            return Optional.empty();
        }
        String normalizedContent = normalizeContent(request);
        if (isBlank(provider) || isBlank(model) || normalizedContent.isBlank()) {
            return Optional.empty();
        }
        String contentHash = hashContent(normalizedContent);
        String exactCacheKey = buildCacheKey(provider, model, contentHash);
        String exactMatch = stringRedisTemplate.opsForValue().get(exactCacheKey);
        if (exactMatch != null) {
            return Optional.of(exactMatch);
        }

        String indexKey = buildIndexKey(provider, model);
        Set<String> indexedEntries = stringRedisTemplate.opsForZSet().reverseRange(indexKey, 0, -1);
        if (indexedEntries == null || indexedEntries.isEmpty()) {
            return Optional.empty();
        }

        double threshold = resolveThreshold();
        Set<String> requestTrigrams = buildTrigrams(normalizedContent);
        for (String indexedEntry : indexedEntries) {
            IndexedContent indexedContent = parseIndexedContent(indexedEntry);
            if (indexedContent == null || indexedContent.normalizedContent().isBlank()) {
                continue;
            }
            double similarity = calculateJaccardSimilarity(requestTrigrams, buildTrigrams(indexedContent.normalizedContent()));
            if (similarity < threshold) {
                continue;
            }
            String response = stringRedisTemplate.opsForValue().get(buildCacheKey(provider, model, indexedContent.contentHash()));
            if (response != null) {
                return Optional.of(response);
            }
            stringRedisTemplate.opsForZSet().remove(indexKey, indexedEntry);
        }
        return Optional.empty();
    }

    @Override
    public void put(String provider, String model, AiChatCompletionReqDTO request, String response) {
        if (!properties.getCache().isSemanticCacheEnabled()) {
            return;
        }
        String normalizedContent = normalizeContent(request);
        if (isBlank(provider) || isBlank(model) || normalizedContent.isBlank() || isBlank(response)) {
            return;
        }
        Duration ttl = properties.getCache().getTtl();
        String contentHash = hashContent(normalizedContent);
        String exactCacheKey = buildCacheKey(provider, model, contentHash);
        String indexKey = buildIndexKey(provider, model);
        long now = System.currentTimeMillis();

        stringRedisTemplate.opsForValue().set(exactCacheKey, response, ttl);
        stringRedisTemplate.opsForZSet().add(indexKey, toIndexedValue(contentHash, normalizedContent), now);
        stringRedisTemplate.expire(indexKey, ttl);
    }

    private String normalizeContent(AiChatCompletionReqDTO request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        return request.getMessages().stream()
                .filter(Objects::nonNull)
                .filter(message -> "user".equalsIgnoreCase(message.getRole()))
                .map(AiChatCompletionMessage::getContent)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(content -> !content.isBlank())
                .map(content -> content.toLowerCase().replaceAll("\\s+", " "))
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private String buildCacheKey(String provider, String model, String contentHash) {
        return SEMANTIC_CACHE_PREFIX + provider + ":" + model + ":" + contentHash;
    }

    private String buildIndexKey(String provider, String model) {
        return SEMANTIC_INDEX_PREFIX + provider + ":" + model;
    }

    private String hashContent(String normalizedContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(normalizedContent.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    private String toIndexedValue(String contentHash, String normalizedContent) {
        String encodedContent = Base64.getUrlEncoder().encodeToString(normalizedContent.getBytes(StandardCharsets.UTF_8));
        return contentHash + INDEX_SEPARATOR + encodedContent;
    }

    private IndexedContent parseIndexedContent(String indexedEntry) {
        if (isBlank(indexedEntry)) {
            return null;
        }
        int separatorIndex = indexedEntry.indexOf(INDEX_SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex + INDEX_SEPARATOR.length() >= indexedEntry.length()) {
            return null;
        }
        String contentHash = indexedEntry.substring(0, separatorIndex);
        String encodedContent = indexedEntry.substring(separatorIndex + INDEX_SEPARATOR.length());
        try {
            String normalizedContent = new String(Base64.getUrlDecoder().decode(encodedContent), StandardCharsets.UTF_8);
            return new IndexedContent(contentHash, normalizedContent);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Set<String> buildTrigrams(String normalizedContent) {
        if (normalizedContent == null || normalizedContent.isBlank()) {
            return Collections.emptySet();
        }
        if (normalizedContent.length() < 3) {
            return Set.of(normalizedContent);
        }
        Set<String> trigrams = new HashSet<>();
        for (int index = 0; index <= normalizedContent.length() - 3; index++) {
            trigrams.add(normalizedContent.substring(index, index + 3));
        }
        return trigrams;
    }

    private double calculateJaccardSimilarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1D;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 0D;
        }
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        if (union.isEmpty()) {
            return 0D;
        }
        return (double) intersection.size() / union.size();
    }

    private double resolveThreshold() {
        Double threshold = properties.getCache().getSemanticSimilarityThreshold();
        if (threshold == null) {
            return 0.85D;
        }
        return Math.max(0D, Math.min(1D, threshold));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record IndexedContent(String contentHash, String normalizedContent) {
    }
}
