import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { Campus, ExpectedVisit, Me, Visit, VisitHost } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDateTime, formatDay } from '../lib/format';

const has = (me: Me, ...keys: string[]) => me.roles.some((r) => keys.includes(r.roleKey));
export const canRecordVisitors = (me: Me) => has(me, 'campus_admin', 'group_admin', 'front_desk');
export const canExpectVisitors = (me: Me) =>
  has(me, 'campus_admin', 'group_admin', 'principal', 'teacher', 'finance_officer');
export const canSeeVisitorLog = (me: Me) => canRecordVisitors(me) || has(me, 'principal');
export const canSeeVisitors = (me: Me) => canSeeVisitorLog(me) || canExpectVisitors(me);

const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function VisitorsPage({
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
  return (
    <>
      <PageHeader title="Visitors" lead="Who is on campus, who they came to see, and when they left." />
      {!canSeeVisitors(me) ? (
        <EmptyState title="Not available">
          <p>Visitors are handled by school staff.</p>
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
          {campusId === null ? (
            <EmptyState title="No campus yet" />
          ) : (
            <Body key={campusId} me={me} campusId={campusId} />
          )}
        </>
      )}
    </>
  );
}

function Body({ me, campusId }: { me: Me; campusId: string }) {
  const [today, setToday] = useState(false);
  const [visits, reload] = useApiResource<Visit[]>(
    canSeeVisitorLog(me) ? `/api/v1/campuses/${campusId}/visits?view=${today ? 'today' : 'inside'}` : null,
  );
  const [gate, reloadGate] = useApiResource<ExpectedVisit[]>(
    canRecordVisitors(me) ? `/api/v1/campuses/${campusId}/expected-visitors?view=today` : null,
  );
  const [mine, reloadMine] = useApiResource<ExpectedVisit[]>(
    canExpectVisitors(me) ? `/api/v1/campuses/${campusId}/expected-visitors?view=mine` : null,
  );
  const refresh = () => {
    reload();
    reloadGate();
    reloadMine();
  };
  return (
    <>
      {canSeeVisitorLog(me) && (
        <section aria-labelledby="log-h">
          <h2 id="log-h">{today ? 'Today’s visitors' : 'Inside now'}</h2>
          <p>
            <Button variant="quiet" onClick={() => setToday(!today)}>
              {today ? 'Show who is inside now' : 'Show everyone today'}
            </Button>
          </p>
          {visits.status === 'loading' && <LoadingState label="Loading visitors" />}
          {visits.status === 'error' && <ErrorState error={visits.error} onRetry={reload} />}
          {visits.status === 'ready' &&
            (visits.data.length === 0 ? (
              <EmptyState title={today ? 'No visitors today' : 'Nobody is inside'} />
            ) : (
              <ul className="plain-list">
                {visits.data.map((v) => (
                  <VisitRow key={v.id} v={v} canRecord={canRecordVisitors(me)} onChanged={refresh} />
                ))}
              </ul>
            ))}
        </section>
      )}
      {canRecordVisitors(me) && (
        <section aria-labelledby="gate-h">
          <h2 id="gate-h">Expected today</h2>
          {gate.status === 'ready' &&
            (gate.data.length === 0 ? (
              <p className="muted">Nobody has been announced for today.</p>
            ) : (
              <ul className="plain-list">
                {gate.data.map((e) => (
                  <ArrivalRow key={e.id} e={e} onChanged={refresh} />
                ))}
              </ul>
            ))}
        </section>
      )}
      {canRecordVisitors(me) && <CheckIn campusId={campusId} onSaved={refresh} />}
      {canExpectVisitors(me) && (
        <Announce campusId={campusId} mine={mine.status === 'ready' ? mine.data : []} onChanged={refresh} />
      )}
    </>
  );
}

function ArrivalRow({ e, onChanged }: { e: ExpectedVisit; onChanged: () => void }) {
  const session = useApiSession();
  const [badge, setBadge] = useState('');
  const [error, setError] = useState<string | null>(null);
  async function arrive() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/expected-visitors/${e.id}/arrive`, {
        method: 'POST',
        body: { badgeNo: badge.trim() || undefined },
      });
      setBadge('');
      onChanged();
    } catch (err) {
      setError(message(err));
    }
  }
  return (
    <li>
      <p>
        <strong>{e.visitorName}</strong>{' '}
        <span className="muted">
          {e.purpose}, to see {e.host}
          {e.organisation ? `, ${e.organisation}` : ''}
        </span>
      </p>
      <div className="form form--inline">
        <Field label={`Badge for ${e.visitorName}`} hint="Optional">
          {(p) => <input {...p} value={badge} onChange={(ev) => setBadge(ev.target.value)} />}
        </Field>
        <Button onClick={() => void arrive()}>{`Check in ${e.visitorName}`}</Button>
      </div>
      {error && <p role="alert">{error}</p>}
    </li>
  );
}

function Announce({
  campusId,
  mine,
  onChanged,
}: {
  campusId: string;
  mine: ExpectedVisit[];
  onChanged: () => void;
}) {
  const session = useApiSession();
  const [name, setName] = useState('');
  const [organisation, setOrganisation] = useState('');
  const [purpose, setPurpose] = useState('');
  const [on, setOn] = useState('');
  const [error, setError] = useState<string | null>(null);
  async function save() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/campuses/${campusId}/expected-visitors`, {
        method: 'POST',
        body: {
          visitorName: name.trim(),
          organisation: organisation.trim() || undefined,
          purpose: purpose.trim(),
          expectedOn: on,
        },
      });
      setName('');
      setOrganisation('');
      setPurpose('');
      setOn('');
      onChanged();
    } catch (e) {
      setError(message(e));
    }
  }
  async function cancel(e: ExpectedVisit) {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/expected-visitors/${e.id}/cancel`, { method: 'POST' });
      onChanged();
    } catch (err) {
      setError(message(err));
    }
  }
  return (
    <section aria-labelledby="ann-h">
      <h2 id="ann-h">Announce a guest</h2>
      {mine.length > 0 && (
        <ul className="plain-list" aria-label="Your announced guests">
          {mine.map((e) => (
            <li key={e.id}>
              {e.visitorName}, {formatDay(e.expectedOn)} <span className="muted">{e.purpose}</span>{' '}
              <Button variant="quiet" onClick={() => void cancel(e)}>{`Cancel ${e.visitorName}`}</Button>
            </li>
          ))}
        </ul>
      )}
      <div className="form">
        <Field label="Guest name">
          {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Guest organisation" hint="Optional">
          {(p) => <input {...p} value={organisation} onChange={(e) => setOrganisation(e.target.value)} />}
        </Field>
        <Field label="Why they are coming">
          {(p) => <input {...p} value={purpose} onChange={(e) => setPurpose(e.target.value)} />}
        </Field>
        <Field label="Expected on">
          {(p) => <input {...p} type="date" value={on} onChange={(e) => setOn(e.target.value)} />}
        </Field>
        {error && <p role="alert">{error}</p>}
        <Button
          onClick={() => void save()}
          disabled={name.trim().length < 2 || purpose.trim().length < 2 || !on}
        >
          Announce guest
        </Button>
      </div>
    </section>
  );
}

function VisitRow({ v, canRecord, onChanged }: { v: Visit; canRecord: boolean; onChanged: () => void }) {
  const session = useApiSession();
  const [error, setError] = useState<string | null>(null);
  async function out() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/visits/${v.id}/checkout`, { method: 'POST' });
      onChanged();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <li>
      <p>
        <strong>{v.visitorName}</strong>{' '}
        <StatusBadge tone={v.inside ? 'warning' : 'neutral'}>{v.inside ? 'Inside' : 'Left'}</StatusBadge>{' '}
        <span className="muted">
          {v.purpose}
          {v.host ? `, to see ${v.host}` : ''}
          {v.organisation ? `, ${v.organisation}` : ''}
          {v.badgeNo ? `, badge ${v.badgeNo}` : ''}, in {formatDateTime(v.checkedInAt)}
          {v.checkedOutAt ? `, out ${formatDateTime(v.checkedOutAt)}` : ''}
        </span>
        {v.inside && canRecord && (
          <>
            {' '}
            <Button variant="quiet" onClick={() => void out()}>{`Check out ${v.visitorName}`}</Button>
          </>
        )}
      </p>
      {error && <p role="alert">{error}</p>}
    </li>
  );
}

