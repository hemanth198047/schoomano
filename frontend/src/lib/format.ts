/** India-first formatting. Money is carried as integer paise end to end; never as floats. */

const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2 });

export function formatPaise(paise: number): string {
  if (!Number.isInteger(paise)) throw new Error('money must be integer paise');
  return inr.format(paise / 100);
}

export function formatDateTime(iso: string, timeZone = 'Asia/Kolkata'): string {
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(
    new Date(iso),
  );
}

export function formatDate(iso: string, timeZone = 'Asia/Kolkata'): string {
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeZone }).format(new Date(iso));
}

/** Turns a datetime-local input value (campus wall clock) into ISO with the IST offset. */
export function localInputToIsoIst(value: string): string {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) throw new Error('expected YYYY-MM-DDTHH:mm');
  return `${value}:00+05:30`;
}

/** Formats a calendar day ("2026-08-15") without shifting it through a time zone. */
export function formatDay(isoDate: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(isoDate)) throw new Error('expected YYYY-MM-DD');
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeZone: 'UTC' }).format(
    new Date(`${isoDate}T00:00:00Z`),
  );
}

export function formatDayRange(startsOn: string, endsOn: string): string {
  return startsOn === endsOn ? formatDay(startsOn) : `${formatDay(startsOn)} – ${formatDay(endsOn)}`;
}

/**
 * Turns what a person types ("1,250", "1250.5", "₹ 99.99") into whole paise without floating point.
 * Returns null for anything that is not a positive amount with at most two decimals.
 */
export function rupeesToPaise(text: string): number | null {
  const t = text.replace(/[₹,\s]/g, '');
  const m = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(t);
  if (!m) return null;
  const paise = Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0') || '0');
  return paise > 0 ? paise : null;
}
