import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  Campus,
  Me,
  RosterEntry,
  StudentListItem,
  TransportRoute,
  Vehicle,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';

const has = (me: Me, ...keys: string[]) => me.roles.some((r) => keys.includes(r.roleKey));
export const canManageTransport = (me: Me) => has(me, 'campus_admin', 'principal', 'group_admin');
export const canRecordBoarding = (me: Me) => has(me, 'transport_attendant', 'principal');
export const canSeeTransport = (me: Me) => canManageTransport(me) || canRecordBoarding(me);

const short = (t: string) => t.slice(0, 5);
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function TransportPage({
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
  const allowed = canSeeTransport(me);
  const [structure] = useApiResource<AcademicStructure>(
    allowed && campusId !== null ? `/api/v1/campuses/${campusId}/academic-structure` : null,
  );
  return (
    <>
      <PageHeader
        title="Transport"
        lead="Routes, stops and vehicles, who rides which bus, and who got on and off today."
      />
      {!allowed ? (
        <EmptyState title="Not available">
          <p>Transport is run by the campus office and the transport attendants.</p>
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
  const [routes, reloadRoutes] = useApiResource<TransportRoute[]>(`/api/v1/campuses/${campusId}/routes`);
  const [vehicles, reloadVehicles] = useApiResource<Vehicle[]>(`/api/v1/campuses/${campusId}/vehicles`);
  return (
    <>
      <section aria-labelledby="routes-h">
        <h2 id="routes-h">Routes</h2>
        {routes.status === 'loading' && <LoadingState label="Loading routes" />}
        {routes.status === 'error' && <ErrorState error={routes.error} onRetry={reloadRoutes} />}
        {routes.status === 'ready' &&
          (routes.data.length === 0 ? (
            <EmptyState title="No routes yet" />
          ) : (
            <ul className="plain-list">
              {routes.data.map((r) => (
                <li key={r.id}>
                  <p>
                    <strong>{r.name}</strong>{' '}
                    <span className="muted">
                      Driver {r.driverName}
                      {r.vehicle ? `, bus ${r.vehicle}` : ', no bus yet'}, {r.assigned}
                      {r.capacity !== null ? ` of ${r.capacity}` : ''} seats used
                    </span>
                  </p>
                  <ol className="plain-list" aria-label={`Stops of ${r.name}`}>
                    {r.stops.map((s) => (
                      <li key={s.id}>
                        {short(s.pickupTime)} {s.name}
                      </li>
                    ))}
                  </ol>
                  {canManageTransport(me) && <StopForm route={r} onSaved={reloadRoutes} />}
                  {canRecordBoarding(me) && <Roster route={r} />}
                </li>
              ))}
            </ul>
          ))}
      </section>
      {canManageTransport(me) && (
        <>
          <VehicleForm campusId={campusId} onSaved={reloadVehicles} />
          <RouteForm
            campusId={campusId}
            vehicles={vehicles.status === 'ready' ? vehicles.data : []}
            onSaved={reloadRoutes}
          />
          {routes.status === 'ready' && (
            <AssignForm yearId={yearId} routes={routes.data} onSaved={reloadRoutes} />
          )}
        </>
      )}
    </>
  );
}

function VehicleForm({ campusId, onSaved }: { campusId: string; onSaved: () => void }) {
  const session = useApiSession();
  const [registration, setRegistration] = useState('');
  const [capacity, setCapacity] = useState('');
  const [error, setError] = useState<string | null>(null);
  const seats = Number(capacity);
  async function add() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/campuses/${campusId}/vehicles`, {
        method: 'POST',
        body: { registration: registration.trim(), capacity: seats },
      });
      setRegistration('');
      setCapacity('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <section aria-labelledby="vehicle-h">
      <h2 id="vehicle-h">Add a vehicle</h2>
      <div className="form form--inline">
        <Field label="Registration number">
          {(p) => <input {...p} value={registration} onChange={(e) => setRegistration(e.target.value)} />}
        </Field>
        <Field label="Seats">
          {(p) => (
            <input
              {...p}
              inputMode="numeric"
              value={capacity}
              onChange={(e) => setCapacity(e.target.value)}
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
          disabled={registration.trim().length < 4 || !Number.isInteger(seats) || seats < 1}
          onClick={() => void add()}
        >
          Add vehicle
        </Button>
      </p>
    </section>
  );
}

function RouteForm({
  campusId,
  vehicles,
  onSaved,
}: {
  campusId: string;
  vehicles: Vehicle[];
  onSaved: () => void;
}) {
  const session = useApiSession();
  const [name, setName] = useState('');
  const [vehicleId, setVehicleId] = useState('');
  const [driver, setDriver] = useState('');
  const [phone, setPhone] = useState('');
  const [error, setError] = useState<string | null>(null);
  async function add() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/campuses/${campusId}/routes`, {
        method: 'POST',
        body: {
          name: name.trim(),
          vehicleId: vehicleId || undefined,
          driverName: driver.trim(),
          driverPhone: phone.trim() || undefined,
        },
      });
      setName('');
      setDriver('');
      setPhone('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <section aria-labelledby="route-h">
      <h2 id="route-h">Add a route</h2>
      <div className="form form--inline">
        <Field label="Route name">
          {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Vehicle">
          {(p) => (
            <select {...p} value={vehicleId} onChange={(e) => setVehicleId(e.target.value)}>
              <option value="">No vehicle yet</option>
              {vehicles.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.registration} ({v.capacity} seats)
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Driver name">
          {(p) => <input {...p} value={driver} onChange={(e) => setDriver(e.target.value)} />}
        </Field>
        <Field label="Driver phone" hint="Optional, digits only">
          {(p) => <input {...p} inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} />}
        </Field>
      </div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <p className="form__actions">
        <Button disabled={name.trim().length < 2 || driver.trim().length < 2} onClick={() => void add()}>
          Add route
        </Button>
      </p>
    </section>
  );
}

function StopForm({ route, onSaved }: { route: TransportRoute; onSaved: () => void }) {
  const session = useApiSession();
  const [name, setName] = useState('');
  const [time, setTime] = useState('');
  const [error, setError] = useState<string | null>(null);
  async function add() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/routes/${route.id}/stops`, {
        method: 'POST',
        body: { name: name.trim(), pickupTime: time },
      });
      setName('');
      setTime('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <div>
      <div className="form form--inline">
        <Field label={`New stop on ${route.name}`}>
          {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label={`Pick-up time at the new stop on ${route.name}`}>
          {(p) => <input {...p} type="time" value={time} onChange={(e) => setTime(e.target.value)} />}
        </Field>
      </div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <p className="form__actions">
        <Button variant="quiet" disabled={name.trim().length < 2 || !time} onClick={() => void add()}>
          {`Add stop to ${route.name}`}
        </Button>
      </p>
    </div>
  );
}

function AssignForm({
  yearId,
  routes,
  onSaved,
}: {
  yearId: string;
  routes: TransportRoute[];
  onSaved: () => void;
}) {
  const session = useApiSession();
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [student, setStudent] = useState<StudentListItem | null>(null);
  const [routeId, setRouteId] = useState('');
  const [stopId, setStopId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [found] = useApiResource<{ students: StudentListItem[] }>(
    submitted ? `/api/v1/academic-years/${yearId}/students?limit=8&q=${encodeURIComponent(submitted)}` : null,
  );
  const route = routes.find((r) => r.id === routeId);
  async function assign() {
    if (!student) return;
    setError(null);
    setDone(false);
    try {
      await apiRequest(session, `/api/v1/academic-years/${yearId}/transport-assignments`, {
        method: 'POST',
        body: { studentId: student.id, routeId, stopId },
      });
      setDone(true);
      setStudent(null);
      setSubmitted('');
      setQuery('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <section aria-labelledby="assign-h">
      <h2 id="assign-h">Put a child on a bus</h2>
      {done && <p role="status">Assigned.</p>}
      {!student ? (
        <>
          <div className="form form--inline">
            <Field label="Find the child">
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
                  <Button variant="quiet" onClick={() => setStudent(s)}>{`Choose ${s.displayName}`}</Button>
                </li>
              ))}
            </ul>
          )}
        </>
      ) : (
        <>
          <p>
            <strong>{student.displayName}</strong>{' '}
            <Button variant="quiet" onClick={() => setStudent(null)}>
              Choose someone else
            </Button>
          </p>
          <div className="form form--inline">
            <Field label="Route">
              {(p) => (
                <select
                  {...p}
                  value={routeId}
                  onChange={(e) => {
                    setRouteId(e.target.value);
                    setStopId('');
                  }}
                >
                  <option value="">Choose a route</option>
                  {routes.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.name}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Stop">
              {(p) => (
                <select {...p} value={stopId} onChange={(e) => setStopId(e.target.value)}>
                  <option value="">Choose a stop</option>
                  {route?.stops.map((s) => (
                    <option key={s.id} value={s.id}>
                      {short(s.pickupTime)} {s.name}
                    </option>
                  ))}
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
            <Button variant="primary" disabled={!routeId || !stopId} onClick={() => void assign()}>
              Assign
            </Button>
          </p>
        </>
      )}
    </section>
  );
}

function Roster({ route }: { route: TransportRoute }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <Button variant="quiet" onClick={() => setOpen(!open)}>
        {open ? `Hide ${route.name} roster` : `Open ${route.name} roster`}
      </Button>
      {open && <RosterList route={route} />}
    </div>
  );
}

function RosterList({ route }: { route: TransportRoute }) {
  const session = useApiSession();
  const [roster, reload] = useApiResource<RosterEntry[]>(`/api/v1/routes/${route.id}/roster`);
  const [trip, setTrip] = useState<'MORNING' | 'AFTERNOON'>(
    new Date().getUTCHours() < 6 ? 'MORNING' : 'AFTERNOON',
  );
  const [error, setError] = useState<string | null>(null);
  async function mark(studentId: string, event: 'BOARDED' | 'ALIGHTED') {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/routes/${route.id}/boarding`, {
        method: 'POST',
        body: { studentId, trip, event },
      });
      reload();
    } catch (e) {
      setError(message(e));
    }
  }
  if (roster.status === 'loading') return <LoadingState label="Loading roster" />;
  if (roster.status === 'error') return <ErrorState error={roster.error} onRetry={reload} />;
  return (
    <div>
      <Field label="Trip">
        {(p) => (
          <select {...p} value={trip} onChange={(e) => setTrip(e.target.value as 'MORNING' | 'AFTERNOON')}>
            <option value="MORNING">Morning</option>
            <option value="AFTERNOON">Afternoon</option>
          </select>
        )}
      </Field>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      {roster.data.length === 0 ? (
        <p className="muted">No children on this route.</p>
      ) : (
        <ul className="plain-list" aria-label={`${route.name} roster`}>
          {roster.data.map((s) => {
            const done = (e: 'BOARDED' | 'ALIGHTED') => s.today.some((t) => t.trip === trip && t.event === e);
            return (
              <li key={s.studentId}>
                {s.name} <span className="muted">{s.stop}</span>{' '}
                {done('BOARDED') ? (
                  <span>On the bus</span>
                ) : (
                  <Button
                    variant="quiet"
                    onClick={() => void mark(s.studentId, 'BOARDED')}
                  >{`${s.name} got on`}</Button>
                )}{' '}
                {done('ALIGHTED') ? (
                  <span>Got off</span>
                ) : (
                  <Button
                    variant="quiet"
                    onClick={() => void mark(s.studentId, 'ALIGHTED')}
                  >{`${s.name} got off`}</Button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
