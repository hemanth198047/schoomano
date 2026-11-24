import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import type { AttendanceBatchResult } from '../api/types';
import { flush, isRetryable, newBatch, type PendingBatch } from './attendanceQueue';

const ok: AttendanceBatchResult = { results: [], conflicts: 0, replayed: false };
const mark = {
  studentId: 's1',
  status: 'PRESENT' as const,
  baseVersion: 0,
  markedAt: '2026-10-05T03:00:00Z',
};
const batch = (n: number): PendingBatch => ({
  ...newBatch('sec', '2026-10-05', [mark]),
  key: `key-${n}0000000`,
});

describe('attendance queue', () => {
  it('gives every batch its own key', () => {
    expect(newBatch('a', '2026-10-05', [mark]).key).not.toBe(newBatch('a', '2026-10-05', [mark]).key);
  });

  it('treats no-network and brief outages as retryable, refusals as not', () => {
    expect(isRetryable(new ApiError(0, 'network_unreachable', 'x', null))).toBe(true);
    expect(isRetryable(new ApiError(503, 'unavailable', 'x', null))).toBe(true);
    expect(isRetryable(new ApiError(409, 'attendance_rejected', 'x', null))).toBe(false);
    expect(isRetryable(new Error('boom'))).toBe(false);
  });

  it('sends in order and empties the queue', async () => {
    const seen: string[] = [];
    const out = await flush([batch(1), batch(2)], async (b) => {
      seen.push(b.key);
      return ok;
    });
    expect(seen).toEqual(['key-10000000', 'key-20000000']);
    expect(out.remaining).toEqual([]);
    expect(out.rejected).toBeNull();
  });

  it('stops at the first network failure and keeps order and keys for the retry', async () => {
    const out = await flush([batch(1), batch(2), batch(3)], async (b) => {
      if (b.key === 'key-20000000') throw new ApiError(0, 'network_unreachable', 'offline', null);
      return ok;
    });
    expect(out.results).toHaveLength(1);
    expect(out.remaining.map((b) => b.key)).toEqual(['key-20000000', 'key-30000000']);
  });

  it('drops a refused batch so it cannot block the ones behind it', async () => {
    const out = await flush([batch(1), batch(2)], async (b) => {
      if (b.key === 'key-10000000') throw new ApiError(409, 'attendance_rejected', 'Future day', null);
      return ok;
    });
    expect(out.rejected?.error.code).toBe('attendance_rejected');
    expect(out.remaining.map((b) => b.key)).toEqual(['key-20000000']);
  });
});
