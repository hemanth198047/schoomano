# ADR 0007: OIDC identity, server-side tenant resolution, dev tokens only locally

Status: Accepted (2026-09-25)

- Production: OIDC access tokens validated by Spring Security resource server (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`). The IdP (e.g. Keycloak, Azure AD, Google Workspace) is decision D3.
- Issuer + subject map to `identity` rows; one identity per tenant. If an identity belongs to several school groups, the client must select one with `X-Schoomano-Tenant`, which the server checks against the identity's memberships (tested).
- Local development: `Bearer dev:<subject>` for synthetic seed users, only when `schoomano.auth.dev-mode=true`; application startup fails if combined with the `prod` profile.
- Web keeps tokens in memory only (no localStorage).
- Parents sign in with a phone OTP (pilot decision D3): see ADR 0009. Staff MFA, session revocation and recovery: slice B4.
