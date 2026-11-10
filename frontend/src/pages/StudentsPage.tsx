import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  Campus,
  GuardianCapability,
  GuardianView,
  Me,
  StudentPage,
  StudentProfile,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { DataTable } from '../components/DataTable';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDay } from '../lib/format';
import { asApiError } from './AcademicManage';

export const capabilityText: Record<GuardianCapability, string> = {
  VIEW_ATTENDANCE: 'Attendance',
  VIEW_ACADEMIC: 'Academic',
  PAY_FEES: 'Pays fees',
  GIVE_CONSENT: 'Consent',
  RECEIVE_NOTICES: 'Notices',
  PICKUP: 'Pickup',
};
const allCapabilities = Object.keys(capabilityText) as GuardianCapability[];
const statusTone = {
  ACTIVE: 'success',
  RESTRICTED: 'warning',
  SUSPENDED: 'warning',
  ENDED: 'neutral',
} as const;
const statusText = {
  ACTIVE: 'Active',
  RESTRICTED: 'Restricted',
  SUSPENDED: 'Suspended',
  ENDED: 'Ended',
} as const;
const fieldText: Record<string, string> = {
  givenName: 'first name',
  familyName: 'last name',
  dateOfBirth: 'date of birth',
  placement: 'section',
};

/** Staff who may change student and guardian records; the server enforces STUDENT_WRITE and PERSON_MANAGE. */
export const canManageStudents = (me: Me) =>
  me.roles.some((r) => r.roleKey === 'group_admin' || r.roleKey === 'campus_admin');

export function StudentsPage({
  campuses,
  selectedCampusId,
}: {
  campuses: Campus[];
  selectedCampusId: string | null;
}) {
  const [chosenCampus, setChosenCampus] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const campusId = selectedCampusId ?? chosenCampus ?? campuses[0]?.id ?? null;
  const [structure] = useApiResource<AcademicStructure>(
    campusId === null ? null : `/api/v1/campuses/${campusId}/academic-structure`,
  );
  const yearId = structure.status === 'ready' ? (structure.data.year?.id ?? null) : null;
  const [page, reload] = useApiResource<StudentPage>(
    yearId === null
      ? null
      : `/api/v1/academic-years/${yearId}/students?limit=200${submitted ? `&q=${encodeURIComponent(submitted)}` : ''}`,
  );

  return (
    <>
      <PageHeader title="Students" lead="Find a student to see their placement and guardians." />
      {campuses.length === 0 ? (
        <EmptyState title="No students to show">
          <p>You need a campus role to see students.</p>
        </EmptyState>
      ) : (
        <form
          className="form form--inline"
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            setSubmitted(query.trim());
          }}
        >
          {selectedCampusId === null && campuses.length > 1 && (
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
          )}
          <Field label="Name or admission number">
            {(p) => <input {...p} type="search" value={query} onChange={(e) => setQuery(e.target.value)} />}
          </Field>
          <div className="form__actions form__actions--end">
            <Button type="submit" variant="secondary">
              Search
            </Button>
          </div>
        </form>
      )}
      {page.status === 'loading' && yearId !== null && <LoadingState label="Loading students" />}
      {page.status === 'error' && <ErrorState error={page.error} onRetry={reload} />}
      {page.status === 'ready' && (
        <>
          <DataTable
            caption={`Students${structure.status === 'ready' && structure.data.year ? ` in ${structure.data.year.label}` : ''}`}
            rows={page.data.students}
            rowKey={(s) => s.id}
            empty={<EmptyState title={submitted ? `No student matches "${submitted}"` : 'No students yet'} />}
            columns={[
              {
                key: 'name',
                header: 'Student',
                render: (s) => <Link to={`/students/${s.id}`}>{s.displayName}</Link>,
                sortValue: (s) => s.displayName,
              },
              {
                key: 'ref',
                header: 'Admission no.',
                render: (s) => s.schoolRef,
                sortValue: (s) => s.schoolRef,
              },
              {
                key: 'class',
                header: 'Class',
                render: (s) =>
                  s.gradeName ? `${s.programmeCode} · ${s.gradeName} ${s.sectionCode}` : 'Not placed',
              },
              { key: 'guardians', header: 'Guardians', render: (s) => s.guardians, numeric: true },
              {
                key: 'source',
                header: 'Record from',
                render: (s) => (s.source === 'LEGACY' ? 'Old system' : 'Schoomano'),
              },
            ]}
          />
          {page.data.total > page.data.students.length && (
            <p className="muted">
              Showing {page.data.students.length} of {page.data.total}. Search to narrow the list.
            </p>
          )}
        </>
      )}
    </>
  );
}

