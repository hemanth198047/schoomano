import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { SupportPage, canManageSupport, canSeeSupportPage } from './SupportPage';

const admin: Me = {
  personId: 'p1',
  displayName: 'Asha',
  tenant: { id: 't', slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey: 'group_admin', scopeLevel: 'GROUP', scopeId: 't', scopeName: 'Nilgiri' }],
  children: [],
  policyVersion: 'test',
};
const principal: Me = { ...admin, roles: [{ ...admin.roles[0]!, roleKey: 'principal' }] };
const support: Me = { ...admin, displayName: 'Platform support', roles: [] };
const teacher: Me = { ...admin, roles: [{ ...admin.roles[0]!, roleKey: 'teacher' }] };

const health = {
  at: '2026-11-24T10:00:00Z',
  supportAccessLive: 1,
  checks: [
    {
      key: 'sms',
      label: 'Text messages',
      level: 'ATTENTION',
      detail: '2 failed in the last 7 days, 0 due to go out.',
    },
    { key: 'audit', label: 'Audit trail, last 24 hours', level: 'OK', detail: '10 events, 1 refused.' },
  ],
};
const rows = [
  {
    seq: 9,
    occurredAt: '2026-11-24T09:00:00Z',
    actorKind: 'SUPPORT',
    actorName: 'Support: dev:helper',
    action: 'access.audit_read',
    resourceType: 'audit',
    resourceId: null,
    outcome: 'ALLOW',
    reason: null,
    correlationId: null,
  },
];

describe('SupportPage', () => {
  it('shows the page to administrators, principals and support, and approvals only to administrators', () => {
    expect(canSeeSupportPage(admin)).toBe(true);
    expect(canSeeSupportPage(principal)).toBe(true);
    expect(canSeeSupportPage(support)).toBe(true);
    expect(canSeeSupportPage(teacher)).toBe(false);
    expect(canManageSupport(admin)).toBe(true);
    expect(canManageSupport(principal)).toBe(false);
  });

  it('lets an administrator approve and end support access, and shows what support did', async () => {
    let grants = [] as unknown[];
    const calls = stubFetch((url, init) => {
      if (url.endsWith('/operations/health')) return { body: health };
      if (url.endsWith('/support/grants') && init.method === 'POST') {
        grants = [
          {
            id: 'g1',
            supportUserRef: 'dev:helper',
            purpose: 'Look into the stuck import',
            approvedBy: 'Asha',
            startsAt: '2026-11-24T09:00:00Z',
            expiresAt: '2026-11-24T11:00:00Z',
            revokedAt: null,
            state: 'ACTIVE',
          },
        ];
        return { status: 201, body: grants[0] };
      }
      if (url.endsWith('/support/grants')) return { body: grants };
      if (url.endsWith('/revoke')) {
        grants = [];
        return { body: {} };
      }
      if (url.includes('/audit')) return { body: rows };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(<SupportPage me={admin} />);
    expect(await screen.findByText('Text messages')).toBeInTheDocument();
    expect(screen.getByText('Needs attention')).toBeInTheDocument();
    expect(await screen.findByText('Support: dev:helper')).toBeInTheDocument();
    const approve = screen.getByRole('button', { name: 'Approve access' });
    expect(approve).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/^Support person/), 'dev:helper');
    await userEvent.type(screen.getByLabelText(/^Why/), 'Look into the stuck import');
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(approve);
    expect(await screen.findByText('Access approved for 2 hours.')).toBeInTheDocument();
    const post = calls.find((c) => c.init.method === 'POST')!;
    expect(JSON.parse(String(post.init.body))).toEqual({
      supportUserRef: 'dev:helper',
      purpose: 'Look into the stuck import',
      hours: 2,
    });
    await userEvent.click(await screen.findByRole('button', { name: 'End access for dev:helper' }));
    expect(await screen.findByText('Access for dev:helper has ended.')).toBeInTheDocument();
  });

  it('reports plainly whether the audit trail is intact', async () => {
    stubFetch((url) => {
      if (url.endsWith('/operations/health')) return { body: health };
      if (url.endsWith('/audit/verify'))
        return { body: { checked: 42, intact: true, brokenSeq: null, problem: null } };
      if (url.includes('/audit')) return { body: rows };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    renderWithApp(<SupportPage me={support} />);
    expect(screen.queryByRole('heading', { name: 'Platform support access' })).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: /Check the trail/ }));
    expect(
      await screen.findByText(/Checked 42 entries. Nothing has been changed or removed./),
    ).toBeInTheDocument();
  });
});
