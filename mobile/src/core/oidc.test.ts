import { createHash } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from './api';
import {
  buildAuthRequest,
  discover,
  exchangeCode,
  readRedirect,
  refreshTokens,
  type OidcConfig,
  type Platform,
} from './oidc';

const config: OidcConfig = {
  issuer: 'https://idp.example/realms/school',
  clientId: 'schoomano-mobile',
  redirectUri: 'schoomano://signin',
};
const d = { authorizationEndpoint: 'https://idp.example/auth', tokenEndpoint: 'https://idp.example/token' };
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

function platform(fetchImpl: typeof fetch): Platform {
  let n = 0;
  return {
    fetchImpl,
    randomBytes: (len) => Uint8Array.from({ length: len }, (_, i) => (i * 7 + ++n) % 256),
    sha256: (t) => Promise.resolve(new Uint8Array(createHash('sha256').update(t).digest())),
  };
}

describe('OIDC sign-in', () => {
  it('builds a PKCE request whose challenge is the hash of a verifier that never leaves the phone', async () => {
    const r = await buildAuthRequest(config, d, platform(vi.fn() as never));
    const q = new URL(r.url).searchParams;
    expect(q.get('code_challenge_method')).toBe('S256');
    expect(q.get('code_challenge')).toBe(createHash('sha256').update(r.verifier).digest('base64url'));
    expect(q.get('state')).toBe(r.state);
    expect(q.get('scope')).toContain('offline_access');
    expect(r.url).not.toContain(r.verifier);
    expect(r.verifier.length).toBeGreaterThanOrEqual(43);
    expect(r.verifier).toMatch(/^[A-Za-z0-9_-]+$/);
    const again = await buildAuthRequest(config, d, platform(vi.fn() as never));
    expect(again.state).toBe(r.state); // same fake randomness here; real bytes differ every time
  });

  it('accepts only a redirect that carries the state it started with', () => {
    expect(readRedirect('schoomano://signin?code=abc&state=s1', 's1')).toBe('abc');
    expect(() => readRedirect('schoomano://signin?code=abc&state=other', 's1')).toThrow(/did not match/);
    expect(() => readRedirect('schoomano://signin?state=s1', 's1')).toThrow(/did not return a code/);
    expect(() => readRedirect('schoomano://signin?error=access_denied&state=s1', 's1')).toThrow(/cancelled/);
    expect(() => readRedirect('schoomano://signin', 's1')).toThrow(ApiError);
  });

  it('refuses a discovery document that names another issuer or an insecure address', async () => {
    const ok = {
      issuer: config.issuer,
      authorization_endpoint: 'https://idp.example/auth',
      token_endpoint: 'https://idp.example/token',
    };
    expect(await discover(config, platform((() => Promise.resolve(json(200, ok))) as never))).toMatchObject({
      tokenEndpoint: 'https://idp.example/token',
    });
    await expect(
      discover(
        config,
        platform((() => Promise.resolve(json(200, { ...ok, issuer: 'https://evil.example' }))) as never),
      ),
    ).rejects.toMatchObject({ code: 'oidc_discovery_invalid' });
    await expect(
      discover(
        config,
        platform((() =>
          Promise.resolve(json(200, { ...ok, token_endpoint: 'http://idp.example/token' }))) as never),
      ),
    ).rejects.toMatchObject({ code: 'oidc_insecure' });
    await expect(
      discover(config, platform((() => Promise.reject(new TypeError('x'))) as never)),
    ).rejects.toMatchObject({ status: 0 });
  });

  it('exchanges the code with the verifier and keeps a rotated refresh token', async () => {
    let sent = '';
    const f = vi.fn((_u: string, init: RequestInit) => {
      sent = String(init.body);
      return Promise.resolve(json(200, { access_token: 'a1', refresh_token: 'r1', expires_in: 300 }));
    });
    const t = await exchangeCode(config, d, 'code1', 'verifier1', platform(f as never), 1000);
    expect(new URLSearchParams(sent).get('code_verifier')).toBe('verifier1');
    expect(new URLSearchParams(sent).get('grant_type')).toBe('authorization_code');
    expect(t).toEqual({ accessToken: 'a1', refreshToken: 'r1', expiresAt: 1000 + 300_000 });
  });

  it('refreshes, keeps the old refresh token when the provider sends none, and reports an ended sign-in', async () => {
    const keep = vi.fn(() => Promise.resolve(json(200, { access_token: 'a2', expires_in: 60 })));
    expect(await refreshTokens(config, d, 'r1', platform(keep as never), 0)).toEqual({
      accessToken: 'a2',
      refreshToken: 'r1',
      expiresAt: 60_000,
    });
    const dead = vi.fn(() => Promise.resolve(json(400, { error: 'invalid_grant' })));
    await expect(refreshTokens(config, d, 'r1', platform(dead as never), 0)).rejects.toMatchObject({
      code: 'oidc_invalid_grant',
    });
    const down = vi.fn(() => Promise.reject(new TypeError('offline')));
    await expect(refreshTokens(config, d, 'r1', platform(down as never), 0)).rejects.toMatchObject({
      status: 0,
    });
  });
});

describe('staff refresh hook', () => {
  const cfg = config;
  const disc = {
    issuer: cfg.issuer,
    authorization_endpoint: 'https://idp.example/auth',
    token_endpoint: 'https://idp.example/token',
  };

  it('does nothing for phone-code sessions', async () => {
    const { createOidcRefresher } = await import('./oidc');
    const f = vi.fn();
    const r = createOidcRefresher(cfg, platform(f as never), () => Promise.resolve(false));
    expect(await r({ accessToken: 'a', refreshToken: 'r' })).toBeNull();
    expect(f).not.toHaveBeenCalled();
  });

  it('refreshes through the provider, ends the session on invalid_grant, and keeps it when offline', async () => {
    const { createOidcRefresher } = await import('./oidc');
    let mode: 'ok' | 'dead' | 'down' = 'ok';
    const f = vi.fn((u: string) => {
      if (u.endsWith('openid-configuration')) return Promise.resolve(json(200, disc));
      if (mode === 'down') return Promise.reject(new TypeError('offline'));
      return Promise.resolve(
        mode === 'ok'
          ? json(200, { access_token: 'new', refresh_token: 'r2', expires_in: 300 })
          : json(400, { error: 'invalid_grant' }),
      );
    });
    const r = createOidcRefresher(
      cfg,
      platform(f as never),
      () => Promise.resolve(true),
      () => 0,
    );
    expect(await r({ accessToken: 'a', refreshToken: 'r1' })).toEqual({
      accessToken: 'new',
      refreshToken: 'r2',
    });
    mode = 'dead';
    expect(await r({ accessToken: 'a', refreshToken: 'r2' })).toBe('ended');
    mode = 'down';
    await expect(r({ accessToken: 'a', refreshToken: 'r2' })).rejects.toMatchObject({ status: 0 });
    expect(await r({ accessToken: 'a', refreshToken: '' })).toBe('ended');
    expect(f.mock.calls.filter((c) => String(c[0]).endsWith('openid-configuration'))).toHaveLength(1);
  });
});
