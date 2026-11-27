import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { VisitorsPage, canRecordVisitors, canSeeVisitors } from './VisitorsPage';

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
const visit = {
  id: 'v1',
  visitorName: 'Asha Rao',
  organisation: 'Books Ltd',
  phone: null,
  purpose: 'Book fair',
  host: 'Ravi Teacher',
  badgeNo: 'B-7',
  checkedInAt: '2026-11-27T04:30:00Z',
  checkedInBy: 'Front desk',
  checkedOutAt: null,
  inside: true,
};
const server = (url: string, init: { method?: string }) => {
  if (url.endsWith('/checkout')) return { body: { ...visit, inside: false } };
  if (url.includes('/visits') && init.method === 'POST') return { status: 201, body: visit };
  if (url.includes('/visits')) return { body: [visit] };
  if (url.endsWith('/visit-hosts'))
    return { body: [{ personId: 'p2', name: 'Ravi Teacher', designation: 'Teacher' }] };
  return { status: 404, body: { code: 'not_found', detail: 'no' } };
};

describe('VisitorsPage', () => {
  it('knows who records and who only looks', () => {
    expect(canRecordVisitors(person('campus_admin'))).toBe(true);
    expect(canRecordVisitors(person('front_desk'))).toBe(true);
    expect(canRecordVisitors(person('principal'))).toBe(false);
    expect(canSeeVisitors(person('principal'))).toBe(true);
    expect(canSeeVisitors(person('teacher'))).toBe(false);
  });

  it('checks a visitor in with a host and out again', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <VisitorsPage me={person('campus_admin')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('Asha Rao')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await user.type(screen.getByLabelText('Visitor name'), 'Meena Iyer');
    await user.type(screen.getByLabelText('Purpose of visit'), 'Parent meeting');
    await user.selectOptions(await screen.findByLabelText(/Here to see/), 'p2');
    await user.click(screen.getByRole('button', { name: 'Check in' }));
    expect(
      JSON.parse(String(calls.find((c) => c.init.method === 'POST' && c.url.endsWith('/visits'))?.init.body)),
    ).toEqual({
      visitorName: 'Meena Iyer',
      purpose: 'Parent meeting',
      hostPersonId: 'p2',
    });
    await user.click(screen.getByRole('button', { name: 'Check out Asha Rao' }));
    expect(calls.some((c) => c.url.endsWith('/visits/v1/checkout'))).toBe(true);
  });

  it('lets the principal look but not record', async () => {
    stubFetch(server);
    renderWithApp(<VisitorsPage me={person('principal')} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('Asha Rao')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Check out/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Check a visitor in')).not.toBeInTheDocument();
  });
});
