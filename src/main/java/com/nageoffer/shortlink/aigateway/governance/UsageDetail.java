package com.nageoffer.shortlink.aigateway.governance;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UsageDetail {

    private Long promptTokens;

    private Long completionTokens;

    private Long totalTokens;
}
