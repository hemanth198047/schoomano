package com.schoomano.platform;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns outbox events into notices and sends the SMS that are due. All cross-school work is done by the database's
 * notice_* functions (which decide recipients and quiet hours); this class only calls them and talks to the SMS
 * provider. Safe to run on several instances: the database leases each message to one sender at a time. A message
 * that fails is retried with a growing delay and ends FAILED after three attempts.
 */
@Component
public class NoticeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NoticeDispatcher.class);
    private static final int BATCH = 50;

    private record Due(UUID id, String phone, String text) { }

    private final TenantTx tx;
    private final SmsSender sms;
    private final boolean enabled;

    public NoticeDispatcher(TenantTx tx, SmsSender sms, @Value("${schoomano.notices.dispatcher:true}") boolean enabled) {
        this.tx = tx;
        this.sms = sms;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${schoomano.notices.poll-ms:15000}", initialDelayString = "${schoomano.notices.poll-ms:15000}")
    void tick() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("notice dispatch failed", e);
        }
    }

    /** One pass: consume outbox events, then send due SMS. Returns the number of SMS attempted. */
    public int runOnce() {
        Integer events = tx.runWithoutTenant(jdbc -> jdbc.queryForObject("select notice_process_outbox(?)", Integer.class, BATCH));
        if (events != null && events > 0) {
            log.info("notices created from {} outbox events", events);
        }
        List<Due> due = tx.runWithoutTenant(jdbc -> jdbc.query("select delivery_id, phone, text_body from notice_claim_due_sms(?)",
            (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), BATCH));
        for (Due d : due) {
            boolean ok = true;
            String error = null;
            try {
                sms.sendText(d.phone(), d.text());
            } catch (RuntimeException e) {
                ok = false;
                error = e.getClass().getSimpleName();   // never the message: providers may echo the number
            }
            boolean sent = ok;
            String why = error;
            tx.runWithoutTenant(jdbc -> jdbc.queryForList("select notice_mark_sms(?, ?, ?)", d.id(), sent, why));
        }
        return due.size();
    }
}
