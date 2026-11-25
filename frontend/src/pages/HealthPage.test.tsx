import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { HealthPage, canSeeFlags, isNurse } from './HealthPage';

const role = (roleKey: string): Me => ({
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
const record = (visits: unknown[]) => ({
  studentId: 'st1',
  studentName: 'Anaya Rao',
  bloodGroup: 'O+',
  allergies: 'Peanuts',
  conditions: null,
  medicines: null,
  emergencyFlag: 'Severe nut allergy',
  updatedAt: '2026-11-20T09:00:00Z',
  updatedBy: 'Nisha',
  visits,
});

describe('HealthPage', () => {
  it('shows teachers only the emergency flags', async () => {
    expect(isNurse(role('nurse'))).toBe(true);
    expect(canSeeFlags(role('teacher'))).toBe(true);
    expect(canSeeFlags(role('nurse'))).toBe(false);
    const calls = stubFetch((url) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/health-flags'))
        return { body: [{ studentId: 'st1', studentName: 'Anaya Rao', flag: 'Severe nut allergy' }] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(
      <HealthPage me={role('teacher')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText(/Severe nut allergy/)).toBeInTheDocument();
    expect(screen.queryByText('Health record')).not.toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/health-record'))).toBe(false);
    expect(await axeViolations(container)).toEqual([]);
  });

  it('is closed to everyone else', async () => {
    stubFetch(() => ({ body: structure }));
    renderWithApp(<HealthPage me={role('finance_officer')} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('Not available')).toBeInTheDocument();
  });

  it('lets the nurse save the record and log a visit', async () => {
    let visits: unknown[] = [];
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/students?'))
        return {
          body: { students: [{ id: 'st1', schoolRef: 'A1', displayName: 'Anaya Rao', guardians: 1 }] },
        };
      if (url.endsWith('/health-visits') && init.method === 'POST') {
        visits = [
          {
            id: 'v1',
            visitedAt: '2026-11-25T09:00:00Z',
            complaint: 'Wheezing after PE',
            actionTaken: null,
            outcome: 'RETURNED_TO_CLASS',
            guardianInformed: false,
            recordedBy: 'Nisha',
          },
        ];
        return { status: 201, body: {} };
      }
      if (url.endsWith('/health-record') && init.method === 'PUT') return { body: record(visits) };
      if (url.endsWith('/health-record')) return { body: record(visits) };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <HealthPage me={role('nurse')} campuses={campuses} selectedCampusId="n" />,
    );
    await user.type(await screen.findByLabelText(/Name or school reference/), 'Anaya');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.click(await screen.findByRole('button', { name: 'Open Anaya Rao' }));
    expect(await screen.findByLabelText(/Allergies/)).toHaveValue('Peanuts');
    await user.click(screen.getByRole('button', { name: 'Save record' }));
    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'PUT')?.init.body))).toMatchObject({
      bloodGroup: 'O+',
      emergencyFlag: 'Severe nut allergy',
    });
    await user.type(screen.getByLabelText(/Complaint/), 'Wheezing after PE');
    await user.click(screen.getByRole('button', { name: 'Record visit' }));
    expect(await screen.findByText('Wheezing after PE')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });
});
