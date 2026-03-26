package com.nageoffer.shortlink.aigateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.shortlink.aigateway.dto.req.ProviderModelListReqDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/providers")
@Tag(name = "模型发现", description = "通过 API 地址与 Key 获取模型列表")
public class AiProviderController {

    private final WebClient aiGatewayWebClient;

    @Operation(summary = "获取模型列表", description = "调用上游 /v1/models 并返回模型 ID 列表")
    @PostMapping("/models")
    public Mono<ResponseEntity<Map<String, Object>>> listModels(@Valid @RequestBody ProviderModelListReqDTO requestParam) {
        String normalizedBaseUrl;
        try {
            normalizedBaseUrl = normalizeBaseUrl(requestParam.getBaseUrl());
        } catch (IllegalArgumentException ex) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    "message", ex.getMessage(),
                    "models", List.of()
            )));
        }

        return aiGatewayWebClient.get()
                .uri(normalizedBaseUrl + "/v1/models")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + requestParam.getApiKey())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::toSuccessResponse)
                .onErrorResume(WebClientResponseException.class, ex -> Mono.just(
                        ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                                "message", "上游响应错误: " + ex.getStatusCode().value(),
                                "detail", ex.getResponseBodyAsString(),
                                "models", List.of()
                        ))
                ))
                .onErrorResume(ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                                "message", "调用上游失败: " + ex.getMessage(),
                                "models", List.of()
                        ))
                ));
    }

    private ResponseEntity<Map<String, Object>> toSuccessResponse(JsonNode responseNode) {
        List<String> models = new ArrayList<>();
        JsonNode dataNode = responseNode.path("data");
        if (dataNode.isArray()) {
            for (JsonNode modelNode : dataNode) {
                String modelId = modelNode.path("id").asText(null);
                if (modelId != null && !modelId.isBlank()) {
                    models.add(modelId);
                }
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("models", models);
        body.put("count", models.size());
        body.put("raw", responseNode);
        return ResponseEntity.ok(body);
    }

    private String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("baseUrl不能为空");
        }
        URI uri = URI.create(trimmed);
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("baseUrl必须是http或https地址");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUrl缺少主机名");
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
