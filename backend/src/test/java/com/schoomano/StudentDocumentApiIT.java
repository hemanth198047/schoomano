package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B59: documents kept with a student are for the school office only, checked from their bytes, audited on download, and removed for a reason. */
class StudentDocumentApiIT extends ApiITSupport {

    static final String ANAYA = "13000000-0000-4000-8000-000000000020";
    static final String DOCS = "/api/v1/students/" + ANAYA + "/documents";
    static final byte[] PDF = "%PDF-1.7\nthe birth certificate".getBytes(StandardCharsets.US_ASCII);

    private static MockHttpServletRequestBuilder upload(String who, String name, String declared, byte[] bytes, String kind) {
        return multipart(DOCS).file(new MockMultipartFile("file", name, declared, bytes)).param("kind", kind).header("Authorization", dev(who));
    }

    @Test
    void theOfficeAddsReadsAndRemovesADocumentAndOthersSeeNothing() throws Exception {
        long before = ownerCount("select count(*) from student_document where student_id = '" + ANAYA + "'");
        try {
            String made = mvc.perform(upload("dev-asha", "../../Birth Certificate.pdf", "application/pdf", PDF, "BIRTH_CERTIFICATE").param("note", "Original seen"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("Birth Certificate.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(PDF.length))
                .andReturn().getResponse().getContentAsString();
            String id = JsonPath.read(made, "$.id");

            mvc.perform(get(DOCS).header("Authorization", dev("dev-asha"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].note").value("Original seen"));

            long reads = ownerCount("select count(*) from audit_event where action = 'student.document_read' and resource_id = '" + ANAYA + "'");
            mvc.perform(get(DOCS + "/" + id + "/content").header("Authorization", dev("dev-asha"))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(content().bytes(PDF));
            assertEquals(reads + 1, ownerCount("select count(*) from audit_event where action = 'student.document_read' and resource_id = '" + ANAYA + "'"),
                "a download is recorded");

            // A teacher, and a parent, are told there is nothing here.
            mvc.perform(get(DOCS).header("Authorization", dev("dev-ravi"))).andExpect(status().isNotFound());
            mvc.perform(get(DOCS + "/" + id + "/content").header("Authorization", dev("dev-ravi"))).andExpect(status().isNotFound());
            mvc.perform(upload("dev-ravi", "x.pdf", "application/pdf", PDF, "OTHER")).andExpect(status().isNotFound());

            // Removing needs a reason, takes the file, and the document stops being listed or downloadable.
            mvc.perform(post(DOCS + "/" + id + "/remove").header("Authorization", dev("dev-asha")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"short\"}")).andExpect(status().isBadRequest());
            mvc.perform(post(DOCS + "/" + id + "/remove").header("Authorization", dev("dev-asha")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Uploaded to the wrong child\"}")).andExpect(status().isOk());
            assertEquals(0, ownerCount("select count(*) from student_document_content where document_id = '" + id + "'"), "the file is gone");
            assertEquals(1, ownerCount("select count(*) from student_document where id = '" + id + "' and removed_at is not null and remove_reason is not null"),
                "the record of it stays");
            mvc.perform(get(DOCS + "/" + id + "/content").header("Authorization", dev("dev-asha"))).andExpect(status().isNotFound());
            mvc.perform(get(DOCS).header("Authorization", dev("dev-asha"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
            mvc.perform(post(DOCS + "/" + id + "/remove").header("Authorization", dev("dev-asha")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Removing it a second time\"}")).andExpect(status().isNotFound());
        } finally {
            cleanup();
        }
        assertTrue(before >= 0);
    }

    @Test
    void onlyPdfPngAndJpegAreAcceptedWhateverTheSenderSays() throws Exception {
        try {
            mvc.perform(upload("dev-asha", "page.pdf", "application/pdf", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8), "OTHER"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unsupported_file"));
            mvc.perform(upload("dev-asha", "run.exe", "application/octet-stream", "MZ\u0090\u0000\u0003".getBytes(StandardCharsets.ISO_8859_1), "OTHER"))
                .andExpect(status().isBadRequest());
            mvc.perform(upload("dev-asha", "empty.pdf", "application/pdf", new byte[0], "OTHER")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("empty_file"));
            mvc.perform(upload("dev-asha", "x.pdf", "application/pdf", PDF, "SELFIE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_kind"));
            byte[] big = new byte[5 * 1024 * 1024 + 1];
            System.arraycopy(PDF, 0, big, 0, PDF.length);
            mvc.perform(upload("dev-asha", "big.pdf", "application/pdf", big, "OTHER")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("file_too_large"));
            // A pdf named .exe is stored as a pdf, with a name that says so.
            mvc.perform(upload("dev-asha", "scan.exe", "application/pdf", PDF, "ID_PROOF")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("scan.pdf"));
            assertEquals(1, ownerCount("select count(*) from student_document where student_id = '" + ANAYA + "' and removed_at is null"));
        } finally {
            cleanup();
        }
    }

    private void cleanup() throws Exception {
        ownerUpdate("""
            alter table student_document disable trigger student_document_check;
            alter table student_document_content disable trigger student_document_content_check;
            delete from student_document_content where document_id in (select id from student_document where student_id = '%1$s');
            delete from student_document where student_id = '%1$s';
            alter table student_document enable trigger student_document_check;
            alter table student_document_content enable trigger student_document_content_check;
            """.formatted(ANAYA));
    }
}
