import { useCallback, useEffect, useState } from 'react';
import { ScrollView, View } from 'react-native';
import { ApiError, type Client } from '../core/api';
import { formatPaise } from '../core/money';
import { homeworkLine } from '../core/homework';
import { activityLine, leaderLine } from '../core/activities';
import { boardingLines, routeLine } from '../core/transport';
import { consentLine, tripLine } from '../core/trips';
import { reportLines } from '../core/report';
import type {
  ChildAttendance,
  InboxItem,
  InvoiceSummary,
  ChildActivity,
  ChildTrip,
  ChildTransport,
  HomeworkItem,
  Me,
  PaymentOrder,
  ReportCard,
} from '../core/types';
import { Body, Button, Card, Heading, Notice } from '../ui';

type Child = Me['children'][number];

const oops = (e: unknown) => (e instanceof ApiError ? e.detail : 'Something went wrong.');
const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
const daysAgo = (n: number) =>
  new Date(Date.now() - n * 86400000).toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
const key = () => `app-${Math.random().toString(16).slice(2)}-${Date.now().toString(16)}`;

/** One screen for a guardian: pick a child, then see attendance, notices and fees. Only what the school allows is shown. */
export function ParentHome({ client, me, onSignOut }: { client: Client; me: Me; onSignOut: () => void }) {
  const [childId, setChildId] = useState(me.children[0]?.studentId ?? '');
  const child = me.children.find((c) => c.studentId === childId) ?? me.children[0];
  return (
    <ScrollView contentContainerStyle={{ padding: 16 }}>
      <Heading>{`Hello, ${me.displayName}`}</Heading>
      {me.children.length > 1 && (
        <View accessibilityRole="tablist" style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
          {me.children.map((c) => (
            <View key={c.studentId} style={{ marginRight: 8 }}>
              <Button
                label={c.displayName}
                quiet={c.studentId !== childId}
                onPress={() => setChildId(c.studentId)}
              />
            </View>
          ))}
        </View>
      )}
      {child && <ChildView key={child.studentId} client={client} child={child} />}
      <Inbox client={client} />
      <Button label="Sign out" quiet onPress={onSignOut} />
    </ScrollView>
  );
}

function ChildView({ client, child }: { client: Client; child: Child }) {
  return (
    <>
      <Heading>{child.displayName}</Heading>
      {child.campusName && <Body muted>{child.campusName}</Body>}
      {child.capabilities.includes('VIEW_ATTENDANCE') && (
        <>
          <Attendance client={client} studentId={child.studentId} />
          <Transport client={client} studentId={child.studentId} />
        </>
      )}
      {child.capabilities.includes('VIEW_ACADEMIC') && (
        <>
          <Activities client={client} studentId={child.studentId} />
          <Trips client={client} studentId={child.studentId} />
          <Homework client={client} studentId={child.studentId} />
          <ReportCards client={client} studentId={child.studentId} />
        </>
      )}
      {child.capabilities.includes('PAY_FEES') && <Fees client={client} studentId={child.studentId} />}
    </>
  );
}

function Attendance({ client, studentId }: { client: Client; studentId: string }) {
  const [data, setData] = useState<ChildAttendance | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    client
      .request<ChildAttendance>(`/api/v1/students/${studentId}/attendance?from=${daysAgo(29)}&to=${today()}`)
      .then((d) => live && setData(d))
      .catch((e: unknown) => live && setError(oops(e)));
    return () => {
      live = false;
    };
  }, [client, studentId]);
  const absences = data?.days.filter((d) => d.status === 'ABSENT' || d.status === 'LEAVE') ?? [];
  return (
    <Card>
      <Heading>Attendance, last 30 days</Heading>
      {error && <Notice kind="error">{error}</Notice>}
      {!data && !error && <Body muted>Loading…</Body>}
      {data && (
        <>
          <Body>{`Present ${data.present}, late ${data.late}, absent ${data.absent}, leave ${data.leave}`}</Body>
          {absences.map((d) => (
            <Body key={d.date} muted>{`${d.date}: ${d.status === 'ABSENT' ? 'absent' : 'on leave'}`}</Body>
          ))}
          {data.days.length === 0 && <Body muted>No attendance recorded yet.</Body>}
        </>
      )}
    </Card>
  );
}

