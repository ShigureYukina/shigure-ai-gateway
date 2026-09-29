# AI Gateway API 清单

> 项目：`ai-gateway`
> 说明：按业务域整理当前已实现接口，区分 `data-plane` 与 `management-plane`。
>
> 配置类写接口的行为约定：六个配置域的写入口（§3 routing、§4 rateLimit、§5 cache、§6 security、
> §11 plugin、§12 safety）在成功时会落库并广播版本号，响应里带 `persisted` 与 `version`；
> 语义与不落库的键见 §16 Runtime Config。

## 1. Gateway（data-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiGatewayController.java`

| Method | Path | Plane | 说明 |
|---|---|---|---|
| POST | `/v1/chat/completions` | data-plane | OpenAI 兼容聊天补全入口，根据 `stream` 返回 JSON 或 SSE |
| GET | `/v1/models` | data-plane | OpenAI 兼容模型清单，按调用凭证返回可用模型 |
| POST | `/v1/messages` | data-plane | Anthropic Messages 原生入口，stream=true 返回 Anthropic 事件流 |
| POST | `/v1/messages/count_tokens` | data-plane | Anthropic 输入 token 估算 |
| POST | `/v1/responses` | data-plane | OpenAI Responses 原生入口，支持流式事件 |

## 2. Tenant Config（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiTenantConfigController.java`

### 2.1 API Key

| Method | Path | 说明 |
|---|---|---|
| POST | `/v1/tenant-config/api-keys/lookup` | 按请求体 `{apiKey}` 查询指定 API Key 配置 |
| POST | `/v1/tenant-config/api-keys` | 新增或更新 API Key 配置 |
| DELETE | `/v1/tenant-config/api-keys` | 按请求体 `{apiKey}` 删除 API Key 配置 |

> **破坏性变更（2026-09-18）**：查询与删除原本把 Key 放在 URL path（`GET`/`DELETE /api-keys/{apiKey}`），
> 现改为请求体。原因：path 会进访问日志、审计明细、反向代理日志与浏览器历史，等于把密钥抄了好几份；
> 删除接口的响应也不再回显被删的 Key。控制台未引用这两个端点，无需改动。

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

### 2.6 Provider Credential

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiTenantConfigController.java`

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/tenant-config/provider-credentials/{provider}` | 查询上游凭证（API Key 只回显掩码）；`tenantId` 省略时查平台级 |
| POST | `/v1/tenant-config/provider-credentials/{provider}` | 新增或更新上游凭证；带 `tenantId` 时写入租户 BYOK |
| DELETE | `/v1/tenant-config/provider-credentials/{provider}` | 删除上游凭证 |

### 2.7 Model Price

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
| POST | `/v1/routing/config` | 运行时更新路由配置；见下方「providerBaseUrl 的写入校验」 |
| GET | `/v1/routing/preview` | 预览主路由及 fallback；可选 `tenantId`，带上后按该租户模型策略解析，与真实链路一致 |
| GET | `/v1/routing/simulate` | 模拟 A/B 分桶与 provider 分布 |

**`providerBaseUrl` 的写入校验（SSRF 持久化入口）**：`providerBaseUrl` 会被拼成上游请求地址并落库，
是比 `/v1/providers/models` 更严重的跳板（一次写入长期可用），因此每一项都要过
`OutboundUrlValidator.requireSafe`（校验的是 baseUrl 拼上 `provider-chat-path` 之后的**真实请求地址**）。

- 任一项不合规 → **整个请求 400，且一个键都不写入**。不会"跳过非法项继续写"：那样调用方收到 200，
  会以为地址改成功了，实际库里还是旧值，排查成本远高于直接报错。
- 校验要解析 DNS，因此该接口的执行被调度到 `boundedElastic`（不占用 event loop）。
- 私网/回环是否放行同样取决于 `security.ssrf.allow-private-addresses`（dev 默认 `true`、prod 默认 `false`）。

**热路径只做廉价防线**：`ProviderRoutingService.buildChatUri` 对每个请求都拼地址，不可能每个请求解析一次 DNS，
因此那里只做不碰 DNS 的 `requireWellFormed`（非 http(s) / 缺主机名 / `userinfo` / 云元数据主机名）。
"域名解析到内网"这一层由上面的写入口 + 启动期校验兜住。

**启动期校验**：`config/ConfigSecurityStartupValidator` 在 `@PostConstruct` 对
`upstream.provider-base-url.*`、`upstream.provider-chat-path.*`（按拼好的地址）、`sync.price.url` 各跑一次校验。
prod 不合规**拒绝启动**（日志指明是哪个 yml 键，并提示 on-prem 需显式设 `AI_GATEWAY_SSRF_ALLOW_PRIVATE=true`）；
非 prod 只 warn。**例外**：域名解析失败在 prod 也只 warn —— 解析失败不等于不安全，
启动瞬间的 DNS 抖动不该放大成全站起不来。

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

