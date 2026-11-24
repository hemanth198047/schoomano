# ADR 0009: Parents sign in with a phone OTP

Status: Accepted in principle (2026-09-25); implemented in slice B4 (2026-11-18) except the real SMS provider

## Context
Pilot decision D3: parents sign in with a one-time password sent to their phone. Parents use personal Android phones, often on slow networks, and many do not use email.

## Decision
- Parent identity = verified mobile number (+91) held on the guardian record by the school. A number not on a guardian record cannot sign in; the response is the same either way so numbers cannot be enumerated.
- OTP: 6 digits, valid 5 minutes, stored only as a salted hash, max 5 attempts per code, resend allowed after 30 s, per-number and per-IP rate limits, daily cap per number to limit SMS-pumping cost.
- SMS via an Indian provider registered under TRAI DLT (sender ID and template approval needed; lead time of days to weeks, so start early). Provider behind an interface; a console "SMS" adapter is used locally.
- A successful OTP issues the same session/token type as staff sign-in, so authorization (ADR 0003) is unchanged. Parent sessions are long-lived on mobile with refresh-token rotation and can be revoked by the school.
- Changing a parent's phone number is a school-office action with reason and audit, never self-service, because a hijacked number would expose child data.
- Staff do **not** use SMS OTP; they use the OIDC provider with TOTP MFA for privileged roles (assumption; staff sign-in was not specified).

## Implementation notes (B4)
- Codes are stored as HMAC-SHA256 with a server-side pepper (`SCHOOMANO_OTP_PEPPER`), not a plain salted hash: a 6-digit code has only a million possibilities, so a leaked database would otherwise reveal every live code by brute force.
- Unknown numbers get a stored, counted challenge that can never verify, so responses, limits and timing match known numbers; the SMS itself is sent in the background.
- Access tokens last 30 minutes, refresh tokens 30 days and are single use; replaying a replaced refresh token revokes the session.
- A phone session carries guardian links only. Staff roles require the staff sign-in, so a teacher who is also a parent cannot reach staff powers with only a code.
- The school office can list and end a guardian's sessions (audited). Changing the number ends the old number's sign-in.

## Risks
SIM-swap and shared family phones: mitigated by school-controlled number changes, visible "new device signed in" notices, and session lists parents can see. Revisit WhatsApp OTP as a delivery channel only after DPDP review.
