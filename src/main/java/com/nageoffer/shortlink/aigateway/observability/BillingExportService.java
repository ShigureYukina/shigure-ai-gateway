package com.nageoffer.shortlink.aigateway.observability;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BillingExportService {

    private static final String CALL_KEY_PREFIX = "short-link:ai-gateway:call:";

    private final StringRedisTemplate stringRedisTemplate;

    public String exportCsv(LocalDate startDate, LocalDate endDate) {
        long startMillis = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMillis = endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        List<AiCallRecord> records = new ArrayList<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            String key = CALL_KEY_PREFIX + cursor.format(DateTimeFormatter.BASIC_ISO_DATE);
            List<String> rows = stringRedisTemplate.opsForList().range(key, 0, -1);
            if (rows != null) {
                for (String row : rows) {
                    AiCallRecord record = JSON.parseObject(row, AiCallRecord.class);
                    if (record == null) {
                        continue;
                    }
                    long timestamp = record.getTimestamp() == null ? 0L : record.getTimestamp();
                    if (timestamp >= startMillis && timestamp < endMillis) {
                        records.add(record);
                    }
                }
            }
            cursor = cursor.plusDays(1);
        }

        StringBuilder csv = new StringBuilder();
        csv.append("requestId,provider,model,tokenIn,tokenOut,latencyMillis,status,cost,timestamp\n");
        for (AiCallRecord each : records) {
            csv.append(nvl(each.getRequestId())).append(',')
                    .append(nvl(each.getProvider())).append(',')
                    .append(nvl(each.getModel())).append(',')
                    .append(nvl(each.getTokenIn())).append(',')
                    .append(nvl(each.getTokenOut())).append(',')
                    .append(nvl(each.getLatencyMillis())).append(',')
                    .append(nvl(each.getStatus())).append(',')
                    .append(nvl(each.getCost())).append(',')
                    .append(nvl(each.getTimestamp()))
                    .append('\n');
        }
        return csv.toString();
    }

    private Object nvl(Object value) {
        return value == null ? "" : value;
    }
}