export function StudentProfilePage({ me }: { me: Me }) {
  const { studentId } = useParams();
  const [profile, reload] = useApiResource<StudentProfile>(`/api/v1/students/${studentId}`);
  const manage = canManageStudents(me);
  if (profile.status === 'loading') return <LoadingState label="Loading student" />;
  if (profile.status === 'error') return <ErrorState error={profile.error} onRetry={reload} />;
  const s = profile.data;
  const name = `${s.preferredName ?? s.givenName}${s.familyName ? ` ${s.familyName}` : ''}`;
  const current = s.placements[0];

  return (
    <>
      <p>
        <Link to="/students">← All students</Link>
      </p>
      <PageHeader
        title={name}
        lead={`${s.schoolRef}${current?.gradeName ? ` · ${current.programmeCode} ${current.gradeName} ${current.sectionCode}` : ''}`}
      />
      {s.ownedFields.length > 0 && (
        <p className="notice">
          This record comes from the old school system. Its{' '}
          {s.ownedFields.map((f) => fieldText[f] ?? f).join(', ')} can only be changed there; the next import
          brings changes across.
        </p>
      )}
      <section className="section panel" aria-labelledby="details-h">
        <h2 id="details-h" className="section__title">
          Details
        </h2>
        <dl className="details">
          <dt>First name</dt>
          <dd>
            {s.givenName}{' '}
            {s.ownedFields.includes('givenName') && <StatusBadge tone="neutral">Old system</StatusBadge>}
          </dd>
          <dt>Last name</dt>
          <dd>
            {s.familyName ?? '—'}{' '}
            {s.ownedFields.includes('familyName') && <StatusBadge tone="neutral">Old system</StatusBadge>}
          </dd>
          <dt>Preferred name</dt>
          <dd>{s.preferredName ?? '—'}</dd>
          {s.dateOfBirth && (
            <>
              <dt>Date of birth</dt>
              <dd>{formatDay(s.dateOfBirth)}</dd>
            </>
          )}
          {s.households.length > 0 && (
            <>
              <dt>Household</dt>
              <dd>{s.households.join(', ')}</dd>
            </>
          )}
        </dl>
        {manage && s.view === 'STAFF' && <PreferredNameForm student={s} onSaved={reload} />}
      </section>

      {s.placements.length > 0 && (
        <section className="section" aria-labelledby="placements-h">
          <h2 id="placements-h" className="section__title">
            Placement
          </h2>
          <DataTable
            caption="Placement by year"
            rows={s.placements}
            empty={<EmptyState title="Not enrolled in any year" />}
            rowKey={(p) => p.academicYearId}
            columns={[
              { key: 'year', header: 'Year', render: (p) => p.yearLabel },
              { key: 'campus', header: 'Campus', render: (p) => p.campusName },
              {
                key: 'class',
                header: 'Class',
                render: (p) =>
                  p.gradeName ? `${p.programmeCode} · ${p.gradeName} ${p.sectionCode}` : 'Not placed',
              },
            ]}
          />
        </section>
      )}

      {s.view === 'STAFF' && (
        <section className="section" aria-labelledby="guardians-h">
          <h2 id="guardians-h" className="section__title">
            Guardians
          </h2>
          {s.guardians.length === 0 && <EmptyState title="No guardians recorded" />}
          {s.guardians.map((g) => (
            <GuardianCard key={g.relationshipId} guardian={g} manage={manage} onSaved={reload} />
          ))}
          {manage && <AddGuardianForm studentId={s.id} onSaved={reload} />}
        </section>
      )}
    </>
  );
}

function useSave() {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const save = async (work: () => Promise<unknown>, message: string) => {
    setBusy(true);
    setFailure(null);
    setDone(null);
    try {
      await work();
      setDone(message);
      return true;
    } catch (e) {
      setFailure(asApiError(e));
      return false;
    } finally {
      setBusy(false);
    }
  };
  const feedback = (
    <>
      {done && (
        <p className="notice notice--success" role="status">
          {done}
        </p>
      )}
      {failure && <ErrorState error={failure} />}
    </>
  );
  return { busy, save, feedback };
}

