import { describe, expect, it } from 'vitest';
import { deviceLine, otherDevices, type MyDevice } from './devices';

const phone: MyDevice = {
  id: 'a',
  deviceLabel: 'Pixel 6a',
  signedInAt: '2026-11-01T08:00:00Z',
  lastSeenAt: '2026-11-28T08:00:00Z',
  current: true,
};
const tablet: MyDevice = {
  id: 'b',
  deviceLabel: null,
  signedInAt: '2026-10-01T08:00:00Z',
  lastSeenAt: '2026-10-20T08:00:00Z',
  current: false,
};

describe('devices', () => {
  it('says which one is this phone and when the others were used', () => {
    expect(deviceLine(phone)).toBe('Pixel 6a (this phone)');
    expect(deviceLine(tablet)).toBe('Unnamed device, last used 2026-10-20, signed in 2026-10-01');
  });
  it('offers only the other devices for signing out', () => {
    expect(otherDevices([phone, tablet]).map((d) => d.id)).toEqual(['b']);
    expect(otherDevices([phone])).toEqual([]);
  });
});
