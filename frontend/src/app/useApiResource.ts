import { useCallback, useEffect, useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import { useApiSession } from './session';

export type Resource<T> =
  { status: 'loading' } | { status: 'error'; error: ApiError } | { status: 'ready'; data: T };

/**
 * GET with explicit loading/error/ready states, abort on unmount and a reload handle.
 * "Loading" is derived: a stored result only counts if it belongs to the current request key,
 * so changing path or session shows loading without a cascading setState in the effect.
 * A reload of the same request keeps showing the previous result until the new one arrives, so pages
 * do not unmount (and lose confirmation messages) after a successful save.
 */
export function useApiResource<T>(path: string | null): [Resource<T>, () => void] {
  const session = useApiSession();
  const [nonce, setNonce] = useState(0);
  const [result, setResult] = useState<{ key: string; base: string; value: Resource<T> } | null>(null);
  const base =
    path === null ? null : `${session.baseUrl}|${session.token}|${session.tenantSlug ?? ''}|${path}`;
  const key = base === null ? null : `${base}|${nonce}`;

  useEffect(() => {
    if (path === null || key === null) return;
    const ctrl = new AbortController();
    apiRequest<T>(session, path, { signal: ctrl.signal })
      .then((data) => setResult({ key, base: base!, value: { status: 'ready', data } }))
      .catch((e: unknown) => {
        if (e instanceof DOMException && e.name === 'AbortError') return;
        const error =
          e instanceof ApiError ? e : new ApiError(0, 'client_error', 'Something went wrong.', null);
        setResult({ key, base: base!, value: { status: 'error', error } });
      });
    return () => ctrl.abort();
  }, [session, path, key, base]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const state: Resource<T> =
    result !== null && (result.key === key || (result.base === base && result.value.status === 'ready'))
      ? result.value
      : { status: 'loading' };
  return [state, reload];
}
