type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

/** Colour is never the only signal: the badge always carries its text. */
export function StatusBadge({ tone, children }: { tone: Tone; children: string }) {
  return <span className={`badge badge--${tone}`}>{children}</span>;
}
