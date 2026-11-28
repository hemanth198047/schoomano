package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B43: the audit trail as a CSV file, for those who may read it, safe to open in a spreadsheet, and itself audited. */
class AuditExportApiIT extends ApiITSupport {

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    @Test
    void exportsThePeriodAsSafeCsvAndRecordsTheExport() throws Exception {
        ownerUpdate("""
            insert into audit_event (tenant_id, actor_kind, action, resource_type, resource_id, outcome, reason, details)
            values ('10000000-0000-4000-8000-000000000001', 'SYSTEM', 'it.export.probe', 'probe', 'p1', 'SUCCESS', '=HYPERLINK("http://x"),y', '{"n":1}'),
                   ('10000000-0000-4000-8000-000000000001', 'SYSTEM', 'it.export.probe', 'probe', 'p2', 'SUCCESS', 'plain', '{}')""");
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

        String csv = mvc.perform(as(get("/api/v1/audit/export?action=it.export.probe&from=" + today + "&to=" + today), "dev-asha"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
            .andExpect(header().string("X-Export-Rows", "2")).andExpect(header().doesNotExist("X-Export-Truncated"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"audit-" + today + "-to-" + today + ".csv\""))
            .andReturn().getResponse().getContentAsString();
        String[] lines = csv.split("\r\n");
        assertEquals(3, lines.length);
        assertEquals("seq,occurred_at,actor_kind,actor,action,resource_type,resource_id,outcome,reason,correlation_id,details,prev_hash,hash", lines[0]);
        assertTrue(lines[1].contains(",it.export.probe,probe,p1,SUCCESS,\"'=HYPERLINK(\"\"http://x\"\"),y\","), lines[1]);
        assertTrue(lines[1].matches(".*,[0-9a-f]{64}$"), "each row ends with its hash");
        assertTrue(lines[2].contains(",p2,SUCCESS,plain,"));

        // The export is on the record, with who asked and for what, and the period is respected.
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'audit.exported' and details->>'from' = '" + today + "' and (details->>'rows')::int = 2"));
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1).toString();
        mvc.perform(as(get("/api/v1/audit/export?action=it.export.probe&from=" + tomorrow), "dev-asha")).andExpect(status().isOk())
            .andExpect(header().string("X-Export-Rows", "0"));
        mvc.perform(as(get("/api/v1/audit/export?from=" + tomorrow + "&to=" + today), "dev-asha")).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/v1/audit/export?from=not-a-date"), "dev-asha")).andExpect(status().isBadRequest());

        // Only people who may read the trail, and only their own school's entries.
        mvc.perform(as(get("/api/v1/audit/export"), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/audit/export"), "dev-ravi")).andExpect(status().isNotFound());
        String other = mvc.perform(as(get("/api/v1/audit/export?action=it.export.probe"), "dev-other")).andReturn().getResponse().getContentAsString();
        assertFalse(other.contains("it.export.probe,probe"), "another school's entries are never included");
    }
}
