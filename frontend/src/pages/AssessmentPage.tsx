import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  AssessmentItem,
  AssessmentMarks,
  Campus,
  MarkKind,
  MarksResult,
  Me,
  PublishResult,
  ReportPreview,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';

/** Who may enter marks and who may publish; the server enforces ASSESSMENT_WRITE and REPORT_PUBLISH regardless. */
export const canEnterMarks = (me: Me) =>
  me.roles.some((r) => ['teacher', 'campus_admin', 'group_admin'].includes(r.roleKey));
export const canPublish = (me: Me) =>
  me.roles.some((r) => ['principal', 'campus_admin', 'group_admin'].includes(r.roleKey));

interface SectionChoice {
  id: string;
  label: string;
  subjects: { id: string; name: string }[];
  terms: { id: string; label: string }[];
}

const KIND_LABEL: Record<MarkKind, string> = { SCORED: 'Score', ABSENT: 'Absent', EXEMPT: 'Exempt' };
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function AssessmentPage({
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
  const [termId, setTermId] = useState('');
  const sections: SectionChoice[] =
    structure.status === 'ready'
      ? structure.data.programmes.flatMap((p) =>
          p.grades.flatMap((g) =>
            g.sections.map((s) => ({
              id: s.id,
              label: `${g.name} ${s.code}`,
              subjects: p.subjects
                .filter((x) => x.status === 'ACTIVE')
                .map((x) => ({ id: x.id, name: x.name })),
              terms: p.terms.map((t) => ({ id: t.id, label: t.label })),
            })),
          ),
        )
      : [];
  const section = sections.find((s) => s.id === sectionId);

  return (
    <>
      <PageHeader
        title="Marks and report cards"
        lead="Set up the tests for a class, enter the marks, lock them, and publish report cards once everything is in."
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
                  setTermId('');
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
            <select
              {...p}
              value={sectionId}
              onChange={(e) => {
                setSectionId(e.target.value);
                setTermId('');
              }}
            >
              <option value="">Choose a class</option>
              {sections.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.label}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Term">
          {(p) => (
            <select {...p} value={termId} onChange={(e) => setTermId(e.target.value)} disabled={!section}>
              <option value="">Choose a term</option>
              {(section?.terms ?? []).map((t) => (
                <option key={t.id} value={t.id}>
                  {t.label}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      {structure.status === 'loading' && <LoadingState label="Loading classes" />}
      {structure.status === 'error' && <ErrorState error={structure.error} />}
      {(!section || termId === '') && structure.status === 'ready' && (
        <EmptyState title="Choose a class and a term">
          <p>Pick both to see the assessments and report cards.</p>
        </EmptyState>
      )}
      {section && termId !== '' && (
        <ClassTerm key={`${sectionId}-${termId}`} me={me} section={section} termId={termId} />
      )}
    </>
  );
}

function ClassTerm({ me, section, termId }: { me: Me; section: SectionChoice; termId: string }) {
  const [list, reload] = useApiResource<AssessmentItem[]>(
    `/api/v1/sections/${section.id}/assessments?termId=${termId}`,
  );
  const [open, setOpen] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const changed = () => {
    reload();
    setVersion((v) => v + 1);
  };
  if (list.status === 'loading') return <LoadingState label="Loading assessments" />;
  if (list.status === 'error') return <ErrorState error={list.error} onRetry={reload} />;
  return (
    <>
      <section aria-labelledby="as-h">
        <h2 id="as-h">Assessments, {section.label}</h2>
        {list.data.length === 0 ? (
          <EmptyState title="No assessments yet">
            <p>Add the first test or task for this class and term.</p>
          </EmptyState>
        ) : (
          <ul className="plain-list">
            {list.data.map((a) => (
              <li key={a.id}>
                <p>
                  <strong>{a.subjectName}</strong>: {a.name}{' '}
                  <span className="muted">
                    out of {a.maxMarks}, weight {a.weightPercent}%
                  </span>{' '}
                  <StatusBadge tone={a.status === 'LOCKED' ? 'success' : 'info'}>
                    {a.status === 'LOCKED' ? 'Locked' : 'Open'}
                  </StatusBadge>{' '}
                  <span className="muted">
                    {a.marksEntered} of {a.students} entered
                  </span>{' '}
                  <Button variant="quiet" onClick={() => setOpen(open === a.id ? null : a.id)}>
                    {open === a.id ? 'Close marks' : 'Marks'}
                  </Button>
                </p>
                {open === a.id && <MarksEditor key={a.id} me={me} assessmentId={a.id} onChanged={changed} />}
              </li>
            ))}
          </ul>
        )}
      </section>
      {canEnterMarks(me) && <NewAssessment section={section} termId={termId} onCreated={changed} />}
      <ReportCards key={version} me={me} sectionId={section.id} termId={termId} />
    </>
  );
}

function NewAssessment({
  section,
  termId,
  onCreated,
}: {
  section: SectionChoice;
  termId: string;
  onCreated: () => void;
}) {
  const session = useApiSession();
  const [subjectId, setSubjectId] = useState('');
  const [name, setName] = useState('');
  const [max, setMax] = useState('');
  const [weight, setWeight] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const valid =
    subjectId !== '' &&
    name.trim().length >= 2 &&
    Number(max) > 0 &&
    Number(weight) >= 1 &&
    Number(weight) <= 100;

  async function create() {
    setBusy(true);
    setError(null);
    try {
      await apiRequest<AssessmentItem>(session, `/api/v1/sections/${section.id}/assessments`, {
        method: 'POST',
        body: { subjectId, termId, name: name.trim(), maxMarks: Number(max), weightPercent: Number(weight) },
      });
      setName('');
      setMax('');
      setWeight('');
      onCreated();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="new-as-h">
      <h2 id="new-as-h">Add an assessment</h2>
      <div className="form form--inline">
        <Field label="Subject">
          {(p) => (
            <select {...p} value={subjectId} onChange={(e) => setSubjectId(e.target.value)}>
              <option value="">Choose a subject</option>
              {section.subjects.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Name" hint="For example Unit test 1">
          {(p) => <input {...p} value={name} maxLength={80} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Out of (marks)">
          {(p) => <input {...p} inputMode="decimal" value={max} onChange={(e) => setMax(e.target.value)} />}
        </Field>
        <Field label="Weight (%)" hint="How much it counts within the subject">
          {(p) => (
            <input {...p} inputMode="numeric" value={weight} onChange={(e) => setWeight(e.target.value)} />
          )}
        </Field>
      </div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <p className="form__actions">
        <Button variant="primary" busy={busy} disabled={!valid} onClick={() => void create()}>
          Add assessment
        </Button>
      </p>
    </section>
  );
}

interface Draft {
  kind: MarkKind;
  marks: string;
}

function MarksEditor({
  me,
  assessmentId,
  onChanged,
}: {
  me: Me;
  assessmentId: string;
  onChanged: () => void;
}) {
  const session = useApiSession();
  const [data, reload] = useApiResource<AssessmentMarks>(`/api/v1/assessments/${assessmentId}/marks`);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [reason, setReason] = useState('');
  const [info, setInfo] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (data.status === 'loading') return <LoadingState label="Loading marks" />;
  if (data.status === 'error') return <ErrorState error={data.error} onRetry={reload} />;

  const { assessment, marks } = data.data;
  const locked = assessment.status === 'LOCKED';
  const editable = canEnterMarks(me);
  const current = (studentId: string): Draft | null => {
    const d = drafts[studentId];
    if (d) return d;
    const m = marks.find((x) => x.studentId === studentId);
    return m && m.kind ? { kind: m.kind, marks: m.marks === null ? '' : String(m.marks) } : null;
  };
  const changes = marks.filter((m) => {
    const d = drafts[m.studentId];
    if (!d) return false;
    const was = m.kind ? `${m.kind}|${m.marks ?? ''}` : '';
    return was !== `${d.kind}|${d.kind === 'SCORED' && d.marks !== '' ? Number(d.marks) : ''}`;
  });
  const complete = changes.every((m) => {
    const d = drafts[m.studentId]!;
    return (
      d.kind !== 'SCORED' ||
      (d.marks !== '' && Number(d.marks) >= 0 && Number(d.marks) <= assessment.maxMarks)
    );
  });
  const needsReason = locked && changes.length > 0;
  const reasonOk = !needsReason || reason.trim().length >= 5;

  async function save() {
    setBusy(true);
    setInfo(null);
    try {
      const result = await apiRequest<MarksResult>(session, `/api/v1/assessments/${assessmentId}/marks`, {
        method: 'PUT',
        body: {
          reason: locked ? reason.trim() : undefined,
          entries: changes.map((m) => {
            const d = drafts[m.studentId]!;
            return {
              studentId: m.studentId,
              kind: d.kind,
              marks: d.kind === 'SCORED' ? Number(d.marks) : null,
            };
          }),
        },
      });
      setInfo(`Saved: ${result.created} new, ${result.changed} changed.`);
      setDrafts({});
      setReason('');
      reload();
      onChanged();
    } catch (e) {
      setInfo(message(e));
    } finally {
      setBusy(false);
    }
  }

  async function lock() {
    setBusy(true);
    setInfo(null);
    try {
      await apiRequest<AssessmentItem>(session, `/api/v1/assessments/${assessmentId}/lock`, {
        method: 'POST',
      });
      reload();
      onChanged();
    } catch (e) {
      setInfo(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      {locked && (
        <p className="notice" role="status">
          Locked. Marks can still be corrected, but you must give a reason and every change is kept.
        </p>
      )}
      <ul className="plain-list">
        {marks.map((m) => {
          const d = current(m.studentId);
          return (
            <li key={m.studentId}>
              <fieldset disabled={!editable}>
                <legend>
                  {m.name} <small className="muted">{m.schoolRef}</small>
                </legend>
                <label>
                  <span className="visually-hidden">{`Kind for ${m.name}`}</span>
                  <select
                    value={d?.kind ?? ''}
                    aria-label={`Kind for ${m.name}`}
                    onChange={(e) =>
                      setDrafts((x) => ({
                        ...x,
                        [m.studentId]: {
                          kind: e.target.value as MarkKind,
                          marks: x[m.studentId]?.marks ?? '',
                        },
                      }))
                    }
                  >
                    <option value="" disabled>
                      Not entered
                    </option>
                    {(Object.keys(KIND_LABEL) as MarkKind[]).map((k) => (
                      <option key={k} value={k}>
                        {KIND_LABEL[k]}
                      </option>
                    ))}
                  </select>
                </label>{' '}
                {d?.kind === 'SCORED' && (
                  <input
                    inputMode="decimal"
                    aria-label={`Marks for ${m.name}`}
                    value={d.marks}
                    onChange={(e) =>
                      setDrafts((x) => ({ ...x, [m.studentId]: { kind: 'SCORED', marks: e.target.value } }))
                    }
                  />
                )}{' '}
                {d?.kind === 'SCORED' && <span className="muted">of {assessment.maxMarks}</span>}
              </fieldset>
            </li>
          );
        })}
      </ul>
      {needsReason && (
        <Field
          label="Reason for the change"
          hint="At least 5 characters. It is kept with the old mark."
          required
        >
          {(p) => <input {...p} value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />}
        </Field>
      )}
      {info && (
        <p className="notice" role="status">
          {info}
        </p>
      )}
      {editable && (
        <p className="form__actions">
          <Button
            variant="primary"
            busy={busy}
            disabled={changes.length === 0 || !complete || !reasonOk}
            onClick={() => void save()}
          >
            Save marks
          </Button>{' '}
          {!locked && (
            <Button busy={busy} onClick={() => void lock()}>
              Lock marks
            </Button>
          )}
        </p>
      )}
    </div>
  );
}

function ReportCards({ me, sectionId, termId }: { me: Me; sectionId: string; termId: string }) {
  const session = useApiSession();
  const [preview, reload] = useApiResource<ReportPreview>(
    `/api/v1/sections/${sectionId}/report-preview?termId=${termId}`,
  );
  const [reason, setReason] = useState('');
  const [info, setInfo] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (preview.status === 'loading') return <LoadingState label="Checking report cards" />;
  if (preview.status === 'error') return <ErrorState error={preview.error} onRetry={reload} />;
  const p = preview.data;
  const toPublish = p.students.filter((s) => s.changed);
  const revising = toPublish.some((s) => s.publishedRevision !== null);

  async function publish() {
    setBusy(true);
    setInfo(null);
    try {
      const r = await apiRequest<PublishResult>(
        session,
        `/api/v1/sections/${sectionId}/report-cards/publish?termId=${termId}`,
        { method: 'POST', body: { reason: revising ? reason.trim() : undefined } },
      );
      setInfo(`Published ${r.published} report card${r.published === 1 ? '' : 's'}.`);
      setReason('');
      reload();
    } catch (e) {
      setInfo(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="rc-h">
      <h2 id="rc-h">Report cards</h2>
      {p.blockers.length > 0 && (
        <div className="notice" role="status">
          <p>Not ready to publish:</p>
          <ul>
            {p.blockers.map((b) => (
              <li key={b}>{b}</li>
            ))}
          </ul>
        </div>
      )}
      {p.ready && (
        <table>
          <caption className="visually-hidden">Report card preview</caption>
          <thead>
            <tr>
              <th scope="col">Student</th>
              <th scope="col">Subjects</th>
              <th scope="col">Overall</th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            {p.students.map((s) => (
              <tr key={s.studentId}>
                <th scope="row">{s.name}</th>
                <td>{s.subjects.map((x) => `${x.name} ${x.percent.toFixed(1)}% ${x.grade}`).join(', ')}</td>
                <td>
                  {s.overallPercent.toFixed(1)}% {s.overallGrade}
                </td>
                <td>
                  {s.publishedRevision === null ? (
                    <StatusBadge tone="info">Not published</StatusBadge>
                  ) : s.changed ? (
                    <StatusBadge tone="warning">{`Changed since revision ${s.publishedRevision}`}</StatusBadge>
                  ) : (
                    <StatusBadge tone="success">{`Published, revision ${s.publishedRevision}`}</StatusBadge>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {revising && canPublish(me) && (
        <Field
          label="Reason for the correction"
          hint="At least 5 characters. Parents see it on the new revision."
          required
        >
          {(f) => <input {...f} value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />}
        </Field>
      )}
      {info && (
        <p className="notice" role="status">
          {info}
        </p>
      )}
      {p.ready && canPublish(me) && (
        <p className="form__actions">
          <Button
            variant="primary"
            busy={busy}
            disabled={toPublish.length === 0 || (revising && reason.trim().length < 5)}
            onClick={() => void publish()}
          >
            {revising ? 'Publish corrected report cards' : 'Publish report cards'}
          </Button>
        </p>
      )}
      {p.ready && !canPublish(me) && (
        <p className="muted">Only the principal or office staff publish report cards.</p>
      )}
    </section>
  );
}
