## ADDED Requirements

### Requirement: Tenant model whitelist enforcement
The system SHALL enforce a tenant-scoped model whitelist before provider routing and SHALL reject requests for models that are not allowed for the authenticated tenant.

#### Scenario: Allowed model passes policy validation
- **WHEN** an authenticated tenant requests a model that is present in its whitelist
- **THEN** the system allows the request to continue to provider routing

#### Scenario: Disallowed model is rejected before routing
- **WHEN** an authenticated tenant requests a model that is not present in its whitelist
- **THEN** the system rejects the request with a client authorization error before selecting a provider or reserving quota

### Requirement: Tenant default model policy
The system SHALL support a tenant-specific default model policy that can be applied as a tenant-level model mapping while preserving the existing public API contract that requires the request model field.

#### Scenario: Tenant default model mapping is applied
- **WHEN** tenant policy defines a default allowed model mapping for the incoming request model
- **THEN** the system uses the mapped tenant default model for downstream routing and governance

#### Scenario: Default model must also be allowed
- **WHEN** tenant policy defines a default model that is not allowed by the tenant whitelist
- **THEN** the system treats the policy as invalid and rejects the request rather than routing with that model
