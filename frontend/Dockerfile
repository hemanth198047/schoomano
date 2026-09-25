# syntax=docker/dockerfile:1
# Schoomano frontend: static React build served by unprivileged nginx, which also proxies /api to the backend.

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
# LOCAL STACKS ONLY: "true" shows the synthetic-user sign-in picker. Never true for shared environments.
ARG VITE_DEV_AUTH=false
ENV VITE_DEV_AUTH=${VITE_DEV_AUTH}
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
# The official image renders /etc/nginx/templates/*.template with envsubst at startup.
ENV BACKEND_UPSTREAM=backend:8080
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
