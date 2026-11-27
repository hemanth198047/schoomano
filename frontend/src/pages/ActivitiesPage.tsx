import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  Activity,
  ActivityMember,
  ActivitySession,
  Campus,
  Me,
  StudentListItem,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';

const has = (me: Me, ...keys: string[]) => me.roles.some((r) => keys.includes(r.roleKey));
export const canManageActivities = (me: Me) => has(me, 'campus_admin', 'group_admin');
export const canTakeActivityAttendance = (me: Me) => has(me, 'campus_admin', 'teacher');
export const canSeeActivities = (me: Me) =>
  canManageActivities(me) || canTakeActivityAttendance(me) || has(me, 'principal');

const KINDS: Record<Activity['kind'], string> = {
  CLUB: 'Club',
  SPORT: 'Sport',
  ARTS: 'Arts',
  OTHER: 'Other',
};
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');
const todayIst = () => new Date(Date.now() + 5.5 * 3600 * 1000).toISOString().slice(0, 10);

export function ActivitiesPage({
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
  const allowed = canSeeActivities(me);
  const [structure] = useApiResource<AcademicStructure>(
    allowed && campusId !== null ? `/api/v1/campuses/${campusId}/academic-structure` : null,
  );
  return (
    <>
      <PageHeader
        title="Activities"
        lead="Clubs, sports and arts: who is in each, and who came to each session."
      />
      {!allowed ? (
        <EmptyState title="Not available">
          <p>Activities are run by the campus office and the teachers who lead them.</p>
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
            <Body key={structure.data.year.id} me={me} yearId={structure.data.year.id} />
          )}
        </>
      )}
    </>
  );
}

function Body({ me, yearId }: { me: Me; yearId: string }) {
  const [activities, reload] = useApiResource<Activity[]>(`/api/v1/academic-years/${yearId}/activities`);
  const [openId, setOpenId] = useState<string | null>(null);
  return (
    <>
      <section aria-labelledby="act-h">
        <h2 id="act-h">This year</h2>
        {activities.status === 'loading' && <LoadingState label="Loading activities" />}
        {activities.status === 'error' && <ErrorState error={activities.error} onRetry={reload} />}
        {activities.status === 'ready' &&
          (activities.data.length === 0 ? (
            <EmptyState title="No activities yet" />
          ) : (
            <ul className="plain-list">
              {activities.data.map((a) => (
                <li key={a.id}>
                  <p>
                    <strong>{a.name}</strong>{' '}
                    <span className="muted">
                      {KINDS[a.kind]}
                      {a.leadName ? `, led by ${a.leadName}` : ''}, {a.members}
                      {a.capacity !== null ? ` of ${a.capacity}` : ''} members, {a.sessions} sessions
                      {a.status === 'CLOSED' ? ', closed' : ''}
                    </span>{' '}
                    <Button variant="quiet" onClick={() => setOpenId(openId === a.id ? null : a.id)}>
                      {openId === a.id ? `Hide ${a.name}` : `Open ${a.name}`}
                    </Button>
                  </p>
                  {openId === a.id && <Detail me={me} yearId={yearId} activity={a} onChanged={reload} />}
                </li>
              ))}
            </ul>
          ))}
      </section>
      {canManageActivities(me) && <CreateForm yearId={yearId} onSaved={reload} />}
    </>
  );
}

