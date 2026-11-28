import { useCallback, useEffect, useState } from 'react';
import { ScrollView, TextInput, View } from 'react-native';
import {
  decide,
  decisionProblem,
  loadPending,
  approverCampuses,
  type PendingRequest,
} from '../core/approvals';
import { ApiError, type Client } from '../core/api';
import type { Me } from '../core/types';
import { Body, Button, Card, Heading, Notice, colors } from '../ui';

const oops = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong.');

/** The approvals waiting for a principal, campus office or school administrator, to approve or reject from the phone. */
export function Approvals({ client, me, onSignOut }: { client: Client; me: Me; onSignOut: () => void }) {
  const [rows, setRows] = useState<PendingRequest[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setRows(await loadPending(client, await approverCampuses(client, me)));
    } catch (e) {
      setError(oops(e));
    }
  }, [client, me]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <ScrollView contentContainerStyle={{ padding: 16 }}>
      <Heading>{`Hello, ${me.displayName}`}</Heading>
      <Body muted>Requests waiting for your approval</Body>
      {error && <Notice kind="error">{error}</Notice>}
      {done && <Notice kind="info">{done}</Notice>}
      {rows === null && !error && <Body muted>Loading…</Body>}
      {rows?.length === 0 && <Body>Nothing is waiting for you.</Body>}
      {rows?.map((r) => (
        <Request
          key={r.id}
          client={client}
          row={r}
          onDecided={(text) => {
            setDone(text);
            void load();
          }}
        />
      ))}
      <View style={{ height: 12 }} />
      <Button label="Refresh" quiet onPress={() => void load()} />
      <Button label="Sign out" quiet onPress={onSignOut} />
    </ScrollView>
  );
}

function Request({
  client,
  row,
  onDecided,
}: {
  client: Client;
  row: PendingRequest;
  onDecided: (text: string) => void;
}) {
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function answer(decision: 'APPROVED' | 'REJECTED') {
    const problem = decisionProblem(decision, note);
    if (problem) return setError(problem);
    setBusy(true);
    setError(null);
    try {
      await decide(client, row.id, decision, note);
      onDecided(`${decision === 'APPROVED' ? 'Approved' : 'Rejected'}: ${row.subject}`);
    } catch (e) {
      setError(oops(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <Heading>{row.subject}</Heading>
      <Body>{`${row.requester} · ${row.campusName}`}</Body>
      <Body muted>{`${row.chainName}${row.waitingFor ? ` · waiting for ${row.waitingFor}` : ''}`}</Body>
      {row.details ? <Body>{row.details}</Body> : null}
      <TextInput
        accessibilityLabel={`Note for ${row.subject}`}
        placeholder="Note (needed to reject)"
        value={note}
        onChangeText={setNote}
        multiline
        style={{
          borderWidth: 1,
          borderColor: colors.line,
          borderRadius: 8,
          padding: 12,
          marginVertical: 8,
          backgroundColor: '#fff',
          color: colors.ink,
        }}
      />
      {error && <Notice kind="error">{error}</Notice>}
      <Button label={`Approve ${row.subject}`} busy={busy} onPress={() => void answer('APPROVED')} />
      <Button label={`Reject ${row.subject}`} quiet busy={busy} onPress={() => void answer('REJECTED')} />
    </Card>
  );
}
