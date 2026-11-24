# Schoomano mobile (Expo, React Native)

One app for parents and teachers. Which screens appear is decided by what the server says the signed-in person is
(`GET /api/v1/me`): a teacher role gets attendance, a guardian gets their children, notices and fees.

## What is here

- `src/core/`: plain TypeScript, no React Native imports, unit tested (`npm test`):
  - `api.ts` API client. A 401 triggers one token refresh shared by every call that failed together (refresh tokens are single use).
  - `offlineQueue.ts` attendance batches written to the phone before sending, sent oldest first with the same idempotency key on every retry.
  - `roster.ts`, `cached.ts` the last class list, person and class structure, so a teacher can start the app and mark with no network.
- `src/platform/stores.ts` the phone's secure store for tokens and AsyncStorage for the queue.
- `src/screens/` sign-in with a phone code, parent home (child switcher, attendance, notices, fees with sandbox pay), teacher attendance.

## Run

```
npm install
EXPO_PUBLIC_API_URL=http://10.0.2.2:8080 EXPO_PUBLIC_ALLOW_DEV_TOKEN=true npx expo start --android
```

`npm run check` runs typecheck, format check and unit tests (this is what CI runs). The screens have not been run on a device yet.
