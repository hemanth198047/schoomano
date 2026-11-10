import type { ReactNode } from 'react';
import type { ApiError } from '../api/client';
import { Button } from './Button';

export function LoadingState({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="state state--loading" role="status" aria-live="polite">
      <span className="state__spinner" aria-hidden="true" />
      <span>{label}…</span>
    </div>
  );
}

const friendly: Record<string, string> = {
  network_unreachable: 'Schoomano could not reach the server.',
  unauthenticated: 'Your session has ended.',
  forbidden: 'You do not have access to this.',
  not_found: 'This record does not exist or you cannot access it.',
  conflict: 'This change conflicts with existing data.',
  invalid_value: 'This value is not valid.',
  term_rejected: 'Check the term dates.',
  wrong_campus: 'That programme belongs to another campus.',
  outside_year: 'Those dates fall outside the academic year.',
};

export function ErrorState({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  return (
    <div className="state state--error" role="alert">
      <p className="state__title">{friendly[error.code] ?? 'This could not be loaded.'}</p>
      <p className="state__body">{error.detail}</p>
      {error.problems.length > 0 && (
        <ul className="state__problems">
          {error.problems.map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
      )}
      {error.correlationId && (
        <p className="state__meta">
          Reference for support: <code>{error.correlationId}</code>
        </p>
      )}
      {onRetry && (
        <Button variant="secondary" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}

export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="state state--empty">
      <p className="state__title">{title}</p>
      {children && <div className="state__body">{children}</div>}
      {action}
    </div>
  );
}
