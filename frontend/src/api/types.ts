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
