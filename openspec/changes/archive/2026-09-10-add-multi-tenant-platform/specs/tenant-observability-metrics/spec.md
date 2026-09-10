## ADDED Requirements

### Requirement: Tenant-aware Prometheus metrics
The system SHALL expose Prometheus-compatible metrics for authenticated tenant traffic covering request count, latency, status outcomes, cache activity, quota activity, and estimated cost.

#### Scenario: Successful request contributes to metrics
- **WHEN** an authenticated tenant request completes successfully
- **THEN** the system updates Prometheus metrics for tenant request count, latency, status, and any available token or cost data

#### Scenario: Failed request contributes to error metrics
- **WHEN** an authenticated tenant request fails
- **THEN** the system updates Prometheus metrics to reflect the failed outcome and observed latency

### Requirement: Metric labels and aggregation safety
The system SHALL expose tenant-aware metrics using bounded-cardinality labels and MUST NOT use request-unique identifiers as Prometheus labels.

#### Scenario: Request-specific identifiers are excluded from labels
- **WHEN** the system exports tenant-related Prometheus metrics
- **THEN** it includes stable aggregation labels such as tenant, application, provider, model, or result class and excludes request-unique identifiers such as request ID

#### Scenario: Cache and quota metrics remain aggregatable
- **WHEN** the system exports cache hit or quota consumption metrics for tenant traffic
- **THEN** those metrics are emitted in a form that supports aggregation by stable tenant-related dimensions
