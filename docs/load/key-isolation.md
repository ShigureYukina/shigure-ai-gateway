# 单 Key 故障隔离在故障注入下的表现

> **状态：数据已采集三轮（c-1 修复前 / c-1 后 / c-2 后），Docker 环境，有 Redis，CPU 限 4 核。**
> 绝对吞吐不可与 `README.md` 基线并列（CPU 差异同 `fault-injection.md` 第二节）；
> 相对结论不受影响。场景为非流式 `non_stream_success`。

## 一、要回答的问题

`fault-injection.md §4.2` 记录了一个现象：**一次性 25% 的限流足以让整个渠道被摘掉**。
本文件专门验证"故障隔离到单把 Key"这个设计——它是否真的能把一把 Key 的故障挡在渠道之外，
在什么条件下会失效，以及修完之后是否真的修好。

隔离有两层，必须分开看：

| 层 | 代码 | 粒度 | 参数 |
| --- | --- | --- | --- |
| **Key 池冷却** | `ProviderKeyPoolService` | 单把 Key | 连败 2 次、冷却 60s，实例内存态 |
| **通道熔断** | Resilience4j `circuitBreaker("provider-"+provider)` | 整个 provider | 窗口 20 / 最少 10 次 / 失败率 50% / OPEN 10s |

## 二、口径

- 两把 Key 都通过命令行注入：`--…provider-credentials.openai.api-keys[0]=openai-bad`、
  `[1]=openai-good`；单 Key 组只注入坏 Key。
- 故障用 mock 的 `--fail-auth-key sk-pool-bad` 注入：**只对携带指定 Key 的请求**返回 429
  （默认 `--fail-auth-key-status 429`），其余请求正常。这是"只打坏其中一把 Key"的关键。
- 每组前重启网关，熔断器状态不跨组继承；预热一律对着健康 mock，故障只在测量阶段注入。
- 200 请求 / 上游固定 35ms；`--seed 42`。
- 三轮之间的代码差异只有两处修复：**c-1**（第五节）与 **c-2**（第六节），配置其余完全相同。

## 三、结果（c-1 前 / c-1 后 / c-2 后）

| 组 | 配置 | 并发 | 成功率 | 状态分布（c-2 后） | `cooling down` | 熔断拒绝 |
| --- | --- | --- | --- | --- | --- | --- |
| pool-serial | 池 2 Key，只坏 key#1 | 2 | **99% / 99% / 99%** | 200×198，504×2 | 1 / 1 / 1 | 0 / 0 / 0 |
| pool-concurrent | 池 2 Key，只坏 key#1 | 20 | **6.5% / 5% / 94%** | 200×188，504×12 | 232 / 6 / 11 | 362 / 366 / **0** |
| single-key | 单 Key（=坏 key） | 20 | **0% / 0% / 0%** | 504×200 | 249 / 8 / 249 | 384 / 382 / **0** |

（每格三段按"修复前 / c-1 后 / c-2 后"排列。c-2 后 pool-concurrent 的 mock 按 Key 命中：
坏键 24 / 好键 238。）

## 四、机制

**低并发（pool-serial，99%）**：坏 Key 连败 2 次即进入 60s 冷却，后续请求由好 Key 承接。
冷却在下一个请求到来前已落地，所以隔离生效。

**并发突发（修复前 pool-concurrent，6.5%）**：坏 Key 的 429 同时喂了两层——

1. `ProviderKeyPoolService.isKeyRelated` 把 429 记为**该 Key 失败**（连败 2 次、冷却 60s）；
2. `UpstreamCallExecutor.isProviderChannelFault` 把 `PROVIDER_RATE_LIMITED` 记为**通道故障**，
   推高 `provider-openai` 熔断失败率 → 约第 19 个请求熔断器 OPEN。

熔断器 OPEN 后抛出的 `CallNotPermittedException` 是**网关自己的拒绝**，却因 `isKeyRelated`
对非 `AiGatewayClientException` 的异常默认返回 true，被记到**当前选中的那把 Key**头上——
修复前日志里好 Key 的冷却原因正是 `CircuitBreaker 'provider-openai' is OPEN`（134 次）。
两把 Key 相继被连坐冷却后，`select` 在所有 Key 都冷却时**退回冷却池继续挑**，
于是每轮又重新选中坏 Key → 熔断拒绝 → 继续记账。

