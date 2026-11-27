import { describe, expect, it } from 'vitest';
import { activityLine, leaderLine } from './activities';
import type { ChildActivity } from './types';

const base: ChildActivity = {
  activityId: 'a',
  name: 'Chess Club',
  kind: 'CLUB',
  leadName: null,
  joinedOn: '2026-11-01',
  sessions: 5,
  attended: 3,
};

describe('activities', () => {
  it('says how many sessions the child came to', () => {
    expect(activityLine(base)).toBe('Chess Club: came to 3 of 5 sessions');
    expect(activityLine({ ...base, sessions: 1, attended: 1 })).toBe('Chess Club: came to 1 of 1 session');
  });
  it('says so when nothing has happened yet', () => {
    expect(activityLine({ ...base, sessions: 0, attended: 0 })).toBe('Chess Club: no sessions yet');
  });
  it('names the leader only when there is one', () => {
    expect(leaderLine(base)).toBeNull();
    expect(leaderLine({ ...base, leadName: 'Mr Rao' })).toBe('Led by Mr Rao');
  });
});