统计口径：**命中 = 精确命中 + 语义命中**，命中率 = (精确命中 + 语义命中) / (精确命中 + 语义命中 + 未命中)。
该口径与本服务写入 Redis 的 tenant cache 事件一致，`hitRate`、`totalLookup` 与趋势序列均按此计算。

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
| POST | `/v1/providers/models` | 用上游 baseUrl + API Key 探测并返回模型列表；请求路径取 `sync.model.path`（默认 `/v1/models`），与定时同步共用同一套拼装与解析 |

**出站地址校验（SSRF）**：这是唯一一个让网关朝"调用方指定的任意地址"发请求的入口，请求体里的
`baseUrl` 会先过 `upstream/OutboundUrlValidator.requireSafe`（要解析 DNS，因此跑在 `boundedElastic` 上）。
不合规时返回 **400**，`message` 说明原因（非 http(s) / 缺主机名 / 带 `userinfo` / 云元数据主机 /
解析到私网或回环 / 链路本地 / CGNAT / 保留网段）。私网与回环是否放行由
`security.ssrf.allow-private-addresses` 决定：dev 默认 `true`（本地把上游指向 `http://127.0.0.1:11434` 是正常用法），
prod 默认 `false`。**云元数据主机名、`userinfo`、非 http(s) 不受该开关影响，任何环境都拒。**

响应体形状（**破坏性变更，2026-09-18**）：

| 状态 | body |
|---|---|
| 200 | `{ "models": [...], "count": n }` |
| 400 | `{ "message": "...", "models": [] }` |
| 上游 4xx/5xx | `{ "message": "上游响应错误: <status>", "models": [] }`（状态码透传） |
| 其他失败 | `{ "message": "调用上游失败: ...", "models": [] }`（502） |

原响应体里有一个 `detail` 字段，直接把上游响应原文回传给浏览器。已**移除**：那是另一个系统的内容，
原样透出等于开了一条"用控制台读任意上游响应"的数据外带通道（控制台只读 `message`/`models`，不受影响）。

**残余风险**：地址校验在"解析时"完成，真正的连接由 Netty 之后重新解析一次 DNS ——
两次解析之间可以被做手脚（DNS TOCTOU）。本轮**不做**连接期过滤，详见 `OutboundUrlValidator` 的类注释与 README。

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

## 13. Provider Group（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiProviderGroupController.java`

通道组把多个 provider 组成一个可负载均衡的组，模型绑定到组后，通道选择与降级链由组决定。
策略取值：`PRIORITY`（优先级降级）/ `ROUND_ROBIN` / `RANDOM` / `WEIGHTED` / `DYNAMIC`（健康分）。

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/routing/groups` | 查询全部通道组、策略、成员权重与模型绑定，并标注数据来源（database/yml） |
| POST | `/v1/routing/groups` | 新建或覆盖通道组，成员整体替换 |
| DELETE | `/v1/routing/groups/{groupName}` | 删除组并解除其上的模型绑定 |
| POST | `/v1/routing/groups/{groupName}/bindings` | 把一个或多个模型绑定到该组（一个模型只归属一个组） |
| DELETE | `/v1/routing/groups/bindings?model=xxx` | 解除单个模型的组绑定 |
| POST | `/v1/routing/groups/reload` | 从数据库重新加载内存快照 |
| GET | `/v1/routing/groups/preview?model=xxx` | 预览该模型命中的组、策略与通道顺序 |

## 14. Metadata Sync（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiSyncController.java`

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/sync/status` | 价格同步与模型发现的开关、上次成功时间、失败原因、当前模型数 |
| POST | `/v1/sync/prices` | 立即从 models.dev 拉取一次价格 |
| POST | `/v1/sync/models` | 立即向各渠道拉取一次模型清单 |
| POST | `/v1/sync/all` | 依次执行价格同步与模型发现 |

## 15. Trace（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiTraceController.java`

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/trace/stream` | SSE 实时推送链路阶段事件（routing/cache/quota/upstream/first-token/fallback/completed/failed），带 20s 心跳 |
| GET | `/v1/trace/recent?limit=50` | 最近事件快照，供控制台首屏加载 |

## 16. Runtime Config（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiRuntimeConfigController.java`

运行时配置中心：六个配置域（`routing` / `rateLimit` / `cache` / `safety` / `plugin` / `security`）的落库快照与跨实例同步。
**改值不在这里** —— 每个域走各自的业务入口（`/v1/routing/config`、`/v1/cache/config` …），
那些入口成功落库后会在响应里返回 `persisted` 与 `version` 两个字段：

