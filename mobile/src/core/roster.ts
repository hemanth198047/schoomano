import { ApiError, type Client } from './api';
import { newBatchKey, type PendingBatch, type PendingMark } from './offlineQueue';
import type { KeyValueStore } from './storage';
import type { AttendanceStatus, Roster } from './types';

const cacheKey = (sectionId: string, date: string) => `roster-${sectionId}-${date}`;

/**
 * The class list for a day. When the phone cannot reach the server the last copy it saw is used, so a teacher in a
 * dead spot can still mark. The copy carries the versions seen, which is what lets the server spot office changes.
 */
export async function loadRoster(
  client: Pick<Client, 'request'>,
  store: KeyValueStore,
  sectionId: string,
  date: string,
): Promise<{ roster: Roster; fromCache: boolean } | null> {
  try {
    const roster = await client.request<Roster>(`/api/v1/sections/${sectionId}/attendance?date=${date}`);
    await store.set(cacheKey(sectionId, date), JSON.stringify(roster));
    return { roster, fromCache: false };
  } catch (e) {
    if (!(e instanceof ApiError) || e.status !== 0) throw e;
    const raw = await store.get(cacheKey(sectionId, date));
    if (!raw) return null;
    return { roster: JSON.parse(raw) as Roster, fromCache: true };
  }
}

/** Only the marks the teacher actually changed, each remembering the version they were looking at. */
export function buildBatch(
  roster: Roster,
  chosen: Record<string, AttendanceStatus>,
  now: Date,
): PendingBatch | null {
  const marks: PendingMark[] = [];
  for (const s of roster.students) {
    const status = chosen[s.studentId];
    if (status && status !== s.status) {
      marks.push({
        studentId: s.studentId,
        status,
        baseVersion: s.version ?? 0,
        markedAt: now.toISOString(),
      });
    }
  }
  if (marks.length === 0) return null;
  return {
    key: newBatchKey(),
    sectionId: roster.sectionId,
    sectionLabel: roster.sectionLabel,
    date: roster.date,
    marks,
  };
}

export const NEXT_STATUS: Record<AttendanceStatus | 'NONE', AttendanceStatus> = {
  NONE: 'PRESENT',
  PRESENT: 'ABSENT',
  ABSENT: 'LATE',
  LATE: 'LEAVE',
  LEAVE: 'PRESENT',
};
