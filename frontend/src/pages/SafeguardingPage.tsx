import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  Campus,
  Me,
  SafeguardingDetail,
  SafeguardingReceipt,
  SafeguardingStatus,
  SafeguardingSummary,
  StudentListItem,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';

/** Any member of staff may raise a concern; the server decides who may read one. */
export const canRaiseConcern = (me: Me) => me.roles.length > 0;

const CATEGORIES: Record<string, string> = {
  PHYSICAL: 'Physical',
  EMOTIONAL: 'Emotional',
  NEGLECT: 'Neglect',
  ONLINE: 'Online',
  BULLYING: 'Bullying',
  OTHER: 'Other',
};
const SEVERITIES: Record<string, string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  IMMEDIATE: 'Immediate danger',
};
const STATUS_LABEL: Record<SafeguardingStatus, string> = {
  REPORTED: 'Reported',
  TRIAGED: 'Triaged',
  UNDER_REVIEW: 'Under review',
  ESCALATED: 'Escalated',
  CLOSED: 'Closed',
};
const NEXT: Record<SafeguardingStatus, SafeguardingStatus[]> = {
  REPORTED: ['TRIAGED'],
  TRIAGED: ['UNDER_REVIEW', 'CLOSED'],
  UNDER_REVIEW: ['ESCALATED', 'CLOSED'],
  ESCALATED: ['CLOSED'],
  CLOSED: [],
};
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function SafeguardingPage({
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
  const [structure] = useApiResource<AcademicStructure>(
    campusId === null ? null : `/api/v1/campuses/${campusId}/academic-structure`,
  );
  return (
    <>
      <PageHeader
        title="Safeguarding"
        lead="Report a concern about a child. Reports go only to the designated safeguarding lead, and every time one is read it is recorded."
      />
      {!canRaiseConcern(me) ? (
        <EmptyState title="Not available">
          <p>Safeguarding reports are raised by school staff.</p>
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
          {structure.status === 'loading' && <LoadingState label="Loading" />}
          {structure.status === 'error' && <ErrorState error={structure.error} />}
          {structure.status === 'ready' && !structure.data.year && (
            <EmptyState title="No academic year yet" />
          )}
          {structure.status === 'ready' && structure.data.year && (
            <>
              <Raise key={`r-${structure.data.year.id}`} yearId={structure.data.year.id} />
              <Reports key={structure.data.year.id} yearId={structure.data.year.id} />
            </>
          )}
        </>
      )}
    </>
  );
}

function Raise({ yearId }: { yearId: string }) {
  const session = useApiSession();
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [student, setStudent] = useState<StudentListItem | null>(null);
  const [category, setCategory] = useState('EMOTIONAL');
  const [severity, setSeverity] = useState('MEDIUM');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [receipt, setReceipt] = useState<SafeguardingReceipt | null>(null);
  const [busy, setBusy] = useState(false);
  const [found] = useApiResource<{ students: StudentListItem[] }>(
    submitted ? `/api/v1/academic-years/${yearId}/students?limit=8&q=${encodeURIComponent(submitted)}` : null,
  );

  async function send() {
    if (!student) return;
    setBusy(true);
    setError(null);
    try {
      const r = await apiRequest<SafeguardingReceipt>(
        session,
        `/api/v1/students/${student.id}/safeguarding-reports`,
        { method: 'POST', body: { category, severity, description: description.trim() } },
      );
      setReceipt(r);
      setStudent(null);
      setDescription('');
      setSubmitted('');
      setQuery('');
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="raise-h">
      <h2 id="raise-h">Report a concern</h2>
      {receipt && (
        <p className="notice" role="status">
          {receipt.message}
        </p>
      )}
      {!student ? (
        <>
          <div className="form form--inline">
            <Field label="Find the child" hint="Name or school reference">
              {(p) => <input {...p} value={query} onChange={(e) => setQuery(e.target.value)} />}
            </Field>
          </div>
          <p className="form__actions">
            <Button
              disabled={query.trim().length < 2}
              onClick={() => {
                setReceipt(null);
                setSubmitted(query.trim());
              }}
            >
              Search
            </Button>
          </p>
          {found.status === 'ready' && (
            <ul className="plain-list">
              {found.data.students.map((s) => (
                <li key={s.id}>
                  {s.displayName} <span className="muted">{s.schoolRef}</span>{' '}
                  <Button variant="quiet" onClick={() => setStudent(s)}>{`Choose ${s.displayName}`}</Button>
                </li>
              ))}
            </ul>
          )}
        </>
      ) : (
        <>
          <p>
            Concern about <strong>{student.displayName}</strong>{' '}
            <Button variant="quiet" onClick={() => setStudent(null)}>
              Choose someone else
            </Button>
          </p>
          <div className="form form--inline">
            <Field label="Kind of concern">
              {(p) => (
                <select {...p} value={category} onChange={(e) => setCategory(e.target.value)}>
                  {Object.entries(CATEGORIES).map(([k, v]) => (
                    <option key={k} value={k}>
                      {v}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="How serious">
              {(p) => (
                <select {...p} value={severity} onChange={(e) => setSeverity(e.target.value)}>
                  {Object.entries(SEVERITIES).map(([k, v]) => (
                    <option key={k} value={k}>
                      {v}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="What you saw or heard" hint="At least 10 characters. Facts, not opinions.">
              {(p) => (
                <textarea
                  {...p}
                  rows={4}
                  value={description}
                  maxLength={4000}
                  onChange={(e) => setDescription(e.target.value)}
                />
              )}
            </Field>
          </div>
          {severity === 'IMMEDIATE' && (
            <p className="notice">
              If a child is in immediate danger, also tell the safeguarding lead in person or by phone.
            </p>
          )}
          {error && (
            <p className="notice" role="alert">
              {error}
            </p>
          )}
          <p className="form__actions">
            <Button
              variant="primary"
              busy={busy}
              disabled={description.trim().length < 10}
              onClick={() => void send()}
            >
              Send report
            </Button>
          </p>
        </>
      )}
    </section>
  );
}

function Reports({ yearId }: { yearId: string }) {
  const [list, reload] = useApiResource<SafeguardingSummary[]>(
    `/api/v1/academic-years/${yearId}/safeguarding-reports`,
  );
  const [openId, setOpenId] = useState<string | null>(null);
  return (
    <section aria-labelledby="reports-h">
      <h2 id="reports-h">Reports</h2>
      {list.status === 'loading' && <LoadingState label="Loading reports" />}
      {list.status === 'error' &&
        (list.error instanceof ApiError && list.error.status === 404 ? (
          <EmptyState title="Reports are private">
            <p>Only the designated safeguarding lead can read reports.</p>
          </EmptyState>
        ) : (
          <ErrorState error={list.error} onRetry={reload} />
        ))}
      {list.status === 'ready' &&
        (list.data.length === 0 ? (
          <EmptyState title="No reports" />
        ) : (
          <ul className="plain-list">
            {list.data.map((r) => (
              <li key={r.id}>
                <p>
                  <strong>{r.studentName}</strong>{' '}
                  <span className="muted">
                    {CATEGORIES[r.category] ?? r.category}, {SEVERITIES[r.severity] ?? r.severity}
                  </span>{' '}
                  <StatusBadge tone={r.status === 'CLOSED' ? 'neutral' : 'info'}>
                    {STATUS_LABEL[r.status]}
                  </StatusBadge>{' '}
                  {r.triageOverdue && r.status === 'REPORTED' && (
                    <StatusBadge tone="warning">Triage overdue</StatusBadge>
                  )}{' '}
                  <Button variant="quiet" onClick={() => setOpenId(openId === r.id ? null : r.id)}>
                    {openId === r.id
                      ? `Close ${r.studentName}'s report view`
                      : `Open ${r.studentName}'s report`}
                  </Button>
                </p>
                {openId === r.id && <ReportView key={r.id} id={r.id} onChanged={reload} />}
              </li>
            ))}
          </ul>
        ))}
    </section>
  );
}

function ReportView({ id, onChanged }: { id: string; onChanged: () => void }) {
  const session = useApiSession();
  const [detail, reload] = useApiResource<SafeguardingDetail>(`/api/v1/safeguarding-reports/${id}`);
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (detail.status === 'loading') return <LoadingState label="Opening report" />;
  if (detail.status === 'error') return <ErrorState error={detail.error} onRetry={reload} />;
  const { summary, description, actions, reportedBy, occurredOn } = detail.data;

  async function act(toStatus?: SafeguardingStatus) {
    setBusy(true);
    setError(null);
    try {
      await apiRequest(session, `/api/v1/safeguarding-reports/${id}/actions`, {
        method: 'POST',
        body: { toStatus, note: note.trim() || undefined },
      });
      setNote('');
      reload();
      onChanged();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <p>{description}</p>
      <p className="muted">
        Reported by {reportedBy}, {summary.reportedAt.slice(0, 10)}
        {occurredOn ? `; happened ${occurredOn}` : ''}
      </p>
      <ol className="plain-list" aria-label="Handling">
        {actions.map((a, i) => (
          <li key={i}>
            <p>
              {a.to ? `${STATUS_LABEL[a.to as SafeguardingStatus] ?? a.to}: ` : 'Note: '}
              {a.note}
            </p>
            <p className="muted">
              {a.by}, {a.at.slice(0, 10)}
            </p>
          </li>
        ))}
      </ol>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      {NEXT[summary.status].length > 0 && (
        <>
          <Field label="Note" hint="Required (5 characters or more) to escalate or close.">
            {(p) => <input {...p} value={note} maxLength={4000} onChange={(e) => setNote(e.target.value)} />}
          </Field>
          <p className="form__actions">
            {NEXT[summary.status].map((to) => (
              <Button
                key={to}
                busy={busy}
                disabled={(to === 'ESCALATED' || to === 'CLOSED') && note.trim().length < 5}
                onClick={() => void act(to)}
              >
                {`Move to ${STATUS_LABEL[to].toLowerCase()}`}
              </Button>
            ))}{' '}
            <Button variant="quiet" busy={busy} disabled={note.trim().length < 2} onClick={() => void act()}>
              Add note only
            </Button>
          </p>
        </>
      )}
    </div>
  );
}
