import { useState, type FormEvent } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  Campus,
  Me,
  MyLesson,
  Substitution,
  TeacherOption,
  TimetableEntry,
  TimetablePeriod,
  TimetableStatus,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';

export const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/** Who may plan the timetable; the server enforces TIMETABLE_MANAGE regardless. */
export const canPlan = (me: Me) =>
  me.roles.some((r) => r.roleKey === 'group_admin' || r.roleKey === 'campus_admin');

const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
const hhmm = (t: string) => t.slice(0, 5);

export function TimetablePage({
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
      <PageHeader
        title="Timetable"
        lead="Your lessons for the day, and the weekly plan for each class."
      />
      <MyDay />
      {canPlan(me) && <Planner campuses={campuses} selectedCampusId={selectedCampusId} />}
    </>
  );
}

function MyDay() {
  const [date, setDate] = useState(today());
  const [lessons] = useApiResource<MyLesson[]>(`/api/v1/timetable/me?date=${date}`);
  return (
    <section aria-labelledby="myday-h">
      <h2 id="myday-h">My day</h2>
      <Field label="Date">
        {(p) => <input {...p} type="date" value={date} onChange={(e) => setDate(e.target.value)} />}
      </Field>
      {lessons.status === 'loading' && <LoadingState label="Loading your lessons" />}
      {lessons.status === 'error' && <ErrorState error={lessons.error} />}
      {lessons.status === 'ready' &&
        (lessons.data.length === 0 ? (
          <EmptyState title="No lessons on this day">
            <p>Either there is nothing scheduled for you, or the timetable is not published yet.</p>
          </EmptyState>
        ) : (
          <ol className="plain-list">
            {lessons.data.map((l) => (
              <li key={`${l.periodOrdinal}-${l.sectionLabel}`}>
                <strong>
                  {hhmm(l.startsAt)}–{hhmm(l.endsAt)}
                </strong>{' '}
                {l.subjectName}, {l.sectionLabel}
                {l.roomName ? `, ${l.roomName}` : ''}
                {l.coverFor && <StatusBadge tone="warning">{`Covering for ${l.coverFor}`}</StatusBadge>}
              </li>
            ))}
          </ol>
        ))}
    </section>
  );
}

