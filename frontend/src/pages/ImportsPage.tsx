import { useState, type FormEvent } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  Campus,
  ImportAction,
  ImportBatch,
  ImportCommitResult,
  ImportRollbackResult,
  ImportRow,
  Me,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { DataTable } from '../components/DataTable';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDateTime } from '../lib/format';
import { asApiError } from './AcademicManage';

const actionText: Record<ImportAction, string> = {
  CREATE: 'New',
  UPDATE: 'Changed',
  UNCHANGED: 'No change',
  CONFLICT: 'Conflict',
  ERROR: 'Error',
};
const actionTone = {
  CREATE: 'success',
  UPDATE: 'info',
  UNCHANGED: 'neutral',
  CONFLICT: 'warning',
  ERROR: 'danger',
} as const;
const statusText: Record<ImportBatch['status'], string> = {
  VALIDATED: 'Checked, not imported',
  COMMITTED: 'Imported',
  ROLLED_BACK: 'Rolled back',
  DISCARDED: 'Discarded',
};
const fieldText: Record<string, string> = {
  givenName: 'First name',
  familyName: 'Last name',
  dateOfBirth: 'Date of birth',
  placement: 'Section',
};

export const TEMPLATE =
  'Admission No,First Name,Last Name,Date of Birth,Programme,Grade,Section\n' +
  'ADM-1001,Kavya,Iyer,14/08/2015,CBSE-K12,5,A\n';

/** Who may import; the server enforces IMPORT_MANAGE regardless. */
export const canImport = (me: Me) =>
  me.roles.some((r) => r.roleKey === 'group_admin' || r.roleKey === 'campus_admin');

