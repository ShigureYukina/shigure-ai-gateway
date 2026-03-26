package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.observability.ModelMetricsSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/metrics")
@Tag(name = "观测指标", description = "模型维度聚合指标查询")
public class AiObservabilityController {

    private final AiGatewayMetricsRecorder aiGatewayMetricsRecorder;

    @Operation(summary = "查询模型当前小时指标", description = "返回调用量、成功率、P95 延迟、成本")
    @GetMapping("/models/{model}")
    public ModelMetricsSnapshot currentHourModelMetrics(@PathVariable("model") String model) {
        return aiGatewayMetricsRecorder.currentHourSnapshot(model);
    }
}
