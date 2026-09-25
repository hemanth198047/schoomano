# ADR 0001: Single-project monoliths: Spring Boot backend, React frontend, PostgreSQL

Status: Accepted (2026-09-25); amended the same day: the backend's Maven multi-module split was removed at the product owner's request.

## Context
The spec (§13) recommends a monolith with clear domain boundaries and an events/outbox table, PostgreSQL as transactional truth, and React on the web. The team knows Java and Spring. Pilot scale is one school. The product owner prefers the simplest possible structure: one backend project and one frontend project, no sub-modules.

## Decision
- **Backend**: one Maven project (`backend/pom.xml`), one deployable jar, packages by responsibility:
  - `com.schoomano.domain.*`: business rules (tenancy scopes, authorization policy, configuration resolution) in plain Java.
  - `com.schoomano.platform`, `.security`, `.api`: Spring infrastructure, sign-in and HTTP endpoints.
- **Frontend**: one npm package (`frontend/package.json`); design tokens are a JSON file inside it.
- PostgreSQL 16 with Flyway; transactional outbox table; object storage, queue and cache are added when a slice first needs them.

## Keeping the boundary without modules
The multi-module build used to guarantee that the rules protecting children could not depend on Spring or the database. That guarantee is now a unit test, `DomainPurityTest`, which fails the build if any class under `com.schoomano.domain` imports Spring, Jakarta/javax, JDBC, Jackson, SLF4J or the application packages. A negative control (planting a `java.sql` import) was verified to fail it.

## Consequences
- Simpler build, one jar, one place to look.
- Should any part ever need to be extracted, the package boundaries are the seams.

## Version note
Spring Boot is pinned to **3.5.6** because Stage A was written without access to Maven Central. Its open-source support ended in mid-2026; **upgrade to Spring Boot 4.0.x right after the first green local build**.
