import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { IntegrationsPage, canManageKeys } from './IntegrationsPage';

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
const server = (url: string, init: { method?: string }) => {
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

  it('shows teachers nothing to manage', () => {
    stubFetch(server);
    renderWithApp(<IntegrationsPage me={person('teacher')} campuses={campuses} selectedCampusId="n" />);
    expect(screen.getByText('Not available')).toBeInTheDocument();
    expect(screen.queryByText('Create a key')).not.toBeInTheDocument();
  });
});
