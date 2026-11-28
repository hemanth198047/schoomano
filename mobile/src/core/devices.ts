/** A device the parent's number is signed in on. */
export interface MyDevice {
  id: string;
  deviceLabel: string | null;
  signedInAt: string;
  lastSeenAt: string;
  current: boolean;
}

const dayOf = (iso: string) => iso.slice(0, 10);

export function deviceLine(d: MyDevice): string {
  const name = d.deviceLabel && d.deviceLabel.trim() !== '' ? d.deviceLabel : 'Unnamed device';
  return d.current
    ? `${name} (this phone)`
    : `${name}, last used ${dayOf(d.lastSeenAt)}, signed in ${dayOf(d.signedInAt)}`;
}

/** Devices other than the one in use, which are the ones that can be signed out from here. */
export const otherDevices = (all: MyDevice[]): MyDevice[] => all.filter((d) => !d.current);
