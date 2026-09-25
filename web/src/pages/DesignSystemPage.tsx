import { useState } from 'react';
import { ApiError } from '../api/client';
import { Button } from '../components/Button';
import { DataTable } from '../components/DataTable';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatPaise } from '../lib/format';

const sample = [
  { id: '1', item: 'Term 2 tuition', due: 4_250_000 },
  { id: '2', item: 'Transport, route 7', due: 1_180_000 },
  { id: '3', item: 'Science lab', due: 95_000 },
];

/** Living reference for reviewers. Examples use synthetic values only. */
export function DesignSystemPage() {
  const [name, setName] = useState('');
  return (
    <>
      <PageHeader title="Design system" lead="Components, states and tokens used across Schoomano." />

      <section className="section" aria-labelledby="ds-buttons">
        <h2 id="ds-buttons" className="section__title">
          Actions
        </h2>
        <div className="row">
          <Button variant="primary">Publish notice</Button>
          <Button variant="secondary">Save draft</Button>
          <Button variant="danger">Withdraw offer</Button>
          <Button variant="quiet">Cancel</Button>
          <Button variant="primary" busy>
            Saving
          </Button>
        </div>
      </section>

      <section className="section" aria-labelledby="ds-status">
        <h2 id="ds-status" className="section__title">
          Status
        </h2>
        <div className="row">
          <StatusBadge tone="success">Present</StatusBadge>
          <StatusBadge tone="warning">Late</StatusBadge>
          <StatusBadge tone="danger">Absent, not notified</StatusBadge>
          <StatusBadge tone="info">Draft</StatusBadge>
          <StatusBadge tone="neutral">Archived</StatusBadge>
        </div>
      </section>

      <section className="section" aria-labelledby="ds-forms">
        <h2 id="ds-forms" className="section__title">
          Form fields
        </h2>
        <div className="form">
          <Field label="Guardian name" hint="As printed on the ID document" required>
            {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
          </Field>
          <Field label="Mobile number" error="Enter a 10-digit Indian mobile number." required>
            {(p) => <input {...p} type="tel" inputMode="tel" defaultValue="98450" />}
          </Field>
        </div>
      </section>

      <section className="section" aria-labelledby="ds-table">
        <h2 id="ds-table" className="section__title">
          Table
        </h2>
        <DataTable
          caption="Fees due (synthetic example)"
          rows={sample}
          rowKey={(r) => r.id}
          empty={<EmptyState title="No fees due" />}
          columns={[
            { key: 'item', header: 'Item', render: (r) => r.item, sortValue: (r) => r.item },
            {
              key: 'due',
              header: 'Amount',
              render: (r) => formatPaise(r.due),
              sortValue: (r) => r.due,
              numeric: true,
            },
          ]}
        />
      </section>

      <section className="section" aria-labelledby="ds-states">
        <h2 id="ds-states" className="section__title">
          Loading, empty and error states
        </h2>
        <LoadingState label="Loading attendance" />
        <EmptyState title="No notices yet" action={<Button variant="primary">Write a notice</Button>}>
          <p>Notices you send to a class or campus appear here with their delivery status.</p>
        </EmptyState>
        <ErrorState
          error={
            new ApiError(0, 'network_unreachable', 'Check your connection and try again.', 'example-3f2a9c1d')
          }
          onRetry={() => undefined}
        />
      </section>
    </>
  );
}
