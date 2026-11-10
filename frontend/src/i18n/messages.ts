/**
 * Translation-ready strings. English is the source; other locales fall back key-by-key.
 * Hindi entries need review by a native speaker before any pilot (tracked in IMPLEMENTATION_STATUS).
 */
export const en = {
  'app.name': 'Schoomano',
  'nav.today': 'Today',
  'nav.campuses': 'Campuses',
  'nav.academic': 'Academic structure',
  'nav.configuration': 'Settings and rules',
  'nav.designSystem': 'Design system',
  'nav.main': 'Main',
  'scope.label': 'You are working in',
  'scope.group': 'School group',
  'scope.campus': 'Campus',
  'scope.allCampuses': 'All campuses',
  'skip.toContent': 'Skip to main content',
  'state.loading': 'Loading',
  'state.retry': 'Try again',
  'state.reference': 'Reference',
  'auth.signOut': 'Sign out',
} as const;

export type MessageKey = keyof typeof en;
export type Locale = 'en-IN' | 'hi-IN';

const hi: Partial<Record<MessageKey, string>> = {
  'nav.today': 'आज',
  'nav.campuses': 'परिसर',
  'skip.toContent': 'मुख्य सामग्री पर जाएँ',
};

const catalogs: Record<Locale, Partial<Record<MessageKey, string>>> = { 'en-IN': en, 'hi-IN': hi };

export function translate(locale: Locale, key: MessageKey): string {
  return catalogs[locale][key] ?? en[key];
}
