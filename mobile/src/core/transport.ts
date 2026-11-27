import type { BoardingEvent, ChildTransport } from './types';

/** "07:10:00" becomes "07:10". */
export const shortTime = (t: string): string => t.slice(0, 5);

/** The clock time of an instant in India (UTC+5:30, no daylight saving), as HH:MM. */
export function indiaClock(iso: string): string {
  const minutes = Math.floor(Date.parse(iso) / 60_000) + 330;
  const m = ((minutes % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

/** One plain line per boarding event today, in the order they happened. */
export function boardingLines(events: BoardingEvent[]): string[] {
  return [...events]
    .sort((a, b) => Date.parse(a.recordedAt) - Date.parse(b.recordedAt))
    .map((e) => {
      const trip = e.trip === 'MORNING' ? 'morning' : 'afternoon';
      return e.event === 'BOARDED'
        ? `Got on the ${trip} bus at ${indiaClock(e.recordedAt)}`
        : `Got off the ${trip} bus at ${indiaClock(e.recordedAt)}`;
    });
}

export function routeLine(t: ChildTransport): string {
  return `${t.route}: pick-up at ${t.stop}, ${shortTime(t.pickupTime)}`;
}
