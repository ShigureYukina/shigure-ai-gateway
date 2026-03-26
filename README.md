# shortlink-ai-gateway

一个基于 Spring Boot 3 + WebFlux 的 AI 网关项目，提供 OpenAI 兼容入口，支持多 Provider 路由、流式转发与治理能力。

## 1. 项目定位

- 面向 LLM 接入的统一网关层。
- 对外暴露 OpenAI 兼容接口：`/v1/chat/completions`。
- 对内解耦不同上游 Provider（如 OpenAI / Claude 兼容接口）。

## 2. 核心能力

- OpenAI 兼容入口（流式 / 非流式）
- Provider 路由 + 模型别名映射
- 超时、重试、失败回退（Fallback）
- Token 配额治理（Redis）
- 响应缓存 / 语义缓存开关
- 输入输出安全拦截
- 插件链扩展（请求前 / 响应后）
- 可观测数据记录（延迟、状态、token）
- Resilience4j 熔断保护（provider 级）

## 3. 技术栈

- Java 17
- Spring Boot 3.3.2
- Spring Cloud Gateway + WebFlux
- Spring Data Redis
- JUnit 5 + Reactor Test
- Maven

## 4. 快速启动（本地）

### 4.1 前置依赖

- JDK 17
- Maven（`mvn`）
- Docker（可选，用于启动 Redis）

### 4.2 启动 Redis（推荐）

```bash
docker compose up -d redis
```

### 4.3 启动应用（local profile）

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

默认端口：`8010`

### 4.4 启用 MySQL / R2DBC（可选）

当前项目已支持 **R2DBC + MySQL** 的 tenant 配置持久化读取与管理写入，但默认关闭。

#### 1）先初始化数据库

手动执行脚本：

```bash
src/main/resources/sql/init_multi_tenant_platform_mysql.sql
```

#### 2）设置数据库环境变量

```bash
AI_GATEWAY_R2DBC_URL=r2dbc:pool:mysql://127.0.0.1:3306/ai_gateway
AI_GATEWAY_DB_USERNAME=root
AI_GATEWAY_DB_PASSWORD=root
AI_GATEWAY_TENANT_DB_ENABLED=true
```

#### 3）启动后行为

- tenant API Key / model policy / quota policy / model price 优先从 DB 读取
- 若 DB 不可用或未命中，则回退到 `application.yml`
- Redis 仍负责 quota ledger、exact cache 与运行时指标

### 4.5 生产配置（prod profile）

生产环境请使用 `prod` profile，并通过环境变量注入密钥与账号口令：

```bash
AI_GATEWAY_JWT_SECRET=***
AI_GATEWAY_ADMIN_PASSWORD=***
AI_GATEWAY_VIEWER_PASSWORD=***
NACOS_SERVER_ADDR=***
```

配置文件：`src/main/resources/application-prod.yml`

## 5. 常用构建与测试命令

```bash
# 编译（不跑测试）
mvn -DskipTests compile

# 运行全部测试
mvn test

# 完整校验
mvn clean verify

# 打包
mvn clean package -DskipTests
```

### 单测（重点）

```bash
# 单个测试类
mvn -Dtest=ProviderRoutingServiceTest test

# 单个测试方法
mvn -Dtest=ProviderRoutingServiceTest#shouldResolveAliasProviderAndModel test

# 多个测试类
mvn -Dtest=ProviderRoutingServiceTest,SseStreamE2ETest test
```

### 覆盖率（JaCoCo）

```bash
# 运行测试并生成覆盖率报告
mvn clean verify
```

报告路径：`target/site/jacoco/index.html`

## 6. 接口示例

### 非流式

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

### 流式（SSE）

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

## 7. 本地“近真实”E2E 模拟

项目内置本地 mock 上游脚本，可在无真实外部 API 的情况下验证主流程。

```bash
mvn -DskipTests package
powershell -ExecutionPolicy Bypass -File "scripts/simulate_e2e.ps1"
```

会自动：
- 启动本地 mock provider（`127.0.0.1:18080`）
- 启动网关（`127.0.0.1:18010`）
- 分别验证非流式与流式链路

## 8. 配置说明

主配置：`src/main/resources/application.yml`

建议通过环境变量覆盖敏感配置，例如：

- `AI_GATEWAY_JWT_SECRET`
- `AI_GATEWAY_ADMIN_PASSWORD`
- `AI_GATEWAY_VIEWER_PASSWORD`
- `REDIS_HOST` / `REDIS_PORT`
- `NACOS_SERVER_ADDR`
- `AI_GATEWAY_R2DBC_URL`
- `AI_GATEWAY_DB_USERNAME`
- `AI_GATEWAY_DB_PASSWORD`
- `AI_GATEWAY_TENANT_DB_ENABLED`

## 9. 工程化

- CI：`.github/workflows/ci.yml`（`clean verify` + 覆盖率门禁 + 打包并上传制品）
- 依赖安全扫描：OWASP Dependency-Check（支持 `NVD_API_KEY`）
- 代理/协作规范：`AGENTS.md`

## 10. 监控与熔断

- Prometheus 指标地址：`/actuator/prometheus`
- 健康检查：`/actuator/health`
- Resilience4j 熔断实例：`provider-openai`、`provider-claude`

> 生产环境建议在 CI 设置 `NVD_API_KEY`（GitHub Secrets）以减少 dependency-check 更新时间。

## 11. 后续优化建议

- 增加 Testcontainers 集成测试（Redis）
- 补充 Prometheus + Grafana 观测面板
- 增加真实上游 smoke test（受控 key / 限额）
- 输出性能基线（P95、错误率、吞吐）
