/** The parts of the server's answers the app uses. Money is whole paise. */
export type AttendanceStatus = 'PRESENT' | 'ABSENT' | 'LATE' | 'LEAVE';

export interface Me {
  personId: string;
  displayName: string;
  tenant: { id: string; slug: string; name: string };
  roles: { roleKey: string; scopeLevel: string; scopeId: string; scopeName: string }[];
  children: {
    studentId: string;
    displayName: string;
    schoolRef: string;
    campusName?: string;
    capabilities: string[];
  }[];
}

export interface TokenResponse {
  accessToken: string;
  expiresIn: number;
  refreshToken: string;
  refreshExpiresIn: number;
}

export interface InboxItem {
  id: string;
  kind: string;
  title: string;
  body: string;
  receivedAt: string;
  read: boolean;
}

export interface InvoiceSummary {
  id: string;
  number: string;
  status: 'ISSUED' | 'PARTIALLY_PAID' | 'PAID' | 'CANCELLED';
  dueOn: string;
  totalPaise: number;
  paidPaise: number;
  overdue: boolean;
}

export interface PaymentOrder {
  paymentId: string;
  orderId: string;
  amountPaise: number;
  sandbox: boolean;
}

export interface ChildAttendance {
  days: { date: string; status: AttendanceStatus; classLabel: string }[];
  present: number;
  absent: number;
  late: number;
  leave: number;
}

export interface RosterRow {
  studentId: string;
  schoolRef: string;
  name: string;
  status: AttendanceStatus | null;
  version: number | null;
}

export interface Roster {
  sectionId: string;
  sectionLabel: string;
  date: string;
  students: RosterRow[];
  openConflicts: number;
}

export interface BatchResult {
  results: { studentId: string; outcome: string; message: string | null }[];
  conflicts: number;
  replayed: boolean;
}

export interface CampusStructure {
  year: { id: string } | null;
  programmes: { grades: { name: string; sections: { id: string; code: string }[] }[] }[];
}
