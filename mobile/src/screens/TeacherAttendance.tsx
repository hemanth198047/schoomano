import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AppState, Pressable, ScrollView, Text, View } from 'react-native';
import { ApiError, type Client } from '../core/api';
import { loadSections } from '../core/cached';
import { createQueue, type PendingBatch, type QueueState } from '../core/offlineQueue';
import { NEXT_STATUS, buildBatch, loadRoster } from '../core/roster';
import type { KeyValueStore } from '../core/storage';
import type { AttendanceStatus, BatchResult, Me, Roster } from '../core/types';
import { Body, Button, Card, Heading, Notice, colors } from '../ui';

const LABEL: Record<AttendanceStatus, string> = {
  PRESENT: 'Present',
  ABSENT: 'Absent',
  LATE: 'Late',
  LEAVE: 'Leave',
};
const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
const oops = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong.');

/**
 * Mark attendance with or without a network. Marks are written to the phone first and sent when possible; a retry
 * carries the same key, so nothing is counted twice, and an office change made meanwhile becomes a visible conflict.
 */
export function TeacherAttendance({
  client,
  me,
  store,
  onSignOut,
}: {
  client: Client;
  me: Me;
  store: KeyValueStore;
  onSignOut: () => void;
}) {
  const queue = useMemo(() => createQueue(store), [store]);
  const campusId = me.roles.find((r) => r.roleKey === 'teacher')?.scopeId ?? null;
  const [sections, setSections] = useState<{ id: string; label: string }[]>([]);
  const [sectionId, setSectionId] = useState<string | null>(null);
  const [roster, setRoster] = useState<Roster | null>(null);
  const [fromCache, setFromCache] = useState(false);
  const [chosen, setChosen] = useState<Record<string, AttendanceStatus>>({});
  const [state, setState] = useState<QueueState>({ pending: [], rejected: [] });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const syncing = useRef(false);

  useEffect(() => {
    void queue.snapshot().then(setState);
  }, [queue]);

  useEffect(() => {
    if (!campusId) return;
    loadSections(client, store, campusId)
      .then((s) => s && setSections(s))
      .catch((e: unknown) => setError(oops(e)));
  }, [client, store, campusId]);

  const send = useCallback(
    (b: PendingBatch) =>
      client.request<BatchResult>(`/api/v1/sections/${b.sectionId}/attendance/batches`, {
        method: 'POST',
        json: { date: b.date, source: 'DEVICE', marks: b.marks },
        headers: { 'Idempotency-Key': b.key },
      }),
    [client],
  );

  const sync = useCallback(async () => {
    if (syncing.current) return;
    syncing.current = true;
    setBusy(true);
    try {
      const out = await queue.flush(send);
      setState(out.state);
      const conflicts = out.sent.reduce((n, s) => n + s.result.conflicts, 0);
      if (out.sent.length > 0)
        setMessage(
          conflicts > 0
            ? `Sent. ${conflicts} mark${conflicts === 1 ? ' was' : 's were'} changed by the office meanwhile and left for them to settle.`
            : 'Attendance sent.',
        );
    } finally {
      syncing.current = false;
      setBusy(false);
    }
  }, [queue, send]);

  // Try again whenever the app comes back to the front.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => s === 'active' && void sync());
    void sync();
    return () => sub.remove();
  }, [sync]);

  async function open(id: string) {
    setSectionId(id);
    setRoster(null);
    setChosen({});
    setError(null);
    setMessage(null);
    try {
      const r = await loadRoster(client, store, id, today());
      if (!r) setError('No class list saved on this phone yet. Connect once to download it.');
      else {
        setRoster(r.roster);
        setFromCache(r.fromCache);
      }
    } catch (e) {
      setError(oops(e));
    }
  }

  async function save() {
    if (!roster) return;
    const batch = buildBatch(roster, chosen, new Date());
    if (!batch) {
      setMessage('Nothing has changed.');
      return;
    }
    await queue.enqueue(batch);
    setState(await queue.snapshot());
    setChosen({});
    setMessage('Saved on this phone.');
    await sync();
  }

  const counts = state.pending.reduce((n, b) => n + b.marks.length, 0);

  return (
    <ScrollView contentContainerStyle={{ padding: 16 }}>
      <Heading>Attendance</Heading>
      {state.pending.length > 0 && (
        <Notice kind="info">{`${counts} mark${counts === 1 ? '' : 's'} waiting to be sent. They are safe on this phone.`}</Notice>
      )}
      {state.rejected.map((r) => (
        <Notice
          key={r.batch.key}
          kind="error"
        >{`${r.batch.sectionLabel}, ${r.batch.date}: not accepted. ${r.reason}`}</Notice>
      ))}
      {state.rejected.length > 0 && (
        <Button
          label="Dismiss"
          quiet
          onPress={() =>
            void Promise.all(state.rejected.map((r) => queue.dismissRejected(r.batch.key)))
              .then(() => queue.snapshot())
              .then(setState)
          }
        />
      )}
      {state.pending.length > 0 && <Button label="Send now" busy={busy} onPress={() => void sync()} />}

      {!sectionId && (
        <>
          <Body>Choose a class</Body>
          {sections.map((s) => (
            <Button key={s.id} label={s.label} quiet onPress={() => void open(s.id)} />
          ))}
          {sections.length === 0 && <Body muted>No classes yet. Connect to load them.</Body>}
        </>
      )}

      {sectionId && (
        <>
          <Button
            label="Choose another class"
            quiet
            onPress={() => {
              setSectionId(null);
              setRoster(null);
            }}
          />
          {roster && (
            <Card>
              <Heading>{`${roster.sectionLabel}, ${roster.date}`}</Heading>
              {fromCache && (
                <Notice kind="info">Showing the list saved on this phone. You are offline.</Notice>
              )}
              {roster.openConflicts > 0 && (
                <Body muted>{`${roster.openConflicts} mark(s) are waiting for the office.`}</Body>
              )}
              {roster.students.map((s) => {
                const status = chosen[s.studentId] ?? s.status;
                return (
                  <Pressable
                    key={s.studentId}
                    accessibilityRole="button"
                    accessibilityLabel={`${s.name}, ${status ? LABEL[status] : 'not marked'}`}
                    accessibilityHint="Double tap to change"
                    onPress={() =>
                      setChosen((c) => ({
                        ...c,
                        [s.studentId]:
                          NEXT_STATUS[(c[s.studentId] ?? s.status ?? 'NONE') as AttendanceStatus | 'NONE'],
                      }))
                    }
                    style={{
                      minHeight: 52,
                      flexDirection: 'row',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      borderBottomWidth: 1,
                      borderColor: colors.line,
                    }}
                  >
                    <Text style={{ fontSize: 18, color: colors.ink, flex: 1 }}>{s.name}</Text>
                    <Text
                      style={{
                        fontSize: 17,
                        fontWeight: '700',
                        color: status === 'ABSENT' ? colors.bad : status ? colors.ok : '#566070',
                      }}
                    >
                      {status ? LABEL[status] : 'Not marked'}
                    </Text>
                  </Pressable>
                );
              })}
              <Button
                label="Save attendance"
                disabled={Object.keys(chosen).length === 0}
                onPress={() => void save()}
              />
            </Card>
          )}
        </>
      )}
      {message && <Notice kind="info">{message}</Notice>}
      {error && <Notice kind="error">{error}</Notice>}
      <View style={{ height: 16 }} />
      <Button label="Sign out" quiet onPress={onSignOut} />
    </ScrollView>
  );
}
