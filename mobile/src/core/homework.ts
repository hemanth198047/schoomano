import type { HomeworkItem } from './types';

/** One readable line per task, with the due date in words relative to today (all dates are YYYY-MM-DD). */
export function homeworkLine(item: HomeworkItem, today: string): string {
  const days = Math.round((Date.parse(item.dueOn) - Date.parse(today)) / 86_400_000);
  const when =
    days < -1
      ? `${-days} days ago`
      : days === -1
        ? 'yesterday'
        : days === 0
          ? 'today'
          : days === 1
            ? 'tomorrow'
            : `in ${days} days`;
  return `${item.subjectName}: ${item.title}, due ${when}`;
}
