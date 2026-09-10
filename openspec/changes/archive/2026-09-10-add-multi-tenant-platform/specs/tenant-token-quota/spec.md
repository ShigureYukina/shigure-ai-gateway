## ADDED Requirements

### Requirement: Tenant-aware token quota pre-check
The system SHALL perform token quota pre-checks using tenant-scoped identity and SHALL evaluate quota before calling any upstream provider.

#### Scenario: Request within tenant quota is allowed
- **WHEN** an authenticated tenant sends a request whose estimated token usage is within configured tenant limits
- **THEN** the system reserves quota for that tenant and continues processing

#### Scenario: Request exceeding tenant quota is rejected
- **WHEN** an authenticated tenant sends a request whose estimated token usage would exceed configured tenant limits
- **THEN** the system rejects the request with a quota error and does not call any upstream provider

### Requirement: Tenant quota dimensions
The system SHALL support tenant-scoped token quotas at minimum for per-minute, per-day, and per-month periods.

#### Scenario: Per-minute quota is enforced independently
- **WHEN** a tenant exhausts its per-minute token quota but still has remaining daily or monthly quota
- **THEN** the system rejects additional requests until the per-minute window resets

#### Scenario: Monthly quota is enforced independently
- **WHEN** a tenant exhausts its per-month token quota
- **THEN** the system rejects additional requests for that tenant until the monthly window resets or policy is updated

### Requirement: Actual usage reconciliation
The system SHALL reconcile reserved quota with actual token usage after a successful upstream response when actual usage data is available.

#### Scenario: Actual usage adjusts reserved quota
- **WHEN** the upstream response includes actual token usage information
- **THEN** the system adjusts the tenant quota ledger to reflect actual token consumption rather than only the pre-check estimate

#### Scenario: Missing usage data preserves reserve result
- **WHEN** the upstream response does not include actual token usage information
- **THEN** the system keeps the original reserved quota outcome for that request
