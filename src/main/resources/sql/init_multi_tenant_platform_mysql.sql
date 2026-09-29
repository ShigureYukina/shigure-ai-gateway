-- 多租户 AI Gateway 初始化脚本（MySQL 8.x）
-- 说明：
-- 1. 该脚本面向“配置/Redis 第一阶段”之后的数据库化演进，字段尽量对齐当前代码中的 Tenant / ModelPolicy / QuotaPolicy / AiCallRecord。
-- 2. 当前仓库尚未接入 JPA/MyBatis/Flyway/Liquibase，本脚本先作为基线建表与初始化数据脚本。
-- 3. 现阶段 API Key 在代码中以明文配置读取；为兼容当前实现，表中保留 api_key 字段。生产环境建议改造为哈希存储。

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS tenant (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    tenant_id           VARCHAR(64)  NOT NULL COMMENT '租户唯一标识，对齐 TenantContext.tenantId',
    tenant_name         VARCHAR(128) NOT NULL COMMENT '租户名称',
    tenant_status       VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    description         VARCHAR(512) NULL COMMENT '描述',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_tenant_tenant_id (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '租户主表';

CREATE TABLE IF NOT EXISTS tenant_app (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    tenant_id           VARCHAR(64)  NOT NULL COMMENT '租户标识',
    app_id              VARCHAR(64)  NOT NULL COMMENT '应用标识，对齐 TenantContext.appId',
    app_name            VARCHAR(128) NOT NULL COMMENT '应用名称',
    app_status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    description         VARCHAR(512) NULL COMMENT '描述',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_tenant_app (tenant_id, app_id),
    KEY idx_tenant_app_tenant_id (tenant_id),
    CONSTRAINT fk_tenant_app_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '租户应用表';

CREATE TABLE IF NOT EXISTS tenant_api_key (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    tenant_id           VARCHAR(64)  NOT NULL COMMENT '租户标识',
    app_id              VARCHAR(64)  NOT NULL COMMENT '应用标识',
    key_id              VARCHAR(64)  NOT NULL COMMENT '平台内部 key 标识，对齐 TenantContext.keyId',
    api_key             VARCHAR(512) NOT NULL COMMENT '平台 API Key，enc:v1: 前缀表示 AES-GCM 加密，兼容明文',
    api_key_hash        CHAR(64)     NULL COMMENT 'HMAC-SHA256(主密钥, 明文 Key)，用于确定性查找；未配主密钥时为空并回退明文查找',
    key_name            VARCHAR(128) NULL COMMENT '凭证名称',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    expires_at          DATETIME     NULL COMMENT '过期时间',
    last_used_at        DATETIME     NULL COMMENT '最近使用时间',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_tenant_api_key_hash (api_key_hash),
    UNIQUE KEY uk_tenant_api_key_key_id (tenant_id, app_id, key_id),
    KEY idx_tenant_api_key_lookup (tenant_id, app_id, enabled, expires_at),
    CONSTRAINT fk_tenant_api_key_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
    CONSTRAINT fk_tenant_api_key_app FOREIGN KEY (tenant_id, app_id) REFERENCES tenant_app (tenant_id, app_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '租户 API Key 凭证表';

-- 上游 Provider 凭证：tenant_id = '*' 表示平台级凭证，其余为该租户的 BYOK 凭证。
-- 该表不对 tenant 建外键，因为 '*' 是平台级伪租户。
CREATE TABLE IF NOT EXISTS provider_credential (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    tenant_id           VARCHAR(64)  NOT NULL DEFAULT '*' COMMENT '租户标识，* 表示平台级',
    provider            VARCHAR(64)  NOT NULL COMMENT '上游 provider 标识，如 openai / claude',
    api_key             VARCHAR(1024) NOT NULL COMMENT '上游 API Key，enc:v1: 前缀表示 AES-GCM 加密',
    auth_header         VARCHAR(64)  NOT NULL DEFAULT 'Authorization' COMMENT '承载凭证的请求头',
    auth_scheme         VARCHAR(32)  NOT NULL DEFAULT 'Bearer' COMMENT '认证 scheme，空串表示原样放置 Key',
    extra_headers       VARCHAR(1024) NULL COMMENT '附加请求头 JSON，如 anthropic-version',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_provider_credential (tenant_id, provider),
    KEY idx_provider_credential_provider (provider, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '上游 Provider 凭证表';

CREATE TABLE IF NOT EXISTS tenant_model_policy (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    tenant_id           VARCHAR(64)  NOT NULL COMMENT '租户标识',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用模型策略',
    default_model_alias VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '默认模型别名',
    default_model       VARCHAR(128) NULL COMMENT '默认模型',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_tenant_model_policy_tenant_id (tenant_id),
    CONSTRAINT fk_tenant_model_policy_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '租户模型策略主表';

CREATE TABLE IF NOT EXISTS tenant_model_policy_allowed_model (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    tenant_id           VARCHAR(64)  NOT NULL COMMENT '租户标识',
    allowed_model       VARCHAR(128) NOT NULL COMMENT '允许访问的模型',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_tenant_allowed_model (tenant_id, allowed_model),
    KEY idx_tenant_allowed_model_tenant_id (tenant_id),
    CONSTRAINT fk_tenant_allowed_model_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '租户允许模型明细表';

CREATE TABLE IF NOT EXISTS tenant_model_mapping (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    tenant_id           VARCHAR(64)  NOT NULL COMMENT '租户标识',
    request_model       VARCHAR(128) NOT NULL COMMENT '客户端请求模型/别名',
    provider_model      VARCHAR(128) NOT NULL COMMENT '最终映射模型',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_tenant_model_mapping (tenant_id, request_model),
    KEY idx_tenant_model_mapping_tenant_id (tenant_id),
    CONSTRAINT fk_tenant_model_mapping_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '租户模型映射表';

CREATE TABLE IF NOT EXISTS tenant_quota_policy (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    tenant_id               VARCHAR(64) NOT NULL COMMENT '租户标识',
    enabled                 TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否启用配额策略',
    token_quota_per_minute  BIGINT      NULL COMMENT '分钟 token 配额',
    token_quota_per_day     BIGINT      NULL COMMENT '天 token 配额',
    token_quota_per_month   BIGINT      NULL COMMENT '月 token 配额',
    created_at              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_tenant_quota_policy_tenant_id (tenant_id),
    CONSTRAINT fk_tenant_quota_policy_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '租户配额策略表';

CREATE TABLE IF NOT EXISTS ai_model_price (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    model               VARCHAR(128) NOT NULL COMMENT '模型标识',
    input_per_1k        DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '每 1K 输入 token 单价',
    output_per_1k       DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '每 1K 输出 token 单价',
    currency            VARCHAR(16)  NOT NULL DEFAULT 'USD' COMMENT '币种',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_ai_model_price_model (model)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '模型价格配置表';

CREATE TABLE IF NOT EXISTS ai_call_record (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    request_id          VARCHAR(64)  NOT NULL COMMENT '请求 ID',
    tenant_id           VARCHAR(64)  NOT NULL COMMENT '租户标识',
    app_id              VARCHAR(64)  NOT NULL COMMENT '应用标识',
    key_id              VARCHAR(64)  NOT NULL COMMENT 'API Key 标识',
    provider            VARCHAR(64)  NOT NULL COMMENT '模型供应商',
    model               VARCHAR(128) NOT NULL COMMENT '模型名称',
    token_in            BIGINT       NOT NULL DEFAULT 0 COMMENT '输入 token',
    token_out           BIGINT       NOT NULL DEFAULT 0 COMMENT '输出 token',
    latency_millis      BIGINT       NOT NULL DEFAULT 0 COMMENT '延迟毫秒',
    status              INT          NOT NULL COMMENT 'HTTP/业务状态码',
    estimated_cost      DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '估算成本',
    cache_hit           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否命中缓存',
    call_timestamp      BIGINT       NOT NULL COMMENT '调用时间戳（毫秒）',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    KEY idx_ai_call_record_tenant_time (tenant_id, call_timestamp),
    KEY idx_ai_call_record_app_time (tenant_id, app_id, call_timestamp),
    KEY idx_ai_call_record_model_time (provider, model, call_timestamp),
    KEY idx_ai_call_record_request_id (request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 调用记录表';

-- 渠道 Key 池：同一渠道可配多把 Key 轮换。authorization 方式仍由 provider_credential 描述，
-- 这里只放"钥匙"。建议填 key_id，否则 (tenant_id, provider) 下 key_id 为 NULL 的行不受唯一键约束。
CREATE TABLE IF NOT EXISTS provider_credential_key (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    tenant_id  VARCHAR(64)  NOT NULL COMMENT '租户标识，* 表示平台级',
    provider   VARCHAR(64)  NOT NULL COMMENT 'provider 标识',
    key_id     VARCHAR(64)  NULL COMMENT 'Key 标识，用于日志与熔断追踪',
    api_key    VARCHAR(1024) NOT NULL COMMENT '上游 API Key，enc:v1: 前缀表示 AES-GCM 加密',
    weight     INT          NOT NULL DEFAULT 1 COMMENT '权重',
    enabled    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_provider_credential_key (tenant_id, provider, key_id),
    KEY idx_provider_credential_key_provider (provider)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '渠道 Key 池表';

-- 通道组：一组互为备份的 provider + 组内负载均衡策略（PRIORITY/ROUND_ROBIN/RANDOM/WEIGHTED/DYNAMIC）。
CREATE TABLE IF NOT EXISTS provider_group (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    group_name  VARCHAR(64)  NOT NULL COMMENT '组名',
    strategy    VARCHAR(32)  NOT NULL DEFAULT 'PRIORITY' COMMENT '负载均衡策略',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    description VARCHAR(255) NULL COMMENT '说明',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_provider_group_name (group_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '通道组表';

-- 组内成员：model 可选，跨厂商的组里同一逻辑模型在各 provider 上的名字不同。
CREATE TABLE IF NOT EXISTS provider_group_member (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    group_name VARCHAR(64) NOT NULL COMMENT '组名',
    provider   VARCHAR(64) NOT NULL COMMENT 'provider 标识',
    model      VARCHAR(128) NULL COMMENT '该 provider 上的真实模型名，留空沿用路由解析结果',
    weight     INT         NOT NULL DEFAULT 1 COMMENT '权重',
    priority   INT         NOT NULL DEFAULT 1 COMMENT '优先级，数字越大越优先',
    enabled    TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_provider_group_member (group_name, provider),
    KEY idx_provider_group_member_group_name (group_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '通道组成员表';

-- 模型绑定：一个模型只归属一个组，唯一键保证。
CREATE TABLE IF NOT EXISTS provider_group_binding (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    model      VARCHAR(128) NOT NULL COMMENT '模型标识',
    group_name VARCHAR(64)  NOT NULL COMMENT '所属组名',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_provider_group_binding_model (model)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '模型通道组绑定表';

-- 自动同步来的模型单价：与 ai_model_price（人工覆盖）分层，人工值优先。
CREATE TABLE IF NOT EXISTS ai_model_price_sync (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    model         VARCHAR(128) NOT NULL COMMENT '模型标识',
    input_per_1k  DOUBLE       NOT NULL DEFAULT 0 COMMENT '每 1k 输入 token 单价（USD）',
    output_per_1k DOUBLE       NOT NULL DEFAULT 0 COMMENT '每 1k 输出 token 单价（USD）',
    source        VARCHAR(64)  NOT NULL COMMENT '数据来源',
    synced_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '同步时间',
    UNIQUE KEY uk_ai_model_price_sync (model, source)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '模型单价自动同步表';

-- 从渠道发现的可用模型。
CREATE TABLE IF NOT EXISTS provider_model (
    id        BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    provider  VARCHAR(64)  NOT NULL COMMENT 'provider 标识',
    model     VARCHAR(128) NOT NULL COMMENT '模型标识',
    synced_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '同步时间',
    UNIQUE KEY uk_provider_model (provider, model),
    KEY idx_provider_model_provider (provider)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '渠道可用模型表';

-- 初始化演示数据，对齐当前 application.yml 中 demo-tenant / demo-app / demo-key 语义。
INSERT INTO tenant (tenant_id, tenant_name, tenant_status, description)
VALUES ('demo-tenant', 'Demo Tenant', 'ACTIVE', '默认演示租户')
ON DUPLICATE KEY UPDATE tenant_name = VALUES(tenant_name), tenant_status = VALUES(tenant_status), description = VALUES(description);

INSERT INTO tenant_app (tenant_id, app_id, app_name, app_status, description)
VALUES ('demo-tenant', 'demo-app', 'Demo App', 'ACTIVE', '默认演示应用')
ON DUPLICATE KEY UPDATE app_name = VALUES(app_name), app_status = VALUES(app_status), description = VALUES(description);

INSERT INTO tenant_api_key (tenant_id, app_id, key_id, api_key, key_name, enabled, expires_at)
VALUES ('demo-tenant', 'demo-app', 'demo-key', 'demo-platform-key', 'Demo Platform Key', 1, NULL)
ON DUPLICATE KEY UPDATE api_key = VALUES(api_key), key_name = VALUES(key_name), enabled = VALUES(enabled), expires_at = VALUES(expires_at);

-- 平台级上游凭证占位：真实 Key 请用环境变量注入或走管理接口写入，
-- 这里保留可运行的占位值，避免本地 mock 链路因缺凭证直接 502。
INSERT INTO provider_credential (tenant_id, provider, api_key, auth_header, auth_scheme, extra_headers, enabled)
VALUES
    ('*', 'openai', 'sk-local-dev-placeholder', 'Authorization', 'Bearer', NULL, 1),
    ('*', 'claude', '', 'x-api-key', '', '{"anthropic-version":"2023-06-01"}', 1)
ON DUPLICATE KEY UPDATE auth_header = VALUES(auth_header), auth_scheme = VALUES(auth_scheme), extra_headers = VALUES(extra_headers), enabled = VALUES(enabled);

INSERT INTO tenant_model_policy (tenant_id, enabled, default_model_alias, default_model)
VALUES ('demo-tenant', 1, 'default', 'gpt-4o-mini-compatible')
ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), default_model_alias = VALUES(default_model_alias), default_model = VALUES(default_model);

INSERT INTO tenant_model_policy_allowed_model (tenant_id, allowed_model)
VALUES
    ('demo-tenant', 'gpt-4o-mini-compatible'),
    ('demo-tenant', 'gpt-4o-mini')
ON DUPLICATE KEY UPDATE allowed_model = VALUES(allowed_model);

INSERT INTO tenant_model_mapping (tenant_id, request_model, provider_model)
VALUES ('demo-tenant', 'default', 'gpt-4o-mini-compatible')
ON DUPLICATE KEY UPDATE provider_model = VALUES(provider_model);

INSERT INTO tenant_quota_policy (tenant_id, enabled, token_quota_per_minute, token_quota_per_day, token_quota_per_month)
VALUES ('demo-tenant', 1, 50000, 500000, 5000000)
ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), token_quota_per_minute = VALUES(token_quota_per_minute), token_quota_per_day = VALUES(token_quota_per_day), token_quota_per_month = VALUES(token_quota_per_month);

INSERT INTO ai_model_price (model, input_per_1k, output_per_1k, currency, enabled)
VALUES
    ('gpt-4o-mini', 0.150000, 0.600000, 'USD', 1),
    ('gpt-4o-mini-compatible', 0.150000, 0.600000, 'USD', 1)
ON DUPLICATE KEY UPDATE input_per_1k = VALUES(input_per_1k), output_per_1k = VALUES(output_per_1k), currency = VALUES(currency), enabled = VALUES(enabled);

-- 通道组种子：openai 为主、claude 兜底；gpt-4o-mini 绑定到该组。
-- 与 application.yml 的 routing.provider-groups / model-groups 同构，DB 有数据时以 DB 为准。
INSERT INTO provider_group (group_name, strategy, enabled, description)
VALUES ('default', 'PRIORITY', 1, '默认通道组：openai 优先，claude 降级')
ON DUPLICATE KEY UPDATE strategy = VALUES(strategy), enabled = VALUES(enabled), description = VALUES(description);

INSERT INTO provider_group_member (group_name, provider, model, weight, priority, enabled)
VALUES
    ('default', 'openai', NULL, 3, 2, 1),
    ('default', 'claude', 'claude-3-5-sonnet-latest', 1, 1, 1)
ON DUPLICATE KEY UPDATE model = VALUES(model), weight = VALUES(weight), priority = VALUES(priority), enabled = VALUES(enabled);

INSERT INTO provider_group_binding (model, group_name)
VALUES ('gpt-4o-mini', 'default')
ON DUPLICATE KEY UPDATE group_name = VALUES(group_name);

-- 运行时配置中心：一域一行，DB 为真源；Redis 只存版本号用于跨实例通知。
-- 六个域：routing / rateLimit / cache / safety / plugin / security。
-- 注意 config_json 里刻意不含密钥类字段（jwt_secret / users / provider_credentials），
-- 那些只从 yml 与环境变量来，落库等于把口令写进业务表。
-- 用 TEXT 而非 JSON：r2dbc-mysql 对 JSON 列的返回类型在不同版本上会落到 byte[]，TEXT 稳定映射 String。
CREATE TABLE IF NOT EXISTS runtime_config (
    domain      VARCHAR(64) NOT NULL COMMENT '配置域：routing/rateLimit/cache/safety/plugin/security',
    config_json TEXT        NOT NULL COMMENT '该域「运行时可变更键」的 JSON 快照（不含密钥类字段）',
    version     BIGINT      NOT NULL DEFAULT 1 COMMENT '版本令牌，每次写入更新；轮询据此判定是否重载',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (domain)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '网关运行时配置中心';

-- 渠道健康状态：主动探测的真源，也是"这个渠道为什么不见了"的唯一解释来源。
-- 粒度选 provider 而不是 (provider, model)：路由过滤（RouteCandidates / eligibleMembers）本来就是 provider 级，
-- 模型级禁用没有消费方（模型清单真源是 provider_model）。
-- manual_disabled 与 status 必须分开：前者是运维的手动禁用，探测不得覆盖它；
-- 后者是探测的自动结论，冷却到期后会被下一次探测改回 UP。
CREATE TABLE IF NOT EXISTS provider_health (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    provider              VARCHAR(64)  NOT NULL COMMENT '渠道标识',
    status                VARCHAR(16)  NOT NULL DEFAULT 'UP' COMMENT '探测结论：UP / DOWN',
    consecutive_failures  INT          NOT NULL DEFAULT 0 COMMENT '连续失败次数，成功即清零',
    consecutive_successes INT          NOT NULL DEFAULT 0 COMMENT '连续成功次数，失败即清零',
    manual_disabled       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '控制台手动禁用；探测绝不覆盖',
    disabled_until        DATETIME     NULL COMMENT 'DOWN 的冷却到期时间，到期后才重新探测',
    last_error            VARCHAR(512) NULL COMMENT '最近一次失败原因（分类 + HTTP 状态）',
    last_checked_at       DATETIME     NULL COMMENT '最近一次探测时间',
    last_success_at       DATETIME     NULL COMMENT '最近一次成功时间',
    last_failure_at       DATETIME     NULL COMMENT '最近一次失败时间',
    latency_millis        BIGINT       NULL COMMENT '最近一次探测耗时毫秒',
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_health_provider (provider)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '渠道健康状态（主动探测真源）';

SET FOREIGN_KEY_CHECKS = 1;
