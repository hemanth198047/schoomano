import * as Crypto from 'expo-crypto';
import * as Linking from 'expo-linking';
import * as WebBrowser from 'expo-web-browser';
import { OIDC_CLIENT_ID, OIDC_ISSUER } from '../config';
import { ApiError } from '../core/api';
import {
  buildAuthRequest,
  createOidcRefresher,
  discover,
  exchangeCode,
  readRedirect,
  type OidcConfig,
  type Platform,
} from '../core/oidc';
import { deviceStore } from './stores';

export const staffSignInAvailable = OIDC_ISSUER !== '' && OIDC_CLIENT_ID !== '';

const SESSION_KIND = 'session-kind';

export const platform: Platform = {
  fetchImpl: (input, init) => fetch(input, init),
  randomBytes: (n) => Crypto.getRandomBytes(n),
  sha256: async (text) => {
    const hex = await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, text, {
      encoding: Crypto.CryptoEncoding.HEX,
    });
    return Uint8Array.from(hex.match(/../g) ?? [], (h) => parseInt(h, 16));
  },
};

const config = (): OidcConfig => ({
  issuer: OIDC_ISSUER,
  clientId: OIDC_CLIENT_ID,
  redirectUri: Linking.createURL('signin'),
});

/** Lets the client refresh staff sessions through the provider and leave parents' sessions alone. */
export const staffRefresh = createOidcRefresher(
  { issuer: OIDC_ISSUER, clientId: OIDC_CLIENT_ID, redirectUri: 'schoomano://signin' },
  platform,
  async () => (await deviceStore.get(SESSION_KIND)) === 'oidc',
);

export const markParentSession = () => deviceStore.set(SESSION_KIND, 'otp');

/** Opens the school's sign-in in the system browser (never an embedded view) and returns the tokens it issues. */
export async function signInStaff(): Promise<{ accessToken: string; refreshToken: string }> {
  const cfg = config();
  const d = await discover(cfg, platform);
  const req = await buildAuthRequest(cfg, d, platform);
  const result = await WebBrowser.openAuthSessionAsync(req.url, cfg.redirectUri);
  if (result.type !== 'success') throw new ApiError(400, 'oidc_denied', 'Sign-in was cancelled.');
  const code = readRedirect(result.url, req.state);
  const t = await exchangeCode(cfg, d, code, req.verifier, platform, Date.now());
  await deviceStore.set(SESSION_KIND, 'oidc');
  return { accessToken: t.accessToken, refreshToken: t.refreshToken ?? '' };
}
