import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { Campus, FacilityRoom, MaintenanceIssue, Me, RoomBooking } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { formatDateTime, localInputToIsoIst } from '../lib/format';

const has = (me: Me, ...keys: string[]) => me.roles.some((r) => keys.includes(r.roleKey));
export const canManageFacilities = (me: Me) => has(me, 'campus_admin', 'group_admin');
export const canSeeFacilities = (me: Me) => canManageFacilities(me) || has(me, 'teacher', 'principal');

const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');
const PRIORITIES = { URGENT: 'Urgent', NORMAL: 'Normal', LOW: 'Low' } as const;
const STATE = { OPEN: 'Open', IN_PROGRESS: 'In progress', RESOLVED: 'Resolved' } as const;

export function FacilitiesPage({
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
      <PageHeader
        title="Facilities"
        lead="Book a room for a class or event, and report anything that needs fixing."
      />
      {!canSeeFacilities(me) ? (
        <EmptyState title="Not available">
          <p>Facilities are used by teachers, the principal and the campus office.</p>
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
  const [rooms] = useApiResource<FacilityRoom[]>(`/api/v1/campuses/${campusId}/rooms`);
  const roomList = rooms.status === 'ready' ? rooms.data.filter((r) => r.status === 'ACTIVE') : [];
  return (
    <>
      <Bookings me={me} campusId={campusId} rooms={roomList} />
      <Issues me={me} campusId={campusId} rooms={roomList} />
    </>
  );
}

function Bookings({ me, campusId, rooms }: { me: Me; campusId: string; rooms: FacilityRoom[] }) {
  const session = useApiSession();
  const [list, reload] = useApiResource<RoomBooking[]>(`/api/v1/campuses/${campusId}/bookings`);
  const [roomId, setRoomId] = useState('');
  const [purpose, setPurpose] = useState('');
  const [from, setFrom] = useState('');
  const [until, setUntil] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function book() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/rooms/${roomId}/bookings`, {
        method: 'POST',
        body: {
          purpose: purpose.trim(),
          startsAt: localInputToIsoIst(from),
          endsAt: localInputToIsoIst(until),
        },
      });
      setPurpose('');
      setFrom('');
      setUntil('');
      reload();
    } catch (e) {
      setError(message(e));
    }
  }
  async function cancel(id: string) {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/bookings/${id}/cancel`, { method: 'POST' });
      reload();
    } catch (e) {
      setError(message(e));
    }
  }

  return (
    <section aria-labelledby="book-h">
      <h2 id="book-h">Room bookings, next two weeks</h2>
      {list.status === 'loading' && <LoadingState label="Loading bookings" />}
      {list.status === 'error' && <ErrorState error={list.error} onRetry={reload} />}
      {list.status === 'ready' &&
        (list.data.length === 0 ? (
          <EmptyState title="Nothing booked" />
        ) : (
          <ul className="plain-list">
            {list.data.map((b) => (
              <li key={b.id}>
                <strong>{b.roomName}</strong>{' '}
                <span className="muted">
                  {formatDateTime(b.startsAt)} to {formatDateTime(b.endsAt)}, {b.purpose}, {b.bookedBy}
                </span>{' '}
                {(b.mine || canManageFacilities(me)) && (
                  <Button variant="quiet" onClick={() => void cancel(b.id)}>
                    {`Cancel ${b.roomName} booking for ${b.purpose}`}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        ))}
      <h3>Book a room</h3>
      <div className="form form--inline">
        <Field label="Room">
          {(p) => (
            <select {...p} value={roomId} onChange={(e) => setRoomId(e.target.value)}>
              <option value="">Choose a room</option>
              {rooms.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="What for">
          {(p) => <input {...p} value={purpose} onChange={(e) => setPurpose(e.target.value)} />}
        </Field>
        <Field label="From">
          {(p) => (
            <input {...p} type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
          )}
        </Field>
        <Field label="Until">
          {(p) => (
            <input {...p} type="datetime-local" value={until} onChange={(e) => setUntil(e.target.value)} />
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
          disabled={!roomId || purpose.trim().length < 2 || !from || !until}
          onClick={() => void book()}
        >
          Book room
        </Button>
      </p>
    </section>
  );
}

function Issues({ me, campusId, rooms }: { me: Me; campusId: string; rooms: FacilityRoom[] }) {
  const session = useApiSession();
  const [list, reload] = useApiResource<MaintenanceIssue[]>(`/api/v1/campuses/${campusId}/maintenance`);
  const [title, setTitle] = useState('');
  const [details, setDetails] = useState('');
  const [roomId, setRoomId] = useState('');
  const [priority, setPriority] = useState<MaintenanceIssue['priority']>('NORMAL');
  const [noteFor, setNoteFor] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function run(path: string, body?: unknown) {
    setError(null);
    try {
      await apiRequest(session, path, { method: 'POST', body });
      reload();
      return true;
    } catch (e) {
      setError(message(e));
      return false;
    }
  }
  async function report() {
    const ok = await run(`/api/v1/campuses/${campusId}/maintenance`, {
      title: title.trim(),
      description: details.trim() || undefined,
      roomId: roomId || undefined,
      priority,
    });
    if (ok) {
      setTitle('');
      setDetails('');
      setRoomId('');
    }
  }

  return (
    <section aria-labelledby="fix-h">
      <h2 id="fix-h">Things to fix</h2>
      {list.status === 'loading' && <LoadingState label="Loading requests" />}
      {list.status === 'error' && <ErrorState error={list.error} onRetry={reload} />}
      {list.status === 'ready' &&
        (list.data.length === 0 ? (
          <EmptyState title="Nothing reported" />
        ) : (
          <ul className="plain-list">
            {list.data.map((i) => (
              <li key={i.id}>
                <strong>{i.title}</strong>{' '}
                <span className="muted">
                  {PRIORITIES[i.priority]}, {STATE[i.status]}
                  {i.roomName ? `, ${i.roomName}` : ''}, reported by {i.reportedBy} on{' '}
                  {formatDateTime(i.reportedAt)}
                  {i.resolution ? `. Done: ${i.resolution}` : ''}
                </span>{' '}
                {canManageFacilities(me) && i.status === 'OPEN' && (
                  <Button variant="quiet" onClick={() => void run(`/api/v1/maintenance/${i.id}/start`)}>
                    {`Start work on ${i.title}`}
                  </Button>
                )}
                {canManageFacilities(me) && i.status === 'IN_PROGRESS' && (
                  <Button variant="quiet" onClick={() => setNoteFor(noteFor === i.id ? null : i.id)}>
                    {`Resolve ${i.title}`}
                  </Button>
                )}
                {noteFor === i.id && (
                  <div className="form form--inline">
                    <Field label={`What was done for ${i.title}`}>
                      {(p) => <input {...p} value={note} onChange={(e) => setNote(e.target.value)} />}
                    </Field>
                    <Button
                      variant="primary"
                      disabled={note.trim().length < 2}
                      onClick={() => {
                        void run(`/api/v1/maintenance/${i.id}/resolve`, { resolution: note.trim() }).then(
                          (ok) => {
                            if (ok) {
                              setNoteFor(null);
                              setNote('');
                            }
                          },
                        );
                      }}
                    >
                      Save resolution
                    </Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        ))}
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <h3>Report a problem</h3>
      <div className="form form--inline">
        <Field label="What is wrong">
          {(p) => <input {...p} value={title} onChange={(e) => setTitle(e.target.value)} />}
        </Field>
        <Field label="More detail" hint="Optional">
          {(p) => <input {...p} value={details} onChange={(e) => setDetails(e.target.value)} />}
        </Field>
        <Field label="Where" hint="Optional">
          {(p) => (
            <select {...p} value={roomId} onChange={(e) => setRoomId(e.target.value)}>
              <option value="">Not in a room</option>
              {rooms.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="How urgent">
          {(p) => (
            <select
              {...p}
              value={priority}
              onChange={(e) => setPriority(e.target.value as MaintenanceIssue['priority'])}
            >
              {Object.entries(PRIORITIES).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      <p className="form__actions">
        <Button variant="primary" disabled={title.trim().length < 3} onClick={() => void report()}>
          Report problem
        </Button>
      </p>
    </section>
  );
}
