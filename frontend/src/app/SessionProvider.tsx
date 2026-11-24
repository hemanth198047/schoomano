import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { SessionContext } from './session';
import { ApiError, type ApiSession } from '../api/client';
import { logoutSession, refreshTokens, type TokenResponse } from '../api/auth';

// Same-origin by default (proxied). Set VITE_API_BASE_URL only to call an API on another origin.
const baseUrl = import.meta.env.VITE_API_BASE_URL ?? '';

/** Renew this many seconds before the access token runs out (never sooner than 5 s from now). */
const RENEW_EARLY_SECONDS = 120;
/** After a failed renewal that was not a refusal (offline, server error), wait this long before trying again. */
const RETRY_SECONDS = 15;

/**
 * Holds tokens in memory only (never localStorage), so a stolen browser profile does not carry a live
 * session. Reloading the page means signing in again, which is acceptable for the web app; the phone apps
 * (B9) keep their refresh token in the device's secure storage instead.
 */
export function SessionProvider({
  children,
  initial = null,
}: {
  children: ReactNode;
  initial?: ApiSession | null;
}) {
  const [session, setSession] = useState<ApiSession | null>(initial);
  const [selectedCampusId, setSelectedCampusId] = useState<string | null>(null);
  // The refresh token of a phone-code session, and when to use it. Rotated on every use.
  const [renewal, setRenewal] = useState<{ token: string; at: number } | null>(null);

  const signIn = useCallback((token: string, tenantSlug?: string) => {
    setRenewal(null);
    setSession({ baseUrl, token, tenantSlug });
  }, []);

  const signInWithTokens = useCallback((t: TokenResponse) => {
    setSession({ baseUrl, token: t.accessToken });
    setRenewal({
      token: t.refreshToken,
      at: Date.now() + Math.max(5, t.expiresIn - RENEW_EARLY_SECONDS) * 1000,
    });
  }, []);

  const signOut = useCallback(() => {
    if (renewal && session) void logoutSession(baseUrl, session.token);
    setSession(null);
    setRenewal(null);
    setSelectedCampusId(null);
  }, [renewal, session]);

  useEffect(() => {
    if (!renewal) return;
    const timer = setTimeout(
      () => {
        refreshTokens(baseUrl, renewal.token).then(
          (t) => {
            setSession((s) => (s ? { ...s, token: t.accessToken } : s));
            setRenewal({
              token: t.refreshToken,
              at: Date.now() + Math.max(5, t.expiresIn - RENEW_EARLY_SECONDS) * 1000,
            });
          },
          (e: unknown) => {
            if (e instanceof ApiError && e.status === 401) {
              // The session was ended (by the school, or a replayed token): sign in again.
              setSession(null);
              setRenewal(null);
            } else {
              // Offline or a server hiccup: try again shortly instead of signing the parent out.
              setRenewal((r) => (r ? { ...r, at: Date.now() + RETRY_SECONDS * 1000 } : r));
            }
          },
        );
      },
      Math.max(0, renewal.at - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [renewal]);

  const value = useMemo(
    () => ({ session, signIn, signInWithTokens, signOut, selectedCampusId, setSelectedCampusId }),
    [session, signIn, signInWithTokens, signOut, selectedCampusId],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
