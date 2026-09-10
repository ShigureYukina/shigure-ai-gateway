## 1. Tenant identity and request context

- [x] 1.1 Add tenant domain models and request-scoped `TenantContext` for tenant, app, and key identity
- [x] 1.2 Implement platform API key authentication service for Bearer and compatible `x-api-key` request credentials
- [x] 1.3 Integrate tenant context resolution into the chat completion entry path before routing and governance execution
- [x] 1.4 Add unit tests for valid, missing, disabled, expired, and unmapped API key authentication paths

## 2. Tenant model access policy

- [x] 2.1 Add tenant model policy service to enforce tenant model whitelists before provider routing
- [x] 2.2 Add tenant default model mapping behavior for tenant-level model override while preserving the required request model contract
- [x] 2.3 Integrate model policy validation with the existing routing flow and return client errors for disallowed models
- [x] 2.4 Add tests for allowed model, disallowed model, and invalid default model scenarios

## 3. Tenant-aware token quota governance

- [x] 3.1 Extend quota policy models to support tenant-scoped per-minute, per-day, and per-month token limits
- [x] 3.2 Update `QuotaKeyGenerator` and Redis quota ledger keys to include tenant, app, and key dimensions
- [x] 3.3 Refactor `RedisTokenQuotaService` pre-check and reconciliation flows to consume `TenantContext`
- [x] 3.4 Add tests for tenant quota isolation, quota exceed rejection, and actual token usage reconciliation

## 4. Tenant usage recording and cost estimation

- [x] 4.1 Extend call record and usage models with tenant, app, key, cache, and estimated cost fields
- [x] 4.2 Update metrics recording flow to persist tenant-aware request outcomes for success and failure paths
- [x] 4.3 Reuse configured model pricing to estimate request cost when usage data is available
- [x] 4.4 Add tests for tenant usage recording, failure recording, and missing-pricing cost behavior

## 5. Tenant-isolated exact cache

- [x] 5.1 Update cache key generation to include tenant and app identity alongside model and cacheable request parameters
- [x] 5.2 Apply tenant-isolated exact cache behavior only to eligible non-streaming requests
- [x] 5.3 Record tenant-scoped cache hit, miss, and write outcomes for savings analysis
- [x] 5.4 Add tests for same-tenant cache hits, cross-tenant cache isolation, and streaming cache bypass

## 6. Prometheus observability metrics

- [x] 6.1 Add Prometheus metrics for tenant request count, latency, status, cache activity, quota activity, and estimated cost
- [x] 6.2 Ensure metric tags use bounded-cardinality dimensions and exclude request-unique identifiers
- [x] 6.3 Expose tenant-aware metrics through the existing observability pipeline without changing public chat APIs
- [x] 6.4 Add tests or verification steps for successful, failed, cache-related, and quota-related metric emission

## 7. Configuration, rollout, and verification

- [x] 7.1 Add configuration properties and sample configuration for tenant API keys, model policies, quota policies, and metric controls
- [x] 7.2 Preserve backward-compatible behavior so the gateway can fall back to current global behavior when tenant features are disabled
- [x] 7.3 Add integration tests covering end-to-end tenant authentication, model policy, quota, cache, usage, and metrics behavior
- [x] 7.4 Run targeted tests and a broader verification command set to confirm the multi-tenant change is stable
