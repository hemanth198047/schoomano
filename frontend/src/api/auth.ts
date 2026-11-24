import { publicRequest } from './client';

/** Parent phone sign-in (ADR 0009). Public endpoints: no session yet. */
export interface TokenResponse {
  tokenType: 'Bearer';
  accessToken: string;
  /** Seconds until the access token stops working. */
  expiresIn: number;
  refreshToken: string;
  refreshExpiresIn: number;
}

export function requestOtp(baseUrl: string, phone: string) {
  return publicRequest<{ resendAfterSeconds: number }>(baseUrl, '/api/v1/auth/otp/request', { phone });
}

export function verifyOtp(baseUrl: string, phone: string, code: string, deviceLabel: string) {
  return publicRequest<TokenResponse>(baseUrl, '/api/v1/auth/otp/verify', { phone, code, deviceLabel });
}

export function refreshTokens(baseUrl: string, refreshToken: string) {
  return publicRequest<TokenResponse>(baseUrl, '/api/v1/auth/token/refresh', { refreshToken });
}

/** Best effort: the session ends on the server; any failure is ignored because the user is leaving anyway. */
export async function logoutSession(baseUrl: string, accessToken: string): Promise<void> {
  try {
    await fetch(`${baseUrl}/api/v1/auth/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}` },
    });
  } catch {
    /* offline: the short-lived token expires by itself */
  }
}
