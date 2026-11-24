import type { ReactNode } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';

export const colors = {
  ink: '#1B2430',
  paper: '#FBF8F2',
  brand: '#0F5C5C',
  warn: '#8A4B00',
  bad: '#9B1C1C',
  ok: '#1E6B3A',
  line: '#D8D2C4',
};

/** Touch targets are at least 48 dp, text is large, and colour is never the only signal. */
export function Button({
  label,
  onPress,
  busy,
  disabled,
  quiet,
  hint,
}: {
  label: string;
  onPress: () => void;
  busy?: boolean;
  disabled?: boolean;
  quiet?: boolean;
  hint?: string;
}) {
  const off = disabled || busy;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled: !!off, busy: !!busy }}
      onPress={off ? undefined : onPress}
      style={[styles.button, quiet ? styles.quiet : styles.primary, off && styles.off]}
    >
      {busy ? (
        <ActivityIndicator color={quiet ? colors.brand : '#fff'} />
      ) : (
        <Text style={[styles.buttonText, quiet && { color: colors.brand }]}>{label}</Text>
      )}
    </Pressable>
  );
}

export function Heading({ children }: { children: string }) {
  return (
    <Text accessibilityRole="header" style={styles.h}>
      {children}
    </Text>
  );
}

export function Body({
  children,
  muted,
  strong,
}: {
  children: ReactNode;
  muted?: boolean;
  strong?: boolean;
}) {
  return (
    <Text style={[styles.body, muted && { color: '#566070' }, strong && { fontWeight: '700' }]}>
      {children}
    </Text>
  );
}

export function Notice({ kind, children }: { kind: 'error' | 'info'; children: string }) {
  return (
    <View
      accessibilityRole={kind === 'error' ? 'alert' : undefined}
      style={[styles.notice, kind === 'error' ? { borderColor: colors.bad } : { borderColor: colors.brand }]}
    >
      <Text style={[styles.body, kind === 'error' && { color: colors.bad }]}>{children}</Text>
    </View>
  );
}

export const Card = ({ children }: { children: ReactNode }) => <View style={styles.card}>{children}</View>;

const styles = StyleSheet.create({
  button: {
    minHeight: 48,
    paddingHorizontal: 16,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    marginVertical: 6,
  },
  primary: { backgroundColor: colors.brand },
  quiet: { borderWidth: 1, borderColor: colors.brand, backgroundColor: 'transparent' },
  off: { opacity: 0.5 },
  buttonText: { color: '#fff', fontSize: 17, fontWeight: '600' },
  h: { fontSize: 22, fontWeight: '700', color: colors.ink, marginTop: 16, marginBottom: 8 },
  body: { fontSize: 17, color: colors.ink, lineHeight: 24 },
  notice: { borderWidth: 1, borderRadius: 8, padding: 12, marginVertical: 8, backgroundColor: '#fff' },
  card: {
    backgroundColor: '#fff',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: colors.line,
    padding: 12,
    marginVertical: 6,
  },
});
