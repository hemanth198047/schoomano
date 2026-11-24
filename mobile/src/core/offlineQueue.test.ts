import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import { createQueue, isRetryable, newBatchKey, type PendingBatch } from './offlineQueue';
import { memoryStore } from './storage';
import type { BatchResult } from './types';

const batch = (key: string): PendingBatch => ({
  key,
  sectionId: 's1',
  sectionLabel: 'Grade 5 A',
  date: '2026-10-05',
  marks: [{ studentId: 'st1', status: 'ABSENT', baseVersion: 0, markedAt: '2026-10-05T03:30:00Z' }],
});
const ok: BatchResult = { results: [], conflicts: 0, replayed: false };

describe('offline attendance queue', () => {
  it('survives the app being closed: a new queue over the same storage still has the batches', async () => {
    const store = memoryStore();
    await createQueue(store).enqueue(batch('k1'));
    await createQueue(store).enqueue(batch('k2'));
    const reopened = await createQueue(store).snapshot();
    expect(reopened.pending.map((b) => b.key)).toEqual(['k1', 'k2']);
  });

  it('sends oldest first and removes only what was sent', async () => {
    const q = createQueue(memoryStore());
    await q.enqueue(batch('k1'));
    await q.enqueue(batch('k2'));
    const order: string[] = [];
    const out = await q.flush((b) => {
      order.push(b.key);
      return Promise.resolve(ok);
    });
    expect(order).toEqual(['k1', 'k2']);
    expect(out.state.pending).toEqual([]);
    expect((await q.snapshot()).pending).toEqual([]);
  });

  it('stops at the first network failure and keeps order and keys for the retry', async () => {
    const store = memoryStore();
    const q = createQueue(store);
    await q.enqueue(batch('k1'));
    await q.enqueue(batch('k2'));
    const out = await q.flush((b) =>
      b.key === 'k1' ? Promise.resolve(ok) : Promise.reject(new ApiError(0, 'network_unreachable', 'down')),
    );
    expect(out.sent.map((s) => s.batch.key)).toEqual(['k1']);
    expect(out.state.pending.map((b) => b.key)).toEqual(['k2']);
    const again = await createQueue(store).snapshot();
    expect(again.pending.map((b) => b.key)).toEqual(['k2']);
  });

  it('moves a refused batch aside, keeps it visible, and carries on with the rest', async () => {
    const q = createQueue(memoryStore());
    await q.enqueue(batch('bad'));
    await q.enqueue(batch('good'));
    const out = await q.flush((b) =>
      b.key === 'bad'
        ? Promise.reject(new ApiError(400, 'attendance_rejected', 'Not in this class'))
        : Promise.resolve(ok),
    );
    expect(out.state.pending).toEqual([]);
    expect(out.state.rejected).toEqual([
      { batch: expect.objectContaining({ key: 'bad' }), reason: 'Not in this class' },
    ]);
    await q.dismissRejected('bad');
    expect((await q.snapshot()).rejected).toEqual([]);
  });

  it('does not lose a batch added while a send is in flight', async () => {
    const q = createQueue(memoryStore());
    await q.enqueue(batch('k1'));
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => (release = r));
    const flushing = q.flush(async () => {
      await gate;
      return ok;
    });
    const adding = q.enqueue(batch('k2'));
    release();
    await Promise.all([flushing, adding]);
    expect((await q.snapshot()).pending.map((b) => b.key)).toEqual(['k2']);
  });

  it('starts empty when the stored file is damaged', async () => {
    const store = memoryStore();
    await store.set('attendance-queue-v1', '{not json');
    expect(await createQueue(store).snapshot()).toEqual({ pending: [], rejected: [] });
  });

  it('classifies retryable failures and makes distinct keys', () => {
    expect(isRetryable(new ApiError(0, 'network_unreachable', ''))).toBe(true);
    expect(isRetryable(new ApiError(503, 'x', ''))).toBe(true);
    expect(isRetryable(new ApiError(409, 'request_in_progress', ''))).toBe(true);
    expect(isRetryable(new ApiError(400, 'attendance_rejected', ''))).toBe(false);
    expect(newBatchKey()).not.toBe(newBatchKey());
    expect(newBatchKey().length).toBeGreaterThanOrEqual(8);
  });
});
