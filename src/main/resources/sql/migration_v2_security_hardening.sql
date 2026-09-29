-- =====================================================================================
-- 安全加固迁移 v2：密钥列扩容 + 平台 API Key 查找索引
-- 项目：ai-gateway
-- 适用：已经执行过 init_multi_tenant_platform_mysql.sql 的环境
-- 说明：逐条执行，不要整体粘贴；第 3 步的体检必须先跑完再决定是否继续。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. 上游凭证列扩容
--    GCM 密文长度 ≈ 7(前缀) + 4*ceil((L+28)/3)。provider_credential 按明文 350 字节算 ≈ 511，
--    原来的 512 卡死在边界上；扩到 1024 留出余量。
--    唯一键不含 api_key（分别是 (tenant_id,provider) 与 (tenant_id,provider,key_id)），
--    所以随机 IV 导致的密文不固定不会破坏约束。
-- -------------------------------------------------------------------------------------
ALTER TABLE provider_credential
    MODIFY api_key VARCHAR(1024) NOT NULL COMMENT '上游 API Key，enc:v1: 前缀表示 AES-GCM 加密';

ALTER TABLE provider_credential_key
    MODIFY api_key VARCHAR(1024) NOT NULL COMMENT '上游 API Key，enc:v1: 前缀表示 AES-GCM 加密';

-- -------------------------------------------------------------------------------------
-- 2. 平台 API Key 列扩容 + 新增确定性查找索引
--    tenant_api_key 原来 255 字节，密文在明文 160 字节时就会溢出，扩到 512。
--    api_key_hash = HMAC-SHA256(主密钥, 明文 Key) 的 64 位十六进制。
--    为什么要它：加密用随机 IV，同一个 Key 两次入库得到不同密文，
--    于是 api_key 上的唯一约束与"按 Key 查行"同时失效，必须换一个确定性的查找键。
-- -------------------------------------------------------------------------------------
ALTER TABLE tenant_api_key
    MODIFY api_key VARCHAR(512) NOT NULL COMMENT '平台 API Key，enc:v1: 前缀表示 AES-GCM 加密，兼容明文';

ALTER TABLE tenant_api_key
    ADD COLUMN api_key_hash CHAR(64) NULL COMMENT 'HMAC-SHA256(主密钥, 明文 Key)，用于确定性查找' AFTER api_key;

-- -------------------------------------------------------------------------------------
-- 3. ⚠️ 执行前体检（必须先跑，结果必须为空）
--    旧表上 api_key 是唯一键，理论上不会有重复；但如果历史上手工插过数据、
--    或曾经把某行的 api_key 改成了空串/相同值，换唯一键时 ADD UNIQUE KEY 会失败。
--    有结果就先处理那一行，再往下执行。
-- -------------------------------------------------------------------------------------
-- SELECT api_key, COUNT(*) AS dup FROM tenant_api_key GROUP BY api_key HAVING COUNT(*) > 1;
--
-- 顺带确认一下待回填的行数（这些行的 api_key_hash 为 NULL，会被 SecretMigrationRunner 处理；
-- 如果 SecretMigrationRunner 未开启，务必手工确认服务端已配主密钥）：
-- SELECT COUNT(*) AS pending FROM tenant_api_key WHERE api_key_hash IS NULL;

-- -------------------------------------------------------------------------------------
-- 4. 换唯一键：从"密文唯一"改成"哈希唯一"
--    顺序不能反（必须先 ADD 新键再 DROP 旧键的话，中间会有一段没有任何唯一约束的窗口；
--    反过来则会在同一列上短暂并存两个唯一键，后者会因密文不固定而挡不住重复 —— 两种都不可接受）。
--    这里的做法是：先建 hash 唯一键（此时 hash 允许 NULL，MySQL 唯一键对多个 NULL 不判重），
--    再由 SecretMigrationRunner 回填 hash，最后删掉旧的 api_key 唯一键。
--    因此 DROP 旧键放在迁移器跑完之后手工执行更稳；若确认存量数据已全部回填，可直接执行。
-- -------------------------------------------------------------------------------------
ALTER TABLE tenant_api_key
    ADD UNIQUE KEY uk_tenant_api_key_hash (api_key_hash);

-- ⚠️ 下面这条请在 SecretMigrationRunner 完成回填、且确认所有行 api_key_hash 非空之后再执行。
--    提前执行会让"未回填的行"失去原有的价值唯一约束。
-- ALTER TABLE tenant_api_key DROP INDEX uk_tenant_api_key_value;
