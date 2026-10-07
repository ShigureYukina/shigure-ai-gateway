# 配置中心（Nacos Config）

网关在 Nacos 服务发现之上接入了 Nacos Config：启动时从 Nacos 导入配置，运行期变更动态刷新，不重启进程。

## 快速使用

```bash
# 1. 起依赖（含 standalone Nacos，8848 HTTP + 9848/9849 gRPC 都要暴露，2.x 客户端走 gRPC）
docker compose up -d nacos

# 2. 往配置写一个动态键（dataId 默认 short-link-ai-gateway.yml，组 DEFAULT_GROUP）
curl -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=short-link-ai-gateway.yml" \
  --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode "type=yaml" \
  --data-urlencode $'content=short-link:\n  ai-gateway:\n    rate-limit:\n      default-token-quota-per-minute: 200\n'

# 3. 以默认 profile 启动网关（local profile 显式关掉了 config，见下）
mvn spring-boot:run
# 观察日志：Nacos 命中后 rate-limit 配置立即生效；改 Nacos 上的值再观察刷新日志
```

## 导入机制

- `application.yml` 声明 `spring.config.import: optional:nacos:${NACOS_CONFIG_DATAID:short-link-ai-gateway.yml}`：
  - `optional:` 前缀保证 **Nacos 不可达时只告警、照常启动**（本地零依赖跑法不受影响）；
  - dataId / group / namespace / 开关都可用环境变量覆盖（`NACOS_CONFIG_ENABLED`、`NACOS_CONFIG_DATAID`、`NACOS_CONFIG_GROUP`、`NACOS_CONFIG_NAMESPACE`）。
- `application-local.yml` 与 discovery 同策略显式关闭 config（`spring.cloud.nacos.config.enabled=false`），避免本地起服务时后台客户端反复重连刷日志；要用时用 `-Dspring.cloud.nacos.config.enabled=true` 临时打开。
- `application-prod.yml` 的 `nacos.config.server-addr` 与 discovery 共用 `NACOS_SERVER_ADDR` 且不设默认值：缺变量直接启动失败（fail-fast）。

## 动态刷新与多配置源优先级

刷新链路刻意没有引入第二套机制（没有 `@RefreshScope`）：

```
Nacos 配置变更
  → NacosContextRefresher 发布 RefreshEvent
  → ContextRefresher 重读 Environment
  → ConfigurationPropertiesRebinder 把 AiGatewayProperties 整体重绑（无 @RefreshScope 也能生效，
    因为请求路径每次都直接读这个单例的字段）
  → NacosConfigRefreshBridge 监听 EnvironmentChangeEvent，
    把"DB 里有落库的域"重新 apply 一遍（RuntimeConfigService.reapplyDatabaseOverrides）
```

最终取值优先级（每层的职责见 `runtime/RuntimeConfigSupport` 与 `runtime/RuntimeConfigService` 的 javadoc）：

| 优先级 | 配置源 | 说明 |
|---|---|---|
| 1 | 环境变量 kill-switch | 如 `AI_GATEWAY_SECURITY_FORCE_ENABLED`，代码里直接读 env，防"DB 加载失败导致安全域静默关闭" |
| 2 | DB runtime-config | 控制台写入并落库的域（六个域：routing/rate-limit/cache/safety/plugin/security），跨实例由 Redis 版本号轮询同步 |
| 3 | Nacos Config | `spring.config.import` 导入的键，动态刷新 |
| 4 | 本地 application.yml | 兜底默认值 |

对账桥只重放**落过库的域**：没落库的域在重绑后 Nacos 新值直接生效——如果把它也重放到启动基线，等于把这次刷新结果回滚掉。

已知边界（预期行为）：没有 DB 持久化的实例（`tenant.persistence.enabled=false`，即本地默认跑法）里，控制台的内存改动本来就不跨实例、不跨重启；配置源刷新触发整体重绑后，这些内存改动会被配置源的值重置。

## 测试

- `NacosConfigRefreshBridgeTest`：纯单测，验证"重绑冲掉 DB 覆盖 → 桥恢复"与"无 DB 时桥是无害空操作"。
- `NacosConfigImportIntegrationTest`：端到端验证导入值确实来自 Nacos（CI 先起 nacos 服务容器、用 OpenAPI 预置 dataId，再断言 `quota-retry-after-seconds` 是 Nacos 上的 99 而非本地 yml 的 60）。本地没有 Nacos，靠 `NACOS_CONFIG_IT=true` 环境变量门控，本地自动跳过。

## 面试挂靠要点

- 为什么不用 `@RefreshScope`：网关全部配置都收敛在一个可变的 `@ConfigurationProperties` 单例（`AiGatewayProperties`）上，请求路径按字段读取；重绑 + 就地覆盖即可生效，不必给几十个消费点包代理对象。
- 多配置源怎么不打架：按"越动态的源优先级越低"排序（env 兜底安全 > DB 运维操作 > Nacos 运营调参 > yml 出厂默认），刷新后用对账桥恢复更高优先级源的覆盖。
- `optional:` 的语义边界：它只兜"服务器不可达"，不兜"dataId 不存在但服务器可达"——后者导入为空、直接吃 yml 默认值，集成测试专门防这一点。
