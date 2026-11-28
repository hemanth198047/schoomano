import { APPROVER_ROLES } from './approvals';

/** Whole paise to rupees with Indian digit grouping. Never floats for money. */
export function formatPaise(paise: number): string {
  if (!Number.isInteger(paise)) throw new Error('money must be integer paise');
  const sign = paise < 0 ? '-' : '';
  const abs = Math.abs(paise);
  const rupees = Math.floor(abs / 100).toString();
  const last3 = rupees.slice(-3);
  const rest = rupees.slice(0, -3);
  const grouped = rest ? `${rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',')},${last3}` : last3;
  return `${sign}₹${grouped}.${(abs % 100).toString().padStart(2, '0')}`;
}

/** Which dashboard the person gets, decided by what the server says they are. */
export function modeOf(me: {
  roles: { roleKey: string }[];
  children: unknown[];
}): 'teacher' | 'parent' | 'approver' | 'none' {
  if (me.roles.some((r) => r.roleKey === 'teacher')) return 'teacher';
  if (me.children.length > 0) return 'parent';
  // Someone who is both a parent and a principal gets the parent view; approvals are for staff with no child here.
  if (me.roles.some((r) => APPROVER_ROLES.includes(r.roleKey))) return 'approver';
  return 'none';
}
