import { useState, type FormEvent } from 'react';
import { ApiError, apiRequest, apiText } from '../api/client';
import type { AuditRow, AuditVerification, Me, OperationsHealth, SupportGrant } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDateTime } from '../lib/format';

/** School administrators approve support and read the trail; platform support (no school role) reads health and the trail. */
export const canManageSupport = (me: Me) => me.roles.some((r) => r.roleKey === 'group_admin');
export const canSeeSupportPage = (me: Me) =>
  me.roles.some((r) => r.roleKey === 'group_admin' || r.roleKey === 'principal') ||
  (me.roles.length === 0 && me.children.length === 0);

const oops = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong.');
const stateTone = (s: SupportGrant['state']) =>
  s === 'ACTIVE' ? 'warning' : s === 'UPCOMING' ? 'info' : 'neutral';

export function SupportPage({ me }: { me: Me }) {
  return (
    <>
      <PageHeader
        title="Support and audit"
        lead="Who has had access, what they did, and whether the school is running smoothly."
      />
      <Health />
      {canManageSupport(me) && <Grants />}
      <Audit />
    </>
  );
}

function Health() {
  const [health, reload] = useApiResource<OperationsHealth>('/api/v1/operations/health');
  return (
    <section aria-labelledby="health-h">
      <h2 id="health-h">Health</h2>
      {health.status === 'loading' && <LoadingState label="Loading health" />}
      {health.status === 'error' && <ErrorState error={health.error} onRetry={reload} />}
      {health.status === 'ready' && (
        <ul className="plain-list">
          {health.data.checks.map((c) => (
            <li key={c.key}>
              <strong>{c.label}</strong>{' '}
              <StatusBadge tone={c.level === 'OK' ? 'success' : 'warning'}>
                {c.level === 'OK' ? 'OK' : 'Needs attention'}
              </StatusBadge>
              <br />
              {c.detail}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Grants() {
  const session = useApiSession();
  const [grants, reload] = useApiResource<SupportGrant[]>('/api/v1/support/grants');
  const [who, setWho] = useState('');
  const [purpose, setPurpose] = useState('');
  const [hours, setHours] = useState('2');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const ready = who.trim().length >= 3 && purpose.trim().length >= 10;

  async function approve(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    setError(null);
    try {
      await apiRequest(session, '/api/v1/support/grants', {
        method: 'POST',
        body: { supportUserRef: who.trim(), purpose: purpose.trim(), hours: Number(hours) },
      });
      setMessage(`Access approved for ${hours} hour${hours === '1' ? '' : 's'}.`);
      setWho('');
      setPurpose('');
      reload();
    } catch (err) {
      setError(oops(err));
    } finally {
      setBusy(false);
    }
  }

  async function revoke(g: SupportGrant) {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/support/grants/${g.id}/revoke`, { method: 'POST', body: {} });
      setMessage(`Access for ${g.supportUserRef} has ended.`);
      reload();
    } catch (err) {
      setError(oops(err));
    }
  }

  return (
    <section aria-labelledby="grants-h">
      <h2 id="grants-h">Platform support access</h2>
      <p className="muted">
        Support can only enter after you approve a named person for a stated reason, for at most 8 hours. You
        can end it at any time.
      </p>
      <form className="form" onSubmit={approve} aria-label="Approve support access">
        <Field label="Support person" hint="Their sign-in name, as support gave it to you." required>
          {(p) => <input {...p} value={who} maxLength={200} onChange={(e) => setWho(e.target.value)} />}
        </Field>
        <Field label="Why" hint="At least 10 characters. Shown in the audit trail." required>
          {(p) => (
            <input {...p} value={purpose} maxLength={500} onChange={(e) => setPurpose(e.target.value)} />
          )}
        </Field>
        <Field label="For how long" required>
          {(p) => (
            <select {...p} value={hours} onChange={(e) => setHours(e.target.value)}>
              {[1, 2, 4, 8].map((h) => (
                <option key={h} value={h}>{`${h} hour${h === 1 ? '' : 's'}`}</option>
              ))}
            </select>
          )}
        </Field>
        <Button type="submit" variant="primary" busy={busy} disabled={!ready}>
          Approve access
        </Button>
      </form>
      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
      {grants.status === 'loading' && <LoadingState label="Loading support access" />}
      {grants.status === 'error' && <ErrorState error={grants.error} onRetry={reload} />}
      {grants.status === 'ready' && grants.data.length > 0 && (
        <ul className="plain-list" aria-label="Support access">
          {grants.data.map((g) => (
            <li key={g.id}>
              <strong>{g.supportUserRef}</strong>{' '}
              <StatusBadge tone={stateTone(g.state)}>{g.state.toLowerCase()}</StatusBadge>
              <br />
              {g.purpose}
              <br />
              <small className="muted">
                Approved by {g.approvedBy}, until {formatDateTime(g.expiresAt)}
              </small>{' '}
              {(g.state === 'ACTIVE' || g.state === 'UPCOMING') && (
                <Button
                  variant="quiet"
                  aria-label={`End access for ${g.supportUserRef}`}
                  onClick={() => void revoke(g)}
                >
                  End access
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Audit() {
  const session = useApiSession();
  const [kind, setKind] = useState('');
  const [more, setMore] = useState<AuditRow[]>([]);
  const [verification, setVerification] = useState<AuditVerification | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [exported, setExported] = useState<string | null>(null);
  const query = `/api/v1/audit?limit=50${kind ? `&actorKind=${kind}` : ''}`;
  const [first, reload] = useApiResource<AuditRow[]>(query);
  const all = first.status === 'ready' ? [...first.data, ...more] : [];
  const last = all[all.length - 1];

  async function loadMore() {
    if (!last) return;
    try {
      const next = await apiRequest<AuditRow[]>(session, `${query}&beforeSeq=${last.seq}`);
      setMore((m) => [...m, ...next]);
    } catch (e) {
      setError(oops(e));
    }
  }

  async function download() {
    setError(null);
    setExported(null);
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (kind) params.set('actorKind', kind);
    try {
      const file = await apiText(session, `/api/v1/audit/export?${params.toString()}`);
      const url = URL.createObjectURL(new Blob([file.text], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `audit-${from || 'start'}-to-${to || 'now'}.csv`;
      link.click();
      URL.revokeObjectURL(url);
      setExported(
        file.truncated
          ? 'The file holds the first 50,000 entries of this period. Choose a shorter period to get the rest.'
          : 'The audit trail for this period has been downloaded. The download is recorded in the trail.',
      );
    } catch (e) {
      setError(oops(e));
    }
  }

  async function verify() {
    setError(null);
    try {
      setVerification(await apiRequest<AuditVerification>(session, '/api/v1/audit/verify'));
    } catch (e) {
      setError(oops(e));
    }
  }

  return (
    <section aria-labelledby="audit-h">
      <h2 id="audit-h">Audit trail</h2>
      <Field label="Show">
        {(p) => (
          <select
            {...p}
            value={kind}
            onChange={(e) => {
              setKind(e.target.value);
              setMore([]);
            }}
          >
            <option value="">Everyone</option>
            <option value="USER">School staff and parents</option>
            <option value="SUPPORT">Platform support</option>
          </select>
        )}
      </Field>
      <div className="form form--inline">
        <Field label="From date" hint="Optional">
          {(p) => <input {...p} type="date" value={from} onChange={(e) => setFrom(e.target.value)} />}
        </Field>
        <Field label="To date" hint="Optional, included">
          {(p) => <input {...p} type="date" value={to} onChange={(e) => setTo(e.target.value)} />}
        </Field>
        <Button variant="quiet" onClick={() => void download()}>
          Download as a spreadsheet (CSV)
        </Button>
      </div>
      {exported && <p role="status">{exported}</p>}
      <Button variant="quiet" onClick={() => void verify()}>
        Check the trail has not been altered
      </Button>
      {verification && (
        <p role="status">
          {verification.intact
            ? `Checked ${verification.checked} entries. Nothing has been changed or removed.`
            : `Problem at entry ${verification.brokenSeq}: ${verification.problem}.`}
        </p>
      )}
      {error && <p role="alert">{error}</p>}
      {first.status === 'loading' && <LoadingState label="Loading the audit trail" />}
      {first.status === 'error' && <ErrorState error={first.error} onRetry={reload} />}
      {first.status === 'ready' && (
        <table>
          <caption className="visually-hidden">Audit trail, newest first</caption>
          <thead>
            <tr>
              <th scope="col">When</th>
              <th scope="col">Who</th>
              <th scope="col">What</th>
              <th scope="col">Result</th>
            </tr>
          </thead>
          <tbody>
            {all.map((r) => (
              <tr key={r.seq}>
                <td>{formatDateTime(r.occurredAt)}</td>
                <td>{r.actorName}</td>
                <td>
                  {r.action}
                  {r.reason ? `: ${r.reason}` : ''}
                </td>
                <td>{r.outcome.toLowerCase()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {first.status === 'ready' && all.length >= 50 && (
        <Button variant="quiet" onClick={() => void loadMore()}>
          Show older entries
        </Button>
      )}
    </section>
  );
}
