import { useState, type FormEvent } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  Campus,
  InvoiceDetail,
  InvoiceSummary,
  Me,
  PaymentOrder,
  Reconciliation,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDay, formatPaise, rupeesToPaise } from '../lib/format';

const FEE_STAFF = ['group_admin', 'campus_admin', 'finance_officer'];
const FEE_MANAGERS = ['group_admin', 'finance_officer'];

/** Who sees the office screens; the server enforces FEE_READ and FEE_MANAGE regardless. */
export const isFeeStaff = (me: Me) => me.roles.some((r) => FEE_STAFF.includes(r.roleKey));
export const canManageFees = (me: Me) => me.roles.some((r) => FEE_MANAGERS.includes(r.roleKey));
export const payingChildren = (me: Me) => me.children.filter((c) => c.capabilities.includes('PAY_FEES'));

const tone = (s: InvoiceSummary) =>
  s.status === 'PAID' ? 'success' : s.status === 'CANCELLED' ? 'neutral' : s.overdue ? 'danger' : 'warning';
const label = (s: InvoiceSummary) =>
  s.status === 'PARTIALLY_PAID'
    ? 'Part paid'
    : s.status === 'ISSUED'
      ? s.overdue
        ? 'Overdue'
        : 'Due'
      : s.status === 'PAID'
        ? 'Paid'
        : 'Cancelled';
const newKey = () => `web-${crypto.randomUUID()}`;
const oops = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong.');

export function FeesPage({
  me,
  campuses,
  selectedCampusId,
}: {
  me: Me;
  campuses: Campus[];
  selectedCampusId: string | null;
}) {
  const children = payingChildren(me);
  const staff = isFeeStaff(me);
  return (
    <>
      <PageHeader title="Fees" lead="Invoices, payments and what is still due." />
      {children.length > 0 && <ParentFees children={children} />}
      {staff && <OfficeFees me={me} campuses={campuses} selectedCampusId={selectedCampusId} />}
      {!staff && children.length === 0 && (
        <EmptyState title="Nothing to show">
          <p>Fees appear here for children whose guardian can pay fees.</p>
        </EmptyState>
      )}
    </>
  );
}

function ParentFees({ children }: { children: Me['children'] }) {
  return (
    <section aria-labelledby="my-fees-h">
      <h2 id="my-fees-h">Your fees</h2>
      {children.map((c) => (
        <ChildInvoices key={c.studentId} studentId={c.studentId} name={c.displayName} />
      ))}
    </section>
  );
}

function ChildInvoices({ studentId, name }: { studentId: string; name: string }) {
  const session = useApiSession();
  const [list, reload] = useApiResource<InvoiceSummary[]>(`/api/v1/students/${studentId}/invoices`);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function pay(inv: InvoiceSummary) {
    setBusy(inv.id);
    setMessage(null);
    setError(null);
    try {
      const order = await apiRequest<PaymentOrder>(session, `/api/v1/invoices/${inv.id}/pay`, {
        method: 'POST',
        body: {},
        headers: { 'Idempotency-Key': newKey() },
      });
      if (!order.sandbox) {
        setMessage('Your payment has started. It will show here once the bank confirms it.');
      } else {
        await apiRequest(session, '/api/v1/payments/sandbox/complete', {
          method: 'POST',
          body: { orderId: order.orderId, outcome: 'SUCCEEDED' },
        });
        setMessage(`Test payment of ${formatPaise(order.amountPaise)} recorded.`);
      }
    } catch (e) {
      setError(oops(e));
    } finally {
      setBusy(null);
      reload();
    }
  }

  return (
    <div>
      <h3>{name}</h3>
      {list.status === 'loading' && <LoadingState label={`Loading fees for ${name}`} />}
      {list.status === 'error' && <ErrorState error={list.error} onRetry={reload} />}
      {list.status === 'ready' &&
        (list.data.length === 0 ? (
          <p className="muted">No invoices yet.</p>
        ) : (
          <ul className="plain-list">
            {list.data.map((i) => (
              <li key={i.id}>
                <strong>{i.number}</strong> <StatusBadge tone={tone(i)}>{label(i)}</StatusBadge>
                <br />
                {formatPaise(i.paidPaise)} paid of {formatPaise(i.totalPaise)}, due {formatDay(i.dueOn)}
                {(i.status === 'ISSUED' || i.status === 'PARTIALLY_PAID') && (
                  <>
                    {' '}
                    <Button
                      variant="primary"
                      busy={busy === i.id}
                      aria-label={`Pay ${formatPaise(i.totalPaise - i.paidPaise)} for ${i.number}`}
                      onClick={() => void pay(i)}
                    >
                      {`Pay ${formatPaise(i.totalPaise - i.paidPaise)}`}
                    </Button>
                  </>
                )}
              </li>
            ))}
          </ul>
        ))}
      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
    </div>
  );
}

