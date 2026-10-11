# 竞品分析 · AI 网关（vs New API / Octopus / LiteLLM）

> 版本 v1.0 ｜ 维护：ShigureYukina
> 本文档对应 README Roadmap 挂账项「竞品对比文档（vs New API / Octopus / LiteLLM）」。
> 说明：Roadmap 中的 "Octopus" 经检索核实指向 **bestruirui/octopus**（个人向 LLM API 聚合网关），
> 下文按此分析。竞品信息以 2026-10 时点公开资料为准。

---

## 一、结论速览

1. **四者定位不同，不构成正面替代关系**：New API 是"模型 API 分发与资产管理"（偏商用转售），
   LiteLLM 是"通用 LLM 代理 + 企业版"（适配广度优先），Octopus 是"个人自托管聚合网关"（轻量优先），
   本网关定位是"**企业内部多团队共享的 AI 接入平台**"（治理深度优先，见《PRD-AI统一网关》）。
2. **广度上全面落后于 LiteLLM**（100+ Provider 适配 vs 本网关 2 内置 + 任意 OpenAI 兼容/自建上游），
   这是生态积累差距，不作为追赶目标。
3. **治理细节上本网关有四项真实差异化**：Key 池粒度的故障隔离、配额"预扣-结算-退还"+服务端身份键、
   流式 first-token 硬边界、原生协议（Anthropic Messages / OpenAI Responses）的完整事件流与错误契约适配。
4. **管理面成熟度存在可见差距**：竞品的运营后台/管理 UI 更完整；本网关控制台尚有四组"只有 API 没有页面"的缺口（已列入 PRD backlog）。

## 二、竞品概览

| 竞品 | 形态与技术栈 | 定位 | 代表能力 |
| --- | --- | --- | --- |
| **New API**（Calcium-Ion/new-api） | Go；One API 二次开发 | 大模型网关与 AI 资产管理，统一接入 30~40+ 服务商 | 渠道管理（智能路由/格式转换/负载均衡/故障转移）、令牌管理（额度上限/过期/模型限制/标签）、额度计费与用量报表、按部门/项目分发令牌分账 |
| **Octopus**（bestruirui/octopus） | TypeScript；自托管 | "为个人打造的 LLM API 聚合网关"（约 2.7k star） | 多 Provider 统一单端点、负载均衡、OpenAI 与 Anthropic 协议支持；另有 Flutter 管理客户端（Octopus-Manage）提供实时用量面板 |
| **LiteLLM**（BerriAI/litellm） | Python；代理服务 | 100+ LLM Provider 的统一 OpenAI 格式代理 | Router（负载均衡/fallback/重试/超时）、Virtual Keys（权限/模型限制/支出追踪）、预算体系（个人/团队/成员三层）、TPM/RPM 限速、缓存、Admin UI、企业版 SSO 与审计 |

选取理由：三者分别代表"商用分发方向"（New API）、"个人聚合方向"（Octopus）、"通用代理方向"（LiteLLM）
的主流实现，恰好覆盖 AI 网关赛道的三个生态位；对比它们可以准确定位本网关的生态位与差异化主张。

## 三、定位对比

| 维度 | 本网关 | New API | Octopus | LiteLLM |
| --- | --- | --- | --- | --- |
| **目标场景** | 企业内部多团队/应用共享接入 | API 分发、令牌分账、资产管理 | 个人多渠道聚合自用 | 团队/应用的统一代理层 |
| **多租户模型** | 租户/应用双层：API Key 鉴权、模型白名单、租户配额、BYOK、隔离缓存、租户指标 | 用户分组 + 令牌维度分账 | 无（单用户视角） | Team / Virtual Key，个人/团队/成员三层预算 |
| **商用化能力** | 明确不做（Non-goal） | 充值/兑换/令牌分发（核心场景） | 不涉及 | 企业版商业化 |
| **技术栈取向** | Java 17 / Spring Boot 3 + WebFlux（自研转发层） | Go | TypeScript | Python |

## 四、功能矩阵

