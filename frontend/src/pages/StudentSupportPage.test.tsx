import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { StudentSupportPage, canSeeStudentSupport } from './StudentSupportPage';

const admin: Me = {
  personId: 'p1',
  displayName: 'Asha',
  tenant: { id: 't', slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey: 'group_admin', scopeLevel: 'GROUP', scopeId: 't', scopeName: 'Nilgiri' }],
  children: [],
  policyVersion: 'test',
};
const teacher: Me = {
  ...admin,
  roles: [{ roleKey: 'teacher', scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'N' }],
};
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
  id: 'c1',
  studentId: 'st1',
  studentName: 'Anaya Rao',
  category: 'WELLBEING',
  title: 'Settling in',
  status,
  reviewOn: '2026-11-01',
  reviewDue: status !== 'CLOSED',
  openedOn: '2026-10-01',
});

describe('StudentSupportPage', () => {
  it('limits the screen to the principal and administrators', async () => {
    expect(canSeeStudentSupport(admin)).toBe(true);
    expect(canSeeStudentSupport(teacher)).toBe(false);
    stubFetch(() => ({ body: structure }));
    renderWithApp(<StudentSupportPage me={teacher} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('Not available')).toBeInTheDocument();
    expect(screen.queryByText('Open a case')).not.toBeInTheDocument();
  });

  it('opens a case, adds a note, and will not close without a reason', async () => {
    let status = 'OPEN';
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/notes') && init.method === 'POST') return { body: {} };
      if (url.endsWith('/status') && init.method === 'POST') {
        status = 'CLOSED';
        return { body: summary(status) };
      }
      if (url.includes('/support-cases/c1'))
        return {
          body: {
            summary: summary(status),
            closedReason: status === 'CLOSED' ? 'Settled well' : null,
            notes: [
              {
                id: 'n1',
                body: 'Parent asked for a check-in',
                author: 'Asha Admin',
                at: '2026-10-01T09:00:00Z',
              },
            ],
          },
        };
      if (url.includes('/support-cases')) return { body: [summary(status)] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(
      <StudentSupportPage me={admin} campuses={campuses} selectedCampusId="n" />,
    );
    await userEvent.click(await screen.findByRole('button', { name: "Open Anaya Rao's case" }));
    expect(await screen.findByText('Parent asked for a check-in')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.type(screen.getByLabelText(/Add a note/), 'Called home');
    await userEvent.click(screen.getByRole('button', { name: 'Add note' }));
    expect(calls.some((c) => c.url.endsWith('/notes') && c.init.method === 'POST')).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: 'Close case' }));
    const confirm = screen.getByRole('button', { name: 'Confirm closing' });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Why is it being closed/), 'Settled well');
    await userEvent.click(confirm);
    expect(await screen.findByText('Closed: Settled well')).toBeInTheDocument();
    const post = calls.find((c) => c.url.endsWith('/status'))!;
    expect(JSON.parse(String(post.init.body))).toEqual({ status: 'CLOSED', reason: 'Settled well' });
  });
});
