import { describe, expect, it } from 'vitest';
import { homeworkLine } from './homework';
import type { HomeworkItem } from './types';

const hw = (dueOn: string): HomeworkItem => ({
  id: 'h',
  subjectName: 'Science',
  title: 'Draw a plant',
  instructions: null,
  assignedOn: '2026-11-20',
  dueOn,
});

describe('homeworkLine', () => {
  it('says when homework is due in plain words', () => {
    expect(homeworkLine(hw('2026-11-25'), '2026-11-25')).toBe('Science: Draw a plant, due today');
    expect(homeworkLine(hw('2026-11-26'), '2026-11-25')).toContain('due tomorrow');
    expect(homeworkLine(hw('2026-11-24'), '2026-11-25')).toContain('due yesterday');
    expect(homeworkLine(hw('2026-11-28'), '2026-11-25')).toContain('due in 3 days');
    expect(homeworkLine(hw('2026-11-20'), '2026-11-25')).toContain('due 5 days ago');
  });
});
