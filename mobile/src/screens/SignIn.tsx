import { useState } from 'react';
import { Platform, TextInput, View } from 'react-native';
import { ApiError, type Client } from '../core/api';
import { ALLOW_DEV_TOKEN } from '../config';
import { Body, Button, Heading, Notice, colors } from '../ui';

const input = {
  borderWidth: 1,
  borderColor: colors.line,
  borderRadius: 8,
  padding: 12,
  fontSize: 20,
  backgroundColor: '#fff',
  color: colors.ink,
} as const;

/** Parents sign in with a code sent to the mobile number the school has on file (ADR 0009). */
export function SignIn({
  client,
  onDone,
  onStaff,
  onParent,
}: {
  client: Client;
  onDone: () => void;
  onParent?: () => Promise<void>;
  onStaff?: () => Promise<{ accessToken: string; refreshToken: string }>;
}) {
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(false);
  const [dev, setDev] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof ApiError ? e.detail : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={{ padding: 20 }}>
      <Heading>Sign in to Schoomano</Heading>
      {!sent ? (
        <>
          <Body>Enter the mobile number your school has for you. We will send a 6-digit code.</Body>
          <View style={{ height: 8 }} />
          <TextInput
            accessibilityLabel="Mobile number"
            keyboardType="phone-pad"
            autoComplete="tel"
            value={phone}
            onChangeText={setPhone}
            placeholder="98450 12345"
            style={input}
          />
          <Button
            label="Send code"
            busy={busy}
            disabled={phone.replace(/\D/g, '').length < 10}
            onPress={() =>
              void run(async () => {
                await client.requestOtp(phone);
                setSent(true);
              })
            }
          />
        </>
      ) : (
        <>
          <Body>Enter the code we sent to {phone}.</Body>
          <View style={{ height: 8 }} />
          <TextInput
            accessibilityLabel="6-digit code"
            keyboardType="number-pad"
            autoComplete="sms-otp"
            textContentType="oneTimeCode"
            maxLength={7}
            value={code}
            onChangeText={setCode}
            style={input}
          />
          <Button
            label="Sign in"
            busy={busy}
            disabled={code.replace(/\D/g, '').length !== 6}
            onPress={() =>
              void run(async () => {
                await client.verifyOtp(phone, code, `${Platform.OS} app`);
                await onParent?.();
                onDone();
              })
            }
          />
          <Button
            label="Use a different number"
            quiet
            onPress={() => {
              setSent(false);
              setCode('');
              setError(null);
            }}
          />
        </>
      )}
      {onStaff && (
        <>
          <Heading>School staff</Heading>
          <Button
            label="Sign in with your school account"
            quiet
            busy={busy}
            onPress={() =>
              void run(async () => {
                await client.useTokens(await onStaff());
                onDone();
              })
            }
          />
        </>
      )}
      {error && <Notice kind="error">{error}</Notice>}
      {ALLOW_DEV_TOKEN && (
        <>
          <Heading>Staff (development builds)</Heading>
          <TextInput
            accessibilityLabel="Development token"
            autoCapitalize="none"
            value={dev}
            onChangeText={setDev}
            placeholder="dev:dev-ravi"
            style={input}
          />
          <Button
            label="Use token"
            quiet
            disabled={!dev.startsWith('dev:')}
            onPress={() =>
              void run(async () => {
                await client.useTokens({ accessToken: dev, refreshToken: '' });
                onDone();
              })
            }
          />
        </>
      )}
    </View>
  );
}
