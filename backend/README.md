# Schoomano backend

A single Spring Boot 3.5 project (Java 21) using PostgreSQL 16. See the repository README for running the full stack.

```
pom.xml
src/main/java/com/schoomano/
  SchoomanoApplication.java
  domain/      business rules in plain Java: tenancy scopes, access policy, configuration (no Spring, no JDBC)
  platform/    tenant transactions, audit, errors, correlation ids
  security/    sign-in, request context
  api/         HTTP endpoints (/api/v1/...)
src/main/resources/
  application.yml
  db/migration/    Flyway migrations
src/test/java/     unit tests (incl. DomainPurityTest) and Testcontainers API tests (*IT)
db/seed/           synthetic, re-runnable development seed
db/tests/          SQL tests: RLS isolation and invariants; smoke test of every SQL statement the API uses
scripts/           verify-domain-without-maven.sh (fallback when Maven Central is unreachable)
Dockerfile         multi-stage build → non-root JRE image, health check on /actuator/health/readiness
```

## Build and test
```sh
mvn verify                          # unit tests + Testcontainers API tests (needs Docker)
mvn -DskipTests package             # → target/schoomano.jar
docker build -t schoomano-backend:local .
```

## Run on your machine
From the repository root, `scripts/start.sh --dev backend` (Windows: `scripts\start.cmd -Dev backend`) starts the database container and runs the jar locally. Or manually, with the variables from the root `.env` loaded:
```sh
java -jar target/schoomano.jar
```

## Configuration (environment)
| Variable | Purpose |
|---|---|
| `SCHOOMANO_DB_URL` | JDBC URL (default `jdbc:postgresql://localhost:5432/schoomano`) |
| `SCHOOMANO_DB_APP_USER` / `_PASSWORD` | Unprivileged runtime role; row-level security applies to it |
| `SCHOOMANO_DB_OWNER_USER` / `_PASSWORD` | Schema owner, used only by Flyway |
| `SCHOOMANO_AUTH_DEV_MODE` | `true` enables synthetic `Bearer dev:<subject>` tokens. Local only; refused with the `prod` profile |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | OIDC issuer for real sign-in |
| `SCHOOMANO_PORT` | HTTP port (default 8080) |

API contract: `../docs/api/openapi.yaml`. Migration rules: `../docs/MIGRATIONS.md`.
