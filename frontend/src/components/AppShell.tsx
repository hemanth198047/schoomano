import { useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useI18n } from '../i18n/context';
import type { MessageKey } from '../i18n/messages';
import { Button } from './Button';

export interface ScopeInfo {
  groupName: string;
  campuses: { id: string; name: string }[];
  selectedCampusId: string | null;
  onSelectCampus: (id: string | null) => void;
  personName: string;
}

const nav: { to: string; label: MessageKey }[] = [
  { to: '/', label: 'nav.today' },
  { to: '/students', label: 'nav.students' },
  { to: '/campuses', label: 'nav.campuses' },
  { to: '/academic', label: 'nav.academic' },
  { to: '/imports', label: 'nav.imports' },
  { to: '/settings', label: 'nav.configuration' },
  { to: '/design-system', label: 'nav.designSystem' },
];

/**
 * Responsive shell: skip link, ink navigation rail, and the marigold scope bar that always states
 * where the person is acting (group, campus). On narrow screens the rail collapses behind a menu button.
 */
export function AppShell({
  scope,
  onSignOut,
  children,
}: {
  scope: ScopeInfo;
  onSignOut: () => void;
  children: ReactNode;
}) {
  const { t } = useI18n();
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        {t('skip.toContent')}
      </a>

      <div className="scope-bar" role="region" aria-label={t('scope.label')}>
        <button
          type="button"
          className="scope-bar__menu"
          aria-expanded={menuOpen}
          aria-controls="primary-nav"
          onClick={() => setMenuOpen((o) => !o)}
        >
          Menu
        </button>
        <div className="scope-bar__path">
          <span className="scope-bar__label">{t('scope.label')}</span>
          <span className="scope-bar__group">{scope.groupName}</span>
          {/* Campus is shown only to people who act on campuses; guardians act on their children. */}
          {scope.campuses.length > 0 && (
            <span aria-hidden="true" className="scope-bar__sep">
              /
            </span>
          )}
          {scope.campuses.length === 1 && (
            <span className="scope-bar__campus">{scope.campuses[0]!.name}</span>
          )}
          {scope.campuses.length > 1 && (
            <label className="scope-bar__switch">
              <span className="visually-hidden">{t('scope.campus')}</span>
              <select
                value={scope.selectedCampusId ?? ''}
                onChange={(e) => scope.onSelectCampus(e.target.value || null)}
              >
                <option value="">{t('scope.allCampuses')}</option>
                {scope.campuses.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>
        <div className="scope-bar__person">
          <span>{scope.personName}</span>
          <Button variant="quiet" onClick={onSignOut}>
            {t('auth.signOut')}
          </Button>
        </div>
      </div>

      <nav id="primary-nav" className={menuOpen ? 'rail rail--open' : 'rail'} aria-label={t('nav.main')}>
        <p className="rail__brand">{t('app.name')}</p>
        <ul className="rail__list">
          {nav.map((item) => (
            <li key={item.to}>
              <NavLink
                to={item.to}
                end={item.to === '/'}
                className={({ isActive }) => (isActive ? 'rail__link rail__link--active' : 'rail__link')}
                onClick={() => setMenuOpen(false)}
              >
                {t(item.label)}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      <main id="main" className="main" tabIndex={-1}>
        {children}
      </main>
    </div>
  );
}
