package com.schoomano.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.imports.Csv;
import com.schoomano.domain.imports.StudentImport;
import com.schoomano.domain.imports.StudentImport.RowPlan;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.imports.StudentImporter;
import com.schoomano.imports.Xlsx;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * B2 repeatable import of students from the old school system (M29, coexistence decision D5).
 * Upload validates and plans only (a dry run); commit applies exactly what the dry run showed, refusing if the
 * data changed in between; rollback reverts the latest committed batch, keeping anything edited since.
 * Requires IMPORT_MANAGE on the academic year's campus. Every step is audited.
 */
@RestController
@RequestMapping("/api/v1")
public class ImportController {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_ROWS = 20_000;
    static final int ROW_PAGE_MAX = 1000;

    private final TenantTx tenantTx;
    private final AcademicAccess access;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final StudentImporter importer;
    private final ObjectMapper json;
    private final Clock clock;

    public ImportController(TenantTx tenantTx, AcademicAccess access, Authorizer authz, AuditWriter audit,
                            StudentImporter importer, ObjectMapper json, Clock clock) {
        this.tenantTx = tenantTx;
        this.access = access;
        this.authz = authz;
        this.audit = audit;
        this.importer = importer;
        this.json = json;
        this.clock = clock;
    }

    public record RowView(int rowNo, String externalRef, String action, List<String> problems,
                          List<StudentImport.FieldChange> changes) { }
    public record BatchView(UUID id, String entity, String fileName, String status, UUID academicYearId,
                            String yearLabel, Map<String, Integer> counts, List<String> fileProblems,
                            List<String> missingFromFile, Instant createdAt, Instant committedAt, Instant rolledBackAt,
                            int rowsTotal, List<RowView> rows) { }
    public record CommitResult(BatchView batch, int created, int updated, int unchanged, int skipped) { }
    public record RollbackResult(BatchView batch, int removed, int restored, int linksRestored, List<String> kept) { }

