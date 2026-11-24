import type { TokenResponse } from './types';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(detail);
    this.name = 'ApiError';
  }
}

/** Where the session lives between launches. The real one is the platform's secure store. */
export interface TokenStore {
  load(): Promise<{ accessToken: string; refreshToken: string } | null>;
  save(tokens: { accessToken: string; refreshToken: string }): Promise<void>;
  clear(): Promise<void>;
}

export interface ClientOptions {
  baseUrl: string;
  tokens: TokenStore;
  fetchImpl?: typeof fetch;
  /** Called when the refresh token no longer works, so the app can return to sign-in. */
  onSignedOut?: () => void;
}

const unreachable = () =>
  new ApiError(0, 'network_unreachable', 'The server could not be reached. Check your connection.');

async function errorFrom(res: Response): Promise<ApiError> {
  let p: { code?: string; detail?: string; retryAfterSeconds?: number } = {};
  try {
    p = (await res.json()) as typeof p;
  } catch {
    /* not JSON */
  }
  return new ApiError(
    res.status,
    p.code ?? (res.status === 401 ? 'unauthenticated' : 'http_error'),
    p.detail ?? `Request failed with status ${res.status}.`,
    p.retryAfterSeconds ?? null,
  );
}

export function createClient(opts: ClientOptions) {
  const doFetch = opts.fetchImpl ?? fetch;
  let refreshing: Promise<boolean> | null = null;

  async function send(
    path: string,
    init: RequestInit & { json?: unknown },
    token: string | null,
  ): Promise<Response> {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      'X-Correlation-Id': Math.random().toString(16).slice(2) + Date.now().toString(16),
      ...(init.headers as Record<string, string> | undefined),
    };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (init.json !== undefined) headers['Content-Type'] = 'application/json';
    try {
      return await doFetch(`${opts.baseUrl}${path}`, {
        method: init.method ?? 'GET',
        headers,
        body: init.json === undefined ? undefined : JSON.stringify(init.json),
      });
    } catch {
      throw unreachable();
    }
  }

  /** One refresh at a time: refresh tokens are single use, so two parallel refreshes would sign the person out. */
  function refresh(): Promise<boolean> {
    refreshing ??= (async () => {
      try {
        const t = await opts.tokens.load();
        if (!t) return false;
        const res = await send(
          '/api/v1/auth/token/refresh',
          { method: 'POST', json: { refreshToken: t.refreshToken } },
          null,
        );
        if (!res.ok) {
          if (res.status === 400 || res.status === 401) {
            await opts.tokens.clear();
            opts.onSignedOut?.();
          }
          return false;
        }
        const n = (await res.json()) as TokenResponse;
        await opts.tokens.save({ accessToken: n.accessToken, refreshToken: n.refreshToken });
        return true;
      } finally {
        refreshing = null;
      }
    })();
    return refreshing;
  }

  async function request<T>(
    path: string,
    init: { method?: string; json?: unknown; headers?: Record<string, string> } = {},
  ): Promise<T> {
    const t = await opts.tokens.load();
    let res = await send(path, init, t?.accessToken ?? null);
    if (res.status === 401 && t) {
      if (await refresh()) {
        const again = await opts.tokens.load();
        res = await send(path, init, again?.accessToken ?? null);
      }
    }
    if (res.ok) return (res.status === 204 ? undefined : await res.json()) as T;
    throw await errorFrom(res);
  }

  async function publicPost<T>(path: string, json: unknown): Promise<T> {
    const res = await send(path, { method: 'POST', json }, null);
    if (res.ok) return (await res.json()) as T;
    throw await errorFrom(res);
  }

  return {
    request,
    requestOtp: (phone: string) =>
      publicPost<{ resendAfterSeconds: number }>('/api/v1/auth/otp/request', { phone }),
    async verifyOtp(phone: string, code: string, deviceLabel: string) {
      const t = await publicPost<TokenResponse>('/api/v1/auth/otp/verify', { phone, code, deviceLabel });
      await opts.tokens.save({ accessToken: t.accessToken, refreshToken: t.refreshToken });
    },
    /** For staff builds and development, where the token comes from elsewhere (the school's sign-in or a dev token). */
    async useToken(accessToken: string) {
      await opts.tokens.save({ accessToken, refreshToken: '' });
    },
    async signOut() {
      const t = await opts.tokens.load();
      await opts.tokens.clear();
      if (t) {
        try {
          await send('/api/v1/auth/logout', { method: 'POST' }, t.accessToken);
        } catch {
          /* offline: the short-lived token expires by itself */
        }
      }
    },
  };
}

export type Client = ReturnType<typeof createClient>;
