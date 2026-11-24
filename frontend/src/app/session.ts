import { createContext, useContext } from 'react';
import type { ApiSession } from '../api/client';
import type { TokenResponse } from '../api/auth';

export interface SessionState {
  session: ApiSession | null;
  signIn: (token: string, tenantSlug?: string) => void;
  /** Parent session from the phone code: renews itself before the access token runs out. */
  signInWithTokens: (tokens: TokenResponse) => void;
  signOut: () => void;
  selectedCampusId: string | null;
  setSelectedCampusId: (id: string | null) => void;
}

export const SessionContext = createContext<SessionState | null>(null);

export function useSession(): SessionState {
  const s = useContext(SessionContext);
  if (!s) throw new Error('useSession outside SessionProvider');
  return s;
}

export function useApiSession(): ApiSession {
  const { session } = useSession();
  if (!session) throw new Error('no active session');
  return session;
}
