import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import type { TokenStore } from '../core/api';
import type { KeyValueStore } from '../core/storage';

const ACCESS = 'schoomano.access';
const REFRESH = 'schoomano.refresh';

/**
 * Tokens live in the operating system's secure store (Keystore on Android), never in plain storage. The two tokens
 * are kept under separate keys because a provider's tokens together can exceed the 2 KB a single entry may hold.
 */
export const secureTokenStore: TokenStore = {
  async load() {
    const [accessToken, refreshToken] = await Promise.all([
      SecureStore.getItemAsync(ACCESS),
      SecureStore.getItemAsync(REFRESH),
    ]);
    return accessToken ? { accessToken, refreshToken: refreshToken ?? '' } : null;
  },
  async save(t) {
    await SecureStore.setItemAsync(ACCESS, t.accessToken);
    await SecureStore.setItemAsync(REFRESH, t.refreshToken);
  },
  async clear() {
    await SecureStore.deleteItemAsync(ACCESS);
    await SecureStore.deleteItemAsync(REFRESH);
  },
};

/** Queued attendance and class lists. Not yet encrypted (tracked in the status document). */
export const deviceStore: KeyValueStore = {
  get: (k) => AsyncStorage.getItem(`schoomano.${k}`),
  set: (k, v) => AsyncStorage.setItem(`schoomano.${k}`, v),
  remove: (k) => AsyncStorage.removeItem(`schoomano.${k}`),
};
