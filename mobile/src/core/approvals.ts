import type { Client } from './api';
import type { Me } from './types';

/** What an approver sees of a request waiting for them. */
export interface PendingRequest {
  id: string;
  chainName: string;
  subject: string;
  details: string | null;
  requester: string;
  currentStep: number;
  waitingFor: string | null;
  submittedAt: string;
  campusId: string;
  campusName: string;
}

/** Roles that can decide a workflow step, with the school's own role names. */
export const APPROVER_ROLES = ['group_admin', 'campus_admin', 'principal', 'finance_officer'];

export function isApprover(me: { roles: { roleKey: string }[] }): boolean {
  return me.roles.some((r) => APPROVER_ROLES.includes(r.roleKey));
}

/**
 * The campuses whose approval inbox this person should look at: the campuses named in their grants, and every campus of the
 * school when they hold a school-wide one.
 */
export async function approverCampuses(
  client: Pick<Client, 'request'>,
  me: Me,
): Promise<{ id: string; name: string }[]> {
  const mine = me.roles.filter((r) => APPROVER_ROLES.includes(r.roleKey));
  const found = new Map<string, string>();
  for (const r of mine) if (r.scopeLevel === 'CAMPUS') found.set(r.scopeId, r.scopeName);
  if (mine.some((r) => r.scopeLevel === 'GROUP' || r.scopeLevel === 'LEGAL_ENTITY')) {
    const all = await client.request<{ id: string; name: string }[]>('/api/v1/campuses');
    for (const c of all) found.set(c.id, c.name);
  }
  return [...found].map(([id, name]) => ({ id, name }));
}

/** Requests waiting at a step this person holds, across their campuses, oldest first. A campus that refuses is skipped. */
export async function loadPending(
  client: Pick<Client, 'request'>,
  campuses: { id: string; name: string }[],
): Promise<PendingRequest[]> {
  const lists = await Promise.all(
    campuses.map((c) =>
      client
        .request<Omit<PendingRequest, 'campusId' | 'campusName'>[]>(
          `/api/v1/campuses/${c.id}/workflow-requests?view=inbox`,
        )
        .then((rows) => rows.map((r) => ({ ...r, campusId: c.id, campusName: c.name })))
        .catch(() => [] as PendingRequest[]),
    ),
  );
  return lists.flat().sort((a, b) => a.submittedAt.localeCompare(b.submittedAt));
}

/** A rejection must say why; an approval may carry a note. Returns a message when the answer cannot be sent yet. */
export function decisionProblem(decision: 'APPROVED' | 'REJECTED', note: string): string | null {
  if (decision === 'REJECTED' && note.trim().length === 0) return 'Say why you are rejecting it.';
  if (note.length > 1000) return 'The note is too long (1000 characters at most).';
  return null;
}

export function decide(
  client: Pick<Client, 'request'>,
  id: string,
  decision: 'APPROVED' | 'REJECTED',
  note: string,
): Promise<unknown> {
  return client.request(`/api/v1/workflow-requests/${id}/decision`, {
    method: 'POST',
    json: { decision, note: note.trim() === '' ? null : note.trim() },
  });
}
