import { describe, expect, it } from 'vitest';
import { boardingLines, indiaClock, routeLine, shortTime } from './transport';
import type { ChildTransport } from './types';

describe('transport', () => {
  it('reads India clock time from an instant', () => {
    expect(indiaClock('2026-11-25T01:42:00Z')).toBe('07:12');
    expect(indiaClock('2026-11-25T20:00:00Z')).toBe('01:30');
  });

  it('describes the day in order, in plain words', () => {
    const lines = boardingLines([
      {
        studentId: 's',
        trip: 'AFTERNOON',
        event: 'ALIGHTED',
        tripDate: '2026-11-25',
        recordedAt: '2026-11-25T10:50:00Z',
      },
      {
        studentId: 's',
        trip: 'MORNING',
        event: 'BOARDED',
        tripDate: '2026-11-25',
        recordedAt: '2026-11-25T01:42:00Z',
      },
    ]);
    expect(lines).toEqual(['Got on the morning bus at 07:12', 'Got off the afternoon bus at 16:20']);
    expect(boardingLines([])).toEqual([]);
  });

  it('names the route and stop', () => {
    const t: ChildTransport = {
      studentId: 's',
      route: 'North 1',
      stop: 'Market Corner',
      pickupTime: '07:25:00',
      vehicle: 'KA01AB1234',
      driverName: 'Ramesh',
      driverPhone: null,
      today: [],
    };
    expect(shortTime(t.pickupTime)).toBe('07:25');
    expect(routeLine(t)).toBe('North 1: pick-up at Market Corner, 07:25');
  });
});
