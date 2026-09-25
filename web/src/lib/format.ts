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
