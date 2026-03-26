package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.observability.BillingExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/billing")
@Tag(name = "账单导出", description = "AI 调用账单与成本导出")
public class AiBillingController {

    private final BillingExportService billingExportService;

    @Operation(summary = "导出账单 CSV", description = "按日期范围导出调用账单")
    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                         @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        String csv = billingExportService.exportCsv(startDate, endDate);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ai-billing.csv")
                .contentType(MediaType.TEXT_PLAIN)
                .body(csv);
    }
}
