import { ApiError } from './api';

/**
 * Staff sign in through the school's identity provider with the authorization-code flow and PKCE (RFC 7636, RFC 8252).
 * Everything here is plain logic with the platform pieces passed in, so it can be tested without a phone.
 */
export interface OidcConfig {
  issuer: string;
  clientId: string;
  redirectUri: string;
  scopes?: string[];
}

export interface Discovery {
  authorizationEndpoint: string;
  tokenEndpoint: string;
  endSessionEndpoint?: string;
}

export interface Platform {
  fetchImpl: typeof fetch;
  /** Cryptographically secure random bytes. */
  randomBytes(n: number): Uint8Array;
  /** SHA-256 of an ASCII string. */
  sha256(text: string): Promise<Uint8Array>;
}

export interface IdpTokens {
  accessToken: string;
  refreshToken: string | null;
  /** Epoch milliseconds when the access token stops working. */
  expiresAt: number;
}

const b64url = (bytes: Uint8Array): string => {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

export async function discover(config: OidcConfig, p: Platform): Promise<Discovery> {
  const base = config.issuer.replace(/\/+$/, '');
  let res: Response;
  try {
    res = await p.fetchImpl(`${base}/.well-known/openid-configuration`);
  } catch {
    throw new ApiError(
      0,
      'network_unreachable',
      'The school sign-in could not be reached. Check your connection.',
    );
  }
  if (!res.ok)
    throw new ApiError(res.status, 'oidc_discovery_failed', 'The school sign-in is not available right now.');
  const d = (await res.json()) as {
    issuer?: string;
    authorization_endpoint?: string;
    token_endpoint?: string;
    end_session_endpoint?: string;
  };
  // A discovery document that names a different issuer could redirect people to someone else's login page.
  if (d.issuer?.replace(/\/+$/, '') !== base || !d.authorization_endpoint || !d.token_endpoint) {
    throw new ApiError(
      502,
      'oidc_discovery_invalid',
      'The school sign-in is set up incorrectly. Please tell the school office.',
    );
  }
  for (const u of [d.authorization_endpoint, d.token_endpoint]) {
    if (!u.startsWith('https://') && !isLocalDev(u))
      throw new ApiError(502, 'oidc_insecure', 'The school sign-in must use a secure address.');
  }
  return {
    authorizationEndpoint: d.authorization_endpoint,
    tokenEndpoint: d.token_endpoint,
    endSessionEndpoint: d.end_session_endpoint,
  };
}

const isLocalDev = (u: string) => /^http:\/\/(localhost|127\.0\.0\.1|10\.0\.2\.2)(:|\/)/.test(u);

export interface AuthRequest {
  url: string;
  state: string;
  verifier: string;
}

/** Starts a sign-in: a fresh secret (verifier) stays on the phone, only its hash goes to the provider. */
export async function buildAuthRequest(config: OidcConfig, d: Discovery, p: Platform): Promise<AuthRequest> {
  const verifier = b64url(p.randomBytes(48));
  const state = b64url(p.randomBytes(24));
  const challenge = b64url(await p.sha256(verifier));
  const q = new URLSearchParams({
    response_type: 'code',
    client_id: config.clientId,
    redirect_uri: config.redirectUri,
    scope: (config.scopes ?? ['openid', 'profile', 'offline_access']).join(' '),
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  });
  return {
    url: `${d.authorizationEndpoint}${d.authorizationEndpoint.includes('?') ? '&' : '?'}${q.toString()}`,
    state,
    verifier,
  };
}

/** Reads the provider's redirect back into the app. A wrong state means the answer was not for this sign-in. */
export function readRedirect(redirectUrl: string, expectedState: string): string {
  const qIndex = redirectUrl.indexOf('?');
  const params = new URLSearchParams(qIndex < 0 ? '' : redirectUrl.slice(qIndex + 1).split('#')[0]);
  const error = params.get('error');
  if (error) {
    throw new ApiError(
      400,
      'oidc_denied',
      error === 'access_denied' ? 'Sign-in was cancelled.' : 'The school sign-in refused this attempt.',
    );
  }
  if (params.get('state') !== expectedState) {
    throw new ApiError(
      400,
      'oidc_state_mismatch',
      'That sign-in did not match the one that was started. Please try again.',
    );
  }
  const code = params.get('code');
  if (!code)
    throw new ApiError(400, 'oidc_no_code', 'The school sign-in did not return a code. Please try again.');
  return code;
}

async function tokenCall(
  d: Discovery,
  body: URLSearchParams,
  p: Platform,
  now: number,
  keepRefresh: string | null,
): Promise<IdpTokens> {
  let res: Response;
  try {
    res = await p.fetchImpl(d.tokenEndpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body: body.toString(),
    });
  } catch {
    throw new ApiError(
      0,
      'network_unreachable',
      'The school sign-in could not be reached. Check your connection.',
    );
  }
  if (!res.ok) {
    const invalid = res.status === 400 || res.status === 401;
    throw new ApiError(
      res.status,
      invalid ? 'oidc_invalid_grant' : 'oidc_token_failed',
      invalid
        ? 'Your sign-in has ended. Please sign in again.'
        : 'The school sign-in is not available right now.',
    );
  }
  const t = (await res.json()) as { access_token?: string; refresh_token?: string; expires_in?: number };
  if (!t.access_token)
    throw new ApiError(502, 'oidc_token_invalid', 'The school sign-in returned an unusable answer.');
  // Providers may rotate the refresh token or keep the old one.
  return {
    accessToken: t.access_token,
    refreshToken: t.refresh_token ?? keepRefresh,
    expiresAt: now + (t.expires_in ?? 300) * 1000,
  };
}

export function exchangeCode(
  config: OidcConfig,
  d: Discovery,
  code: string,
  verifier: string,
  p: Platform,
  now: number,
): Promise<IdpTokens> {
  return tokenCall(
    d,
    new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: config.redirectUri,
      client_id: config.clientId,
      code_verifier: verifier,
    }),
    p,
    now,
    null,
  );
}

export function refreshTokens(
  config: OidcConfig,
  d: Discovery,
  refreshToken: string,
  p: Platform,
  now: number,
): Promise<IdpTokens> {
  return tokenCall(
    d,
    new URLSearchParams({
      grant_type: 'refresh_token',
      refresh_token: refreshToken,
      client_id: config.clientId,
    }),
    p,
    now,
    refreshToken,
  );
}

/** The client's hook for staff sessions: refreshes through the provider, and leaves phone-code sessions alone. */
export function createOidcRefresher(
  config: OidcConfig,
  p: Platform,
  isStaffSession: () => Promise<boolean>,
  now: () => number = Date.now,
) {
  let discovery: Promise<Discovery> | null = null;
  return async (current: { accessToken: string; refreshToken: string }) => {
    if (!(await isStaffSession())) return null;
    if (!current.refreshToken) return 'ended' as const;
    discovery ??= discover(config, p).catch((e: unknown) => {
      discovery = null;
      throw e;
    });
    try {
      const t = await refreshTokens(config, await discovery, current.refreshToken, p, now());
      return { accessToken: t.accessToken, refreshToken: t.refreshToken ?? current.refreshToken };
    } catch (e) {
      if (e instanceof ApiError && e.code === 'oidc_invalid_grant') return 'ended' as const;
      throw e;
    }
  };
}
