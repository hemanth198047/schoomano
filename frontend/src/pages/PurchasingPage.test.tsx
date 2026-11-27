import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { PurchasingPage, canApprove, canPay, canSeePurchasing } from './PurchasingPage';

const person = (roleKey: string, displayName: string): Me => ({
  personId: 'p1',
  displayName,
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
const budget = { id: 'b1', category: 'IT Lab', amountPaise: 1000000, committedPaise: 600000, paidPaise: 0 };
const request = (status: string) => ({
  id: 'r1',
  budgetId: 'b1',
  category: 'IT Lab',
  vendor: null,
  title: 'Ten microscopes',
  description: null,
  amountPaise: 600000,
  status,
  requestedBy: 'Ravi',
  requestedAt: '2026-11-20T09:00:00Z',
  needsPrincipal: false,
  paidPaise: null,
  paymentReference: null,
  workflowRequestId: null as string | null,
  waitingFor: null as string | null,
  history: [],
});

describe('PurchasingPage', () => {
  it('knows who does what', () => {
    expect(canSeePurchasing(person('teacher', 'T'))).toBe(true);
    expect(canSeePurchasing(person('nurse', 'N'))).toBe(false);
    expect(canApprove(person('campus_admin', 'C'))).toBe(true);
    expect(canApprove(person('teacher', 'T'))).toBe(false);
    expect(canPay(person('finance_officer', 'F'))).toBe(true);
  });

  it('lets an approver approve, and a requester only withdraw', async () => {
    let status = 'SUBMITTED';
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/decision') && init.method === 'POST') {
        status = 'APPROVED';
        return { body: request(status) };
      }
      if (url.includes('/budgets')) return { body: [budget] };
      if (url.includes('/vendors')) return { body: [] };
      if (url.includes('/purchase-requests')) return { body: [request(status)] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    const { container, unmount } = renderWithApp(
      <PurchasingPage me={person('campus_admin', 'Chitra')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('Ten microscopes')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Withdraw/ })).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await user.click(await screen.findByRole('button', { name: 'Approve Ten microscopes' }));
    expect(await screen.findByText('Approved')).toBeInTheDocument();
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toMatchObject({
      decision: 'APPROVE',
    });
    unmount();

    status = 'SUBMITTED';
    renderWithApp(<PurchasingPage me={person('teacher', 'Ravi')} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByRole('button', { name: 'Withdraw Ten microscopes' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Approve/ })).not.toBeInTheDocument();
  });

  it('lets a teacher raise a request and finance record the payment', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/purchase-requests') && init.method === 'POST')
        return { status: 201, body: request('SUBMITTED') };
      if (url.endsWith('/payment') && init.method === 'POST') return { body: request('PAID') };
      if (url.includes('/budgets')) return { body: [budget] };
      if (url.includes('/vendors')) return { body: [{ id: 'v1', name: 'Lab Supplies', contact: null }] };
      if (url.includes('/purchase-requests')) return { body: [request('APPROVED')] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    const { unmount } = renderWithApp(
      <PurchasingPage me={person('teacher', 'Ravi')} campuses={campuses} selectedCampusId="n" />,
    );
    await user.selectOptions(await screen.findByLabelText('Budget'), 'b1');
    await user.type(screen.getByLabelText(/What is needed/), 'Projector');
    await user.type(screen.getByLabelText(/Amount \(₹\)/), '4,500');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    await screen.findByText('Ten microscopes');
    const post = calls.find((c) => c.init.method === 'POST' && c.url.endsWith('/purchase-requests'));
    expect(JSON.parse(String(post?.init.body))).toMatchObject({
      title: 'Projector',
      amountPaise: 450000,
      budgetId: 'b1',
    });
    unmount();

    renderWithApp(
      <PurchasingPage me={person('finance_officer', 'Fin')} campuses={campuses} selectedCampusId="n" />,
    );
    await user.type(await screen.findByLabelText(/Payment reference/), 'NEFT-9');
    await user.click(screen.getByRole('button', { name: 'Record payment for Ten microscopes' }));
    const pay = calls.find((c) => c.url.endsWith('/payment'));
    expect(JSON.parse(String(pay?.init.body))).toMatchObject({ amountPaise: 600000, reference: 'NEFT-9' });
  });

  it('sends a request through an approval chain and keeps direct approval off it', async () => {
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.endsWith('/purchase-requests') && init.method === 'POST')
        return { status: 201, body: request('SUBMITTED') };
      if (url.includes('/workflow-chains'))
        return {
          body: [
            {
              id: 'c1',
              chainKey: 'buy',
              version: 1,
              name: 'Big buys',
              status: 'PUBLISHED',
              steps: [{ position: 1, roleKey: 'principal', label: 'Principal' }],
            },
          ],
        };
      if (url.includes('/budgets')) return { body: [budget] };
      if (url.includes('/vendors')) return { body: [] };
      if (url.includes('/purchase-requests'))
        return { body: [{ ...request('SUBMITTED'), workflowRequestId: 'w1', waitingFor: 'Principal' }] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const user = userEvent.setup();
    renderWithApp(
      <PurchasingPage me={person('campus_admin', 'Chitra')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText(/waiting for Principal/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Approve/ })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Budget'), 'b1');
    await user.selectOptions(screen.getByLabelText(/Approval chain/), 'c1');
    await user.type(screen.getByLabelText(/What is needed/), 'Projector');
    await user.type(screen.getByLabelText(/Amount \(₹\)/), '4,500');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    const post = calls.find((c) => c.init.method === 'POST' && c.url.endsWith('/purchase-requests'));
    expect(JSON.parse(String(post?.init.body))).toMatchObject({ chainId: 'c1', title: 'Projector' });
  });
});
