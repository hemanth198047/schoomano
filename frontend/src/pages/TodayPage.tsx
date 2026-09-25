import type { Campus, GuardianCapability, Me } from '../api/types';
import { useApiResource } from '../app/useApiResource';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';

const capabilityText: Record<GuardianCapability, string> = {
  VIEW_ATTENDANCE: 'See attendance',
  VIEW_ACADEMIC: 'See academic records',
  PAY_FEES: 'Pay fees',
  GIVE_CONSENT: 'Give consent',
  RECEIVE_NOTICES: 'Receive notices',
  PICKUP: 'Collect from school',
};

const roleText: Record<string, string> = {
  group_admin: 'Group administrator',
  campus_admin: 'Campus administrator',
  principal: 'Principal',
  teacher: 'Teacher',
  finance_officer: 'Finance officer',
  transport_attendant: 'Transport attendant',
  nurse: 'Nurse',
  safeguarding_lead: 'Safeguarding lead',
};

export function TodayPage({ me }: { me: Me }) {
  const isStaff = me.roles.length > 0;
  return (
    <>
      <PageHeader title={`Good day, ${me.displayName.split(' ')[0]}`} lead={me.tenant.name} />
      {isStaff && <StaffToday me={me} />}
      {me.children.length > 0 && <GuardianToday me={me} />}
      {!isStaff && me.children.length === 0 && (
        <EmptyState title="Nothing is linked to your account yet">
          <p>Ask your school office to link your children or assign your staff role.</p>
        </EmptyState>
      )}
    </>
  );
}

function StaffToday({ me }: { me: Me }) {
  const [campuses, reload] = useApiResource<Campus[]>('/api/v1/campuses');
  return (
    <section className="section" aria-labelledby="roles-h">
      <h2 id="roles-h" className="section__title">
        Your roles
      </h2>
      <ul className="plain-list">
        {me.roles.map((r) => (
          <li key={`${r.roleKey}-${r.scopeId}`}>
            <strong>{roleText[r.roleKey] ?? r.roleKey}</strong> for {r.scopeName}
          </li>
        ))}
      </ul>

      <h2 className="section__title">Campus readiness</h2>
      {campuses.status === 'loading' && <LoadingState label="Checking campuses" />}
      {campuses.status === 'error' && <ErrorState error={campuses.error} onRetry={reload} />}
      {campuses.status === 'ready' &&
        (campuses.data.length === 0 ? (
          <EmptyState title="No campuses in your scope" />
        ) : (
          <ul className="checklist">
            {campuses.data.map((c) => (
              <li key={c.id} className="checklist__item">
                <span className="checklist__name">{c.name}</span>
                {c.activeYear ? (
                  <StatusBadge tone="success">{`Academic year ${c.activeYear} active`}</StatusBadge>
                ) : (
                  <StatusBadge tone="warning">No active academic year</StatusBadge>
                )}
              </li>
            ))}
          </ul>
        ))}
    </section>
  );
}

function GuardianToday({ me }: { me: Me }) {
  return (
    <section className="section" aria-labelledby="children-h">
      <h2 id="children-h" className="section__title">
        Your children
      </h2>
      <ul className="child-list">
        {me.children.map((c) => (
          <li key={c.studentId} className="child">
            <p className="child__name">{c.displayName}</p>
            <p className="child__meta">
              {c.campusName ?? 'Campus not assigned'} · {c.schoolRef}
            </p>
            <p className="child__caps-label">What you can do</p>
            <ul className="child__caps">
              {c.capabilities.map((cap) => (
                <li key={cap}>{capabilityText[cap]}</li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </section>
  );
}
