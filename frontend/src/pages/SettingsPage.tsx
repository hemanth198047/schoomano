import { useState, type FormEvent } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type { Campus, ConfigVersion, Me, PreviewRow } from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { DataTable } from '../components/DataTable';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDateTime, localInputToIsoIst } from '../lib/format';

interface SettingDef {
  key: string;
  label: string;
  help: string;
  kind: 'minutes' | 'switch';
}

export const settings: SettingDef[] = [
  {
    key: 'attendance.late_after_minutes',
    label: 'Mark a student late after',
    help: 'Minutes after the first bell. A campus can override the group value.',
    kind: 'minutes',
  },
  {
    key: 'flag.ai.enabled',
    label: 'AI assistance',
    help: 'Off by default. Every workflow works without it.',
    kind: 'switch',
  },
];

const show = (def: SettingDef, v: unknown) =>
  v === null || v === undefined
    ? 'Not set'
    : def.kind === 'switch'
      ? v
        ? 'On'
        : 'Off'
      : `${String(v)} minutes`;

export function SettingsPage({ me }: { me: Me }) {
  const [selected, setSelected] = useState(settings[0]!.key);
  const def = settings.find((s) => s.key === selected)!;
  const canEdit = me.roles.some((r) => r.roleKey === 'group_admin');
  const [history, reloadHistory] = useApiResource<ConfigVersion[]>(`/api/v1/config/${def.key}/versions`);
  const [campuses] = useApiResource<Campus[]>('/api/v1/campuses');

  return (
    <>
      <PageHeader
        title="Settings and rules"
        lead="Changes take effect from a date you choose. Earlier dates keep the rule that applied then."
      />
      <div className="tabs" role="group" aria-label="Setting">
        {settings.map((s) => (
          <Button
            key={s.key}
            variant={s.key === selected ? 'primary' : 'secondary'}
            aria-pressed={s.key === selected}
            onClick={() => setSelected(s.key)}
          >
            {s.label}
          </Button>
        ))}
      </div>
      <p className="muted">{def.help}</p>

      <section className="section" aria-labelledby="history-h">
        <h2 id="history-h" className="section__title">
          Version history
        </h2>
        {history.status === 'loading' && <LoadingState label="Loading history" />}
        {history.status === 'error' && <ErrorState error={history.error} onRetry={reloadHistory} />}
        {history.status === 'ready' && (
          <DataTable
            caption={`${def.label}: version history`}
            rows={history.data}
            rowKey={(v) => v.id}
            empty={<EmptyState title="This setting has never been set" />}
            columns={[
              {
                key: 'scope',
                header: 'Applies to',
                render: (v) =>
                  v.scopeLevel === 'GROUP'
                    ? 'Whole group'
                    : campuses.status === 'ready'
                      ? (campuses.data.find((c) => c.id === v.scopeId)?.name ?? 'Campus')
                      : 'Campus',
              },
              {
                key: 'v',
                header: 'Version',
                render: (v) => v.version,
                sortValue: (v) => v.version,
                numeric: true,
              },
              { key: 'value', header: 'Value', render: (v) => show(def, v.value) },
              {
                key: 'from',
                header: 'From',
                render: (v) => formatDateTime(v.effectiveFrom),
                sortValue: (v) => v.effectiveFrom,
              },
              {
                key: 'to',
                header: 'Until',
                render: (v) => (v.effectiveTo ? formatDateTime(v.effectiveTo) : 'Open'),
              },
              {
                key: 'status',
                header: 'Status',
                render: (v) => (
                  <StatusBadge
                    tone={v.status === 'PUBLISHED' ? 'success' : v.status === 'DRAFT' ? 'info' : 'neutral'}
                  >
                    {v.status === 'PUBLISHED' ? 'Published' : v.status === 'DRAFT' ? 'Draft' : 'Retired'}
                  </StatusBadge>
                ),
              },
              { key: 'note', header: 'Reason', render: (v) => v.changeNote },
            ]}
          />
        )}
      </section>

      {canEdit ? (
        <DraftPanel
          key={def.key}
          def={def}
          tenantScopeId={me.tenant.id}
          campuses={campuses.status === 'ready' ? campuses.data : []}
          onPublished={reloadHistory}
        />
      ) : (
        <p className="muted">Only a group administrator can change this setting.</p>
      )}
    </>
  );
}

