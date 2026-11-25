// Mirrors docs/api/openapi.yaml. Replace with generated types once the contract generator is wired (Stage B).

export type GuardianCapability =
  'VIEW_ATTENDANCE' | 'VIEW_ACADEMIC' | 'PAY_FEES' | 'GIVE_CONSENT' | 'RECEIVE_NOTICES' | 'PICKUP';

export interface Me {
  personId: string;
  displayName: string;
  tenant: { id: string; slug: string; name: string };
  roles: { roleKey: string; scopeLevel: string; scopeId: string; scopeName: string }[];
  children: {
    studentId: string;
    displayName: string;
    schoolRef: string;
    campusId?: string;
    campusName?: string;
    capabilities: GuardianCapability[];
  }[];
  policyVersion: string;
}

export interface Campus {
  id: string;
  code: string;
  name: string;
  timeZone: string;
  status: 'SETUP' | 'ACTIVE' | 'ARCHIVED';
  legalEntity: string;
  activeYear?: string;
}

export interface ResolvedConfig {
  key: string;
  value: unknown;
  version: number;
  definedAtLevel: string;
  definedAtId: string;
  inherited: boolean;
  asOf: string;
}

export interface ConfigVersion {
  id: string;
  scopeLevel: 'GROUP' | 'CAMPUS' | 'PROGRAMME';
  scopeId: string;
  version: number;
  value: unknown;
  effectiveFrom: string;
  effectiveTo?: string;
  status: 'DRAFT' | 'PUBLISHED' | 'RETIRED';
  changeNote: string;
  publishedAt?: string;
}

export interface PreviewRow {
  campusId: string;
  campusName: string;
  before: unknown;
  after: unknown;
  changed: boolean;
  programmeId?: string | null;
  programmeName?: string | null;
}

export interface AcademicYear {
  id: string;
  label: string;
  startsOn: string;
  endsOn: string;
  status: 'PLANNED' | 'ACTIVE' | 'CLOSED';
}

export interface Section {
  id: string;
  code: string;
  capacity: number | null;
  students: number;
}

export interface Grade {
  id: string;
  code: string;
  name: string;
  ordinal: number;
  sections: Section[];
}

export interface Term {
  id: string;
  label: string;
  startsOn: string;
  endsOn: string;
}

export interface Programme {
  id: string;
  code: string;
  name: string;
  board: 'CBSE' | 'CISCE' | 'IB' | 'CAMBRIDGE' | 'STATE' | 'OTHER';
  boardDetail: string | null;
  grades: Grade[];
  subjects: { id: string; code: string; name: string; status: 'ACTIVE' | 'RETIRED' }[];
  terms: Term[];
}

export interface CalendarEvent {
  id: string;
  kind: 'HOLIDAY' | 'EXAM' | 'EVENT' | 'WORKING_DAY';
  title: string;
  startsOn: string;
  endsOn: string;
  programmeId: string | null;
}

export interface AcademicStructure {
  campusId: string;
  campusName: string;
  years: AcademicYear[];
  year: AcademicYear | null;
  programmes: Programme[];
  calendar: CalendarEvent[];
}

export interface DateRange {
  startsOn: string;
  endsOn: string;
}

export interface RolloverPreview {
  label: string;
  dates: DateRange;
  terms: { programmeCode: string; label: string; dates: DateRange }[];
  sections: { programmeCode: string; gradeCode: string; sectionCode: string; capacity: number | null }[];
  promotions: { programmeCode: string; fromGrade: string; toGrade: string | null; students: number }[];
  warnings: string[];
}

export interface RolloverResult {
  yearId: string;
  label: string;
  startsOn: string;
  endsOn: string;
  termsCreated: number;
  sectionsCreated: number;
  studentsPlaced: number;
  studentsUnplaced: number;
  studentsCompleted: number;
}

export type ImportAction = 'CREATE' | 'UPDATE' | 'UNCHANGED' | 'CONFLICT' | 'ERROR';

