/** Set at build time with EXPO_PUBLIC_* variables. The emulator reaches the host machine at 10.0.2.2. */
export const API_BASE_URL = process.env.EXPO_PUBLIC_API_URL ?? 'http://10.0.2.2:8080';
/** Staff sign-in through the school's identity provider is not built yet; development builds may paste a dev token. */
export const ALLOW_DEV_TOKEN = process.env.EXPO_PUBLIC_ALLOW_DEV_TOKEN === 'true';
