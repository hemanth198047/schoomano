import { useState, type FormEvent } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { Campus, Me } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { formatDay } from '../lib/format';

export const canManageStaff = (me: Me) => me.roles.some((r) => r.roleKey === 'group_admin');

interface RoleView {
  grantId: string;
  roleKey: string;
  scopeLevel: 'GROUP' | 'CAMPUS';
  scopeId: string;
  scopeName: string;
  validFrom: string;
  reason: string | null;
}
interface StaffMember {
  personId: string;
  displayName: string;
  email: string | null;
  status: string;
  roles: RoleView[];
}

const ROLES: [string, string, boolean][] = [
  ['group_admin', 'School group administrator', false],
  ['campus_admin', 'Campus administrator', true],
  ['principal', 'Principal', true],
  ['teacher', 'Teacher', true],
  ['finance_officer', 'Finance officer', false],
  ['front_desk', 'Front desk', true],
  ['nurse', 'Nurse', true],
  ['safeguarding_lead', 'Safeguarding lead', true],
];
const roleText = (k: string) => ROLES.find(([key]) => key === k)?.[1] ?? k.replaceAll('_', ' ');
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

/** Who works at the school and what each person may do. Only the school group's administrators see this. */
export function StaffPage({ me, campuses }: { me: Me; campuses: Campus[] }) {
  return (
    <>
      <PageHeader
        title="Staff and access"
        lead="Add the people who work at the school and give each the role they need, for the whole group or for one campus."
      />
      {!canManageStaff(me) ? (
        <EmptyState title="Not available">
          <p>Only the school group's administrators manage staff access.</p>
        </EmptyState>
      ) : (
        <StaffList campuses={campuses} />
      )}
    </>
  );
}

function StaffList({ campuses }: { campuses: Campus[] }) {
  const session = useApiSession();
  const [staff, reload] = useApiResource<StaffMember[]>('/api/v1/staff');
  const [form, setForm] = useState({ givenName: '', familyName: '', email: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const add = async (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!form.givenName.trim()) err.givenName = 'Enter their first name.';
    if (!/^\S+@\S+\.\S+$/.test(form.email.trim())) err.email = 'Enter a valid email address.';
    setErrors(err);
    if (Object.keys(err).length > 0) return;
    setBusy(true);
    setFailure(null);
    setDone(null);
    try {
      await apiRequest(session, '/api/v1/staff', {
        method: 'POST',
        body: {
          givenName: form.givenName.trim(),
          familyName: form.familyName.trim(),
          email: form.email.trim(),
        },
      });
      setForm({ givenName: '', familyName: '', email: '' });
      setDone('Added. Now give them a role.');
      reload();
    } catch (x) {
      setFailure(message(x));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <section className="section" aria-labelledby="staff-h">
        <h2 id="staff-h" className="section__title">
          People
        </h2>
        {staff.status === 'loading' && <LoadingState label="Loading staff" />}
        {staff.status === 'error' && <ErrorState error={staff.error} onRetry={reload} />}
        {staff.status === 'ready' && staff.data.length === 0 && <EmptyState title="No staff yet" />}
        {staff.status === 'ready' &&
          staff.data.map((m) => (
            <Member key={m.personId} member={m} campuses={campuses} onChanged={reload} />
          ))}
      </section>
      <section className="section" aria-labelledby="add-staff-h">
        <h2 id="add-staff-h" className="section__title">
          Add a person
        </h2>
        <form className="form" onSubmit={add} noValidate>
          <Field label="First name" required error={errors.givenName}>
            {(p) => (
              <input
                {...p}
                value={form.givenName}
                onChange={(e) => setForm({ ...form, givenName: e.target.value })}
              />
            )}
          </Field>
          <Field label="Last name">
            {(p) => (
              <input
                {...p}
                value={form.familyName}
                onChange={(e) => setForm({ ...form, familyName: e.target.value })}
              />
            )}
          </Field>
          <Field
            label="Work email"
            required
            error={errors.email}
            hint="Used to recognise them when they sign in."
          >
            {(p) => (
              <input
                {...p}
                type="email"
                value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })}
              />
            )}
          </Field>
          {failure && <p role="alert">{failure}</p>}
          {done && <p role="status">{done}</p>}
          <div className="form__actions">
            <Button type="submit" variant="primary" busy={busy}>
              Add person
            </Button>
          </div>
        </form>
      </section>
    </>
  );
}

