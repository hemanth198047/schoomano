import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  Campus,
  Me,
  StudentListItem,
  SupportCaseDetail,
  SupportCaseSummary,
  SupportStatus,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';

/** Only the principal and administrators hold student-support access; the server decides, this just hides the screen. */
export const canSeeStudentSupport = (me: Me) =>
  me.roles.some((r) => ['principal', 'campus_admin', 'group_admin'].includes(r.roleKey));

const CATEGORIES: Record<string, string> = {
  LEARNING: 'Learning',
  BEHAVIOUR: 'Behaviour',
  WELLBEING: 'Wellbeing',
  ATTENDANCE: 'Attendance',
  FAMILY: 'Family',
  OTHER: 'Other',
};
const STATUS_LABEL: Record<SupportStatus, string> = {
  OPEN: 'Open',
  MONITORING: 'Monitoring',
  CLOSED: 'Closed',
};
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function StudentSupportPage({
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
        title="Student support"
        lead="Confidential cases about children who need extra help. Every time a case is opened it is recorded."
      />
      {!canSeeStudentSupport(me) ? (
        <EmptyState title="Not available">
          <p>Student support cases are limited to the principal and administrators.</p>
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
            <Cases key={structure.data.year.id} yearId={structure.data.year.id} />
          )}
        </>
      )}
    </>
  );
}

function Cases({ yearId }: { yearId: string }) {
  const [status, setStatus] = useState('');
  const [list, reload] = useApiResource<SupportCaseSummary[]>(
    `/api/v1/academic-years/${yearId}/support-cases${status ? `?status=${status}` : ''}`,
  );
  const [openId, setOpenId] = useState<string | null>(null);
  return (
    <>
      <NewCase
        yearId={yearId}
        onCreated={(id) => {
          reload();
          setOpenId(id);
        }}
      />
      <section aria-labelledby="cases-h">
        <h2 id="cases-h">Cases</h2>
        <div className="form form--inline">
          <Field label="Show">
            {(p) => (
              <select {...p} value={status} onChange={(e) => setStatus(e.target.value)}>
                <option value="">Everything</option>
                {(Object.keys(STATUS_LABEL) as SupportStatus[]).map((k) => (
                  <option key={k} value={k}>
                    {STATUS_LABEL[k]}
                  </option>
                ))}
              </select>
            )}
          </Field>
        </div>
        {list.status === 'loading' && <LoadingState label="Loading cases" />}
        {list.status === 'error' && <ErrorState error={list.error} onRetry={reload} />}
        {list.status === 'ready' &&
          (list.data.length === 0 ? (
            <EmptyState title="No cases here" />
          ) : (
            <ul className="plain-list">
              {list.data.map((c) => (
                <li key={c.id}>
                  <p>
                    <strong>{c.studentName}</strong>: {c.title}{' '}
                    <span className="muted">{CATEGORIES[c.category] ?? c.category}</span>{' '}
                    <StatusBadge
                      tone={c.status === 'CLOSED' ? 'neutral' : c.status === 'OPEN' ? 'warning' : 'info'}
                    >
                      {STATUS_LABEL[c.status]}
                    </StatusBadge>{' '}
                    {c.reviewDue && <StatusBadge tone="warning">Review due</StatusBadge>}{' '}
                    <Button variant="quiet" onClick={() => setOpenId(openId === c.id ? null : c.id)}>
                      {openId === c.id
                        ? `Close ${c.studentName}'s case view`
                        : `Open ${c.studentName}'s case`}
                    </Button>
                  </p>
                  {openId === c.id && <CaseView key={c.id} id={c.id} onChanged={reload} />}
                </li>
              ))}
            </ul>
          ))}
      </section>
    </>
  );
}

