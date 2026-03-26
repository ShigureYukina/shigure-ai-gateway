package com.nageoffer.shortlink.aigateway.audit;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class AuditLogService {

    private final ConcurrentLinkedDeque<AuditLogEntry> logs = new ConcurrentLinkedDeque<>();

    public void record(String actor, String role, String action, String target, boolean success, String detail) {
        logs.addFirst(AuditLogEntry.builder()
                .timestamp(Instant.now())
                .actor(actor)
                .role(role)
                .action(action)
                .target(target)
                .success(success)
                .detail(detail)
                .build());
        while (logs.size() > 2000) {
            logs.pollLast();
        }
    }

    public List<AuditLogEntry> list(int limit) {
        int normalized = Math.min(Math.max(limit, 1), 500);
        List<AuditLogEntry> result = new ArrayList<>(normalized);
        int i = 0;
        for (AuditLogEntry each : logs) {
            if (i++ >= normalized) {
                break;
            }
            result.add(each);
        }
        return result;
    }

    public void clear() {
        logs.clear();
    }
}
