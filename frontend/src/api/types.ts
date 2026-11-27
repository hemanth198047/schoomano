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

export interface Budget {
  id: string;
  category: string;
  amountPaise: number;
  committedPaise: number;
  paidPaise: number;
}

export interface Vendor {
  id: string;
  name: string;
  contact: string | null;
}

export type PurchaseStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'PAID';

export interface PurchaseRequest {
  id: string;
  budgetId: string;
  category: string;
  vendor: string | null;
  title: string;
  description: string | null;
  amountPaise: number;
  status: PurchaseStatus;
  requestedBy: string;
  requestedAt: string;
  needsPrincipal: boolean;
  paidPaise: number | null;
  paymentReference: string | null;
  history: { from: string | null; to: string; note: string | null; by: string; at: string }[];
}

export interface StaffMember {
  personId: string;
  name: string;
  employeeNo: string;
  designation: string;
  department: string | null;
  employmentType: 'FULL_TIME' | 'PART_TIME' | 'CONTRACT';
  joinedOn: string;
  status: 'ACTIVE' | 'LEFT';
  leftOn: string | null;
}

export interface PersonMatch {
  personId: string;
  name: string;
  schoolRef: string | null;
  hasProfile: boolean;
}

export interface LeaveBalance {
  leaveType: 'CASUAL' | 'SICK' | 'EARNED';
  allowance: number;
  approved: number;
  pending: number;
  remaining: number;
}

export type LeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface LeaveRequest {
  id: string;
  personId: string;
  name: string;
  leaveType: string;
  fromDate: string;
  toDate: string;
  days: number;
  reason: string | null;
  status: LeaveStatus;
  decidedBy: string | null;
  decisionNote: string | null;
  requestedAt: string;
}

export interface TransportStop {
  id: string;
  sequence: number;
  name: string;
  pickupTime: string;
}

export interface TransportRoute {
  id: string;
  name: string;
  vehicle: string | null;
  capacity: number | null;
  driverName: string;
  driverPhone: string | null;
  assigned: number;
  stops: TransportStop[];
}

export interface Vehicle {
  id: string;
  registration: string;
  capacity: number;
}

export interface BoardingRecord {
  studentId: string;
  trip: 'MORNING' | 'AFTERNOON';
  event: 'BOARDED' | 'ALIGHTED';
  tripDate: string;
  recordedAt: string;
  replayed: boolean;
}

export interface RosterEntry {
  assignmentId: string;
  studentId: string;
  name: string;
  stop: string;
  pickupTime: string;
  today: BoardingRecord[];
}

export interface Activity {
  id: string;
  name: string;
  kind: 'CLUB' | 'SPORT' | 'ARTS' | 'OTHER';
  capacity: number | null;
  leadName: string | null;
  status: 'OPEN' | 'CLOSED';
  members: number;
  sessions: number;
}

export interface ActivityMember {
  id: string;
  studentId: string;
  name: string;
  joinedOn: string;
}

export interface ActivitySession {
  id: string;
  sessionDate: string;
  topic: string | null;
  present: number;
  absent: number;
}

export interface Trip {
  id: string;
  name: string;
  destination: string;
  tripDate: string;
  costPaise: number;
  consentDeadline: string;
  capacity: number | null;
  status: 'PLANNED' | 'COMPLETED' | 'CANCELLED';
  invited: number;
  given: number;
  declined: number;
  departed: number;
  returned: number;
}

export interface TripParticipant {
  id: string;
  studentId: string;
  name: string;
  consent: 'NONE' | 'GIVEN' | 'DECLINED';
  departed: boolean;
  returned: boolean;
}

export interface FacilityRoom {
  id: string;
  code: string;
  name: string;
  capacity: number | null;
  status: string;
}

export interface RoomBooking {
  id: string;
  roomId: string;
  roomName: string;
  purpose: string;
  startsAt: string;
  endsAt: string;
  bookedBy: string;
  mine: boolean;
  status: 'BOOKED' | 'CANCELLED';
}

export interface MaintenanceIssue {
  id: string;
  roomId: string | null;
  roomName: string | null;
  title: string;
  description: string | null;
  priority: 'LOW' | 'NORMAL' | 'URGENT';
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED';
  reportedBy: string;
  reportedAt: string;
  resolution: string | null;
  resolvedAt: string | null;
}

export interface ComplianceItem {
  id: string;
  title: string;
  category: 'FIRE_SAFETY' | 'HEALTH_SAFETY' | 'PRIVACY' | 'SAFEGUARDING' | 'FINANCE' | 'STATUTORY' | 'OTHER';
  ownerName: string;
  repeatMonths: number | null;
  nextDueOn: string | null;
  status: 'ACTIVE' | 'RETIRED';
  standing: 'OVERDUE' | 'DUE_SOON' | 'CURRENT' | 'DONE' | 'RETIRED';
  lastDoneOn: string | null;
  lastReference: string | null;
  evidenceCount: number;
}

export interface ComplianceEvidence {
  id: string;
  doneOn: string;
  reference: string | null;
  note: string | null;
  validUntil: string | null;
  recordedBy: string;
  recordedAt: string;
}

export interface AnalyticsOverview {
  yearId: string;
  yearLabel: string;
  enrolment: { enrolled: number; withdrawn: number; transferredOut: number; completed: number };
  attendance: {
    from: string;
    to: string;
    sessions: number;
    marks: number;
    attended: number;
    absent: number;
    percent: number | null;
    days: { date: string; marks: number; attended: number; percent: number | null }[];
    sections: { section: string; marks: number; attended: number; percent: number | null }[];
  };
  fees: {
    invoices: number;
    billedPaise: number;
    collectedPaise: number;
    outstandingPaise: number;
    collectedPercent: number | null;
    overdueInvoices: number;
    overduePaise: number;
  };
}
