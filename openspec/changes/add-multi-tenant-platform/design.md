## Context

当前项目是一个基于 Spring Boot 3 + WebFlux 的 AI Gateway，主链路已具备 Provider 路由、Redis token 配额、响应缓存、安全治理、调用记录和 Prometheus 暴露能力，但这些能力均以“全局单实例网关”为前提。现有 `AiGatewayService` 直接基于 `HttpHeaders` 驱动路由、配额、缓存和观测逻辑，缺少统一的调用方身份模型，导致系统无法对不同租户、应用和 API Key 进行隔离治理。

本次变更的核心不是重写主链路，而是在保留现有网关编排结构的前提下，引入租户身份上下文，并将现有全局能力升级为 tenant-aware 能力。第一阶段继续复用 Redis 作为核心运行时存储，以降低改造成本并保持交付节奏；控制台 JWT 登录体系保持不变，不与租户 API Key 体系混用。

## Goals / Non-Goals

**Goals:**
- 为 OpenAI 兼容请求新增 API Key 鉴权能力，并解析出 tenant、app、key 三类身份信息。
- 在请求进入主链路前建立统一 `TenantContext`，供路由、配额、缓存和观测复用。
- 为租户提供模型白名单控制，在路由前完成访问权限校验。
- 将现有 Redis token 配额升级为租户级额度治理，支持额度预检、预扣和实际 token 用量修正。
- 为调用记录补充租户维度字段，并结合现有模型价格配置完成成本估算。
- 将现有精确缓存升级为租户隔离缓存，避免跨租户缓存污染。
- 暴露 Prometheus 指标，覆盖租户请求量、延迟、状态码、缓存命中、配额消耗和成本估算。

**Non-Goals:**
- 第一阶段不引入新的关系型数据库，不实现完整的租户后台资源管理系统。
- 第一阶段不实现真正的语义缓存，只保留租户隔离的精确缓存能力。
- 第一阶段不改造控制台 JWT 登录模型，也不实现组织管理员、租户管理员等细粒度 RBAC。
- 第一阶段不实现复杂的预算驱动路由、套餐计费、结算出账和自动扣费。
- 第一阶段不改变现有 OpenAI 兼容 API 契约，包括继续要求请求显式提供 `model` 字段。

## Decisions

### 1. 通过独立的 TenantContext 将租户身份接入主链路
- **Decision**: 新增 `TenantContext`、`ApiKeyAuthService` 和 `TenantContextResolver`，在网关入口处解析 `Authorization: Bearer <api-key>` 或兼容的 `x-api-key`，生成统一租户上下文并透传给 `AiGatewayService`。
- **Rationale**: 现有治理能力都依赖请求期信息，但直接基于原始 `HttpHeaders` 扩展会造成认证、配额和缓存逻辑耦合。显式引入 `TenantContext` 可以把身份解析与业务治理分层，并让主链路只依赖标准化身份对象。
- **Alternatives considered**:
  - 直接在 `AiGatewayService` 内解析 API Key：实现快，但会让服务继续膨胀，后续难以测试和扩展。
  - 全量使用 Reactor Context 隐式传递：适合响应式链路，但第一阶段会提高改造复杂度；优先采用显式参数/解析对象更稳。

### 2. 保持控制台安全体系与租户 API Key 体系分离
- **Decision**: 保留现有控制台 JWT 登录、静态 console 用户和管理接口权限校验逻辑；新增租户 API Key 鉴权仅用于 `/v1/chat/completions` 主调用链路。
- **Rationale**: 两类身份的使用场景和安全边界不同。将 console 用户与租户调用凭证分离，可以避免把运维控制面和数据平面耦合在一起，也能减少对现有管理接口的破坏。
- **Alternatives considered**:
  - 统一到单一安全模型：长期更整齐，但第一阶段需要重写较多管理接口和审计逻辑，收益低于成本。

### 3. 先用内存/配置 + Redis 组合承载第一阶段租户数据
- **Decision**: 第一阶段的 API Key、租户模型白名单和租户配额策略允许来自配置或轻量内存注册表；运行时额度、缓存、指标和 usage 聚合继续依赖 Redis。
- **Rationale**: 当前项目已经深度依赖 Redis 承载治理数据，复用现有基础设施能够快速完成平台化改造，同时避免过早引入数据库迁移、Repository 和数据一致性问题。
- **Alternatives considered**:
  - 直接接入 MySQL/PostgreSQL：更接近最终形态，但会显著扩大改造面，使变更从“平台化能力升级”变成“基础设施重构”。

### 4. 让现有治理服务 tenant-aware，而不是重写新服务
- **Decision**: 对 `RedisTokenQuotaService`、`QuotaKeyGenerator`、`AiCacheKeyService`、`AiGatewayMetricsRecorder`、`AiCallRecord` 做增量改造，使 Redis key 和指标维度增加 tenantId/appId/keyId，而不新建平行治理栈。
- **Rationale**: 当前治理服务已经在主链路中稳定使用，增量改造能够最大限度复用既有逻辑、测试和接口，降低回归风险。
- **Alternatives considered**:
  - 新建完整的多租户治理实现并逐步替换旧服务：架构更纯粹，但会带来较大的双轨维护成本。

### 5. 在路由前引入租户模型白名单校验
- **Decision**: 新增 `TenantModelPolicyService`，在 `ProviderRoutingService` 做 provider/model 解析之前先校验租户是否允许访问请求模型，必要时应用租户默认模型映射策略，但不放宽公开 API 对 `model` 字段的必填约束。
- **Rationale**: 模型访问控制本质上是身份权限问题，不应混入 provider 选择逻辑。将其前置可以明确“先授权、后路由”的顺序，也更利于失败语义统一为客户端错误。
- **Alternatives considered**:
  - 在 `ProviderRoutingService` 内联模型权限判断：改动少，但会让路由服务同时承担权限职责，边界不清晰。

