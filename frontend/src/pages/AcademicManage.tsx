import { useState, type FormEvent, type ReactNode } from 'react';
import { ApiError, apiRequest } from '../api/client';
import type {
  AcademicStructure,
  AcademicYear,
  ConfigVersion,
  Programme,
  ResolvedConfig,
  RolloverResult,
} from '../api/types';
import { useApiSession } from '../app/session';
import { useApiResource } from '../app/useApiResource';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { ErrorState } from '../components/States';
import { formatDateTime, formatDayRange, localInputToIsoIst } from '../lib/format';

export const asApiError = (e: unknown) =>
  e instanceof ApiError ? e : new ApiError(0, 'client_error', 'Something went wrong.', null);

/** One write at a time, with a success message or the server's error (including its list of problems). */
function useWrite() {
  const session = useApiSession();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const run = async <T,>(path: string, method: string, body: unknown, success: (r: T) => string) => {
    setBusy(true);
    setFailure(null);
    setDone(null);
    try {
      const r = await apiRequest<T>(session, path, { method, body });
      setDone(success(r));
      return r;
    } catch (e) {
      setFailure(asApiError(e));
      return null;
    } finally {
      setBusy(false);
    }
  };
  const feedback = (
    <>
      {done && (
        <p className="notice notice--success" role="status">
          {done}
        </p>
      )}
      {failure && <ErrorState error={failure} />}
    </>
  );
  return { busy, run, feedback };
}

type AddKind = 'grade' | 'subject' | 'section' | 'calendar' | 'year';
const addKinds: { kind: AddKind; label: string }[] = [
  { kind: 'calendar', label: 'Holiday or event' },
  { kind: 'section', label: 'Section' },
  { kind: 'subject', label: 'Subject' },
  { kind: 'grade', label: 'Grade' },
  { kind: 'year', label: 'Academic year' },
];

