# Shortlink AI Gateway

<div align="center">

![Java](https://img.shields.io/badge/Java-17-3A75B0?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.2-6DB33F?logo=springboot&logoColor=white)
![WebFlux](https://img.shields.io/badge/Spring-WebFlux-00A3E0)
![Build](https://img.shields.io/badge/Build-Maven-C71A36?logo=apachemaven&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-Enabled-DC382D?logo=redis&logoColor=white)

</div>

> **关于历史提交信息中的数字**：早期 commit message 里的吞吐数字（如 1.3k → 11.5k RPS）
> 来自与 docs/perf/ 不同的测量条件，本仓库与简历均不引用该口径；
> 权威压测数据与口径见 docs/perf/README.md 与 docs/load/。

一个面向 LLM 接入场景的统一 AI 网关，基于 **Spring Boot 3 + WebFlux** 构建，对外提供 **OpenAI 兼容接口**，对内支持多 Provider 路由、流式透传、配额治理与韧性保护。

---

## 目录

- [项目亮点](#项目亮点)
- [技术栈](#技术栈)
- [架构概览](#架构概览)
- [快速开始](#快速开始)
- [接口示例](#接口示例)
- [配置说明](#配置说明)
- [构建与测试](#构建与测试)
- [可观测性与稳定性](#可观测性与稳定性)
- [工程化能力](#工程化能力)
- [Roadmap](#roadmap)

---

## 项目亮点

| 能力模块 | 说明 |
| --- | --- |
| OpenAI 兼容入口 | 暴露 `/v1/chat/completions`，支持流式（SSE）与非流式 |
| 原生协议入口 | `/v1/messages`（Anthropic Messages，含 `count_tokens`）与 `/v1/responses`（OpenAI Responses）；错误体按各自协议返回，内部复用同一条治理链路 |
| 渠道 Key 池 | 同一渠道多把 Key 加权轮换；连续失败达阈值自动冷却 60s 并把流量切到次优 Key，失败被隔离在"一把钥匙"而不是整个渠道 |
| 渠道级限速 | `rpm-limit` 按分钟定窗计数（Redis 跨实例一致），超限当作"通道暂不可用"触发回退，全部被限才返回 429；未配置时零开销 |
| 模型清单 | 数据面 `/v1/models`，按调用凭证返回真实可用模型，而非平台全量模型 |
| 上游凭证与 BYOK | 平台级凭证 + 租户自带 Key，DB / yml 双轨；客户端 Authorization 只用于平台鉴权，不再透传上游 |
| 字段透传 | `tools` / `response_format` / `top_p` 等未显式建模的字段原样透传，避免"到网关就丢" |
| 流式用量结算 | 流式请求按上游回传 usage 结算 token 配额与成本；失败与客户端取消自动退还预扣 |
| 错误契约对齐 | 错误体为 OpenAI `error.type / error.code` 形状，流内错误转错误帧并补 `[DONE]`，不再硬断流 |
| 多 Provider 路由 | 静态路由 / 动态路由（成本优化 / 延迟优化）/ A/B 灰度 / 模型别名映射 |
| 通道组与负载均衡 | 把多个 provider 组成一个组并绑定模型，组内可选 **优先级降级 / 轮询 / 随机 / 加权 / 动态健康分** 五种策略；组内成员天然互为降级通道 |
| 智能路由 | 基于健康评分的动态路由：成功率 40% + 延迟 30% + 成本 30%，每次调用结果回灌健康分 |
| 元数据自动同步 | 定时从 models.dev 拉模型价格、从各渠道 `/v1/models` 发现模型；价格按 **人工覆盖 → yml → 自动同步** 分层，自动值永远盖不住手写值 |
| 实时请求链路 | SSE 推送每一跳（route → cache → quota → upstream → first-token → 收尾），控制台可视化；无人订阅时零开销 |
| 韧性治理 | 超时、重试、Fallback、Resilience4j 熔断 |
| 配额体系 | 基于 Redis 的 token quota ledger |
| 语义缓存 | Trigram Jaccard 相似度匹配 + 精确 Hash 命中，可配置阈值 |
| 安全拦截 | 输入输出安全策略、Prompt Injection 检测、PII 脱敏 |
| 插件化扩展 | 请求前 / 响应后插件链机制 |
| 分布式追踪 | Micrometer Tracing + OpenTelemetry，全链路 Span 标记 |
| 运行可观测 | Prometheus 指标、P95 延迟、Provider 健康评分 |

---

## 技术栈

- **Language**: Java 17
- **Framework**: Spring Boot 3.3.2, Spring Cloud Gateway, WebFlux
- **Data & Cache**: Redis, R2DBC(MySQL，可选)
- **Observability**: Micrometer, Prometheus, OpenTelemetry Tracing
- **Test**: JUnit 5, Reactor Test, Testcontainers（Redis 集成测试）
- **Build**: Maven

---

## 架构概览

```mermaid
flowchart LR
    C[Client / SDK] --> G[/OpenAI Compatible API<br/>/v1/chat/completions/]
    G --> R[Routing & Alias Mapping]
    R --> P1[Provider A]
    R --> P2[Provider B]
    G --> GOV[Quota / Cache / Security / Plugins]
    G --> OBS[Metrics / Health / Circuit Breaker]
```

核心启动类：`src/main/java/com/nageoffer/shortlink/aigateway/AiGatewayApplication.java`

---

## 快速开始

### 1) 前置依赖

- JDK 17
- Maven（`mvn`）
- Docker（可选，推荐用于启动 Redis）

### 2) 启动 Redis

```bash
docker compose up -d redis
```

### 3) 启动网关（local profile）

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

默认端口：`8010`

### 4) （可选）启用 MySQL / R2DBC 租户配置

先执行初始化脚本：

```bash
src/main/resources/sql/init_multi_tenant_platform_mysql.sql
```

再配置环境变量：

```bash
AI_GATEWAY_R2DBC_URL=r2dbc:pool:mysql://127.0.0.1:3306/ai_gateway
AI_GATEWAY_DB_USERNAME=root
AI_GATEWAY_DB_PASSWORD=root
AI_GATEWAY_TENANT_DB_ENABLED=true
```

启用后：tenant API Key / model policy / quota policy / model price 优先从 DB 读取，未命中时回退到 `application.yml`。

### 5) 生产部署建议（prod profile）

生产环境建议使用 `prod` profile，并通过环境变量注入敏感配置：

```bash
AI_GATEWAY_JWT_SECRET=***
AI_GATEWAY_ADMIN_PASSWORD=***            # 必须是 {bcrypt} 哈希，prod 拒绝明文（生成方式见下）
AI_GATEWAY_VIEWER_PASSWORD=***
AI_GATEWAY_MASTER_KEY=***                # Base64 的 32 字节；缺失会导致启动失败
NACOS_SERVER_ADDR=***
```

启动时会对上述缺失项直接报错，不会静默降级。

生成 bcrypt 口令哈希（`{bcrypt}` 前缀必须保留）：

```bash
# 任意一次以非 prod 启动时，若 yml 里还是明文口令，SecretMigrationRunner 会把现成哈希打进日志：
#   console user 'admin' stores a plaintext password; to migrate, replace it with: {bcrypt}$2a$10$...
```

生成主密钥（`openssl rand -base64 32`）：

```bash
AI_GATEWAY_MASTER_KEY="$(openssl rand -base64 32)"
```

#### 出站地址（SSRF）与残余风险

网关会朝配置里的上游地址发请求，因此所有出站地址都过 `upstream/OutboundUrlValidator`：
拒绝非 http(s)、`userinfo`（`http://user:pass@host` 的 host 混淆写法）、云元数据主机名、
以及解析到的私网/回环/链路本地/多播/CGNAT/保留网段。私网与回环由
`security.ssrf.allow-private-addresses` 控制（dev 默认 `true`，prod 默认 `false`）。

prod 里若上游在 VPC 内（自建 Ollama / vLLM），需要**显式**打开：

```bash
AI_GATEWAY_SSRF_ALLOW_PRIVATE=true       # 环境变量优先级高于 application-prod.yml
```

> **残余风险（已知且未修）**：校验发生在"解析时"，真正的连接由 Netty 在之后**重新解析**一次 DNS，
> 两次解析之间可以做手脚（DNS TOCTOU / DNS rebinding）：校验时答公网、连接时答 `127.0.0.1`。
> 彻底堵住需要给 Netty 装 `AddressResolverGroup` 做**连接期**过滤，本版本**不做**。
> 因此这层的定位是"把误配置与顺手可得的 SSRF 挡在写入口"，不是"对抗能控制 DNS 的攻击者"。
> 减轻这部分风险的是可写面：管理面写入需要写角色，写入值落库且有审计。

生产配置文件：`src/main/resources/application-prod.yml`

---

## 接口示例

### 非流式请求

```bash
curl -X POST "http://127.0.0.1:8010/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-key>" \
  -d '{
    "model": "gpt-4o-mini-compatible",
    "messages": [{"role": "user", "content": "hello"}],
    "stream": false
  }'
```

### 流式请求（SSE）

```bash
curl -N -X POST "http://127.0.0.1:8010/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-key>" \
  -d '{
    "model": "gpt-4o-mini-compatible",
    "messages": [{"role": "user", "content": "hello"}],
    "stream": true
  }'
```

### 模型清单

```bash
curl "http://127.0.0.1:8010/v1/models" \
  -H "Authorization: Bearer <your-key>"
```

### 上游凭证

网关不转发客户端传来的 `Authorization`，上游只认配置化的凭证：

```bash
# 平台级凭证（管理面，需控制台写权限）
curl -X POST "http://127.0.0.1:8010/v1/tenant-config/provider-credentials/openai" \
  -H "X-Console-Token: <console-token>" -H "Content-Type: application/json" \
  -d '{"apiKey":"sk-xxx","authHeader":"Authorization","authScheme":"Bearer"}'

# 租户自带 Key（BYOK），优先于平台级生效
curl -X POST "http://127.0.0.1:8010/v1/tenant-config/provider-credentials/openai?tenantId=demo-tenant" \
  -H "X-Console-Token: <console-token>" -H "Content-Type: application/json" \
  -d '{"apiKey":"sk-tenant-xxx"}'
```

---

## 配置说明

主配置文件：`src/main/resources/application.yml`

推荐通过环境变量覆盖敏感参数：

- `AI_GATEWAY_JWT_SECRET`
- `AI_GATEWAY_ADMIN_PASSWORD`
- `AI_GATEWAY_VIEWER_PASSWORD`
- `REDIS_HOST` / `REDIS_PORT`
- `NACOS_SERVER_ADDR`
- `AI_GATEWAY_R2DBC_URL`
- `AI_GATEWAY_DB_USERNAME`
- `AI_GATEWAY_DB_PASSWORD`
- `AI_GATEWAY_TENANT_DB_ENABLED`
- `OPENAI_API_KEY` / `ANTHROPIC_API_KEY`（平台级上游凭证，也可走管理接口写入 DB）

---

## 构建与测试

### 常用命令

```bash
# 编译（不跑测试）
mvn -DskipTests compile

# 运行全部测试
mvn test

# 完整校验（含覆盖率）
mvn clean verify

# 打包
mvn clean package -DskipTests
```

### 运行指定测试

```bash
# 单个测试类
mvn -Dtest=ProviderRoutingServiceTest test

# 单个测试方法
mvn -Dtest=ProviderRoutingServiceTest#shouldResolveAliasProviderAndModel test

# 多个测试类
mvn -Dtest=ProviderRoutingServiceTest,SseStreamE2ETest test
```

JaCoCo 报告：`target/site/jacoco/index.html`

### 集成测试（Testcontainers）

`AiGatewayMetricsRecorderRedisIntegrationTest` 会拉起真实 Redis 容器，执行指标埋点的内联 Lua 脚本，
校验聚合值、`cacheHit` 分支、TTL 与"每请求一次脚本调用"。单测里 Redis 是 Mockito 桩，脚本体从未被执行，
所以这层是必要的补充。

- 无 Docker 时自动跳过（`@Testcontainers(disabledWithoutDocker = true)`），不会阻塞本地开发；
- 需要 Docker 可达，镜像 `redis:7.2-alpine`。

若在**容器内**跑测试（挂载 docker.sock 的 Docker-out-of-Docker 方式），需要额外指定：

```bash
docker run --rm -v //var/run/docker.sock:/var/run/docker.sock -w /app <maven-image> \
  mvn -B -Dapi.version=1.44 -Dtest=AiGatewayMetricsRecorderRedisIntegrationTest test
```

- `-Dapi.version=1.44`：新版 Docker Desktop 已拒绝低版本 API（`/v1.32/info` 返回 400），
  docker-java 默认的探测版本会失败，必须显式抬到 1.40 以上；
- `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`：容器内跑测试时，容器映射端口发布在宿主机上，
  需要把回连地址指到宿主机，否则测试会连 `172.17.0.1` 而 Connection refused。

---

## 可观测性与稳定性

- **Prometheus 指标**：`/actuator/prometheus`
- **健康检查**：`/actuator/health`
- **响应头**：所有响应带 `X-Request-Id`；配额超限额外带 `Retry-After`；
  开启限流后，模型调用响应带 `x-ratelimit-limit-tokens` / `x-ratelimit-remaining-tokens`（含 `-day` 变体）
- **错误体**：OpenAI 兼容 `{"error":{"message","type","code","param"}}`，官方 SDK 可直接分类与重试
- **非 OpenAI 协议上游**：通过 `upstream.provider-chat-path` 声明补全路径（如 Claude 的 `/v1/messages`）
- **熔断实例**：`provider-openai`、`provider-claude`
- **分布式追踪**：Micrometer Tracing + OpenTelemetry OTLP exporter
  - 环境变量：`OTEL_EXPORTER_OTLP_ENDPOINT`（默认 `http://localhost:4318`）
  - 采样率：`OTEL_SAMPLING_RATE`（默认 `1.0`）
- **Provider 健康评分**：`GET /v1/routing/health?model=xxx`
  - 评分公式：`成功率×40 + (1-归一化延迟)×30 + (1-归一化成本)×30`
  - 支持策略：`static` / `dynamic` / `cost-optimized` / `latency-optimized`
- **渠道主动探测（可用性开关）**：`GET /v1/routing/channels/health` 查看每条渠道的探测计数与不可用原因；
  `POST /v1/routing/channels/health/probe` 立即探一轮，`POST /v1/routing/channels/health/{provider}`
  人工启用/禁用。三个"不可用"来源是**并列**的独立字段而不是一个合并状态：
  `staticDisabled`（yml `probe.channel-enabled.<p>=false`，跟着发布走）、
  `manualDisabled`（控制台临时动作）、`down`（探测结论），另有一个中文 `reason` 供控制台直接展示。
  - 周期与阈值：`short-link.ai-gateway.probe.*`，默认 5 分钟一轮、连败 3 次禁用、冷却 5 分钟后连成 2 次恢复；
    `AI_GATEWAY_PROBE_ENABLED=false` 可整体关掉（关掉后只能靠人工开关维护）
  - **限流（429）与"没配 Key"不计入失败计数**：前者说明渠道是活的，后者是配置没补齐，
    算成失败会把一个完好的渠道关掉，还要多等一个冷却周期才能回来（在 `lastProbe.inconclusive` 里单独计数）
  - 多实例三层去重：本实例标记 + Redis `SET NX PX` + SQL 条件更新。**第三层才是必需的**
    （保证并发下只有一次状态迁移成功），前两层只是省上游请求；Redis 不可用时降级为各自探测
  - 与 `GET /v1/routing/health` 的区别：那个是**健康评分**（成功率/延迟/成本加权，用于动态路由排序），
    这个是**可用性开关**（连败直接踢出候选集，冷却后自动放回）
- **语义缓存**：Trigram Jaccard 相似度匹配，阈值可配置（默认 0.85）
- **实时请求链路**：`GET /v1/trace/stream`（SSE，含 20s 心跳）+ `GET /v1/trace/recent`；
  控制台「实时链路」页按 requestId 分组画时间线。埋点开关 `observability.trace-stream-enabled`，
  关闭或无人订阅时直接短路，不产生事件对象
- **原生协议入口**：`POST /v1/messages`（stream=true 返回 `message_start/content_block_delta/message_stop` 事件流）、
  `POST /v1/messages/count_tokens`、`POST /v1/responses`（stream=true 返回 `response.output_text.delta` 等事件）
- **Key 池与渠道限速**：`provider-credentials.<provider>.api-keys`（或 `provider_credential_key` 表）配多 Key，
  `rpm-limit` 配渠道 RPM；两者都在未配置时保持原行为
- **元数据同步状态**：`GET /v1/sync/status` 暴露开关、上次成功时间与失败原因；
  `POST /v1/sync/prices` / `/v1/sync/models` / `/v1/sync/all` 可手动触发

---

## 本地“近真实”E2E 模拟

无需真实外部 API，可使用内置脚本验证主流程：

```bash
mvn -DskipTests package
powershell -ExecutionPolicy Bypass -File "scripts/simulate_e2e.ps1"
```

该脚本会自动：

- 启动本地 mock provider（`127.0.0.1:18080`）
- 启动网关（`127.0.0.1:18010`）
- 验证流式与非流式链路

---

## 工程化能力

- CI：`.github/workflows/ci.yml`（`clean verify` + 覆盖率门禁 + 打包上传）
- 安全扫描：OWASP Dependency-Check（按需运行 `mvn dependency-check:check`，未纳入 CI）
- 协作规范：`AGENTS.md`
- API 清单：`docs/api-inventory.md`
- 性能基线：`docs/load/README.md`（稳态 P50 37.7ms / P95 44.6ms / 503 RPS，网关开销 ≈1.7ms）

---

## Roadmap

- [x] 增加 Testcontainers 集成测试（Redis）—— `AiGatewayMetricsRecorderRedisIntegrationTest`
- [ ] 补充 Prometheus + Grafana 可视化面板
- [ ] 增加真实上游 smoke test（受控 key / 限额）
- [x] 输出性能基线（P95、错误率、吞吐）—— 见 `docs/load/README.md`
- [x] 通道组 + 五种负载均衡策略（优先级降级 / 轮询 / 随机 / 加权 / 动态健康分）
- [x] 模型清单与价格自动同步（models.dev + 渠道 `/v1/models`）
- [x] 实时请求链路可视化（SSE + 控制台时间线）
- [x] 原生协议入口（Anthropic Messages / OpenAI Responses）
- [x] 渠道多 Key 池 + 渠道级 RPM
- [ ] 竞品对比文档（vs New API / Octopus / LiteLLM）
- [ ] 通道主动探测（定时健康测试 + 自动禁用/恢复）