export interface ImportRow {
  rowNo: number;
  externalRef: string;
  action: ImportAction;
  problems: string[];
  changes: { field: string; before: string; after: string }[];
}

export interface ImportBatch {
  id: string;
  entity: 'STUDENT';
  fileName: string;
  status: 'VALIDATED' | 'COMMITTED' | 'ROLLED_BACK' | 'DISCARDED';
  academicYearId: string;
  yearLabel: string;
  counts: Record<ImportAction | 'MISSING', number>;
  fileProblems: string[];
  missingFromFile: string[];
  createdAt: string;
  committedAt?: string;
  rolledBackAt?: string;
  rowsTotal: number;
  rows: ImportRow[];
}

export interface ImportCommitResult {
  batch: ImportBatch;
  created: number;
  updated: number;
  unchanged: number;
  skipped: number;
}

export interface ImportRollbackResult {
  batch: ImportBatch;
  removed: number;
  restored: number;
  linksRestored: number;
  kept: string[];
}

export interface StudentListItem {
  id: string;
  schoolRef: string;
  displayName: string;
  programmeCode?: string;
  gradeName?: string;
  sectionCode?: string;
  sectionId?: string;
  guardians: number;
  source?: string;
  status: string;
}

export interface StudentPage {
  total: number;
  students: StudentListItem[];
}

export type Capability = GuardianCapability;

export interface GuardianView {
  relationshipId: string;
  guardianId: string;
  displayName: string;
  givenName: string;
  familyName?: string;
  mobile?: string;
  email?: string;
  relationshipType: string;
  status: 'ACTIVE' | 'RESTRICTED' | 'SUSPENDED' | 'ENDED';
  capabilities: GuardianCapability[];
  sharesHousehold: boolean;
}

export interface StudentProfile {
  id: string;
  schoolRef: string;
  givenName: string;
  familyName?: string;
  preferredName?: string;
  dateOfBirth?: string;
  status: string;
  view: 'STAFF' | 'GUARDIAN';
  source?: string;
  ownedFields: string[];
  placements: {
    academicYearId: string;
    yearLabel: string;
    campusName: string;
    programmeCode?: string;
    gradeName?: string;
    sectionCode?: string;
    sectionId?: string;
    status: string;
  }[];
  guardians: GuardianView[];
  households: string[];
  yourCapabilities: GuardianCapability[];
}

export interface TimetablePeriod {
  id: string;
  ordinal: number;
  label: string;
  startsAt: string;
  endsAt: string;
  kind: 'LESSON' | 'BREAK';
}

export interface TimetableEntry {
  id: string;
  sectionId: string;
  sectionLabel: string;
  day: number;
  periodId: string;
  periodOrdinal: number;
  periodLabel: string;
  subjectId: string;
  subjectName: string;
  teacherId: string;
  teacherName: string;
  roomId: string | null;
  roomName: string | null;
}

export interface TimetableStatus {
  status: 'DRAFT' | 'PUBLISHED';
  version: number;
  publishedAt: string | null;
  clashes: {
    kind: 'TEACHER' | 'ROOM';
    day: number;
    period: string;
    resource: string;
    sections: string[];
    text: string;
  }[];
}

export interface TeacherOption {
  id: string;
  name: string;
}

export interface MyLesson {
  date: string;
  periodOrdinal: number;
  periodLabel: string;
  startsAt: string;
  endsAt: string;
  sectionLabel: string;
  subjectName: string;
  roomName: string | null;
  coverFor: string | null;
}

export interface Substitution {
  id: string;
  entryId: string;
  onDate: string;
  sectionLabel: string;
  periodOrdinal: number;
  periodLabel: string;
  subjectName: string;
  absentTeacherId: string;
  absentTeacherName: string;
  substituteId: string;
  substituteName: string;
  reason: string;
}

export type AttendanceStatus = 'PRESENT' | 'ABSENT' | 'LATE' | 'LEAVE';

export interface AttendanceRosterRow {
  studentId: string;
  schoolRef: string;
  name: string;
  status: AttendanceStatus | null;
  version: number | null;
}

