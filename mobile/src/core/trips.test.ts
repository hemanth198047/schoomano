import { describe, expect, it } from 'vitest';
import { consentLine, tripLine } from './trips';
import type { ChildTrip } from './types';

const base: ChildTrip = {
  tripId: 't',
  name: 'Museum visit',
  destination: 'Science Museum',
  tripDate: '2026-12-20',
  costPaise: 25000,
  consentDeadline: '2026-12-10',
  status: 'PLANNED',
  consent: 'NONE',
  canDecide: true,
};

describe('trips', () => {
  it('describes the trip and its cost', () => {
    expect(tripLine(base)).toBe('Museum visit to Science Museum on 2026-12-20 (₹250.00)');
    expect(tripLine({ ...base, costPaise: 0 })).toBe('Museum visit to Science Museum on 2026-12-20 (free)');
  });
  it('asks for an answer while there is time', () => {
    expect(consentLine(base)).toBe('Your answer is needed by 2026-12-10.');
  });
  it('lets the parent change their mind until the deadline', () => {
    expect(consentLine({ ...base, consent: 'GIVEN' })).toBe(
      'You agreed. You can change your answer until 2026-12-10.',
    );
    expect(consentLine({ ...base, consent: 'DECLINED' })).toBe(
      'You said no. You can change your answer until 2026-12-10.',
    );
  });
  it('says so when it is too late', () => {
    expect(consentLine({ ...base, canDecide: false })).toBe(
      'The time to answer has passed. Please contact the school.',
    );
    expect(consentLine({ ...base, consent: 'GIVEN', canDecide: false })).toBe('You agreed.');
    expect(consentLine({ ...base, status: 'COMPLETED', consent: 'GIVEN', canDecide: false })).toBe(
      'You agreed. The trip is over.',
    );
  });
});
