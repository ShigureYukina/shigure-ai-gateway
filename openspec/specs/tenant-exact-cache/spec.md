# Tenant Exact Cache Specification

## Purpose
提供按租户隔离的精确缓存能力，确保一个租户的缓存响应不会被另一个租户命中，并记录命中、未命中与写入事件以支撑节省收益分析。

## Requirements

### Requirement: Tenant-isolated exact cache
The system SHALL isolate exact cache entries by tenant identity so that cached responses from one tenant cannot be returned to another tenant.

#### Scenario: Same tenant can hit exact cache
- **WHEN** the same tenant repeats an equivalent non-streaming request with the same effective model and cacheable parameters
- **THEN** the system returns the cached response instead of calling an upstream provider

#### Scenario: Different tenant cannot reuse cached response
- **WHEN** a different tenant sends a request equivalent to a request cached by another tenant
- **THEN** the system treats the request as a cache miss and does not return the other tenant's cached response

### Requirement: Exact cache scope and accounting
The system SHALL apply exact caching only to cacheable non-streaming responses and SHALL record cache hit, miss, and write outcomes for tenant traffic.

#### Scenario: Streaming request bypasses exact cache storage
- **WHEN** an authenticated tenant sends a streaming chat completion request
- **THEN** the system does not store the response as an exact cache entry

#### Scenario: Cache events are tracked per tenant request
- **WHEN** a cacheable request results in a hit, miss, or write
- **THEN** the system records the cache event for tenant-scoped observability and savings analysis
