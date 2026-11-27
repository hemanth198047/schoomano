import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { CompliancePage, canManageCompliance, canSeeCompliance } from './CompliancePage';

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
const fire = {
  id: 'i1',
  title: 'Fire certificate',
  category: 'FIRE_SAFETY',
  ownerName: 'Estates head',
  repeatMonths: 12,
  nextDueOn: '2026-11-20',
  status: 'ACTIVE',
  standing: 'OVERDUE',
  lastDoneOn: null,
  lastReference: null,
  evidenceCount: 0,
};
const server = (url: string, init: { method?: string }) => {
  if (url.endsWith('/evidence') && init.method === 'POST')
    return { status: 201, body: { ...fire, standing: 'CURRENT' } };
  if (url.endsWith('/evidence')) return { body: [] };
  if (url.endsWith('/compliance-items') && init.method === 'POST') return { status: 201, body: fire };
  if (url.endsWith('/compliance-items')) return { body: [fire] };
  return { status: 404, body: { code: 'not_found', detail: 'no' } };
};

describe('CompliancePage', () => {
  it('knows who sees and manages', () => {
    expect(canSeeCompliance(person('principal'))).toBe(true);
    expect(canSeeCompliance(person('teacher'))).toBe(false);
    expect(canManageCompliance(person('principal'))).toBe(false);
    expect(canManageCompliance(person('campus_admin'))).toBe(true);
  });

  it('shows what is overdue in words and lets the office add an item', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <CompliancePage me={person('campus_admin')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('Overdue')).toBeInTheDocument();
    expect(screen.getByText(/never done/)).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await user.type(screen.getByLabelText('What must be kept up to date'), 'Lift inspection');
    await user.type(screen.getByLabelText('Owner'), 'Estates head');
    await user.type(screen.getByLabelText('Repeats every (months)'), '6');
    await user.type(screen.getByLabelText('First due on'), '2027-01-15');
    await user.click(screen.getByRole('button', { name: 'Add to register' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toMatchObject({
      title: 'Lift inspection',
      ownerName: 'Estates head',
      repeatMonths: 6,
      firstDueOn: '2027-01-15',
    });
  });

  it('lets the principal record evidence but not add or retire', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    renderWithApp(<CompliancePage me={person('principal')} campuses={campuses} selectedCampusId="n" />);
    expect(screen.queryByText('Add to the register')).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Open Fire certificate' }));
    expect(screen.queryByRole('button', { name: /Retire/ })).not.toBeInTheDocument();
    await user.type(await screen.findByLabelText('Date done for Fire certificate'), '2026-11-26');
    await user.type(screen.getByLabelText('Certificate or reference'), 'FS-114');
    await user.click(screen.getByRole('button', { name: 'Record evidence for Fire certificate' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toEqual({
      doneOn: '2026-11-26',
      reference: 'FS-114',
    });
  });
});
