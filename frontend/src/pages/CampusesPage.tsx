import type { Campus } from '../api/types';
import { useApiResource } from '../app/useApiResource';
import { DataTable } from '../components/DataTable';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';

const statusTone = { ACTIVE: 'success', SETUP: 'warning', ARCHIVED: 'neutral' } as const;
const statusText = { ACTIVE: 'Active', SETUP: 'In setup', ARCHIVED: 'Archived' } as const;

export function CampusesPage() {
  const [campuses, reload] = useApiResource<Campus[]>('/api/v1/campuses');
  return (
    <>
      <PageHeader title="Campuses" lead="Campuses you can see in this school group." />
      {campuses.status === 'loading' && <LoadingState label="Loading campuses" />}
      {campuses.status === 'error' && <ErrorState error={campuses.error} onRetry={reload} />}
      {campuses.status === 'ready' && (
        <DataTable
          caption="Campuses"
          rows={campuses.data}
          rowKey={(c) => c.id}
          empty={
            <EmptyState title="No campuses to show">
              <p>You have no campus-level role. A group administrator can grant one.</p>
            </EmptyState>
          }
          columns={[
            { key: 'name', header: 'Campus', render: (c) => c.name, sortValue: (c) => c.name },
            { key: 'code', header: 'Code', render: (c) => <code>{c.code}</code>, sortValue: (c) => c.code },
            {
              key: 'entity',
              header: 'Legal entity',
              render: (c) => c.legalEntity,
              sortValue: (c) => c.legalEntity,
            },
            { key: 'year', header: 'Current year', render: (c) => c.activeYear ?? 'None' },
            {
              key: 'status',
              header: 'Status',
              render: (c) => <StatusBadge tone={statusTone[c.status]}>{statusText[c.status]}</StatusBadge>,
              sortValue: (c) => c.status,
            },
          ]}
        />
      )}
    </>
  );
}
