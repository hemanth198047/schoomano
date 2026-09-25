import { useCallback, useEffect, useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import { useApiSession } from './session';

export type Resource<T> =
  { status: 'loading' } | { status: 'error'; error: ApiError } | { status: 'ready'; data: T };

/**
 * GET with explicit loading/error/ready states, abort on unmount and a reload handle.
 * "Loading" is derived: a stored result only counts if it belongs to the current request key,
 * so changing path, session or reloading shows loading without a cascading setState in the effect.
 */
export function useApiResource<T>(path: string | null): [Resource<T>, () => void] {
  const session = useApiSession();
  const [nonce, setNonce] = useState(0);
  const [result, setResult] = useState<{ key: string; value: Resource<T> } | null>(null);
  const key =
    path === null ? null : `${session.baseUrl}|${session.token}|${session.tenantSlug ?? ''}|${path}|${nonce}`;

  useEffect(() => {
    if (path === null || key === null) return;
    const ctrl = new AbortController();
    apiRequest<T>(session, path, { signal: ctrl.signal })
      .then((data) => setResult({ key, value: { status: 'ready', data } }))
      .catch((e: unknown) => {
        if (e instanceof DOMException && e.name === 'AbortError') return;
        const error =
          e instanceof ApiError ? e : new ApiError(0, 'client_error', 'Something went wrong.', null);
        setResult({ key, value: { status: 'error', error } });
      });
    return () => ctrl.abort();
  }, [session, path, key]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const state: Resource<T> = result !== null && result.key === key ? result.value : { status: 'loading' };
  return [state, reload];
}
