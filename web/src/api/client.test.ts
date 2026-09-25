import { describe, expect, it } from 'vitest';
import { ApiError, apiRequest } from './client';
import { stubFetch } from '../test/render';

const session = { baseUrl: 'http://api.test', token: 'dev:dev-asha' };

describe('apiRequest', () => {
  it('sends auth and a correlation id, and never a tenant header unless selected', async () => {
    const calls = stubFetch(() => ({ body: { ok: true } }));
    await apiRequest(session, '/api/v1/me');
    const headers = calls[0]!.init.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer dev:dev-asha');
    expect(headers['X-Correlation-Id']).toMatch(/^[0-9a-f-]{36}$/);
    expect(headers['X-Schoomano-Tenant']).toBeUndefined();
  });

  it('passes a selected tenant slug as a selector only', async () => {
    const calls = stubFetch(() => ({ body: {} }));
    await apiRequest({ ...session, tenantSlug: 'demo-group' }, '/api/v1/me');
    expect((calls[0]!.init.headers as Record<string, string>)['X-Schoomano-Tenant']).toBe('demo-group');
  });

  it('turns problem details into ApiError with the support reference', async () => {
    stubFetch(() => ({
      status: 403,
      body: { code: 'forbidden', detail: 'You do not have permission.', correlationId: 'corr-12345678' },
    }));
    const err = await apiRequest(session, '/api/v1/config/x/drafts', { method: 'POST', body: {} }).catch(
      (e) => e,
    );
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 403, code: 'forbidden', correlationId: 'corr-12345678' });
  });

  it('reports network failure distinctly', async () => {
    globalThis.fetch = (() => Promise.reject(new TypeError('Failed to fetch'))) as typeof fetch;
    const err = await apiRequest(session, '/api/v1/me').catch((e) => e);
    expect(err).toMatchObject({ status: 0, code: 'network_unreachable' });
  });
});
