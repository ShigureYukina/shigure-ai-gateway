# AGENTS.md

## Purpose
This file is the operating guide for agentic coding assistants working in this repository.
It captures how to build, test, and modify code safely in this Java/Spring WebFlux project.

## Project Snapshot
- Stack: Java 17, Spring Boot 3.3.2, Spring Cloud Gateway, WebFlux, Redis.
- Build tool: Maven (`pom.xml` at repo root).
- Main app: `src/main/java/com/nageoffer/shortlink/aigateway/AiGatewayApplication.java`.
- Tests: JUnit 5 + Reactor Test under `src/test/java`.
- Packaging: Spring Boot Maven Plugin (`repackage` goal).

---

## Build / Lint / Test Commands

### Prerequisites
- JDK 17 available on PATH.
- Maven available on PATH (`mvn`) — no Maven wrapper (`mvnw`) exists.

### Core Commands
Run from repository root: `E:\ai-gateway`

```bash
# Clean build artifacts
mvn clean

# Compile main + test code
mvn test-compile

# Run all tests
mvn test

# Full verification lifecycle (includes tests)
mvn verify

# Build executable jar (skipping tests when needed)
mvn clean package -DskipTests

# Run application locally
mvn spring-boot:run
```

### Run a Single Test (Important)
```bash
# Single test class
mvn -Dtest=ProviderRoutingServiceTest test

# Single test method
mvn -Dtest=ProviderRoutingServiceTest#shouldResolveAliasProviderAndModel test

# Multiple specific classes
mvn -Dtest=ProviderRoutingServiceTest,SseStreamE2ETest test
```

### Suggested Fast Loops
```bash
# Validate one changed test quickly
mvn -Dtest=ClassNameTest test

# Validate compile only after refactor
mvn -DskipTests compile

# Validate full project before handoff
mvn clean verify
```

### Lint / Static Analysis Status
- No explicit Checkstyle/PMD/SpotBugs/Formatter config detected.
- No `.editorconfig` detected.
- No separate lint command currently exists.
- Use IDE inspections + `mvn test` / `mvn verify` as baseline quality gate.

---

## Repository Rules Discovery

### Cursor Rules
- `.cursor/rules/`: not found.
- `.cursorrules`: not found.

### Copilot Rules
- `.github/copilot-instructions.md`: not found.

### Implication for Agents
- Use this file as the primary repository-level agent guidance.
- Do not assume hidden editor rules exist.

---

## Code Style Guidelines

## 1) Language and Framework Conventions
- Use Java 17 features conservatively; prefer readability over novelty.
- Follow Spring Boot idioms for bean wiring and configuration.
- For reactive flows, keep pipelines linear and stage responsibilities clearly.

## 2) Formatting and Structure
- Use 4-space indentation, no tabs.
- Keep methods focused; split long methods by responsibility boundaries.
- Prefer early returns/guards for invalid conditions.
- Keep class members grouped: dependencies, public APIs, private helpers.

## 3) Imports
- Avoid wildcard imports (`*`).
- Keep imports organized and minimal; remove unused imports.
- Use explicit JDK and framework imports for clarity.

## 4) Naming
- Packages: lowercase, hierarchical by domain (`controller`, `service`, `governance`, etc.).
- Classes: PascalCase, descriptive nouns (`AiGatewayService`, `ProviderRoutingService`).
- Methods: camelCase, verb-driven (`chatCompletion`, `resolveRequestId`).
- Test classes: `*Test` suffix.
- DTOs: keep `ReqDTO` / `RespDTO` suffix convention consistent with existing code.

## 5) Types and Data Modeling
- Prefer concrete, meaningful types over raw `Object`.
- Use DTOs for transport boundaries; avoid leaking upstream-specific payload shapes.
- Use `@Valid` + bean validation annotations for request/config input constraints.
- Use `Duration` for time-related config values.

## 6) Lombok Usage
- Existing code uses Lombok (`@Data`, `@RequiredArgsConstructor`).
- Continue existing Lombok patterns for consistency unless refactor requires explicit methods.
- Do not mix styles arbitrarily inside the same module.

## 7) Error Handling
- Use domain exceptions:
  - `AiGatewayClientException` for client/input/business violations.
  - `AiGatewayUpstreamException` for upstream/provider failures.
- Map exceptions through centralized handler (`AiGatewayExceptionHandler`).
- Return stable error payload shape (`AiGatewayErrorRespDTO`).
- Do not swallow errors in reactive chains; propagate with context.

## 8) Reactive Programming (WebFlux / Reactor)
- Avoid blocking calls in reactive paths.
- Keep side effects explicit via `doOn...` operators.
- Keep fallback/retry logic deterministic and bounded.
- Prefer small helper methods for complex `Mono`/`Flux` branches.
- Ensure timeout/retry policy derives from configuration where available.

## 9) Configuration and Properties
- Centralize app settings in `AiGatewayProperties`.
- Add defaults thoughtfully; document non-obvious values.
- Keep `application.yml` keys aligned with `@ConfigurationProperties` schema.
- For new config sections, include validation where safety-critical.

## 10) Security and Secrets
- Never hardcode real secrets, tokens, or credentials.
- Treat current sample credentials in `application.yml` as local/dev placeholders only.
- Any security-related change should preserve least privilege and explicit role checks.

## 11) Testing Expectations
- Add/adjust tests with behavioral changes.
- For reactive logic, prefer `StepVerifier`.
- Cover success path + failure path + boundary conditions.
- For routing/governance changes, test provider/model resolution and fallback behavior.
- Run at least targeted tests locally before concluding work.

## 12) API and Compatibility
- This project exposes OpenAI-compatible endpoints.
- Preserve request/response compatibility unless explicitly asked to change contracts.
- If contract change is required, update DTOs, handler logic, and tests together.

---

## Recommended Agent Workflow for This Repo
1. Inspect impacted module(s) and nearby tests.
2. Implement minimal coherent change.
3. Run targeted single-test command first.
4. Run broader test scope if change touches shared services.
5. Run `mvn clean verify` before final handoff when feasible.

## Quick Pre-Handoff Checklist
- Code compiles.
- Changed behavior covered by tests.
- No wildcard/unused imports.
- Error handling uses project exception model.
- Config changes mirrored in `AiGatewayProperties` and `application.yml`.
- No secrets introduced.
