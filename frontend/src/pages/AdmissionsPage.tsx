import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  AdmissionItem,
  AdmissionListing,
  AdmissionStatus,
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

/** The server enforces ADMISSION_MANAGE regardless; this only decides whether to show the buttons. */
export const canManageAdmissions = (me: Me) =>
  me.roles.some((r) => ['group_admin', 'campus_admin'].includes(r.roleKey));

const LABEL: Record<AdmissionStatus, string> = {
  ENQUIRY: 'Enquiry',
  APPLIED: 'Applied',
  OFFERED: 'Offered',
  ACCEPTED: 'Accepted',
  ENROLLED: 'Enrolled',
  DECLINED: 'Declined',
  WITHDRAWN: 'Withdrawn',
  REJECTED: 'Rejected',
};
const NEXT: Partial<Record<AdmissionStatus, { to: string; label: string }>> = {
  ENQUIRY: { to: 'APPLIED', label: 'Mark as applied' },
  APPLIED: { to: 'OFFERED', label: 'Make an offer' },
  OFFERED: { to: 'ACCEPTED', label: 'Offer accepted' },
};
/** What ends an application at each stage; every one of these needs a reason. */
const ENDS: Partial<Record<AdmissionStatus, { to: string; label: string }[]>> = {
  ENQUIRY: [
    { to: 'REJECTED', label: 'Reject' },
    { to: 'WITHDRAWN', label: 'Withdrawn by family' },
  ],
  APPLIED: [
    { to: 'REJECTED', label: 'Reject' },
    { to: 'WITHDRAWN', label: 'Withdrawn by family' },
  ],
  OFFERED: [
    { to: 'DECLINED', label: 'Offer declined' },
    { to: 'WITHDRAWN', label: 'Withdrawn by family' },
  ],
  ACCEPTED: [{ to: 'WITHDRAWN', label: 'Withdrawn by family' }],
};
const tone = (s: AdmissionStatus) =>
  s === 'ENROLLED'
    ? 'success'
    : s === 'ACCEPTED' || s === 'OFFERED'
      ? 'info'
      : s === 'ENQUIRY' || s === 'APPLIED'
        ? 'neutral'
        : 'warning';
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function AdmissionsPage({
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
        title="Admissions"
        lead="Follow each family from first enquiry to a place in a class. Every step is recorded."
      />
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
        <EmptyState title="No academic year yet">
          <p>Create the academic year before taking admissions.</p>
        </EmptyState>
      )}
      {structure.status === 'ready' && structure.data.year && (
        <Funnel
          key={structure.data.year.id}
          me={me}
          structure={structure.data}
          yearId={structure.data.year.id}
        />
      )}
    </>
  );
}

