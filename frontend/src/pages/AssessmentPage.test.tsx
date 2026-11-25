import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { AssessmentPage, canEnterMarks, canPublish } from './AssessmentPage';

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
  year: null,
  calendar: [],
  programmes: [
    {
      id: 'pr',
      code: 'C',
      name: 'CBSE',
      board: 'CBSE',
      boardDetail: null,
      terms: [{ id: 'term1', label: 'Term 1', startsOn: '2026-04-01', endsOn: '2026-09-30' }],
      subjects: [{ id: 'eng', code: 'ENG', name: 'English', status: 'ACTIVE' }],
      grades: [
        {
          id: 'g5',
          code: '5',
          name: 'Grade 5',
          ordinal: 5,
          sections: [{ id: 's5a', code: 'A', capacity: 40, students: 1 }],
        },
      ],
    },
  ],
};
const assessment = {
  id: 'a1',
  sectionId: 's5a',
  subjectId: 'eng',
  subjectName: 'English',
  termId: 'term1',
  termLabel: 'Term 1',
  name: 'Unit test 1',
  maxMarks: 20,
  weightPercent: 50,
  heldOn: null,
  status: 'LOCKED',
  marksEntered: 1,
  students: 1,
};
const marks = {
  assessment,
  marks: [
    { studentId: 'st1', schoolRef: 'STU-0020', name: 'Anaya Rao', kind: 'SCORED', marks: 15, version: 1 },
  ],
};
const preview = {
  sectionId: 's5a',
  termId: 'term1',
  ready: true,
  blockers: [],
  gradeScaleVersion: 1,
  students: [
    {
      studentId: 'st1',
      name: 'Anaya Rao',
      subjects: [{ subjectId: 'eng', name: 'English', percent: 75, grade: 'B1', assessments: 1 }],
      overallPercent: 75,
      overallGrade: 'B1',
      publishedRevision: null,
      changed: true,
    },
  ],
};

describe('AssessmentPage', () => {
  it('knows who enters marks and who publishes', () => {
    expect(canEnterMarks(teacher)).toBe(true);
    expect(canPublish(teacher)).toBe(false);
    expect(canPublish(admin)).toBe(true);
  });

  it('asks for a reason before changing a locked mark and sends it with the change', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/assessments/a1/marks') && init.method === 'PUT')
        return { body: { created: 0, changed: 1, unchanged: 0 } };
      if (url.endsWith('/assessments/a1/marks')) return { body: marks };
      if (url.includes('/assessments?')) return { body: [assessment] };
      if (url.includes('/report-preview')) return { body: preview };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(
      <AssessmentPage me={teacher} campuses={campuses} selectedCampusId="n" />,
    );
    await userEvent.selectOptions(await screen.findByLabelText('Class'), 's5a');
    await userEvent.selectOptions(screen.getByLabelText('Term'), 'term1');
    await userEvent.click(await screen.findByRole('button', { name: 'Marks' }));
    const input = await screen.findByLabelText('Marks for Anaya Rao');
    await userEvent.clear(input);
    await userEvent.type(input, '17');
    const save = screen.getByRole('button', { name: 'Save marks' });
    expect(save).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Reason for the change/), 'Re-marked after query');
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(save);
    expect(await screen.findByText('Saved: 0 new, 1 changed.')).toBeInTheDocument();
    const put = calls.find((c) => c.init.method === 'PUT')!;
    expect(JSON.parse(String(put.init.body))).toEqual({
      reason: 'Re-marked after query',
      entries: [{ studentId: 'st1', kind: 'SCORED', marks: 17 }],
    });
  });

  it('lists what blocks publishing, and lets the principal publish once ready', async () => {
    let ready = false;
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/report-cards/publish') && init.method === 'POST') {
        ready = true;
        return { body: { published: 1, unchanged: 0 } };
      }
      if (url.includes('/assessments?')) return { body: [assessment] };
      if (url.includes('/report-preview'))
        return {
          body: ready
            ? { ...preview, students: [{ ...preview.students[0], publishedRevision: 1, changed: false }] }
            : preview,
        };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    renderWithApp(<AssessmentPage me={admin} campuses={campuses} selectedCampusId="n" />);
    await userEvent.selectOptions(await screen.findByLabelText('Class'), 's5a');
    await userEvent.selectOptions(screen.getByLabelText('Term'), 'term1');
    expect(await screen.findByText(/English 75.0% B1/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Publish report cards' }));
    expect(await screen.findByText('Published 1 report card.')).toBeInTheDocument();
    expect(await screen.findByText('Published, revision 1')).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/publish?termId=term1'))).toBe(true);
  });

  it('shows blockers and hides publishing from a teacher', async () => {
    stubFetch((url) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/assessments?')) return { body: [] };
      if (url.includes('/report-preview'))
        return {
          body: {
            ...preview,
            ready: false,
            blockers: ['Not locked yet: English – Unit test 1.'],
            students: [],
          },
        };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    renderWithApp(<AssessmentPage me={teacher} campuses={campuses} selectedCampusId="n" />);
    await userEvent.selectOptions(await screen.findByLabelText('Class'), 's5a');
    await userEvent.selectOptions(screen.getByLabelText('Term'), 'term1');
    expect(await screen.findByText(/Not locked yet/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Publish/ })).not.toBeInTheDocument();
  });
});