export interface AttendanceRoster {
  sectionId: string;
  sectionLabel: string;
  date: string;
  students: AttendanceRosterRow[];
  openConflicts: number;
}

export interface AttendanceMarkResult {
  studentId: string;
  outcome: 'CREATED' | 'UPDATED' | 'UNCHANGED' | 'CONFLICT' | 'REJECTED';
  status: AttendanceStatus | null;
  version: number | null;
  message: string | null;
}

export interface AttendanceBatchResult {
  results: AttendanceMarkResult[];
  conflicts: number;
  replayed: boolean;
}

export interface AttendanceConflict {
  id: string;
  studentId: string;
  studentName: string;
  sectionLabel: string;
  date: string;
  currentStatus: AttendanceStatus;
  currentVersion: number;
  attemptedStatus: AttendanceStatus;
  attemptedBy: string;
  attemptedMarkedAt: string;
}

export interface InboxItem {
  id: string;
  kind: 'ANNOUNCEMENT' | 'ATTENDANCE' | 'SUBSTITUTION';
  title: string;
  body: string;
  receivedAt: string;
  read: boolean;
}

export interface SentNotice {
  id: string;
  title: string;
  audience: string;
  createdAt: string;
  people: number;
  smsSent: number;
  smsWaiting: number;
  smsFailed: number;
  smsSkipped: number;
}

export interface InvoiceSummary {
  id: string;
  number: string;
  status: 'ISSUED' | 'PARTIALLY_PAID' | 'PAID' | 'CANCELLED';
  studentId: string;
  studentName: string;
  dueOn: string;
  totalPaise: number;
  paidPaise: number;
  overdue: boolean;
}

