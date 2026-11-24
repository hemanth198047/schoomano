import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  AttendanceBatchResult,
  AttendanceConflict,
  AttendanceRoster,
  AttendanceStatus,
  Campus,
  Me,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { flush, newBatch, type PendingBatch } from '../lib/attendanceQueue';

const STATUSES: { value: AttendanceStatus; label: string }[] = [
  { value: 'PRESENT', label: 'Present' },
  { value: 'ABSENT', label: 'Absent' },
  { value: 'LATE', label: 'Late' },
  { value: 'LEAVE', label: 'On leave' },
];
const label = (s: AttendanceStatus) => STATUSES.find((x) => x.value === s)?.label ?? s;
const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });

/** Who may correct marks and settle conflicts; the server enforces ATTENDANCE_CORRECT regardless. */
export const canCorrect = (me: Me) =>
  me.roles.some((r) => ['group_admin', 'campus_admin', 'principal'].includes(r.roleKey));
export const canMark = (me: Me) => me.roles.some((r) => r.roleKey === 'teacher');

export function AttendancePage({
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
  const [sectionId, setSectionId] = useState('');
  const [date, setDate] = useState(today());
  const sections =
    structure.status === 'ready'
      ? structure.data.programmes.flatMap((p) =>
          p.grades.flatMap((g) => g.sections.map((s) => ({ id: s.id, label: `${g.name} ${s.code}` }))),
        )
      : [];

  return (
    <>
      <PageHeader
        title="Attendance"
        lead="Mark the day for a class. If the connection drops, your marks wait on this screen and are sent when it returns."
      />
      <div className="form form--inline">
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
        <Field label="Class">
          {(p) => (
            <select {...p} value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
              <option value="">Choose a class</option>
              {sections.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.label}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Date">
          {(p) => (
            <input {...p} type="date" value={date} max={today()} onChange={(e) => setDate(e.target.value)} />
          )}
        </Field>
      </div>
      {structure.status === 'loading' && <LoadingState label="Loading classes" />}
      {structure.status === 'error' && <ErrorState error={structure.error} />}
      {sectionId === '' && structure.status === 'ready' && (
        <EmptyState title="Choose a class">
          <p>Pick a class and a day to see the register.</p>
        </EmptyState>
      )}
      {sectionId !== '' && (
        <Register key={`${sectionId}-${date}`} me={me} sectionId={sectionId} date={date} />
      )}
      {canCorrect(me) && campusId && <Conflicts campusId={campusId} />}
    </>
  );
}

function Register({ me, sectionId, date }: { me: Me; sectionId: string; date: string }) {
  const session = useApiSession();
  const [roster, reload] = useApiResource<AttendanceRoster>(
    `/api/v1/sections/${sectionId}/attendance?date=${date}`,
  );
  const [edits, setEdits] = useState<Record<string, AttendanceStatus>>({});
  const [queue, setQueue] = useState<PendingBatch[]>([]);
  const queueRef = useRef<PendingBatch[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const editable = canMark(me);

  const send = useCallback(
    (b: PendingBatch) =>
      apiRequest<AttendanceBatchResult>(session, `/api/v1/sections/${b.sectionId}/attendance/batches`, {
        method: 'POST',
        body: { date: b.date, source: 'WEB', marks: b.marks },
        // The key rides in a header so a retry is recognised even if the body was lost on the way.
        headers: { 'Idempotency-Key': b.key },
      }),
    [session],
  );

  const sendQueue = useCallback(async () => {
    if (queueRef.current.length === 0) return;
    setBusy(true);
    const out = await flush(queueRef.current, send);
    queueRef.current = out.remaining;
    setQueue(out.remaining);
    const conflicts = out.results.reduce((n, r) => n + r.result.conflicts, 0);
    if (out.rejected) setMessage(out.rejected.error.detail);
    else if (out.remaining.length > 0)
      setMessage('Not sent yet. Your marks are kept here and will be sent when the connection returns.');
    else if (conflicts > 0)
      setMessage(
        `Saved. ${conflicts} mark${conflicts === 1 ? ' was' : 's were'} changed by the office meanwhile, so yours ${conflicts === 1 ? 'is' : 'are'} held for review.`,
      );
    else if (out.results.length > 0) setMessage('Saved.');
    if (out.results.length > 0) {
      setEdits({});
      reload();
    }
    setBusy(false);
  }, [reload, send]);

  useEffect(() => {
    const again = () => void sendQueue();
    window.addEventListener('online', again);
    return () => window.removeEventListener('online', again);
  }, [sendQueue]);

  if (roster.status === 'loading') return <LoadingState label="Loading the register" />;
  if (roster.status === 'error') return <ErrorState error={roster.error} onRetry={reload} />;
  const data = roster.data;
  const current = (id: string, s: AttendanceStatus | null) => edits[id] ?? s;
  const changed = data.students.filter(
    (s) => edits[s.studentId] !== undefined && edits[s.studentId] !== s.status,
  );

  function save() {
    const batch = newBatch(
      sectionId,
      date,
      changed.map((s) => ({
        studentId: s.studentId,
        status: edits[s.studentId]!,
        baseVersion: s.version ?? 0,
        markedAt: new Date().toISOString(),
      })),
    );
    queueRef.current = [...queueRef.current, batch];
    setQueue(queueRef.current);
    setMessage(null);
    void sendQueue();
  }

  const unmarked = data.students.filter((s) => current(s.studentId, s.status) === null).length;

  return (
    <section aria-labelledby="register-h">
      <h2 id="register-h">
        {data.sectionLabel}, {date}
      </h2>
      {data.openConflicts > 0 && (
        <p className="notice" role="status">
          {data.openConflicts} mark{data.openConflicts === 1 ? ' is' : 's are'} waiting for the office to
          review.
        </p>
      )}
      {data.students.length === 0 ? (
        <EmptyState title="No students in this class">
          <p>Students appear here once they are placed in the class.</p>
        </EmptyState>
      ) : (
        <>
          {editable && (
            <p>
              <Button
                variant="secondary"
                onClick={() =>
                  setEdits(
                    Object.fromEntries(
                      data.students
                        .filter((s) => s.status === null)
                        .map((s) => [s.studentId, 'PRESENT' as const]),
                    ),
                  )
                }
              >
                Mark everyone not yet marked as present
              </Button>{' '}
              <span className="muted">{unmarked} not marked</span>
            </p>
          )}
          <ul className="plain-list">
            {data.students.map((s) => {
              const value = current(s.studentId, s.status);
              return (
                <li key={s.studentId}>
                  <fieldset disabled={!editable}>
                    <legend>
                      {s.name} <small className="muted">{s.schoolRef}</small>
                    </legend>
                    {STATUSES.map((o) => (
                      <label key={o.value} style={{ marginInlineEnd: '1rem' }}>
                        <input
                          type="radio"
                          name={`att-${s.studentId}`}
                          checked={value === o.value}
                          onChange={() => setEdits((e) => ({ ...e, [s.studentId]: o.value }))}
                        />{' '}
                        {o.label}
                      </label>
                    ))}
                    {edits[s.studentId] !== undefined && edits[s.studentId] !== s.status && (
                      <StatusBadge tone="info">Not saved</StatusBadge>
                    )}
                  </fieldset>
                </li>
              );
            })}
          </ul>
          {editable && (
            <p className="form__actions">
              <Button variant="primary" busy={busy} disabled={changed.length === 0} onClick={save}>
                Save attendance
              </Button>
              {queue.length > 0 && (
                <>
                  {' '}
                  <StatusBadge tone="warning">{`${queue.length} waiting to send`}</StatusBadge>{' '}
                  <Button variant="quiet" busy={busy} onClick={() => void sendQueue()}>
                    Send now
                  </Button>
                </>
              )}
            </p>
          )}
          {!editable && (
            <p className="muted">Only teachers mark attendance. Office staff correct it below.</p>
          )}
          {message && <p role="status">{message}</p>}
        </>
      )}
    </section>
  );
}

function Conflicts({ campusId }: { campusId: string }) {
  const session = useApiSession();
  const [list, reload] = useApiResource<AttendanceConflict[]>(
    `/api/v1/campuses/${campusId}/attendance/conflicts`,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function resolve(c: AttendanceConflict, useAttempt: boolean) {
    setBusy(true);
    setError(null);
    try {
      await apiRequest(session, `/api/v1/attendance/conflicts/${c.id}/resolve`, {
        method: 'POST',
        body: { useAttempt, reason: useAttempt ? 'Teacher entry confirmed' : 'Office entry kept' },
      });
      reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.detail : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  if (list.status !== 'ready' || list.data.length === 0) return null;
  return (
    <section aria-labelledby="conf-h">
      <h2 id="conf-h">Marks to review</h2>
      <p className="muted">
        A teacher marked these while the office had already changed them. Nothing was overwritten; choose
        which to keep.
      </p>
      {error && <p role="alert">{error}</p>}
      <ul className="plain-list">
        {list.data.map((c) => (
          <li key={c.id}>
            <strong>{c.studentName}</strong>, {c.sectionLabel}, {c.date}: office has{' '}
            <em>{label(c.currentStatus)}</em>, {c.attemptedBy} marked <em>{label(c.attemptedStatus)}</em>.{' '}
            <Button variant="secondary" busy={busy} onClick={() => void resolve(c, false)}>
              {`Keep ${label(c.currentStatus)}`}
            </Button>{' '}
            <Button variant="secondary" busy={busy} onClick={() => void resolve(c, true)}>
              {`Use ${label(c.attemptedStatus)}`}
            </Button>
          </li>
        ))}
      </ul>
    </section>
  );
}