export function ImportsPage({
  me,
  campuses,
  selectedCampusId,
}: {
  me: Me;
  campuses: Campus[];
  selectedCampusId: string | null;
}) {
  const [chosenCampus, setChosenCampus] = useState<string | null>(null);
  const [chosenYear, setChosenYear] = useState<string | null>(null);
  const campusId = selectedCampusId ?? chosenCampus ?? campuses[0]?.id ?? null;
  const [structure] = useApiResource<AcademicStructure>(
    campusId === null ? null : `/api/v1/campuses/${campusId}/academic-structure`,
  );
  const years = structure.status === 'ready' ? structure.data.years : [];
  const yearId = chosenYear ?? (structure.status === 'ready' ? (structure.data.year?.id ?? null) : null);

  if (!canImport(me)) {
    return (
      <>
        <PageHeader title="Import from the old system" />
        <EmptyState title="Imports are for school administrators">
          <p>Ask an administrator if student records need to be brought across.</p>
        </EmptyState>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Import from the old system"
        lead="Bring student records across from your current system's export. Check the file first; nothing changes until you import."
      />
      <div className="form form--inline">
        {selectedCampusId === null && campuses.length > 1 && (
          <Field label="Campus">
            {(p) => (
              <select
                {...p}
                value={campusId ?? ''}
                onChange={(e) => {
                  setChosenCampus(e.target.value);
                  setChosenYear(null);
                }}
              >
                {campuses.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        {years.length > 0 && (
          <Field label="Academic year">
            {(p) => (
              <select {...p} value={yearId ?? ''} onChange={(e) => setChosenYear(e.target.value)}>
                {years.map((y) => (
                  <option key={y.id} value={y.id}>
                    {y.label}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
      </div>
      {structure.status === 'loading' && campusId !== null && <LoadingState label="Loading years" />}
      {structure.status === 'error' && <ErrorState error={structure.error} />}
      {structure.status === 'ready' && yearId === null && (
        <EmptyState title="Create an academic year first">
          <p>Students are imported into an academic year. Add one under Academic structure.</p>
        </EmptyState>
      )}
      {yearId !== null && <ImportWorkspace key={yearId} yearId={yearId} />}
    </>
  );
}

function ImportWorkspace({ yearId }: { yearId: string }) {
  const session = useApiSession();
  const [history, reloadHistory] = useApiResource<ImportBatch[]>(`/api/v1/academic-years/${yearId}/imports`);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | undefined>();
  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [kept, setKept] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const act = async (work: () => Promise<void>) => {
    setBusy(true);
    setFailure(null);
    setDone(null);
    setKept([]);
    try {
      await work();
    } catch (e) {
      setFailure(asApiError(e));
    } finally {
      setBusy(false);
    }
  };

  const check = (ev: FormEvent) => {
    ev.preventDefault();
    if (!file) {
      setFileError('Choose the exported .csv or .xlsx file.');
      return;
    }
    setFileError(undefined);
    const form = new FormData();
    form.append('file', file);
    void act(async () => {
      setBatch(
        await apiRequest<ImportBatch>(session, `/api/v1/academic-years/${yearId}/imports/students`, {
          method: 'POST',
          form,
        }),
      );
      reloadHistory();
    });
  };

  const commit = () =>
    act(async () => {
      const r = await apiRequest<ImportCommitResult>(session, `/api/v1/imports/${batch!.id}/commit`, {
        method: 'POST',
      });
      setDone(
        `Imported: ${r.created} new, ${r.updated} changed, ${r.unchanged} unchanged, ${r.skipped} skipped (errors and conflicts).`,
      );
      setBatch(null);
      reloadHistory();
    });

  const discard = () =>
    act(async () => {
      await apiRequest(session, `/api/v1/imports/${batch!.id}/discard`, { method: 'POST' });
      setDone('Discarded. Nothing was imported.');
      setBatch(null);
      reloadHistory();
    });

  const rollback = (id: string) =>
    act(async () => {
      const r = await apiRequest<ImportRollbackResult>(session, `/api/v1/imports/${id}/rollback`, {
        method: 'POST',
      });
      setDone(`Rolled back: ${r.removed} students removed, ${r.restored} restored to their earlier details.`);
      setKept(r.kept);
      reloadHistory();
    });

  const latestCommitted =
    history.status === 'ready' ? history.data.find((b) => b.status === 'COMMITTED')?.id : undefined;
  const toApply = batch ? batch.counts.CREATE + batch.counts.UPDATE : 0;

  return (
    <>
      <section className="section panel" aria-labelledby="upload-h">
        <h2 id="upload-h" className="section__title">
          Check a file
        </h2>
        <p className="muted">
          Columns: admission number and first name are required; last name, date of birth (DD/MM/YYYY),
          programme, grade and section are optional.{' '}
          <a
            href={`data:text/csv;charset=utf-8,${encodeURIComponent(TEMPLATE)}`}
            download="students-template.csv"
          >
            Download a template
          </a>
          .
        </p>
        <form className="form" onSubmit={check} noValidate>
          <Field label="Export file" hint=".csv or .xlsx, up to 5 MB" required error={fileError}>
            {(p) => (
              <input
                {...p}
                type="file"
                accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            )}
          </Field>
          <div className="form__actions">
            <Button type="submit" variant="primary" disabled={busy}>
              Check file
            </Button>
          </div>
        </form>
        {done && (
          <p className="notice notice--success" role="status">
            {done}
          </p>
        )}
        {kept.length > 0 && (
          <div className="notice" role="region" aria-label="Kept during rollback">
            <p>Some changes were kept because they were edited after the import:</p>
            <ul>
              {kept.map((k) => (
                <li key={k}>{k}</li>
              ))}
            </ul>
          </div>
        )}
        {failure && <ErrorState error={failure} />}
      </section>

      {batch && (
        <section className="section panel" aria-labelledby="report-h">
          <h2 id="report-h" className="section__title">
            What this file would do
          </h2>
          {batch.fileProblems.length > 0 ? (
            <ul className="notice">
              {batch.fileProblems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          ) : (
            <>
              <p role="status">
                {(['CREATE', 'UPDATE', 'UNCHANGED', 'CONFLICT', 'ERROR'] as ImportAction[]).map((a) => (
                  <span key={a} className="count-chip">
                    <StatusBadge tone={actionTone[a]}>{actionText[a]}</StatusBadge> {batch.counts[a]}
                  </span>
                ))}
              </p>
              {batch.counts.MISSING > 0 && (
                <p className="muted">
                  {batch.counts.MISSING} students imported before are not in this file. They are left as they
                  are.
                </p>
              )}
              <ImportRows rows={batch.rows} total={batch.rowsTotal} />
              <div className="form__actions">
                <Button variant="primary" disabled={busy || toApply === 0} onClick={() => void commit()}>
                  {toApply === 0 ? 'Nothing to import' : `Import ${toApply} new and changed students`}
                </Button>
                <Button variant="secondary" disabled={busy} onClick={() => void discard()}>
                  Discard
                </Button>
              </div>
              {(batch.counts.CONFLICT > 0 || batch.counts.ERROR > 0) && (
                <p className="muted">
                  Rows with errors or conflicts are skipped. Fix them in the old system or in Schoomano and
                  check the file again.
                </p>
              )}
            </>
          )}
        </section>
      )}

      <section className="section" aria-labelledby="history-h">
        <h2 id="history-h" className="section__title">
          Earlier imports
        </h2>
        {history.status === 'loading' && <LoadingState label="Loading imports" />}
        {history.status === 'error' && <ErrorState error={history.error} onRetry={reloadHistory} />}
        {history.status === 'ready' && (
          <DataTable
            caption="Imports for this year"
            rows={history.data}
            rowKey={(b) => b.id}
            empty={<EmptyState title="No imports yet" />}
            columns={[
              {
                key: 'when',
                header: 'Checked',
                render: (b) => formatDateTime(b.createdAt),
                sortValue: (b) => b.createdAt,
              },
              { key: 'file', header: 'File', render: (b) => b.fileName },
              {
                key: 'status',
                header: 'Status',
                render: (b) => (
                  <StatusBadge
                    tone={
                      b.status === 'COMMITTED' ? 'success' : b.status === 'VALIDATED' ? 'info' : 'neutral'
                    }
                  >
                    {statusText[b.status]}
                  </StatusBadge>
                ),
              },
              {
                key: 'counts',
                header: 'New / changed / conflicts / errors',
                render: (b) =>
                  `${b.counts.CREATE} / ${b.counts.UPDATE} / ${b.counts.CONFLICT} / ${b.counts.ERROR}`,
              },
              {
                key: 'actions',
                header: 'Actions',
                render: (b) =>
                  b.id === latestCommitted ? <RollbackButton onConfirm={() => void rollback(b.id)} /> : null,
              },
            ]}
          />
        )}
      </section>
    </>
  );
}

function RollbackButton({ onConfirm }: { onConfirm: () => void }) {
  const [asking, setAsking] = useState(false);
  return asking ? (
    <span className="form__actions">
      <Button variant="danger" onClick={onConfirm}>
        Yes, roll back
      </Button>
      <Button variant="secondary" onClick={() => setAsking(false)}>
        Cancel
      </Button>
    </span>
  ) : (
    <Button variant="quiet" onClick={() => setAsking(true)}>
      Roll back
    </Button>
  );
}

function ImportRows({ rows, total }: { rows: ImportRow[]; total: number }) {
  const describe = (r: ImportRow) =>
    r.problems.length > 0
      ? r.problems.join(' ')
      : r.changes
          .map((c) =>
            c.before === ''
              ? `${fieldText[c.field] ?? c.field}: ${c.after}`
              : `${fieldText[c.field] ?? c.field}: ${c.before} → ${c.after}`,
          )
          .join('; ');
  return (
    <>
      <DataTable
        caption="Rows needing attention first"
        rows={rows}
        rowKey={(r) => String(r.rowNo)}
        empty={<EmptyState title="The file has no student rows" />}
        columns={[
          { key: 'row', header: 'Row', render: (r) => r.rowNo, numeric: true },
          { key: 'ref', header: 'Admission no.', render: (r) => r.externalRef || '—' },
          {
            key: 'action',
            header: 'Result',
            render: (r) => <StatusBadge tone={actionTone[r.action]}>{actionText[r.action]}</StatusBadge>,
          },
          { key: 'detail', header: 'Details', render: describe },
        ]}
      />
      {total > rows.length && (
        <p className="muted">
          Showing the first {rows.length} of {total} rows (errors and conflicts first).
        </p>
      )}
    </>
  );
}
