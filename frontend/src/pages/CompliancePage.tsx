import { useState } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { Campus, ComplianceEvidence, ComplianceItem, Me } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDay } from '../lib/format';

const has = (me: Me, ...keys: string[]) => me.roles.some((r) => keys.includes(r.roleKey));
export const canManageCompliance = (me: Me) => has(me, 'campus_admin', 'group_admin');
export const canSeeCompliance = (me: Me) => canManageCompliance(me) || has(me, 'principal');

const message = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong. Try again.');
const CATEGORY: Record<ComplianceItem['category'], string> = {
  FIRE_SAFETY: 'Fire safety',
  HEALTH_SAFETY: 'Health and safety',
  PRIVACY: 'Privacy',
  SAFEGUARDING: 'Safeguarding',
  FINANCE: 'Finance',
  STATUTORY: 'Statutory',
  OTHER: 'Other',
};
const STANDING: Record<
  ComplianceItem['standing'],
  { label: string; tone: 'success' | 'warning' | 'danger' | 'info' | 'neutral' }
> = {
  OVERDUE: { label: 'Overdue', tone: 'danger' },
  DUE_SOON: { label: 'Due soon', tone: 'warning' },
  CURRENT: { label: 'Up to date', tone: 'success' },
  DONE: { label: 'Done', tone: 'info' },
  RETIRED: { label: 'Retired', tone: 'neutral' },
};

export function CompliancePage({
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
        title="Compliance"
        lead="What the campus must keep up to date, who owns it, and the evidence that it was done."
      />
      {!canSeeCompliance(me) ? (
        <EmptyState title="Not available">
          <p>The compliance register is kept by the principal and the campus office.</p>
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
  const [items, reload] = useApiResource<ComplianceItem[]>(`/api/v1/campuses/${campusId}/compliance-items`);
  const [openId, setOpenId] = useState<string | null>(null);
  return (
    <>
      <section aria-labelledby="reg-h">
        <h2 id="reg-h">Register</h2>
        {items.status === 'loading' && <LoadingState label="Loading register" />}
        {items.status === 'error' && <ErrorState error={items.error} onRetry={reload} />}
        {items.status === 'ready' &&
          (items.data.length === 0 ? (
            <EmptyState title="Nothing in the register yet" />
          ) : (
            <ul className="plain-list">
              {items.data.map((i) => (
                <li key={i.id}>
                  <p>
                    <strong>{i.title}</strong>{' '}
                    <StatusBadge tone={STANDING[i.standing].tone}>{STANDING[i.standing].label}</StatusBadge>{' '}
                    <span className="muted">
                      {CATEGORY[i.category]}, owner {i.ownerName}
                      {i.nextDueOn ? `, due ${formatDay(i.nextDueOn)}` : ''}
                      {i.lastDoneOn ? `, last done ${formatDay(i.lastDoneOn)}` : ', never done'}
                      {i.lastReference ? ` (${i.lastReference})` : ''}
                    </span>{' '}
                    <Button variant="quiet" onClick={() => setOpenId(openId === i.id ? null : i.id)}>
                      {openId === i.id ? `Hide ${i.title}` : `Open ${i.title}`}
                    </Button>
                  </p>
                  {openId === i.id && <Detail me={me} item={i} onChanged={reload} />}
                </li>
              ))}
            </ul>
          ))}
      </section>
      {canManageCompliance(me) && <AddForm campusId={campusId} onSaved={reload} />}
    </>
  );
}

function Detail({ me, item, onChanged }: { me: Me; item: ComplianceItem; onChanged: () => void }) {
  const session = useApiSession();
  const [history, reload] = useApiResource<ComplianceEvidence[]>(
    `/api/v1/compliance-items/${item.id}/evidence`,
  );
  const [doneOn, setDoneOn] = useState('');
  const [reference, setReference] = useState('');
  const [validUntil, setValidUntil] = useState('');
  const [error, setError] = useState<string | null>(null);
  const active = item.status === 'ACTIVE';

  async function record() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/compliance-items/${item.id}/evidence`, {
        method: 'POST',
        body: { doneOn, reference: reference.trim() || undefined, validUntil: validUntil || undefined },
      });
      setDoneOn('');
      setReference('');
      setValidUntil('');
      reload();
      onChanged();
    } catch (e) {
      setError(message(e));
    }
  }
  async function retire() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/compliance-items/${item.id}/retire`, { method: 'POST' });
      onChanged();
    } catch (e) {
      setError(message(e));
    }
  }

  return (
    <div>
      {history.status === 'loading' && <LoadingState label="Loading evidence" />}
      {history.status === 'error' && <ErrorState error={history.error} onRetry={reload} />}
      {history.status === 'ready' &&
        (history.data.length === 0 ? (
          <p className="muted">No evidence recorded.</p>
        ) : (
          <ul className="plain-list" aria-label={`Evidence for ${item.title}`}>
            {history.data.map((e) => (
              <li key={e.id}>
                {formatDay(e.doneOn)}
                {e.reference ? `, ${e.reference}` : ''}
                {e.validUntil ? `, valid until ${formatDay(e.validUntil)}` : ''}
                {e.note ? `, ${e.note}` : ''} <span className="muted">recorded by {e.recordedBy}</span>
              </li>
            ))}
          </ul>
        ))}
      {active && (
        <>
          <div className="form form--inline">
            <Field label={`Date done for ${item.title}`}>
              {(p) => <input {...p} type="date" value={doneOn} onChange={(e) => setDoneOn(e.target.value)} />}
            </Field>
            <Field label="Certificate or reference" hint="Optional">
              {(p) => <input {...p} value={reference} onChange={(e) => setReference(e.target.value)} />}
            </Field>
            <Field label="Valid until" hint="Optional">
              {(p) => (
                <input
                  {...p}
                  type="date"
                  value={validUntil}
                  onChange={(e) => setValidUntil(e.target.value)}
                />
              )}
            </Field>
          </div>
          {error && (
            <p className="notice" role="alert">
              {error}
            </p>
          )}
          <p className="form__actions">
            <Button variant="primary" disabled={!doneOn} onClick={() => void record()}>
              {`Record evidence for ${item.title}`}
            </Button>{' '}
            {canManageCompliance(me) && (
              <Button variant="quiet" onClick={() => void retire()}>
                {`Retire ${item.title}`}
              </Button>
            )}
          </p>
        </>
      )}
    </div>
  );
}