/** Add grades, subjects, sections, calendar entries and academic years. The server re-checks every rule. */
export function AddPanel({ data, onChanged }: { data: AcademicStructure; onChanged: () => void }) {
  const year = data.year!;
  const [kind, setKind] = useState<AddKind>('calendar');
  const [programmeId, setProgrammeId] = useState(data.programmes[0]?.id ?? '');
  const [f, setF] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const { busy, run, feedback } = useWrite();
  const programme = data.programmes.find((p) => p.id === programmeId);
  const set = (k: string) => (v: string) => setF((o) => ({ ...o, [k]: v }));
  const v = (k: string) => f[k] ?? '';

  const validate = () => {
    const e: Record<string, string> = {};
    const need = (k: string, msg: string) => {
      if (v(k).trim() === '') e[k] = msg;
    };
    if (kind === 'grade') {
      need('code', 'Enter a short code, for example G6.');
      need('name', 'Enter the grade name.');
      if (!/^-?\d+$/.test(v('ordinal'))) e.ordinal = 'Enter the order as a whole number, for example 6.';
    }
    if (kind === 'subject') {
      need('code', 'Enter a short code, for example MATH.');
      need('name', 'Enter the subject name.');
    }
    if (kind === 'section') {
      need('gradeId', 'Choose the grade.');
      need('code', 'Enter the section letter, for example C.');
    }
    if (kind === 'calendar') need('title', 'Say what the day is, for example "Pongal".');
    if (kind === 'year') need('label', 'Name the year, for example 2027-28.');
    if (kind === 'calendar' || kind === 'year') {
      need('startsOn', 'Choose the first day.');
      need('endsOn', 'Choose the last day.');
      if (v('startsOn') && v('endsOn') && v('endsOn') < v('startsOn'))
        e.endsOn = 'The last day must be on or after the first day.';
    }
    if ((kind === 'grade' || kind === 'subject') && v('code') && !/^[A-Z0-9-]+$/.test(v('code')))
      e.code = 'Use capital letters, digits and hyphens only.';
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const submit = async (ev: FormEvent) => {
    ev.preventDefault();
    if (!validate()) return;
    const cap = v('capacity') === '' ? undefined : Number(v('capacity'));
    const ok =
      kind === 'grade'
        ? await run(
            `/api/v1/programmes/${programmeId}/grades`,
            'POST',
            { code: v('code'), name: v('name').trim(), ordinal: Number(v('ordinal')) },
            () => `Added ${v('name').trim()}.`,
          )
        : kind === 'subject'
          ? await run(
              `/api/v1/programmes/${programmeId}/subjects`,
              'POST',
              { code: v('code'), name: v('name').trim() },
              () => `Added ${v('name').trim()}.`,
            )
          : kind === 'section'
            ? await run(
                `/api/v1/academic-years/${year.id}/sections`,
                'POST',
                { gradeId: v('gradeId'), code: v('code').toUpperCase(), capacity: cap },
                () => `Added section ${v('code').toUpperCase()}.`,
              )
            : kind === 'calendar'
              ? await run(
                  `/api/v1/academic-years/${year.id}/calendar-events`,
                  'POST',
                  {
                    kind: v('eventKind') || 'HOLIDAY',
                    title: v('title').trim(),
                    startsOn: v('startsOn'),
                    endsOn: v('endsOn'),
                    programmeId: v('scope') === 'programme' ? programmeId : undefined,
                  },
                  () => `Added ${v('title').trim()} (${formatDayRange(v('startsOn'), v('endsOn'))}).`,
                )
              : await run(
                  `/api/v1/campuses/${data.campusId}/academic-years`,
                  'POST',
                  { label: v('label').trim(), startsOn: v('startsOn'), endsOn: v('endsOn') },
                  () => `Created ${v('label').trim()} as a planned year.`,
                );
    if (ok) {
      setF({});
      onChanged();
    }
  };

  const needsProgramme = kind === 'grade' || kind === 'subject' || kind === 'section';
  return (
    <section className="section panel" aria-labelledby="add-h">
      <h2 id="add-h" className="section__title">
        Add to the structure
      </h2>
      {feedback}
      <form className="form" onSubmit={submit} noValidate>
        <Field label="What to add">
          {(p) => (
            <select
              {...p}
              value={kind}
              onChange={(e) => {
                setKind(e.target.value as AddKind);
                setErrors({});
              }}
            >
              {addKinds.map((k) => (
                <option key={k.kind} value={k.kind}>
                  {k.label}
                </option>
              ))}
            </select>
          )}
        </Field>
        {(needsProgramme || (kind === 'calendar' && v('scope') === 'programme')) && (
          <Field label="Programme" required>
            {(p) => (
              <select {...p} value={programmeId} onChange={(e) => setProgrammeId(e.target.value)}>
                {data.programmes.map((pr) => (
                  <option key={pr.id} value={pr.id}>
                    {pr.name}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        {kind === 'section' && (
          <Field label="Grade" required error={errors.gradeId}>
            {(p) => (
              <select {...p} value={v('gradeId')} onChange={(e) => set('gradeId')(e.target.value)}>
                <option value="">Choose a grade</option>
                {programme?.grades.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        {(kind === 'grade' || kind === 'subject' || kind === 'section') && (
          <Text label={kind === 'section' ? 'Section letter' : 'Code'} k="code" {...{ v, set, errors }} />
        )}
        {(kind === 'grade' || kind === 'subject') && <Text label="Name" k="name" {...{ v, set, errors }} />}
        {kind === 'grade' && (
          <Text
            label="Order"
            hint="Students move up in this order at rollover (Grade 6 = 6)."
            k="ordinal"
            inputMode="numeric"
            {...{ v, set, errors }}
          />
        )}
        {kind === 'section' && (
          <Text label="Capacity (optional)" k="capacity" inputMode="numeric" {...{ v, set, errors }} />
        )}
        {kind === 'calendar' && (
          <>
            <Text label="Title" k="title" {...{ v, set, errors }} />
            <Field label="Type">
              {(p) => (
                <select
                  {...p}
                  value={v('eventKind') || 'HOLIDAY'}
                  onChange={(e) => set('eventKind')(e.target.value)}
                >
                  <option value="HOLIDAY">Holiday</option>
                  <option value="EXAM">Examinations</option>
                  <option value="EVENT">Event</option>
                  <option value="WORKING_DAY">Working day (e.g. a working Saturday)</option>
                </select>
              )}
            </Field>
            <Field label="Applies to">
              {(p) => (
                <select {...p} value={v('scope') || 'campus'} onChange={(e) => set('scope')(e.target.value)}>
                  <option value="campus">Whole campus</option>
                  <option value="programme">One programme</option>
                </select>
              )}
            </Field>
          </>
        )}
        {kind === 'year' && <Text label="Year name" k="label" {...{ v, set, errors }} />}
        {(kind === 'calendar' || kind === 'year') && (
          <>
            <Text label="First day" k="startsOn" type="date" {...{ v, set, errors }} />
            <Text label="Last day" k="endsOn" type="date" {...{ v, set, errors }} />
          </>
        )}
        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={busy}>
            Add
          </Button>
        </div>
      </form>
    </section>
  );
}

function Text({
  label,
  hint,
  k,
  type = 'text',
  inputMode,
  v,
  set,
  errors,
}: {
  label: string;
  hint?: string;
  k: string;
  type?: string;
  inputMode?: 'numeric';
  v: (k: string) => string;
  set: (k: string) => (v: string) => void;
  errors: Record<string, string>;
}) {
  return (
    <Field label={label} hint={hint} required={!label.includes('optional')} error={errors[k]}>
      {(p) => (
        <input
          {...p}
          type={type}
          inputMode={inputMode}
          value={v(k)}
          onChange={(e) => set(k)(e.target.value)}
        />
      )}
    </Field>
  );
}

/** Lifecycle of the selected year: a planned year can be made active (closing the current one). */
export function YearActions({ year, onChanged }: { year: AcademicYear; onChanged: () => void }) {
  const { busy, run, feedback } = useWrite();
  if (year.status !== 'PLANNED') return null;
  return (
    <div className="section">
      {feedback}
      <p className="muted">
        {year.label} is planned. Making it active closes the current year on this campus.
      </p>
      <Button
        variant="secondary"
        disabled={busy}
        onClick={async () => {
          if (
            await run(
              `/api/v1/academic-years/${year.id}/activate`,
              'POST',
              undefined,
              () => `${year.label} is now active.`,
            )
          )
            onChanged();
        }}
      >
        Make {year.label} the active year
      </Button>
    </div>
  );
}

/** Commits the rollover the preview showed. Offered only after a preview, and only once per year. */
export function RolloverCommit({
  yearId,
  label,
  onDone,
}: {
  yearId: string;
  label: string;
  onDone: () => void;
}) {
  const { busy, run, feedback } = useWrite();
  const [confirm, setConfirm] = useState(false);
  return (
    <div className="section">
      {feedback}
      {!confirm ? (
        <Button variant="primary" onClick={() => setConfirm(true)}>
          Create {label}
        </Button>
      ) : (
        <div role="group" aria-label="Confirm rollover" className="form__actions">
          <Button
            variant="primary"
            disabled={busy}
            onClick={async () => {
              const r = await run<RolloverResult>(
                `/api/v1/academic-years/${yearId}/rollover`,
                'POST',
                undefined,
                (x) =>
                  `Created ${x.label} as a planned year: ${x.termsCreated} terms, ${x.sectionsCreated} sections, ` +
                  `${x.studentsPlaced} students placed, ${x.studentsUnplaced} to place, ${x.studentsCompleted} completed.`,
              );
              setConfirm(false);
              if (r) onDone();
            }}
          >
            Yes, create {label}
          </Button>
          <Button variant="secondary" onClick={() => setConfirm(false)}>
            Cancel
          </Button>
        </div>
      )}
    </div>
  );
}

interface Band {
  grade: string;
  minPercent: number;
}

/** Parses "A1 91" lines. Returns bands or a user-readable error. */
export function parseBands(text: string): Band[] | string {
  const bands: Band[] = [];
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (line === '') continue;
    const m = /^([A-Za-z0-9+-]{1,6})\s+(\d{1,3})%?$/.exec(line);
    if (!m) return `Could not read "${line}". Write one grade per line, like "A1 91".`;
    bands.push({ grade: m[1]!, minPercent: Number(m[2]) });
  }
  if (bands.length < 2) return 'A grade scale needs at least two grades.';
  if (!bands.some((b) => b.minPercent === 0))
    return 'The lowest grade must start at 0 so every score has a grade.';
  return bands;
}

/** The programme's grade scale (effective-dated config) and a form to schedule a new one. */
export function GradeScalePanel({ programme }: { programme: Programme }) {
  const [current, reload] = useApiResource<ResolvedConfig>(
    `/api/v1/config/assessment.grade_scale?programmeId=${programme.id}`,
  );
  const [text, setText] = useState('');
  const [from, setFrom] = useState('');
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const { busy, run, feedback } = useWrite();
  const bands =
    current.status === 'ready' ? ((current.data.value as { bands?: Band[] }).bands ?? []) : ([] as Band[]);

  const submit = async (ev: FormEvent) => {
    ev.preventDefault();
    const e: Record<string, string> = {};
    const parsed = parseBands(text);
    if (typeof parsed === 'string') e.text = parsed;
    if (!from) e.from = 'Choose when the new scale takes effect.';
    else if (new Date(localInputToIsoIst(from)) <= new Date())
      e.from = 'Choose a date and time in the future.';
    if (note.trim().length < 5) e.note = 'Explain the change in a few words. It is kept in the history.';
    setErrors(e);
    if (Object.keys(e).length > 0 || typeof parsed === 'string') return;
    const draft = await run<ConfigVersion>(
      '/api/v1/config/assessment.grade_scale/drafts',
      'POST',
      {
        scopeLevel: 'PROGRAMME',
        scopeId: programme.id,
        value: { bands: parsed },
        effectiveFrom: localInputToIsoIst(from),
        changeNote: note.trim(),
      },
      () => 'Draft saved.',
    );
    if (!draft) return;
    const published = await run<ConfigVersion>(
      `/api/v1/config/drafts/${draft.id}/publish`,
      'POST',
      undefined,
      (r) =>
        `New scale published. It applies from ${formatDateTime(r.effectiveFrom)}; earlier marks keep the old scale.`,
    );
    if (published) {
      setText('');
      setFrom('');
      setNote('');
      reload();
    }
  };

  return (
    <details className="section">
      <summary>Grade scale</summary>
      {current.status === 'ready' ? (
        <p>
          {[...bands]
            .sort((a, b) => b.minPercent - a.minPercent)
            .map((b) => `${b.grade} from ${b.minPercent}%`)
            .join(' · ')}
        </p>
      ) : current.status === 'error' && current.error.status === 404 ? (
        <p className="muted">No grade scale set yet.</p>
      ) : null}
      {feedback}
      <form className="form" onSubmit={submit} noValidate>
        <Field
          label="New scale"
          hint='One grade per line with the lowest percentage that earns it, e.g. "A1 91". The last grade starts at 0.'
          required
          error={errors.text}
        >
          {(p) => <textarea {...p} rows={6} value={text} onChange={(e) => setText(e.target.value)} />}
        </Field>
        <Field label="Takes effect from" required error={errors.from}>
          {(p) => (
            <input {...p} type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
          )}
        </Field>
        <Field label="Reason for the change" required error={errors.note}>
          {(p) => <input {...p} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />}
        </Field>
        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={busy}>
            Schedule new scale
          </Button>
        </div>
      </form>
    </details>
  );
}

export function Confirmable({
  label,
  onConfirm,
  children,
  ariaLabel,
}: {
  label: string;
  onConfirm: () => void;
  children: ReactNode;
  /** Full name for assistive technology when the visible text is short, e.g. "Remove" → "Remove Term 1". */
  ariaLabel?: string;
}) {
  const [asking, setAsking] = useState(false);
  return asking ? (
    <span className="form__actions">
      <Button variant="primary" onClick={onConfirm}>
        {label}
      </Button>
      <Button variant="secondary" onClick={() => setAsking(false)}>
        Cancel
      </Button>
    </span>
  ) : (
    <Button variant="quiet" aria-label={ariaLabel} onClick={() => setAsking(true)}>
      {children}
    </Button>
  );
}
