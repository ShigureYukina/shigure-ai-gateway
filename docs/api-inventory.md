# AI Gateway API 清单

> 项目：`ai-gateway`
> 说明：按业务域整理当前已实现接口，区分 `data-plane` 与 `management-plane`。

## 1. Gateway（data-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiGatewayController.java`

| Method | Path | Plane | 说明 |
|---|---|---|---|
| POST | `/v1/chat/completions` | data-plane | OpenAI 兼容聊天补全入口，根据 `stream` 返回 JSON 或 SSE |

## 2. Tenant Config（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiTenantConfigController.java`

### 2.1 API Key

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/tenant-config/api-keys/{apiKey}` | 查询指定 API Key 配置 |
| POST | `/v1/tenant-config/api-keys` | 新增或更新 API Key 配置 |
| DELETE | `/v1/tenant-config/api-keys/{apiKey}` | 删除 API Key 配置 |

### 2.2 Tenant

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/tenant-config/tenants/{tenantId}` | 查询租户信息 |
| POST | `/v1/tenant-config/tenants` | 新增或更新租户 |
| DELETE | `/v1/tenant-config/tenants/{tenantId}` | 删除租户 |

### 2.3 Tenant App

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/tenant-config/tenants/{tenantId}/apps/{appId}` | 查询租户应用 |
| GET | `/v1/tenant-config/tenants/{tenantId}/apps` | 列出租户下全部应用 |
| POST | `/v1/tenant-config/tenants/{tenantId}/apps` | 新增或更新租户应用 |
| DELETE | `/v1/tenant-config/tenants/{tenantId}/apps/{appId}` | 删除租户应用 |

### 2.4 Model Policy

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/tenant-config/model-policies/{tenantId}` | 查询租户模型策略 |
| POST | `/v1/tenant-config/model-policies/{tenantId}` | 新增或更新租户模型策略 |
| DELETE | `/v1/tenant-config/model-policies/{tenantId}` | 删除租户模型策略 |

### 2.5 Quota Policy

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/tenant-config/quota-policies/{tenantId}` | 查询租户配额策略 |
| POST | `/v1/tenant-config/quota-policies/{tenantId}` | 新增或更新租户配额策略 |
| DELETE | `/v1/tenant-config/quota-policies/{tenantId}` | 删除租户配额策略 |

### 2.6 Model Price

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/tenant-config/model-prices/{model}` | 查询模型价格配置 |
| POST | `/v1/tenant-config/model-prices/{model}` | 新增或更新模型价格 |
| DELETE | `/v1/tenant-config/model-prices/{model}` | 删除模型价格 |

## 3. Routing（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiRoutingController.java`

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/routing/config` | 查看当前路由与 fallback 配置 |
| POST | `/v1/routing/config` | 运行时更新路由配置 |
| GET | `/v1/routing/preview` | 预览主路由及 fallback |
| GET | `/v1/routing/simulate` | 模拟 A/B 分桶与 provider 分布 |

## 4. Rate Limit（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiRateLimitController.java`

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/rate-limit/config` | 查询限流与配额配置 |
| POST | `/v1/rate-limit/config` | 更新限流与配额配置 |
| GET | `/v1/rate-limit/usage` | 查看当前 provider/model 配额用量 |

## 5. Cache（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiCacheController.java`

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/cache/config` | 查询缓存配置 |
| POST | `/v1/cache/config` | 更新缓存配置 |
| GET | `/v1/cache/stats` | 查询缓存统计快照 |
| POST | `/v1/cache/stats/reset` | 重置缓存统计 |
| GET | `/v1/cache/stats/trend` | 查询最近 N 分钟缓存命中趋势 |

## 6. Security（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiSecurityController.java`

| Method | Path | 说明 |
|---|---|---|
| POST | `/v1/security/login` | 控制台登录 |
| POST | `/v1/security/logout` | 控制台登出 |
| GET | `/v1/security/config` | 查询控制台安全配置与当前角色 |
| POST | `/v1/security/config` | 更新控制台安全配置 |

## 7. Observability（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiObservabilityController.java`

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/metrics/models/{model}` | 查询模型当前小时调用量、成功率、P95 延迟和成本 |

## 8. Audit（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiAuditController.java`

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/audit/logs` | 查看审计日志 |
| POST | `/v1/audit/logs/clear` | 清空审计日志 |

## 9. Billing（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiBillingController.java`

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/billing/export` | 按日期范围导出账单 CSV |

## 10. Provider（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiProviderController.java`

| Method | Path | 说明 |
|---|---|---|
| POST | `/v1/providers/models` | 用上游 baseUrl + API Key 探测并返回模型列表 |

## 11. Plugin（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiPluginController.java`

| Method | Path | 说明 |
|---|---|---|
| POST | `/v1/plugins/toggle` | 按插件名启用或禁用插件 |
| GET | `/v1/plugins/config` | 查看全局插件、路由插件和启停状态 |

## 12. Safety（management-plane）

来源：
- `src/main/java/com/nageoffer/shortlink/aigateway/controller/AiSafetyController.java`
- `src/main/java/com/nageoffer/shortlink/aigateway/controller/AiSafetySandboxController.java`

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/safety/config` | 查询当前安全策略配置 |
| POST | `/v1/safety/config` | 更新输入/输出安全过滤配置 |
| POST | `/v1/safety/sandbox/check` | 对任意文本做安全规则沙箱检测并返回命中项 |

## 汇总

- 已实现端点总数：**38**
- `data-plane`：**1** 个核心入口
- `management-plane`：**37** 个治理/配置/运维接口
