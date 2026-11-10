import { useState, type FormEvent } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  CalendarEvent,
  Campus,
  Me,
  Programme,
  RolloverPreview,
  Term,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { DataTable } from '../components/DataTable';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatDayRange } from '../lib/format';

const kindText: Record<CalendarEvent['kind'], string> = {
  HOLIDAY: 'Holiday',
  EXAM: 'Examinations',
  EVENT: 'Event',
  WORKING_DAY: 'Working day',
};
const kindTone = { HOLIDAY: 'warning', EXAM: 'info', EVENT: 'neutral', WORKING_DAY: 'success' } as const;

const asApiError = (e: unknown) =>
  e instanceof ApiError ? e : new ApiError(0, 'client_error', 'Something went wrong.', null);

/** Can change structure; the server enforces ACADEMIC_STRUCTURE_MANAGE regardless. */
export const canManage = (me: Me) =>
  me.roles.some((r) => r.roleKey === 'group_admin' || r.roleKey === 'campus_admin');

export function AcademicPage({
  me,
  campuses,
  selectedCampusId,
}: {
  me: Me;
  campuses: Campus[];
  selectedCampusId: string | null;
}) {
  const [chosenCampus, setChosenCampus] = useState<string | null>(null);
  const [yearId, setYearId] = useState<string | null>(null);
  const campusId = selectedCampusId ?? chosenCampus ?? campuses[0]?.id ?? null;
  const path =
    campusId === null
      ? null
      : `/api/v1/campuses/${campusId}/academic-structure${yearId ? `?yearId=${yearId}` : ''}`;
  const [structure, reload] = useApiResource<AcademicStructure>(path);
  const manage = canManage(me);

  return (
    <>
      <PageHeader
        title="Academic structure"
        lead="Grades, sections, subjects, terms and the calendar for each programme. Grade scales are under Settings and rules."
      />
      {campuses.length === 0 ? (
        <EmptyState title="No campus to show">
          <p>You need a campus role to see its academic structure.</p>
        </EmptyState>
      ) : (
        <div className="form form--inline">
          {selectedCampusId === null && campuses.length > 1 && (
            <Field label="Campus">
              {(p) => (
                <select
                  {...p}
                  value={campusId ?? ''}
                  onChange={(e) => {
                    setChosenCampus(e.target.value);
                    setYearId(null);
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
          {structure.status === 'ready' && structure.data.years.length > 1 && (
            <Field label="Academic year">
              {(p) => (
                <select
                  {...p}
                  value={structure.data.year?.id ?? ''}
                  onChange={(e) => setYearId(e.target.value)}
                >
                  {structure.data.years.map((y) => (
                    <option key={y.id} value={y.id}>
                      {y.label}
                    </option>
                  ))}
                </select>
              )}
            </Field>
          )}
        </div>
      )}

      {structure.status === 'loading' && path !== null && <LoadingState label="Loading academic structure" />}
      {structure.status === 'error' && <ErrorState error={structure.error} onRetry={reload} />}
      {structure.status === 'ready' && (
        <StructureView data={structure.data} manage={manage} onChanged={reload} />
      )}
    </>
  );
}

function StructureView({
  data,
  manage,
  onChanged,
}: {
  data: AcademicStructure;
  manage: boolean;
  onChanged: () => void;
}) {
  if (data.year === null) {
    return (
      <EmptyState title={`${data.campusName} has no academic year yet`}>
        <p>An administrator creates the first year before terms and sections can be added.</p>
      </EmptyState>
    );
  }
  // The API omits null fields, so a campus-wide event has no programmeId at all.
  const programmeName = (id: string | null | undefined) =>
    id == null ? 'Whole campus' : (data.programmes.find((p) => p.id === id)?.name ?? 'Programme');

  return (
    <>
      <p className="muted">
        {data.campusName} · {data.year.label} · {formatDayRange(data.year.startsOn, data.year.endsOn)}
      </p>

      {data.programmes.map((p) => (
        <ProgrammeSection key={p.id} programme={p} />
      ))}

      <section className="section" aria-labelledby="calendar-h">
        <h2 id="calendar-h" className="section__title">
          Calendar
        </h2>
        <DataTable
          caption={`Calendar for ${data.year.label}`}
          rows={data.calendar}
          rowKey={(e) => e.id}
          empty={<EmptyState title="No holidays or events yet" />}
          columns={[
            {
              key: 'when',
              header: 'Dates',
              render: (e) => formatDayRange(e.startsOn, e.endsOn),
              sortValue: (e) => e.startsOn,
            },
            { key: 'title', header: 'What', render: (e) => e.title, sortValue: (e) => e.title },
            {
              key: 'kind',
              header: 'Type',
              render: (e) => <StatusBadge tone={kindTone[e.kind]}>{kindText[e.kind]}</StatusBadge>,
              sortValue: (e) => e.kind,
            },
            { key: 'for', header: 'Applies to', render: (e) => programmeName(e.programmeId) },
          ]}
        />
      </section>

      {manage && (
        <>
          <AddTermPanel yearId={data.year.id} programmes={data.programmes} onAdded={onChanged} />
          <RolloverPanel yearId={data.year.id} yearLabel={data.year.label} />
        </>
      )}
    </>
  );
}

function ProgrammeSection({ programme: p }: { programme: Programme }) {
  const headingId = `prog-${p.id}`;
  return (
    <section className="section panel" aria-labelledby={headingId}>
      <h2 id={headingId} className="section__title">
        {p.name} <StatusBadge tone="info">{p.board === 'STATE' ? 'State board' : p.board}</StatusBadge>
      </h2>
      {p.boardDetail && <p className="muted">{p.boardDetail}</p>}

      <h3 className="section__subtitle">Terms</h3>
      <DataTable
        caption={`${p.name}: terms`}
        rows={p.terms}
        rowKey={(t: Term) => t.id}
        empty={<EmptyState title="No terms yet" />}
        columns={[
          { key: 'label', header: 'Term', render: (t) => t.label },
          {
            key: 'dates',
            header: 'Dates',
            render: (t) => formatDayRange(t.startsOn, t.endsOn),
            sortValue: (t) => t.startsOn,
          },
        ]}
      />

      <h3 className="section__subtitle">Grades and sections</h3>
      <DataTable
        caption={`${p.name}: grades and sections`}
        rows={p.grades}
        rowKey={(g) => g.id}
        empty={<EmptyState title="No grades yet" />}
        columns={[
          { key: 'grade', header: 'Grade', render: (g) => g.name, sortValue: (g) => g.ordinal },
          {
            key: 'sections',
            header: 'Sections (students / capacity)',
            render: (g) =>
              g.sections.length === 0
                ? 'None this year'
                : g.sections
                    .map((s) => `${s.code}: ${s.students}${s.capacity ? ` / ${s.capacity}` : ''}`)
                    .join(' · '),
          },
        ]}
      />

      <h3 className="section__subtitle">Subjects</h3>
      <p>
        {p.subjects.length === 0
          ? 'No subjects yet.'
          : p.subjects
              .filter((s) => s.status === 'ACTIVE')
              .map((s) => s.name)
              .join(', ')}
      </p>
    </section>
  );
}

function AddTermPanel({
  yearId,
  programmes,
  onAdded,
}: {
  yearId: string;
  programmes: Programme[];
  onAdded: () => void;
}) {
  const session = useApiSession();
  const [programmeId, setProgrammeId] = useState(programmes[0]?.id ?? '');
  const [label, setLabel] = useState('');
  const [startsOn, setStartsOn] = useState('');
  const [endsOn, setEndsOn] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (ev: FormEvent) => {
    ev.preventDefault();
    setDone(null);
    const e: Record<string, string> = {};
    if (label.trim() === '') e.label = 'Name the term, for example "Term 3".';
    if (!startsOn) e.startsOn = 'Choose the first day.';
    if (!endsOn) e.endsOn = 'Choose the last day.';
    else if (startsOn && endsOn < startsOn) e.endsOn = 'The last day must be on or after the first day.';
    setErrors(e);
    if (Object.keys(e).length > 0) return;
    setBusy(true);
    setFailure(null);
    try {
      const t = await apiRequest<Term>(session, `/api/v1/academic-years/${yearId}/terms`, {
        method: 'POST',
        body: { programmeId, label: label.trim(), startsOn, endsOn },
      });
      setDone(`Added ${t.label} (${formatDayRange(t.startsOn, t.endsOn)}).`);
      setLabel('');
      setStartsOn('');
      setEndsOn('');
      onAdded();
    } catch (err) {
      setFailure(asApiError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="section panel" aria-labelledby="add-term-h">
      <h2 id="add-term-h" className="section__title">
        Add a term
      </h2>
      <p className="muted">Terms of one programme cannot overlap and must fall inside the academic year.</p>
      {done && (
        <p className="notice notice--success" role="status">
          {done}
        </p>
      )}
      {failure && <ErrorState error={failure} />}
      <form className="form" onSubmit={submit} noValidate>
        <Field label="Programme" required>
          {(p) => (
            <select {...p} value={programmeId} onChange={(e) => setProgrammeId(e.target.value)}>
              {programmes.map((pr) => (
                <option key={pr.id} value={pr.id}>
                  {pr.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Term name" required error={errors.label}>
          {(p) => <input {...p} value={label} maxLength={40} onChange={(e) => setLabel(e.target.value)} />}
        </Field>
        <Field label="First day" required error={errors.startsOn}>
          {(p) => <input {...p} type="date" value={startsOn} onChange={(e) => setStartsOn(e.target.value)} />}
        </Field>
        <Field label="Last day" required error={errors.endsOn}>
          {(p) => <input {...p} type="date" value={endsOn} onChange={(e) => setEndsOn(e.target.value)} />}
        </Field>
        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={busy}>
            Add term
          </Button>
        </div>
      </form>
    </section>
  );
}

function RolloverPanel({ yearId, yearLabel }: { yearId: string; yearLabel: string }) {
  const session = useApiSession();
  const [preview, setPreview] = useState<RolloverPreview | null>(null);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async () => {
    setBusy(true);
    setFailure(null);
    try {
      setPreview(
        await apiRequest<RolloverPreview>(session, `/api/v1/academic-years/${yearId}/rollover-preview`, {
          method: 'POST',
        }),
      );
    } catch (err) {
      setFailure(asApiError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="section panel" aria-labelledby="rollover-h">
      <h2 id="rollover-h" className="section__title">
        Next year
      </h2>
      <p className="muted">See what rolling {yearLabel} forward would create. The preview changes nothing.</p>
      <Button variant="secondary" onClick={run} disabled={busy}>
        Preview next year
      </Button>
      {failure && <ErrorState error={failure} />}
      {preview && (
        <div role="region" aria-label="Rollover preview">
          <p>
            <strong>{preview.label}</strong> · {formatDayRange(preview.dates.startsOn, preview.dates.endsOn)}{' '}
            · {preview.terms.length} terms · {preview.sections.length} sections
          </p>
          {preview.warnings.map((w) => (
            <p key={w} className="notice">
              {w}
            </p>
          ))}
          <DataTable
            caption="Students moving up"
            rows={preview.promotions}
            rowKey={(p) => `${p.programmeCode}-${p.fromGrade}`}
            empty={<EmptyState title="No students are placed in sections yet" />}
            columns={[
              { key: 'prog', header: 'Programme', render: (p) => p.programmeCode },
              { key: 'from', header: 'From', render: (p) => p.fromGrade },
              { key: 'to', header: 'To', render: (p) => p.toGrade ?? 'Completes the programme' },
              { key: 'n', header: 'Students', render: (p) => p.students, numeric: true },
            ]}
          />
        </div>
      )}
    </section>
  );
}
