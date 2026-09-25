import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { SessionContext } from './session';
import type { ApiSession } from '../api/client';

// Same-origin by default (proxied). Set VITE_API_BASE_URL only to call an API on another origin.
const baseUrl = import.meta.env.VITE_API_BASE_URL ?? '';

/**
 * Holds the access token in memory only (never localStorage), so a stolen browser profile does not
 * carry a live session. Refresh = sign in again, which is acceptable for the admin web in Stage A.
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

  const signIn = useCallback((token: string, tenantSlug?: string) => {
    setSession({ baseUrl, token, tenantSlug });
  }, []);
  const signOut = useCallback(() => {
    setSession(null);
    setSelectedCampusId(null);
  }, []);

  const value = useMemo(
    () => ({ session, signIn, signOut, selectedCampusId, setSelectedCampusId }),
    [session, signIn, signOut, selectedCampusId],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
