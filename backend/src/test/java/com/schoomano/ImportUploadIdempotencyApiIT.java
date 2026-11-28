package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** B40: a file upload repeated with the same Idempotency-Key makes one import batch, not two. */
class ImportUploadIdempotencyApiIT extends ApiITSupport {

    static final String YEAR = "15000000-0000-4000-8000-000000000001";
    static final String CSV = "Admission No,First Name,Last Name,Programme,Grade,Section\nIDU-1,Tara,Menon,CBSE-K12,8,A\n";

    private ResultActions upload(String year, String name, String csv, String key) throws Exception {
        var request = multipart("/api/v1/academic-years/" + year + "/imports/students")
            .file(new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", dev("dev-asha"));
        return mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
    }

    private long batches() {
        return ownerCount("select count(*) from import_batch where file_name like 'idem-up-%'");
    }

    @Test
    void aRepeatedUploadReturnsTheFirstBatchAndMakesNoSecond() throws Exception {
        String first = upload(YEAR, "idem-up-a.csv", CSV, "upload-it-key-0001").andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replay")).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(first, "$.id");
        assertEquals(1, batches());

        // The same file with the same key: the same answer, flagged as a replay, and no new batch.
        upload(YEAR, "idem-up-a.csv", CSV, "upload-it-key-0001").andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replay", "true")).andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.counts.CREATE").value(1));
        assertEquals(1, batches());

        // The same key for different content, or another file name, is refused rather than answered with the wrong batch.
        upload(YEAR, "idem-up-a.csv", CSV.replace("IDU-1", "IDU-2"), "upload-it-key-0001").andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
        upload(YEAR, "idem-up-b.csv", CSV, "upload-it-key-0001").andExpect(status().isUnprocessableEntity());
        assertEquals(1, batches());

        // Without a key, or with a new one, each upload is its own batch as before.
        upload(YEAR, "idem-up-c.csv", CSV, null).andExpect(status().isCreated());
        upload(YEAR, "idem-up-c.csv", CSV, "upload-it-key-0002").andExpect(status().isCreated());
        assertEquals(3, batches());

        // A key that is not well formed is refused.
        upload(YEAR, "idem-up-d.csv", CSV, "short").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_idempotency_key"));

        // A request that fails is forgotten, so the same key can be used again once the problem is fixed.
        String nowhere = "15000000-0000-4000-8000-0000000000ff";
        upload(nowhere, "idem-up-e.csv", CSV, "upload-it-key-0003").andExpect(status().isNotFound());
        upload(YEAR, "idem-up-e.csv", CSV, "upload-it-key-0003").andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotent-Replay"));
        assertEquals(4, batches());

        ownerUpdate("""
            delete from import_row where batch_id in (select id from import_batch where file_name like 'idem-up-%');
            delete from import_batch where file_name like 'idem-up-%';""");
    }
}
