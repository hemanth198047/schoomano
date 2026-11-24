import { ApiError, type Client } from './api';
import type { KeyValueStore } from './storage';
import type { CampusStructure, Me } from './types';

/**
 * Reads from the server and keeps the last good answer. When the phone has no network the saved answer is used, so
 * a teacher who opens the app in a dead spot still gets to their classes. Real refusals are never hidden.
 */
async function withFallback<T>(
  store: KeyValueStore,
  key: string,
  fetchFresh: () => Promise<T>,
): Promise<{ value: T; fromCache: boolean } | null> {
  try {
    const value = await fetchFresh();
    await store.set(key, JSON.stringify(value));
    return { value, fromCache: false };
  } catch (e) {
    if (!(e instanceof ApiError) || e.status !== 0) throw e;
    const raw = await store.get(key);
    return raw ? { value: JSON.parse(raw) as T, fromCache: true } : null;
  }
}

export const loadMe = (client: Pick<Client, 'request'>, store: KeyValueStore) =>
  withFallback<Me>(store, 'me', () => client.request<Me>('/api/v1/me'));

export async function loadSections(client: Pick<Client, 'request'>, store: KeyValueStore, campusId: string) {
  const r = await withFallback<CampusStructure>(store, `structure-${campusId}`, () =>
    client.request<CampusStructure>(`/api/v1/campuses/${campusId}/academic-structure`),
  );
  if (!r) return null;
  return r.value.programmes.flatMap((p) =>
    p.grades.flatMap((g) => g.sections.map((s) => ({ id: s.id, label: `${g.name} ${s.code}` }))),
  );
}

/** A different person must never see the previous person's saved lists. */
export const forgetSavedLists = async (store: KeyValueStore, keys: string[]) => {
  for (const k of keys) await store.remove(k);
};
