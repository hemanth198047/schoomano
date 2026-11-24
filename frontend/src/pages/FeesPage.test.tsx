import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { FeesPage, canManageFees, isFeeStaff, payingChildren } from './FeesPage';

const base: Me = {
  personId: 'p1',
  displayName: 'Finn',
  tenant: { id: 't', slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey: 'finance_officer', scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'North' }],
  children: [],
  policyVersion: 'test',
};
const parent: Me = {
  ...base,
  roles: [],
  children: [
    {
      studentId: 'st1',
      displayName: 'Fiona',
      schoolRef: 'S1',
      capabilities: ['PAY_FEES', 'RECEIVE_NOTICES'],
    },
    { studentId: 'st2', displayName: 'Noah', schoolRef: 'S2', capabilities: ['RECEIVE_NOTICES'] },
  ],
};
const campuses: Campus[] = [
  { id: 'n', code: 'N', name: 'North Campus', timeZone: 'Asia/Kolkata', status: 'ACTIVE', legalEntity: 'X' },
];
const invoice = {
  id: 'i1',
  number: 'INV-2026-27-000001',
  status: 'ISSUED',
  studentId: 'st1',
  studentName: 'Fiona Synthetic',
  dueOn: '2099-04-30',
  totalPaise: 3000000,
  paidPaise: 500000,
  overdue: false,
};
const structure = {
  campusId: 'n',
  campusName: 'North',
  years: [],
  year: { id: 'y1', label: '2026-27', startsOn: '2026-04-01', endsOn: '2027-03-31', status: 'ACTIVE' },
  calendar: [],
  programmes: [],
};

describe('FeesPage', () => {
  it('knows who sees the office screens and who may manage', () => {
    expect(isFeeStaff(base)).toBe(true);
    expect(canManageFees(base)).toBe(true);
    expect(isFeeStaff(parent)).toBe(false);
    expect(payingChildren(parent).map((c) => c.studentId)).toEqual(['st1']);
  });

  it('shows a parent only the children they can pay for, and pays through the sandbox with an idempotency key', async () => {
    const calls = stubFetch((url, init) => {
      if (url.endsWith('/students/st1/invoices')) return { body: [invoice] };
      if (url.endsWith('/pay') && init.method === 'POST')
        return {
          status: 201,
          body: {
            paymentId: 'p',
            orderId: 'order_1',
            amountPaise: 2500000,
            provider: 'sandbox',
            sandbox: true,
          },
        };
      if (url.endsWith('/sandbox/complete')) return { body: { outcome: 'APPLIED' } };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(<FeesPage me={parent} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('INV-2026-27-000001')).toBeInTheDocument();
    expect(screen.queryByText('Noah')).not.toBeInTheDocument();
    expect(screen.getByText(/₹5,000\.00 paid of ₹30,000\.00/)).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: /Pay ₹25,000\.00 for INV-2026-27-000001/ }));
    expect(await screen.findByText('Test payment of ₹25,000.00 recorded.')).toBeInTheDocument();
    const pay = calls.find((c) => c.url.endsWith('/pay'))!;
    expect(new Headers(pay.init.headers).get('Idempotency-Key')).toMatch(/^web-/);
  });

  it('lets the office record a counter payment, in rupees turned into paise', async () => {
    const detail = {
      invoice,
      lines: [{ description: 'Tuition', amountPaise: 3000000 }],
      payments: [],
    };
    const calls = stubFetch((url, init) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/fees/reconciliation'))
        return { body: { days: [], exceptions: [], collectedPaise: 0 } };
      if (url.endsWith('/payments/offline') && init.method === 'POST') return { status: 201, body: detail };
      if (url.endsWith('/invoices/i1')) return { body: detail };
      if (url.includes('/invoices')) return { body: [invoice] };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(<FeesPage me={base} campuses={campuses} selectedCampusId="n" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Open INV-2026-27-000001' }));
    const record = await screen.findByRole('button', { name: 'Record payment' });
    expect(record).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/^Amount \(₹\)/), '1,250.50');
    expect(record).toBeEnabled();
    await userEvent.selectOptions(screen.getByLabelText(/^Method/), 'CHEQUE');
    expect(record).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/^Reference/), '000123');
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(record);
    expect(await screen.findByText('Recorded ₹1,250.50.')).toBeInTheDocument();
    const post = calls.find((c) => c.url.endsWith('/payments/offline'))!;
    expect(JSON.parse(String(post.init.body))).toEqual({
      amountPaise: 125050,
      method: 'CHEQUE',
      reference: '000123',
    });
  });
});
