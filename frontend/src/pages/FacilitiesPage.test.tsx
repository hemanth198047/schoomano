import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { FacilitiesPage, canManageFacilities, canSeeFacilities } from './FacilitiesPage';

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
const hall = { id: 'r1', code: 'HALL', name: 'Main Hall', capacity: 200, status: 'ACTIVE' };
const booking = {
  id: 'b1',
  roomId: 'r1',
  roomName: 'Main Hall',
  purpose: 'Assembly',
  startsAt: '2026-12-01T04:00:00Z',
  endsAt: '2026-12-01T05:00:00Z',
  bookedBy: 'Ravi Kumar',
  mine: true,
  status: 'BOOKED',
};
const fan = {
  id: 'm1',
  roomId: 'r1',
  roomName: 'Main Hall',
  title: 'Broken fan',
  description: null,
  priority: 'URGENT',
  status: 'IN_PROGRESS',
  reportedBy: 'Ravi Kumar',
  reportedAt: '2026-11-27T04:00:00Z',
  resolution: null,
  resolvedAt: null,
};
const server = (url: string, init: { method?: string }) => {
  if (url.endsWith('/rooms')) return { body: [hall] };
  if (url.includes('/bookings') && init.method === 'POST') return { status: 201, body: booking };
  if (url.includes('/bookings')) return { body: [booking] };
  if (url.includes('/maintenance') && init.method === 'POST') return { body: fan };
  if (url.includes('/maintenance')) return { body: [fan] };
  return { status: 404, body: { code: 'not_found', detail: 'no' } };
};

describe('FacilitiesPage', () => {
  it('knows who sees and manages', () => {
    expect(canSeeFacilities(person('teacher'))).toBe(true);
    expect(canSeeFacilities(person('transport_attendant'))).toBe(false);
    expect(canManageFacilities(person('teacher'))).toBe(false);
    expect(canManageFacilities(person('campus_admin'))).toBe(true);
  });

  it('lets a teacher book a room in Indian time and report a problem, but not run the repairs', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <FacilitiesPage me={person('teacher')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('Broken fan')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Resolve/ })).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await user.selectOptions(screen.getByLabelText('Room'), 'r1');
    await user.type(screen.getByLabelText('What for'), 'Debate');
    await user.type(screen.getByLabelText('From'), '2026-12-02T10:00');
    await user.type(screen.getByLabelText('Until'), '2026-12-02T11:30');
    await user.click(screen.getByRole('button', { name: 'Book room' }));
    const post = calls.find((c) => c.init.method === 'POST' && c.url.includes('/bookings'));
    expect(JSON.parse(String(post?.init.body))).toEqual({
      purpose: 'Debate',
      startsAt: '2026-12-02T10:00:00+05:30',
      endsAt: '2026-12-02T11:30:00+05:30',
    });
  });

  it('lets the campus office resolve a problem with a note', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    renderWithApp(<FacilitiesPage me={person('campus_admin')} campuses={campuses} selectedCampusId="n" />);
    await user.click(await screen.findByRole('button', { name: 'Resolve Broken fan' }));
    await user.type(screen.getByLabelText('What was done for Broken fan'), 'Replaced the capacitor');
    await user.click(screen.getByRole('button', { name: 'Save resolution' }));
    const post = calls.find((c) => c.url.endsWith('/resolve'));
    expect(JSON.parse(String(post?.init.body))).toEqual({ resolution: 'Replaced the capacitor' });
  });
});
