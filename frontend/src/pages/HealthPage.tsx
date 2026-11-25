import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  Campus,
  HealthFlag,
  HealthRecord,
  HealthVisit,
  Me,
  StudentListItem,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';

/** Only the nurse keeps health records; the server decides, this just chooses the screen. */
export const isNurse = (me: Me) => me.roles.some((r) => r.roleKey === 'nurse');
/** Teachers, the principal and campus administrators see the emergency flags only. */
export const canSeeFlags = (me: Me) =>
  me.roles.some((r) => ['teacher', 'principal', 'campus_admin'].includes(r.roleKey));

const OUTCOMES: Record<HealthVisit['outcome'], string> = {
  RETURNED_TO_CLASS: 'Returned to class',
  SENT_HOME: 'Sent home',
  REFERRED: 'Referred to a doctor or hospital',
  OTHER: 'Other',
};
const BLOOD = ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'];
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function HealthPage({
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
  const allowed = isNurse(me) || canSeeFlags(me);
  const [structure] = useApiResource<AcademicStructure>(
    allowed && campusId !== null ? `/api/v1/campuses/${campusId}/academic-structure` : null,
  );
  return (
    <>
      <PageHeader
        title="Health"
        lead="The school nurse keeps each child's health record and infirmary visits. Everyone else sees only a short emergency flag."
      />
      {!allowed ? (
        <EmptyState title="Not available">
          <p>Health information is limited to the school nurse and, for emergency flags, teaching staff.</p>
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
          {structure.status === 'ready' &&
            structure.data.year &&
            (isNurse(me) ? (
              <Infirmary key={structure.data.year.id} yearId={structure.data.year.id} />
            ) : (
              <Flags key={structure.data.year.id} yearId={structure.data.year.id} />
            ))}
        </>
      )}
    </>
  );
}

function Flags({ yearId }: { yearId: string }) {
  const [flags, reload] = useApiResource<HealthFlag[]>(`/api/v1/academic-years/${yearId}/health-flags`);
  return (
    <section aria-labelledby="flags-h">
      <h2 id="flags-h">Emergency flags</h2>
      {flags.status === 'loading' && <LoadingState label="Loading flags" />}
      {flags.status === 'error' && <ErrorState error={flags.error} onRetry={reload} />}
      {flags.status === 'ready' &&
        (flags.data.length === 0 ? (
          <EmptyState title="No flagged children" />
        ) : (
          <ul className="plain-list">
            {flags.data.map((f) => (
              <li key={f.studentId}>
                <strong>{f.studentName}</strong>: {f.flag}
              </li>
            ))}
          </ul>
        ))}
    </section>
  );
}

function Infirmary({ yearId }: { yearId: string }) {
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [student, setStudent] = useState<StudentListItem | null>(null);
  const [found] = useApiResource<{ students: StudentListItem[] }>(
    submitted ? `/api/v1/academic-years/${yearId}/students?limit=8&q=${encodeURIComponent(submitted)}` : null,
  );
  return (
    <section aria-labelledby="find-h">
      <h2 id="find-h">Find a child</h2>
      {!student ? (
        <>
          <div className="form form--inline">
            <Field label="Name or school reference">
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
                  <Button variant="quiet" onClick={() => setStudent(s)}>{`Open ${s.displayName}`}</Button>
                </li>
              ))}
            </ul>
          )}
        </>
      ) : (
        <>
          <p>
            <Button variant="quiet" onClick={() => setStudent(null)}>
              Choose someone else
            </Button>
          </p>
          <Child key={student.id} student={student} />
        </>
      )}
    </section>
  );
}

function Child({ student }: { student: StudentListItem }) {
  const [record, reload] = useApiResource<HealthRecord>(`/api/v1/students/${student.id}/health-record`);
  if (record.status === 'loading') return <LoadingState label="Opening health record" />;
  if (record.status === 'error') return <ErrorState error={record.error} onRetry={reload} />;
  return (
    <>
      <h3>{record.data.studentName}</h3>
      <RecordForm studentId={student.id} record={record.data} onSaved={reload} />
      <VisitForm studentId={student.id} onSaved={reload} />
      <h4>Infirmary visits</h4>
      {record.data.visits.length === 0 ? (
        <p className="muted">No visits recorded.</p>
      ) : (
        <ol className="plain-list" aria-label="Infirmary visits">
          {record.data.visits.map((v) => (
            <li key={v.id}>
              <p>
                <strong>{v.complaint}</strong> ({OUTCOMES[v.outcome]})
                {v.actionTaken ? `: ${v.actionTaken}` : ''}
              </p>
              <p className="muted">
                {v.recordedBy}, {v.visitedAt.slice(0, 10)}
                {v.guardianInformed ? ', guardian informed' : ''}
              </p>
            </li>
          ))}
        </ol>
      )}
    </>
  );
}