function Funnel({ me, structure, yearId }: { me: Me; structure: AcademicStructure; yearId: string }) {
  const [filter, setFilter] = useState('');
  const [listing, reload] = useApiResource<AdmissionListing>(
    `/api/v1/academic-years/${yearId}/admissions${filter ? `?status=${filter}` : ''}`,
  );
  const grades = structure.programmes.flatMap((p) =>
    p.grades.map((g) => ({ id: g.id, label: `${g.name} (${p.code})`, sections: g.sections })),
  );
  if (listing.status === 'loading') return <LoadingState label="Loading admissions" />;
  if (listing.status === 'error') return <ErrorState error={listing.error} onRetry={reload} />;
  const { items, seats } = listing.data;
  return (
    <>
      {seats.length > 0 && (
        <section aria-labelledby="seats-h">
          <h2 id="seats-h">Seats</h2>
          <table>
            <caption className="visually-hidden">Seats by grade</caption>
            <thead>
              <tr>
                <th scope="col">Grade</th>
                <th scope="col">Places</th>
                <th scope="col">Enrolled</th>
                <th scope="col">Offered</th>
                <th scope="col">Left</th>
              </tr>
            </thead>
            <tbody>
              {seats.map((s) => (
                <tr key={s.gradeLevelId}>
                  <th scope="row">
                    {s.gradeName} <small className="muted">{s.programmeCode}</small>
                  </th>
                  <td>{s.capacity ?? 'No limit'}</td>
                  <td>{s.enrolled}</td>
                  <td>{s.held}</td>
                  <td>{s.capacity === null ? '—' : Math.max(0, s.capacity - s.enrolled - s.held)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
      {canManageAdmissions(me) && <NewEnquiry yearId={yearId} grades={grades} onCreated={reload} />}
      <section aria-labelledby="apps-h">
        <h2 id="apps-h">Applications</h2>
        <div className="form form--inline">
          <Field label="Show">
            {(p) => (
              <select {...p} value={filter} onChange={(e) => setFilter(e.target.value)}>
                <option value="">Everything</option>
                {(Object.keys(LABEL) as AdmissionStatus[]).map((k) => (
                  <option key={k} value={k}>
                    {LABEL[k]}
                  </option>
                ))}
              </select>
            )}
          </Field>
        </div>
        {items.length === 0 ? (
          <EmptyState title="No applications here">
            <p>Add an enquiry to get started.</p>
          </EmptyState>
        ) : (
          <ul className="plain-list">
            {items.map((a) => (
              <Row key={a.id} me={me} item={a} grades={grades} onChanged={reload} />
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

function NewEnquiry({
  yearId,
  grades,
  onCreated,
}: {
  yearId: string;
  grades: { id: string; label: string }[];
  onCreated: () => void;
}) {
  const session = useApiSession();
  const [f, setF] = useState({
    gradeLevelId: '',
    child: '',
    family: '',
    gname: '',
    mobile: '',
    source: 'WALK_IN',
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) =>
    setF((x) => ({ ...x, [k]: e.target.value }));
  const valid = f.gradeLevelId && f.child.trim() && f.gname.trim() && f.mobile.trim();
  async function create() {
    setBusy(true);
    setError(null);
    try {
      await apiRequest<AdmissionItem>(session, `/api/v1/academic-years/${yearId}/admissions`, {
        method: 'POST',
        body: {
          gradeLevelId: f.gradeLevelId,
          childGivenName: f.child.trim(),
          childFamilyName: f.family.trim() || undefined,
          guardianGivenName: f.gname.trim(),
          guardianMobile: f.mobile.trim(),
          source: f.source,
        },
      });
      setF({ gradeLevelId: f.gradeLevelId, child: '', family: '', gname: '', mobile: '', source: f.source });
      onCreated();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section aria-labelledby="new-h">
      <h2 id="new-h">New enquiry</h2>
      <div className="form form--inline">
        <Field label="Grade applied for">
          {(p) => (
            <select {...p} value={f.gradeLevelId} onChange={set('gradeLevelId')}>
              <option value="">Choose a grade</option>
              {grades.map((g) => (
                <option key={g.id} value={g.id}>
                  {g.label}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Child's first name">
          {(p) => <input {...p} value={f.child} onChange={set('child')} />}
        </Field>
        <Field label="Child's family name">
          {(p) => <input {...p} value={f.family} onChange={set('family')} />}
        </Field>
        <Field label="Parent or guardian's name">
          {(p) => <input {...p} value={f.gname} onChange={set('gname')} />}
        </Field>
        <Field label="Their mobile number">
          {(p) => <input {...p} inputMode="tel" value={f.mobile} onChange={set('mobile')} />}
        </Field>
        <Field label="How they came to us">
          {(p) => (
            <select {...p} value={f.source} onChange={set('source')}>
              <option value="WALK_IN">Walked in</option>
              <option value="PHONE">Phone call</option>
              <option value="WEBSITE">Website</option>
              <option value="REFERRAL">Referral</option>
              <option value="OTHER">Other</option>
            </select>
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
          Add enquiry
        </Button>
      </p>
    </section>
  );
}

function Row({
  me,
  item,
  grades,
  onChanged,
}: {
  me: Me;
  item: AdmissionItem;
  grades: {
    id: string;
    sections: { id: string; code: string; capacity: number | null; students: number }[];
  }[];
  onChanged: () => void;
}) {
  const session = useApiSession();
  const [reason, setReason] = useState('');
  const [ending, setEnding] = useState<string | null>(null);
  const [override, setOverride] = useState(false);
  const [sectionId, setSectionId] = useState('');
  const [info, setInfo] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const manage = canManageAdmissions(me);
  const sections = grades.find((g) => g.id === item.gradeLevelId)?.sections ?? [];
  const next = NEXT[item.status];

  async function run(fn: () => Promise<string | null>) {
    setBusy(true);
    setInfo(null);
    try {
      const done = await fn();
      if (done) setInfo(done);
      setReason('');
      setEnding(null);
      setOverride(false);
      onChanged();
    } catch (e) {
      const text = message(e);
      setInfo(text);
      if (e instanceof ApiError && e.status === 409 && /No seats/.test(text)) setOverride(true);
    } finally {
      setBusy(false);
    }
  }
  const move = (to: string) =>
    run(async () => {
      await apiRequest<AdmissionItem>(session, `/api/v1/admissions/${item.id}/transition`, {
        method: 'POST',
        body: { to, reason: reason.trim() || undefined, overrideSeats: override },
      });
      return null;
    });
  const enrol = () =>
    run(async () => {
      const r = await apiRequest<{ schoolRef: string }>(session, `/api/v1/admissions/${item.id}/enrol`, {
        method: 'POST',
        body: { sectionId },
      });
      return `Enrolled as ${r.schoolRef}.`;
    });

  return (
    <li>
      <p>
        <strong>{item.childName}</strong>, {item.gradeName}{' '}
        <span className="muted">{item.programmeCode}</span>{' '}
        <StatusBadge tone={tone(item.status)}>{LABEL[item.status]}</StatusBadge>
      </p>
      <p className="muted">
        {item.guardianName}, {item.guardianMobile}
      </p>
      {manage && (next || ENDS[item.status] || item.status === 'ACCEPTED') && (
        <p className="form__actions">
          {next && (
            <Button variant="primary" busy={busy} onClick={() => void move(next.to)}>
              {next.label}
            </Button>
          )}{' '}
          {item.status === 'ACCEPTED' && (
            <>
              <select
                aria-label={`Class for ${item.childName}`}
                value={sectionId}
                onChange={(e) => setSectionId(e.target.value)}
              >
                <option value="">Choose a class</option>
                {sections.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code} ({s.students}
                    {s.capacity ? ` of ${s.capacity}` : ''})
                  </option>
                ))}
              </select>{' '}
              <Button variant="primary" busy={busy} disabled={!sectionId} onClick={() => void enrol()}>
                Enrol
              </Button>{' '}
            </>
          )}
          {(ENDS[item.status] ?? []).map((e) => (
            <Button key={e.to} variant="quiet" onClick={() => setEnding(ending === e.to ? null : e.to)}>
              {e.label}
            </Button>
          ))}
        </p>
      )}
      {(ending !== null || override) && (
        <>
          <Field
            label={ending ? 'Reason' : 'Reason for offering with no seats left'}
            hint="At least 5 characters. It is kept in the history."
            required
          >
            {(p) => (
              <input {...p} value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
            )}
          </Field>
          <p className="form__actions">
            <Button
              variant="primary"
              busy={busy}
              disabled={reason.trim().length < 5}
              onClick={() => void move(ending ?? 'OFFERED')}
            >
              {ending ? 'Confirm' : 'Offer anyway'}
            </Button>
          </p>
        </>
      )}
      {info && (
        <p className="notice" role="status">
          {info}
        </p>
      )}
    </li>
  );
}
