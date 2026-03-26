package com.nageoffer.shortlink.aigateway.audit;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AuditLogEntry {

    private Instant timestamp;

    private String actor;

    private String role;

    private String action;

    private String target;

    private Boolean success;

    private String detail;
}