function NewCase({ yearId, onCreated }: { yearId: string; onCreated: (id: string) => void }) {
  const session = useApiSession();
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [student, setStudent] = useState<StudentListItem | null>(null);
  const [category, setCategory] = useState('WELLBEING');
  const [title, setTitle] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [found] = useApiResource<{ students: StudentListItem[] }>(
    submitted ? `/api/v1/academic-years/${yearId}/students?limit=8&q=${encodeURIComponent(submitted)}` : null,
  );

  async function create() {
    if (!student) return;
    setBusy(true);
    setError(null);
    try {
      const c = await apiRequest<SupportCaseSummary>(
        session,
        `/api/v1/students/${student.id}/support-cases`,
        {
          method: 'POST',
          body: { category, title: title.trim(), firstNote: note.trim() || undefined },
        },
      );
      setStudent(null);
      setTitle('');
      setNote('');
      onCreated(c.id);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="new-case-h">
      <h2 id="new-case-h">Open a case</h2>
      {!student ? (
        <>
          <div className="form form--inline">
            <Field label="Find the child" hint="Name or school reference">
              {(p) => <input {...p} value={query} onChange={(e) => setQuery(e.target.value)} />}
            </Field>
          </div>
          <p className="form__actions">
            <Button disabled={query.trim().length < 2} onClick={() => setSubmitted(query.trim())}>
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
            Case for <strong>{student.displayName}</strong>{' '}
            <Button variant="quiet" onClick={() => setStudent(null)}>
              Choose someone else
            </Button>
          </p>
          <div className="form form--inline">
            <Field label="Category">
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
            <Field label="Title">
              {(p) => (
                <input {...p} value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} />
              )}
            </Field>
            <Field label="First note" hint="Optional">
              {(p) => (
                <input {...p} value={note} maxLength={4000} onChange={(e) => setNote(e.target.value)} />
              )}
            </Field>
          </div>
          {error && (
            <p className="notice" role="alert">
              {error}
            </p>
          )}
          <p className="form__actions">
            <Button
              variant="primary"
              busy={busy}
              disabled={title.trim().length < 2}
              onClick={() => void create()}
            >
              Open case
            </Button>
          </p>
        </>
      )}
    </section>
  );
}

function CaseView({ id, onChanged }: { id: string; onChanged: () => void }) {
  const session = useApiSession();
  const [detail, reload] = useApiResource<SupportCaseDetail>(`/api/v1/support-cases/${id}`);
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (detail.status === 'loading') return <LoadingState label="Opening case" />;
  if (detail.status === 'error') return <ErrorState error={detail.error} onRetry={reload} />;
  const { summary, notes, closedReason } = detail.data;
  const closed = summary.status === 'CLOSED';

  async function run(fn: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      setNote('');
      setReason('');
      setClosing(false);
      reload();
      onChanged();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  const change = (to: SupportStatus) =>
    run(() =>
      apiRequest(session, `/api/v1/support-cases/${id}/status`, {
        method: 'POST',
        body: { status: to, reason: to === 'CLOSED' ? reason.trim() : undefined },
      }),
    );

  return (
    <div>
      <ol className="plain-list" aria-label="Notes">
        {notes.map((n) => (
          <li key={n.id}>
            <p>{n.body}</p>
            <p className="muted">
              {n.author}, {n.at.slice(0, 10)}
            </p>
          </li>
        ))}
      </ol>
      {closed && closedReason && <p className="notice">Closed: {closedReason}</p>}
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      {!closed && (
        <>
          <Field label="Add a note" hint="Notes cannot be edited or removed.">
            {(p) => <input {...p} value={note} maxLength={4000} onChange={(e) => setNote(e.target.value)} />}
          </Field>
          <p className="form__actions">
            <Button
              variant="primary"
              busy={busy}
              disabled={note.trim().length < 2}
              onClick={() =>
                void run(() =>
                  apiRequest(session, `/api/v1/support-cases/${id}/notes`, {
                    method: 'POST',
                    body: { body: note.trim() },
                  }),
                )
              }
            >
              Add note
            </Button>{' '}
            {summary.status === 'OPEN' && (
              <Button busy={busy} onClick={() => void change('MONITORING')}>
                Start monitoring
              </Button>
            )}{' '}
            {summary.status === 'MONITORING' && (
              <Button busy={busy} onClick={() => void change('OPEN')}>
                Back to open
              </Button>
            )}{' '}
            <Button variant="quiet" onClick={() => setClosing(!closing)}>
              Close case
            </Button>
          </p>
          {closing && (
            <>
              <Field label="Why is it being closed?" hint="At least 5 characters. Closing is final." required>
                {(p) => (
                  <input {...p} value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
                )}
              </Field>
              <p className="form__actions">
                <Button
                  variant="primary"
                  busy={busy}
                  disabled={reason.trim().length < 5}
                  onClick={() => void change('CLOSED')}
                >
                  Confirm closing
                </Button>
              </p>
            </>
          )}
        </>
      )}
    </div>
  );
}
