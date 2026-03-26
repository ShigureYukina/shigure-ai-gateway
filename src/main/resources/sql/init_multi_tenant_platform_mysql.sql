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
    api_key             VARCHAR(255) NOT NULL COMMENT '平台 API Key，当前实现兼容明文读取',
    key_name            VARCHAR(128) NULL COMMENT '凭证名称',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    expires_at          DATETIME     NULL COMMENT '过期时间',
    last_used_at        DATETIME     NULL COMMENT '最近使用时间',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_tenant_api_key_value (api_key),
    UNIQUE KEY uk_tenant_api_key_key_id (tenant_id, app_id, key_id),
    KEY idx_tenant_api_key_lookup (tenant_id, app_id, enabled, expires_at),
    CONSTRAINT fk_tenant_api_key_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
    CONSTRAINT fk_tenant_api_key_app FOREIGN KEY (tenant_id, app_id) REFERENCES tenant_app (tenant_id, app_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '租户 API Key 凭证表';

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

SET FOREIGN_KEY_CHECKS = 1;
