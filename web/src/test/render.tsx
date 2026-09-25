import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { SessionProvider } from '../app/SessionProvider';
import { I18nProvider } from '../i18n/I18nProvider';
import axe from 'axe-core';

export function renderWithApp(ui: ReactElement, route = '/') {
  return render(
    <I18nProvider>
      <SessionProvider initial={{ baseUrl: 'http://api.test', token: 'dev:dev-asha' }}>
        <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
      </SessionProvider>
    </I18nProvider>,
  );
}

/** Runs axe against the rendered DOM. Colour contrast is verified on tokens separately (jsdom cannot compute it). */
export async function axeViolations(container: Element) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  return result.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length})`);
}

type Handler = (
  url: string,
  init: RequestInit,
) => { status?: number; body?: unknown; headers?: Record<string, string> };

/** Minimal fetch stub that records requests. Test-only: never used by the running app. */
export function stubFetch(handler: Handler) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fn = async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    calls.push({ url, init });
    const r = handler(url, init);
    return new Response(r.body === undefined ? null : JSON.stringify(r.body), {
      status: r.status ?? 200,
      headers: { 'Content-Type': 'application/json', ...(r.headers ?? {}) },
    });
  };
  globalThis.fetch = fn as typeof fetch;
  return calls;
}
