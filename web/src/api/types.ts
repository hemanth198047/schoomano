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
  scopeLevel: 'GROUP' | 'CAMPUS';
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
}
