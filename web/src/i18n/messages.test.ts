import { describe, expect, it } from 'vitest';
import { translate } from './messages';

describe('translation catalogue', () => {
  it('uses the locale string when present', () => {
    expect(translate('hi-IN', 'nav.today')).toBe('आज');
  });
  it('falls back to English key by key', () => {
    expect(translate('hi-IN', 'nav.configuration')).toBe('Settings and rules');
  });
});
