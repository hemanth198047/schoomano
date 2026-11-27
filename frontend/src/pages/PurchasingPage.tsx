import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  WorkflowChain,
  AcademicStructure,
  Budget,
  Campus,
  Me,
  PurchaseRequest,
  PurchaseStatus,
  Vendor,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatPaise, rupeesToPaise } from '../lib/format';

const has = (me: Me, ...keys: string[]) => me.roles.some((r) => keys.includes(r.roleKey));
export const canRaise = (me: Me) => has(me, 'teacher', 'campus_admin', 'principal', 'finance_officer');
export const canSetBudgets = (me: Me) => has(me, 'finance_officer', 'principal', 'group_admin');
export const canApprove = (me: Me) => has(me, 'campus_admin', 'principal');
export const canPay = (me: Me) => has(me, 'finance_officer');
export const canSeePurchasing = (me: Me) => canRaise(me) || canSetBudgets(me);

const STATUS: Record<PurchaseStatus, string> = {
  SUBMITTED: 'Waiting for approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
  PAID: 'Paid',
};
const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');

export function PurchasingPage({
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
  const allowed = canSeePurchasing(me);
  const [structure] = useApiResource<AcademicStructure>(
    allowed && campusId !== null ? `/api/v1/campuses/${campusId}/academic-structure` : null,
  );
  return (
    <>
      <PageHeader
        title="Purchasing"
        lead="Budgets, purchase requests and vendor payments. Someone other than the requester approves a request, and approval stops at the budget."
      />
      {!allowed ? (
        <EmptyState title="Not available">
          <p>Purchasing is for school staff.</p>
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
          {structure.status === 'loading' && <LoadingState label="Loading" />}
          {structure.status === 'error' && <ErrorState error={structure.error} />}
          {structure.status === 'ready' && (!structure.data.year || campusId === null) && (
            <EmptyState title="No academic year yet" />
          )}
          {structure.status === 'ready' && structure.data.year && campusId !== null && (
            <Body key={structure.data.year.id} me={me} campusId={campusId} yearId={structure.data.year.id} />
          )}
        </>
      )}
    </>
  );
}

function Body({ me, campusId, yearId }: { me: Me; campusId: string; yearId: string }) {
  const [budgets, reloadBudgets] = useApiResource<Budget[]>(`/api/v1/academic-years/${yearId}/budgets`);
  const [requests, reloadRequests] = useApiResource<PurchaseRequest[]>(
    `/api/v1/academic-years/${yearId}/purchase-requests`,
  );
  const [vendors, reloadVendors] = useApiResource<Vendor[]>(`/api/v1/campuses/${campusId}/vendors`);
  const [chains] = useApiResource<WorkflowChain[]>(`/api/v1/campuses/${campusId}/workflow-chains`);
  const refresh = () => {
    reloadBudgets();
    reloadRequests();
  };
  return (
    <>
      <section aria-labelledby="budgets-h">
        <h2 id="budgets-h">Budgets</h2>
        {budgets.status === 'loading' && <LoadingState label="Loading budgets" />}
        {budgets.status === 'error' && <ErrorState error={budgets.error} onRetry={reloadBudgets} />}
        {budgets.status === 'ready' &&
          (budgets.data.length === 0 ? (
            <EmptyState title="No budgets yet" />
          ) : (
            <ul className="plain-list">
              {budgets.data.map((b) => (
                <li key={b.id}>
                  <strong>{b.category}</strong>: {formatPaise(b.committedPaise)} approved of{' '}
                  {formatPaise(b.amountPaise)} ({formatPaise(b.amountPaise - b.committedPaise)} left),{' '}
                  {formatPaise(b.paidPaise)} paid
                </li>
              ))}
            </ul>
          ))}
        {canSetBudgets(me) && <BudgetForm yearId={yearId} onSaved={reloadBudgets} />}
        {canSetBudgets(me) && <VendorForm campusId={campusId} onSaved={reloadVendors} />}
      </section>
      {canRaise(me) && budgets.status === 'ready' && (
        <Raise
          yearId={yearId}
          budgets={budgets.data}
          vendors={vendors.status === 'ready' ? vendors.data : []}
          chains={chains.status === 'ready' ? chains.data.filter((c) => c.status === 'PUBLISHED') : []}
          onSaved={refresh}
        />
      )}
      <section aria-labelledby="requests-h">
        <h2 id="requests-h">Requests</h2>
        {requests.status === 'loading' && <LoadingState label="Loading requests" />}
        {requests.status === 'error' && <ErrorState error={requests.error} onRetry={reloadRequests} />}
        {requests.status === 'ready' &&
          (requests.data.length === 0 ? (
            <EmptyState title="No requests" />
          ) : (
            <ul className="plain-list">
              {requests.data.map((r) => (
                <RequestRow key={r.id} me={me} r={r} onChanged={refresh} />
              ))}
            </ul>
          ))}
      </section>
    </>
  );
}

function BudgetForm({ yearId, onSaved }: { yearId: string; onSaved: () => void }) {
  const session = useApiSession();
  const [category, setCategory] = useState('');
  const [amount, setAmount] = useState('');
  const [error, setError] = useState<string | null>(null);
  const paise = rupeesToPaise(amount);
  async function save() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/academic-years/${yearId}/budgets`, {
        method: 'PUT',
        body: { category: category.trim(), amountPaise: paise },
      });
      setCategory('');
      setAmount('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <div>
      <h3>Set a budget</h3>
      <div className="form form--inline">
        <Field label="Budget category">
          {(p) => <input {...p} value={category} onChange={(e) => setCategory(e.target.value)} />}
        </Field>
        <Field label="Budget amount (₹)">
          {(p) => (
            <input {...p} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
          )}
        </Field>
      </div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <p className="form__actions">
        <Button disabled={category.trim().length < 2 || paise === null} onClick={() => void save()}>
          Save budget
        </Button>
      </p>
    </div>
  );
}

function VendorForm({ campusId, onSaved }: { campusId: string; onSaved: () => void }) {
  const session = useApiSession();
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  async function add() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/campuses/${campusId}/vendors`, {
        method: 'POST',
        body: { name: name.trim() },
      });
      setName('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <div>
      <h3>Add a vendor</h3>
      <div className="form form--inline">
        <Field label="Vendor name">
          {(p) => <input {...p} value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
      </div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <p className="form__actions">
        <Button disabled={name.trim().length < 2} onClick={() => void add()}>
          Add vendor
        </Button>
      </p>
    </div>
  );
}

function Raise({
  yearId,
  budgets,
  vendors,
  chains,
  onSaved,
}: {
  yearId: string;
  budgets: Budget[];
  vendors: Vendor[];
  chains: WorkflowChain[];
  onSaved: () => void;
}) {
  const session = useApiSession();
  const [budgetId, setBudgetId] = useState('');
  const [vendorId, setVendorId] = useState('');
  const [chainId, setChainId] = useState('');
  const [title, setTitle] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const paise = rupeesToPaise(amount);
  async function send() {
    setBusy(true);
    setError(null);
    try {
      await apiRequest(session, `/api/v1/academic-years/${yearId}/purchase-requests`, {
        method: 'POST',
        body: {
          budgetId,
          vendorId: vendorId || undefined,
          title: title.trim(),
          description: description.trim() || undefined,
          amountPaise: paise,
          chainId: chainId || undefined,
        },
      });
      setTitle('');
      setAmount('');
      setDescription('');
      onSaved();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section aria-labelledby="raise-h">
      <h2 id="raise-h">Raise a purchase request</h2>
      <div className="form form--inline">
        <Field label="Budget">
          {(p) => (
            <select {...p} value={budgetId} onChange={(e) => setBudgetId(e.target.value)}>
              <option value="">Choose a budget</option>
              {budgets.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.category}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Vendor" hint="Optional">
          {(p) => (
            <select {...p} value={vendorId} onChange={(e) => setVendorId(e.target.value)}>
              <option value="">No vendor yet</option>
              {vendors.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        {chains.length > 0 && (
          <Field
            label="Approval chain"
            hint="Optional. Without one, the principal or office approves directly"
          >
            {(p) => (
              <select {...p} value={chainId} onChange={(e) => setChainId(e.target.value)}>
                <option value="">Approve directly</option>
                {chains.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name} ({c.steps.map((st) => st.label).join(', then ')})
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        <Field label="What is needed">
          {(p) => <input {...p} value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />}
        </Field>
        <Field label="Amount (₹)" hint="Above ₹50,000 the principal must approve">
          {(p) => (
            <input {...p} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
          )}
        </Field>
        <Field label="Why" hint="Optional">
          {(p) => (
            <textarea {...p} rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
          )}
        </Field>
      </div>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <p className="form__actions">
        <Button
          variant="primary"
          busy={busy}
          disabled={!budgetId || title.trim().length < 3 || paise === null}
          onClick={() => void send()}
        >
          Send request
        </Button>
      </p>
    </section>
  );
}

function RequestRow({ me, r, onChanged }: { me: Me; r: PurchaseRequest; onChanged: () => void }) {
  const session = useApiSession();
  const [note, setNote] = useState('');
  const [reference, setReference] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const mine = r.requestedBy === me.displayName;

  async function call(path: string, body: unknown) {
    setBusy(true);
    setError(null);
    try {
      await apiRequest(session, `/api/v1/purchase-requests/${r.id}/${path}`, { method: 'POST', body });
      setNote('');
      setReference('');
      onChanged();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  const tone = r.status === 'SUBMITTED' ? 'warning' : r.status === 'REJECTED' ? 'danger' : 'info';
  return (
    <li>
      <p>
        <strong>{r.title}</strong> {formatPaise(r.amountPaise)}{' '}
        <span className="muted">
          {r.category}
          {r.vendor ? `, ${r.vendor}` : ''}, by {r.requestedBy}
        </span>{' '}
        <StatusBadge tone={tone}>{STATUS[r.status]}</StatusBadge>
        {r.needsPrincipal && r.status === 'SUBMITTED' && <> (needs the principal)</>}
      </p>
      {r.paymentReference && (
        <p className="muted">
          Paid {formatPaise(r.paidPaise ?? 0)}, ref {r.paymentReference}
        </p>
      )}
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      {r.status === 'SUBMITTED' && (
        <div>
          {r.workflowRequestId && (
            <p className="muted">
              In the approval chain{r.waitingFor ? `, waiting for ${r.waitingFor}` : ''}. Decide it on the
              Approvals page.
            </p>
          )}
          {canApprove(me) && !mine && !r.workflowRequestId && (
            <>
              <Field label={`Note for ${r.title}`} hint="Required to reject">
                {(p) => <input {...p} value={note} onChange={(e) => setNote(e.target.value)} />}
              </Field>
              <p className="form__actions">
                <Button busy={busy} onClick={() => void call('decision', { decision: 'APPROVE', note })}>
                  {`Approve ${r.title}`}
                </Button>{' '}
                <Button
                  variant="quiet"
                  busy={busy}
                  disabled={note.trim().length < 5}
                  onClick={() => void call('decision', { decision: 'REJECT', note })}
                >
                  {`Reject ${r.title}`}
                </Button>
              </p>
            </>
          )}
          {mine && (
            <Button variant="quiet" busy={busy} onClick={() => void call('decision', { decision: 'CANCEL' })}>
              {`Withdraw ${r.title}`}
            </Button>
          )}
        </div>
      )}
      {r.status === 'APPROVED' && canPay(me) && (
        <div>
          <Field label={`Payment reference for ${r.title}`} hint="Bank or cheque reference">
            {(p) => <input {...p} value={reference} onChange={(e) => setReference(e.target.value)} />}
          </Field>
          <p className="form__actions">
            <Button
              busy={busy}
              disabled={reference.trim().length < 2}
              onClick={() =>
                void call('payment', {
                  amountPaise: r.amountPaise,
                  paidOn: new Date().toISOString().slice(0, 10),
                  reference,
                })
              }
            >
              {`Record payment for ${r.title}`}
            </Button>
          </p>
        </div>
      )}
    </li>
  );
}
