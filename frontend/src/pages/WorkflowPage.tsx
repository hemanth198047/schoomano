import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { Campus, Me, WorkflowChain, WorkflowRequest } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDateTime } from '../lib/format';

const has = (me: Me, ...keys: string[]) => me.roles.some((r) => keys.includes(r.roleKey));
export const canDesignWorkflow = (me: Me) => has(me, 'campus_admin', 'group_admin');
export const canSubmitWorkflow = (me: Me) =>
  has(me, 'campus_admin', 'group_admin', 'principal', 'teacher', 'finance_officer');
export const canDecideWorkflow = (me: Me) =>
  has(me, 'campus_admin', 'group_admin', 'principal', 'finance_officer');
export const canSeeAllWorkflow = (me: Me) => has(me, 'campus_admin', 'group_admin', 'principal');

const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');
const STATUS: Record<
  WorkflowRequest['status'],
  { label: string; tone: 'success' | 'warning' | 'danger' | 'info' | 'neutral' }
> = {
  PENDING: { label: 'Waiting', tone: 'warning' },
  APPROVED: { label: 'Approved', tone: 'success' },
  REJECTED: { label: 'Rejected', tone: 'danger' },
  WITHDRAWN: { label: 'Withdrawn', tone: 'neutral' },
};

export function WorkflowPage({
  me,
  campuses,
  selectedCampusId,
}: {
  me: Me;
  campuses: Campus[];
  selectedCampusId: string | null;
}) {
  const [chosenCampus, setChosenCampus] = useState<string | null>(null);
  const campusId = selectedCampusId ?? chosenCampus ?? campuses[0]?.id ?? null;
  return (
    <>
      <PageHeader
        title="Approvals"
        lead="Requests that need sign-off, step by step, with a permanent record of who decided."
      />
      {!canSubmitWorkflow(me) ? (
        <EmptyState title="Not available">
          <p>Approvals are for school staff.</p>
        </EmptyState>
      ) : (
        <>
          {selectedCampusId === null && campuses.length > 1 && (
            <div className="form form--inline">
              <Field label="Campus">
                {(p) => (
                  <select {...p} value={campusId ?? ''} onChange={(e) => setChosenCampus(e.target.value)}>
                    {campuses.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
            </div>
          )}
          {campusId === null ? (
            <EmptyState title="No campus yet" />
          ) : (
            <Body key={campusId} me={me} campusId={campusId} />
          )}
        </>
      )}
    </>
  );
}

function Body({ me, campusId }: { me: Me; campusId: string }) {
  const base = `/api/v1/campuses/${campusId}`;
  const [mine, reloadMine] = useApiResource<WorkflowRequest[]>(`${base}/workflow-requests?view=mine`);
  const [inbox, reloadInbox] = useApiResource<WorkflowRequest[]>(
    canDecideWorkflow(me) ? `${base}/workflow-requests?view=inbox` : null,
  );
  const [chains, reloadChains] = useApiResource<WorkflowChain[]>(`${base}/workflow-chains`);
  const reload = () => {
    reloadMine();
    reloadInbox();
    reloadChains();
  };
  return (
    <>
      {canDecideWorkflow(me) && (
        <section aria-labelledby="inbox-h">
          <h2 id="inbox-h">Waiting for you</h2>
          {inbox.status === 'loading' && <LoadingState label="Loading inbox" />}
          {inbox.status === 'error' && <ErrorState error={inbox.error} onRetry={reloadInbox} />}
          {inbox.status === 'ready' &&
            (inbox.data.length === 0 ? (
              <EmptyState title="Nothing waiting for you" />
            ) : (
              <ul className="plain-list">
                {inbox.data.map((r) => (
                  <li key={r.id}>
                    <RequestRow request={r} />
                    <Decide request={r} onDone={reload} />
                  </li>
                ))}
              </ul>
            ))}
        </section>
      )}
      <section aria-labelledby="mine-h">
        <h2 id="mine-h">My requests</h2>
        {mine.status === 'loading' && <LoadingState label="Loading requests" />}
        {mine.status === 'error' && <ErrorState error={mine.error} onRetry={reloadMine} />}
        {mine.status === 'ready' &&
          (mine.data.length === 0 ? (
            <EmptyState title="You have not asked for anything yet" />
          ) : (
            <ul className="plain-list">
              {mine.data.map((r) => (
                <li key={r.id}>
                  <RequestRow request={r} />
                  {r.status === 'PENDING' && <Withdraw request={r} onDone={reload} />}
                </li>
              ))}
            </ul>
          ))}
      </section>
      {chains.status === 'ready' && (
        <NewRequest
          campusId={campusId}
          chains={chains.data.filter((c) => c.status === 'PUBLISHED')}
          onSaved={reload}
        />
      )}
      {canDesignWorkflow(me) && chains.status === 'ready' && (
        <Design campusId={campusId} chains={chains.data} onSaved={reload} />
      )}
    </>
  );
}

function RequestRow({ request: r }: { request: WorkflowRequest }) {
  return (
    <div>
      <p>
        <strong>{r.subject}</strong>{' '}
        <StatusBadge tone={STATUS[r.status].tone}>{STATUS[r.status].label}</StatusBadge>{' '}
        <span className="muted">
          {r.chainName}, from {r.requester}, sent {formatDateTime(r.submittedAt)}
          {r.waitingFor ? `, waiting for ${r.waitingFor}` : ''}
        </span>
      </p>
      {r.details && <p>{r.details}</p>}
      <ol aria-label={`Steps for ${r.subject}`}>
        {r.steps.map((s) => (
          <li key={s.position}>
            {s.label}:{' '}
            {s.decision
              ? `${s.decision === 'APPROVED' ? 'approved' : 'rejected'} by ${s.decidedBy}${s.note ? `, ${s.note}` : ''}`
              : 'not decided'}
          </li>
        ))}
      </ol>
    </div>
  );
}

function Decide({ request, onDone }: { request: WorkflowRequest; onDone: () => void }) {
  const session = useApiSession();
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  async function decide(decision: 'APPROVED' | 'REJECTED') {
    setError(null);
    if (decision === 'REJECTED' && note.trim() === '') {
      setError('Say why you are rejecting this request.');
      return;
    }
    try {
      await apiRequest(session, `/api/v1/workflow-requests/${request.id}/decision`, {
        method: 'POST',
        body: { decision, note: note.trim() || undefined },
      });
      setNote('');
      onDone();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <div className="form form--inline">
      <Field label={`Note for ${request.subject}`} hint="Required when rejecting">
        {(p) => <input {...p} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
      <Button onClick={() => void decide('APPROVED')}>{`Approve ${request.subject}`}</Button>
      <Button variant="quiet" onClick={() => void decide('REJECTED')}>{`Reject ${request.subject}`}</Button>
      {error && <p role="alert">{error}</p>}
    </div>
  );
}

function Withdraw({ request, onDone }: { request: WorkflowRequest; onDone: () => void }) {
  const session = useApiSession();
  const [error, setError] = useState<string | null>(null);
  async function withdraw() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/workflow-requests/${request.id}/withdraw`, { method: 'POST' });
      onDone();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <>
      <Button variant="quiet" onClick={() => void withdraw()}>{`Withdraw ${request.subject}`}</Button>
      {error && <p role="alert">{error}</p>}
    </>
  );
}

function NewRequest({
  campusId,
  chains,
  onSaved,
}: {
  campusId: string;
  chains: WorkflowChain[];
  onSaved: () => void;
}) {
  const session = useApiSession();
  const [chainId, setChainId] = useState('');
  const [subject, setSubject] = useState('');
  const [details, setDetails] = useState('');
  const [error, setError] = useState<string | null>(null);
  if (chains.length === 0) return null;
  async function submit() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/campuses/${campusId}/workflow-requests`, {
        method: 'POST',
        body: {
          chainId: chainId || chains[0]?.id,
          subject: subject.trim(),
          details: details.trim() || undefined,
        },
      });
      setSubject('');
      setDetails('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <section aria-labelledby="new-h">
      <h2 id="new-h">Ask for approval</h2>
      <div className="form">
        <Field label="Approval chain">
          {(p) => (
            <select {...p} value={chainId || chains[0]?.id} onChange={(e) => setChainId(e.target.value)}>
              {chains.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.steps.map((s) => s.label).join(', then ')})
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="What you need approved">
          {(p) => <input {...p} value={subject} onChange={(e) => setSubject(e.target.value)} />}
        </Field>
        <Field label="Details" hint="Optional">
          {(p) => <textarea {...p} value={details} onChange={(e) => setDetails(e.target.value)} />}
        </Field>
        {error && <p role="alert">{error}</p>}
        <Button onClick={() => void submit()} disabled={subject.trim().length < 3}>
          Send for approval
        </Button>
      </div>
    </section>
  );
}

function Design({
  campusId,
  chains,
  onSaved,
}: {
  campusId: string;
  chains: WorkflowChain[];
  onSaved: () => void;
}) {
  const session = useApiSession();
  const [key, setKey] = useState('');
  const [name, setName] = useState('');
  const [steps, setSteps] = useState('');
  const [error, setError] = useState<string | null>(null);

  // One step per line: "role_key: Label".
  const parsed = steps
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => {
      const [roleKey, ...rest] = l.split(':');
      return { roleKey: (roleKey ?? '').trim(), label: rest.join(':').trim() };
    });

  async function draft() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/campuses/${campusId}/workflow-chains`, {
        method: 'POST',
        body: { chainKey: key.trim(), name: name.trim(), steps: parsed },
      });
      setKey('');
      setName('');
      setSteps('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  async function publish(id: string) {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/workflow-chains/${id}/publish`, { method: 'POST' });
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <section aria-labelledby="design-h">
      <h2 id="design-h">Approval chains</h2>
      <ul className="plain-list">
        {chains.map((c) => (
          <li key={c.id}>
            <strong>{c.name}</strong> version {c.version}{' '}
            <StatusBadge tone={c.status === 'PUBLISHED' ? 'success' : 'neutral'}>
              {c.status === 'PUBLISHED' ? 'Published' : c.status === 'DRAFT' ? 'Draft' : 'Retired'}
            </StatusBadge>{' '}
            <span className="muted">{c.steps.map((s) => s.label).join(', then ')}</span>
            {c.status === 'DRAFT' && (
              <>
                {' '}
                <Button
                  variant="quiet"
                  onClick={() => void publish(c.id)}
                >{`Publish ${c.name} version ${c.version}`}</Button>
              </>
            )}
          </li>
        ))}
      </ul>
      <div className="form">
        <Field label="Chain key" hint="Lower case letters, numbers and hyphens">
          {(p) => <input {...p} value={key} onChange={(e) => setKey(e.target.value)} />}
        </Field>
        <Field label="Chain name">
          {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Steps" hint="One per line, like principal: Principal">
          {(p) => <textarea {...p} value={steps} onChange={(e) => setSteps(e.target.value)} />}
        </Field>
        {error && <p role="alert">{error}</p>}
        <Button
          onClick={() => void draft()}
          disabled={key.trim() === '' || name.trim() === '' || parsed.length === 0}
        >
          Save as draft
        </Button>
      </div>
    </section>
  );
}