    @PostMapping("/academic-years/{yearId}/imports/students")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchView validate(RequestContext ctx, @PathVariable UUID yearId, @RequestPart("file") MultipartFile file) {
        byte[] bytes = read(file);
        String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        List<List<String>> table = parse(bytes, name);
        if (table.size() > MAX_ROWS + 1) {
            throw ApiException.badRequest("too_many_rows", "Import at most " + MAX_ROWS + " rows per file.");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireImport(jdbc, ctx, null, access.campusPath(jdbc, ctx, year.campusId()));
            StudentImport.Plan plan = importer.plan(jdbc, table, yearId, today());
            UUID id = jdbc.queryForObject("""
                insert into import_batch (tenant_id, entity, academic_year_id, file_name, file_sha256, file_table,
                                          counts, file_problems, missing_refs, created_by)
                values (?, 'STUDENT', ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?) returning id
                """, UUID.class, ctx.tenant().value(), yearId, trimName(name), sha256(bytes), write(table),
                write(counts(plan)), write(plan.fileProblems()), write(plan.missingFromFile()), ctx.actor().personId());
            importer.storeRows(jdbc, ctx.tenant().value(), id, plan.rows());
            audit.write(jdbc, ctx, "import.validated", "import_batch", id.toString(), "SUCCESS", null,
                Map.of("entity", "STUDENT", "rows", plan.rows().size(), "counts", counts(plan)));
            return view(jdbc, id, null, 0, 200);
        });
    }

    @GetMapping("/academic-years/{yearId}/imports")
    public List<BatchView> list(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireImport(jdbc, ctx, null, access.campusPath(jdbc, ctx, year.campusId()));
            return jdbc.queryForList("select id from import_batch where academic_year_id = ? order by created_at desc limit 50",
                UUID.class, yearId).stream().map(id -> view(jdbc, id, null, 0, 0)).toList();
        });
    }

    @GetMapping("/imports/{batchId}")
    public BatchView get(RequestContext ctx, @PathVariable UUID batchId,
                         @RequestParam(required = false) String action,
                         @RequestParam(defaultValue = "0") int offset,
                         @RequestParam(defaultValue = "200") int limit) {
        if (action != null && !action.matches("^(CREATE|UPDATE|UNCHANGED|CONFLICT|ERROR)$")) {
            throw ApiException.badRequest("invalid_action", "Filter by CREATE, UPDATE, UNCHANGED, CONFLICT or ERROR.");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            load(jdbc, ctx, batchId);
            return view(jdbc, batchId, action, Math.max(0, offset), Math.min(Math.max(0, limit), ROW_PAGE_MAX));
        });
    }

    @PostMapping("/imports/{batchId}/commit")
    public CommitResult commit(RequestContext ctx, @PathVariable UUID batchId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Batch b = load(jdbc, ctx, batchId);
            if (!"VALIDATED".equals(b.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_validated", "Only a validated import can be committed.");
            }
            // Serialise imports per tenant so two commits cannot interleave.
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?::text, 11))::text", String.class,
                ctx.tenant().toString());
            List<List<String>> table = readTable(jdbc.queryForObject(
                "select file_table::text from import_batch where id = ?", String.class, batchId));
            StudentImport.Plan now = importer.plan(jdbc, table, b.yearId(), today());
            // The dry run must still be true: same action and same field changes for every row.
            List<String> stored = jdbc.query("select action, changes::text from import_row where batch_id = ? order by row_no",
                (rs, i) -> rs.getString(1) + "|" + readValue(rs.getString(2),
                    new TypeReference<List<StudentImport.FieldChange>>() { }), batchId);
            List<String> fresh = new ArrayList<>();
            for (RowPlan r : now.rows()) {
                fresh.add(r.action().name() + "|" + r.changes());
            }
            if (!stored.equals(fresh)) {
                throw new ApiException(HttpStatus.CONFLICT, "stale_preview",
                    "Student records changed since this file was checked. Check the file again before importing.");
            }
            StudentImporter.Applied applied = importer.commit(jdbc, ctx.tenant().value(), batchId, b.yearId(),
                b.campusId(), ctx.actor().personId(), now.rows());
            jdbc.update("""
                update import_batch set status = 'COMMITTED', committed_by = ?, committed_at = now() where id = ?
                """, ctx.actor().personId(), batchId);
            Map<String, Object> details = Map.of("created", applied.created(), "updated", applied.updated(),
                "unchanged", applied.unchanged(), "skipped", applied.skipped());
            audit.write(jdbc, ctx, "import.committed", "import_batch", batchId.toString(), "SUCCESS", null, details);
            jdbc.update("""
                insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                values (?, 'import_batch', ?, 'import.committed', ?::jsonb)
                """, ctx.tenant().value(), batchId.toString(), write(details));
            return new CommitResult(view(jdbc, batchId, null, 0, 0), applied.created(), applied.updated(),
                applied.unchanged(), applied.skipped());
        });
    }

    @PostMapping("/imports/{batchId}/discard")
    public BatchView discard(RequestContext ctx, @PathVariable UUID batchId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Batch b = load(jdbc, ctx, batchId);
            if (!"VALIDATED".equals(b.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_validated", "Only a validated import can be discarded.");
            }
            jdbc.update("update import_batch set status = 'DISCARDED' where id = ?", batchId);
            audit.write(jdbc, ctx, "import.discarded", "import_batch", batchId.toString(), "SUCCESS", null, Map.of());
            return view(jdbc, batchId, null, 0, 0);
        });
    }

    @PostMapping("/imports/{batchId}/rollback")
    public RollbackResult rollback(RequestContext ctx, @PathVariable UUID batchId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Batch b = load(jdbc, ctx, batchId);
            if (!"COMMITTED".equals(b.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_committed", "Only a committed import can be rolled back.");
            }
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?::text, 11))::text", String.class,
                ctx.tenant().toString());
            Integer later = jdbc.queryForObject("""
                select count(*)::int from import_batch
                 where entity = 'STUDENT' and status = 'COMMITTED' and committed_at > (select committed_at from import_batch where id = ?)
                """, Integer.class, batchId);
            if (later != null && later > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "not_latest",
                    "A later import has been committed. Roll that one back first.");
            }
            StudentImporter.Reverted r = importer.rollback(jdbc, ctx.tenant().value(), batchId, b.yearId(), b.campusId(),
                ctx.actor().personId());
            jdbc.update("""
                update import_batch set status = 'ROLLED_BACK', rolled_back_by = ?, rolled_back_at = now() where id = ?
                """, ctx.actor().personId(), batchId);
            audit.write(jdbc, ctx, "import.rolled_back", "import_batch", batchId.toString(), "SUCCESS", null,
                Map.of("removed", r.removed(), "restored", r.restored(), "kept", r.kept().size()));
            return new RollbackResult(view(jdbc, batchId, null, 0, 0), r.removed(), r.restored(), r.linksRestored(), r.kept());
        });
    }

    // ---- helpers --------------------------------------------------------------------------------

    private record Batch(UUID id, UUID yearId, UUID campusId, String status) { }

    private Batch load(JdbcTemplate jdbc, RequestContext ctx, UUID batchId) {
        Batch b = jdbc.query("""
            select b.id, b.academic_year_id, y.campus_id, b.status
              from import_batch b join academic_year y on y.id = b.academic_year_id where b.id = ?
            """, (rs, i) -> new Batch(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4)), batchId)
            .stream().findFirst().orElseThrow(ApiException::notFound);
        ScopePath campus = access.campusPath(jdbc, ctx, b.campusId());
        authz.requireOrNotFound(jdbc, ctx, Permission.CAMPUS_READ, Resource.ordinary("import_batch", batchId, campus, null));
        requireImport(jdbc, ctx, batchId, campus);
        return b;
    }

    private void requireImport(JdbcTemplate jdbc, RequestContext ctx, UUID id, ScopePath campus) {
        authz.requireOrNotFound(jdbc, ctx, Permission.CAMPUS_READ, Resource.ordinary("import_batch", id, campus, null));
        authz.requireOrForbidden(jdbc, ctx, Permission.IMPORT_MANAGE, Resource.ordinary("import_batch", id, campus, null));
    }

    private BatchView view(JdbcTemplate jdbc, UUID id, String action, int offset, int limit) {
        List<RowView> rows = limit == 0 ? List.of() : jdbc.query("""
            select row_no, external_ref, action, problems::text, changes::text from import_row
             where batch_id = ? and (?::text is null or action = ?)
             order by case action when 'ERROR' then 0 when 'CONFLICT' then 1 when 'CREATE' then 2 when 'UPDATE' then 3 else 4 end,
                      row_no
             offset ? limit ?
            """, (rs, i) -> new RowView(rs.getInt(1), rs.getString(2), rs.getString(3),
                readValue(rs.getString(4), new TypeReference<List<String>>() { }),
                readValue(rs.getString(5), new TypeReference<List<StudentImport.FieldChange>>() { })),
            id, action, action, offset, limit);
        Integer total = jdbc.queryForObject(
            "select count(*)::int from import_row where batch_id = ? and (?::text is null or action = ?)",
            Integer.class, id, action, action);
        return jdbc.queryForObject("""
            select b.id, b.entity, b.file_name, b.status, b.academic_year_id, y.label, b.counts::text,
                   b.file_problems::text, b.missing_refs::text, b.created_at, b.committed_at, b.rolled_back_at
              from import_batch b join academic_year y on y.id = b.academic_year_id where b.id = ?
            """, (rs, i) -> new BatchView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6),
                readValue(rs.getString(7), new TypeReference<Map<String, Integer>>() { }),
                readValue(rs.getString(8), new TypeReference<List<String>>() { }),
                firstHundred(readValue(rs.getString(9), new TypeReference<List<String>>() { })),
                instant(rs.getObject(10, OffsetDateTime.class)), instant(rs.getObject(11, OffsetDateTime.class)),
                instant(rs.getObject(12, OffsetDateTime.class)), total == null ? 0 : total, rows), id);
    }

    /** Responses list at most 100 missing references; counts.MISSING carries the total. */
    private static List<String> firstHundred(List<String> refs) {
        return refs.size() <= 100 ? refs : refs.subList(0, 100);
    }

    private static Map<String, Integer> counts(StudentImport.Plan plan) {
        Map<String, Integer> out = new LinkedHashMap<>();
        plan.counts().forEach((k, v) -> out.put(k.name(), v));
        out.put("MISSING", plan.missingFromFile().size());
        return out;
    }

    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("empty_file", "Choose a file to import.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("file_too_large", "Files up to 5 MB can be imported.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("unreadable_file", "The file could not be read.");
        }
    }

    static List<List<String>> parse(byte[] bytes, String name) {
        try {
            if (Xlsx.looksLikeXlsx(bytes)) {
                return Xlsx.firstSheet(bytes);
            }
            if (name.toLowerCase().endsWith(".xls")) {
                throw new IllegalArgumentException("Old .xls files are not supported. Save the sheet as .xlsx or .csv.");
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                text = new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
            }
            return Csv.parse(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unreadable_file", e.getMessage());
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Kolkata"));
    }

    private static String trimName(String name) {
        String base = name.replaceAll("^.*[/\\\\]", "");
        return base.length() > 200 ? base.substring(0, 200) : base;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<List<String>> readTable(String s) {
        return readValue(s, new TypeReference<List<List<String>>>() { });
    }

    private <T> T readValue(String s, TypeReference<T> type) {
        try {
            return json.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
