import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { SafeguardingPage, canRaiseConcern } from './SafeguardingPage';

const teacher: Me = {
  personId: 'p1',
  displayName: 'Ravi',
  tenant: { id: 't', slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey: 'teacher', scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'N' }],
  children: [],
  policyVersion: 'test',
};
const parent: Me = { ...teacher, roles: [] };
const campuses: Campus[] = [
  { id: 'n', code: 'N', name: 'North Campus', timeZone: 'Asia/Kolkata', status: 'ACTIVE', legalEntity: 'X' },
];
const structure = {
  campusId: 'n',
  campusName: 'North',
  years: [],
  year: { id: 'y1', label: '2026-27', startsOn: '2026-04-01', endsOn: '2027-03-31', status: 'ACTIVE' },
  calendar: [],
  programmes: [],
};
const summary = (status: string) => ({
  id: 'r1',
  studentId: 'st1',
  studentName: 'Anaya Rao',
  category: 'PHYSICAL',
  severity: 'HIGH',
  status,
  reportedAt: '2026-11-20T09:00:00Z',
  triageDueAt: '2026-11-20T13:00:00Z',
  triageOverdue: status === 'REPORTED',
});

describe('SafeguardingPage', () => {
  it('is for staff only', async () => {
    expect(canRaiseConcern(teacher)).toBe(true);
    expect(canRaiseConcern(parent)).toBe(false);
    stubFetch(() => ({ body: structure }));
    renderWithApp(<SafeguardingPage me={parent} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('Not available')).toBeInTheDocument();
  });

  it('gives a reporter a receipt and no way to read reports back', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/students?')) {
        return {
          body: { students: [{ id: 'st1', schoolRef: 'A1', displayName: 'Anaya Rao', guardians: 1 }] },
        };
      }
      if (url.endsWith('/safeguarding-reports') && init.method === 'POST') {
        return {
          status: 201,
          body: { id: 'r1', receivedAt: '2026-11-25T09:00:00Z', message: 'Passed to the lead.' },
        };
      }
      if (url.includes('/safeguarding-reports'))
        return { status: 404, body: { code: 'not_found', detail: 'no' } };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <SafeguardingPage me={teacher} campuses={campuses} selectedCampusId="n" />,
    );
    await user.type(await screen.findByLabelText(/Find the child/), 'Anaya');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.click(await screen.findByRole('button', { name: 'Choose Anaya Rao' }));
    await user.type(screen.getByLabelText(/What you saw or heard/), 'Bruises on the left arm');
    await user.click(screen.getByRole('button', { name: 'Send report' }));
    expect(await screen.findByText('Passed to the lead.')).toBeInTheDocument();
    expect(await screen.findByText('Reports are private')).toBeInTheDocument();
    const post = calls.find((c) => c.init.method === 'POST');
    expect(JSON.parse(String(post?.init.body))).toMatchObject({ category: 'EMOTIONAL', severity: 'MEDIUM' });
    expect(await axeViolations(container)).toEqual([]);
  });

  it('lets the lead follow the steps and insists on a note to escalate', async () => {
    let status = 'REPORTED';
    stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/actions') && init.method === 'POST') {
        status = 'TRIAGED';
        return { body: {} };
      }
      if (url.includes('/safeguarding-reports/r1'))
        return {
          body: {
            summary: summary(status),
            description: 'Bruises on the left arm',
            occurredOn: null,
            reportedBy: 'Ravi',
            actions: [],
          },
        };
      if (url.includes('/safeguarding-reports')) return { body: [summary(status)] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    renderWithApp(<SafeguardingPage me={teacher} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('Triage overdue')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: "Open Anaya Rao's report" }));
    expect(await screen.findByText('Bruises on the left arm')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Move to escalated/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Move to triaged' }));
    expect(await screen.findByRole('button', { name: 'Move to under review' })).toBeInTheDocument();
  });
});
