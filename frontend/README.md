# Schoomano frontend

A single React 19 + TypeScript + Vite project. Talks to the backend only through `/api` on its own origin: Vite proxies it in development, nginx in the container. No API URL is baked into the build.

```
src/              app, components, pages, API client, i18n, styles, tests
src/styles/tokens.json   design tokens → tokens.generated.css (npm run tokens)
scripts/          build-tokens.mjs
nginx/            nginx template: static files, /api proxy, SPA fallback, CSP and security headers
Dockerfile        Node build stage → unprivileged nginx image (port 8080, /healthz)
```

## Develop

```sh
npm ci
npm run dev          # http://localhost:5173, proxies /api to http://localhost:8080
```

Point the proxy elsewhere with `SCHOOMANO_BACKEND_URL=http://host:port npm run dev`. Synthetic sign-in is on in development (`.env.development`); the backend must run with `SCHOOMANO_AUTH_DEV_MODE=true`.

## Check (same as CI)

```sh
npm run check        # token drift + CSS token references, typecheck, lint, format, tests (incl. axe), build
```

## Container

```sh
docker build -t schoomano-frontend:local .                              # production: dev sign-in off
docker build --build-arg VITE_DEV_AUTH=true -t schoomano-frontend:local .   # local stacks only
```

`BACKEND_UPSTREAM` (default `backend:8080`) sets where nginx proxies `/api`.

Design system: `../docs/design/DESIGN_SYSTEM.md`; live gallery at `/design-system` in the app.
