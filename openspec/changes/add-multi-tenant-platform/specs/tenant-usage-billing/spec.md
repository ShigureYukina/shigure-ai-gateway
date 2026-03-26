## ADDED Requirements

### Requirement: Tenant usage recording
The system SHALL record per-request usage data for authenticated tenant traffic, including tenant identity, application identity, key identity, provider, model, status, and token usage when available.

#### Scenario: Successful request records usage
- **WHEN** an authenticated tenant request completes successfully
- **THEN** the system records a usage entry containing the tenant, app, key, provider, model, latency, status, and token usage details

#### Scenario: Failed request still records request outcome
- **WHEN** an authenticated tenant request fails before or after upstream invocation
- **THEN** the system records the tenant, app, key, provider or attempted route, latency, and failure status for observability and billing analysis

### Requirement: Cost estimation from model pricing
The system SHALL estimate request cost using configured model pricing and recorded token usage.

#### Scenario: Cost is estimated when pricing and usage exist
- **WHEN** a request has token usage data and pricing is configured for the resolved model
- **THEN** the system computes and records an estimated request cost

#### Scenario: Missing pricing does not block request recording
- **WHEN** token usage exists but model pricing is not configured
- **THEN** the system records usage without estimated cost and does not fail the request lifecycle
