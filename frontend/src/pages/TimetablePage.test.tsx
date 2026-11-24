import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { TimetablePage, canPlan } from './TimetablePage';

const admin: Me = {
  personId: 'p1',
  displayName: 'Asha',
  tenant: { id: 't', slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey: 'group_admin', scopeLevel: 'GROUP', scopeId: 't', scopeName: 'Nilgiri' }],
  children: [],
  policyVersion: 'test',
};
const teacher: Me = { ...admin, roles: [{ roleKey: 'teacher', scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'N' }] };
const campuses: Campus[] = [
  { id: 'n', code: 'N', name: 'North Campus', timeZone: 'Asia/Kolkata', status: 'ACTIVE', legalEntity: 'X' },
];
const structure = {
  campusId: 'n',
  campusName: 'North Campus',
  years: [],
  year: { id: 'y1', label: '2026-27', startsOn: '2026-06-01', endsOn: '2027-03-31', status: 'ACTIVE' },
  calendar: [],
  programmes: [
    {
      id: 'pr',
      code: 'CBSE-K12',
      name: 'CBSE',
      board: 'CBSE',
      boardDetail: null,
      terms: [],
      subjects: [{ id: 'sub1', code: 'MATH', name: 'Mathematics', status: 'ACTIVE' }],
      grades: [{ id: 'g8', code: '8', name: 'Grade 8', ordinal: 8, sections: [{ id: 's8a', code: 'A', capacity: 40, students: 0 }] }],
    },
  ],
};
const periods = [
  { id: 'p1', ordinal: 1, label: 'Period 1', startsAt: '08:30:00', endsAt: '09:15:00', kind: 'LESSON' },
  { id: 'p2', ordinal: 2, label: 'Break', startsAt: '09:15:00', endsAt: '09:30:00', kind: 'BREAK' },
];

function api(extra: Record<string, unknown> = {}) {
  return stubFetch((url) => {
    if (url.includes('/timetable/me')) {
      return { body: [{ date: '2027-01-11', periodOrdinal: 1, periodLabel: 'Period 1', startsAt: '08:30:00', endsAt: '09:15:00', sectionLabel: 'Grade 8 A', subjectName: 'Mathematics', roomName: null, coverFor: 'Ravi' }] };
    }
    if (url.includes('/academic-structure')) return { body: structure };
    if (url.includes('/periods')) return { body: periods };
    if (url.includes('/teachers')) return { body: [{ id: 't1', name: 'Sunita' }] };
    if (url.includes('/timetable/status')) return { body: extra.status ?? { status: 'DRAFT', version: 0, publishedAt: null, clashes: [] } };
    if (url.includes('/timetable/publish')) {
      return {
        status: 409,
        body: { code: 'publish_rejected', detail: 'The timetable has clashes.', problems: ['Ravi teaches two classes in Period 1 on Monday.'] },
      };
    }
    if (url.includes('/timetable')) return { body: [] };
    if (url.includes('/substitutions')) return { body: [] };
    return { status: 404, body: { code: 'not_found', detail: 'no' } };
  });
}

describe('TimetablePage', () => {
  beforeEach(() => api());

  it('only administrators get the planner', () => {
    expect(canPlan(admin)).toBe(true);
    expect(canPlan(teacher)).toBe(false);
  });

  it('shows a teacher their day, marking lessons they cover', async () => {
    const { container } = renderWithApp(<TimetablePage me={teacher} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText(/Covering for Ravi/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Plan the week' })).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('lets an administrator choose a class and shows the week with breaks', async () => {
    const { container } = renderWithApp(<TimetablePage me={admin} campuses={campuses} selectedCampusId="n" />);
    await userEvent.selectOptions(await screen.findByLabelText('Class'), 's8a');
    expect(await screen.findByRole('table', { name: /Weekly timetable/ })).toBeInTheDocument();
    expect(screen.getAllByText('Break').length).toBeGreaterThan(0);
    expect(await screen.findByRole('button', { name: 'Add lesson' })).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('explains clashes in words when publishing is refused', async () => {
    renderWithApp(<TimetablePage me={admin} campuses={campuses} selectedCampusId="n" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Publish timetable' }));
    expect(await screen.findByText(/Ravi teaches two classes in Period 1/)).toBeInTheDocument();
  });
});