## 五、c-1：熔断器拒绝不再连坐好 Key

**改的什么**：`ProviderKeyPoolService.isKeyRelated` 开头早返回——遇 `CallNotPermittedException`
（请求根本没打到上游）返回 `false`，不再冷却当前 Key。配套单测
`ProviderKeyPoolServiceTest.shouldNotCoolKeyForCircuitBreakerRejection`。

**改对了它那一层**：冷却日志 232 → **6**，且**只落在坏 Key 上**（好 Key 0 条）。
但成功率 6.5% → 5% **没动**——因为端到端瓶颈不在 Key 池，而在熔断层（429 计入失败率）。

## 六、c-2：429 不再计入通道熔断失败率

**改的什么**：429 是"上游按配额临时踩刹车"，不是"通道坏了"。新增
`AiGatewayUpstreamRateLimitedException`（继承 `AiGatewayUpstreamException`），
`UpstreamCallExecutor.buildUpstreamException` 把 429 分流到该类，并在两个 provider 熔断器的
`ignore-exceptions` 里排除它。这样熔断失败率只反映真正的通道故障，口径与探测路径
（`ChannelProbeService` 不计 429）一致。

**取舍**：放弃了"渠道级持续被限流就停用"的硬保护——那个问题本该用**速率**表达，
不该复用错误率熔断器。兜底仍在：Key 冷却（按 429）+ 健康分 + 跨通道回退。

**效果（决定性）**：
- pool-concurrent 成功率 **5% → 94%**，熔断拒绝 366 → **0**——熔断器不再因限流跳闸。
- 剩余 12 个 504 是坏 Key 冷却落地前打进去的请求，符合预期（2 把 Key 分担，坏键仍被命中 24 次）。
- single-key 组仍是 0%——只有一把 Key 且被限流，本就无法服务；但失败形态从
  `500 CircuitBreaker is OPEN`（渠道被误判摘除）变成干净的 **504"上游 429 重试耗尽"**，
  渠道不再被错误地宣告死亡，同时 `UPSTREAM_RETRY_EXHAUSTED` 仍会让健康分降级、触发回退。

**结论：三轮数据把瓶颈精确锁定在熔断层，修完后并发突发场景从 6.5% 恢复到 94%。**

## 七、未覆盖 / 观察

- 修复后"重试耗尽(429)"对外仍返回 **504**（超时语义），严格说更应是 429 / 503 + `Retry-After`。属错误码映射的后续改进，不影响隔离结论。
- **短脉冲 429 未单独测**：现有注入是持续 429；"瞬时限流 vs 持续故障"的区分器未实现（现按 Key 冷却阈值 2 反应，预期脉冲也会冷却该 Key）。
- 只有非流式；流式下的 Key 池/熔断行为未测。
- 未覆盖故障恢复维度（OPEN 10s 后半开、探测恢复的过程未观测）。

## 八、复现

```bash
export MSYS_NO_PATHCONV=1
cd E:/DeepSeek-work/shigure-ai-gateway
bash scripts/run_key_isolation.sh
```

三轮原始数据分别在 `docs/load/data/before-c1/`、`after-c1/`、`after-c2/`
（`key-isolation-*.json` / `-gateway.log` / `-stats.json` 与 `key-isolation-run.log`）。

## 附：首字节硬边界已有可执行断言

`src/test/java/com/nageoffer/shortlink/aigateway/service/UpstreamCallExecutorStreamFallbackBoundaryTest.java`
覆盖了流式回退的首字节边界（此前仓库无任何断言）：

- `shouldFallbackWhenPrimaryFailsBeforeFirstChunk`：主通道吐 chunk 前失败 → 回退到备通道；
- `shouldNotFallbackAfterFirstChunkWhenPrimaryStreamBreaks`：主通道吐 2 个 chunk 后断流 →
  **不回退**，以错误帧 + `[DONE]` 结束，备通道 `never()` 被调用。

这是应用内集成级断言（真实 `UpstreamCallExecutor` 链路 + 真实熔断/重试算子链，仅适配器与
上游为桩），可进 CI；**尚未做端到端故障注入**（harness 只配一个 provider，跨供应商回退的
HTTP 级验证仍缺，见 `fault-injection.md §4.4`）。
