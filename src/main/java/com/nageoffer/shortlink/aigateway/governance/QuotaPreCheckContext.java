package com.nageoffer.shortlink.aigateway.governance;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuotaPreCheckContext {

    private String quotaKey;

    private String tenantId;

    private String appId;

    private String provider;

    private String providerModel;

    private long reservedTokens;

    private long minuteQuota;

    private long dayQuota;

    /** 预扣完成后的分钟窗口用量，用于在预检时刻组装限流响应头（结算后再记已赶不上响应提交） */
    private long minuteUsedAfterReserve;

    private long dayUsedAfterReserve;

    private long monthQuota;

    private String minuteKey;

    private String dayKey;

    private String monthKey;
}
