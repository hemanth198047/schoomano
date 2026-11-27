import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { WorkflowPage, canDecideWorkflow, canDesignWorkflow } from './WorkflowPage';

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
const steps = [
  {
    position: 1,
    roleKey: 'principal',
    label: 'Principal',
    decision: null,
    decidedBy: null,
    note: null,
    decidedAt: null,
  },
  {
    position: 2,
    roleKey: 'finance_officer',
    label: 'Finance',
    decision: null,
    decidedBy: null,
    note: null,
    decidedAt: null,
  },
];
const chain = {
  id: 'c1',
  chainKey: 'trip-spend',
  version: 1,
  name: 'Trip spend',
  status: 'PUBLISHED',
  steps,
};
const request = {
  id: 'r1',
  chainName: 'Trip spend',
  chainVersion: 1,
  subject: 'Museum bus',
  details: null,
  status: 'PENDING',
  currentStep: 1,
  waitingFor: 'Principal',
  requester: 'Ravi Teacher',
  mine: false,
  submittedAt: '2026-11-27T10:00:00Z',
  closedAt: null,
  steps,
};
const server = (url: string, init: { method?: string }) => {
  if (url.includes('workflow-requests?view=inbox')) return { body: [request] };
  if (url.includes('workflow-requests?view=mine'))
    return { body: [{ ...request, id: 'r2', subject: 'Lunch', mine: true }] };
  if (url.endsWith('/decision')) return { body: { ...request, status: 'APPROVED' } };
  if (url.endsWith('/withdraw')) return { body: { ...request, status: 'WITHDRAWN' } };
  if (url.endsWith('/workflow-chains') && init.method === 'POST') return { status: 201, body: chain };
  if (url.endsWith('/workflow-chains')) return { body: [chain] };
  return { status: 404, body: { code: 'not_found', detail: 'no' } };
};

describe('WorkflowPage', () => {
  it('knows who designs and decides', () => {
    expect(canDesignWorkflow(person('campus_admin'))).toBe(true);
    expect(canDesignWorkflow(person('principal'))).toBe(false);
    expect(canDecideWorkflow(person('teacher'))).toBe(false);
    expect(canDecideWorkflow(person('finance_officer'))).toBe(true);
  });

  it('lets an approver approve, and refuses a rejection without a reason', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    const { container } = renderWithApp(
      <WorkflowPage me={person('principal')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('Museum bus')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    expect(screen.queryByText('Approval chains')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reject Museum bus' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Say why');
    await user.click(screen.getByRole('button', { name: 'Approve Museum bus' }));
    expect(JSON.parse(String(calls.find((c) => c.url.endsWith('/decision'))?.init.body))).toEqual({
      decision: 'APPROVED',
    });
  });

  it('gives a teacher a way to ask and withdraw, but no inbox', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    renderWithApp(<WorkflowPage me={person('teacher')} campuses={campuses} selectedCampusId="n" />);
    expect(await screen.findByText('Lunch')).toBeInTheDocument();
    expect(screen.queryByText('Waiting for you')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('What you need approved'), 'Bus hire');
    await user.click(screen.getByRole('button', { name: 'Send for approval' }));
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toMatchObject({
      chainId: 'c1',
      subject: 'Bus hire',
    });
    await user.click(screen.getByRole('button', { name: 'Withdraw Lunch' }));
    expect(calls.some((c) => c.url.endsWith('/withdraw'))).toBe(true);
  });

  it('lets the office draft a chain from lines of role and label', async () => {
    const calls = stubFetch(server);
    const user = userEvent.setup();
    renderWithApp(<WorkflowPage me={person('campus_admin')} campuses={campuses} selectedCampusId="n" />);
    await user.type(await screen.findByLabelText('Chain key'), 'trip-spend');
    await user.type(screen.getByLabelText('Chain name'), 'Trip spend');
    await user.type(screen.getByLabelText('Steps'), 'principal: Principal{Enter}finance_officer: Finance');
    await user.click(screen.getByRole('button', { name: 'Save as draft' }));
    expect(
      JSON.parse(
        String(calls.find((c) => c.url.endsWith('/workflow-chains') && c.init.method === 'POST')?.init.body),
      ),
    ).toEqual({
      chainKey: 'trip-spend',
      name: 'Trip spend',
      steps: [
        { roleKey: 'principal', label: 'Principal' },
        { roleKey: 'finance_officer', label: 'Finance' },
      ],
    });
  });
});
