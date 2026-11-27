import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { ApiKey, ApiKeyIssued, Campus, Me } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDateTime } from '../lib/format';

export const canManageKeys = (me: Me) =>
  me.roles.some((r) => r.roleKey === 'campus_admin' || r.roleKey === 'group_admin');

const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function IntegrationsPage({
  me,
  campuses,
  selectedCampusId,
}: {
  me: Me;
  campuses: Campus[];
  selectedCampusId: string | null;
}) {
  const [chosenCampus, setChosenCampus] = useState<string | null>(null);
  const campusId = selectedCampusId ?? chosenCampus ?? campuses[0]?.id ?? null;
  return (
    <>
      <PageHeader
        title="Integrations"
        lead="Keys that let an outside system, such as a library or canteen app, read basic campus data. Keys cannot change anything."
      />
      {!canManageKeys(me) ? (
        <EmptyState title="Not available">
          <p>Only school administrators manage integration keys.</p>
        </EmptyState>
      ) : (
        <>
          {selectedCampusId === null && campuses.length > 1 && (
            <div className="form form--inline">
              <Field label="Campus">
                {(p) => (
                  <select {...p} value={campusId ?? ''} onChange={(e) => setChosenCampus(e.target.value)}>
                    {campuses.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
            </div>
          )}
          {campusId === null ? (
            <EmptyState title="No campus yet" />
          ) : (
            <Keys key={campusId} campusId={campusId} />
          )}
        </>
      )}
    </>
  );
}

function Keys({ campusId }: { campusId: string }) {
  const session = useApiSession();
  const [keys, reload] = useApiResource<ApiKey[]>(`/api/v1/campuses/${campusId}/api-keys`);
  const [name, setName] = useState('');
  const [days, setDays] = useState('365');
  const [secret, setSecret] = useState<ApiKeyIssued | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function create() {
    setError(null);
    try {
      const issued = await apiRequest<ApiKeyIssued>(session, `/api/v1/campuses/${campusId}/api-keys`, {
        method: 'POST',
        body: { name: name.trim(), validDays: Number(days) },
      });
      setSecret(issued);
      setName('');
      reload();
    } catch (e) {
      setError(message(e));
    }
  }

  async function revoke(k: ApiKey) {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/api-keys/${k.id}/revoke`, { method: 'POST', body: {} });
      reload();
    } catch (e) {
      setError(message(e));
    }
  }

  return (
    <>
      {secret && (
        <section aria-labelledby="secret-h">
          <h2 id="secret-h">Copy this key now</h2>
          <p>
            This is the only time the key for <strong>{secret.key.name}</strong> is shown. Store it in the
            other system’s settings.
          </p>
          <p>
            <code>{secret.secret}</code>
          </p>
          <Button variant="quiet" onClick={() => setSecret(null)}>
            I have copied it
          </Button>
        </section>
      )}
      <section aria-labelledby="keys-h">
        <h2 id="keys-h">Keys</h2>
        {keys.status === 'loading' && <LoadingState label="Loading keys" />}
        {keys.status === 'error' && <ErrorState error={keys.error} onRetry={reload} />}
        {keys.status === 'ready' &&
          (keys.data.length === 0 ? (
            <EmptyState title="No keys yet" />
          ) : (
            <ul className="plain-list">
              {keys.data.map((k) => (
                <li key={k.id}>
                  <p>
                    <strong>{k.name}</strong> <span className="muted">ends in {k.hint}</span>{' '}
                    <StatusBadge tone={k.status === 'ACTIVE' ? 'success' : 'neutral'}>{k.status}</StatusBadge>
                  </p>
                  <p className="muted">
                    Expires {formatDateTime(k.expiresAt)}
                    {k.lastUsedAt ? `, last used ${formatDateTime(k.lastUsedAt)}` : ', never used'}
                  </p>
                  {k.status === 'ACTIVE' && (
                    <Button variant="quiet" onClick={() => void revoke(k)}>{`Revoke ${k.name}`}</Button>
                  )}
                </li>
              ))}
            </ul>
          ))}
      </section>
      <section aria-labelledby="new-h">
        <h2 id="new-h">Create a key</h2>
        <div className="form">
          <Field label="Name" hint="For example, Library sync">
            {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
          </Field>
          <Field label="Valid for (days)" hint="1 to 730">
            {(p) => (
              <input
                {...p}
                type="number"
                min={1}
                max={730}
                value={days}
                onChange={(e) => setDays(e.target.value)}
              />
            )}
          </Field>
          <Button onClick={() => void create()} disabled={name.trim().length < 3}>
            Create key
          </Button>
        </div>
        {error && <p role="alert">{error}</p>}
      </section>
    </>
  );
}