function Member({
  member: m,
  campuses,
  onChanged,
}: {
  member: StaffMember;
  campuses: Campus[];
  onChanged: () => void;
}) {
  const session = useApiSession();
  const [role, setRole] = useState('teacher');
  const [campus, setCampus] = useState(campuses[0]?.id ?? '');
  const [reason, setReason] = useState('');
  const [ending, setEnding] = useState<string | null>(null);
  const [endReason, setEndReason] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const wholeGroup = ROLES.find(([k]) => k === role)?.[2] === false;

  async function run(work: () => Promise<unknown>) {
    setBusy(true);
    setProblem(null);
    try {
      await work();
      onChanged();
      return true;
    } catch (x) {
      setProblem(message(x));
      return false;
    } finally {
      setBusy(false);
    }
  }

  const give = async (e: FormEvent) => {
    e.preventDefault();
    if (reason.trim().length < 10)
      return setProblem('Give a reason of at least 10 characters. It is kept in the audit trail.');
    const ok = await run(() =>
      apiRequest(session, `/api/v1/staff/${m.personId}/roles`, {
        method: 'POST',
        body: {
          roleKey: role,
          scopeLevel: wholeGroup ? 'GROUP' : 'CAMPUS',
          scopeId: wholeGroup ? null : campus,
          reason: reason.trim(),
        },
      }),
    );
    if (ok) setReason('');
  };

  const end = async (e: FormEvent) => {
    e.preventDefault();
    if (endReason.trim().length < 10)
      return setProblem('Give a reason of at least 10 characters. It is kept in the audit trail.');
    const ok = await run(() =>
      apiRequest(session, `/api/v1/staff/${m.personId}/roles/${ending}/end`, {
        method: 'POST',
        body: { reason: endReason.trim() },
      }),
    );
    if (ok) {
      setEnding(null);
      setEndReason('');
    }
  };

  return (
    <article className="panel" aria-label={m.displayName}>
      <h3 className="section__subtitle">{m.displayName}</h3>
      {m.email && <p className="muted">{m.email}</p>}
      {m.roles.length === 0 ? (
        <p className="muted">No role yet, so they cannot do anything.</p>
      ) : (
        <ul className="plain-list">
          {m.roles.map((r) => (
            <li key={r.grantId}>
              <strong>{roleText(r.roleKey)}</strong> · {r.scopeName}{' '}
              <span className="muted">since {formatDay(r.validFrom.slice(0, 10))}</span>{' '}
              <Button
                variant="quiet"
                aria-label={`End ${roleText(r.roleKey)} for ${m.displayName}`}
                onClick={() => {
                  setEnding(r.grantId);
                  setEndReason('');
                }}
              >
                End
              </Button>
            </li>
          ))}
        </ul>
      )}
      {problem && <p role="alert">{problem}</p>}
      {ending && (
        <form className="form" onSubmit={end} noValidate>
          <Field
            label={`Why does this role end for ${m.displayName}?`}
            hint="Kept in the audit trail."
            required
          >
            {(p) => (
              <input
                {...p}
                value={endReason}
                maxLength={300}
                onChange={(e) => setEndReason(e.target.value)}
              />
            )}
          </Field>
          <div className="form__actions">
            <Button type="submit" variant="primary" busy={busy}>
              End role
            </Button>
            <Button variant="secondary" onClick={() => setEnding(null)}>
              Cancel
            </Button>
          </div>
        </form>
      )}
      <details>
        <summary>{`Give ${m.displayName} a role`}</summary>
        <form className="form" onSubmit={give} noValidate>
          <Field label={`Role for ${m.displayName}`}>
            {(p) => (
              <select {...p} value={role} onChange={(e) => setRole(e.target.value)}>
                {ROLES.map(([k, label]) => (
                  <option key={k} value={k}>
                    {label}
                  </option>
                ))}
              </select>
            )}
          </Field>
          {wholeGroup ? (
            <p className="muted">This role covers the whole school group.</p>
          ) : (
            <Field label={`Campus for ${m.displayName}`}>
              {(p) => (
                <select {...p} value={campus} onChange={(e) => setCampus(e.target.value)}>
                  {campuses.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              )}
            </Field>
          )}
          <Field
            label={`Reason for ${m.displayName}'s role`}
            hint="At least 10 characters. Kept in the audit trail."
            required
          >
            {(p) => (
              <input {...p} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
            )}
          </Field>
          <div className="form__actions">
            <Button type="submit" variant="primary" busy={busy}>
              Give role
            </Button>
          </div>
        </form>
      </details>
    </article>
  );
}
