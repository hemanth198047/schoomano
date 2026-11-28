import { fireEvent, screen } from '@testing-library/react';
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

  it('downloads the audit trail for a period and says when the file was cut short', async () => {
    let truncated = false;
    const calls = stubFetch((url) => {
      if (url.endsWith('/operations/health')) return { body: health };
      if (url.includes('/audit/export')) {
        const headers: Record<string, string> = truncated ? { 'X-Export-Truncated': 'true' } : {};
        return { body: 'seq,occurred_at', headers };
      }
      if (url.includes('/audit')) return { body: rows };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const made: Blob[] = [];
    URL.createObjectURL = (b: Blob | MediaSource) => {
      made.push(b as Blob);
      return 'blob:x';
    };
    URL.revokeObjectURL = () => undefined;
    const clicked: string[] = [];
    const realClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function (this: HTMLAnchorElement) {
      clicked.push(this.download);
    };
    try {
      renderWithApp(<SupportPage me={support} />);
      fireEvent.change(await screen.findByLabelText(/From date/), { target: { value: '2026-11-01' } });
      fireEvent.change(screen.getByLabelText(/To date/), { target: { value: '2026-11-30' } });
      await userEvent.click(screen.getByRole('button', { name: 'Download as a spreadsheet (CSV)' }));
      expect(await screen.findByText(/has been downloaded/)).toBeInTheDocument();
      expect(calls.some((c) => c.url.endsWith('/audit/export?from=2026-11-01&to=2026-11-30'))).toBe(true);
      expect(clicked).toEqual(['audit-2026-11-01-to-2026-11-30.csv']);
      expect(made).toHaveLength(1);

      truncated = true;
      await userEvent.click(screen.getByRole('button', { name: 'Download as a spreadsheet (CSV)' }));
      expect(await screen.findByText(/first 50,000 entries/)).toBeInTheDocument();
    } finally {
      HTMLAnchorElement.prototype.click = realClick;
    }
  });

  it('lists emergency access and records a review with a note', async () => {
    const base = {
      personId: 'p1',
      resourceType: 'health_record' as const,
      resourceId: 'r1',
      grantedAt: '2026-11-27T05:00:00Z',
      expiresAt: '2026-11-27T05:30:00Z',
      state: 'EXPIRED' as const,
      opened: 2,
      reviewedBy: null,
      reviewedAt: null,
      outcome: null,
      note: null,
    };
    const list = [
      { ...base, id: 'b1', person: 'Ravi Teacher', justification: 'Child collapsed at dispersal' },
      {
        ...base,
        id: 'b2',
        person: 'Farah Teacher',
        justification: 'Allergy check on a trip',
        opened: 1,
        reviewedBy: 'Asha Admin',
        reviewedAt: '2026-11-28T05:00:00Z',
        outcome: 'INAPPROPRIATE' as const,
        note: 'No incident that day',
        state: 'ENDED' as const,
      },
    ];
    const calls = stubFetch((url) => {
      if (url.endsWith('/operations/health')) return { body: health };
      if (url.endsWith('/support/grants')) return { body: [] };
      if (url.endsWith('/break-glass/b1/review')) return { body: { ...list[0], outcome: 'APPROPRIATE' } };
      if (url.endsWith('/break-glass')) return { body: list };
      if (url.includes('/audit')) return { body: rows };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(<SupportPage me={admin} />);
    expect(await screen.findByText('Reason given: Child collapsed at dispersal')).toBeInTheDocument();
    expect(screen.getByText(/Opened 2 times/)).toBeInTheDocument();
    expect(
      screen.getByText(/Not appropriate \(reviewed by Asha Admin\): No incident that day/),
    ).toBeInTheDocument();
    const record = screen.getByRole('button', { name: 'Record review of Ravi Teacher' });
    expect(record).toBeDisabled();
    expect(screen.queryByRole('button', { name: /Record review of Farah/ })).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/^Note on Ravi Teacher/), 'Matches the incident report');
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(record);
    const post = calls.find((c) => c.url.endsWith('/break-glass/b1/review'))!;
    expect(JSON.parse(String(post.init.body))).toEqual({
      outcome: 'APPROPRIATE',
      note: 'Matches the incident report',
    });
  });

  it('keeps emergency access reasons from platform support', async () => {
    const calls = stubFetch((url) => {
      if (url.endsWith('/operations/health')) return { body: health };
      if (url.includes('/audit')) return { body: rows };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    renderWithApp(<SupportPage me={support} />);
    await screen.findByText('Text messages');
    expect(screen.queryByRole('heading', { name: 'Emergency access' })).not.toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/break-glass'))).toBe(false);
  });
});
