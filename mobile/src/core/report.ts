import type { ReportCard } from './types';

/** Plain-language lines for one published report card, so a screen reader reads each subject on its own. */
export function reportLines(card: ReportCard): string[] {
  const lines = card.subjects.map((s) => `${s.name}: ${s.percent.toFixed(1)}%, grade ${s.grade}`);
  lines.push(`Overall: ${card.overallPercent.toFixed(1)}%, grade ${card.overallGrade}`);
  if (card.revision > 1) {
    lines.push(
      `Corrected (revision ${card.revision})${card.revisionReason ? `: ${card.revisionReason}` : ''}`,
    );
  }
  return lines;
}
