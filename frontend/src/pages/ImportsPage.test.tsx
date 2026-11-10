import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { Campus, ImportBatch, Me } from '../api/types';
import { axeViolations, renderWithApp, stubFetch } from '../test/render';
import { ImportsPage } from './ImportsPage';

const TENANT = 't';
const admin: Me = {
  personId: 'p1',
  displayName: 'Asha',
  tenant: { id: TENANT, slug: 'demo', name: 'Nilgiri' },
  roles: [{ roleKey: 'group_admin', scopeLevel: 'GROUP', scopeId: TENANT, scopeName: 'Nilgiri' }],
  children: [],
  policyVersion: 'test',
};
const teacher: Me = {
  ...admin,
  roles: [{ roleKey: 'teacher', scopeLevel: 'CAMPUS', scopeId: 'n', scopeName: 'N' }],
};
const campuses: Campus[] = [
  {
    id: 'n',
    code: 'BLR-N',
    name: 'North Campus',
    timeZone: 'Asia/Kolkata',
    status: 'ACTIVE',
    legalEntity: 'X',
  },
];
const year = { id: 'y1', label: '2026-27', startsOn: '2026-04-01', endsOn: '2027-03-31', status: 'ACTIVE' };
const batch: ImportBatch = {
  id: 'b1',
  entity: 'STUDENT',
  fileName: 'students.csv',
  status: 'VALIDATED',
  academicYearId: 'y1',
  yearLabel: '2026-27',
  counts: { CREATE: 2, UPDATE: 1, UNCHANGED: 4, CONFLICT: 1, ERROR: 1, MISSING: 3 },
  fileProblems: [],
  missingFromFile: ['OLD-1'],
  createdAt: '2026-11-10T10:00:00Z',
  rowsTotal: 9,
  rows: [
    { rowNo: 4, externalRef: '', action: 'ERROR', problems: ['Admission number is missing.'], changes: [] },
    {
      rowNo: 3,
      externalRef: 'IMP-3',
      action: 'CONFLICT',
      problems: ['Last name was changed in Schoomano and in the old system.'],
      changes: [],
    },
    {
      rowNo: 2,
      externalRef: 'IMP-1',
      action: 'UPDATE',
      problems: [],
      changes: [{ field: 'placement', before: 'CBSE-K12/G8/A', after: 'CBSE-K12/G8/B' }],
    },
  ],
};
const committed: ImportBatch = { ...batch, id: 'b0', status: 'COMMITTED', rows: [], rowsTotal: 0 };

let calls: ReturnType<typeof stubFetch>;
beforeEach(() => {
  calls = stubFetch((url, init) => {
    if (url.endsWith('/academic-structure'))
      return {
        body: { campusId: 'n', campusName: 'North', years: [year], year, programmes: [], calendar: [] },
      };
    if (url.endsWith('/imports') && init.method === 'GET') return { body: [committed] };
    if (url.endsWith('/imports/students')) return { status: 201, body: batch };
    if (url.endsWith('/commit'))
      return {
        body: { batch: { ...batch, status: 'COMMITTED' }, created: 2, updated: 1, unchanged: 4, skipped: 2 },
      };
    if (url.endsWith('/rollback'))
      return {
        body: {
          batch: { ...committed, status: 'ROLLED_BACK' },
          removed: 2,
          restored: 1,
          linksRestored: 0,
          kept: ['IMP-9: kept, because the student details were edited after the import.'],
        },
      };
    return { status: 404, body: { code: 'not_found', detail: 'no route' } };
  });
});

async function checkFile() {
  const { container } = renderWithApp(<ImportsPage me={admin} campuses={campuses} selectedCampusId={null} />);
  const upload = within(await screen.findByRole('region', { name: 'Check a file' }));
  const file = new File(['admission_no,given_name\n'], 'students.csv', { type: 'text/csv' });
  await userEvent.upload(upload.getByLabelText(/Export file/), file);
  await userEvent.click(upload.getByRole('button', { name: 'Check file' }));
  const report = await screen.findByRole('region', { name: 'What this file would do' });
  return Object.assign(report, { pageContainer: container });
}

describe('ImportsPage', () => {
  it('uploads the file as multipart form data and shows the dry-run report, problems first', async () => {
    const report = await checkFile();
    const upload = calls.find((c) => c.url.endsWith('/imports/students'))!;
    expect(upload.init.body).toBeInstanceOf(FormData);
    expect((upload.init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
    const rows = within(report).getByRole('table', { name: /Rows needing attention/ });
    expect(rows).toHaveTextContent('Admission number is missing.');
    expect(rows).toHaveTextContent('Section: CBSE-K12/G8/A → CBSE-K12/G8/B');
    expect(report).toHaveTextContent('3 students imported before are not in this file');
    expect(within(report).getByRole('button', { name: 'Import 3 new and changed students' })).toBeEnabled();
  });

  it('commits and reports what was applied', async () => {
    await checkFile();
    await userEvent.click(screen.getByRole('button', { name: 'Import 3 new and changed students' }));
    expect(await screen.findByText(/Imported: 2 new, 1 changed/)).toBeInTheDocument();
  });

  it('asks for a file before calling the server', async () => {
    renderWithApp(<ImportsPage me={admin} campuses={campuses} selectedCampusId={null} />);
    const upload = within(await screen.findByRole('region', { name: 'Check a file' }));
    await userEvent.click(upload.getByRole('button', { name: 'Check file' }));
    expect(upload.getByLabelText(/Export file/)).toHaveAccessibleDescription(
      '.csv or .xlsx, up to 5 MB Choose the exported .csv or .xlsx file.',
    );
    expect(calls.some((c) => c.init.method === 'POST')).toBe(false);
  });

  it('rolls back the latest import after confirmation and lists what was kept', async () => {
    renderWithApp(<ImportsPage me={admin} campuses={campuses} selectedCampusId={null} />);
    await screen.findByRole('table', { name: 'Imports for this year' });
    await userEvent.click(screen.getByRole('button', { name: 'Roll back' }));
    await userEvent.click(screen.getByRole('button', { name: 'Yes, roll back' }));
    await waitFor(() =>
      expect(screen.getByRole('region', { name: 'Kept during rollback' })).toHaveTextContent('IMP-9'),
    );
  });

  it('is not offered to teachers', () => {
    renderWithApp(<ImportsPage me={teacher} campuses={campuses} selectedCampusId={null} />);
    expect(screen.getByText('Imports are for school administrators')).toBeInTheDocument();
  });

  it('has no automatically detectable accessibility violations', async () => {
    const report = await checkFile();
    expect(await axeViolations(report.pageContainer)).toEqual([]);
  });
});
