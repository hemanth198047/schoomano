# Release checklist

## Every release
- [ ] CI green: backend verify (unit + Testcontainers), database tests, web check, secrets scan
- [ ] `IMPLEMENTATION_STATUS.md` updated with evidence for each changed module
- [ ] Migrations reviewed for RLS, grants, composite FKs, and an expand/contract plan if breaking
- [ ] OpenAPI contract updated and linted; breaking changes versioned
- [ ] New endpoints have a cross-tenant test and a denied-role test
- [ ] Accessibility: axe tests pass; keyboard walk-through of changed screens; 390 px screenshot review
- [ ] No real personal data in fixtures, logs, screenshots or tickets
- [ ] Rollback plan written (app version and database)

## Before the first shared environment
- [ ] Spring Boot upgraded to 4.0.x (ADR 0001) and full suite green
- [ ] `SCHOOMANO_AUTH_DEV_MODE` unset and `prod` profile active; startup guard verified
- [ ] Migration owner is a non-superuser role
- [ ] OIDC issuer configured and tested; MFA for admin roles
- [ ] TLS, secrets in a secret manager, backups enabled

## Before any real child data (pilot)
- [ ] DPDP counsel review of consent, guardian verification, retention and breach process
- [ ] Data processing agreement signed with the school
- [ ] Independent penetration test, critical findings fixed
- [ ] Restore drill meets RPO 15 min / RTO 4 h
- [ ] Support-access procedure agreed with the school
- [ ] Hindi (and any other shipped language) reviewed by native speakers