function Activities({ client, studentId }: { client: Client; studentId: string }) {
  const [items, setItems] = useState<ChildActivity[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    client
      .request<ChildActivity[]>(`/api/v1/students/${studentId}/activities`)
      .then((d) => live && setItems(d))
      .catch((e: unknown) => {
        if (!live) return;
        if (e instanceof ApiError && e.status === 404) setItems([]);
        else setError(oops(e));
      });
    return () => {
      live = false;
    };
  }, [client, studentId]);
  if (items && items.length === 0) return null;
  return (
    <Card>
      <Heading>Activities</Heading>
      {error && <Notice kind="error">{error}</Notice>}
      {!items && !error && <Body muted>Loading…</Body>}
      {items?.map((a) => (
        <View key={a.activityId}>
          <Body strong>{activityLine(a)}</Body>
          {leaderLine(a) && <Body muted>{leaderLine(a)}</Body>}
        </View>
      ))}
    </Card>
  );
}

function Trips({ client, studentId }: { client: Client; studentId: string }) {
  const [items, setItems] = useState<ChildTrip[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    client
      .request<ChildTrip[]>(`/api/v1/students/${studentId}/trips`)
      .then(setItems)
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 404) setItems([]);
        else setError(oops(e));
      });
  }, [client, studentId]);
  useEffect(() => {
    load();
  }, [load]);
  async function decide(t: ChildTrip, decision: 'GIVEN' | 'DECLINED') {
    setError(null);
    try {
      await client.request(`/api/v1/trips/${t.tripId}/consent`, {
        method: 'POST',
        json: { studentId, decision },
      });
      load();
    } catch (e) {
      setError(oops(e));
    }
  }
  if (items && items.length === 0 && !error) return null;
  return (
    <Card>
      <Heading>Trips</Heading>
      {error && <Notice kind="error">{error}</Notice>}
      {!items && !error && <Body muted>Loading…</Body>}
      {items?.map((t) => (
        <View key={t.tripId}>
          <Body strong>{tripLine(t)}</Body>
          <Body muted>{consentLine(t)}</Body>
          {t.canDecide && t.consent !== 'GIVEN' && (
            <Button label={`Agree to ${t.name}`} onPress={() => void decide(t, 'GIVEN')} />
          )}
          {t.canDecide && t.consent !== 'DECLINED' && (
            <Button label={`Decline ${t.name}`} quiet onPress={() => void decide(t, 'DECLINED')} />
          )}
        </View>
      ))}
    </Card>
  );
}

function Transport({ client, studentId }: { client: Client; studentId: string }) {
  const [info, setInfo] = useState<ChildTransport | null | 'none'>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    client
      .request<ChildTransport>(`/api/v1/students/${studentId}/transport`)
      .then((d) => live && setInfo(d))
      .catch((e: unknown) => {
        // A child without a school bus is not an error.
        if (!live) return;
        if (e instanceof ApiError && e.status === 404) setInfo('none');
        else setError(oops(e));
      });
    return () => {
      live = false;
    };
  }, [client, studentId]);
  if (info === 'none') return null;
  return (
    <Card>
      <Heading>School bus</Heading>
      {error && <Notice kind="error">{error}</Notice>}
      {!info && !error && <Body muted>Loading…</Body>}
      {info && (
        <>
          <Body strong>{routeLine(info)}</Body>
          <Body muted>
            Driver {info.driverName}
            {info.driverPhone ? `, ${info.driverPhone}` : ''}
            {info.vehicle ? `, bus ${info.vehicle}` : ''}
          </Body>
          {boardingLines(info.today).length === 0 && <Body muted>No bus activity recorded yet today.</Body>}
          {boardingLines(info.today).map((l) => (
            <Body key={l}>{l}</Body>
          ))}
        </>
      )}
    </Card>
  );
}

function Homework({ client, studentId }: { client: Client; studentId: string }) {
  const [items, setItems] = useState<HomeworkItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    client
      .request<HomeworkItem[]>(`/api/v1/students/${studentId}/homework`)
      .then((d) => live && setItems(d))
      .catch((e: unknown) => live && setError(oops(e)));
    return () => {
      live = false;
    };
  }, [client, studentId]);
  return (
    <Card>
      <Heading>Homework</Heading>
      {error && <Notice kind="error">{error}</Notice>}
      {!items && !error && <Body muted>Loading…</Body>}
      {items?.length === 0 && <Body muted>No homework right now.</Body>}
      {items?.map((h) => (
        <View key={h.id} style={{ marginBottom: 8 }}>
          <Body strong>{homeworkLine(h, today())}</Body>
          {h.instructions && <Body muted>{h.instructions}</Body>}
        </View>
      ))}
    </Card>
  );
}

