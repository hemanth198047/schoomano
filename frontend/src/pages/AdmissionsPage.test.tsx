import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { AdmissionsPage, canManageAdmissions } from './AdmissionsPage';

const admin: Me = {
  personId: 'p1',
  displayName: 'Asha',
  tenant: { id: 't', slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey: 'group_admin', scopeLevel: 'GROUP', scopeId: 't', scopeName: 'Nilgiri' }],
  children: [],
  policyVersion: 'test',
};
const principal: Me = {
  ...admin,
  roles: [{ roleKey: 'principal', scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'N' }],
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
  programmes: [
    {
      id: 'pr',
      code: 'CBSE',
      name: 'CBSE',
      board: 'CBSE',
      boardDetail: null,
      terms: [],
      subjects: [],
      grades: [
        {
          id: 'g5',
          code: 'G5',
          name: 'Grade 5',
          ordinal: 5,
          sections: [{ id: 's5a', code: 'A', capacity: 40, students: 3 }],
        },
      ],
    },
  ],
};
const app = (status: string) => ({
  id: 'a1',
  yearId: 'y1',
  gradeLevelId: 'g5',
  gradeName: 'Grade 5',
  programmeCode: 'CBSE',
  childName: 'Aarav Applicant',
  dateOfBirth: null,
  guardianName: 'Parent Aarav',
  guardianMobile: '98450 11101',
  guardianEmail: null,
  source: 'WALK_IN',
  notes: null,
  status,
  studentId: null,
  createdAt: '2026-11-01T10:00:00Z',
});
const seats = [
  { gradeLevelId: 'g5', gradeName: 'Grade 5', programmeCode: 'CBSE', capacity: 40, enrolled: 3, held: 1 },
];

describe('AdmissionsPage', () => {
  it('knows who manages admissions', () => {
    expect(canManageAdmissions(admin)).toBe(true);
    expect(canManageAdmissions(principal)).toBe(false);
  });

  it('shows seats left and moves an application to the next stage', async () => {
    let status = 'APPLIED';
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/transition') && init.method === 'POST') {
        status = 'OFFERED';
        return { body: app(status) };
      }
      if (url.includes('/admissions')) return { body: { items: [app(status)], seats } };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(
      <AdmissionsPage me={admin} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('Aarav Applicant')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '36' })).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Make an offer' }));
    expect(await screen.findByText('Offered', { selector: 'span.badge' })).toBeInTheDocument();
    const post = calls.find((c) => c.url.includes('/transition'))!;
    expect(JSON.parse(String(post.init.body))).toMatchObject({ to: 'OFFERED', overrideSeats: false });
  });

  it('asks for a reason before ending an application, and hides actions from a principal', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/transition') && init.method === 'POST') return { body: app('REJECTED') };
      if (url.includes('/admissions')) return { body: { items: [app('APPLIED')], seats } };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const first = renderWithApp(<AdmissionsPage me={admin} campuses={campuses} selectedCampusId="n" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    const confirm = screen.getByRole('button', { name: 'Confirm' });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Reason/), 'Not eligible for this grade');
    await userEvent.click(confirm);
    await screen.findByText('Aarav Applicant');
    expect(JSON.parse(String(calls.find((c) => c.url.includes('/transition'))!.init.body))).toMatchObject({
      to: 'REJECTED',
      reason: 'Not eligible for this grade',
    });
    first.unmount();
    renderWithApp(<AdmissionsPage me={principal} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('Aarav Applicant')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Make an offer' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add enquiry' })).not.toBeInTheDocument();
  });
});
