package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.schoomano.api.ImportPurger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** B37: settled imports lose their file and row values after retention, keep their record, and can no longer be rolled back. */
class ImportPurgeApiIT extends ApiITSupport {

    static final String BATCH = "1d000000-0000-4000-8000-0000000000f1";

    @Autowired
    ImportPurger purger;

    @Test
    void oldCommittedImportIsBlankedAndCannotBeRolledBack() throws Exception {
        ownerUpdate("""
            insert into import_batch (id, tenant_id, entity, academic_year_id, file_name, file_sha256, file_table, status, counts, created_by, created_at, committed_at)
            values ('%1$s', '10000000-0000-4000-8000-000000000001', 'STUDENT', '15000000-0000-4000-8000-000000000001', 'it-old.csv', '\\x0f', '[["name"],["Asha"]]',
                    'COMMITTED', '{"CREATE":1,"UPDATE":0,"CONFLICT":0,"ERROR":0}', '13000000-0000-4000-8000-000000000001', now() - interval '200 days', now() - interval '200 days')
            on conflict (id) do nothing;
            insert into import_row (tenant_id, batch_id, row_no, external_ref, action, incoming, changes, before_values, applied)
            values ('10000000-0000-4000-8000-000000000001', '%1$s', 1, 'IT-ADM-1', 'CREATE', '{"name":"Asha"}', '[{"field":"name","to":"Asha"}]', '{"name":"Before"}', true)
            on conflict do nothing;
            """.formatted(BATCH));

        assertTrue(purger.runOnce(90, 7) >= 1);
        assertEquals(0, purger.runOnce(90, 7));
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'import.purged' and actor_kind = 'SYSTEM' and resource_id = '" + BATCH + "'"));

        assertEquals("[]", ownerString("select file_table::text from import_batch where id = '" + BATCH + "'"));
        assertEquals(0, ownerCount("select count(*) from import_row where batch_id = '" + BATCH + "' and (incoming <> '{}'::jsonb or before_values is not null)"));
        mvc.perform(get("/api/v1/imports/" + BATCH).header("Authorization", dev("dev-asha"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMMITTED")).andExpect(jsonPath("$.purgedAt").isNotEmpty())
            .andExpect(jsonPath("$.counts.CREATE").value(1)).andExpect(jsonPath("$.rows[0].externalRef").value("IT-ADM-1"))
            .andExpect(jsonPath("$.rows[0].changes").isEmpty());
        mvc.perform(post("/api/v1/imports/" + BATCH + "/rollback").header("Authorization", dev("dev-asha"))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("import_purged"));
    }
}
