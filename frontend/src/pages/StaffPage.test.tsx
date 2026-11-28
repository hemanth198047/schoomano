import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { renderWithApp, stubFetch } from '../test/render';
import { StaffPage, canManageStaff } from './StaffPage';

const person = (roleKey: string): Me => ({
  personId: 'p1',
  displayName: 'Asha',
  tenant: { id: 't', slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey, scopeLevel: 'GROUP', scopeId: 't', scopeName: 'Nilgiri' }],
  children: [],
  policyVersion: 'test',
});
const campuses: Campus[] = [
  { id: 'n', code: 'N', name: 'North Campus', timeZone: 'Asia/Kolkata', status: 'ACTIVE', legalEntity: 'X' },
];
const ravi = {
  personId: 'r1',
  displayName: 'Ravi Kumar',
  email: 'ravi@school.example',
  status: 'ACTIVE',
  roles: [
    {
      grantId: 'g1',
      roleKey: 'teacher',
      scopeLevel: 'CAMPUS',
      scopeId: 'n',
      scopeName: 'North Campus',
      validFrom: '2026-11-01T04:30:00Z',
      reason: 'x',
    },
  ],
};
const server = (url: string, init: { method?: string }) => {
  if (url.endsWith('/staff') && init.method === 'POST')
    return { status: 201, body: { ...ravi, personId: 'n1' } };
  if (url.endsWith('/staff')) return { body: [ravi] };
  if (url.endsWith('/roles') && init.method === 'POST') return { status: 201, body: ravi };
  if (url.endsWith('/end')) return { body: ravi };
  return { status: 404, body: { code: 'not_found', detail: 'no' } };
};

describe('StaffPage', () => {
  it('is for group administrators only', () => {
    expect(canManageStaff(person('group_admin'))).toBe(true);
    expect(canManageStaff(person('campus_admin'))).toBe(false);
  });

  it('shows a refusal to anyone else without asking the server', () => {
    const calls = stubFetch(server);
    renderWithApp(<StaffPage me={person('teacher')} campuses={campuses} />);
    expect(screen.getByText(/Only the school group's administrators/)).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('adds a person, gives a campus role with a reason and ends a role', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    renderWithApp(<StaffPage me={person('group_admin')} campuses={campuses} />);
    expect(await screen.findByText('Ravi Kumar')).toBeInTheDocument();
    expect(screen.getByText(/since 1 Nov 2026/)).toBeInTheDocument();

    await user.type(screen.getByLabelText(/First name/), 'Meera');
    await user.type(screen.getByLabelText(/Work email/), 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Add person' }));
    expect(screen.getByText('Enter a valid email address.')).toBeInTheDocument();
    await user.clear(screen.getByLabelText(/Work email/));
    await user.type(screen.getByLabelText(/Work email/), 'meera@school.example');
    await user.click(screen.getByRole('button', { name: 'Add person' }));
    expect(await screen.findByText('Added. Now give them a role.')).toBeInTheDocument();

    await user.click(screen.getByText('Give Ravi Kumar a role'));
    await user.type(screen.getByLabelText(/Reason for Ravi Kumar/), 'short');
    await user.click(screen.getByRole('button', { name: 'Give role' }));
    expect(screen.getByRole('alert')).toHaveTextContent('at least 10 characters');
    await user.clear(screen.getByLabelText(/Reason for Ravi Kumar/));
    await user.type(screen.getByLabelText(/Reason for Ravi Kumar/), 'Joins as class teacher');
    await user.click(screen.getByRole('button', { name: 'Give role' }));
    const grant = calls.find((c) => c.url.endsWith('/staff/r1/roles') && c.init.method === 'POST');
    expect(JSON.parse(String(grant?.init.body))).toEqual({
      roleKey: 'teacher',
      scopeLevel: 'CAMPUS',
      scopeId: 'n',
      reason: 'Joins as class teacher',
    });

    await user.click(screen.getByRole('button', { name: 'End Teacher for Ravi Kumar' }));
    await user.type(screen.getByLabelText(/Why does this role end/), 'Moved to another school');
    await user.click(screen.getByRole('button', { name: 'End role' }));
    expect(calls.some((c) => c.url.endsWith('/staff/r1/roles/g1/end'))).toBe(true);
  });
});
