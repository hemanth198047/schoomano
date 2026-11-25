import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { AcademicStructure, Campus, Coverage, HomeworkItem, Me } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';

/** The server enforces LEARNING_WRITE and CURRICULUM_MANAGE regardless; these decide which buttons to show. */
export const canTeach = (me: Me) =>
  me.roles.some((r) => ['teacher', 'campus_admin', 'group_admin'].includes(r.roleKey));
export const canEditSyllabus = (me: Me) =>
  me.roles.some((r) => ['campus_admin', 'group_admin'].includes(r.roleKey));

interface ClassChoice {
  id: string;
  gradeId: string;
  label: string;
  subjects: { id: string; name: string }[];
}
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function LearningPage({
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
  const [classId, setClassId] = useState('');
  const classes: ClassChoice[] =
    structure.status === 'ready'
      ? structure.data.programmes.flatMap((p) =>
          p.grades.flatMap((g) =>
            g.sections.map((s) => ({
              id: s.id,
              gradeId: g.id,
              label: `${g.name} ${s.code}`,
              subjects: p.subjects
                .filter((x) => x.status === 'ACTIVE')
                .map((x) => ({ id: x.id, name: x.name })),
            })),
          ),
        )
      : [];
  const chosen = classes.find((c) => c.id === classId);
  return (
    <>
      <PageHeader
        title="Syllabus and homework"
        lead="See how far each class has got through the syllabus, and set homework that parents can see."
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
                  setClassId('');
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
            <select {...p} value={classId} onChange={(e) => setClassId(e.target.value)}>
              <option value="">Choose a class</option>
              {classes.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.label}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      {structure.status === 'loading' && <LoadingState label="Loading classes" />}
      {structure.status === 'error' && <ErrorState error={structure.error} />}
      {!chosen && structure.status === 'ready' && (
        <EmptyState title="Choose a class">
          <p>Pick a class to see its homework and syllabus.</p>
        </EmptyState>
      )}
      {chosen && <ClassLearning key={chosen.id} me={me} cls={chosen} />}
    </>
  );
}

function ClassLearning({ me, cls }: { me: Me; cls: ClassChoice }) {
  return (
    <>
      <Homework me={me} cls={cls} />
      <SyllabusCoverage me={me} cls={cls} />
    </>
  );
}

function Homework({ me, cls }: { me: Me; cls: ClassChoice }) {
  const session = useApiSession();
  const [list, reload] = useApiResource<HomeworkItem[]>(`/api/v1/sections/${cls.id}/homework`);
  const [subjectId, setSubjectId] = useState('');
  const [title, setTitle] = useState('');
  const [instructions, setInstructions] = useState('');
  const [dueOn, setDueOn] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const valid = subjectId !== '' && title.trim().length >= 2 && dueOn !== '';

  async function add() {
    setBusy(true);
    setError(null);
    try {
      await apiRequest<HomeworkItem>(session, `/api/v1/sections/${cls.id}/homework`, {
        method: 'POST',
        body: { subjectId, title: title.trim(), instructions: instructions.trim() || undefined, dueOn },
      });
      setTitle('');
      setInstructions('');
      reload();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  async function cancel(id: string) {
    setError(null);
    try {
      await apiRequest<HomeworkItem>(session, `/api/v1/homework/${id}/cancel`, { method: 'POST' });
      reload();
    } catch (e) {
      setError(message(e));
    }
  }

  return (
    <section aria-labelledby="hw-h">
      <h2 id="hw-h">Homework, {cls.label}</h2>
      {list.status === 'loading' && <LoadingState label="Loading homework" />}
      {list.status === 'error' && <ErrorState error={list.error} onRetry={reload} />}
      {list.status === 'ready' &&
        (list.data.length === 0 ? (
          <EmptyState title="No homework set">
            <p>Nothing set in the last week or coming up.</p>
          </EmptyState>
        ) : (
          <ul className="plain-list">
            {list.data.map((h) => (
              <li key={h.id}>
                <p>
                  <strong>{h.subjectName}</strong>: {h.title} <span className="muted">due {h.dueOn}</span>{' '}
                  {h.status === 'CANCELLED' && <StatusBadge tone="warning">Cancelled</StatusBadge>}{' '}
                  {h.status === 'ACTIVE' && canTeach(me) && (
                    <Button variant="quiet" onClick={() => void cancel(h.id)}>
                      {`Cancel ${h.title}`}
                    </Button>
                  )}
                </p>
                {h.instructions && <p className="muted">{h.instructions}</p>}
              </li>
            ))}
          </ul>
        ))}
      {canTeach(me) && (
        <>
          <h3>Set homework</h3>
          <div className="form form--inline">
            <Field label="Subject">
              {(p) => (
                <select {...p} value={subjectId} onChange={(e) => setSubjectId(e.target.value)}>
                  <option value="">Choose a subject</option>
                  {cls.subjects.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
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
            <Field label="Due on">
              {(p) => <input {...p} type="date" value={dueOn} onChange={(e) => setDueOn(e.target.value)} />}
            </Field>
            <Field label="Instructions" hint="Optional">
              {(p) => (
                <input
                  {...p}
                  value={instructions}
                  maxLength={2000}
                  onChange={(e) => setInstructions(e.target.value)}
                />
              )}
            </Field>
          </div>
          {error && (
            <p className="notice" role="alert">
              {error}
            </p>
          )}
          <p className="form__actions">
            <Button variant="primary" busy={busy} disabled={!valid} onClick={() => void add()}>
              Set homework
            </Button>
          </p>
        </>
      )}
    </section>
  );
}

function SyllabusCoverage({ me, cls }: { me: Me; cls: ClassChoice }) {
  const [subjectId, setSubjectId] = useState('');
  return (
    <section aria-labelledby="syl-h">
      <h2 id="syl-h">Syllabus covered</h2>
      <div className="form form--inline">
        <Field label="Subject">
          {(p) => (
            <select {...p} value={subjectId} onChange={(e) => setSubjectId(e.target.value)}>
              <option value="">Choose a subject</option>
              {cls.subjects.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      {subjectId !== '' && <Units key={subjectId} me={me} cls={cls} subjectId={subjectId} />}
    </section>
  );
}

function Units({ me, cls, subjectId }: { me: Me; cls: ClassChoice; subjectId: string }) {
  const session = useApiSession();
  const [cov, reload] = useApiResource<Coverage>(
    `/api/v1/sections/${cls.id}/coverage?subjectId=${subjectId}`,
  );
  const [title, setTitle] = useState('');
  const [outcomes, setOutcomes] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function mark(unitId: string) {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/sections/${cls.id}/coverage`, { method: 'POST', body: { unitId } });
      reload();
    } catch (e) {
      setError(message(e));
    }
  }
  async function addUnit() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/grade-levels/${cls.gradeId}/curriculum`, {
        method: 'POST',
        body: { subjectId, title: title.trim(), outcomes: outcomes.trim() || undefined },
      });
      setTitle('');
      setOutcomes('');
      reload();
    } catch (e) {
      setError(message(e));
    }
  }

  if (cov.status === 'loading') return <LoadingState label="Loading syllabus" />;
  if (cov.status === 'error') return <ErrorState error={cov.error} onRetry={reload} />;
  const { units, covered, total } = cov.data;
  return (
    <>
      {total === 0 ? (
        <EmptyState title="No syllabus units yet">
          <p>The syllabus for this grade and subject has not been written.</p>
        </EmptyState>
      ) : (
        <>
          <p role="status">
            {covered} of {total} units covered ({Math.round((covered * 100) / total)}%)
          </p>
          <ol className="plain-list">
            {units.map((u) => (
              <li key={u.id}>
                <strong>{u.title}</strong>{' '}
                {u.coveredOn ? (
                  <StatusBadge tone="success">{`Covered ${u.coveredOn}`}</StatusBadge>
                ) : (
                  canTeach(me) && (
                    <Button variant="quiet" onClick={() => void mark(u.id)}>
                      {`Mark ${u.title} as covered`}
                    </Button>
                  )
                )}
                {u.outcomes && <p className="muted">{u.outcomes}</p>}
              </li>
            ))}
          </ol>
        </>
      )}
      {canEditSyllabus(me) && (
        <>
          <h3>Add a unit</h3>
          <div className="form form--inline">
            <Field label="Unit title">
              {(p) => (
                <input {...p} value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} />
              )}
            </Field>
            <Field label="What children should learn" hint="Optional">
              {(p) => (
                <input
                  {...p}
                  value={outcomes}
                  maxLength={1000}
                  onChange={(e) => setOutcomes(e.target.value)}
                />
              )}
            </Field>
          </div>
          {error && (
            <p className="notice" role="alert">
              {error}
            </p>
          )}
          <p className="form__actions">
            <Button variant="primary" disabled={title.trim().length < 2} onClick={() => void addUnit()}>
              Add unit
            </Button>
          </p>
        </>
      )}
      {!canEditSyllabus(me) && error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
    </>
  );
}
