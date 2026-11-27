import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { VisitorsPage, canExpectVisitors, canRecordVisitors, canSeeVisitors } from './VisitorsPage';

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
const guest = {
  id: 'e1',
  visitorName: 'Dev Patel',
  organisation: null,
  purpose: 'Demo',
  expectedOn: '2026-11-27',
  host: 'Ravi Teacher',
  status: 'EXPECTED',
};
const server = (url: string, init: { method?: string }) => {
  if (url.endsWith('/arrive')) return { body: visit };
  if (url.endsWith('/cancel')) return { body: { ...guest, status: 'CANCELLED' } };
  if (url.includes('/expected-visitors') && init.method === 'POST') return { status: 201, body: guest };
  if (url.includes('/expected-visitors')) return { body: [guest] };
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
    expect(canSeeVisitors(person('teacher'))).toBe(true);
    expect(canSeeVisitors(person('guardian'))).toBe(false);
    expect(canExpectVisitors(person('front_desk'))).toBe(false);
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

  it('lets the gate check in an announced guest', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    renderWithApp(<VisitorsPage me={person('front_desk')} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('Dev Patel')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Badge for Dev Patel'), 'B-3');
    await user.click(screen.getByRole('button', { name: 'Check in Dev Patel' }));
    expect(JSON.parse(String(calls.find((c) => c.url.endsWith('/arrive'))?.init.body))).toEqual({
      badgeNo: 'B-3',
    });
    expect(screen.queryByText('Announce a guest')).not.toBeInTheDocument();
  });

  it('lets a teacher announce and cancel a guest but see no log', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    renderWithApp(<VisitorsPage me={person('teacher')} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByRole('button', { name: 'Cancel Dev Patel' })).toBeInTheDocument();
    expect(screen.queryByText('Inside now')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Guest name'), 'Meena Iyer');
    await user.type(screen.getByLabelText('Why they are coming'), 'Parent talk');
    await user.type(screen.getByLabelText('Expected on'), '2026-12-02');
    await user.click(screen.getByRole('button', { name: 'Announce guest' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toEqual({
      visitorName: 'Meena Iyer',
      purpose: 'Parent talk',
      expectedOn: '2026-12-02',
    });
    await user.click(screen.getByRole('button', { name: 'Cancel Dev Patel' }));
    expect(calls.some((c) => c.url.endsWith('/expected-visitors/e1/cancel'))).toBe(true);
  });
});
