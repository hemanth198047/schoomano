import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import type { Campus, Me, StudentProfile } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { StudentProfilePage, StudentsPage } from './StudentsPage';

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
  {
    id: 'n',
    code: 'BLR-N',
    name: 'North Campus',
    timeZone: 'Asia/Kolkata',
    status: 'ACTIVE',
    legalEntity: 'X',
  },
];
const year = { id: 'y1', label: '2026-27', startsOn: '2026-04-01', endsOn: '2027-03-31', status: 'ACTIVE' };
const anaya: StudentProfile = {
  id: 's1',
  schoolRef: 'STU-0020',
  givenName: 'Anaya',
  familyName: 'Rao',
  dateOfBirth: '2016-05-12',
  status: 'ACTIVE',
  view: 'STAFF',
  source: 'LEGACY',
  ownedFields: ['givenName', 'familyName', 'dateOfBirth', 'placement'],
  placements: [
    {
      academicYearId: 'y1',
      yearLabel: '2026-27',
      campusName: 'North Campus',
      programmeCode: 'CBSE-K12',
      gradeName: 'Grade 5',
      sectionCode: 'A',
      status: 'ENROLLED',
    },
  ],
  guardians: [
    {
      relationshipId: 'r1',
      guardianId: 'g1',
      displayName: 'Meera Rao',
      givenName: 'Meera',
      mobile: '+91 98000 00010',
      relationshipType: 'MOTHER',
      status: 'ACTIVE',
      capabilities: [
        'VIEW_ATTENDANCE',
        'VIEW_ACADEMIC',
        'PAY_FEES',
        'GIVE_CONSENT',
        'RECEIVE_NOTICES',
        'PICKUP',
      ],
      sharesHousehold: true,
    },
    {
      relationshipId: 'r2',
      guardianId: 'g2',
      displayName: 'Karan Rao',
      givenName: 'Karan',
      mobile: '+91 98000 00011',
      relationshipType: 'FATHER',
      status: 'RESTRICTED',
      capabilities: ['RECEIVE_NOTICES'],
      sharesHousehold: false,
    },
  ],
  households: ["Meera's household"],
  yourCapabilities: [],
};

let calls: ReturnType<typeof stubFetch>;
beforeEach(() => {
  calls = stubFetch((url, init) => {
    if (url.endsWith('/academic-structure'))
      return {
        body: { campusId: 'n', campusName: 'North', years: [year], year, programmes: [], calendar: [] },
      };
    if (url.includes('/academic-years/y1/students'))
      return {
        body: {
          total: 1,
          students: [
            {
              id: 's1',
              schoolRef: 'STU-0020',
              displayName: 'Anaya Rao',
              programmeCode: 'CBSE-K12',
              gradeName: 'Grade 5',
              sectionCode: 'A',
              guardians: 2,
              source: 'LEGACY',
              status: 'ENROLLED',
            },
          ],
        },
      };
    if (url.endsWith('/students/s1')) return { body: anaya };
    if (url.endsWith('/guardian-relationships/r1') && init.method === 'PATCH') return { body: anaya };
    return { status: 404, body: { code: 'not_found', detail: 'no route' } };
  });
});

const profile = (me: Me) =>
  renderWithApp(
    <Routes>
      <Route path="/students/:studentId" element={<StudentProfilePage me={me} />} />
    </Routes>,
    '/students/s1',
  );

describe('Students', () => {
  it('lists students with class and record source, searching by name', async () => {
    renderWithApp(<StudentsPage campuses={campuses} selectedCampusId={null} />);
    const table = await screen.findByRole('table', { name: 'Students in 2026-27' });
    expect(table).toHaveTextContent('Anaya Rao');
    expect(table).toHaveTextContent('CBSE-K12 · Grade 5 A');
    expect(table).toHaveTextContent('Old system');
    await userEvent.type(screen.getByLabelText('Name or admission number'), 'anaya');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(calls.some((c) => c.url.includes('q=anaya'))).toBe(true);
  });

  it('shows guardians with status, permissions and the old-system lock', async () => {
    profile(admin);
    expect(await screen.findByRole('heading', { name: 'Anaya Rao' })).toBeInTheDocument();
    expect(screen.getByText(/can only be changed there/)).toHaveTextContent(
      'first name, last name, date of birth, section',
    );
    const karan = screen.getByRole('article', { name: /Karan Rao/ });
    expect(karan).toHaveTextContent('Restricted');
    expect(karan).toHaveTextContent('Notices');
    expect(karan).not.toHaveTextContent('Pickup');
  });

  it('restricting a guardian defaults to notices only and requires a reason', async () => {
    profile(admin);
    await userEvent.click(await screen.findByRole('button', { name: 'Change relationship with Meera Rao' }));
    const meera = screen.getByRole('article', { name: /Meera Rao/ });
    await userEvent.selectOptions(within(meera).getByLabelText('Status'), 'RESTRICTED');
    expect(within(meera).getByRole('checkbox', { name: 'Notices' })).toBeChecked();
    expect(within(meera).getByRole('checkbox', { name: 'Pickup' })).not.toBeChecked();
    await userEvent.click(within(meera).getByRole('button', { name: 'Save change' }));
    expect(within(meera).getByLabelText(/Reason/)).toHaveAccessibleDescription(
      'Kept in the audit trail. Do not write court-order details here. Give a reason of at least 10 characters. It is kept in the audit trail.',
    );
    await userEvent.type(within(meera).getByLabelText(/Reason/), 'Separation agreement on file');
    await userEvent.click(within(meera).getByRole('button', { name: 'Save change' }));
    const body = JSON.parse(String(calls.find((c) => c.init.method === 'PATCH')!.init.body));
    expect(body).toEqual({
      status: 'RESTRICTED',
      capabilities: ['RECEIVE_NOTICES'],
      pickupConfirmed: false,
      reason: 'Separation agreement on file',
    });
  });

  it('does not offer changes to teachers', async () => {
    profile(teacher);
    await screen.findByRole('heading', { name: 'Anaya Rao' });
    expect(screen.queryByRole('button', { name: /Change relationship/ })).toBeNull();
    expect(screen.queryByText('Add a guardian')).toBeNull();
  });

  it('has no automatically detectable accessibility violations', async () => {
    const { container } = profile(admin);
    await screen.findByRole('heading', { name: 'Anaya Rao' });
    expect(await axeViolations(container)).toEqual([]);
  });
});