function DraftPanel({
  def,
  tenantScopeId,
  campuses,
  onPublished,
}: {
  def: SettingDef;
  tenantScopeId: string;
  campuses: Campus[];
  onPublished: () => void;
}) {
  const session = useApiSession();
  const [scope, setScope] = useState<string>('GROUP');
  const [value, setValue] = useState(def.kind === 'switch' ? 'false' : '');
  const [from, setFrom] = useState('');
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [draft, setDraft] = useState<ConfigVersion | null>(null);
  const [preview, setPreview] = useState<PreviewRow[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [published, setPublished] = useState<string | null>(null);

  const validate = () => {
    const e: Record<string, string> = {};
    if (def.kind === 'minutes') {
      const n = Number(value);
      if (value.trim() === '' || !Number.isInteger(n) || n < 0 || n > 120)
        e.value = 'Enter whole minutes from 0 to 120.';
    }
    if (!from) e.from = 'Choose when this takes effect.';
    else if (new Date(localInputToIsoIst(from)) <= new Date())
      e.from = 'Choose a date and time in the future.';
    if (note.trim().length < 5) e.note = 'Explain the change in a few words. It is kept in the history.';
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const createAndPreview = async (ev: FormEvent) => {
    ev.preventDefault();
    setPublished(null);
    if (!validate()) return;
    setBusy(true);
    setFailure(null);
    try {
      const created = await apiRequest<ConfigVersion>(session, `/api/v1/config/${def.key}/drafts`, {
        method: 'POST',
        body: {
          scopeLevel: scope === 'GROUP' ? 'GROUP' : 'CAMPUS',
          scopeId: scope === 'GROUP' ? tenantScopeId : scope,
          value: def.kind === 'switch' ? value === 'true' : Number(value),
          effectiveFrom: localInputToIsoIst(from),
          changeNote: note.trim(),
        },
      });
      setDraft(created);
      setPreview(
        await apiRequest<PreviewRow[]>(session, `/api/v1/config/drafts/${created.id}/preview`, {
          method: 'POST',
        }),
      );
    } catch (e) {
      setFailure(e instanceof ApiError ? e : new ApiError(0, 'client_error', 'Something went wrong.', null));
    } finally {
      setBusy(false);
    }
  };

  const publish = async () => {
    if (!draft) return;
    setBusy(true);
    setFailure(null);
    try {
      await apiRequest<ConfigVersion>(session, `/api/v1/config/drafts/${draft.id}/publish`, {
        method: 'POST',
      });
      setPublished(
        `Published version ${draft.version}. It takes effect ${formatDateTime(draft.effectiveFrom)}.`,
      );
      setDraft(null);
      setPreview(null);
      onPublished();
    } catch (e) {
      setFailure(e instanceof ApiError ? e : new ApiError(0, 'client_error', 'Something went wrong.', null));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="section panel" aria-labelledby="draft-h">
      <h2 id="draft-h" className="section__title">
        Schedule a change
      </h2>
      {published && (
        <p className="notice notice--success" role="status">
          {published}
        </p>
      )}
      {failure && <ErrorState error={failure} />}
      <form className="form" onSubmit={createAndPreview} noValidate>
        <Field label="Applies to" error={errors.scope}>
          {(p) => (
            <select {...p} value={scope} onChange={(e) => setScope(e.target.value)}>
              <option value="GROUP">Whole group (campuses without an override)</option>
              {campuses.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name} only
                </option>
              ))}
            </select>
          )}
        </Field>
        {def.kind === 'minutes' ? (
          <Field label="Minutes" required error={errors.value}>
            {(p) => (
              <input
                {...p}
                type="number"
                inputMode="numeric"
                min={0}
                max={120}
                value={value}
                onChange={(e) => setValue(e.target.value)}
              />
            )}
          </Field>
        ) : (
          <Field label="Setting" required>
            {(p) => (
              <select {...p} value={value} onChange={(e) => setValue(e.target.value)}>
                <option value="false">Off</option>
                <option value="true">On</option>
              </select>
            )}
          </Field>
        )}
        <Field label="Takes effect from" hint="India Standard Time" required error={errors.from}>
          {(p) => (
            <input {...p} type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
          )}
        </Field>
        <Field label="Reason for the change" required error={errors.note}>
          {(p) => (
            <textarea
              {...p}
              rows={2}
              maxLength={500}
              value={note}
              onChange={(e) => setNote(e.target.value)}
            />
          )}
        </Field>
        <div className="form__actions">
          <Button type="submit" variant="secondary" busy={busy && !draft}>
            Preview change
          </Button>
        </div>
      </form>

      {preview && draft && (
        <div className="preview" aria-live="polite">
          <h3 className="section__subtitle">What changes on {formatDateTime(draft.effectiveFrom)}</h3>
          <DataTable
            caption="Effect by campus"
            rows={preview}
            rowKey={(r) => r.campusId}
            empty={<EmptyState title="No campuses are affected" />}
            columns={[
              { key: 'c', header: 'Campus', render: (r) => r.campusName },
              { key: 'b', header: 'Before', render: (r) => show(def, r.before) },
              { key: 'a', header: 'After', render: (r) => show(def, r.after) },
              {
                key: 'x',
                header: 'Result',
                render: (r) =>
                  r.changed ? (
                    <StatusBadge tone="warning">Changes</StatusBadge>
                  ) : (
                    <StatusBadge tone="neutral">No change</StatusBadge>
                  ),
              },
            ]}
          />
          <p className="muted">
            The draft is saved as version {draft.version}. Publishing records who approved it.
          </p>
          <Button variant="primary" busy={busy} onClick={publish}>
            Publish version {draft.version}
          </Button>
        </div>
      )}
    </section>
  );
}