function CreateForm({ yearId, onSaved }: { yearId: string; onSaved: () => void }) {
  const session = useApiSession();
  const [name, setName] = useState('');
  const [kind, setKind] = useState<Activity['kind']>('CLUB');
  const [capacity, setCapacity] = useState('');
  const [lead, setLead] = useState('');
  const [error, setError] = useState<string | null>(null);
  async function add() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/academic-years/${yearId}/activities`, {
        method: 'POST',
        body: {
          name: name.trim(),
          kind,
          capacity: capacity ? Number(capacity) : undefined,
          leadName: lead.trim() || undefined,
        },
      });
      setName('');
      setCapacity('');
      setLead('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <section aria-labelledby="new-act-h">
      <h2 id="new-act-h">Add an activity</h2>
      <div className="form form--inline">
        <Field label="Activity name">
          {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Kind">
          {(p) => (
            <select {...p} value={kind} onChange={(e) => setKind(e.target.value as Activity['kind'])}>
              {Object.entries(KINDS).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Seats" hint="Leave empty for no limit">
          {(p) => (
            <input
              {...p}
              inputMode="numeric"
              value={capacity}
              onChange={(e) => setCapacity(e.target.value)}
            />
          )}
        </Field>
        <Field label="Led by">
          {(p) => <input {...p} value={lead} onChange={(e) => setLead(e.target.value)} />}
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
          disabled={name.trim().length < 2 || (capacity !== '' && !(Number(capacity) >= 1))}
          onClick={() => void add()}
        >
          Add activity
        </Button>
      </p>
    </section>
  );
}

function Detail({
  me,
  yearId,
  activity,
  onChanged,
}: {
  me: Me;
  yearId: string;
  activity: Activity;
  onChanged: () => void;
}) {
  const session = useApiSession();
  const [members, reloadMembers] = useApiResource<ActivityMember[]>(
    `/api/v1/activities/${activity.id}/members`,
  );
  const [sessions, reloadSessions] = useApiResource<ActivitySession[]>(
    `/api/v1/activities/${activity.id}/sessions`,
  );
  const [error, setError] = useState<string | null>(null);
  const [day, setDay] = useState(todayIst());
  const [absent, setAbsent] = useState<Set<string>>(new Set());
  const [markFor, setMarkFor] = useState<string | null>(null);
  const manage = canManageActivities(me) && activity.status === 'OPEN';
  const attend = canTakeActivityAttendance(me) && activity.status === 'OPEN';

  async function run(path: string, body?: unknown) {
    setError(null);
    try {
      await apiRequest(session, path, { method: 'POST', body });
      reloadMembers();
      reloadSessions();
      onChanged();
      return true;
    } catch (e) {
      setError(message(e));
      return false;
    }
  }

  async function save(sessionId: string) {
    if (members.status !== 'ready') return;
    const marks = members.data.map((m) => ({ studentId: m.studentId, present: !absent.has(m.studentId) }));
    if (await run(`/api/v1/activity-sessions/${sessionId}/attendance`, { marks })) {
      setMarkFor(null);
      setAbsent(new Set());
    }
  }

  return (
    <div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <h3>Members</h3>
      {members.status === 'loading' && <LoadingState label="Loading members" />}
      {members.status === 'error' && <ErrorState error={members.error} onRetry={reloadMembers} />}
      {members.status === 'ready' &&
        (members.data.length === 0 ? (
          <p className="muted">Nobody has joined yet.</p>
        ) : (
          <ul className="plain-list" aria-label={`Members of ${activity.name}`}>
            {members.data.map((m) => (
              <li key={m.id}>
                {m.name}{' '}
                {manage && (
                  <Button
                    variant="quiet"
                    onClick={() => void run(`/api/v1/activity-members/${m.id}/withdraw`)}
                  >
                    {`Remove ${m.name}`}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        ))}
      {manage && (
        <AddMember
          yearId={yearId}
          activityId={activity.id}
          onAdd={(id) => run(`/api/v1/activities/${activity.id}/members`, { studentId: id })}
        />
      )}
      <h3>Sessions</h3>
      {sessions.status === 'ready' && sessions.data.length === 0 && <p className="muted">No sessions yet.</p>}
      {sessions.status === 'ready' && (
        <ul className="plain-list" aria-label={`Sessions of ${activity.name}`}>
          {sessions.data.map((s) => (
            <li key={s.id}>
              {s.sessionDate}
              {s.topic ? `, ${s.topic}` : ''}{' '}
              <span className="muted">
                {s.present} came, {s.absent} missed
              </span>{' '}
              {attend && s.present + s.absent === 0 && s.sessionDate <= todayIst() && (
                <Button variant="quiet" onClick={() => setMarkFor(markFor === s.id ? null : s.id)}>
                  {`Take attendance for ${s.sessionDate}`}
                </Button>
              )}
              {markFor === s.id && members.status === 'ready' && (
                <div>
                  <ul className="plain-list">
                    {members.data.map((m) => (
                      <li key={m.id}>
                        <label>
                          <input
                            type="checkbox"
                            checked={!absent.has(m.studentId)}
                            onChange={(e) => {
                              const next = new Set(absent);
                              if (e.target.checked) next.delete(m.studentId);
                              else next.add(m.studentId);
                              setAbsent(next);
                            }}
                          />{' '}
                          {m.name} came
                        </label>
                      </li>
                    ))}
                  </ul>
                  <Button variant="primary" onClick={() => void save(s.id)}>
                    Save attendance
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {attend && (
        <div className="form form--inline">
          <Field label={`Session date for ${activity.name}`}>
            {(p) => <input {...p} type="date" value={day} onChange={(e) => setDay(e.target.value)} />}
          </Field>
          <Button
            disabled={!day}
            onClick={() => void run(`/api/v1/activities/${activity.id}/sessions`, { sessionDate: day })}
          >
            {`Add session to ${activity.name}`}
          </Button>
        </div>
      )}
      {manage && (
        <p>
          <Button variant="quiet" onClick={() => void run(`/api/v1/activities/${activity.id}/close`)}>
            {`Close ${activity.name}`}
          </Button>
        </p>
      )}
    </div>
  );
}

function AddMember({
  yearId,
  activityId,
  onAdd,
}: {
  yearId: string;
  activityId: string;
  onAdd: (studentId: string) => Promise<boolean>;
}) {
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [found] = useApiResource<{ students: StudentListItem[] }>(
    submitted ? `/api/v1/academic-years/${yearId}/students?limit=8&q=${encodeURIComponent(submitted)}` : null,
  );
  return (
    <div key={activityId}>
      <div className="form form--inline">
        <Field label="Find a child to add">
          {(p) => <input {...p} value={query} onChange={(e) => setQuery(e.target.value)} />}
        </Field>
        <Button disabled={query.trim().length < 2} onClick={() => setSubmitted(query.trim())}>
          Search
        </Button>
      </div>
      {found.status === 'ready' && (
        <ul className="plain-list">
          {found.data.students.map((s) => (
            <li key={s.id}>
              {s.displayName}{' '}
              <Button
                variant="quiet"
                onClick={() => {
                  void onAdd(s.id).then((ok) => {
                    if (ok) {
                      setSubmitted('');
                      setQuery('');
                    }
                  });
                }}
              >{`Add ${s.displayName}`}</Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
