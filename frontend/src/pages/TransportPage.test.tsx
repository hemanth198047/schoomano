import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { TransportPage, canManageTransport, canRecordBoarding, canSeeTransport } from './TransportPage';

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
const route = {
  id: 'r1',
  name: 'North 1',
  vehicle: 'KA01AB1234',
  capacity: 30,
  driverName: 'Ramesh',
  driverPhone: null,
  assigned: 1,
  stops: [{ id: 's1', sequence: 1, name: 'Lake Gate', pickupTime: '07:10:00' }],
};

describe('TransportPage', () => {
  it('knows who does what', () => {
    expect(canManageTransport(person('campus_admin'))).toBe(true);
    expect(canManageTransport(person('transport_attendant'))).toBe(false);
    expect(canRecordBoarding(person('transport_attendant'))).toBe(true);
    expect(canSeeTransport(person('teacher'))).toBe(false);
  });

  it('shows the campus office routes and lets it add a route', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/routes') && init.method === 'POST') return { status: 201, body: route };
      if (url.endsWith('/routes')) return { body: [route] };
      if (url.endsWith('/vehicles'))
        return { body: [{ id: 'v1', registration: 'KA01AB1234', capacity: 30 }] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <TransportPage me={person('campus_admin')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('North 1')).toBeInTheDocument();
    expect(screen.getByText(/07:10 Lake Gate/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /roster/ })).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await user.type(screen.getByLabelText('Route name'), 'South 2');
    await user.type(screen.getByLabelText('Driver name'), 'Imran');
    await user.click(screen.getByRole('button', { name: 'Add route' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toMatchObject({
      name: 'South 2',
      driverName: 'Imran',
    });
  });

  it('lets the attendant mark a child on and off the bus', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/boarding') && init.method === 'POST') return { body: {} };
      if (url.includes('/roster'))
        return {
          body: [
            {
              assignmentId: 'a1',
              studentId: 'st1',
              name: 'Anaya Rao',
              stop: 'Lake Gate',
              pickupTime: '07:10:00',
              today: [],
            },
          ],
        };
      if (url.endsWith('/routes')) return { body: [route] };
      if (url.endsWith('/vehicles')) return { status: 404, body: { code: 'not_found', detail: 'no' } };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    renderWithApp(
      <TransportPage me={person('transport_attendant')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(screen.queryByText('Add a vehicle')).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Open North 1 roster' }));
    await user.selectOptions(await screen.findByLabelText('Trip'), 'MORNING');
    await user.click(await screen.findByRole('button', { name: 'Anaya Rao got on' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toMatchObject({
      studentId: 'st1',
      trip: 'MORNING',
      event: 'BOARDED',
    });
  });
});
