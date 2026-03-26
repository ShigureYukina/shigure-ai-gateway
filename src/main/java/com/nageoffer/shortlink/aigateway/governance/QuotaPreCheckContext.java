package com.nageoffer.shortlink.aigateway.governance;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuotaPreCheckContext {

    private String quotaKey;

    private long reservedTokens;

    private long minuteQuota;

    private long dayQuota;

    private long monthQuota;

    private String minuteKey;

    private String dayKey;

    private String monthKey;
}