- `persisted=true`：已写入 `runtime_config` 表并广播版本号，其他实例会在一个轮询周期内跟随；
- `persisted=false`：本实例未接持久化（`short-link.ai-gateway.tenant.persistence.enabled=false`），配置只在当前实例内存中生效；
- 写库失败：返回 400，且内存回滚为**上一次成功生效的值**（不是启动时的 yml 值）。

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/runtime-config` | 六个域的当前生效值、取值来源（`yml` / `database`）、落库版本与更新时间；顶层带 `persisted` / `pollIntervalSeconds` / `securityForced` |
| POST | `/v1/runtime-config/reload` | 从数据库把六个域重放一遍，返回重载后的同一份视图 |
| DELETE | `/v1/runtime-config/{domain}` | 删掉该域的库中快照（恢复出厂），回到 yml 值；其他实例在下一次轮询跟随 |

不落库的键（改了只在本实例生效，也**不会**出现在快照里）：
`routing.providerGroups` / `routing.modelGroups`（真源是 `provider_group` 三表）、
`upstream.providerCredentials` / `upstream.providerChatPath` / `upstream.defaultProvider`、
`security.jwtSecret` / `security.users` / `security.jwtIssuer` / `security.sessionTtlMinutes`。

`security.enabled` 的优先级为：环境变量或系统属性 `AI_GATEWAY_SECURITY_FORCE_ENABLED` > 数据库 > yml。
强制开关为真时，`POST /v1/security/config` 关闭安全开关会被拒绝（400）。

## 17. Channel Health（management-plane）

来源：`src/main/java/com/nageoffer/shortlink/aigateway/controller/AiChannelHealthController.java`

主动探测的读路径与管理入口。定时任务（`ChannelProbeScheduler`）按 `short-link.ai-gateway.probe.interval`
周期性探测全部**已配置**渠道（含没配凭证的，以便区分"没配 Key"与"Key 失效"），
连续失败达阈值自动禁用、冷却后连续成功达门槛自动恢复。

| Method | Path | 说明 |
|---|---|---|
| GET | `/v1/routing/channels/health` | 每条渠道的计数、最近结论与不可用原因；顶层带 `source` / `degradation` / `lastProbe` / `items` |
| POST | `/v1/routing/channels/health/{provider}` | 人工启用/禁用，body `{"enabled": true|false}`；写 `manual_disabled` 并广播版本号 |
| POST | `/v1/routing/channels/health/reload` | 只从库里重读快照，不触发探测（其他实例刚改过状态时手工对齐用） |
| POST | `/v1/routing/channels/health/probe` | 强制跑一轮探测（跳过跨实例锁）并返回最新视图 |

**三种"不可用"是并列的**，`items[]` 里各占一个独立布尔字段，不做合并：

| 字段 | 含义 | 真源 |
|---|---|---|
| `staticDisabled` | yml 静态禁用，跟着发布走 | `probe.channel-enabled.<provider>=false` |
| `manualDisabled` | 运维在控制台的临时动作 | `provider_health.manual_disabled` |
| `down` | 探测判定的结论 | `provider_health.status` |

`usable` = 三者皆否；`reason` 是按同一优先级给出的中文原因（可用时为 `null`），
控制台直接展示它即可，不必自己拼话术。

`items` 的集合是"已配置渠道 ∪ 快照里有行的渠道"：前者防止漏掉还没探过的渠道
（`lastCheckedAt` 为 `null`、计数为 `0`），后者防止漏掉改配置后残留的旧行。
注意 `status='DOWN'` 与 cooldown **是两件事**：`down=true` 且 `disabledUntil` 已过时，
下一轮探测会重试它，此时 `reason` 会说明"冷却已过"。

`degradation` 说明当前有哪些降级，控制台应当把它显示出来：

- `persistenceAvailable=false`：本实例未接 R2DBC 仓储。人工禁用**无处存放**，
  此时 `POST /{provider}` 返回 400 而不是 200 —— 状态根本没变，返回成功会让运维以为生效了；
- `redisAvailable=false`：探测锁拿不到。跨实例去重失效，各实例会各自探测
  （状态迁移仍由 SQL 条件更新兜底，不会改错），探测结论照常落库。

**不计数**的探测结论：`RATE_LIMITED`（429）与 `NO_CREDENTIAL`（渠道没配 Key）。
前者说明渠道是活的，后者是配置问题，把这两种算成失败会把一个完好的渠道关掉，
还要多等一个冷却周期才能回来。它们既不加失败计数也不清零，在 `lastProbe.inconclusive` 里单独计数。

**刻意不提供**"删除健康行"接口：没有行 = fail-open 视为可用，"删掉"与"恢复"等价，
多一个入口只会让人怀疑两者行为不同。

## 汇总

- 已实现端点总数：**74**
- `data-plane`：**5** 个入口（聊天补全 + 模型清单 + Anthropic Messages + count_tokens + Responses）
- `management-plane`：**69** 个治理/配置/运维接口
