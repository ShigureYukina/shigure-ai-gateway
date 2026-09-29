# AI 网关性能基线

> 对应 README Roadmap「输出性能基线（P95、错误率、吞吐）」。
> 全部组件跑在 Docker 内，本目录的 JSON 是原始测量结果，命令行可直接复现。

## 一、测什么

在受控条件下测出三件事：

1. 稳态下网关自身的延迟与吞吐（P50/P95/P99、RPS、成功率）；
2. 网关相对"直连上游"增加了多少开销 —— 这个差值才代表网关的成本；
3. 并发从 1 拉到 50 时，延迟与吞吐如何变化。

结论**只适用于本机受控环境**，不代表生产容量。

## 二、环境

| 组件 | 镜像 / 文件 | 说明 |
| --- | --- | --- |
| 网关 | `eclipse-temurin:17-jre` + `target/shortlink-ai-gateway.jar` | 端口 8010，容器名 `aigw-gateway` |
| 上游 | `python:3.12-slim` + `scripts/mock_openai.py` | 固定延迟：TTFT 20ms + 生成 15ms = 35ms，容器名 `aigw-mock` |
| Redis | `redis:7.2-alpine` | 容器名 `aigw-redis` |
| 压测客户端 | `python:3.12-slim` + `scripts/load_test.py` | 与上述容器同处 `aigw-net` 网络 |

- 宿主：Windows + Docker Desktop，容器可见 12 CPU（未做 CPU 限额）。
- 网关启动参数：租户鉴权开启，缓存 / 限流 / 安全 / 熔断兜底保持默认关闭，链路追踪采样 1.0。
- 上游固定 35ms 是刻意选的：它让"排队"和"额外开销"更容易区分 —— 如果上游是 0ms，测出来的几乎全是网关自身。

## 三、方法

1. **先预热一轮再测**。JVM 冷启动会污染结果：`cold-start.json`（网关刚起来就压）P50 只有 42.9ms，看着不慢，但 P99 高达 1275ms、吞吐 188 RPS；预热后的稳态是 P99 62ms、503 RPS。预热的代价远小于把冷启动写进结论的代价。
2. **正式测量跑 3 轮取区间**，不取单次值。
3. **必须同时测一组"直连上游"对照**。只有这个差值才能回答"网关慢不慢"，单看网关绝对值说明不了任何问题。
4. **按并发梯度测**（1 / 5 / 20 / 50），用来区分"每请求固定开销"和"排队等待"。

## 四、结果

### 4.1 稳态基线（非流式，400 请求，并发 20，3 轮）

| 轮次 | P50 | P95 | P99 | 吞吐 | 成功率 | 原始数据 |
| --- | --- | --- | --- | --- | --- | --- |
| 网关 #1 | 37.90 | 41.87 | 63.22 | 503.19 | 100% | `baseline-r1.json` |
| 网关 #2 | 37.74 | 44.62 | 62.90 | 503.30 | 100% | `baseline-r2.json` |
| 网关 #3 | 37.40 | 44.95 | 62.46 | 505.88 | 100% | `baseline-r3.json` |
| 直连上游 #1 | 36.03 | 43.57 | 61.44 | 520.22 | 100% | `upstream-only-r1.json` |
| 直连上游 #2 | 36.03 | 42.20 | 62.90 | 523.20 | 100% | `upstream-only-r2.json` |
| 直连上游 #3 | 36.04 | 41.84 | 72.38 | 511.96 | 100% | `upstream-only-r3.json` |

单位：延迟 ms，吞吐 req/s。

**网关单请求开销 = 37.7 − 36.0 ≈ 1.7ms（P50），吞吐保留 ≈ 97%**，P95/P99 与直连基本重合。

### 4.2 其余场景（`baseline-r1.json`）

| 场景 | 请求数 / 并发 | P50 | P95 | 首包 P50 | 吞吐 | 状态码 |
| --- | --- | --- | --- | --- | --- | --- |
| 流式成功 | 100 / 5 | 40.85 | 42.96 | 22.27 | 121.61 | 200 ×100 |
| 缺少 API Key | 100 / 5 | 2.08 | 2.88 | — | 2016.91 | 401 ×100 |
| 无权访问模型 | 100 / 5 | 2.15 | 2.77 | — | 2013.97 | 403 ×100 |

两条失败路径在鉴权/模型策略处短路，P50 约 2ms，且返回的是带业务语义的 401/403，说明拦截发生在调用上游之前。

流式场景首包（TTFT）P50 22.27ms，上游配置是 20ms，即网关对首包的额外延迟约 2ms。

### 4.3 并发梯度（与直连对照）

| 并发 | 网关 P50 | 网关吞吐 | 直连 P50 | 直连吞吐 | 数据文件 |
| --- | --- | --- | --- | --- | --- |
| 1 | 37.01 | 26.83 | 36.48 | 27.17 | `gateway-c1.json` / `upstream-only-c1.json` |
| 5 | 38.11 | 129.68 | 37.13 | 132.54 | `gateway-c5.json` / `upstream-only-c5.json` |
| 20 | 37.90 | 503.19 | 36.03 | 520.22 | `baseline-r1.json` / `upstream-only-r1.json` |
| 50 | 38.16 | 1161.62 | 未测 | 未测 | `gateway-c50.json` |

