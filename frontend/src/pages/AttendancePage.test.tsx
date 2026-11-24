import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { AttendancePage, canCorrect, canMark } from './AttendancePage';

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
      terms: [],
      subjects: [],
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
const roster = {
  sectionId: 's5a',
  sectionLabel: 'Grade 5 A',
  date: '2026-10-05',
  openConflicts: 0,
  students: [{ studentId: 'st1', schoolRef: 'STU-0020', name: 'Anaya Rao', status: null, version: null }],
};

describe('AttendancePage', () => {
  it('knows who marks and who corrects', () => {
    expect(canMark(teacher)).toBe(true);
    expect(canMark(admin)).toBe(false);
    expect(canCorrect(admin)).toBe(true);
    expect(canCorrect(teacher)).toBe(false);
  });

  it('sends one batch with an idempotency key and shows the result', async () => {
    const calls = stubFetch((url) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/attendance/batches'))
        return {
          body: {
            results: [{ studentId: 'st1', outcome: 'CREATED', status: 'ABSENT', version: 1, message: null }],
            conflicts: 0,
            replayed: false,
          },
        };
      if (url.includes('/attendance?')) return { body: roster };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(
      <AttendancePage me={teacher} campuses={campuses} selectedCampusId="n" />,
    );
    await userEvent.selectOptions(await screen.findByLabelText('Class'), 's5a');
    await userEvent.click(await screen.findByRole('radio', { name: 'Absent' }));
    expect(screen.getByText('Not saved')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Save attendance' }));
    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    const post = calls.find((c) => c.url.includes('/batches'))!;
    expect((post.init.headers as Record<string, string>)['Idempotency-Key']).toMatch(/^att-/);
    expect(JSON.parse(String(post.init.body)).marks[0]).toMatchObject({
      studentId: 'st1',
      status: 'ABSENT',
      baseVersion: 0,
    });
  });

  it('keeps the marks and says so when the network is down', async () => {
    let fail = true;
    stubFetch((url) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/attendance/batches')) {
        if (fail) return { status: 503, body: { code: 'unavailable', detail: 'down' } };
        return { body: { results: [], conflicts: 0, replayed: false } };
      }
      if (url.includes('/attendance?')) return { body: roster };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    renderWithApp(<AttendancePage me={teacher} campuses={campuses} selectedCampusId="n" />);
    await userEvent.selectOptions(await screen.findByLabelText('Class'), 's5a');
    await userEvent.click(await screen.findByRole('radio', { name: 'Late' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save attendance' }));
    expect(await screen.findByText(/kept here and will be sent/)).toBeInTheDocument();
    expect(screen.getByText('1 waiting to send')).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Send now' }));
    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    expect(screen.queryByText(/waiting to send/)).not.toBeInTheDocument();
  });

  it('shows office staff the marks to review and lets them choose', async () => {
    let resolved = false;
    const calls = stubFetch((url) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/conflicts/') && url.endsWith('/resolve')) {
        resolved = true;
        return { body: { id: 'c1', resolution: 'USED_ATTEMPT' } };
      }
      if (url.includes('/attendance/conflicts'))
        return {
          body: resolved
            ? []
            : [
                {
                  id: 'c1',
                  studentId: 'st1',
                  studentName: 'Anaya Rao',
                  sectionLabel: 'Grade 5 A',
                  date: '2026-10-05',
                  currentStatus: 'LEAVE',
                  currentVersion: 2,
                  attemptedStatus: 'PRESENT',
                  attemptedBy: 'Ravi',
                  attemptedMarkedAt: '2026-10-05T03:00:00Z',
                },
              ],
        };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    renderWithApp(<AttendancePage me={admin} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText(/office has/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Use Present' }));
    await waitFor(() => expect(screen.queryByText(/office has/)).not.toBeInTheDocument());
    expect(calls.some((c) => c.url.endsWith('/resolve'))).toBe(true);
    expect(screen.queryByText(/office has/)).not.toBeInTheDocument();
  });
});
