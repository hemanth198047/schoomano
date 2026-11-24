import { ApiError } from './api';
import type { AttendanceStatus, BatchResult } from './types';
import type { KeyValueStore } from './storage';

export interface PendingMark {
  studentId: string;
  status: AttendanceStatus;
  /** The version this phone last saw (0 when there was no mark), so the server can spot a concurrent office change. */
  baseVersion: number;
  markedAt: string;
}

/** One unsent batch. Its key is fixed when it is made, so every retry is recognised by the server. */
export interface PendingBatch {
  key: string;
  sectionId: string;
  sectionLabel: string;
  date: string;
  marks: PendingMark[];
}

export interface RejectedBatch {
  batch: PendingBatch;
  reason: string;
}

export interface QueueState {
  pending: PendingBatch[];
  /** Batches the server refused for a reason retrying cannot fix. Kept so the teacher can see them. */
  rejected: RejectedBatch[];
}

const KEY = 'attendance-queue-v1';

/** Failures worth retrying later: no network, or the server was briefly unavailable. */
export const isRetryable = (e: unknown): boolean =>
  e instanceof ApiError && (e.status === 0 || e.status >= 502 || e.code === 'request_in_progress');

export function newBatchKey(): string {
  const r = () => Math.random().toString(16).slice(2, 10);
  return `att-${r()}-${r()}-${Date.now().toString(16)}`;
}

/**
 * Attendance waiting to be sent. Every change is written to storage before the call returns, so a phone that is
 * switched off or runs out of battery mid-day loses nothing. Sending is oldest first and stops at the first
 * retryable failure; a batch the server refuses outright moves aside so it cannot block the ones behind it.
 */
export function createQueue(store: KeyValueStore) {
  let state: QueueState | null = null;
  let chain: Promise<unknown> = Promise.resolve();

  async function load(): Promise<QueueState> {
    if (state) return state;
    const raw = await store.get(KEY);
    let parsed: QueueState = { pending: [], rejected: [] };
    if (raw) {
      try {
        const p = JSON.parse(raw) as Partial<QueueState>;
        parsed = { pending: p.pending ?? [], rejected: p.rejected ?? [] };
      } catch {
        /* a damaged file must not stop the app; start empty */
      }
    }
    state = parsed;
    return parsed;
  }

  async function save(s: QueueState) {
    state = s;
    await store.set(KEY, JSON.stringify(s));
  }

  /** Operations run one at a time so a flush and a new batch can never overwrite each other's save. */
  function serial<T>(work: () => Promise<T>): Promise<T> {
    const next = chain.then(work, work);
    chain = next.catch(() => undefined);
    return next;
  }

  return {
    snapshot: () => serial(async () => JSON.parse(JSON.stringify(await load())) as QueueState),

    enqueue: (batch: PendingBatch) =>
      serial(async () => {
        const s = await load();
        await save({ ...s, pending: [...s.pending, batch] });
      }),

    dismissRejected: (key: string) =>
      serial(async () => {
        const s = await load();
        await save({ ...s, rejected: s.rejected.filter((r) => r.batch.key !== key) });
      }),

    flush: (send: (b: PendingBatch) => Promise<BatchResult>) =>
      serial(async () => {
        const s = await load();
        const sent: { batch: PendingBatch; result: BatchResult }[] = [];
        let pending = [...s.pending];
        let rejected = [...s.rejected];
        while (pending.length > 0) {
          const batch = pending[0]!;
          try {
            sent.push({ batch, result: await send(batch) });
            pending = pending.slice(1);
            await save({ pending, rejected });
          } catch (e) {
            if (isRetryable(e)) break;
            pending = pending.slice(1);
            rejected = [
              ...rejected,
              { batch, reason: e instanceof ApiError ? e.detail : 'The server refused this batch.' },
            ];
            await save({ pending, rejected });
          }
        }
        return { sent, state: { pending, rejected } };
      }),
  };
}

export type Queue = ReturnType<typeof createQueue>;