function ReportCards({ client, studentId }: { client: Client; studentId: string }) {
  const [cards, setCards] = useState<ReportCard[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    client
      .request<ReportCard[]>(`/api/v1/students/${studentId}/report-cards`)
      .then((d) => live && setCards(d))
      .catch((e: unknown) => live && setError(oops(e)));
    return () => {
      live = false;
    };
  }, [client, studentId]);
  return (
    <Card>
      <Heading>Report cards</Heading>
      {error && <Notice kind="error">{error}</Notice>}
      {!cards && !error && <Body muted>Loading…</Body>}
      {cards?.length === 0 && <Body muted>No report cards published yet.</Body>}
      {cards?.map((c) => (
        <View key={c.id} style={{ marginBottom: 10 }}>
          <Body strong>{`${c.termLabel}, ${c.sectionLabel}`}</Body>
          {reportLines(c).map((l) => (
            <Body key={l}>{l}</Body>
          ))}
        </View>
      ))}
    </Card>
  );
}

function Fees({ client, studentId }: { client: Client; studentId: string }) {
  const [list, setList] = useState<InvoiceSummary[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    client
      .request<InvoiceSummary[]>(`/api/v1/students/${studentId}/invoices`)
      .then(setList)
      .catch((e: unknown) => setError(oops(e)));
  }, [client, studentId]);
  useEffect(load, [load]);

  async function pay(inv: InvoiceSummary) {
    setBusy(inv.id);
    setMessage(null);
    setError(null);
    try {
      const order = await client.request<PaymentOrder>(`/api/v1/invoices/${inv.id}/pay`, {
        method: 'POST',
        json: {},
        headers: { 'Idempotency-Key': key() },
      });
      if (order.sandbox) {
        await client.request('/api/v1/payments/sandbox/complete', {
          method: 'POST',
          json: { orderId: order.orderId, outcome: 'SUCCEEDED' },
        });
        setMessage(`Test payment of ${formatPaise(order.amountPaise)} recorded.`);
      } else {
        setMessage('Your payment has started. It will show here once the bank confirms it.');
      }
    } catch (e) {
      setError(oops(e));
    } finally {
      setBusy(null);
      load();
    }
  }

  return (
    <Card>
      <Heading>Fees</Heading>
      {!list && !error && <Body muted>Loading…</Body>}
      {list?.length === 0 && <Body muted>No invoices.</Body>}
      {list?.map((i) => {
        const due = i.totalPaise - i.paidPaise;
        const open = i.status === 'ISSUED' || i.status === 'PARTIALLY_PAID';
        return (
          <View key={i.id} style={{ marginBottom: 8 }}>
            <Body strong>{i.number}</Body>
            <Body>{`${formatPaise(i.paidPaise)} paid of ${formatPaise(i.totalPaise)}, due ${i.dueOn}${i.overdue ? ' (overdue)' : ''}`}</Body>
            {open && (
              <Button
                label={`Pay ${formatPaise(due)}`}
                busy={busy === i.id}
                hint={`Pays invoice ${i.number}`}
                onPress={() => void pay(i)}
              />
            )}
            {i.status === 'PAID' && <Body muted>Paid in full</Body>}
          </View>
        );
      })}
      {message && <Notice kind="info">{message}</Notice>}
      {error && <Notice kind="error">{error}</Notice>}
    </Card>
  );
}

function Inbox({ client }: { client: Client }) {
  const [items, setItems] = useState<InboxItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    client
      .request<InboxItem[]>('/api/v1/notices/mine')
      .then(setItems)
      .catch((e: unknown) => setError(oops(e)));
  }, [client]);
  useEffect(load, [load]);
  return (
    <Card>
      <Heading>Notices</Heading>
      {error && <Notice kind="error">{error}</Notice>}
      {items?.length === 0 && <Body muted>No notices yet.</Body>}
      {items?.map((n) => (
        <View key={n.id} style={{ marginBottom: 10 }}>
          <Body strong>{`${n.read ? '' : 'New: '}${n.title}`}</Body>
          <Body>{n.body}</Body>
          {!n.read && (
            <Button
              label="Mark as read"
              quiet
              hint={`Marks ${n.title} as read`}
              onPress={() =>
                void client
                  .request(`/api/v1/notices/${n.id}/read`, { method: 'POST' })
                  .then(load)
                  .catch((e: unknown) => setError(oops(e)))
              }
            />
          )}
        </View>
      ))}
    </Card>
  );
}
