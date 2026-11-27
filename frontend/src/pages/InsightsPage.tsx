import { useState } from 'react';
import type { AcademicStructure, AnalyticsOverview, Campus, Me } from '../api/types';
import { useApiResource } from '../app/useApiResource';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { formatDay, formatPaise } from '../lib/format';

export const canSeeInsights = (me: Me) =>
  me.roles.some((r) => ['campus_admin', 'group_admin', 'principal'].includes(r.roleKey));

const todayIst = () => new Date(Date.now() + 5.5 * 3600 * 1000).toISOString().slice(0, 10);
const daysBefore = (iso: string, n: number) =>
  new Date(Date.parse(`${iso}T00:00:00Z`) - n * 86400000).toISOString().slice(0, 10);
const pct = (p: number | null) => (p === null ? 'no marks' : `${p}%`);

export function InsightsPage({
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
  const allowed = canSeeInsights(me);
  const [structure] = useApiResource<AcademicStructure>(
    allowed && campusId !== null ? `/api/v1/campuses/${campusId}/academic-structure` : null,
  );
  return (
    <>
      <PageHeader
        title="Insights"
        lead="Totals for the campus from attendance, fees and enrolment. Numbers only: no child is named here."
      />
      {!allowed ? (
        <EmptyState title="Not available">
          <p>Insights are for the principal and the campus office.</p>
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
          {structure.status === 'ready' && !structure.data.year && (
            <EmptyState title="No academic year yet" />
          )}
          {structure.status === 'ready' && structure.data.year && (
            <Overview key={structure.data.year.id} yearId={structure.data.year.id} />
          )}
        </>
      )}
    </>
  );
}

function Overview({ yearId }: { yearId: string }) {
  const [to, setTo] = useState(todayIst());
  const [from, setFrom] = useState(daysBefore(todayIst(), 29));
  const valid = from <= to && Date.parse(to) - Date.parse(from) <= 92 * 86400000;
  const [data, reload] = useApiResource<AnalyticsOverview>(
    valid ? `/api/v1/academic-years/${yearId}/analytics?from=${from}&to=${to}` : null,
  );
  return (
    <>
      <div className="form form--inline">
        <Field label="From">
          {(p) => <input {...p} type="date" value={from} onChange={(e) => setFrom(e.target.value)} />}
        </Field>
        <Field label="To">
          {(p) => <input {...p} type="date" value={to} onChange={(e) => setTo(e.target.value)} />}
        </Field>
      </div>
      {!valid && (
        <p className="notice" role="alert">
          Choose a range of up to three months, starting before it ends.
        </p>
      )}
      {valid && data.status === 'loading' && <LoadingState label="Calculating" />}
      {valid && data.status === 'error' && <ErrorState error={data.error} onRetry={reload} />}
      {valid && data.status === 'ready' && <Figures d={data.data} />}
    </>
  );
}

function Figures({ d }: { d: AnalyticsOverview }) {
  const a = d.attendance;
  const f = d.fees;
  return (
    <>
      <section aria-labelledby="enrol-h">
        <h2 id="enrol-h">Enrolment, {d.yearLabel}</h2>
        <p>
          <strong>{d.enrolment.enrolled}</strong> enrolled. {d.enrolment.withdrawn} withdrawn,{' '}
          {d.enrolment.transferredOut} transferred out, {d.enrolment.completed} completed.
        </p>
      </section>
      <section aria-labelledby="att-h">
        <h2 id="att-h">
          Attendance, {formatDay(a.from)} to {formatDay(a.to)}
        </h2>
        {a.marks === 0 ? (
          <p className="muted">No attendance was taken in this period.</p>
        ) : (
          <>
            <p>
              <strong>{pct(a.percent)}</strong> present or late: {a.attended} of {a.marks} marks across{' '}
              {a.sessions} class registers, {a.absent} absent.
            </p>
            <table>
              <caption>By class, lowest attendance first</caption>
              <thead>
                <tr>
                  <th scope="col">Class</th>
                  <th scope="col">Marks</th>
                  <th scope="col">Present or late</th>
                </tr>
              </thead>
              <tbody>
                {a.sections.map((s) => (
                  <tr key={s.section}>
                    <th scope="row">{s.section}</th>
                    <td>{s.marks}</td>
                    <td>
                      <meter
                        min={0}
                        max={100}
                        value={s.percent ?? 0}
                        aria-label={`${s.section} attendance`}
                      />{' '}
                      {pct(s.percent)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <table>
              <caption>By day</caption>
              <thead>
                <tr>
                  <th scope="col">Day</th>
                  <th scope="col">Marks</th>
                  <th scope="col">Present or late</th>
                </tr>
              </thead>
              <tbody>
                {a.days.map((x) => (
                  <tr key={x.date}>
                    <th scope="row">{formatDay(x.date)}</th>
                    <td>{x.marks}</td>
                    <td>{pct(x.percent)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </section>
      <section aria-labelledby="fee-h">
        <h2 id="fee-h">Fees, whole year</h2>
        {f.invoices === 0 ? (
          <p className="muted">No invoices have been issued.</p>
        ) : (
          <ul className="plain-list">
            <li>
              {f.invoices} invoices billed {formatPaise(f.billedPaise)}; {formatPaise(f.collectedPaise)}{' '}
              collected ({pct(f.collectedPercent)}), {formatPaise(f.outstandingPaise)} outstanding.
            </li>
            <li>
              Overdue: {f.overdueInvoices} invoices, {formatPaise(f.overduePaise)}.
            </li>
          </ul>
        )}
      </section>
    </>
  );
}
