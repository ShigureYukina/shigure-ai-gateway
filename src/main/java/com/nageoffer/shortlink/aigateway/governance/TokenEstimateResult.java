package com.nageoffer.shortlink.aigateway.governance;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenEstimateResult {

    private long inputEstimated;

    private long outputReserve;

    public long totalReserve() {
        return inputEstimated + outputReserve;
    }
}
