/**
 * Translation-ready strings. English is the source; other locales fall back key-by-key.
 * Hindi entries need review by a native speaker before any pilot (tracked in IMPLEMENTATION_STATUS).
 */
export const en = {
  'app.name': 'Schoomano',
  'nav.today': 'Today',
  'nav.campuses': 'Campuses',
  'nav.students': 'Students',
  'nav.academic': 'Academic structure',
  'nav.notices': 'Notices',
  'nav.admissions': 'Admissions',
  'nav.learning': 'Syllabus and homework',
  'nav.studentSupport': 'Student support',
  'nav.safeguarding': 'Safeguarding',
  'nav.health': 'Health',
  'nav.purchasing': 'Purchasing',
  'nav.staffLeave': 'Staff and leave',
  'nav.fees': 'Fees',
  'nav.attendance': 'Attendance',
  'nav.assessment': 'Marks and reports',
  'nav.timetable': 'Timetable',
  'nav.imports': 'Imports',
  'nav.support': 'Support and audit',
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
