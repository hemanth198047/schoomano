import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { ApiKey, ApiKeyIssued, Campus, Me, Webhook, WebhookDelivery, WebhookIssued } from '../api/types';
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

export const canManageWebhooks = (me: Me) => me.roles.some((r) => r.roleKey === 'group_admin');

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
          {canManageWebhooks(me) && <Webhooks />}
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

const EVENT_LABELS: Record<string, string> = {
  'academic.year_rolled_over': 'A school year was rolled over',
  'config.published': 'Settings were published',
  'import.committed': 'An import was applied',
  'timetable.published': 'A timetable was published',
  'timetable.substitution_created': 'A substitution was set',
  'timetable.substitution_cancelled': 'A substitution was cancelled',
};

function Webhooks() {
  const session = useApiSession();
  const [hooks, reload] = useApiResource<Webhook[]>('/api/v1/webhooks');
  const [types] = useApiResource<string[]>('/api/v1/webhooks/event-types');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [chosen, setChosen] = useState<string[]>([]);
  const [secret, setSecret] = useState<WebhookIssued | null>(null);
  const [looking, setLooking] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  async function create() {
    setError(null);
    try {
      const issued = await apiRequest<WebhookIssued>(session, '/api/v1/webhooks', {
        method: 'POST',
        body: { name: name.trim(), url: url.trim(), eventTypes: chosen },
      });
      setSecret(issued);
      setName('');
      setUrl('');
      setChosen([]);
      reload();
    } catch (e) {
      setError(message(e));
    }
  }

  async function replaceSecret(h: Webhook) {
    setError(null);
    setNote(null);
    try {
      setSecret(
        await apiRequest<WebhookIssued>(session, `/api/v1/webhooks/${h.id}/rotate-secret`, {
          method: 'POST',
          body: {},
        }),
      );
      reload();
    } catch (e) {
      setError(message(e));
    }
  }

  async function act(h: Webhook, what: 'disable' | 'ping') {
    setError(null);
    setNote(null);
    try {
      await apiRequest(session, `/api/v1/webhooks/${h.id}/${what}`, { method: 'POST', body: {} });
      if (what === 'ping') setNote(`A test event is on its way to ${h.name}.`);
      reload();
    } catch (e) {
      setError(message(e));
    }
  }

  return (
    <>
      <section aria-labelledby="hooks-h">
        <h2 id="hooks-h">Webhooks</h2>
        <p className="muted">
          Webhooks tell another system when something changes at the school. Each message is signed with the
          secret shown when you create the endpoint. Events about individual students are not offered.
        </p>
        {secret && (
          <div role="status">
            <p>
              Copy the signing secret for <strong>{secret.endpoint.name}</strong> now. It is not shown again.
            </p>
            {secret.endpoint.oldSecretValidUntil && (
              <p>
                The old secret keeps working until {formatDateTime(secret.endpoint.oldSecretValidUntil)}.
                Until then each message carries a signature for both, so you can change over without missing
                anything.
              </p>
            )}
            <p>
              <code>{secret.secret}</code>
            </p>
            <Button variant="quiet" onClick={() => setSecret(null)}>
              I have copied the secret
            </Button>
          </div>
        )}
        {hooks.status === 'loading' && <LoadingState label="Loading webhooks" />}
        {hooks.status === 'error' && <ErrorState error={hooks.error} onRetry={reload} />}
        {hooks.status === 'ready' &&
          (hooks.data.length === 0 ? (
            <EmptyState title="No webhooks yet" />
          ) : (
            <ul className="plain-list">
              {hooks.data.map((h) => (
                <li key={h.id}>
                  <p>
                    <strong>{h.name}</strong> <span className="muted">{h.url}</span>{' '}
                    <StatusBadge tone={h.active ? 'success' : 'warning'}>
                      {h.active ? 'ACTIVE' : 'OFF'}
                    </StatusBadge>
                  </p>
                  <p className="muted">
                    {h.eventTypes.map((t) => EVENT_LABELS[t] ?? t).join(', ')}
                    {!h.active && h.disabledReason ? `. ${h.disabledReason}` : ''}
                  </p>
                  {h.secretRotatedAt && (
                    <p className="muted">
                      Secret replaced {formatDateTime(h.secretRotatedAt)}
                      {h.oldSecretValidUntil
                        ? `. The old one works until ${formatDateTime(h.oldSecretValidUntil)}`
                        : ''}
                    </p>
                  )}
                  <div className="form form--inline">
                    {h.active && (
                      <>
                        <Button
                          variant="quiet"
                          onClick={() => void act(h, 'ping')}
                        >{`Send test to ${h.name}`}</Button>
                        <Button
                          variant="quiet"
                          onClick={() => void replaceSecret(h)}
                        >{`Replace secret of ${h.name}`}</Button>
                        <Button
                          variant="quiet"
                          onClick={() => void act(h, 'disable')}
                        >{`Switch off ${h.name}`}</Button>
                      </>
                    )}
                    <Button variant="quiet" onClick={() => setLooking(looking === h.id ? null : h.id)}>
                      {looking === h.id ? `Hide deliveries of ${h.name}` : `Deliveries of ${h.name}`}
                    </Button>
                  </div>
                  {looking === h.id && <Deliveries id={h.id} />}
                </li>
              ))}
            </ul>
          ))}
        {note && <p role="status">{note}</p>}
        <h3>Add a webhook</h3>
        <div className="form">
          <Field label="Webhook name" hint="For example, Library system">
            {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
          </Field>
          <Field label="Address" hint="Must start with https://">
            {(p) => <input {...p} type="url" value={url} onChange={(e) => setUrl(e.target.value)} />}
          </Field>
          <fieldset>
            <legend>Events to send</legend>
            {types.status === 'ready' &&
              types.data.map((t) => (
                <label key={t} className="check">
                  <input
                    type="checkbox"
                    checked={chosen.includes(t)}
                    onChange={(e) =>
                      setChosen(e.target.checked ? [...chosen, t] : chosen.filter((x) => x !== t))
                    }
                  />{' '}
                  {EVENT_LABELS[t] ?? t}
                </label>
              ))}
          </fieldset>
          <Button
            onClick={() => void create()}
            disabled={name.trim().length < 3 || url.trim() === '' || chosen.length === 0}
          >
            Add webhook
          </Button>
        </div>
        {error && <p role="alert">{error}</p>}
      </section>
    </>
  );
}

function Deliveries({ id }: { id: string }) {
  const [list] = useApiResource<WebhookDelivery[]>(`/api/v1/webhooks/${id}/deliveries`);
  if (list.status === 'loading') return <LoadingState label="Loading deliveries" />;
  if (list.status === 'error') return <p role="alert">Could not load deliveries.</p>;
  if (list.data.length === 0) return <p className="muted">Nothing has been sent yet.</p>;
  return (
    <ul className="plain-list">
      {list.data.map((d) => (
        <li key={d.id}>
          {d.eventType}{' '}
          <StatusBadge tone={d.status === 'SENT' ? 'success' : d.status === 'FAILED' ? 'danger' : 'info'}>
            {d.status}
          </StatusBadge>{' '}
          <span className="muted">
            {formatDateTime(d.createdAt)}, {d.attempts} {d.attempts === 1 ? 'try' : 'tries'}
            {d.lastError ? `, ${d.lastError}` : ''}
          </span>
        </li>
      ))}
    </ul>
  );
}
