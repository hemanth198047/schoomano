import { describe, expect, it } from 'vitest';
import { parseBands } from './AcademicManage';

describe('parseBands', () => {
  it('reads one grade per line, with or without a percent sign', () => {
    expect(parseBands('A1 91\nB 50%\n\nE 0')).toEqual([
      { grade: 'A1', minPercent: 91 },
      { grade: 'B', minPercent: 50 },
      { grade: 'E', minPercent: 0 },
    ]);
  });
  it('explains what it could not read', () => {
    expect(parseBands('A1 ninety')).toContain('Could not read "A1 ninety"');
  });
  it('requires a band starting at zero', () => {
    expect(parseBands('A 50\nB 20')).toContain('start at 0');
  });
});