export interface InvoicePayment {
  id: string;
  amountPaise: number;
  method: 'GATEWAY' | 'CASH' | 'CHEQUE' | 'UPI_OFFICE';
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'NEEDS_REFUND';
  reference: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface InvoiceDetail {
  invoice: InvoiceSummary;
  lines: { description: string; amountPaise: number }[];
  payments: InvoicePayment[];
}

export interface PaymentOrder {
  paymentId: string;
  orderId: string;
  amountPaise: number;
  provider: string;
  sandbox: boolean;
}

export interface Reconciliation {
  days: { date: string; method: string; payments: number; totalPaise: number }[];
  exceptions: { kind: string; invoiceNumber: string; amountPaise: number; at: string; detail: string }[];
  collectedPaise: number;
}

export interface SupportGrant {
  id: string;
  supportUserRef: string;
  purpose: string;
  approvedBy: string;
  startsAt: string;
  expiresAt: string;
  revokedAt: string | null;
  state: 'ACTIVE' | 'UPCOMING' | 'EXPIRED' | 'REVOKED';
}

export interface AuditRow {
  seq: number;
  occurredAt: string;
  actorKind: string;
  actorName: string;
  action: string;
  resourceType: string;
  resourceId: string | null;
  outcome: string;
  reason: string | null;
  correlationId: string | null;
}

export interface OperationsHealth {
  at: string;
  supportAccessLive: number;
  checks: { key: string; label: string; level: 'OK' | 'ATTENTION'; detail: string }[];
}

export interface AuditVerification {
  checked: number;
  intact: boolean;
  brokenSeq: number | null;
  problem: string | null;
}

export type MarkKind = 'SCORED' | 'ABSENT' | 'EXEMPT';

export interface AssessmentItem {
  id: string;
  sectionId: string;
  subjectId: string;
  subjectName: string;
  termId: string;
  termLabel: string;
  name: string;
  maxMarks: number;
  weightPercent: number;
  heldOn: string | null;
  status: 'OPEN' | 'LOCKED';
  marksEntered: number;
  students: number;
}

export interface MarkRow {
  studentId: string;
  schoolRef: string;
  name: string;
  kind: MarkKind | null;
  marks: number | null;
  version: number | null;
}

export interface AssessmentMarks {
  assessment: AssessmentItem;
  marks: MarkRow[];
}

export interface MarksResult {
  created: number;
  changed: number;
  unchanged: number;
}

export interface SubjectLine {
  subjectId: string;
  name: string;
  percent: number;
  grade: string;
  assessments: number;
}

export interface StudentPreview {
  studentId: string;
  name: string;
  subjects: SubjectLine[];
  overallPercent: number;
  overallGrade: string;
  publishedRevision: number | null;
  changed: boolean;
}

export interface ReportPreview {
  sectionId: string;
  termId: string;
  ready: boolean;
  blockers: string[];
  gradeScaleVersion: number | null;
  students: StudentPreview[];
}

export interface PublishResult {
  published: number;
  unchanged: number;
}

export type AdmissionStatus =
  'ENQUIRY' | 'APPLIED' | 'OFFERED' | 'ACCEPTED' | 'ENROLLED' | 'DECLINED' | 'WITHDRAWN' | 'REJECTED';

export interface AdmissionItem {
  id: string;
  yearId: string;
  gradeLevelId: string;
  gradeName: string;
  programmeCode: string;
  childName: string;
  dateOfBirth: string | null;
  guardianName: string;
  guardianMobile: string;
  guardianEmail: string | null;
  source: string;
  notes: string | null;
  status: AdmissionStatus;
  studentId: string | null;
  createdAt: string;
}

export interface AdmissionSeats {
  gradeLevelId: string;
  gradeName: string;
  programmeCode: string;
  capacity: number | null;
  enrolled: number;
  held: number;
}

export interface AdmissionListing {
  items: AdmissionItem[];
  seats: AdmissionSeats[];
}

export interface HomeworkItem {
  id: string;
  sectionId: string;
  subjectId: string;
  subjectName: string;
  title: string;
  instructions: string | null;
  assignedOn: string;
  dueOn: string;
  status: 'ACTIVE' | 'CANCELLED';
}

export interface CoverageUnit {
  id: string;
  ordinal: number;
  title: string;
  outcomes: string | null;
  coveredOn: string | null;
}

export interface Coverage {
  subjectId: string;
  total: number;
  covered: number;
  units: CoverageUnit[];
}

export type SupportStatus = 'OPEN' | 'MONITORING' | 'CLOSED';

export interface SupportCaseSummary {
  id: string;
  studentId: string;
  studentName: string;
  category: string;
  title: string;
  status: SupportStatus;
  reviewOn: string | null;
  reviewDue: boolean;
  openedOn: string;
}

export interface SupportCaseDetail {
  summary: SupportCaseSummary;
  closedReason: string | null;
  notes: { id: string; body: string; author: string; at: string }[];
}

export type SafeguardingStatus = 'REPORTED' | 'TRIAGED' | 'UNDER_REVIEW' | 'ESCALATED' | 'CLOSED';

export interface SafeguardingReceipt {
  id: string;
  receivedAt: string;
  message: string;
}

export interface SafeguardingSummary {
  id: string;
  studentId: string;
  studentName: string;
  category: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'IMMEDIATE';
  status: SafeguardingStatus;
  reportedAt: string;
  triageDueAt: string;
  triageOverdue: boolean;
}

export interface SafeguardingDetail {
  summary: SafeguardingSummary;
  description: string;
  occurredOn: string | null;
  reportedBy: string;
  actions: { from: string; to: string | null; note: string | null; by: string; at: string }[];
}

export interface HealthVisit {
  id: string;
  visitedAt: string;
  complaint: string;
  actionTaken: string | null;
  outcome: 'RETURNED_TO_CLASS' | 'SENT_HOME' | 'REFERRED' | 'OTHER';
  guardianInformed: boolean;
  recordedBy: string;
}

export interface HealthRecord {
  studentId: string;
  studentName: string;
  bloodGroup: string | null;
  allergies: string | null;
  conditions: string | null;
  medicines: string | null;
  emergencyFlag: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
  visits: HealthVisit[];
}

export interface HealthFlag {
  studentId: string;
  studentName: string;
  flag: string | null;
}
