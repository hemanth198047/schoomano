import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { LearningPage, canEditSyllabus, canTeach } from './LearningPage';

const base: Me = {
  personId: 'p1',
  displayName: 'Asha',
  tenant: { id: 't', slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey: 'group_admin', scopeLevel: 'GROUP', scopeId: 't', scopeName: 'Nilgiri' }],
  children: [],
  policyVersion: 'test',
};
const teacher: Me = {
  ...base,
  roles: [{ roleKey: 'teacher', scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'N' }],
};
const campuses: Campus[] = [
  { id: 'n', code: 'N', name: 'North Campus', timeZone: 'Asia/Kolkata', status: 'ACTIVE', legalEntity: 'X' },
];
const structure = {
  campusId: 'n',
  campusName: 'North',
  years: [],
  year: null,
  calendar: [],
  programmes: [
    {
      id: 'pr',
      code: 'C',
      name: 'CBSE',
      board: 'CBSE',
      boardDetail: null,
      terms: [],
      subjects: [{ id: 'sci', code: 'SCI', name: 'Science', status: 'ACTIVE' }],
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
const hw = {
  id: 'h1',
  sectionId: 's5a',
  subjectId: 'sci',
  subjectName: 'Science',
  title: 'Draw a plant',
  instructions: 'Label five parts',
  assignedOn: '2026-11-25',
  dueOn: '2026-11-28',
  status: 'ACTIVE',
};
const coverage = (covered: boolean) => ({
  subjectId: 'sci',
  total: 2,
  covered: covered ? 1 : 0,
  units: [
    {
      id: 'u1',
      ordinal: 1,
      title: 'Plants and seeds',
      outcomes: null,
      coveredOn: covered ? '2026-11-25' : null,
    },
    { id: 'u2', ordinal: 2, title: 'Water cycle', outcomes: null, coveredOn: null },
  ],
});

describe('LearningPage', () => {
  it('knows who teaches and who edits the syllabus', () => {
    expect(canTeach(teacher)).toBe(true);
    expect(canEditSyllabus(teacher)).toBe(false);
    expect(canEditSyllabus(base)).toBe(true);
  });

  it('lists homework, sets new homework, and marks a unit as covered', async () => {
    let covered = false;
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/homework') && init.method === 'POST') return { body: hw };
      if (url.endsWith('/homework')) return { body: [hw] };
      if (url.endsWith('/coverage') && init.method === 'POST') {
        covered = true;
        return { body: {} };
      }
      if (url.includes('/coverage?')) return { body: coverage(covered) };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(
      <LearningPage me={teacher} campuses={campuses} selectedCampusId="n" />,
    );
    await userEvent.selectOptions(await screen.findByLabelText('Class'), 's5a');
    expect(await screen.findByRole('button', { name: 'Cancel Draw a plant' })).toBeInTheDocument();
    const subjects = screen.getAllByLabelText('Subject');
    await userEvent.selectOptions(subjects[0]!, 'sci');
    await userEvent.type(screen.getByLabelText('Title'), 'Read chapter 4');
    await userEvent.type(screen.getByLabelText('Due on'), '2026-12-01');
    await userEvent.click(screen.getByRole('button', { name: 'Set homework' }));
    const post = calls.find((c) => c.init.method === 'POST' && c.url.endsWith('/homework'))!;
    expect(JSON.parse(String(post.init.body))).toMatchObject({
      subjectId: 'sci',
      title: 'Read chapter 4',
      dueOn: '2026-12-01',
    });

    await userEvent.selectOptions(screen.getAllByLabelText('Subject')[1]!, 'sci');
    expect(await screen.findByText('0 of 2 units covered (0%)')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Mark Plants and seeds as covered' }));
    expect(await screen.findByText('1 of 2 units covered (50%)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add unit' })).not.toBeInTheDocument();
  });

  it('lets an administrator add a syllabus unit', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/homework')) return { body: [] };
      if (url.includes('/curriculum') && init.method === 'POST') return { body: {} };
      if (url.includes('/coverage?')) return { body: { subjectId: 'sci', total: 0, covered: 0, units: [] } };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    renderWithApp(<LearningPage me={base} campuses={campuses} selectedCampusId="n" />);
    await userEvent.selectOptions(await screen.findByLabelText('Class'), 's5a');
    await userEvent.selectOptions((await screen.findAllByLabelText('Subject'))[1]!, 'sci');
    await userEvent.type(await screen.findByLabelText('Unit title'), 'Photosynthesis');
    await userEvent.click(screen.getByRole('button', { name: 'Add unit' }));
    const post = calls.find((c) => c.url.includes('/grade-levels/g5/curriculum'))!;
    expect(JSON.parse(String(post.init.body))).toMatchObject({ subjectId: 'sci', title: 'Photosynthesis' });
  });
});
