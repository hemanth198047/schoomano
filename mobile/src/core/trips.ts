import { formatPaise } from './money';
import type { ChildTrip } from './types';

export function tripLine(t: ChildTrip): string {
  const cost = t.costPaise === 0 ? 'free' : formatPaise(t.costPaise);
  return `${t.name} to ${t.destination} on ${t.tripDate} (${cost})`;
}

export function consentLine(t: ChildTrip): string {
  if (t.status === 'COMPLETED')
    return t.consent === 'GIVEN' ? 'You agreed. The trip is over.' : 'The trip is over.';
  if (t.consent === 'GIVEN')
    return t.canDecide ? `You agreed. You can change your answer until ${t.consentDeadline}.` : 'You agreed.';
  if (t.consent === 'DECLINED')
    return t.canDecide
      ? `You said no. You can change your answer until ${t.consentDeadline}.`
      : 'You said no.';
  return t.canDecide
    ? `Your answer is needed by ${t.consentDeadline}.`
    : 'The time to answer has passed. Please contact the school.';
}
