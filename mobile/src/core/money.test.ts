import { describe, expect, it } from 'vitest';
import { formatPaise, modeOf } from './money';

describe('money and mode', () => {
  it('groups digits the Indian way', () => {
    expect(formatPaise(123456789)).toBe('₹12,34,567.89');
    expect(formatPaise(0)).toBe('₹0.00');
    expect(formatPaise(99900)).toBe('₹999.00');
    expect(formatPaise(100000)).toBe('₹1,000.00');
    expect(formatPaise(5)).toBe('₹0.05');
    expect(() => formatPaise(1.5)).toThrow();
  });
  it('chooses the screen from what the server says', () => {
    expect(modeOf({ roles: [{ roleKey: 'teacher' }], children: [] })).toBe('teacher');
    expect(modeOf({ roles: [], children: [{}] })).toBe('parent');
    expect(modeOf({ roles: [], children: [] })).toBe('none');
    expect(modeOf({ roles: [{ roleKey: 'teacher' }], children: [{}] })).toBe('teacher');
  });
});
