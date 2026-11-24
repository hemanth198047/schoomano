import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import { NEXT_STATUS, buildBatch, loadRoster } from './roster';
import { memoryStore } from './storage';
import type { Roster } from './types';

const roster: Roster = {
  sectionId: 's1',
  sectionLabel: 'Grade 5 A',
  date: '2026-10-05',
  openConflicts: 0,
  students: [
    { studentId: 'a', schoolRef: '1', name: 'Anaya', status: null, version: null },
    { studentId: 'b', schoolRef: '2', name: 'Bhavya', status: 'PRESENT', version: 3 },
    { studentId: 'c', schoolRef: '3', name: 'Chetan', status: 'ABSENT', version: 2 },
  ],
};

describe('roster', () => {
  it('uses the saved copy only when the network is down', async () => {
    const store = memoryStore();
    const online = { request: () => Promise.resolve(roster) } as never;
    expect(await loadRoster(online, store, 's1', '2026-10-05')).toEqual({ roster, fromCache: false });
    const offline = {
      request: () => Promise.reject(new ApiError(0, 'network_unreachable', 'down')),
    } as never;
    expect(await loadRoster(offline, store, 's1', '2026-10-05')).toEqual({ roster, fromCache: true });
    expect(await loadRoster(offline, store, 's2', '2026-10-05')).toBeNull();
  });

  it('does not hide real refusals behind the cache', async () => {
    const store = memoryStore();
    await store.set('roster-s1-2026-10-05', JSON.stringify(roster));
    const denied = { request: () => Promise.reject(new ApiError(404, 'not_found', 'no')) } as never;
    await expect(loadRoster(denied, store, 's1', '2026-10-05')).rejects.toMatchObject({ status: 404 });
  });

  it('sends only changed marks, with the version each was seen at', () => {
    const batch = buildBatch(
      roster,
      { a: 'ABSENT', b: 'PRESENT', c: 'LATE' },
      new Date('2026-10-05T03:30:00Z'),
    )!;
    expect(batch.marks.map((m) => [m.studentId, m.status, m.baseVersion])).toEqual([
      ['a', 'ABSENT', 0],
      ['c', 'LATE', 2],
    ]);
    expect(batch.marks[0]!.markedAt).toBe('2026-10-05T03:30:00.000Z');
    expect(buildBatch(roster, { b: 'PRESENT' }, new Date())).toBeNull();
  });

  it('cycles through the statuses a teacher needs', () => {
    expect(NEXT_STATUS.NONE).toBe('PRESENT');
    let s = NEXT_STATUS.NONE;
    const seen = [s];
    for (let i = 0; i < 4; i++) seen.push((s = NEXT_STATUS[s]));
    expect(seen).toEqual(['PRESENT', 'ABSENT', 'LATE', 'LEAVE', 'PRESENT']);
  });
});