function AddForm({ campusId, onSaved }: { campusId: string; onSaved: () => void }) {
  const session = useApiSession();
  const [title, setTitle] = useState('');
  const [category, setCategory] = useState<ComplianceItem['category']>('STATUTORY');
  const [owner, setOwner] = useState('');
  const [repeat, setRepeat] = useState('');
  const [due, setDue] = useState('');
  const [error, setError] = useState<string | null>(null);
  async function add() {
    setError(null);
    try {
      await apiRequest(session, `/api/v1/campuses/${campusId}/compliance-items`, {
        method: 'POST',
        body: {
          title: title.trim(),
          category,
          ownerName: owner.trim(),
          repeatMonths: repeat ? Number(repeat) : undefined,
          firstDueOn: due,
        },
      });
      setTitle('');
      setOwner('');
      setRepeat('');
      setDue('');
      onSaved();
    } catch (e) {
      setError(message(e));
    }
  }
  return (
    <section aria-labelledby="add-h">
      <h2 id="add-h">Add to the register</h2>
      <div className="form form--inline">
        <Field label="What must be kept up to date">
          {(p) => <input {...p} value={title} onChange={(e) => setTitle(e.target.value)} />}
        </Field>
        <Field label="Category">
          {(p) => (
            <select
              {...p}
              value={category}
              onChange={(e) => setCategory(e.target.value as ComplianceItem['category'])}
            >
              {Object.entries(CATEGORY).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Owner">
          {(p) => <input {...p} value={owner} onChange={(e) => setOwner(e.target.value)} />}
        </Field>
        <Field label="Repeats every (months)" hint="Leave empty for a one-off">
          {(p) => (
            <input {...p} inputMode="numeric" value={repeat} onChange={(e) => setRepeat(e.target.value)} />
          )}
        </Field>
        <Field label="First due on">
          {(p) => <input {...p} type="date" value={due} onChange={(e) => setDue(e.target.value)} />}
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
          disabled={
            title.trim().length < 3 ||
            owner.trim().length < 2 ||
            !due ||
            (repeat !== '' && !(Number(repeat) >= 1))
          }
          onClick={() => void add()}
        >
          Add to register
        </Button>
      </p>
    </section>
  );
}
