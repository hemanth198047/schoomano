import { ApiError } from '../api/client';
import type { AttendanceBatchResult, AttendanceStatus } from '../api/types';

export interface PendingMark {
  studentId: string;
  status: AttendanceStatus;
  /** The version this device last saw (0 if the student had no mark), so the server can spot a concurrent office change. */
  baseVersion: number;
  markedAt: string;
}

/** One unsent batch. The key is fixed when the batch is created, so every retry is recognised by the server. */
export interface PendingBatch {
  key: string;
  sectionId: string;
  date: string;
  marks: PendingMark[];
}

export const newBatch = (sectionId: string, date: string, marks: PendingMark[]): PendingBatch => ({
  key: `att-${crypto.randomUUID()}`,
  sectionId,
  date,
  marks,
});

/** Failures worth retrying later: no network, or the server was briefly unavailable. Anything else needs a person. */
export const isRetryable = (e: unknown): boolean =>
  e instanceof ApiError && (e.status === 0 || e.status >= 502 || e.code === 'request_in_progress');

export interface FlushOutcome {
  remaining: PendingBatch[];
  results: { batch: PendingBatch; result: AttendanceBatchResult }[];
  /** First batch the server refused for a reason retrying cannot fix. It is dropped from the queue and reported. */
  rejected: { batch: PendingBatch; error: ApiError } | null;
}

/**
 * Sends queued batches oldest first. Stops at the first retryable failure (keeping order), and drops a batch the
 * server refuses outright so it cannot block the ones behind it.
 */
export async function flush(
  queue: PendingBatch[],
  send: (batch: PendingBatch) => Promise<AttendanceBatchResult>,
): Promise<FlushOutcome> {
  const remaining = [...queue];
  const results: FlushOutcome['results'] = [];
  while (remaining.length > 0) {
    const batch = remaining[0]!;
    try {
      results.push({ batch, result: await send(batch) });
      remaining.shift();
    } catch (e) {
      if (isRetryable(e)) return { remaining, results, rejected: null };
      remaining.shift();
      return {
        remaining,
        results,
        rejected: {
          batch,
          error: e instanceof ApiError ? e : new ApiError(0, 'client_error', 'Something went wrong.', null),
        },
      };
    }
  }
  return { remaining, results, rejected: null };
}
