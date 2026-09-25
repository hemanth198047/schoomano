# Schoomano backend

Spring Boot 3.5 (Java 21) modular monolith with PostgreSQL 16. See the repository README for running the full stack.

```
schoomano-core/   framework-free rules: tenancy scopes, access policy, config resolution (+ JUnit tests)
schoomano-app/    Spring Boot API, security, Flyway migrations (src/main/resources/db/migration)
db/seed/          synthetic, re-runnable development seed
db/tests/         SQL tests: RLS isolation and invariants; smoke test of every SQL statement the API uses
scripts/          verify-core-without-maven.sh (fallback when Maven Central is unreachable)
Dockerfile        multi-stage build → non-root JRE image, health check on /actuator/health/readiness
```

## Build and test
```sh
mvn verify                                   # unit tests + Testcontainers API tests (needs Docker)
mvn -DskipTests package                      # → schoomano-app/target/schoomano-app.jar
docker build -t schoomano-backend:local .
```

## Run on your machine
Start only the database with `scripts/start.sh db` (or `scripts\start.cmd db`) from the repository root, then either `scripts/start.sh --dev backend`, or manually with the variables from the root `.env` loaded:
```sh
java -jar schoomano-app/target/schoomano-app.jar
```

## Configuration (environment)
| Variable | Purpose |
|---|---|
| `SCHOOMANO_DB_URL` | JDBC URL (default `jdbc:postgresql://localhost:5432/schoomano`) |
| `SCHOOMANO_DB_APP_USER` / `_PASSWORD` | Unprivileged runtime role; RLS applies to it |
| `SCHOOMANO_DB_OWNER_USER` / `_PASSWORD` | Schema owner used only by Flyway |
| `SCHOOMANO_AUTH_DEV_MODE` | `true` enables synthetic `Bearer dev:<subject>` tokens. Local only; refused with the `prod` profile |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | OIDC issuer for real sign-in |
| `SCHOOMANO_PORT` | HTTP port (default 8080) |

API contract: `../docs/api/openapi.yaml`. Migration rules: `../docs/MIGRATIONS.md`.
