-- =============================================================================
-- 迁移 v3：渠道健康状态表（主动探测）
--
-- 适用：已经跑过 init_multi_tenant_platform_mysql.sql 的环境。
-- 新装环境不需要执行本文件 —— 基线脚本里已经包含 provider_health。
--
-- 本文件只有一条 CREATE TABLE IF NOT EXISTS，纯新增、不动既有列，不存在数据风险。
-- =============================================================================

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

-- 说明：不需要为已存在的渠道插初始行。
-- ChannelHealthRegistry / ChannelProbeService 对"没有行的渠道"按 UP + 未被禁用处理（fail-open），
-- 第一次探测后会自然 upsert 出该行。这样既避免迁移脚本依赖当前 yml 的渠道清单，
-- 也避免"漏插一行 = 渠道被静默禁用"这类事故。
