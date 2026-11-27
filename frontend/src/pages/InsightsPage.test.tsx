import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Campus, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { InsightsPage, canSeeInsights } from './InsightsPage';

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
const overview = {
  yearId: 'y1',
  yearLabel: '2026-27',
  enrolment: { enrolled: 120, withdrawn: 2, transferredOut: 1, completed: 0 },
  attendance: {
    from: '2026-11-01',
    to: '2026-11-27',
    sessions: 40,
    marks: 1000,
    attended: 940,
    absent: 60,
    percent: 94,
    days: [{ date: '2026-11-26', marks: 100, attended: 90, percent: 90 }],
    sections: [{ section: 'Grade 5 A', marks: 100, attended: 90, percent: 90 }],
  },
  fees: {
    invoices: 50,
    billedPaise: 5000000,
    collectedPaise: 3000000,
    outstandingPaise: 2000000,
    collectedPercent: 60,
    overdueInvoices: 5,
    overduePaise: 400000,
  },
};

describe('InsightsPage', () => {
  it('is for the principal and the office only', () => {
    expect(canSeeInsights(person('principal'))).toBe(true);
    expect(canSeeInsights(person('teacher'))).toBe(false);
  });

  it('shows enrolment, attendance by class and day, and fees in words and numbers', async () => {
    const calls = stubFetch((url) => {
      if (url.includes('/academic-structure')) return { body: structure };
      if (url.includes('/analytics')) return { body: overview };
      return { status: 404, body: { code: 'not_found', detail: 'no' } };
    });
    const { container } = renderWithApp(
      <InsightsPage me={person('principal')} campuses={campuses} selectedCampusId="n" />,
    );
    expect(await screen.findByText('120')).toBeInTheDocument();
    expect(screen.getByText('Grade 5 A')).toBeInTheDocument();
    expect(screen.getByText(/940 of 1000 marks across 40 class registers/)).toBeInTheDocument();
    expect(screen.getByText(/50 invoices billed ₹50,000.00/)).toBeInTheDocument();
    expect(calls.some((c) => /analytics\?from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/.test(c.url))).toBe(
      true,
    );
    expect(await axeViolations(container)).toEqual([]);
  });

  it('shows nothing to teachers and makes no request', () => {
    const calls = stubFetch(() => ({ body: {} }));
    renderWithApp(<InsightsPage me={person('teacher')} campuses={campuses} selectedCampusId="n" />);
    expect(screen.getByText('Not available')).toBeInTheDocument();
    expect(calls.length).toBe(0);
  });
});
