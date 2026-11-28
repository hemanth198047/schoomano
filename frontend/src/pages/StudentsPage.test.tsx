import { cleanup, screen, within } from '@testing-library/react';
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
    if (url.endsWith('/students/s1/documents') && init.method === 'POST')
      return { status: 201, body: { id: 'd2' } };
    if (url.endsWith('/students/s1/documents'))
      return {
        body: [
          {
            id: 'd1',
            kind: 'BIRTH_CERTIFICATE',
            fileName: 'birth.pdf',
            contentType: 'application/pdf',
            sizeBytes: 2048,
            note: 'Original seen',
            uploadedAt: '2026-11-20T10:00:00Z',
          },
        ],
      };
    if (url.endsWith('/students/s1/documents/d1/remove') && init.method === 'POST')
      return { body: { removed: true } };
    if (url.endsWith('/guardians/g1/sessions'))
      return {
        body: [
          {
            id: 'x1',
            deviceLabel: 'Redmi 9',
            signedInAt: '2026-11-20T10:00:00Z',
            lastSeenAt: '2026-11-27T10:00:00Z',
          },
        ],
      };
    if (url.endsWith('/guardians/g1/sessions/revoke') && init.method === 'POST')
      return { body: { revoked: 1 } };
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

  it('marks each detail the old system owns, and only those', async () => {
    profile(admin);
    const details = await screen.findByRole('region', { name: 'Details' });
    expect(within(details).getAllByText('Old system')).toHaveLength(3);
    expect(
      within(screen.getByRole('region', { name: 'Placement Old system' })).getByText('Old system'),
    ).toBeInTheDocument();
    cleanup();
    calls = stubFetch((url) =>
      url.endsWith('/students/s1')
        ? { body: { ...anaya, ownedFields: ['givenName'] } }
        : { status: 404, body: { code: 'not_found', detail: 'no route' } },
    );
    profile(admin);
    const some = await screen.findByRole('region', { name: 'Details' });
    expect(within(some).getAllByText('Old system')).toHaveLength(1);
    expect(screen.getByRole('region', { name: 'Placement' })).toBeInTheDocument();
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

  it('shows where a guardian is signed in and ends every session with a reason', async () => {
    profile(admin);
    await userEvent.click(await screen.findByText('Signed-in devices for Meera Rao'));
    expect(await screen.findByText('Redmi 9')).toBeInTheDocument();
    expect(calls.some((c) => c.url.endsWith('/guardians/g1/sessions/revoke'))).toBe(false);
    await userEvent.click(screen.getByRole('button', { name: 'Sign Meera Rao out everywhere' }));
    expect(screen.getByLabelText(/Reason for signing Meera Rao out/)).toHaveAccessibleDescription(
      /at least 10 characters/,
    );
    expect(calls.some((c) => c.url.endsWith('/revoke'))).toBe(false);
    await userEvent.type(screen.getByLabelText(/Reason for signing Meera Rao out/), 'Phone was lost');
    await userEvent.click(screen.getByRole('button', { name: 'Sign Meera Rao out everywhere' }));
    const post = calls.find((c) => c.url.endsWith('/guardians/g1/sessions/revoke'))!;
    expect(JSON.parse(String(post.init.body))).toEqual({ reason: 'Phone was lost' });
    expect(await screen.findByText('Signed out of every device.')).toBeInTheDocument();
  });

  it('keeps documents for the office: lists them, adds one as a file upload, and removes one for a reason', async () => {
    profile(admin);
    const docs = await screen.findByRole('region', { name: 'Documents' });
    expect(await within(docs).findByText(/birth\.pdf/)).toBeInTheDocument();
    expect(docs).toHaveTextContent('Birth certificate');
    expect(docs).toHaveTextContent('2 KB');

    await userEvent.click(within(docs).getByText('Add a document'));
    await userEvent.click(within(docs).getByRole('button', { name: 'Add document' }));
    expect(within(docs).getByLabelText(/^File/)).toHaveAccessibleDescription(/Choose a PDF, PNG or JPEG/);
    await userEvent.selectOptions(within(docs).getByLabelText('Kind'), 'ID_PROOF');
    await userEvent.upload(
      within(docs).getByLabelText(/^File/),
      new File(['%PDF-1.7'], 'aadhaar.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(within(docs).getByRole('button', { name: 'Add document' }));
    const post = calls.find((c) => c.url.endsWith('/students/s1/documents') && c.init.method === 'POST')!;
    expect(post.init.body).toBeInstanceOf(FormData);
    expect((post.init.body as FormData).get('kind')).toBe('ID_PROOF');
    expect(((post.init.body as FormData).get('file') as File).name).toBe('aadhaar.pdf');
    expect(await within(docs).findByText('Document added.')).toBeInTheDocument();

    await userEvent.click(within(docs).getByRole('button', { name: 'Remove birth.pdf' }));
    await userEvent.click(within(docs).getByRole('button', { name: 'Remove document' }));
    expect(within(docs).getByLabelText(/^Why is it being removed/)).toHaveAccessibleDescription(
      /at least 10 characters/,
    );
    await userEvent.type(
      within(docs).getByLabelText(/^Why is it being removed/),
      'Uploaded to the wrong child',
    );
    await userEvent.click(within(docs).getByRole('button', { name: 'Remove document' }));
    const removal = calls.find((c) => c.url.endsWith('/documents/d1/remove'))!;
    expect(JSON.parse(String(removal.init.body))).toEqual({ reason: 'Uploaded to the wrong child' });
  });

  it('does not offer changes to teachers', async () => {
    profile(teacher);
    await screen.findByRole('heading', { name: 'Anaya Rao' });
    expect(screen.queryByRole('button', { name: /Change relationship/ })).toBeNull();
    expect(screen.queryByText('Add a guardian')).toBeNull();
    expect(screen.queryByText(/Signed-in devices for/)).toBeNull();
    expect(screen.queryByRole('region', { name: 'Documents' })).toBeNull();
  });

  it('has no automatically detectable accessibility violations', async () => {
    const { container } = profile(admin);
    await screen.findByRole('heading', { name: 'Anaya Rao' });
    expect(await axeViolations(container)).toEqual([]);
  });
});
