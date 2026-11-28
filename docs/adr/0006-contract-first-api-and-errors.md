# ADR 0006: Contract-first REST API, problem details, correlation ids

Status: Accepted (2026-09-25)

- `docs/api/openapi.yaml` is the contract (validated with Redocly). Web types mirror it today; generated clients arrive in Stage B.
- Errors are RFC 9457 problem details with a stable `code` and `correlationId`; no stack traces or internal messages.
- Every request gets `X-Correlation-Id` (accepted if well-formed, otherwise generated), written to logs (MDC) and audit events.
- Writes that may be retried (payments callbacks, offline sync, imports) must carry an idempotency key; the `idempotency_record` table exists and is unique per tenant + scope + key (verified in DB tests). The HTTP idempotency filter arrived in B36: it uses its own table `api_idempotency` keyed by a caller hash, reachable only through database functions, because the filter runs before the school is known; the older `idempotency_record` table is unused.
