package com.nageoffer.shortlink.aigateway.audit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class AuditLogServiceTest {

    @Test
    void shouldRecordListAndClearAuditLogs() {
        AuditLogService service = new AuditLogService();

        service.record("u1", "admin", "UPDATE", "/v1/security/config", true, "ok");
        service.record("u2", "viewer", "READ", "/v1/routing/config", true, "ok");

        List<AuditLogEntry> list = service.list(10);
        Assertions.assertEquals(2, list.size());
        Assertions.assertEquals("u2", list.get(0).getActor());
        Assertions.assertEquals("u1", list.get(1).getActor());

        List<AuditLogEntry> normalized = service.list(0);
        Assertions.assertEquals(1, normalized.size());

        service.clear();
        Assertions.assertTrue(service.list(10).isEmpty());
    }

    @Test
    void shouldKeepOnlyLatest2000Logs() {
        AuditLogService service = new AuditLogService();
        for (int i = 0; i < 2105; i++) {
            service.record("u-" + i, "admin", "ACT", "/x", true, "d");
        }

        List<AuditLogEntry> all = service.list(500);
        Assertions.assertEquals(500, all.size());
        Assertions.assertEquals("u-2104", all.get(0).getActor());
    }
}
