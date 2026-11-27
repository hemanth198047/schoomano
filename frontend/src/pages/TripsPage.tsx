import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { AcademicStructure, Campus, Me, StudentListItem, Trip, TripParticipant } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { formatDay, formatPaise, rupeesToPaise } from '../lib/format';

const has = (me: Me, ...keys: string[]) => me.roles.some((r) => keys.includes(r.roleKey));
export const canManageTrips = (me: Me) => has(me, 'campus_admin', 'group_admin');
export const canCountHeads = (me: Me) => has(me, 'campus_admin', 'teacher');
export const canSeeTrips = (me: Me) => canManageTrips(me) || canCountHeads(me) || has(me, 'principal');

const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');
const todayIst = () => new Date(Date.now() + 5.5 * 3600 * 1000).toISOString().slice(0, 10);
const CONSENT: Record<TripParticipant['consent'], string> = {
  NONE: 'No answer yet',
  GIVEN: 'Consent given',
  DECLINED: 'Declined',
};

export function TripsPage({
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
  const allowed = canSeeTrips(me);
  const [structure] = useApiResource<AcademicStructure>(
    allowed && campusId !== null ? `/api/v1/campuses/${campusId}/academic-structure` : null,
  );
  return (
    <>
      <PageHeader
        title="Trips"
        lead="Plan a trip, see which guardians have said yes, and count heads on the day."
      />
      {!allowed ? (
        <EmptyState title="Not available">
          <p>Trips are run by the campus office and the teachers who go on them.</p>
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
  const [trips, reload] = useApiResource<Trip[]>(`/api/v1/academic-years/${yearId}/trips`);
  const [openId, setOpenId] = useState<string | null>(null);
  return (
    <>
      <section aria-labelledby="trips-h">
        <h2 id="trips-h">This year</h2>
        {trips.status === 'loading' && <LoadingState label="Loading trips" />}
        {trips.status === 'error' && <ErrorState error={trips.error} onRetry={reload} />}
        {trips.status === 'ready' &&
          (trips.data.length === 0 ? (
            <EmptyState title="No trips yet" />
          ) : (
            <ul className="plain-list">
              {trips.data.map((t) => (
                <li key={t.id}>
                  <p>
                    <strong>{t.name}</strong>{' '}
                    <span className="muted">
                      {t.destination}, {formatDay(t.tripDate)}, {formatPaise(t.costPaise)}, consent by{' '}
                      {formatDay(t.consentDeadline)}, {t.invited} invited, {t.given} said yes, {t.declined}{' '}
                      said no
                      {t.status !== 'PLANNED' ? `, ${t.status.toLowerCase()}` : ''}
                    </span>{' '}
                    <Button variant="quiet" onClick={() => setOpenId(openId === t.id ? null : t.id)}>
                      {openId === t.id ? `Hide ${t.name}` : `Open ${t.name}`}
                    </Button>
                  </p>
                  {openId === t.id && <Detail me={me} yearId={yearId} trip={t} onChanged={reload} />}
                </li>
              ))}
            </ul>
          ))}
      </section>
      {canManageTrips(me) && <CreateForm yearId={yearId} onSaved={reload} />}
    </>
  );
}

function CreateForm({ yearId, onSaved }: { yearId: string; onSaved: () => void }) {
  const session = useApiSession();
  const [name, setName] = useState('');
  const [destination, setDestination] = useState('');
  const [day, setDay] = useState('');
  const [deadline, setDeadline] = useState('');
  const [cost, setCost] = useState('0');
  const [seats, setSeats] = useState('');
  const [error, setError] = useState<string | null>(null);
  const paise = rupeesToPaise(cost);
  async function add() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/academic-years/${yearId}/trips`, {
        method: 'POST',
        body: {
          name: name.trim(),
          destination: destination.trim(),
          tripDate: day,
          consentDeadline: deadline,
          costPaise: paise,
          capacity: seats ? Number(seats) : undefined,
        },
      });
      setName('');
      setDestination('');
      setDay('');
      setDeadline('');
      setCost('0');
      setSeats('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <section aria-labelledby="new-trip-h">
      <h2 id="new-trip-h">Plan a trip</h2>
      <div className="form form--inline">
        <Field label="Trip name">
          {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Destination">
          {(p) => <input {...p} value={destination} onChange={(e) => setDestination(e.target.value)} />}
        </Field>
        <Field label="Trip date">
          {(p) => <input {...p} type="date" value={day} onChange={(e) => setDay(e.target.value)} />}
        </Field>
        <Field label="Consent needed by">
          {(p) => <input {...p} type="date" value={deadline} onChange={(e) => setDeadline(e.target.value)} />}
        </Field>
        <Field label="Cost per child (rupees)">
          {(p) => <input {...p} inputMode="decimal" value={cost} onChange={(e) => setCost(e.target.value)} />}
        </Field>
        <Field label="Seats" hint="Leave empty for no limit">
          {(p) => (
            <input {...p} inputMode="numeric" value={seats} onChange={(e) => setSeats(e.target.value)} />
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
          disabled={
            name.trim().length < 2 ||
            destination.trim().length < 2 ||
            !day ||
            !deadline ||
            paise === null ||
            (seats !== '' && !(Number(seats) >= 1))
          }
          onClick={() => void add()}
        >
          Plan trip
        </Button>
      </p>
    </section>
  );
}

function Detail({
  me,
  yearId,
  trip,
  onChanged,
}: {
  me: Me;
  yearId: string;
  trip: Trip;
  onChanged: () => void;
}) {
  const session = useApiSession();
  const [people, reload] = useApiResource<TripParticipant[]>(`/api/v1/trips/${trip.id}/participants`);
  const [error, setError] = useState<string | null>(null);
  const open = trip.status === 'PLANNED';
  const started = trip.tripDate <= todayIst();

  async function run(path: string, body?: unknown) {
    setError(null);
    try {
      await apiRequest(session, path, { method: 'POST', body });
      reload();
      onChanged();
      return true;
    } catch (e) {
      setError(message(e));
      return false;
    }
  }

  return (
    <div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      {people.status === 'loading' && <LoadingState label="Loading list" />}
      {people.status === 'error' && <ErrorState error={people.error} onRetry={reload} />}
      {people.status === 'ready' &&
        (people.data.length === 0 ? (
          <p className="muted">Nobody invited yet.</p>
        ) : (
          <ul className="plain-list" aria-label={`Students on ${trip.name}`}>
            {people.data.map((p) => (
              <li key={p.id}>
                {p.name} <span className="muted">{CONSENT[p.consent]}</span>{' '}
                {open && canManageTrips(me) && (
                  <Button
                    variant="quiet"
                    onClick={() => void run(`/api/v1/trip-participants/${p.id}/remove`)}
                  >
                    {`Take ${p.name} off`}
                  </Button>
                )}
                {open && canCountHeads(me) && started && p.consent === 'GIVEN' && (
                  <>
                    {p.departed ? (
                      <span>Left</span>
                    ) : (
                      <Button
                        variant="quiet"
                        onClick={() =>
                          void run(`/api/v1/trips/${trip.id}/headcount`, {
                            studentId: p.studentId,
                            checkpoint: 'DEPARTED',
                          })
                        }
                      >
                        {`${p.name} left`}
                      </Button>
                    )}{' '}
                    {p.returned ? (
                      <span>Back</span>
                    ) : (
                      p.departed && (
                        <Button
                          variant="quiet"
                          onClick={() =>
                            void run(`/api/v1/trips/${trip.id}/headcount`, {
                              studentId: p.studentId,
                              checkpoint: 'RETURNED',
                            })
                          }
                        >
                          {`${p.name} is back`}
                        </Button>
                      )
                    )}
                  </>
                )}
              </li>
            ))}
          </ul>
        ))}
      {open && canManageTrips(me) && (
        <>
          <Invite
            yearId={yearId}
            onInvite={(id) => run(`/api/v1/trips/${trip.id}/participants`, { studentIds: [id] })}
          />
          <p>
            {started && (
              <Button variant="quiet" onClick={() => void run(`/api/v1/trips/${trip.id}/complete`)}>
                {`Mark ${trip.name} complete`}
              </Button>
            )}{' '}
            <Button variant="quiet" onClick={() => void run(`/api/v1/trips/${trip.id}/cancel`)}>
              {`Cancel ${trip.name}`}
            </Button>
          </p>
        </>
      )}
    </div>
  );
}

function Invite({ yearId, onInvite }: { yearId: string; onInvite: (studentId: string) => Promise<boolean> }) {
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [found] = useApiResource<{ students: StudentListItem[] }>(
    submitted ? `/api/v1/academic-years/${yearId}/students?limit=8&q=${encodeURIComponent(submitted)}` : null,
  );
  return (
    <div>
      <div className="form form--inline">
        <Field label="Find a child to invite">
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
                  void onInvite(s.id).then((ok) => {
                    if (ok) {
                      setSubmitted('');
                      setQuery('');
                    }
                  });
                }}
              >{`Invite ${s.displayName}`}</Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