| 能力 | 本网关 | New API | Octopus | LiteLLM |
| --- | --- | --- | --- | --- |
| OpenAI 兼容入口（流式/非流式） | ✅ | ✅ | ✅ | ✅ |
| Anthropic Messages 原生协议 | ✅ 原生事件流 + `count_tokens`，错误体按协议返回 | 转换为 OpenAI 形状 | ✅ 协议支持 | ✅ 转换为统一形状 |
| OpenAI Responses 原生协议 | ✅ | 部分 | — | ✅ |
| 路由与容灾 | 静态/别名/优先级链 + **通道组五策略**（优先级/轮询/随机/加权/动态健康分 40-30-30）+ 主动探测自动禁用/恢复 | 渠道权重/智能路由/故障转移 | 负载均衡 | Router：LB/fallback/重试/超时 |
| **Key 池** | ✅ 同渠道多 Key 加权轮换；单 Key 连败冷却 60s、流量切次优——**故障隔离到"一把钥匙"** | 渠道维度管理 | — | 多部署维度 |
| 配额/预算 | **分钟/日/月三档**；Lua 原子预检防超发；预扣-结算-退还；超额 429 + Retry-After + 限流响应头 | 令牌额度上限/有效期 | — | 预算三层 + TPM/RPM + 告警 |
| 配额键防伪造 | ✅ 只认服务端身份（keyId/appId/远端地址），客户端头不参与 | — | — | Virtual Key 体系天然服务端 |
| 缓存 | 精确 Hash + Trigram Jaccard 相似度（阈值可配），**租户隔离** | — | — | ✅ caching |
| 安全治理 | 输入输出策略、Prompt Injection 检测、PII 脱敏、SSRF 出站校验（残余风险诚实披露）、主密钥加密存储 | 基础 | — | 企业版 SSO/审计 |
| 流式边界 | ✅ first-token 硬边界：吐过事件后禁止跨供应商重试（杜绝两段拼接 SSE） | — | — | — |
| 可观测 | Prometheus、OTel Tracing、模型级成功率/P95/成本、**实时链路 SSE（每跳可视化）** | 用量统计/报表 | 用量面板（Flutter 客户端） | usage/cost 日志 + Admin UI |
| 元数据同步 | models.dev 价格 + 渠道模型发现，人工覆盖 > yml > 自动 | 静态配置 | — | — |
| Provider 适配广度 | 2 内置（OpenAI/Anthropic）+ 任意 OpenAI 兼容与自建 Ollama/vLLM | 30~40+ | 多渠道 | **100+** |
| 管理 UI | 控制台 11 页（租户/BYOK/通道组/渠道健康仅 API） | 完整运营后台 | Flutter 管理端 | Admin UI（企业版更全） |

> "—" 表示公开资料中未见对应能力，或非该产品关注点；以各产品官方文档为准。

## 五、差异化分析

### 5.1 本网关的真实差异化（可防守）

1. **故障隔离到 Key 粒度**：同类产品的容灾基本停在"渠道/部署"维度。上游限流通常只打击单把 Key，
   把整渠道判死是过度反应——本网关把"一把钥匙连败冷却 60s、流量切次优 Key"做成默认行为，
   只有整渠道故障才升级到通道组降级。
2. **配额的预扣-结算-退还闭环 + 服务端身份键**：预扣保证不超发，失败/取消自动退还保证不误伤；
   配额键只认服务端身份（认证 keyId / appId / 远端地址），客户端请求头可任意伪造、不参与配额——
   这在"按头分账"的多租户场景是正确性问题，不是优化项。
3. **流式 first-token 硬边界**：流式请求吐出首个事件后禁止跨供应商重试。否则客户端会收到
   "前半段 A 模型 + 后半段 B 模型"的拼接 SSE，且 usage 混算——这是流式网关特有的正确性边界。
4. **原生协议的完整适配**：Anthropic Messages 的事件流（message_start / content_block_delta / message_stop）
   与 `count_tokens`、OpenAI Responses 的事件流按各自协议原样返回，错误体也按各自协议——
   接入方拿官方 SDK 行为对齐，而不是"长得差不多的 OpenAI 形状"。内部复用同一条治理链路，不因协议分裂。

### 5.2 承认的差距

| 差距 | 现状 | 对策/态度 |
| --- | --- | --- |
| Provider 适配广度 | LiteLLM 的 100+ 适配是多年社区积累 | 不追赶数量；保住"OpenAI 兼容 + 自建上游"的覆盖面，广度靠协议层兼容自然增长 |
| 预算体系细度 | LiteLLM 有个人/团队/成员三层预算与告警 | 已有租户/应用双层；成员级预算列入平台化迭代评估 |
| 管理 UI 成熟度 | New API/LiteLLM 后台完整 | 控制台补四组缺口页面（PRD backlog M1） |
| 社区与生态 | 三者均为活跃开源项目，New API 背靠 One API 生态 | 本仓库定位内部平台与工程实践，不以社区规模为目标 |

### 5.3 诚实披露作为产品立场

本仓库对性能数字只引用本机受控环境口径（稳态 P50 37.7ms / P95 44.6ms / 503 RPS、网关开销 ≈1.7ms，
不代表生产容量），对 SSRF 校验的 DNS rebinding 残余风险做了明确披露与定位声明
（"把误配置与顺手可得的 SSRF 挡在写入口"，不是对抗能控制 DNS 的攻击者）。
竞品资料普遍不做此类声明；本仓库把它作为工程立场写进文档，同样的标准也应反过来审视竞品宣传数字。

## 六、结论

- **生态位**：企业内部多团队共享的 AI 接入平台——不做商用转售（New API 的主场），
  不做个人轻量聚合（Octopus 的主场），不在适配广度上追赶 LiteLLM；
  差异化押注在**治理正确性**（Key 粒度隔离、防伪造配额键、流式边界）与 **Java 企业生态的接入/二开成本**上。
- **行动项**：控制台平台化闭环（M1）> Grafana 面板（M2）> 真实上游 smoke test（M3）；
  竞品矩阵随三者迭代按季度复核。

## 七、参考资料

- New API：github.com/Calcium-Ion/new-api（One API 二次开发）
- Octopus：github.com/bestruirui/octopus；管理端 github.com/lingyuins/Octopus-Manage
- LiteLLM：docs.litellm.ai（Proxy / Routing / Budgets & Rate Limits）
- 本网关定位与能力：《PRD-AI统一网关.md》、README 项目亮点表、`docs/api-inventory.md`
