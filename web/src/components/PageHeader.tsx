import { useEffect, useRef, type ReactNode } from 'react';

/** Sets document title and moves focus to the heading on navigation (screen-reader route announcement). */
export function PageHeader({ title, lead, actions }: { title: string; lead?: string; actions?: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    document.title = `${title} · Schoomano`;
    ref.current?.focus();
  }, [title]);
  return (
    <header className="page-header">
      <div>
        <h1 ref={ref} tabIndex={-1} className="page-header__title">
          {title}
        </h1>
        {lead && <p className="page-header__lead">{lead}</p>}
      </div>
      {actions && <div className="page-header__actions">{actions}</div>}
    </header>
  );
}