并发 20 → 50，吞吐从 503 涨到 1162（2.3 倍），P50 只从 37.90 涨到 38.16 —— 说明在这一段负载内网关还没到饱和点，延迟主要由上游那 35ms 决定。

## 五、一个把网关开销测成 44ms 的坑

第一次测出来的结果是：网关 P50 80.5ms vs 直连 36.4ms，看着像"网关每请求多花 44ms、吞吐腰斩"，差点写成结论。

排查过程：

1. 并发梯度显示 **c=1/c=5 时开销只有 1~2ms**，只有 c≥20 才突然多出 44ms —— 说明不是每请求固定成本，而是排队。
2. 关掉链路追踪（采样 0）重测，没有变化 —— 排除 tracing。
3. 44ms 这个量级本身很可疑：它接近 TCP 延迟确认（delayed ACK）的 40ms 定时器。
4. 直连测的时候客户端每条请求都新建连接，而网关是 keep-alive 复用连接 —— 差别正好落在"长连接上的 Nagle + 延迟确认"。

把 `scripts/mock_openai.py` 的 handler 加上 `disable_nagle_algorithm = True` 后立即复测：

| 配置 | 网关 P50 | 网关吞吐 | 直连 P50 | 数据来源 |
| --- | --- | --- | --- | --- |
| mock 默认（Nagle 开） | 80.5 | 244 | 36.4 | 当次定向复测（未留档） |
| mock 关闭 Nagle | 37.7 | 503 | 36.0 | 本目录 `baseline-r*.json` / `upstream-only-r*.json` |

44ms 全部来自上游 mock 的 Nagle，与网关无关。该修复已写进 `scripts/mock_openai.py`（附注释说明原因），避免后续再被误读。

**教训：在测"网关慢不慢"之前，先确认上游 mock 自己不是瓶颈 —— 必须有无网关的直连对照组，否则会把上游的问题算到网关头上。**

## 六、复现

```bash
export MSYS_NO_PATHCONV=1
CTX=E:/DeepSeek-work/shigure-ai-gateway
IMG_PY=docker.m.daocloud.io/library/python:3.12-slim
IMG_JRE=docker.m.daocloud.io/library/eclipse-temurin:17-jre

docker network create aigw-net 2>/dev/null
docker run -d --name aigw-redis --network aigw-net redis:7.2-alpine

# 打包（也可用本机 mvn）
docker run --rm -u root -v "C:/Users/sweyyuki/.m2:/root/.m2" -v "$CTX:/app" -w /app \
  jenkins-dlyk:2.528 mvn -B -DskipTests clean package

# 上游 mock：固定 35ms
docker run -d --name aigw-mock --network aigw-net -v "$CTX/scripts:/scripts" $IMG_PY \
  python /scripts/mock_openai.py --host 0.0.0.0 --port 18080 --ttft-ms 20 --latency-ms 15

# 网关
docker run -d --name aigw-gateway --network aigw-net -v "$CTX/target/shortlink-ai-gateway.jar:/app/app.jar" $IMG_JRE \
  java -jar /app/app.jar --server.port=8010 \
  --spring.cloud.nacos.discovery.enabled=false \
  --spring.cloud.service-registry.auto-registration.enabled=false \
  --spring.data.redis.host=aigw-redis --spring.data.redis.port=6379 \
  --short-link.ai-gateway.upstream.provider-base-url.openai=http://aigw-mock:18080 \
  --short-link.ai-gateway.tenant.enabled=true \
  --management.health.r2dbc.enabled=false

# 预热（丢弃），再正式测 3 轮
docker run --rm --network aigw-net -v "$CTX/scripts:/scripts" -v "$CTX/docs/load:/out" $IMG_PY \
  python /scripts/load_test.py --base-url http://aigw-gateway:8010 --requests 60 --concurrency 4 --output /out/warmup.json
docker run --rm --network aigw-net -v "$CTX/scripts:/scripts" -v "$CTX/docs/load:/out" $IMG_PY \
  python /scripts/load_test.py --base-url http://aigw-gateway:8010 --requests 400 --concurrency 20 --output /out/baseline-r1.json
```

说明：`--management.health.r2dbc.enabled=false` 只是本机未启 MySQL 时避免 `/actuator/health` 返回 503，与性能无关；租户鉴权必须开启，否则鉴权与模型策略两条失败路径不会被走到。

## 七、本目录数据文件

| 文件 | 用途 |
| --- | --- |
| `cold-start.json` | 未预热即压，用来说明为什么必须先预热 |
| `warmup.json` | 预热轮（结果丢弃） |
| `baseline-r1/r2/r3.json` | 稳态基线 3 轮 |
| `upstream-only-r1/r2/r3.json` | 直连上游对照 3 轮 |
| `gateway-c1/c5/c50.json`、`upstream-only-c1/c5.json` | 并发梯度 |

## 八、边界与没做的事

- 上游是 mock，不是真实 LLM：没有真实网络抖动、TTFT 长尾和供应商侧限流。
- 缓存、限流、安全过滤、熔断 fallback 均为关闭状态，这些路径的延迟没有覆盖。
- 未做故障注入（上游 500/超时/慢响应）下的网关表现。
- 未做长时间稳定性（内存、连接泄漏）观察，单轮最长约 1.5 秒。
