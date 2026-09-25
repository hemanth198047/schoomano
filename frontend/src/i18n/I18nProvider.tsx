import { useMemo, useState, type ReactNode } from 'react';
import { translate, type Locale, type MessageKey } from './messages';
import { I18nContext } from './context';

export function I18nProvider({ children, initial = 'en-IN' }: { children: ReactNode; initial?: Locale }) {
  const [locale, setLocale] = useState<Locale>(initial);
  const value = useMemo(() => ({ locale, setLocale, t: (k: MessageKey) => translate(locale, k) }), [locale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}
