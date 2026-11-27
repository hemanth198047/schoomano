import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  Campus,
  LeaveBalance,
  LeaveRequest,
  LeaveStatus,
  Me,
  PersonMatch,
  StaffMember,
  WorkflowChain,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDay } from '../lib/format';

const has = (me: Me, ...keys: string[]) => me.roles.some((r) => keys.includes(r.roleKey));
export const canRequestLeave = (me: Me) =>
  has(
    me,
    'teacher',
    'campus_admin',
    'principal',
    'finance_officer',
    'nurse',
    'safeguarding_lead',
    'transport_attendant',
    'front_desk',
  );
export const isHr = (me: Me) => has(me, 'campus_admin', 'group_admin');
export const canDecideLeave = (me: Me) => has(me, 'campus_admin', 'principal', 'group_admin');
export const canSeeStaffPage = (me: Me) => canRequestLeave(me) || isHr(me);

const TYPES: Record<string, string> = { CASUAL: 'Casual', SICK: 'Sick', EARNED: 'Earned', UNPAID: 'Unpaid' };
const EMPLOYMENT: Record<string, string> = {
  FULL_TIME: 'Full time',
  PART_TIME: 'Part time',
  CONTRACT: 'Contract',
};
const STATUS: Record<LeaveStatus, string> = {
  PENDING: 'Waiting for approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
};
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function StaffLeavePage({
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
  const allowed = canSeeStaffPage(me);
  const [structure] = useApiResource<AcademicStructure>(
    allowed && campusId !== null ? `/api/v1/campuses/${campusId}/academic-structure` : null,
  );
  return (
    <>
      <PageHeader
        title="Staff and leave"
        lead="Staff profiles, leave allowances and leave requests. Someone else decides your leave, and approval stops at your balance."
      />
      {!allowed ? (
        <EmptyState title="Not available">
          <p>This page is for school staff.</p>
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
          {structure.status === 'ready' && (!structure.data.year || campusId === null) && (
            <EmptyState title="No academic year yet" />
          )}
          {structure.status === 'ready' && structure.data.year && campusId !== null && (
            <Body key={structure.data.year.id} me={me} campusId={campusId} yearId={structure.data.year.id} />
          )}
        </>
      )}
    </>
  );
}

function Body({ me, campusId, yearId }: { me: Me; campusId: string; yearId: string }) {
  const [requests, reloadRequests] = useApiResource<LeaveRequest[]>(
    `/api/v1/academic-years/${yearId}/leave-requests`,
  );
  const [balance, reloadBalance] = useApiResource<LeaveBalance[]>(
    canRequestLeave(me) ? `/api/v1/academic-years/${yearId}/leave-balance` : null,
  );
  const [chains] = useApiResource<WorkflowChain[]>(
    canRequestLeave(me) ? `/api/v1/campuses/${campusId}/workflow-chains` : null,
  );
  const refresh = () => {
    reloadRequests();
    reloadBalance();
  };
  return (
    <>
      {canRequestLeave(me) && (
        <section aria-labelledby="mine-h">
          <h2 id="mine-h">Your leave balance</h2>
          {balance.status === 'loading' && <LoadingState label="Loading balance" />}
          {balance.status === 'error' && <ErrorState error={balance.error} onRetry={reloadBalance} />}
          {balance.status === 'ready' &&
            (balance.data.length === 0 ? (
              <p className="muted">
                HR has not set allowances for this year yet. Unpaid leave can still be requested.
              </p>
            ) : (
              <ul className="plain-list">
                {balance.data.map((b) => (
                  <li key={b.leaveType}>
                    <strong>{TYPES[b.leaveType]}</strong>: {b.remaining} days left of {b.allowance} (
                    {b.approved} taken, {b.pending} waiting)
                  </li>
                ))}
              </ul>
            ))}
          <RequestForm
            yearId={yearId}
            chains={chains.status === 'ready' ? chains.data.filter((c) => c.status === 'PUBLISHED') : []}
            onSaved={refresh}
          />
        </section>
      )}
      <section aria-labelledby="requests-h">
        <h2 id="requests-h">Leave requests</h2>
        {requests.status === 'loading' && <LoadingState label="Loading requests" />}
        {requests.status === 'error' && <ErrorState error={requests.error} onRetry={reloadRequests} />}
        {requests.status === 'ready' &&
          (requests.data.length === 0 ? (
            <EmptyState title="No leave requests" />
          ) : (
            <ul className="plain-list">
              {requests.data.map((r) => (
                <LeaveRow key={r.id} me={me} r={r} onChanged={refresh} />
              ))}
            </ul>
          ))}
      </section>
      {isHr(me) && <Hr campusId={campusId} yearId={yearId} />}
    </>
  );
}

function RequestForm({
  yearId,
  chains,
  onSaved,
}: {
  yearId: string;
  chains: WorkflowChain[];
  onSaved: () => void;
}) {
  const session = useApiSession();
  const [type, setType] = useState('CASUAL');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [reason, setReason] = useState('');
  const [chainId, setChainId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function send() {
    setBusy(true);
    setError(null);
    try {
      await apiRequest(session, `/api/v1/academic-years/${yearId}/leave-requests`, {
        method: 'POST',
        body: {
          leaveType: type,
          fromDate: from,
          toDate: to || from,
          reason: reason.trim() || undefined,
          chainId: chainId || undefined,
        },
      });
      setFrom('');
      setTo('');
      setReason('');
      onSaved();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div>
      <h3>Request leave</h3>
      <div className="form form--inline">
        <Field label="Kind of leave">
          {(p) => (
            <select {...p} value={type} onChange={(e) => setType(e.target.value)}>
              {Object.entries(TYPES).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="First day">
          {(p) => <input {...p} type="date" value={from} onChange={(e) => setFrom(e.target.value)} />}
        </Field>
        <Field label="Last day" hint="Leave blank for one day">
          {(p) => <input {...p} type="date" value={to} onChange={(e) => setTo(e.target.value)} />}
        </Field>
        {chains.length > 0 && (
          <Field label="Approval chain" hint="Optional. Without one, HR or the principal approves directly">
            {(p) => (
              <select {...p} value={chainId} onChange={(e) => setChainId(e.target.value)}>
                <option value="">Approve directly</option>
                {chains.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name} ({c.steps.map((st) => st.label).join(', then ')})
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        <Field label="Reason" hint="Optional">
          {(p) => (
            <input {...p} value={reason} maxLength={1000} onChange={(e) => setReason(e.target.value)} />
          )}
        </Field>
      </div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <p className="form__actions">
        <Button variant="primary" busy={busy} disabled={!from} onClick={() => void send()}>
          Send leave request
        </Button>
      </p>
    </div>
  );
}

function LeaveRow({ me, r, onChanged }: { me: Me; r: LeaveRequest; onChanged: () => void }) {
  const session = useApiSession();
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const mine = r.name === me.displayName;
  async function call(decision: 'APPROVE' | 'REJECT' | 'CANCEL') {
    setBusy(true);
    setError(null);
    try {
      await apiRequest(session, `/api/v1/leave-requests/${r.id}/decision`, {
        method: 'POST',
        body: { decision, note: note.trim() || undefined },
      });
      setNote('');
      onChanged();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  const label = `${r.name}, ${formatDay(r.fromDate)}`;
  return (
    <li>
      <p>
        <strong>{r.name}</strong> {TYPES[r.leaveType] ?? r.leaveType}, {formatDay(r.fromDate)}
        {r.toDate !== r.fromDate ? ` to ${formatDay(r.toDate)}` : ''} ({r.days}{' '}
        {r.days === 1 ? 'day' : 'days'}){' '}
        <StatusBadge
          tone={r.status === 'PENDING' ? 'warning' : r.status === 'APPROVED' ? 'success' : 'neutral'}
        >
          {STATUS[r.status]}
        </StatusBadge>
      </p>
      {r.reason && <p className="muted">{r.reason}</p>}
      {r.decisionNote && (
        <p className="muted">
          {r.decidedBy}: {r.decisionNote}
        </p>
      )}
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      {r.status === 'PENDING' && (
        <div>
          {r.workflowRequestId && (
            <p className="muted">
              In the approval chain{r.waitingFor ? `, waiting for ${r.waitingFor}` : ''}. Decide it on the
              Approvals page.
            </p>
          )}
          {canDecideLeave(me) && !mine && !r.workflowRequestId && (
            <>
              <Field label={`Note for ${label}`} hint="Required to reject">
                {(p) => <input {...p} value={note} onChange={(e) => setNote(e.target.value)} />}
              </Field>
              <p className="form__actions">
                <Button busy={busy} onClick={() => void call('APPROVE')}>{`Approve ${label}`}</Button>{' '}
                <Button
                  variant="quiet"
                  busy={busy}
                  disabled={note.trim().length < 5}
                  onClick={() => void call('REJECT')}
                >{`Reject ${label}`}</Button>
              </p>
            </>
          )}
          {mine && (
            <Button variant="quiet" busy={busy} onClick={() => void call('CANCEL')}>
              {`Withdraw ${label}`}
            </Button>
          )}
        </div>
      )}
    </li>
  );
}

function Hr({ campusId, yearId }: { campusId: string; yearId: string }) {
  const [staff, reload] = useApiResource<StaffMember[]>(`/api/v1/campuses/${campusId}/staff`);
  return (
    <>
      <section aria-labelledby="staff-h">
        <h2 id="staff-h">Staff</h2>
        {staff.status === 'loading' && <LoadingState label="Loading staff" />}
        {staff.status === 'error' && <ErrorState error={staff.error} onRetry={reload} />}
        {staff.status === 'ready' &&
          (staff.data.length === 0 ? (
            <EmptyState title="No staff profiles yet" />
          ) : (
            <ul className="plain-list">
              {staff.data.map((s) => (
                <li key={s.personId}>
                  <strong>{s.name}</strong> {s.employeeNo}, {s.designation}
                  {s.department ? `, ${s.department}` : ''}{' '}
                  <span className="muted">
                    {EMPLOYMENT[s.employmentType]}, joined {formatDay(s.joinedOn)}
                  </span>{' '}
                  {s.status === 'LEFT' && <StatusBadge tone="neutral">Left</StatusBadge>}
                </li>
              ))}
            </ul>
          ))}
        <ProfileForm campusId={campusId} onSaved={reload} />
      </section>
      <AllowanceForm yearId={yearId} />
    </>
  );
}

function ProfileForm({ campusId, onSaved }: { campusId: string; onSaved: () => void }) {
  const session = useApiSession();
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [person, setPerson] = useState<PersonMatch | null>(null);
  const [f, setF] = useState({
    employeeNo: '',
    designation: '',
    department: '',
    employmentType: 'FULL_TIME',
    joinedOn: '',
  });
  const [error, setError] = useState<string | null>(null);
  const [found] = useApiResource<PersonMatch[]>(
    submitted ? `/api/v1/campuses/${campusId}/people?q=${encodeURIComponent(submitted)}` : null,
  );
  async function save() {
    if (!person) return;
    setError(null);
    try {
      await apiRequest(session, `/api/v1/campuses/${campusId}/staff/${person.personId}`, {
        method: 'PUT',
        body: {
          employeeNo: f.employeeNo.trim(),
          designation: f.designation.trim(),
          department: f.department.trim() || undefined,
          employmentType: f.employmentType,
          joinedOn: f.joinedOn,
        },
      });
      setPerson(null);
      setSubmitted('');
      setQuery('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  const set = (k: keyof typeof f) => (v: string) => setF({ ...f, [k]: v });
  return (
    <div>
      <h3>Add or update a staff profile</h3>
      {!person ? (
        <>
          <div className="form form--inline">
            <Field label="Find a person" hint="Name or reference">
              {(p) => <input {...p} value={query} onChange={(e) => setQuery(e.target.value)} />}
            </Field>
          </div>
          <p className="form__actions">
            <Button disabled={query.trim().length < 2} onClick={() => setSubmitted(query.trim())}>
              Find
            </Button>
          </p>
          {found.status === 'ready' && (
            <ul className="plain-list">
              {found.data.map((m) => (
                <li key={m.personId}>
                  {m.name} <span className="muted">{m.hasProfile ? 'has a profile' : 'no profile yet'}</span>{' '}
                  <Button variant="quiet" onClick={() => setPerson(m)}>{`Choose ${m.name}`}</Button>
                </li>
              ))}
            </ul>
          )}
        </>
      ) : (
        <>
          <p>
            Profile for <strong>{person.name}</strong>{' '}
            <Button variant="quiet" onClick={() => setPerson(null)}>
              Choose someone else
            </Button>
          </p>
          <div className="form form--inline">
            <Field label="Employee number">
              {(p) => (
                <input {...p} value={f.employeeNo} onChange={(e) => set('employeeNo')(e.target.value)} />
              )}
            </Field>
            <Field label="Designation">
              {(p) => (
                <input {...p} value={f.designation} onChange={(e) => set('designation')(e.target.value)} />
              )}
            </Field>
            <Field label="Department" hint="Optional">
              {(p) => (
                <input {...p} value={f.department} onChange={(e) => set('department')(e.target.value)} />
              )}
            </Field>
            <Field label="Employment">
              {(p) => (
                <select
                  {...p}
                  value={f.employmentType}
                  onChange={(e) => set('employmentType')(e.target.value)}
                >
                  {Object.entries(EMPLOYMENT).map(([k, v]) => (
                    <option key={k} value={k}>
                      {v}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Joined on">
              {(p) => (
                <input
                  {...p}
                  type="date"
                  value={f.joinedOn}
                  onChange={(e) => set('joinedOn')(e.target.value)}
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
              disabled={f.employeeNo.trim().length < 2 || f.designation.trim().length < 2 || !f.joinedOn}
              onClick={() => void save()}
            >
              Save profile
            </Button>
          </p>
        </>
      )}
    </div>
  );
}

function AllowanceForm({ yearId }: { yearId: string }) {
  const session = useApiSession();
  const [type, setType] = useState('CASUAL');
  const [days, setDays] = useState('');
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const n = Number(days);
  async function save() {
    setError(null);
    setSaved(false);
    try {
      await apiRequest(session, `/api/v1/academic-years/${yearId}/leave-allowances`, {
        method: 'PUT',
        body: { leaveType: type, days: n },
      });
      setSaved(true);
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <section aria-labelledby="allow-h">
      <h2 id="allow-h">Leave allowances</h2>
      <div className="form form--inline">
        <Field label="Allowance for">
          {(p) => (
            <select {...p} value={type} onChange={(e) => setType(e.target.value)}>
              {['CASUAL', 'SICK', 'EARNED'].map((k) => (
                <option key={k} value={k}>
                  {TYPES[k]}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Days per year">
          {(p) => <input {...p} inputMode="decimal" value={days} onChange={(e) => setDays(e.target.value)} />}
        </Field>
      </div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      {saved && <p role="status">Saved.</p>}
      <p className="form__actions">
        <Button disabled={days.trim() === '' || !Number.isFinite(n) || n < 0} onClick={() => void save()}>
          Save allowance
        </Button>
      </p>
    </section>
  );
}
