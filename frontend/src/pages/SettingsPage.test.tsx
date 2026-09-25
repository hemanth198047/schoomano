import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { SettingsPage } from './SettingsPage';

const TENANT = '10000000-0000-4000-8000-000000000001';
const admin: Me = {
  personId: 'p1',
  displayName: 'Asha Admin',
  tenant: { id: TENANT, slug: 'demo-group', name: 'Nilgiri Learning Trust (synthetic)' },
  roles: [{ roleKey: 'group_admin', scopeLevel: 'GROUP', scopeId: TENANT, scopeName: 'Nilgiri' }],
  children: [],
  policyVersion: 'test',
};
const teacher: Me = {
  ...admin,
  roles: [{ roleKey: 'teacher', scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'North' }],
};

const history = [
  {
    id: 'v1',
    scopeLevel: 'GROUP',
    scopeId: TENANT,
    version: 1,
    value: 10,
    effectiveFrom: '2026-03-31T18:30:00Z',
    status: 'PUBLISHED',
    changeNote: 'Group default',
  },
];
const campuses = [
  {
    id: 'n',
    code: 'BLR-N',
    name: 'North Campus',
    timeZone: 'Asia/Kolkata',
    status: 'ACTIVE',
    legalEntity: 'X',
  },
  {
    id: 's',
    code: 'BLR-S',
    name: 'South Campus',
    timeZone: 'Asia/Kolkata',
    status: 'ACTIVE',
    legalEntity: 'X',
  },
];

let calls: ReturnType<typeof stubFetch>;
beforeEach(() => {
  calls = stubFetch((url, init) => {
    if (url.endsWith('/versions')) return { body: history };
    if (url.endsWith('/api/v1/campuses')) return { body: campuses };
    if (url.endsWith('/drafts') && init.method === 'POST')
      return {
        status: 201,
        body: {
          ...history[0],
          id: 'd2',
          version: 2,
          value: 5,
          status: 'DRAFT',
          effectiveFrom: '2030-01-01T02:30:00Z',
        },
      };
    if (url.endsWith('/preview'))
      return {
        body: [
          { campusId: 'n', campusName: 'North Campus', before: 10, after: 5, changed: true },
          { campusId: 's', campusName: 'South Campus', before: 15, after: 15, changed: false },
        ],
      };
    if (url.endsWith('/publish'))
      return { body: { ...history[0], id: 'd2', version: 2, status: 'PUBLISHED' } };
    return { status: 404, body: { code: 'not_found', detail: 'no route' } };
  });
});

describe('SettingsPage', () => {
  it('shows version history with plain-language values', async () => {
    renderWithApp(<SettingsPage me={admin} />);
    expect(await screen.findByRole('table', { name: /version history/ })).toHaveTextContent('10 minutes');
    expect(screen.getByRole('table', { name: /version history/ })).toHaveTextContent('Whole group');
  });

  it('validates before calling the server', async () => {
    renderWithApp(<SettingsPage me={admin} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Preview change' }));
    expect(screen.getByLabelText(/Minutes/)).toHaveAccessibleDescription(
      'Enter whole minutes from 0 to 120.',
    );
    expect(calls.some((c) => c.init.method === 'POST')).toBe(false);
  });

  it('drafts, previews per-campus impact, then publishes', async () => {
    renderWithApp(<SettingsPage me={admin} />);
    await userEvent.type(await screen.findByLabelText(/Minutes/), '5');
    await userEvent.type(screen.getByLabelText(/Takes effect from/), '2030-01-01T08:00');
    await userEvent.type(screen.getByLabelText(/Reason for the change/), 'Stricter from term 3');
    await userEvent.click(screen.getByRole('button', { name: 'Preview change' }));

    const preview = await screen.findByRole('table', { name: 'Effect by campus' });
    expect(preview).toHaveTextContent('North Campus10 minutes5 minutesChanges');
    expect(preview).toHaveTextContent('South Campus15 minutes15 minutesNo change');

    const draftCall = calls.find((c) => c.url.endsWith('/drafts'))!;
    expect(JSON.parse(String(draftCall.init.body))).toEqual({
      scopeLevel: 'GROUP',
      scopeId: TENANT,
      value: 5,
      effectiveFrom: '2030-01-01T08:00:00+05:30',
      changeNote: 'Stricter from term 3',
    });

    await userEvent.click(screen.getByRole('button', { name: 'Publish version 2' }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Published version 2'));
  });

  it('does not offer editing to roles without publish rights', async () => {
    renderWithApp(<SettingsPage me={teacher} />);
    expect(
      await screen.findByText('Only a group administrator can change this setting.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Preview change' })).toBeNull();
  });

  it('has no automatically detectable accessibility violations', async () => {
    const { container } = renderWithApp(<SettingsPage me={admin} />);
    await screen.findByRole('table', { name: /version history/ });
    expect(await axeViolations(container)).toEqual([]);
  });
});
