import { useState, type FormEvent } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { AcademicStructure, Campus, InboxItem, Me, SentNotice } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDateTime } from '../lib/format';

const OFFICE = ['group_admin', 'campus_admin', 'principal'];
const STAFF = [...OFFICE, 'teacher'];

/** Who may write notices; the server enforces NOTICE_SEND regardless. */
export const canSend = (me: Me) => me.roles.some((r) => STAFF.includes(r.roleKey));
export const isOffice = (me: Me) => me.roles.some((r) => OFFICE.includes(r.roleKey));

export function NoticesPage({
  me,
  campuses,
  selectedCampusId,
}: {
  me: Me;
  campuses: Campus[];
  selectedCampusId: string | null;
}) {
  return (
    <>
      <PageHeader title="Notices" lead="What the school has told you, and what you can tell parents." />
      <Inbox />
      {canSend(me) && <Compose me={me} campuses={campuses} selectedCampusId={selectedCampusId} />}
    </>
  );
}

function Inbox() {
  const session = useApiSession();
  const [items, reload] = useApiResource<InboxItem[]>('/api/v1/notices/mine');
  async function markRead(id: string) {
    try {
      await apiRequest(session, `/api/v1/notices/${id}/read`, { method: 'POST' });
    } finally {
      reload();
    }
  }
  return (
    <section aria-labelledby="inbox-h">
      <h2 id="inbox-h">Your inbox</h2>
      {items.status === 'loading' && <LoadingState label="Loading your notices" />}
      {items.status === 'error' && <ErrorState error={items.error} onRetry={reload} />}
      {items.status === 'ready' &&
        (items.data.length === 0 ? (
          <EmptyState title="No notices yet">
            <p>Notices from the school appear here.</p>
          </EmptyState>
        ) : (
          <ul className="plain-list">
            {items.data.map((n) => (
              <li key={n.id}>
                <strong>{n.title}</strong> {!n.read && <StatusBadge tone="info">New</StatusBadge>}
                <br />
                {n.body}
                <br />
                <small className="muted">{formatDateTime(n.receivedAt)}</small>{' '}
                {!n.read && (
                  <Button
                    variant="quiet"
                    aria-label={`Mark "${n.title}" as read`}
                    onClick={() => void markRead(n.id)}
                  >
                    Mark as read
                  </Button>
                )}
              </li>
            ))}
          </ul>
        ))}
    </section>
  );
}

function Compose({
  me,
  campuses,
  selectedCampusId,
}: {
  me: Me;
  campuses: Campus[];
  selectedCampusId: string | null;
}) {
  const session = useApiSession();
  const [chosenCampus, setChosenCampus] = useState<string | null>(null);
  const campusId = selectedCampusId ?? chosenCampus ?? campuses[0]?.id ?? null;
  const [structure] = useApiResource<AcademicStructure>(
    campusId === null ? null : `/api/v1/campuses/${campusId}/academic-structure`,
  );
  const [sent, reloadSent] = useApiResource<SentNotice[]>(
    campusId ? `/api/v1/campuses/${campusId}/notices` : null,
  );
  const [sectionId, setSectionId] = useState('');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const office = isOffice(me);
  const sections =
    structure.status === 'ready'
      ? structure.data.programmes.flatMap((p) =>
          p.grades.flatMap((g) => g.sections.map((s) => ({ id: s.id, label: `${g.name} ${s.code}` }))),
        )
      : [];
  const ready = title.trim().length >= 3 && body.trim().length > 0 && (office || sectionId !== '');

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    setError(null);
    try {
      const r = await apiRequest<{ people: number }>(session, `/api/v1/campuses/${campusId}/notices`, {
        method: 'POST',
        body: { title, body, sectionId: sectionId || null },
      });
      setMessage(`Sent to ${r.people} ${r.people === 1 ? 'person' : 'people'}.`);
      setTitle('');
      setBody('');
      reloadSent();
    } catch (err) {
      setError(err instanceof ApiError ? err.detail : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="send-h">
      <h2 id="send-h">Write a notice</h2>
      <form className="form" onSubmit={submit} aria-label="Write a notice">
        {selectedCampusId === null && campuses.length > 1 && (
          <Field label="Campus">
            {(p) => (
              <select
                {...p}
                value={campusId ?? ''}
                onChange={(e) => {
                  setChosenCampus(e.target.value);
                  setSectionId('');
                }}
              >
                {campuses.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        <Field
          label="To"
          hint="Parents of the children in this class who may receive notices."
          required={!office}
        >
          {(p) => (
            <select {...p} value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
              <option value="">{office ? 'Whole campus' : 'Choose a class'}</option>
              {sections.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.label}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Title" hint="Also sent by SMS, so keep it short and do not name a child." required>
          {(p) => <input {...p} value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} />}
        </Field>
        <Field label="Message" required>
          {(p) => (
            <textarea
              {...p}
              rows={4}
              value={body}
              maxLength={2000}
              onChange={(e) => setBody(e.target.value)}
            />
          )}
        </Field>
        <p className="muted">Text messages are not sent between 9 pm and 7 am; they wait until morning.</p>
        <Button type="submit" variant="primary" busy={busy} disabled={!ready}>
          Send notice
        </Button>
        {message && <p role="status">{message}</p>}
        {error && <p role="alert">{error}</p>}
      </form>
      {sent.status === 'ready' && sent.data.length > 0 && (
        <>
          <h3>Sent recently</h3>
          <ul className="plain-list">
            {sent.data.map((n) => (
              <li key={n.id}>
                <strong>{n.title}</strong>, {n.audience}: {n.people} {n.people === 1 ? 'person' : 'people'},
                text messages sent {n.smsSent}
                {n.smsWaiting > 0 && `, waiting ${n.smsWaiting}`}
                {n.smsFailed > 0 && `, failed ${n.smsFailed}`}
                {n.smsSkipped > 0 && `, no number ${n.smsSkipped}`}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
