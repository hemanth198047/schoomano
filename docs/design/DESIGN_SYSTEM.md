# Schoomano design system

Source of truth: `frontend/design-tokens/tokens.json` → `npm run tokens` → CSS variables (web) and a typed TS object (mobile). CI fails if the TS output is stale or if any stylesheet references an undefined token.

## Direction
Schools are busy, mixed-literacy, mixed-device environments. The interface is calm, dense where staff need density, and always explicit about **where you are acting**.

- **Palette**: school-ink blue `#1D2B53` (navigation, primary actions), paper `#F7F9FC`, register-rule grey for table lines, and status colours (leaf green, amber, brick red, info blue) each paired with a tinted background.
- **The one bold element**: the marigold (`#F2A900`) scope bar across the top, stating school group and campus and holding the campus switcher. In a multi-campus system acting in the wrong campus is a real risk, so the scope is always visible. Marigold is used nowhere else except the active-navigation marker.
- **Type**: Atkinson Hyperlegible (designed for low-vision readers; self-hosted, no third-party requests), Noto fallbacks for Devanagari, Kannada and Tamil. Tabular figures in tables.
- **Shape**: radius by hierarchy (4 px controls, 6 px panels, 10 px dialogs), not one value everywhere.

## Accessibility rules (WCAG 2.2 AA)
- Every text/background token pair ≥ 4.5:1; control borders ≥ 3:1 (checked; `ruleStrong` was darkened to pass).
- 44 px minimum touch targets; visible 3 px focus ring; skip link; one `h1` per page, focused on navigation.
- Status is never colour alone: badges always carry text.
- Form fields wire label, hint and error through `aria-describedby`; errors state how to fix the problem.
- Tables have captions, sortable headers expose `aria-sort`, wide tables scroll inside a labelled region.
- `prefers-reduced-motion` disables animation.
- Automated axe scans run in the unit tests; a control test proves the scanner catches violations.

## Components (frontend/src/components)
`AppShell` (skip link, ink rail, scope bar, collapsible menu under 52 rem), `PageHeader`, `Button` (primary, secondary, danger, quiet; busy state), `Field`, `DataTable`, `StatusBadge`, `LoadingState`, `EmptyState`, `ErrorState` (shows the support reference/correlation id). A live gallery is at `/design-system` in the web app.

## Copy
Plain English at roughly a grade-8 reading level; say what happened and what to do next; no blame; "campus", "class", "guardian" consistently. All strings go through the i18n catalogue.
