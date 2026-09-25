import { describe, expect, it } from 'vitest';
import { formatDateTime, formatPaise, localInputToIsoIst } from './format';

describe('India formatting', () => {
  it('formats paise as rupees with lakh grouping', () => {
    expect(formatPaise(123456789)).toBe('₹12,34,567.89');
    expect(formatPaise(0)).toBe('₹0.00');
  });

  it('refuses fractional paise so floats never become money', () => {
    expect(() => formatPaise(10.5)).toThrow();
  });

  it('shows times in India Standard Time regardless of the browser zone', () => {
    expect(formatDateTime('2026-09-25T03:30:00Z')).toContain('9:00');
  });

  it('treats form input as IST wall-clock time', () => {
    expect(localInputToIsoIst('2026-12-01T08:00')).toBe('2026-12-01T08:00:00+05:30');
    expect(() => localInputToIsoIst('01/12/2026')).toThrow();
  });
});
