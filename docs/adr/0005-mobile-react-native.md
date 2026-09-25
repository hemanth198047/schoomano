# ADR 0005: React Native (Expo) for mobile, one role-adaptive app to start

Status: Proposed, confirm after the Stage B usability prototype (spec §6 asks to choose after testing)

## Options
- **React Native + Expo**: shares TypeScript, API types, validation and design tokens with the web; mature offline storage (SQLite) and encrypted storage; OTA updates for fast fixes during a pilot.
- **Flutter**: excellent rendering on low-end Android and a strong single codebase, but a second language (Dart) and no code sharing with the React web.

## Decision (proposed)
React Native with Expo, one role-adaptive app (guardian, teacher, transport, coach views selected by server-side roles). Reasons: one language across web and mobile for a small team, shared tokens (`packages/design-tokens` already emits a TS object for this), and shared offline-queue logic for attendance and boarding.

## Validation before commitment
A two-week prototype of teacher offline attendance on a low-end Android device (2 GB RAM class) with a cold-start budget of 3 s and a 60-student roster; plus a guardian child-switcher flow tested with 5 parents. If the device budget fails, revisit Flutter for field-staff apps only.
