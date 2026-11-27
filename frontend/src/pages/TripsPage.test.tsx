import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { TripsPage, canCountHeads, canManageTrips, canSeeTrips } from './TripsPage';

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
const today = new Date(Date.now() + 5.5 * 3600 * 1000).toISOString().slice(0, 10);
const museum = {
  id: 't1',
  name: 'Museum visit',
  destination: 'Science Museum',
  tripDate: today,
  costPaise: 50000,
  consentDeadline: today,
  capacity: 10,
  status: 'PLANNED',
  invited: 2,
  given: 1,
  declined: 0,
  departed: 0,
  returned: 0,
};

describe('TripsPage', () => {
  it('knows who does what', () => {
    expect(canManageTrips(person('campus_admin'))).toBe(true);
    expect(canManageTrips(person('teacher'))).toBe(false);
    expect(canCountHeads(person('teacher'))).toBe(true);
    expect(canSeeTrips(person('transport_attendant'))).toBe(false);
  });

  it('lets the campus office plan a trip', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/trips') && init.method === 'POST') return { status: 201, body: museum };
      if (url.endsWith('/trips')) return { body: [museum] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <TripsPage me={person('campus_admin')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('Museum visit')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await user.type(screen.getByLabelText('Trip name'), 'Fort');
    await user.type(screen.getByLabelText('Destination'), 'Old Fort');
    await user.type(screen.getByLabelText('Trip date'), '2026-12-20');
    await user.type(screen.getByLabelText('Consent needed by'), '2026-12-10');
    await user.clear(screen.getByLabelText('Cost per child (rupees)'));
    await user.type(screen.getByLabelText('Cost per child (rupees)'), '250');
    await user.click(screen.getByRole('button', { name: 'Plan trip' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toMatchObject({
      name: 'Fort',
      tripDate: '2026-12-20',
      consentDeadline: '2026-12-10',
      costPaise: 25000,
    });
  });

  it('lets a teacher count heads only for children with consent', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/headcount') && init.method === 'POST') return { body: {} };
      if (url.endsWith('/participants'))
        return {
          body: [
            {
              id: 'p1',
              studentId: 's1',
              name: 'Anaya Rao',
              consent: 'GIVEN',
              departed: false,
              returned: false,
            },
            {
              id: 'p2',
              studentId: 's2',
              name: 'Vihaan Iyer',
              consent: 'NONE',
              departed: false,
              returned: false,
            },
          ],
        };
      if (url.endsWith('/trips')) return { body: [museum] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    renderWithApp(<TripsPage me={person('teacher')} campuses={campuses} selectedCampusId="n" />);
    expect(screen.queryByText('Plan a trip')).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Open Museum visit' }));
    expect(await screen.findByText('Vihaan Iyer')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Vihaan Iyer left' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Cancel Museum visit/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Anaya Rao left' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toEqual({
      studentId: 's1',
      checkpoint: 'DEPARTED',
    });
  });
});
