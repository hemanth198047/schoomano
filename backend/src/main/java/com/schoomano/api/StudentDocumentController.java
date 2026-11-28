package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.documents.UploadRules;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Documents kept with a student (birth certificate, transfer certificate, ID proof, medical letter). Only school staff who may
 * manage people at the student's campus can see, add, download or remove them; a guardian or teacher gets "not found". Every
 * download is recorded. The file is checked from its bytes (PDF, PNG or JPEG, at most 5 MB) and always downloads as an attachment.
 */
@RestController
@RequestMapping("/api/v1/students/{studentId}/documents")
public class StudentDocumentController {

    private static final Set<String> KINDS = Set.of("BIRTH_CERTIFICATE", "TRANSFER_CERTIFICATE", "ID_PROOF", "MEDICAL", "OTHER");

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final ScopeDirectory scopes;
    private final AuditWriter audit;

    public StudentDocumentController(TenantTx tenantTx, Authorizer authz, ScopeDirectory scopes, AuditWriter audit) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.scopes = scopes;
        this.audit = audit;
    }

    public record DocumentView(UUID id, String kind, String fileName, String contentType, int sizeBytes, String note, UUID uploadedBy,
                               OffsetDateTime uploadedAt) { }

    public record RemoveRequest(@NotBlank @Size(min = 10, max = 300) String reason) { }

    @GetMapping
    public List<DocumentView> list(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, studentId);
            return jdbc.query("""
                select id, kind, file_name, content_type, size_bytes, note, uploaded_by, uploaded_at
                  from student_document where student_id = ? and removed_at is null order by uploaded_at desc, id
                """, (rs, i) -> view(rs), studentId);
        });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentView upload(RequestContext ctx, @PathVariable UUID studentId, @RequestPart("file") MultipartFile file,
                               @RequestParam String kind, @RequestParam(required = false) String note) {
        if (!KINDS.contains(kind)) {
            throw ApiException.badRequest("invalid_kind", "The kind must be one of " + String.join(", ", KINDS.stream().sorted().toList()) + ".");
        }
        if (note != null && note.length() > 300) {
            throw ApiException.badRequest("note_too_long", "The note can be at most 300 characters.");
        }
        byte[] bytes = read(file);
        String type = UploadRules.typeOf(bytes).orElseThrow(() ->
            ApiException.badRequest("unsupported_file", "Only PDF, PNG and JPEG files can be added."));
        String name = UploadRules.cleanName(file.getOriginalFilename(), type);
        String hash = UploadRules.sha256(bytes);
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, studentId);
            UUID id = jdbc.queryForObject("""
                insert into student_document (tenant_id, student_id, kind, file_name, content_type, size_bytes, sha256, note, uploaded_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), studentId, kind, name, type, bytes.length, hash,
                note == null || note.isBlank() ? null : note.trim(), ctx.actor().personId());
            jdbc.update("insert into student_document_content (tenant_id, document_id, content) values (?, ?, ?)", ctx.tenant().value(), id, bytes);
            audit.write(jdbc, ctx, "student.document_added", "student", studentId.toString(), "SUCCESS", null,
                Map.of("documentId", id.toString(), "kind", kind, "contentType", type, "bytes", bytes.length));
            return jdbc.queryForObject("""
                select id, kind, file_name, content_type, size_bytes, note, uploaded_by, uploaded_at from student_document where id = ?
                """, (rs, i) -> view(rs), id);
        });
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> download(RequestContext ctx, @PathVariable UUID studentId, @PathVariable UUID documentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, studentId);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.file_name, d.content_type, c.content from student_document d
                  join student_document_content c on c.tenant_id = d.tenant_id and c.document_id = d.id
                 where d.id = ? and d.student_id = ? and d.removed_at is null
                """, documentId, studentId);
            if (rows.isEmpty()) {
                throw ApiException.notFound();
            }
            Map<String, Object> r = rows.get(0);
            audit.write(jdbc, ctx, "student.document_read", "student", studentId.toString(), "SUCCESS", null, Map.of("documentId", documentId.toString()));
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, UploadRules.contentDisposition((String) r.get("file_name")))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType((String) r.get("content_type")))
                .body((byte[]) r.get("content"));
        });
    }

    @PostMapping("/{documentId}/remove")
    public Map<String, Object> remove(RequestContext ctx, @PathVariable UUID studentId, @PathVariable UUID documentId,
                                      @Valid @RequestBody RemoveRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, studentId);
            int n = jdbc.update("""
                update student_document set removed_at = now(), removed_by = ?, remove_reason = ?
                 where id = ? and student_id = ? and removed_at is null
                """, ctx.actor().personId(), req.reason().trim(), documentId, studentId);
            if (n == 0) {
                throw ApiException.notFound();
            }
            jdbc.update("delete from student_document_content where document_id = ?", documentId);
            audit.write(jdbc, ctx, "student.document_removed", "student", studentId.toString(), "SUCCESS", req.reason().trim(),
                Map.of("documentId", documentId.toString()));
            return Map.of("removed", true);
        });
    }

    private void require(JdbcTemplate jdbc, RequestContext ctx, UUID studentId) {
        if (jdbc.queryForList("select id from person where id = ?", UUID.class, studentId).isEmpty()) {
            throw ApiException.notFound();
        }
        ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElse(ScopePath.group(ctx.tenant()));
        authz.requireOrNotFound(jdbc, ctx, Permission.PERSON_MANAGE, Resource.ordinary("student", studentId, path, null));
    }

    private static DocumentView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DocumentView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
            rs.getObject(7, UUID.class), rs.getObject(8, OffsetDateTime.class));
    }

    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("empty_file", "Choose a file to add.");
        }
        if (file.getSize() > UploadRules.MAX_BYTES) {
            throw ApiException.badRequest("file_too_large", "Files up to 5 MB can be added.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("unreadable_file", "The file could not be read.");
        }
    }
}
