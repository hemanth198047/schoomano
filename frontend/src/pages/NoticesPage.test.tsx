import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { NoticesPage, canSend, isOffice } from './NoticesPage';

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
const parent: Me = { ...base, roles: [], children: [] };
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
const inbox = [
  {
    id: 'd1',
    kind: 'ATTENDANCE',
    title: 'Attendance update',
    body: 'Anaya was marked absent on 05 Oct 2026.',
    receivedAt: '2026-10-05T04:00:00Z',
    read: false,
  },
];

describe('NoticesPage', () => {
  it('knows who may write and who is office staff', () => {
    expect(canSend(teacher)).toBe(true);
    expect(canSend(parent)).toBe(false);
    expect(isOffice(teacher)).toBe(false);
    expect(isOffice(base)).toBe(true);
  });

  it('shows a parent their inbox, lets them mark read, and offers no composer', async () => {
    let read = false;
    const calls = stubFetch((url) => {
      if (url.endsWith('/read')) {
        read = true;
        return { status: 204 };
      }
      if (url.includes('/notices/mine')) return { body: inbox.map((n) => ({ ...n, read })) };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(<NoticesPage me={parent} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('Attendance update')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Write a notice' })).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: /Mark "Attendance update" as read/ }));
    await screen.findByText('No notices yet').catch(() => undefined);
    expect(calls.some((c) => c.url.endsWith('/d1/read'))).toBe(true);
    expect(screen.queryByText('New')).not.toBeInTheDocument();
  });

  it('makes a teacher choose a class, then reports how many people were reached', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/notices/mine')) return { body: [] };
      if (url.endsWith('/notices') && init.method === 'POST')
        return { status: 201, body: { id: 'n1', people: 2 } };
      if (url.endsWith('/notices')) return { body: [] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(
      <NoticesPage me={teacher} campuses={campuses} selectedCampusId="n" />,
    );
    await userEvent.type(await screen.findByLabelText(/^Title/), 'Sports day moved');
    await userEvent.type(screen.getByLabelText(/^Message/), 'Now on Friday.');
    const send = screen.getByRole('button', { name: 'Send notice' });
    expect(send).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText(/^To/), 's5a');
    expect(send).toBeEnabled();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(send);
    expect(await screen.findByText('Sent to 2 people.')).toBeInTheDocument();
    const post = calls.find((c) => c.init.method === 'POST')!;
    expect(JSON.parse(String(post.init.body))).toMatchObject({ title: 'Sports day moved', sectionId: 's5a' });
  });
});
