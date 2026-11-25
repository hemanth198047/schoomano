import { describe, expect, it, vi } from 'vitest';
import { ApiError, createClient, type TokenStore } from './api';

function tokenStore(
  initial: { accessToken: string; refreshToken: string } | null,
): TokenStore & { current: typeof initial } {
  const s = {
    current: initial,
    load: () => Promise.resolve(s.current),
    save: (t: { accessToken: string; refreshToken: string }) => {
      s.current = t;
      return Promise.resolve();
    },
    clear: () => {
      s.current = null;
      return Promise.resolve();
    },
  };
  return s;
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('client', () => {
  it('sends the bearer token and returns the body', async () => {
    const seen: Headers[] = [];
    const f = vi.fn((_u: string, init: RequestInit) => {
      seen.push(new Headers(init.headers));
      return Promise.resolve(json(200, { ok: true }));
    });
    const c = createClient({
      baseUrl: 'http://x',
      tokens: tokenStore({ accessToken: 'a1', refreshToken: 'r1' }),
      fetchImpl: f as unknown as typeof fetch,
    });
    expect(await c.request('/api/v1/me')).toEqual({ ok: true });
    expect(seen[0]!.get('Authorization')).toBe('Bearer a1');
  });

  it('refreshes once after a 401, even when several calls fail together, then retries them', async () => {
    const store = tokenStore({ accessToken: 'old', refreshToken: 'r1' });
    let refreshes = 0;
    const f = vi.fn((u: string, init: RequestInit) => {
      if (u.endsWith('/auth/token/refresh')) {
        refreshes++;
        return Promise.resolve(
          json(200, { accessToken: 'new', refreshToken: 'r2', expiresIn: 1800, refreshExpiresIn: 1 }),
        );
      }
      const auth = new Headers(init.headers).get('Authorization');
      return Promise.resolve(
        auth === 'Bearer new' ? json(200, { n: 1 }) : json(401, { code: 'unauthenticated', detail: 'no' }),
      );
    });
    const c = createClient({ baseUrl: 'http://x', tokens: store, fetchImpl: f as unknown as typeof fetch });
    const out = await Promise.all([c.request('/a'), c.request('/b'), c.request('/c')]);
    expect(out).toHaveLength(3);
    expect(refreshes).toBe(1);
    expect(store.current).toEqual({ accessToken: 'new', refreshToken: 'r2' });
  });

  it('signs the person out when the refresh token is refused', async () => {
    const store = tokenStore({ accessToken: 'old', refreshToken: 'dead' });
    const out = vi.fn();
    const f = vi.fn((u: string) =>
      Promise.resolve(
        u.endsWith('/auth/token/refresh')
          ? json(401, { code: 'invalid_refresh_token', detail: 'no' })
          : json(401, { code: 'unauthenticated', detail: 'no' }),
      ),
    );
    const c = createClient({
      baseUrl: 'http://x',
      tokens: store,
      fetchImpl: f as unknown as typeof fetch,
      onSignedOut: out,
    });
    await expect(c.request('/a')).rejects.toMatchObject({ status: 401 });
    expect(out).toHaveBeenCalledOnce();
    expect(store.current).toBeNull();
  });

  it('keeps the session when the network is simply down during a refresh', async () => {
    const store = tokenStore({ accessToken: 'old', refreshToken: 'r1' });
    let first = true;
    const f = vi.fn((u: string) => {
      if (u.endsWith('/auth/token/refresh')) return Promise.reject(new TypeError('offline'));
      if (first) {
        first = false;
        return Promise.resolve(json(401, { code: 'unauthenticated', detail: 'no' }));
      }
      return Promise.resolve(json(200, {}));
    });
    const c = createClient({ baseUrl: 'http://x', tokens: store, fetchImpl: f as unknown as typeof fetch });
    await expect(c.request('/a')).rejects.toBeInstanceOf(ApiError);
    expect(store.current).not.toBeNull();
  });

  it('turns a dead connection into a plain network error', async () => {
    const c = createClient({
      baseUrl: 'http://x',
      tokens: tokenStore(null),
      fetchImpl: (() => Promise.reject(new TypeError('x'))) as unknown as typeof fetch,
    });
    await expect(c.request('/a')).rejects.toMatchObject({ status: 0, code: 'network_unreachable' });
  });

  it('stores the tokens after a correct code and never sends a token with sign-in calls', async () => {
    const store = tokenStore(null);
    const seen: Headers[] = [];
    const f = vi.fn((_u: string, init: RequestInit) => {
      seen.push(new Headers(init.headers));
      return Promise.resolve(
        json(200, { accessToken: 'a', refreshToken: 'r', expiresIn: 1, refreshExpiresIn: 1 }),
      );
    });
    const c = createClient({ baseUrl: 'http://x', tokens: store, fetchImpl: f as unknown as typeof fetch });
    await c.verifyOtp('98450 12345', '123456', 'Pixel');
    expect(store.current).toEqual({ accessToken: 'a', refreshToken: 'r' });
    expect(seen[0]!.get('Authorization')).toBeNull();
  });
});

describe('client with a staff (provider) refresh', () => {
  const unauthorized = () => Promise.resolve(json(401, { code: 'unauthenticated', detail: 'no' }));

  it('uses the provider refresh instead of the phone-code one and retries', async () => {
    const store = tokenStore({ accessToken: 'old', refreshToken: 'idp-r1' });
    const urls: string[] = [];
    const f = vi.fn((u: string, init: RequestInit) => {
      urls.push(u);
      return Promise.resolve(
        new Headers(init.headers).get('Authorization') === 'Bearer idp-new'
          ? json(200, { ok: 1 })
          : json(401, {}),
      );
    });
    const c = createClient({
      baseUrl: 'http://x',
      tokens: store,
      fetchImpl: f as unknown as typeof fetch,
      externalRefresh: () => Promise.resolve({ accessToken: 'idp-new', refreshToken: 'idp-r2' }),
    });
    expect(await c.request('/a')).toEqual({ ok: 1 });
    expect(urls.some((u) => u.includes('/auth/token/refresh'))).toBe(false);
    expect(store.current).toEqual({ accessToken: 'idp-new', refreshToken: 'idp-r2' });
  });

  it('signs out when the provider ends the session, but keeps the session when it cannot be reached', async () => {
    const out = vi.fn();
    const ended = tokenStore({ accessToken: 'o', refreshToken: 'r' });
    const f = vi.fn(unauthorized);
    const c1 = createClient({
      baseUrl: 'http://x',
      tokens: ended,
      fetchImpl: f as unknown as typeof fetch,
      onSignedOut: out,
      externalRefresh: () => Promise.resolve('ended'),
    });
    await expect(c1.request('/a')).rejects.toMatchObject({ status: 401 });
    expect(out).toHaveBeenCalledOnce();
    expect(ended.current).toBeNull();

    const kept = tokenStore({ accessToken: 'o', refreshToken: 'r' });
    const c2 = createClient({
      baseUrl: 'http://x',
      tokens: kept,
      fetchImpl: f as unknown as typeof fetch,
      externalRefresh: () => Promise.reject(new ApiError(0, 'network_unreachable', 'down')),
    });
    await expect(c2.request('/a')).rejects.toMatchObject({ status: 401 });
    expect(kept.current).not.toBeNull();
  });

  it('falls back to the phone-code refresh for parents', async () => {
    const store = tokenStore({ accessToken: 'o', refreshToken: 'r' });
    let refreshed = false;
    const f = vi.fn((u: string, init: RequestInit) => {
      if (u.endsWith('/auth/token/refresh')) {
        refreshed = true;
        return Promise.resolve(
          json(200, { accessToken: 'n', refreshToken: 'r2', expiresIn: 1, refreshExpiresIn: 1 }),
        );
      }
      return Promise.resolve(
        new Headers(init.headers).get('Authorization') === 'Bearer n' ? json(200, {}) : json(401, {}),
      );
    });
    const c = createClient({
      baseUrl: 'http://x',
      tokens: store,
      fetchImpl: f as unknown as typeof fetch,
      externalRefresh: () => Promise.resolve(null),
    });
    await c.request('/a');
    expect(refreshed).toBe(true);
  });
});
