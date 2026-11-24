import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import { loadMe, loadSections } from './cached';
import { memoryStore } from './storage';

const me = {
  personId: 'p',
  displayName: 'Ravi',
  tenant: { id: 't', slug: 's', name: 'N' },
  roles: [],
  children: [],
};
const down = { request: () => Promise.reject(new ApiError(0, 'network_unreachable', 'down')) } as never;

describe('saved answers', () => {
  it('saves the person and uses the copy only when offline', async () => {
    const store = memoryStore();
    expect(await loadMe(down, store)).toBeNull();
    expect(await loadMe({ request: () => Promise.resolve(me) } as never, store)).toEqual({
      value: me,
      fromCache: false,
    });
    expect(await loadMe(down, store)).toEqual({ value: me, fromCache: true });
  });

  it('does not use a saved copy to hide a sign-out', async () => {
    const store = memoryStore();
    await loadMe({ request: () => Promise.resolve(me) } as never, store);
    await expect(
      loadMe({ request: () => Promise.reject(new ApiError(401, 'unauthenticated', 'no')) } as never, store),
    ).rejects.toMatchObject({ status: 401 });
  });

  it('lists classes from the saved structure when offline', async () => {
    const store = memoryStore();
    const structure = {
      year: { id: 'y' },
      programmes: [{ grades: [{ name: 'Grade 5', sections: [{ id: 's1', code: 'A' }] }] }],
    };
    expect(await loadSections({ request: () => Promise.resolve(structure) } as never, store, 'c1')).toEqual([
      { id: 's1', label: 'Grade 5 A' },
    ]);
    expect(await loadSections(down, store, 'c1')).toEqual([{ id: 's1', label: 'Grade 5 A' }]);
    expect(await loadSections(down, store, 'c2')).toBeNull();
  });
});