### 5.1 收敛 tenant 默认模型语义以保持 API 契约稳定
- **Decision**: 将“tenant default model”解释为租户级模型映射/重写策略，而不是允许客户端省略 `model` 字段。
- **Rationale**: 当前公开 API 和 DTO 校验已要求 `model` 必填，放宽该约束会引入公开契约变更，与第一阶段目标冲突。
- **Alternatives considered**:
  - 允许 `model` 为空并在服务端填充默认值：能够满足更宽松的调用体验，但属于公开 API 行为变化，需在后续单独变更中处理。

### 6. 租户级 token 配额沿用预估预扣 + 实际修正模式
- **Decision**: 继续使用当前 `preCheck -> reserve -> adjustByActualUsage` 模式，只是把 quota key 扩展为 tenant/app/key/provider/model 维度，并补充月额度支持。
- **Rationale**: 当前链路已支持在请求前做 token 估算并在成功后按实际 token 调整，能兼容流式和非流式场景。tenant-aware 改造无需改变核心配额算法，只需升级维度和策略来源。
- **Alternatives considered**:
  - 完全按实际 token 后扣费：对短期统计友好，但无法在请求前做限额拒绝，不满足平台治理诉求。

### 7. 精确缓存 key 必须纳入 tenant/app 维度
- **Decision**: `AiCacheKeyService` 生成缓存 key 时纳入 tenantId、appId、provider、providerModel 以及请求关键参数；仅缓存非流式响应，语义缓存继续保持非强制占位实现。
- **Rationale**: 多租户场景下不能允许跨租户命中缓存。继续限制在精确缓存范围内可以控制一致性风险，避免语义缓存误命中带来的错误回答和权限泄露问题。
- **Alternatives considered**:
  - 直接启用语义缓存：简历亮点更强，但当前仓库只有 `NoopSemanticCacheService`，贸然引入会让改造目标失焦。

### 8. 调用记录与 Prometheus 指标复用现有观测体系扩展维度
- **Decision**: 扩展 `AiCallRecord` 和 `AiGatewayMetricsRecorder`，为每次调用增加 tenantId、appId、keyId、cacheHit、estimatedCost 等字段，并通过 Micrometer 暴露租户请求量、延迟、错误率、缓存命中率、token 消耗和成本估算指标。
- **Rationale**: 当前项目已经有调用记录、成本估算和 Prometheus 入口，最优路径是直接补租户维度，而不是建设新的观测系统。
- **Alternatives considered**:
  - 新建专门账单服务：更利于长期演进，但超出本次改造范围。

## Risks / Trade-offs

- **[Risk] API Key 与租户策略先放配置/内存，运行时修改持久化不足** → **Mitigation**: 在设计中明确这是第一阶段取舍，并为后续数据库化预留独立的 `TenantContextResolver`、策略服务和领域对象边界。
- **[Risk] 租户维度加入后，Redis key 和 Prometheus 标签维度膨胀** → **Mitigation**: 控制标签基数，优先暴露 tenant/app 级聚合指标，避免将原始 requestId 等高基数字段作为指标标签。
- **[Risk] 主链路方法签名增加 `TenantContext` 后会影响现有测试和调用点** → **Mitigation**: 通过集中入口改造和默认测试辅助构造器降低影响面，并优先补充针对身份解析、quota key 和 cache key 的单测。
- **[Risk] 配额预估与实际 token 存在偏差，可能导致额度短期误差** → **Mitigation**: 保留实际 token 回写修正机制，并在指标中区分预估与实际统计值。
- **[Risk] 精确缓存纳入租户维度后，缓存复用率下降** → **Mitigation**: 将其视为必要的隔离成本，并通过后续语义缓存或租户内热点优化弥补命中率。
- **[Risk] 主链路前置鉴权可能影响现有客户端透传上游 key 的行为** → **Mitigation**: 明确区分“平台 API Key”与“上游 Provider 凭证”，第一阶段租户鉴权用于识别调用方，不强制接管上游 provider 密钥管理。

## Migration Plan

1. 新增租户身份模型和 API Key 鉴权服务，在不改变现有 OpenAI 兼容接口的前提下为请求建立 `TenantContext`。
2. 改造 `AiGatewayService` 主链路，使路由、配额、缓存和指标记录读取 `TenantContext`。
3. 升级 `QuotaKeyGenerator`、`RedisTokenQuotaService` 和 `AiCacheKeyService`，完成租户级 Redis key 改造。
4. 扩展 `AiCallRecord`、`AiGatewayMetricsRecorder` 和 Micrometer 指标，增加租户维度 usage/cost/cache 指标。
5. 补充单元测试与集成测试，覆盖 API Key 鉴权、模型白名单拒绝、租户配额隔离、租户缓存隔离和指标记录。
6. 部署时默认关闭新能力或使用最小示例配置灰度启用，若发现问题，可通过关闭租户鉴权/缓存/配额相关开关回退到现有全局网关行为。

## Open Questions

- 第一阶段 API Key、租户白名单和配额策略采用纯配置文件、内存注册表，还是补一组轻量管理接口动态维护？
- 是否需要在第一阶段就区分 tenant 与 app 两级配额，还是先以 tenant 为主、app 为辅？
- Prometheus 指标默认是否暴露 `tenantId` 标签，还是仅通过管理接口查看租户维度聚合，避免高基数风险？
- 第一阶段是否需要为租户调用记录提供查询/导出接口，还是先只完成内部记录和指标埋点？
