# ADR 0001: Modular monolith on Java 21 + Spring Boot, PostgreSQL, React

Status: Accepted (2026-09-25)

## Context
The spec (§13) recommends a modular monolith with events/outbox, PostgreSQL as transactional truth, and React on the web. The team knows Java and Spring. Pilot scale is 5 campuses / 25,000 students.

## Decision
- One deployable backend (`backend/schoomano-app`) with bounded packages per domain.
- A separate Maven module `schoomano-core` holds framework-free domain rules (tenancy scopes, authorization policy, config resolution). It has no Spring or JDBC dependency, so the rules that protect children are testable in milliseconds and cannot quietly depend on infrastructure.
- PostgreSQL 16 with Flyway migrations; transactional outbox table for events; object storage, queue and Redis are added when a slice first needs them (not before).
- React + TypeScript + Vite for the admin web; design tokens shared through `packages/design-tokens`.

## Consequences
- Service extraction stays possible along module boundaries but is not planned until load evidence exists.
- ArchUnit checks for package dependencies are a Stage B follow-up.

## Version note
Spring Boot is pinned to **3.5.6**. Stage A was authored in an environment without Maven Central access, so code was written against APIs that could be relied on without compiling. Spring Boot 3.5's open-source support window ended in mid-2026; **upgrading to Spring Boot 4.0.x is the first task after the first green local build** (expected changes: `spring-boot-starter-webmvc` naming, Jackson 3 packages, Flyway starter). Tracked in the release checklist.