function OfficeFees({
  me,
  campuses,
  selectedCampusId,
}: {
  me: Me;
  campuses: Campus[];
  selectedCampusId: string | null;
}) {
  const [chosen, setChosen] = useState<string | null>(null);
  const campusId = selectedCampusId ?? chosen ?? campuses[0]?.id ?? null;
  const [structure] = useApiResource<AcademicStructure>(
    campusId === null ? null : `/api/v1/campuses/${campusId}/academic-structure`,
  );
  const year = structure.status === 'ready' ? structure.data.year : null;
  const manage = canManageFees(me);
  return (
    <section aria-labelledby="office-h">
      <h2 id="office-h">School office</h2>
      {selectedCampusId === null && campuses.length > 1 && (
        <Field label="Campus">
          {(p) => (
            <select {...p} value={campusId ?? ''} onChange={(e) => setChosen(e.target.value)}>
              {campuses.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          )}
        </Field>
      )}
      {structure.status === 'loading' && <LoadingState label="Loading the academic year" />}
      {structure.status === 'error' && <ErrorState error={structure.error} />}
      {structure.status === 'ready' && year === null && (
        <EmptyState title="No active academic year">
          <p>Set up an academic year before issuing invoices.</p>
        </EmptyState>
      )}
      {structure.status === 'ready' && year !== null && campusId !== null && (
        <>
          <Invoices yearId={year.id} manage={manage} />
          {manage && <IssueToClass yearId={year.id} structure={structure.data} />}
          <ReconciliationPanel campusId={campusId} />
        </>
      )}
    </section>
  );
}

function Invoices({ yearId, manage }: { yearId: string; manage: boolean }) {
  const session = useApiSession();
  const [status, setStatus] = useState('');
  const [list, reload] = useApiResource<InvoiceSummary[]>(
    `/api/v1/academic-years/${yearId}/invoices${status ? `?status=${status}` : ''}`,
  );
  const [open, setOpen] = useState<string | null>(null);
  const [detail, reloadDetail] = useApiResource<InvoiceDetail>(open ? `/api/v1/invoices/${open}` : null);
  const [amount, setAmount] = useState('');
  const [method, setMethod] = useState('CASH');
  const [reference, setReference] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const paise = rupeesToPaise(amount);
  const ready = paise !== null && (method !== 'CHEQUE' || reference.trim() !== '');

  async function record(e: FormEvent) {
    e.preventDefault();
    if (!open || paise === null) return;
    setBusy(true);
    setMessage(null);
    setError(null);
    try {
      await apiRequest(session, `/api/v1/invoices/${open}/payments/offline`, {
        method: 'POST',
        body: { amountPaise: paise, method, reference: reference.trim() || null },
        headers: { 'Idempotency-Key': newKey() },
      });
      setMessage(`Recorded ${formatPaise(paise)}.`);
      setAmount('');
      setReference('');
      reload();
      reloadDetail();
    } catch (err) {
      setError(oops(err));
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    if (!open) return;
    const reason = window.prompt('Why is this invoice being cancelled?');
    if (!reason || reason.trim().length < 3) return;
    setError(null);
    try {
      await apiRequest(session, `/api/v1/invoices/${open}/cancel`, { method: 'POST', body: { reason } });
      reload();
      reloadDetail();
    } catch (err) {
      setError(oops(err));
    }
  }

  return (
    <div>
      <h3>Invoices</h3>
      <Field label="Show">
        {(p) => (
          <select {...p} value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All</option>
            <option value="ISSUED">Not paid</option>
            <option value="PARTIALLY_PAID">Part paid</option>
            <option value="PAID">Paid</option>
            <option value="CANCELLED">Cancelled</option>
          </select>
        )}
      </Field>
      {list.status === 'loading' && <LoadingState label="Loading invoices" />}
      {list.status === 'error' && <ErrorState error={list.error} onRetry={reload} />}
      {list.status === 'ready' &&
        (list.data.length === 0 ? (
          <EmptyState title="No invoices">
            <p>Nothing matches yet.</p>
          </EmptyState>
        ) : (
          <table>
            <caption className="visually-hidden">Invoices</caption>
            <thead>
              <tr>
                <th scope="col">Invoice</th>
                <th scope="col">Student</th>
                <th scope="col">Due</th>
                <th scope="col">Paid</th>
                <th scope="col">Status</th>
                <th scope="col">
                  <span className="visually-hidden">Open</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((i) => (
                <tr key={i.id}>
                  <th scope="row">{i.number}</th>
                  <td>{i.studentName}</td>
                  <td>{formatDay(i.dueOn)}</td>
                  <td>
                    {formatPaise(i.paidPaise)} / {formatPaise(i.totalPaise)}
                  </td>
                  <td>
                    <StatusBadge tone={tone(i)}>{label(i)}</StatusBadge>
                  </td>
                  <td>
                    <Button
                      variant="quiet"
                      aria-label={`Open ${i.number}`}
                      onClick={() => {
                        setOpen(i.id);
                        setMessage(null);
                        setError(null);
                      }}
                    >
                      Open
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
      {open && detail.status === 'ready' && (
        <div role="region" aria-label={`Invoice ${detail.data.invoice.number}`}>
          <h4>{detail.data.invoice.number}</h4>
          <ul className="plain-list">
            {detail.data.lines.map((l, n) => (
              <li key={n}>
                {l.description}: {formatPaise(l.amountPaise)}
              </li>
            ))}
          </ul>
          {detail.data.payments.length > 0 && (
            <ul className="plain-list" aria-label="Payments">
              {detail.data.payments.map((p) => (
                <li key={p.id}>
                  {formatPaise(p.amountPaise)} by {p.method.replace('_', ' ').toLowerCase()},{' '}
                  {p.status.replace('_', ' ').toLowerCase()}
                  {p.reference ? ` (${p.reference})` : ''}
                </li>
              ))}
            </ul>
          )}
          {manage &&
            (detail.data.invoice.status === 'ISSUED' || detail.data.invoice.status === 'PARTIALLY_PAID') && (
              <form className="form" onSubmit={record} aria-label="Record a payment">
                <Field label="Amount (₹)" required>
                  {(p) => (
                    <input
                      {...p}
                      inputMode="decimal"
                      value={amount}
                      onChange={(e) => setAmount(e.target.value)}
                    />
                  )}
                </Field>
                <Field label="Method" required>
                  {(p) => (
                    <select {...p} value={method} onChange={(e) => setMethod(e.target.value)}>
                      <option value="CASH">Cash</option>
                      <option value="CHEQUE">Cheque</option>
                      <option value="UPI_OFFICE">UPI at the office</option>
                    </select>
                  )}
                </Field>
                <Field
                  label="Reference"
                  hint="Cheque number or UPI reference."
                  required={method === 'CHEQUE'}
                >
                  {(p) => (
                    <input
                      {...p}
                      value={reference}
                      maxLength={60}
                      onChange={(e) => setReference(e.target.value)}
                    />
                  )}
                </Field>
                <Button type="submit" variant="primary" busy={busy} disabled={!ready}>
                  Record payment
                </Button>
                {detail.data.payments.every((p) => p.status !== 'SUCCEEDED') && (
                  <Button variant="quiet" onClick={() => void cancel()}>
                    Cancel invoice
                  </Button>
                )}
              </form>
            )}
        </div>
      )}
      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
    </div>
  );
}

function IssueToClass({ yearId, structure }: { yearId: string; structure: AcademicStructure }) {
  const session = useApiSession();
  const [sectionId, setSectionId] = useState('');
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [dueOn, setDueOn] = useState('');
  const [batchKey, setBatchKey] = useState(() => `batch-${crypto.randomUUID().slice(0, 8)}`);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const sections = structure.programmes.flatMap((p) =>
    p.grades.flatMap((g) => g.sections.map((s) => ({ id: s.id, label: `${g.name} ${s.code}` }))),
  );
  const paise = rupeesToPaise(amount);
  const ready = sectionId !== '' && description.trim().length >= 2 && paise !== null && dueOn !== '';

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (paise === null) return;
    setBusy(true);
    setMessage(null);
    setError(null);
    try {
      const r = await apiRequest<{ created: number; skipped: number }>(
        session,
        `/api/v1/academic-years/${yearId}/invoices/bulk`,
        {
          method: 'POST',
          body: {
            sectionId,
            dueOn,
            batchKey,
            lines: [{ description: description.trim(), amountPaise: paise }],
          },
        },
      );
      setMessage(`Issued ${r.created} invoice${r.created === 1 ? '' : 's'}; ${r.skipped} already had one.`);
      setBatchKey(`batch-${crypto.randomUUID().slice(0, 8)}`);
    } catch (err) {
      setError(oops(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <h3>Issue invoices to a class</h3>
      <form className="form" onSubmit={submit} aria-label="Issue invoices to a class">
        <Field label="Class" required>
          {(p) => (
            <select {...p} value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
              <option value="">Choose a class</option>
              {sections.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.label}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="What is it for" required>
          {(p) => (
            <input
              {...p}
              value={description}
              maxLength={120}
              onChange={(e) => setDescription(e.target.value)}
            />
          )}
        </Field>
        <Field label="Amount per child (₹)" required>
          {(p) => (
            <input {...p} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
          )}
        </Field>
        <Field label="Due on" required>
          {(p) => <input {...p} type="date" value={dueOn} onChange={(e) => setDueOn(e.target.value)} />}
        </Field>
        <Button type="submit" variant="primary" busy={busy} disabled={!ready}>
          Issue invoices
        </Button>
        {message && <p role="status">{message}</p>}
        {error && <p role="alert">{error}</p>}
      </form>
    </div>
  );
}

function ReconciliationPanel({ campusId }: { campusId: string }) {
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(today);
  const [rec, reload] = useApiResource<Reconciliation>(
    `/api/v1/campuses/${campusId}/fees/reconciliation?from=${from}&to=${to}`,
  );
  return (
    <div>
      <h3>Collections</h3>
      <Field label="From">
        {(p) => <input {...p} type="date" value={from} onChange={(e) => setFrom(e.target.value)} />}
      </Field>
      <Field label="To">
        {(p) => <input {...p} type="date" value={to} onChange={(e) => setTo(e.target.value)} />}
      </Field>
      {rec.status === 'loading' && <LoadingState label="Loading collections" />}
      {rec.status === 'error' && <ErrorState error={rec.error} onRetry={reload} />}
      {rec.status === 'ready' && (
        <>
          <p>
            Collected: <strong>{formatPaise(rec.data.collectedPaise)}</strong>
          </p>
          {rec.data.days.length > 0 && (
            <ul className="plain-list" aria-label="Collected by day">
              {rec.data.days.map((d) => (
                <li key={`${d.date}${d.method}`}>
                  {formatDay(d.date)}, {d.method.replace('_', ' ').toLowerCase()}: {formatPaise(d.totalPaise)}{' '}
                  ({d.payments})
                </li>
              ))}
            </ul>
          )}
          {rec.data.exceptions.length > 0 && (
            <>
              <h4>Needs attention</h4>
              <ul className="plain-list">
                {rec.data.exceptions.map((x, n) => (
                  <li key={n}>
                    <strong>{x.invoiceNumber}</strong> {formatPaise(x.amountPaise)}: {x.detail}
                  </li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
    </div>
  );
}
