import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import type { TokenStore } from '../core/api';
import type { KeyValueStore } from '../core/storage';

const TOKENS = 'schoomano.session';

/** Tokens live in the operating system's secure store (Keystore on Android), never in plain storage. */
export const secureTokenStore: TokenStore = {
  async load() {
    const raw = await SecureStore.getItemAsync(TOKENS);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as { accessToken: string; refreshToken: string };
    } catch {
      return null;
    }
  },
  save: (t) => SecureStore.setItemAsync(TOKENS, JSON.stringify(t)),
  clear: () => SecureStore.deleteItemAsync(TOKENS),
};

/** Queued attendance and class lists. Not yet encrypted (tracked in the status document). */
export const deviceStore: KeyValueStore = {
  get: (k) => AsyncStorage.getItem(`schoomano.${k}`),
  set: (k, v) => AsyncStorage.setItem(`schoomano.${k}`, v),
  remove: (k) => AsyncStorage.removeItem(`schoomano.${k}`),
};
