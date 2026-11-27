import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import {
  ActivitiesPage,
  canManageActivities,
  canSeeActivities,
  canTakeActivityAttendance,
} from './ActivitiesPage';

const person = (roleKey: string): Me => ({
  personId: 'p1',
  displayName: 'Someone',
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
const chess = {
  id: 'a1',
  name: 'Chess Club',
  kind: 'CLUB',
  capacity: 10,
  leadName: 'Mr Rao',
  status: 'OPEN',
  members: 1,
  sessions: 1,
};

describe('ActivitiesPage', () => {
  it('knows who does what', () => {
    expect(canManageActivities(person('campus_admin'))).toBe(true);
    expect(canManageActivities(person('teacher'))).toBe(false);
    expect(canTakeActivityAttendance(person('teacher'))).toBe(true);
    expect(canSeeActivities(person('transport_attendant'))).toBe(false);
  });

  it('lets the campus office add an activity', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/activities') && init.method === 'POST') return { status: 201, body: chess };
      if (url.endsWith('/activities')) return { body: [chess] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <ActivitiesPage me={person('campus_admin')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('Chess Club')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await user.type(screen.getByLabelText('Activity name'), 'Football');
    await user.selectOptions(screen.getByLabelText('Kind'), 'SPORT');
    await user.click(screen.getByRole('button', { name: 'Add activity' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toMatchObject({
      name: 'Football',
      kind: 'SPORT',
    });
  });

  it('lets a teacher take attendance without management buttons', async () => {
    const today = new Date(Date.now() + 5.5 * 3600 * 1000).toISOString().slice(0, 10);
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/attendance') && init.method === 'POST')
        return { body: { recorded: 2, alreadyRecorded: 0 } };
      if (url.endsWith('/members'))
        return {
          body: [
            { id: 'm1', studentId: 's1', name: 'Anaya Rao', joinedOn: '2026-11-01' },
            { id: 'm2', studentId: 's2', name: 'Vihaan Iyer', joinedOn: '2026-11-01' },
          ],
        };
      if (url.endsWith('/sessions'))
        return { body: [{ id: 'ss1', sessionDate: today, topic: null, present: 0, absent: 0 }] };
      if (url.endsWith('/activities')) return { body: [chess] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    renderWithApp(<ActivitiesPage me={person('teacher')} campuses={campuses} selectedCampusId="n" />);
    expect(screen.queryByText('Add an activity')).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Open Chess Club' }));
    expect(screen.queryByRole('button', { name: /Close Chess Club/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: `Take attendance for ${today}` }));
    await user.click(screen.getByLabelText('Vihaan Iyer came'));
    await user.click(screen.getByRole('button', { name: 'Save attendance' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toEqual({
      marks: [
        { studentId: 's1', present: true },
        { studentId: 's2', present: false },
      ],
    });
  });
});
