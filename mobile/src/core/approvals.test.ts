import { describe, expect, it, vi } from 'vitest';
import { approverCampuses, decide, decisionProblem, isApprover, loadPending } from './approvals';
import type { Me } from './types';

const me = (roles: Me['roles']): Me => ({
  personId: 'p',
  displayName: 'Asha',
  tenant: { id: 't', slug: 's', name: 'S' },
  roles,
  children: [],
});

describe('approvals', () => {
  it('knows who can approve', () => {
    expect(isApprover({ roles: [{ roleKey: 'principal' }] })).toBe(true);
    expect(isApprover({ roles: [{ roleKey: 'teacher' }] })).toBe(false);
  });

  it('looks at the campuses named in the grants, and at every campus for a school-wide grant', async () => {
    const request = vi.fn().mockResolvedValue([
      { id: 'c1', name: 'North' },
      { id: 'c2', name: 'South' },
    ]);
    const campus = me([{ roleKey: 'principal', scopeLevel: 'CAMPUS', scopeId: 'c2', scopeName: 'South' }]);
    expect(await approverCampuses({ request }, campus)).toEqual([{ id: 'c2', name: 'South' }]);
    expect(request).not.toHaveBeenCalled();
    const group = me([{ roleKey: 'group_admin', scopeLevel: 'GROUP', scopeId: 't', scopeName: 'S' }]);
    expect((await approverCampuses({ request }, group)).map((c) => c.id)).toEqual(['c1', 'c2']);
  });

  it('gathers waiting requests across campuses, oldest first, and skips a campus that refuses', async () => {
    const request = vi.fn((path: string): Promise<unknown> => {
      if (path.includes('/c1/'))
        return Promise.resolve([{ id: 'r2', subject: 'B', submittedAt: '2026-11-20T10:00:00Z' }]);
      if (path.includes('/c2/'))
        return Promise.resolve([{ id: 'r1', subject: 'A', submittedAt: '2026-11-19T10:00:00Z' }]);
      return Promise.reject(new Error('no'));
    });
    const rows = await loadPending({ request: request as never }, [
      { id: 'c1', name: 'North' },
      { id: 'c2', name: 'South' },
      { id: 'c3', name: 'Closed' },
    ]);
    expect(rows.map((r) => r.id)).toEqual(['r1', 'r2']);
    expect(rows[0]?.campusName).toBe('South');
    expect(request).toHaveBeenCalledWith('/api/v1/campuses/c1/workflow-requests?view=inbox');
  });

  it('needs a reason to reject, not to approve', () => {
    expect(decisionProblem('REJECTED', '  ')).toBe('Say why you are rejecting it.');
    expect(decisionProblem('REJECTED', 'Over budget')).toBeNull();
    expect(decisionProblem('APPROVED', '')).toBeNull();
    expect(decisionProblem('APPROVED', 'x'.repeat(1001))).toMatch(/too long/);
  });

  it('sends the decision with a trimmed note, or none', async () => {
    const request = vi.fn().mockResolvedValue({});
    await decide({ request }, 'r1', 'APPROVED', '  ');
    await decide({ request }, 'r1', 'REJECTED', ' Too dear ');
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/workflow-requests/r1/decision', {
      method: 'POST',
      json: { decision: 'APPROVED', note: null },
    });
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/workflow-requests/r1/decision', {
      method: 'POST',
      json: { decision: 'REJECTED', note: 'Too dear' },
    });
  });
});
