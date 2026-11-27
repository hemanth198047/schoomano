import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { StaffLeavePage, canDecideLeave, canRequestLeave, isHr } from './StaffLeavePage';

const person = (roleKey: string, displayName: string): Me => ({
  personId: 'p1',
  displayName,
  tenant: { id: 't', slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey, scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'N' }],
  children: [],
  policyVersion: 'test',
});
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
const balance = [{ leaveType: 'CASUAL', allowance: 4, approved: 3, pending: 0, remaining: 1 }];
const leave = (status: string) => ({
  id: 'l1',
  personId: 'p9',
  name: 'Tina Teacher',
  leaveType: 'CASUAL',
  fromDate: '2026-12-07',
  toDate: '2026-12-09',
  days: 3,
  reason: 'Family function',
  status,
  decidedBy: null,
  decisionNote: null,
  requestedAt: '2026-11-20T09:00:00Z',
});

describe('StaffLeavePage', () => {
  it('knows who does what', () => {
    expect(canRequestLeave(person('teacher', 'T'))).toBe(true);
    expect(canRequestLeave(person('group_admin', 'G'))).toBe(false);
    expect(isHr(person('campus_admin', 'C'))).toBe(true);
    expect(isHr(person('principal', 'P'))).toBe(false);
    expect(canDecideLeave(person('principal', 'P'))).toBe(true);
  });

  it('shows a teacher the balance and lets them request and withdraw leave', async () => {
    let status = 'PENDING';
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/leave-balance')) return { body: balance };
      if (url.endsWith('/decision') && init.method === 'POST') {
        status = 'CANCELLED';
        return { body: leave(status) };
      }
      if (url.endsWith('/leave-requests') && init.method === 'POST')
        return { status: 201, body: leave('PENDING') };
      if (url.endsWith('/leave-requests')) return { body: [leave(status)] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <StaffLeavePage me={person('teacher', 'Tina Teacher')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText(/1 days left of 4/)).toBeInTheDocument();
    expect(screen.queryByText('Staff')).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await user.type(screen.getByLabelText('First day'), '2026-12-07');
    await user.click(screen.getByRole('button', { name: 'Send leave request' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toMatchObject({
      leaveType: 'CASUAL',
      fromDate: '2026-12-07',
      toDate: '2026-12-07',
    });
    await user.click(await screen.findByRole('button', { name: /^Withdraw/ }));
    expect(await screen.findByText('Cancelled')).toBeInTheDocument();
  });

  it('lets HR decide others’ leave and set allowances', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/leave-balance')) return { body: [] };
      if (url.endsWith('/decision') && init.method === 'POST') return { body: leave('APPROVED') };
      if (url.endsWith('/leave-allowances') && init.method === 'PUT') return { body: [] };
      if (url.endsWith('/leave-requests')) return { body: [leave('PENDING')] };
      if (url.endsWith('/staff'))
        return {
          body: [
            {
              personId: 'p9',
              name: 'Tina Teacher',
              employeeNo: 'E-9',
              designation: 'Teacher',
              department: null,
              employmentType: 'FULL_TIME',
              joinedOn: '2020-04-01',
              status: 'ACTIVE',
              leftOn: null,
            },
          ],
        };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    renderWithApp(
      <StaffLeavePage me={person('campus_admin', 'Chitra')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('E-9, Teacher')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Approve Tina Teacher/ }));
    expect(JSON.parse(String(calls.find((c) => c.url.endsWith('/decision'))?.init.body))).toMatchObject({
      decision: 'APPROVE',
    });
    await user.type(screen.getByLabelText('Days per year'), '12');
    await user.click(screen.getByRole('button', { name: 'Save allowance' }));
    expect(await screen.findByText('Saved.')).toBeInTheDocument();
  });
});