function Planner({ campuses, selectedCampusId }: { campuses: Campus[]; selectedCampusId: string | null }) {
  const session = useApiSession();
  const [chosenCampus, setChosenCampus] = useState<string | null>(null);
  const [sectionId, setSectionId] = useState('');
  const campusId = selectedCampusId ?? chosenCampus ?? campuses[0]?.id ?? null;
  const [structure] = useApiResource<AcademicStructure>(
    campusId === null ? null : `/api/v1/campuses/${campusId}/academic-structure`,
  );
  const yearId = structure.status === 'ready' ? (structure.data.year?.id ?? null) : null;
  const [periods] = useApiResource<TimetablePeriod[]>(campusId ? `/api/v1/campuses/${campusId}/periods` : null);
  const [teachers] = useApiResource<TeacherOption[]>(campusId ? `/api/v1/campuses/${campusId}/teachers` : null);
  const [entries, reloadEntries] = useApiResource<TimetableEntry[]>(
    yearId && sectionId ? `/api/v1/academic-years/${yearId}/timetable?sectionId=${sectionId}` : null,
  );
  const [status, reloadStatus] = useApiResource<TimetableStatus>(
    yearId ? `/api/v1/academic-years/${yearId}/timetable/status` : null,
  );
  const [problems, setProblems] = useState<string[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const sections =
    structure.status === 'ready'
      ? structure.data.programmes.flatMap((p) =>
          p.grades.flatMap((g) => g.sections.map((s) => ({ id: s.id, label: `${g.name} ${s.code}`, programmeId: p.id }))),
        )
      : [];
  const programme =
    structure.status === 'ready'
      ? structure.data.programmes.find((p) => sections.find((s) => s.id === sectionId)?.programmeId === p.id)
      : undefined;
  const subjects = (programme?.subjects ?? []).filter((s) => s.status === 'ACTIVE');
  const lessonPeriods = periods.status === 'ready' ? periods.data.filter((p) => p.kind === 'LESSON') : [];

  async function act(path: string, method: string, body?: unknown, done?: string) {
    setBusy(true);
    setProblems([]);
    setNotice(null);
    try {
      await apiRequest(session, path, { method, body });
      if (done) setNotice(done);
      reloadEntries();
      reloadStatus();
    } catch (e) {
      const err = e instanceof ApiError ? e : null;
      setProblems(err && err.problems.length > 0 ? err.problems : [err?.detail ?? 'Something went wrong.']);
    } finally {
      setBusy(false);
    }
  }

  function addLesson(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    void act(
      `/api/v1/academic-years/${yearId}/timetable/entries`,
      'POST',
      {
        sectionId,
        day: Number(f.get('day')),
        periodId: f.get('period'),
        subjectId: f.get('subject'),
        teacherId: f.get('teacher'),
      },
      'Lesson added.',
    );
  }

  const cell = (day: number, ordinal: number) => entries.status === 'ready'
    ? entries.data.find((x) => x.day === day && x.periodOrdinal === ordinal)
    : undefined;

  return (
    <section aria-labelledby="plan-h">
      <h2 id="plan-h">Plan the week</h2>
      <div className="form form--inline">
        {selectedCampusId === null && campuses.length > 1 && (
          <Field label="Campus">
            {(p) => (
              <select {...p} value={campusId ?? ''} onChange={(e) => { setChosenCampus(e.target.value); setSectionId(''); }}>
                {campuses.map((c) => (
                  <option key={c.id} value={c.id}>{c.name}</option>
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
                <option key={s.id} value={s.id}>{s.label}</option>
              ))}
            </select>
          )}
        </Field>
      </div>

      {structure.status === 'loading' && <LoadingState label="Loading classes" />}
      {structure.status === 'error' && <ErrorState error={structure.error} />}

      {status.status === 'ready' && (
        <p>
          <StatusBadge tone={status.data.status === 'PUBLISHED' ? 'success' : 'neutral'}>
            {status.data.status === 'PUBLISHED' ? `Published (version ${status.data.version})` : 'Draft'}
          </StatusBadge>{' '}
          {status.data.status === 'DRAFT' && (
            <Button
              variant="primary"
              busy={busy}
              onClick={() => void act(`/api/v1/academic-years/${yearId}/timetable/publish`, 'POST', undefined, 'Timetable published.')}
            >
              Publish timetable
            </Button>
          )}
        </p>
      )}
      {status.status === 'ready' && status.data.clashes.length > 0 && (
        <div role="alert" className="notice">
          <p><strong>These clashes must be fixed before publishing:</strong></p>
          <ul>
            {status.data.clashes.map((c) => (
              <li key={c.text}>{c.text}</li>
            ))}
          </ul>
        </div>
      )}
      {problems.length > 0 && (
        <div role="alert" className="notice">
          <ul>
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </div>
      )}
      {notice && <p role="status">{notice}</p>}

      {sectionId === '' && structure.status === 'ready' && (
        <EmptyState title="Choose a class">
          <p>Pick a class to see and change its weekly timetable.</p>
        </EmptyState>
      )}
      {sectionId !== '' && entries.status === 'loading' && <LoadingState label="Loading the week" />}
      {sectionId !== '' && entries.status === 'error' && <ErrorState error={entries.error} />}
      {sectionId !== '' && entries.status === 'ready' && periods.status === 'ready' && (
        <div className="table-wrap" role="region" aria-label="Weekly timetable" tabIndex={0}>
          <table className="table">
            <caption>Weekly timetable for the chosen class</caption>
            <thead>
              <tr>
                <th scope="col">Period</th>
                {DAYS.map((d) => (
                  <th key={d} scope="col">{d}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {periods.data.map((p) => (
                <tr key={p.id}>
                  <th scope="row">
                    {p.label}
                    <br />
                    <small>{hhmm(p.startsAt)}–{hhmm(p.endsAt)}</small>
                  </th>
                  {DAYS.map((d, i) => {
                    const e = cell(i + 1, p.ordinal);
                    return (
                      <td key={d}>
                        {p.kind === 'BREAK' ? (
                          <em>Break</em>
                        ) : e ? (
                          <>
                            {e.subjectName}
                            <br />
                            <small>{e.teacherName}</small>
                            <br />
                            <Button
                              variant="quiet"
                              busy={busy}
                              aria-label={`Remove ${e.subjectName} on ${d}, ${p.label}`}
                              onClick={() => void act(`/api/v1/timetable/entries/${e.id}`, 'DELETE', undefined, 'Lesson removed.')}
                            >
                              Remove
                            </Button>
                          </>
                        ) : (
                          '—'
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {sectionId !== '' && (
        <form className="form" onSubmit={addLesson} aria-label="Add a lesson">
          <h3>Add a lesson</h3>
          <div className="form form--inline">
            <Field label="Day" required>
              {(p) => (
                <select {...p} name="day" defaultValue="1">
                  {DAYS.map((d, i) => (
                    <option key={d} value={i + 1}>{d}</option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Period" required>
              {(p) => (
                <select {...p} name="period">
                  {lessonPeriods.map((x) => (
                    <option key={x.id} value={x.id}>{x.label}</option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Subject" required>
              {(p) => (
                <select {...p} name="subject">
                  {subjects.map((x) => (
                    <option key={x.id} value={x.id}>{x.name}</option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Teacher" required>
              {(p) => (
                <select {...p} name="teacher">
                  {(teachers.status === 'ready' ? teachers.data : []).map((x) => (
                    <option key={x.id} value={x.id}>{x.name}</option>
                  ))}
                </select>
              )}
            </Field>
          </div>
          <Button type="submit" variant="primary" busy={busy}>Add lesson</Button>
        </form>
      )}

      {yearId && <SubstitutionsPanel yearId={yearId} teachers={teachers.status === 'ready' ? teachers.data : []} />}
    </section>
  );
}

function SubstitutionsPanel({ yearId, teachers }: { yearId: string; teachers: TeacherOption[] }) {
  const session = useApiSession();
  const [date, setDate] = useState(today());
  const [list, reload] = useApiResource<Substitution[]>(`/api/v1/academic-years/${yearId}/substitutions?from=${date}`);
  const [day, reloadDay] = useApiResource<TimetableEntry[]>(`/api/v1/academic-years/${yearId}/timetable`);
  const [entryId, setEntryId] = useState('');
  const [substituteId, setSubstituteId] = useState('');
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const weekday = (new Date(`${date}T00:00:00`).getDay() + 6) % 7 + 1;
  const lessons = day.status === 'ready' ? day.data.filter((e) => e.day === weekday) : [];
  const [free] = useApiResource<TeacherOption[]>(
    entryId ? `/api/v1/academic-years/${yearId}/substitutions/available?entryId=${entryId}&date=${date}` : null,
  );
  const choices = free.status === 'ready' ? free.data : teachers;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      await apiRequest(session, `/api/v1/academic-years/${yearId}/substitutions`, {
        method: 'POST',
        body: { entryId, onDate: date, substituteId, reason },
      });
      setMessage('Substitute arranged.');
      setReason('');
      reload();
      reloadDay();
    } catch (err) {
      setMessage(err instanceof ApiError ? err.detail : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="sub-h">
      <h2 id="sub-h">Substitutions</h2>
      <form className="form" onSubmit={submit} aria-label="Arrange a substitute">
        <div className="form form--inline">
          <Field label="Date" required>
            {(p) => <input {...p} type="date" value={date} onChange={(e) => { setDate(e.target.value); setEntryId(''); }} />}
          </Field>
          <Field label="Lesson to cover" required>
            {(p) => (
              <select {...p} value={entryId} onChange={(e) => setEntryId(e.target.value)}>
                <option value="">Choose a lesson</option>
                {lessons.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.periodLabel}: {l.sectionLabel}, {l.subjectName} ({l.teacherName})
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Substitute" hint="Only teachers who are free are listed." required>
            {(p) => (
              <select {...p} value={substituteId} onChange={(e) => setSubstituteId(e.target.value)}>
                <option value="">Choose a teacher</option>
                {choices.map((t) => (
                  <option key={t.id} value={t.id}>{t.name}</option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Reason" required>
            {(p) => <input {...p} value={reason} onChange={(e) => setReason(e.target.value)} minLength={3} maxLength={200} />}
          </Field>
        </div>
        <Button type="submit" variant="primary" busy={busy} disabled={!entryId || !substituteId || reason.trim().length < 3}>
          Arrange substitute
        </Button>
        {message && <p role="status">{message}</p>}
      </form>
      {list.status === 'ready' && list.data.length > 0 && (
        <ul className="plain-list">
          {list.data.map((s) => (
            <li key={s.id}>
              {s.onDate}, {s.periodLabel}, {s.sectionLabel}: {s.substituteName} covers {s.absentTeacherName} ({s.reason})
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
