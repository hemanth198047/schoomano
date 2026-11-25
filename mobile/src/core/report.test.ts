import { describe, expect, it } from 'vitest';
import { reportLines } from './report';
import type { ReportCard } from './types';

const card: ReportCard = {
  id: 'r1',
  termId: 't1',
  termLabel: 'Term 1',
  sectionLabel: 'Grade 5 A',
  revision: 1,
  revisionReason: null,
  subjects: [
    { subjectId: 'e', name: 'English', percent: 90, grade: 'A2', assessments: 2 },
    { subjectId: 'm', name: 'Maths', percent: 80.25, grade: 'B1', assessments: 1 },
  ],
  overallPercent: 85,
  overallGrade: 'A2',
  publishedAt: '2026-10-01T10:00:00Z',
};

describe('reportLines', () => {
  it('lists each subject with one decimal, then the overall result', () => {
    expect(reportLines(card)).toEqual([
      'English: 90.0%, grade A2',
      'Maths: 80.3%, grade B1',
      'Overall: 85.0%, grade A2',
    ]);
  });

  it('says when a card was corrected, with the reason', () => {
    expect(reportLines({ ...card, revision: 2, revisionReason: 'Corrected an English mark' }).at(-1)).toBe(
      'Corrected (revision 2): Corrected an English mark',
    );
  });
});
