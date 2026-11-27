import type { ChildActivity } from './types';

/** "Chess Club: came to 3 of 5 sessions". */
export function activityLine(a: ChildActivity): string {
  if (a.sessions === 0) return `${a.name}: no sessions yet`;
  return `${a.name}: came to ${a.attended} of ${a.sessions} ${a.sessions === 1 ? 'session' : 'sessions'}`;
}

export const leaderLine = (a: ChildActivity): string | null => (a.leadName ? `Led by ${a.leadName}` : null);
