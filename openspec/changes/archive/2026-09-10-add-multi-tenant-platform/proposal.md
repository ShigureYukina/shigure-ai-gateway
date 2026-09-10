## Why

当前 AI Gateway 已具备路由、配额、缓存和可观测等全局治理能力，但仍缺少面向平台化场景的多租户身份与资源隔离机制，无法支撑企业内部多个团队或应用安全、可控地共享同一网关。现在推进该变更，可以在复用现有主链路的基础上，把单实例网关演进为具备鉴权、权限、成本治理和降本能力的多租户 AI 接入平台。

## What Changes

- 新增 API Key 鉴权能力，为每次模型调用解析租户、应用和密钥身份。
- 新增租户模型白名单能力，限制不同租户可访问的模型集合。
- 将现有 Redis token 配额能力升级为租户级治理，支持按租户/应用进行额度校验与扣减。
- 扩展调用记录链路，记录租户维度 usage，并基于模型价格进行成本估算。
- 将现有精确缓存升级为租户隔离缓存，避免跨租户缓存污染并统计命中收益。
- 补充 Prometheus 指标，输出租户、模型、缓存、配额与成本相关监控数据。

## Capabilities

### New Capabilities
- `tenant-api-key-auth`: 通过 API Key 识别租户、应用和密钥身份，并为请求建立统一的租户上下文。
- `tenant-model-policy`: 为租户配置模型白名单并在请求进入路由前完成模型访问校验。
- `tenant-token-quota`: 基于 Redis 提供租户级 token 配额预检、扣减和实际用量修正能力。
- `tenant-usage-billing`: 记录租户维度调用明细、token 使用量与成本估算结果，支撑账单与统计分析。
- `tenant-exact-cache`: 提供租户隔离的精确缓存能力，并统计命中、写入与节省收益。
- `tenant-observability-metrics`: 暴露 Prometheus 指标，覆盖租户请求量、延迟、错误率、缓存命中率、配额消耗与成本估算。

### Modified Capabilities
- 无

## Impact

- 影响主调用链路：`src/main/java/com/nageoffer/shortlink/aigateway/service/AiGatewayService.java`
- 影响安全与身份相关模块：新增 API Key 鉴权、租户上下文解析与请求透传能力
- 影响治理模块：`RedisTokenQuotaService`、`QuotaKeyGenerator`、`AiCacheKeyService`、`RedisResponseCacheService`
- 影响可观测模块：`AiGatewayMetricsRecorder`、`AiCallRecord`、Prometheus 指标输出
- 影响配置与管理接口：新增租户/模型策略/配额相关配置和管理能力
- 继续依赖 Redis 作为第一阶段核心存储，不引入新的必选基础设施
