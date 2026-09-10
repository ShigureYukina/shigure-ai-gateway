# Tenant API Key Authentication Specification

## Purpose
通过平台 API Key 识别调用方身份，解析出租户、应用与密钥三级标识，并为每次 OpenAI 兼容请求建立统一的租户上下文，供路由、配额、缓存与观测组件复用。

## Requirements

### Requirement: API Key request authentication
The system SHALL authenticate every OpenAI-compatible chat completion request with a platform API key and SHALL reject requests whose API key is missing, invalid, disabled, or expired.

#### Scenario: Valid API key authenticates request
- **WHEN** a client sends a chat completion request with a valid platform API key
- **THEN** the system authenticates the request and continues processing with an attached tenant context

#### Scenario: Missing API key is rejected
- **WHEN** a client sends a chat completion request without a platform API key
- **THEN** the system rejects the request with a client authentication error and does not call any upstream provider

#### Scenario: Disabled or expired API key is rejected
- **WHEN** a client sends a chat completion request with a disabled or expired API key
- **THEN** the system rejects the request with a client authentication error and does not reserve quota, query cache, or call any upstream provider

### Requirement: Tenant context resolution
The system SHALL resolve a tenant context from the authenticated API key and SHALL include at least tenant identity, application identity, and key identity for downstream governance.

#### Scenario: Tenant context is attached after authentication
- **WHEN** a request is authenticated successfully
- **THEN** the system attaches tenant, app, and key identity to the request context for routing, quota, cache, and observability components

#### Scenario: Unsupported credential cannot produce context
- **WHEN** a presented credential does not map to a known tenant or application
- **THEN** the system rejects the request as an authentication failure
