package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.schoomano.api.HealthAlerter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** B46: administrators are told once when a check starts needing attention, again after a day, and afresh after it recovers. */
class HealthAlertApiIT extends ApiITSupport {

    @Autowired
    HealthAlerter alerter;

    private static final String TITLE = "Needs attention: Emergency access reviews";

    private long notices() throws Exception {
        return ownerCount("select count(*) from notice where kind = 'OPERATIONS' and title = '" + TITLE + "' and tenant_id = '10000000-0000-4000-8000-000000000001'");
    }

    private void overdueGrant() throws Exception {
        ownerUpdate("""
            insert into break_glass_grant (tenant_id, person_id, resource_type, resource_id, justification, granted_at, expires_at)
            values ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', 'health_record', gen_random_uuid(),
                    'IT-OPS emergency access nobody has reviewed', now() - interval '2 days', now() - interval '2 days' + interval '1 hour')""");
    }

    private void clean() throws Exception {
        ownerUpdate("""
            delete from break_glass_grant where justification like 'IT-OPS%';
            delete from ops_alert_state where check_key = 'breakglass'""");
    }

    @Test
    void administratorsAreToldOnceThenAgainAfterADayAndAfreshAfterRecovery() throws Exception {
        try {
            clean();
            long before = notices();
            alerter.runOnce();
            assertEquals(before, notices(), "a check that is fine tells nobody");

            overdueGrant();
            alerter.runOnce();
            assertEquals(before + 1, notices());
            assertTrue(ownerCount("select count(*) from notice_delivery d join notice n on n.id = d.notice_id where n.title = '" + TITLE + "' and d.channel = 'IN_APP'"
                + " and n.tenant_id = '10000000-0000-4000-8000-000000000001' and d.recipient_person_id in (select person_id from role_grant where role_key = 'group_admin')") >= 1);
            assertEquals(1, ownerCount("select count(*) from notice where title = '" + TITLE + "' and body = '1 waiting for review, 1 for more than a day.'"));

            alerter.runOnce();
            assertEquals(before + 1, notices(), "told once, not every pass");

            ownerUpdate("update ops_alert_state set notified_at = now() - interval '25 hours' where check_key = 'breakglass' and tenant_id = '10000000-0000-4000-8000-000000000001'");
            alerter.runOnce();
            assertEquals(before + 2, notices(), "reminded after a day");
            alerter.runOnce();
            assertEquals(before + 2, notices());

            ownerUpdate("delete from break_glass_grant where justification like 'IT-OPS%'");
            alerter.runOnce();
            assertEquals("OK", ownerString("select level from ops_alert_state where check_key = 'breakglass' and tenant_id = '10000000-0000-4000-8000-000000000001'"));
            assertEquals(before + 2, notices(), "nothing is sent when a check recovers");

            overdueGrant();
            alerter.runOnce();
            assertEquals(before + 3, notices(), "a new problem is a new alert");
        } finally {
            clean();
        }
    }
}
