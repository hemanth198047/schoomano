import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { AcademicStructure, CalendarEvent, Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { AcademicPage } from './AcademicPage';

const TENANT = '10000000-0000-4000-8000-000000000001';
const admin: Me = {
  personId: 'p1',
  displayName: 'Asha Admin',
  tenant: { id: TENANT, slug: 'demo-group', name: 'Nilgiri' },
  roles: [{ roleKey: 'group_admin', scopeLevel: 'GROUP', scopeId: TENANT, scopeName: 'Nilgiri' }],
  children: [],
  policyVersion: 'test',
};
const teacher: Me = {
  ...admin,
  roles: [{ roleKey: 'teacher', scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'North' }],
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

const structure: AcademicStructure = {
  campusId: 'n',
  campusName: 'North Campus',
  years: [{ id: 'y1', label: '2026-27', startsOn: '2026-04-01', endsOn: '2027-03-31', status: 'ACTIVE' }],
  year: { id: 'y1', label: '2026-27', startsOn: '2026-04-01', endsOn: '2027-03-31', status: 'ACTIVE' },
  programmes: [
    {
      id: 'cbse',
      code: 'CBSE-K12',
      name: 'CBSE Grades 1–12',
      board: 'CBSE',
      boardDetail: null,
      grades: [
        {
          id: 'g5',
          code: 'G5',
          name: 'Grade 5',
          ordinal: 5,
          sections: [
            { id: 's1', code: 'A', capacity: 40, students: 1 },
            { id: 's2', code: 'B', capacity: 40, students: 0 },
          ],
        },
      ],
      subjects: [{ id: 'm', code: 'MATH', name: 'Mathematics', status: 'ACTIVE' }],
      terms: [{ id: 't1', label: 'Term 1', startsOn: '2026-04-01', endsOn: '2026-09-30' }],
    },
  ],
  calendar: [
    {
      id: 'c1',
      kind: 'HOLIDAY',
      title: 'Independence Day',
      startsOn: '2026-08-15',
      endsOn: '2026-08-15',
      programmeId: null,
    },
    // As the API sends it: null fields are omitted.
    {
      id: 'c2',
      kind: 'HOLIDAY',
      title: 'Republic Day',
      startsOn: '2027-01-26',
      endsOn: '2027-01-26',
    } as CalendarEvent,
  ],
};

let calls: ReturnType<typeof stubFetch>;
beforeEach(() => {
  calls = stubFetch((url, init) => {
    if (url.endsWith('/academic-structure')) return { body: structure };
    if (url.includes('/config/assessment.grade_scale?programmeId='))
      return {
        body: {
          key: 'assessment.grade_scale',
          value: {
            bands: [
              { grade: 'E', minPercent: 0 },
              { grade: 'A1', minPercent: 91 },
            ],
          },
          version: 1,
          definedAtLevel: 'PROGRAMME',
          definedAtId: 'cbse',
          inherited: false,
          asOf: '2026-11-10T00:00:00Z',
        },
      };
    if (url.endsWith('/calendar-events') && init.method === 'POST')
      return { status: 201, body: { id: 'c9' } };
    if (url.endsWith('/terms/t1') && init.method === 'DELETE') return { status: 204 };
    if (url.endsWith('/rollover') && init.method === 'POST')
      return {
        status: 201,
        body: {
          yearId: 'y2',
          label: '2027-28',
          startsOn: '2027-04-01',
          endsOn: '2028-03-31',
          termsCreated: 1,
          sectionsCreated: 2,
          studentsPlaced: 1,
          studentsUnplaced: 0,
          studentsCompleted: 0,
        },
      };
    if (url.endsWith('/terms') && init.method === 'POST')
      return {
        status: 409,
        body: {
          code: 'term_rejected',
          detail: 'This term cannot be added.',
          problems: ['Overlaps Term 1 (2026-04-01 to 2026-09-30).'],
        },
      };
    if (url.endsWith('/rollover-preview'))
      return {
        body: {
          label: '2027-28',
          dates: { startsOn: '2027-04-01', endsOn: '2028-03-31' },
          terms: [],
          sections: [],
          promotions: [{ programmeCode: 'CBSE-K12', fromGrade: 'G5', toGrade: 'G6', students: 1 }],
          warnings: ['1 student(s) in CBSE-K12 G12 complete the programme.'],
        },
      };
    return { status: 404, body: { code: 'not_found', detail: 'no route' } };
  });
});

describe('AcademicPage', () => {
  it('shows terms, sections with occupancy, subjects and the calendar per programme', async () => {
    renderWithApp(<AcademicPage me={teacher} campuses={campuses} selectedCampusId={null} />);
    expect(await screen.findByRole('table', { name: /terms/ })).toHaveTextContent('Term 1');
    expect(screen.getByRole('table', { name: /grades and sections/ })).toHaveTextContent(
      'A: 1 / 40 · B: 0 / 40',
    );
    expect(screen.getByText('Mathematics')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: /Calendar/ })).toHaveTextContent('Independence Day');
    const rows = within(screen.getByRole('table', { name: /Calendar/ })).getAllByRole('row');
    expect(rows.filter((r) => r.textContent?.includes('Whole campus'))).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Add term' })).toBeNull();
  });

  it('shows the server reasons when a term is rejected', async () => {
    renderWithApp(<AcademicPage me={admin} campuses={campuses} selectedCampusId={null} />);
    const form = within(await screen.findByRole('region', { name: 'Add a term' }));
    await userEvent.type(form.getByLabelText(/Term name/), 'Mid term');
    await userEvent.type(form.getByLabelText(/First day/), '2026-09-01');
    await userEvent.type(form.getByLabelText(/Last day/), '2026-10-15');
    await userEvent.click(form.getByRole('button', { name: 'Add term' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Check the term dates.');
    expect(alert).toHaveTextContent('Overlaps Term 1');
    const body = JSON.parse(String(calls.find((c) => c.url.endsWith('/terms'))!.init.body));
    expect(body).toEqual({
      programmeId: 'cbse',
      label: 'Mid term',
      startsOn: '2026-09-01',
      endsOn: '2026-10-15',
    });
  });

  it('validates term dates before calling the server', async () => {
    renderWithApp(<AcademicPage me={admin} campuses={campuses} selectedCampusId={null} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Add term' }));
    expect(screen.getByLabelText(/Term name/)).toHaveAccessibleDescription(
      'Name the term, for example "Term 3".',
    );
    expect(calls.some((c) => c.init.method === 'POST')).toBe(false);
  });

  it('previews next year without changing anything', async () => {
    renderWithApp(<AcademicPage me={admin} campuses={campuses} selectedCampusId={null} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Preview next year' }));
    const region = await screen.findByRole('region', { name: 'Rollover preview' });
    expect(region).toHaveTextContent('2027-28');
    await waitFor(() =>
      expect(within(region).getByRole('table', { name: 'Students moving up' })).toHaveTextContent('G5G61'),
    );
    expect(region).toHaveTextContent('complete the programme');
  });

  it('adds a campus-wide holiday', async () => {
    renderWithApp(<AcademicPage me={admin} campuses={campuses} selectedCampusId={null} />);
    const form = within(await screen.findByRole('region', { name: 'Add to the structure' }));
    await userEvent.type(form.getByLabelText(/Title/), 'Pongal');
    await userEvent.type(form.getByLabelText(/First day/), '2027-01-14');
    await userEvent.type(form.getByLabelText(/Last day/), '2027-01-15');
    await userEvent.click(form.getByRole('button', { name: 'Add' }));
    expect(await form.findByRole('status')).toHaveTextContent('Added Pongal');
    const body = JSON.parse(String(calls.find((c) => c.url.endsWith('/calendar-events'))!.init.body));
    expect(body).toEqual({ kind: 'HOLIDAY', title: 'Pongal', startsOn: '2027-01-14', endsOn: '2027-01-15' });
  });

  it('removes a term only after confirmation', async () => {
    renderWithApp(<AcademicPage me={admin} campuses={campuses} selectedCampusId={null} />);
    await screen.findByRole('table', { name: /terms/ });
    await userEvent.click(screen.getByRole('button', { name: 'Remove Term 1' }));
    expect(calls.some((c) => c.init.method === 'DELETE')).toBe(false);
    await userEvent.click(screen.getByRole('button', { name: 'Yes, remove Term 1' }));
    await waitFor(() => expect(calls.some((c) => c.init.method === 'DELETE')).toBe(true));
  });

  it('commits the rollover after preview and confirmation', async () => {
    renderWithApp(<AcademicPage me={admin} campuses={campuses} selectedCampusId={null} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Preview next year' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Create 2027-28' }));
    await userEvent.click(screen.getByRole('button', { name: 'Yes, create 2027-28' }));
    expect(await screen.findByText(/1 students placed/)).toBeInTheDocument();
  });

  it('shows the current grade scale to administrators', async () => {
    renderWithApp(<AcademicPage me={admin} campuses={campuses} selectedCampusId={null} />);
    expect(await screen.findByText('A1 from 91% · E from 0%')).toBeInTheDocument();
  });

  it('tells people without a campus role what they need', () => {
    renderWithApp(<AcademicPage me={teacher} campuses={[]} selectedCampusId={null} />);
    expect(screen.getByText('No campus to show')).toBeInTheDocument();
  });

  it('has no automatically detectable accessibility violations', async () => {
    const { container } = renderWithApp(
      <AcademicPage me={admin} campuses={campuses} selectedCampusId={null} />,
    );
    await screen.findByRole('table', { name: /terms/ });
    expect(await axeViolations(container)).toEqual([]);
  });
});