function CheckIn({ campusId, onSaved }: { campusId: string; onSaved: () => void }) {
  const session = useApiSession();
  const [staff] = useApiResource<VisitHost[]>(`/api/v1/campuses/${campusId}/visit-hosts`);
  const [name, setName] = useState('');
  const [organisation, setOrganisation] = useState('');
  const [phone, setPhone] = useState('');
  const [purpose, setPurpose] = useState('');
  const [host, setHost] = useState('');
  const [badge, setBadge] = useState('');
  const [error, setError] = useState<string | null>(null);
  async function save() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/campuses/${campusId}/visits`, {
        method: 'POST',
        body: {
          visitorName: name.trim(),
          organisation: organisation.trim() || undefined,
          phone: phone.trim() || undefined,
          purpose: purpose.trim(),
          hostPersonId: host || undefined,
          badgeNo: badge.trim() || undefined,
        },
      });
      setName('');
      setOrganisation('');
      setPhone('');
      setPurpose('');
      setHost('');
      setBadge('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <section aria-labelledby="in-h">
      <h2 id="in-h">Check a visitor in</h2>
      <div className="form">
        <Field label="Visitor name">
          {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Organisation" hint="Optional">
          {(p) => <input {...p} value={organisation} onChange={(e) => setOrganisation(e.target.value)} />}
        </Field>
        <Field label="Phone" hint="Optional">
          {(p) => <input {...p} inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} />}
        </Field>
        <Field label="Purpose of visit">
          {(p) => <input {...p} value={purpose} onChange={(e) => setPurpose(e.target.value)} />}
        </Field>
        <Field label="Here to see" hint="Optional">
          {(p) => (
            <select {...p} value={host} onChange={(e) => setHost(e.target.value)}>
              <option value="">Nobody in particular</option>
              {staff.status === 'ready' &&
                staff.data.map((s) => (
                  <option key={s.personId} value={s.personId}>
                    {s.name}
                  </option>
                ))}
            </select>
          )}
        </Field>
        <Field label="Badge number" hint="Optional">
          {(p) => <input {...p} value={badge} onChange={(e) => setBadge(e.target.value)} />}
        </Field>
        {error && <p role="alert">{error}</p>}
        <Button onClick={() => void save()} disabled={name.trim().length < 2 || purpose.trim().length < 2}>
          Check in
        </Button>
      </div>
    </section>
  );
}