function PreferredNameForm({ student, onSaved }: { student: StudentProfile; onSaved: () => void }) {
  const session = useApiSession();
  const [value, setValue] = useState(student.preferredName ?? '');
  const { busy, save, feedback } = useSave();
  return (
    <form
      className="form"
      onSubmit={async (e: FormEvent) => {
        e.preventDefault();
        if (
          await save(
            () =>
              apiRequest(session, `/api/v1/students/${student.id}`, {
                method: 'PATCH',
                body: { preferredName: value.trim() },
              }),
            'Preferred name saved.',
          )
        )
          onSaved();
      }}
    >
      {feedback}
      <Field
        label="Preferred name"
        hint="What the student likes to be called. Leave empty to use the first name."
      >
        {(p) => <input {...p} value={value} maxLength={80} onChange={(e) => setValue(e.target.value)} />}
      </Field>
      <div className="form__actions">
        <Button type="submit" variant="secondary" disabled={busy}>
          Save preferred name
        </Button>
      </div>
    </form>
  );
}

function CapabilityChecks({
  value,
  onChange,
  legend,
}: {
  value: GuardianCapability[];
  onChange: (v: GuardianCapability[]) => void;
  legend: string;
}) {
  return (
    <fieldset className="fieldset">
      <legend className="field__label">{legend}</legend>
      {allCapabilities.map((c) => (
        <label key={c} className="check">
          <input
            type="checkbox"
            checked={value.includes(c)}
            onChange={(e) => onChange(e.target.checked ? [...value, c] : value.filter((x) => x !== c))}
          />{' '}
          {capabilityText[c]}
        </label>
      ))}
    </fieldset>
  );
}

function GuardianCard({
  guardian: g,
  manage,
  onSaved,
}: {
  guardian: GuardianView;
  manage: boolean;
  onSaved: () => void;
}) {
  const session = useApiSession();
  const [editing, setEditing] = useState(false);
  const [status, setStatus] = useState(g.status);
  const [caps, setCaps] = useState<GuardianCapability[]>(g.capabilities);
  const [pickupConfirmed, setPickupConfirmed] = useState(false);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | undefined>();
  const { busy, save, feedback } = useSave();
  const headingId = `g-${g.relationshipId}`;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (reason.trim().length < 10) {
      setReasonError('Give a reason of at least 10 characters. It is kept in the audit trail.');
      return;
    }
    setReasonError(undefined);
    const ok = await save(
      () =>
        apiRequest(session, `/api/v1/guardian-relationships/${g.relationshipId}`, {
          method: 'PATCH',
          body: { status, capabilities: caps, pickupConfirmed, reason: reason.trim() },
        }),
      'Relationship updated.',
    );
    if (ok) {
      setEditing(false);
      setReason('');
      onSaved();
    }
  };

  return (
    <article className="panel guardian" aria-labelledby={headingId}>
      <h3 id={headingId} className="section__subtitle">
        {g.displayName} <StatusBadge tone={statusTone[g.status]}>{statusText[g.status]}</StatusBadge>
      </h3>
      <p className="muted">
        {g.relationshipType.charAt(0) + g.relationshipType.slice(1).toLowerCase()}
        {g.mobile ? ` · ${g.mobile}` : ''}
        {g.email ? ` · ${g.email}` : ''}
        {g.sharesHousehold ? ' · same household' : ''}
      </p>
      <p>
        {g.capabilities.length === 0
          ? 'No permissions'
          : g.capabilities.map((c) => (
              <span key={c} className="count-chip">
                <StatusBadge tone="info">{capabilityText[c]}</StatusBadge>
              </span>
            ))}
      </p>
      {feedback}
      {manage && g.status !== 'ENDED' && !editing && (
        <Button
          variant="quiet"
          aria-label={`Change relationship with ${g.displayName}`}
          onClick={() => setEditing(true)}
        >
          Change
        </Button>
      )}
      {editing && (
        <form className="form" onSubmit={submit} noValidate>
          <Field label="Status">
            {(p) => (
              <select
                {...p}
                value={status}
                onChange={(e) => {
                  const next = e.target.value as GuardianView['status'];
                  setStatus(next);
                  if (next === 'RESTRICTED') setCaps(['RECEIVE_NOTICES']);
                }}
              >
                <option value="ACTIVE">Active</option>
                <option value="RESTRICTED">Restricted (e.g. separated parent)</option>
                <option value="SUSPENDED">Suspended</option>
                <option value="ENDED">Ended (cannot be reopened)</option>
              </select>
            )}
          </Field>
          <CapabilityChecks value={caps} onChange={setCaps} legend="Allowed for this child" />
          {status === 'RESTRICTED' && caps.includes('PICKUP') && (
            <label className="check">
              <input
                type="checkbox"
                checked={pickupConfirmed}
                onChange={(e) => setPickupConfirmed(e.target.checked)}
              />{' '}
              I confirm a court order or the school's decision allows this guardian to collect the child
            </label>
          )}
          <Field
            label="Reason"
            hint="Kept in the audit trail. Do not write court-order details here."
            required
            error={reasonError}
          >
            {(p) => (
              <input {...p} value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
            )}
          </Field>
          <div className="form__actions">
            <Button type="submit" variant="primary" disabled={busy}>
              Save change
            </Button>
            <Button variant="secondary" onClick={() => setEditing(false)}>
              Cancel
            </Button>
          </div>
        </form>
      )}
    </article>
  );
}

