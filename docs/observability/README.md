# 可观测性：指标契约与看板

指标端点 `/actuator/prometheus`，看板由 compose 里的 Grafana 自动 provision。本文记录**指标名、单位口径和开关**——
这三点决定了 PromQL 怎么写，看板里写错了不会报错，只会安静地返回空或差 1000 倍。

---

## 一、起服务

```bash
docker compose up -d
```

| 服务 | 地址 | 说明 |
|---|---|---|
| Prometheus | http://localhost:9090 | 抓 `app:8010/actuator/prometheus`，5s 一轮 |
| Grafana | http://localhost:3000 | 账号密码默认 `admin`/`admin`，看板在「AI Gateway」文件夹 |

账号密码走 `.env`：

```
AI_GATEWAY_GRAFANA_USER=
AI_GATEWAY_GRAFANA_PASSWORD=
```

**默认口令只为本地调试**，端口对外暴露前必须改掉。

启动顺序上 Prometheus `depends_on: app`，但 app 起来前抓取会失败（`connection refused`），目标列表里显示
`down` 属于正常，等 app 就绪后自动转 `up`。用 `http://localhost:9090/targets` 确认。

**抓取目标是 compose 网络里的 `app:8010`**，也就是应用跑在容器里时的地址。如果你用的是
`docker compose up -d redis` + 宿主机 `mvn spring-boot:run` 这种组合，容器里的 Prometheus 连不到宿主机端口，
要么把 `deploy/prometheus/prometheus.yml` 的 target 改成 `host.docker.internal:8010`，要么直接用本地 Prometheus。

---

## 二、业务指标契约

全部由 `observability/AiGatewayMetricsRecorder` 写入，Redis 聚合键由 `AiGatewayMetricsKeys` 定。
标签值缺失时统一落到 `unknown`（不会写出空值标签）。

| Prometheus 指标 | 类型 | 标签 | 说明 |
|---|---|---|---|
| `ai_gateway_tenant_requests_total` | counter | `tenant, app, provider, model, result, status_class` | `result` 取 `success`/`error` |
| `ai_gateway_tenant_request_latency_ms_seconds` | histogram | `tenant, app, provider, model, result` | 端到端时延 |
| `ai_gateway_tenant_tokens_in` / `_out` | summary | `tenant, app, provider, model` | 仅 `_count/_sum/_max` |
| `ai_gateway_tenant_cost_usd` | summary | `tenant, app, provider, model` | 仅 `_count/_sum/_max` |
| `ai_gateway_tenant_cache_events_total` | counter | `tenant, event` | `event` = `cacheHit`/`cacheMiss`/`cacheWrite` |
| `ai_gateway_tenant_quota_events_total` | counter | `tenant, app, provider, model, event` | `event` = `reserve`/`release`/`reject` |
| `ai_gateway_tenant_quota_tokens` | summary | 同上 | 预扣/释放的 token 数 |

另有 Spring Boot 自带的 `http_server_requests_seconds`（按 `uri`/`status` 维度）。

---

## 三、两个会踩的坑

### 1. 时延指标的导出单位是「秒」，不是毫秒

meter 名叫 `ai_gateway_tenant_request_latency_ms`，但 Micrometer 把 Timer 按 Prometheus 惯例导出成
`..._ms_seconds`，值也是**秒**：

```
# record(42, MILLISECONDS) 的实际导出
ai_gateway_tenant_request_latency_ms_seconds_sum 0.042
```

所以 `histogram_quantile` 的结果要 `* 1000` 才是毫秒。看板里所有时延面板都做了这个换算，
面板单位标 `ms`。直接当毫秒用会差 1000 倍——37.7ms 的请求会被画成 0.0377ms。

同理，Micrometer 的 Timer 默认导出 base unit 秒的 `_seconds`，JVM 的 `jvm_gc_pause_seconds` 也是秒。

### 2. summary 型指标没有 `_bucket`，不能算分位数

`tokens_in` / `tokens_out` / `cost_usd` / `quota_tokens` 是 `DistributionSummary`，
只有 `_count` / `_sum` / `_max`。`histogram_quantile` 用在它们上面只会返回空。

均值这样算：

```promql
sum(rate(ai_gateway_tenant_cost_usd_sum[5m])) / sum(rate(ai_gateway_tenant_cost_usd_count[5m]))
```

要让它们也出 `_bucket`，得在 `DistributionSummary.builder(...)` 上调 `publishPercentileHistogram()`。
目前没开——token 和金额的分布在本地开发里用不到，为它付出每组标签一份直方图的内存不划算。

---

## 四、两处直方图的来源不同

| 指标 | 怎么才有 `_bucket` |
|---|---|
| `http_server_requests_seconds` | `application.yml` 里 `management.metrics.distribution.percentiles-histogram.http.server.requests: true` |
| `ai_gateway_tenant_request_latency_ms` | 代码里 `Timer.builder(...).publishPercentileHistogram()` |

**`management.metrics.distribution` 这套配置管不到应用自己注册的 meter**（试过
`ai_gateway.tenant.request.latency` 和 `ai.gateway.tenant.request.latency.ms` 两种键名，都是 0 行 bucket）。
所以时延指标只能走代码那一条路。

代价是每个 tag 组合一份 HdrHistogram。租户和模型两个维度都跟着扩散时内存会涨，
这是 `observability.tenant-metrics-enabled` 需要按租户规模取舍的原因。

---

## 五、看板覆盖的查询

`deploy/grafana/dashboards/ai-gateway-overview.json`，20 个 panel 分 5 组：
请求速率与错误率、端到端时延分位、HTTP 入口、Token 与成本、配额事件、JVM 堆/GC/CPU/线程，
以及一张按供应商拆的明细表。顶部三个变量 `tenant`/`provider`/`model` 直接取自
`ai_gateway_tenant_requests_total` 的标签值。

改看板后校验 PromQL（没有 `mvn` 也能做，靠 docker 里的 promtool）：

```bash
# 1. 语法校验：把看板里的 expr 抽成 rules 文件，替换掉 Grafana 变量
docker run --rm --entrypoint /bin/promtool -v "$(pwd):/w:ro" \
  prom/prometheus:v3.5.0 check rules /w/dashcheck.yml

# 2. 取值验证：对真实 Prometheus 逐条查，EMPTY 的面板要确认是"没数据"而不是"写错了"
curl -s 'http://localhost:9090/api/v1/query?query=<expr>'
```

---

## 六、面板显示「无数据」的三种正常原因

| 现象 | 原因 | 怎么确认 |
|---|---|---|
| 错误率面板空 | 全程没有 `result="error"` 的样本 | 看板已用 `or vector(0)` 兜底，会显示 0 而不是空 |
| 语义缓存命中率空 | `cache.enabled` / `cache.semantic-cache-enabled` 默认都是 `false`（见 `application.yml`），不产生缓存事件 | 打开开关后重放同一请求，应出现 `cacheMiss` → `cacheHit` |
| 配额事件面板空 | 没给应用/租户配 token 配额，预扣链路不会进 | `GET /v1/routing/health` 之外，先确认配额策略已绑定到该租户 |

缓存事件还要 `observability.cache-event-metrics-enabled`（默认 `true`），
配额事件要 `observability.quota-event-metrics-enabled`（默认 `true`）。