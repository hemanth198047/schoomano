import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { IntegrationsPage, canManageKeys, canManageWebhooks } from './IntegrationsPage';

const person = (roleKey: string): Me => ({
  personId: 'p1',
  displayName: 'Someone',
  tenant: { id: 't', slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey, scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'N' }],
  children: [],
  policyVersion: 'test',
});
const campuses: Campus[] = [
  { id: 'n', code: 'N', name: 'North Campus', timeZone: 'Asia/Kolkata', status: 'ACTIVE', legalEntity: 'X' },
];
const key = {
  id: 'k1',
  name: 'Library sync',
  hint: 'AbCd',
  createdAt: '2026-11-01T04:30:00Z',
  expiresAt: '2027-11-01T04:30:00Z',
  lastUsedAt: null,
  revokedAt: null,
  status: 'ACTIVE',
  createdBy: 'Asha Admin',
};
const hook = {
  id: 'w1',
  name: 'Library system',
  url: 'https://hooks.example.com/x',
  eventTypes: ['config.published'],
  active: true,
  failures: 0,
  createdAt: '2026-11-01T04:30:00Z',
  disabledAt: null,
  disabledReason: null,
};
const server = (url: string, init: { method?: string }) => {
  if (url.endsWith('/webhooks/event-types')) return { body: ['config.published', 'import.committed'] };
  if (url.endsWith('/webhooks/w1/ping')) return { status: 202, body: { deliveryId: 'x' } };
  if (url.endsWith('/webhooks/w1/disable')) return { body: { ...hook, active: false } };
  if (url.endsWith('/webhooks/w1/deliveries'))
    return {
      body: [
        {
          id: 'd1',
          eventType: 'config.published',
          status: 'SENT',
          attempts: 1,
          lastStatus: 204,
          lastError: null,
          createdAt: '2026-11-02T04:30:00Z',
          sentAt: '2026-11-02T04:30:05Z',
        },
      ],
    };
  if (url.endsWith('/webhooks') && init.method === 'POST')
    return {
      status: 201,
      body: { endpoint: { ...hook, id: 'w2', name: 'Canteen' }, secret: 'whsec_secretvalue' },
    };
  if (url.endsWith('/webhooks')) return { body: [hook] };
  if (url.endsWith('/revoke')) return { body: { ...key, status: 'REVOKED' } };
  if (url.endsWith('/api-keys') && init.method === 'POST')
    return { status: 201, body: { key: { ...key, id: 'k2', name: 'Canteen' }, secret: 'smk_secretvalue' } };
  if (url.endsWith('/api-keys')) return { body: [key] };
  return { status: 404, body: { code: 'not_found', detail: 'no' } };
};

describe('IntegrationsPage', () => {
  it('is for administrators only', () => {
    expect(canManageKeys(person('campus_admin'))).toBe(true);
    expect(canManageKeys(person('teacher'))).toBe(false);
  });

  it('creates a key, shows the secret once and revokes a key', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <IntegrationsPage me={person('campus_admin')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('Library sync')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await user.type(screen.getByLabelText('Name'), 'Canteen');
    await user.clear(screen.getByLabelText('Valid for (days)'));
    await user.type(screen.getByLabelText('Valid for (days)'), '90');
    await user.click(screen.getByRole('button', { name: 'Create key' }));
    expect(await screen.findByText('smk_secretvalue')).toBeInTheDocument();
    expect(
      JSON.parse(
        String(calls.find((c) => c.init.method === 'POST' && c.url.endsWith('/api-keys'))?.init.body),
      ),
    ).toEqual({
      name: 'Canteen',
      validDays: 90,
    });
    await user.click(screen.getByRole('button', { name: 'I have copied it' }));
    expect(screen.queryByText('smk_secretvalue')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Revoke Library sync' }));
    expect(calls.some((c) => c.url.endsWith('/api-keys/k1/revoke'))).toBe(true);
  });

  it('lets only the group administrator manage webhooks', async () => {
    expect(canManageWebhooks(person('group_admin'))).toBe(true);
    expect(canManageWebhooks(person('campus_admin'))).toBe(false);
    stubFetch(server);
    renderWithApp(<IntegrationsPage me={person('campus_admin')} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('Library sync')).toBeInTheDocument();
    expect(screen.queryByText('Add a webhook')).not.toBeInTheDocument();
  });

  it('adds a webhook, shows its secret once, tests it and switches it off', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <IntegrationsPage me={person('group_admin')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('Library system')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await user.type(screen.getByLabelText('Webhook name'), 'Canteen');
    await user.type(screen.getByLabelText('Address'), 'https://canteen.example.com/in');
    await user.click(await screen.findByLabelText('An import was applied'));
    await user.click(screen.getByRole('button', { name: 'Add webhook' }));
    expect(await screen.findByText('whsec_secretvalue')).toBeInTheDocument();
    expect(
      JSON.parse(
        String(calls.find((c) => c.init.method === 'POST' && c.url.endsWith('/webhooks'))?.init.body),
      ),
    ).toEqual({ name: 'Canteen', url: 'https://canteen.example.com/in', eventTypes: ['import.committed'] });
    await user.click(screen.getByRole('button', { name: 'I have copied the secret' }));
    expect(screen.queryByText('whsec_secretvalue')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Send test to Library system' }));
    expect(calls.some((c) => c.url.endsWith('/webhooks/w1/ping'))).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Deliveries of Library system' }));
    expect(await screen.findByText('SENT')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Switch off Library system' }));
    expect(calls.some((c) => c.url.endsWith('/webhooks/w1/disable'))).toBe(true);
  });

  it('shows teachers nothing to manage', () => {
    stubFetch(server);
    renderWithApp(<IntegrationsPage me={person('teacher')} campuses={campuses} selectedCampusId="n" />);
    expect(screen.getByText('Not available')).toBeInTheDocument();
    expect(screen.queryByText('Create a key')).not.toBeInTheDocument();
  });
});
