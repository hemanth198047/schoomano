import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { SafeAreaView, View } from 'react-native';
import { ApiError, createClient } from './src/core/api';
import { forgetSavedLists, loadMe } from './src/core/cached';
import { modeOf } from './src/core/money';
import type { Me } from './src/core/types';
import { API_BASE_URL } from './src/config';
import { deviceStore, secureTokenStore } from './src/platform/stores';
import { ParentHome } from './src/screens/ParentHome';
import { SignIn } from './src/screens/SignIn';
import { TeacherAttendance } from './src/screens/TeacherAttendance';
import { Body, Button, Heading, Notice, colors } from './src/ui';

type Phase =
  | { name: 'starting' }
  | { name: 'signedOut' }
  | { name: 'ready'; me: Me }
  | { name: 'failed'; message: string };

/** Which screens appear is decided by what the server says the person is, not by a choice made in the app. */
export default function App() {
  const [phase, setPhase] = useState<Phase>({ name: 'starting' });
  const client = useMemo(
    () =>
      createClient({
        baseUrl: API_BASE_URL,
        tokens: secureTokenStore,
        onSignedOut: () => setPhase({ name: 'signedOut' }),
      }),
    [],
  );

  const start = useCallback(async () => {
    const t = await secureTokenStore.load();
    if (!t) return setPhase({ name: 'signedOut' });
    try {
      // Offline at launch: the saved copy lets a teacher mark from what the phone already holds.
      const r = await loadMe(client, deviceStore);
      if (r) setPhase({ name: 'ready', me: r.value });
      else setPhase({ name: 'failed', message: 'The server could not be reached. Check your connection.' });
    } catch (e) {
      if (e instanceof ApiError && e.status === 0) setPhase({ name: 'failed', message: e.detail });
      else setPhase({ name: 'signedOut' });
    }
  }, [client]);

  useEffect(() => {
    void start();
  }, [start]);

  const signOut = () =>
    void client
      .signOut()
      .then(() => forgetSavedLists(deviceStore, ['me']))
      .then(() => setPhase({ name: 'signedOut' }));

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.paper }}>
      <StatusBar style="dark" />
      {phase.name === 'starting' && (
        <View style={{ padding: 20 }}>
          <Body muted>Opening Schoomano…</Body>
        </View>
      )}
      {phase.name === 'signedOut' && <SignIn client={client} onDone={() => void start()} />}
      {phase.name === 'failed' && (
        <View style={{ padding: 20 }}>
          <Heading>No connection</Heading>
          <Notice kind="error">{phase.message}</Notice>
          <Button label="Try again" onPress={() => void start()} />
        </View>
      )}
      {phase.name === 'ready' && modeOf(phase.me) === 'teacher' && (
        <TeacherAttendance client={client} me={phase.me} store={deviceStore} onSignOut={signOut} />
      )}
      {phase.name === 'ready' && modeOf(phase.me) === 'parent' && (
        <ParentHome client={client} me={phase.me} onSignOut={signOut} />
      )}
      {phase.name === 'ready' && modeOf(phase.me) === 'none' && (
        <View style={{ padding: 20 }}>
          <Heading>Nothing here yet</Heading>
          <Body>Your account is not linked to a child or a class. Please ask the school office.</Body>
          <Button label="Sign out" quiet onPress={signOut} />
        </View>
      )}
    </SafeAreaView>
  );
}