function RecordForm({
  studentId,
  record,
  onSaved,
}: {
  studentId: string;
  record: HealthRecord;
  onSaved: () => void;
}) {
  const session = useApiSession();
  const [f, setF] = useState({
    bloodGroup: record.bloodGroup ?? '',
    allergies: record.allergies ?? '',
    conditions: record.conditions ?? '',
    medicines: record.medicines ?? '',
    emergencyFlag: record.emergencyFlag ?? '',
  });
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (v: string) => {
    setSaved(false);
    setF({ ...f, [k]: v });
  };

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await apiRequest(session, `/api/v1/students/${studentId}/health-record`, {
        method: 'PUT',
        body: {
          bloodGroup: f.bloodGroup || undefined,
          allergies: f.allergies.trim() || undefined,
          conditions: f.conditions.trim() || undefined,
          medicines: f.medicines.trim() || undefined,
          emergencyFlag: f.emergencyFlag.trim() || undefined,
        },
      });
      setSaved(true);
      onSaved();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <h4>Health record</h4>
      {record.updatedAt && (
        <p className="muted">
          Last updated by {record.updatedBy}, {record.updatedAt.slice(0, 10)}
        </p>
      )}
      <div className="form form--inline">
        <Field label="Blood group">
          {(p) => (
            <select {...p} value={f.bloodGroup} onChange={(e) => set('bloodGroup')(e.target.value)}>
              <option value="">Not known</option>
              {BLOOD.map((b) => (
                <option key={b} value={b}>
                  {b}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Emergency flag" hint="One short line teachers can see, such as 'Severe nut allergy'">
          {(p) => (
            <input
              {...p}
              value={f.emergencyFlag}
              maxLength={80}
              onChange={(e) => set('emergencyFlag')(e.target.value)}
            />
          )}
        </Field>
        <Field label="Allergies">
          {(p) => <input {...p} value={f.allergies} onChange={(e) => set('allergies')(e.target.value)} />}
        </Field>
        <Field label="Conditions">
          {(p) => <input {...p} value={f.conditions} onChange={(e) => set('conditions')(e.target.value)} />}
        </Field>
        <Field label="Medicines">
          {(p) => <input {...p} value={f.medicines} onChange={(e) => set('medicines')(e.target.value)} />}
        </Field>
      </div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      {saved && <p role="status">Saved.</p>}
      <p className="form__actions">
        <Button variant="primary" busy={busy} onClick={() => void save()}>
          Save record
        </Button>
      </p>
    </div>
  );
}

function VisitForm({ studentId, onSaved }: { studentId: string; onSaved: () => void }) {
  const session = useApiSession();
  const [complaint, setComplaint] = useState('');
  const [action, setAction] = useState('');
  const [outcome, setOutcome] = useState<HealthVisit['outcome']>('RETURNED_TO_CLASS');
  const [informed, setInformed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function add() {
    setBusy(true);
    setError(null);
    try {
      await apiRequest(session, `/api/v1/students/${studentId}/health-visits`, {
        method: 'POST',
        body: {
          complaint: complaint.trim(),
          actionTaken: action.trim() || undefined,
          outcome,
          guardianInformed: informed,
        },
      });
      setComplaint('');
      setAction('');
      setInformed(false);
      onSaved();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <h4>Record a visit</h4>
      <div className="form form--inline">
        <Field label="Complaint">
          {(p) => (
            <input {...p} value={complaint} maxLength={1000} onChange={(e) => setComplaint(e.target.value)} />
          )}
        </Field>
        <Field label="Action taken" hint="Optional">
          {(p) => (
            <input {...p} value={action} maxLength={1000} onChange={(e) => setAction(e.target.value)} />
          )}
        </Field>
        <Field label="Outcome">
          {(p) => (
            <select
              {...p}
              value={outcome}
              onChange={(e) => setOutcome(e.target.value as HealthVisit['outcome'])}
            >
              {Object.entries(OUTCOMES).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Guardian informed">
          {(p) => (
            <input
              {...p}
              type="checkbox"
              checked={informed}
              onChange={(e) => setInformed(e.target.checked)}
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
        <Button
          variant="primary"
          busy={busy}
          disabled={complaint.trim().length < 2}
          onClick={() => void add()}
        >
          Record visit
        </Button>
      </p>
    </div>
  );
}