function AddGuardianForm({ studentId, onSaved }: { studentId: string; onSaved: () => void }) {
  const session = useApiSession();
  const [f, setF] = useState({
    givenName: '',
    familyName: '',
    mobile: '',
    relationshipType: 'MOTHER',
    reason: '',
  });
  const [caps, setCaps] = useState<GuardianCapability[]>([...allCapabilities]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const { busy, save, feedback } = useSave();
  const set = (k: keyof typeof f) => (v: string) => setF((o) => ({ ...o, [k]: v }));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.givenName.trim()) err.givenName = "Enter the guardian's first name.";
    if (!/^(\+?91[\s-]?|0)?[6-9]\d{4}[\s-]?\d{5}$/.test(f.mobile.trim()))
      err.mobile = 'Enter a 10-digit Indian mobile number, for example 98450 12345.';
    if (f.reason.trim().length < 10)
      err.reason = 'Give a reason of at least 10 characters, for example "Mother at admission".';
    setErrors(err);
    if (Object.keys(err).length > 0) return;
    const ok = await save(
      () =>
        apiRequest(session, `/api/v1/students/${studentId}/guardians`, {
          method: 'POST',
          body: { ...f, status: 'ACTIVE', capabilities: caps, pickupConfirmed: false },
        }),
      'Guardian added.',
    );
    if (ok) {
      setF({ givenName: '', familyName: '', mobile: '', relationshipType: 'MOTHER', reason: '' });
      onSaved();
    }
  };

  return (
    <details className="section panel">
      <summary>Add a guardian</summary>
      {feedback}
      <form className="form" onSubmit={submit} noValidate>
        <Field label="First name" required error={errors.givenName}>
          {(p) => <input {...p} value={f.givenName} onChange={(e) => set('givenName')(e.target.value)} />}
        </Field>
        <Field label="Last name">
          {(p) => <input {...p} value={f.familyName} onChange={(e) => set('familyName')(e.target.value)} />}
        </Field>
        <Field
          label="Mobile"
          hint="Used for school messages and, later, for signing in. A number already on record links that guardian."
          required
          error={errors.mobile}
        >
          {(p) => (
            <input
              {...p}
              type="tel"
              inputMode="tel"
              value={f.mobile}
              onChange={(e) => set('mobile')(e.target.value)}
            />
          )}
        </Field>
        <Field label="Relationship">
          {(p) => (
            <select
              {...p}
              value={f.relationshipType}
              onChange={(e) => set('relationshipType')(e.target.value)}
            >
              <option value="MOTHER">Mother</option>
              <option value="FATHER">Father</option>
              <option value="GUARDIAN">Guardian</option>
              <option value="GRANDPARENT">Grandparent</option>
              <option value="OTHER">Other</option>
            </select>
          )}
        </Field>
        <CapabilityChecks value={caps} onChange={setCaps} legend="Allowed for this child" />
        <Field label="Reason" hint="Kept in the audit trail." required error={errors.reason}>
          {(p) => <input {...p} value={f.reason} onChange={(e) => set('reason')(e.target.value)} />}
        </Field>
        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={busy}>
            Add guardian
          </Button>
        </div>
      </form>
    </details>
  );
}
