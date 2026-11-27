import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { Campus, Me, StaffMember, Visit } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDateTime } from '../lib/format';

const has = (me: Me, ...keys: string[]) => me.roles.some((r) => keys.includes(r.roleKey));
export const canRecordVisitors = (me: Me) => has(me, 'campus_admin', 'group_admin');
export const canSeeVisitors = (me: Me) => canRecordVisitors(me) || has(me, 'principal');

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
          <p>The visitor log is kept by the campus office and the principal.</p>
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
    `/api/v1/campuses/${campusId}/visits?view=${today ? 'today' : 'inside'}`,
  );
  return (
    <>
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
                <VisitRow key={v.id} v={v} canRecord={canRecordVisitors(me)} onChanged={reload} />
              ))}
            </ul>
          ))}
      </section>
      {canRecordVisitors(me) && <CheckIn campusId={campusId} onSaved={reload} />}
    </>
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
  const [staff] = useApiResource<StaffMember[]>(`/api/v1/campuses/${campusId}/staff`);
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
