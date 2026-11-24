package com.schoomano.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.attendance.AttendanceStatus;
import com.schoomano.domain.attendance.BatchFingerprint;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B6 attendance (M06). Teachers mark with ATTENDANCE_MARK and send a batch with an Idempotency-Key, so a phone that
 * retries after a lost reply changes nothing twice. If the office corrected a mark while the phone was offline, the
 * phone's value is kept as a visible conflict and the current value stays; the office resolves it (ATTENDANCE_CORRECT).
 * Marking a child absent queues an outbox event so guardians can be told (delivered by the notices slice).
 */
@RestController
@RequestMapping("/api/v1")
public class AttendanceController {

    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int MAX_BATCH = 200;

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ObjectMapper json;
    private final Clock clock;

    public AttendanceController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access,
                                ObjectMapper json, Clock clock) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    public record RosterRow(UUID studentId, String schoolRef, String name, String status, Integer version) { }

    public record Roster(UUID sectionId, String sectionLabel, LocalDate date, List<RosterRow> students, int openConflicts) { }

    public record MarkRequest(@NotNull UUID studentId, @NotBlank String status, int baseVersion, OffsetDateTime markedAt) { }

    public record BatchRequest(@NotNull LocalDate date, @NotBlank String source,
                               @NotNull @Size(min = 1, max = MAX_BATCH) List<@Valid MarkRequest> marks) { }

    public record MarkResult(UUID studentId, String outcome, String status, Integer version, String message) { }

    public record BatchResult(List<MarkResult> results, int conflicts, boolean replayed) { }

    public record CorrectionRequest(@NotNull LocalDate date, @NotNull UUID studentId, @NotBlank String status,
                                    @NotBlank @Size(min = 3, max = 200) String reason) { }

    public record ConflictView(UUID id, UUID studentId, String studentName, String sectionLabel, LocalDate date,
                               String currentStatus, int currentVersion, String attemptedStatus, String attemptedBy,
                               OffsetDateTime attemptedMarkedAt) { }

    public record ResolveRequest(boolean useAttempt, @NotBlank @Size(min = 3, max = 200) String reason) { }

    // ---- roster ---------------------------------------------------------------------------------------------

    @GetMapping("/sections/{sectionId}/attendance")
    public Roster roster(RequestContext ctx, @PathVariable UUID sectionId, @RequestParam(required = false) LocalDate date) {
        LocalDate day = date != null ? date : today();
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = sectionPath(jdbc, ctx, sectionId);
            authz.requireOrNotFound(jdbc, ctx, Permission.ATTENDANCE_READ, Resource.ordinary("attendance", sectionId, path, null));
            String label = jdbc.queryForObject("select g.name || ' ' || s.code from class_section s join grade_level g on g.id = s.grade_level_id where s.id = ?",
                String.class, sectionId);
            List<RosterRow> rows = jdbc.query("""
                select p.id, p.school_ref, trim(p.given_name || ' ' || coalesce(p.family_name, '')), m.status, m.version
                  from student_enrollment e
                  join person p on p.id = e.student_person_id
                  left join attendance_session a on a.class_section_id = e.class_section_id and a.on_date = ?
                  left join attendance_mark m on m.session_id = a.id and m.student_person_id = p.id
                 where e.class_section_id = ? and e.status = 'ENROLLED'
                 order by p.given_name, p.family_name
                """, (rs, i) -> new RosterRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    (Integer) rs.getObject(5)), day, sectionId);
            int open = jdbc.queryForObject("""
                select count(*) from attendance_conflict c join attendance_mark m on m.id = c.mark_id
                  join attendance_session a on a.id = m.session_id
                 where c.state = 'OPEN' and a.class_section_id = ? and a.on_date = ?
                """, Integer.class, sectionId, day);
            return new Roster(sectionId, label, day, rows, open);
        });
    }

    // ---- marking --------------------------------------------------------------------------------------------

    @PostMapping("/sections/{sectionId}/attendance/batches")
    public BatchResult mark(RequestContext ctx, @PathVariable UUID sectionId,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                            @Valid @RequestBody BatchRequest req) {
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw ApiException.badRequest("idempotency_key_required", "Send an Idempotency-Key of 8 to 100 characters.");
        }
        if (!Set.of("DEVICE", "WEB").contains(req.source())) {
            throw ApiException.badRequest("invalid_source", "Source must be DEVICE or WEB.");
        }
        List<BatchFingerprint.Row> rows = new ArrayList<>();
        for (MarkRequest m : req.marks()) {
            AttendanceStatus st = AttendanceStatus.parse(m.status())
                .orElseThrow(() -> ApiException.badRequest("invalid_status", "Status must be PRESENT, ABSENT, LATE or LEAVE."));
            rows.add(new BatchFingerprint.Row(m.studentId(), st, Math.max(0, m.baseVersion())));
        }
        if (!BatchFingerprint.duplicates(rows).isEmpty()) {
            throw ApiException.badRequest("duplicate_student", "A student appears twice in this batch.");
        }
        String hash = BatchFingerprint.of(sectionId, req.date(), rows);

        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = sectionPath(jdbc, ctx, sectionId);
            authz.requireOrNotFound(jdbc, ctx, Permission.ATTENDANCE_READ, Resource.ordinary("attendance", sectionId, path, null));
            authz.requireOrForbidden(jdbc, ctx, Permission.ATTENDANCE_MARK, Resource.ordinary("attendance", sectionId, path, null));

            var previous = jdbc.query("select request_hash, response::text from attendance_submission where idempotency_key = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, key).stream().findFirst();
            if (previous.isPresent()) {
                if (!previous.get()[0].equals(hash)) {
                    throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused",
                        "That key was already used for a different set of marks. Use a new key.");
                }
                BatchResult first = read(previous.get()[1]);
                return new BatchResult(first.results(), first.conflicts(), true);
            }

            jdbc.queryForObject("select set_config('app.attendance_key', ?, true)", String.class, key);
            UUID sessionId = jdbc.queryForObject("""
                insert into attendance_session (tenant_id, class_section_id, on_date) values (?, ?, ?)
                on conflict (tenant_id, class_section_id, on_date) do update set on_date = excluded.on_date returning id
                """, UUID.class, ctx.tenant().value(), sectionId, req.date());
            Set<UUID> enrolled = new HashSet<>(jdbc.queryForList(
                "select student_person_id from student_enrollment where class_section_id = ? and status = 'ENROLLED'", UUID.class, sectionId));

            OffsetDateTime now = OffsetDateTime.now(clock);
            List<MarkResult> results = new ArrayList<>();
            int conflicts = 0;
            for (int i = 0; i < req.marks().size(); i++) {
                MarkRequest m = req.marks().get(i);
                AttendanceStatus st = rows.get(i).status();
                if (!enrolled.contains(m.studentId())) {
                    results.add(new MarkResult(m.studentId(), "REJECTED", null, null, "This student is not in this class."));
                    continue;
                }
                OffsetDateTime at = m.markedAt() == null || m.markedAt().isAfter(now) ? now : m.markedAt();
                String outcome = jdbc.queryForObject("select attendance_apply(?, ?, ?, ?, ?, ?, ?)", String.class,
                    sessionId, m.studentId(), st.name(), rows.get(i).baseVersion(), ctx.actor().personId(), at, req.source());
                var current = jdbc.queryForMap("select status, version from attendance_mark where session_id = ? and student_person_id = ?",
                    sessionId, m.studentId());
                String message = switch (outcome) {
                    case "CONFLICT" -> "Someone at the office changed this after you last synced. Your entry is kept for review.";
                    default -> null;
                };
                if ("CONFLICT".equals(outcome)) {
                    conflicts++;
                }
                if (("CREATED".equals(outcome) || "UPDATED".equals(outcome)) && st.notifiesGuardians()) {
                    outbox(jdbc, ctx, "student", m.studentId().toString(), "attendance.absent",
                        Map.of("studentId", m.studentId().toString(), "sectionId", sectionId.toString(), "date", req.date().toString()));
                }
                results.add(new MarkResult(m.studentId(), outcome, (String) current.get("status"), (Integer) current.get("version"), message));
            }
            BatchResult result = new BatchResult(results, conflicts, false);
            jdbc.update("""
                insert into attendance_submission (tenant_id, idempotency_key, session_id, request_hash, response, created_by)
                values (?, ?, ?, ?, ?::jsonb, ?)
                """, ctx.tenant().value(), key, sessionId, hash, write(result), ctx.actor().personId());
            audit.write(jdbc, ctx, "attendance.batch_recorded", "class_section", sectionId.toString(), "SUCCESS", null,
                Map.of("date", req.date().toString(), "marks", req.marks().size(), "conflicts", conflicts, "source", req.source()));
            return result;
        }));
    }

    /** Office correction: always applies, needs a reason, and is audited. */
    @PostMapping("/sections/{sectionId}/attendance/corrections")
    public MarkResult correct(RequestContext ctx, @PathVariable UUID sectionId, @Valid @RequestBody CorrectionRequest req) {
        AttendanceStatus st = AttendanceStatus.parse(req.status())
            .orElseThrow(() -> ApiException.badRequest("invalid_status", "Status must be PRESENT, ABSENT, LATE or LEAVE."));
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = sectionPath(jdbc, ctx, sectionId);
            authz.requireOrNotFound(jdbc, ctx, Permission.ATTENDANCE_READ, Resource.ordinary("attendance", sectionId, path, null));
            authz.requireOrForbidden(jdbc, ctx, Permission.ATTENDANCE_CORRECT, Resource.ordinary("attendance", sectionId, path, null));
            if (jdbc.queryForObject("select count(*) from student_enrollment where class_section_id = ? and student_person_id = ? and status = 'ENROLLED'",
                Integer.class, sectionId, req.studentId()) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "attendance_rejected", "This student is not in this class.");
            }
            UUID sessionId = jdbc.queryForObject("""
                insert into attendance_session (tenant_id, class_section_id, on_date) values (?, ?, ?)
                on conflict (tenant_id, class_section_id, on_date) do update set on_date = excluded.on_date returning id
                """, UUID.class, ctx.tenant().value(), sectionId, req.date());
            String outcome = jdbc.queryForObject("select attendance_apply(?, ?, ?, 0, ?, now(), 'OFFICE')", String.class,
                sessionId, req.studentId(), st.name(), ctx.actor().personId());
            var current = jdbc.queryForMap("select status, version from attendance_mark where session_id = ? and student_person_id = ?",
                sessionId, req.studentId());
            audit.write(jdbc, ctx, "attendance.corrected", "class_section", sectionId.toString(), "SUCCESS", req.reason().trim(),
                Map.of("date", req.date().toString(), "studentId", req.studentId().toString(), "status", st.name(), "outcome", outcome));
            if (("CREATED".equals(outcome) || "UPDATED".equals(outcome)) && st.notifiesGuardians()) {
                outbox(jdbc, ctx, "student", req.studentId().toString(), "attendance.absent",
                    Map.of("studentId", req.studentId().toString(), "sectionId", sectionId.toString(), "date", req.date().toString()));
            }
            return new MarkResult(req.studentId(), outcome, (String) current.get("status"), (Integer) current.get("version"), null);
        }));
    }

    // ---- conflicts ------------------------------------------------------------------------------------------

    @GetMapping("/campuses/{campusId}/attendance/conflicts")
    public List<ConflictView> conflicts(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = access.campusPath(jdbc, ctx, campusId);
            authz.requireOrNotFound(jdbc, ctx, Permission.ATTENDANCE_READ, Resource.ordinary("attendance", campusId, path, null));
            authz.requireOrForbidden(jdbc, ctx, Permission.ATTENDANCE_CORRECT, Resource.ordinary("attendance", campusId, path, null));
            return jdbc.query("""
                select c.id, m.student_person_id, trim(st.given_name || ' ' || coalesce(st.family_name, '')), g.name || ' ' || s.code,
                       a.on_date, c.current_status, c.current_version, c.attempted_status,
                       trim(t.given_name || ' ' || coalesce(t.family_name, '')), c.attempted_marked_at
                  from attendance_conflict c
                  join attendance_mark m on m.id = c.mark_id
                  join attendance_session a on a.id = m.session_id
                  join class_section s on s.id = a.class_section_id
                  join academic_year y on y.id = s.academic_year_id and y.campus_id = ?
                  join grade_level g on g.id = s.grade_level_id
                  join person st on st.id = m.student_person_id
                  join person t on t.id = c.attempted_by
                 where c.state = 'OPEN'
                 order by a.on_date, g.ordinal, s.code, st.given_name
                """, (rs, i) -> new ConflictView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    rs.getObject(5, LocalDate.class), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getString(9),
                    rs.getObject(10, OffsetDateTime.class)), campusId);
        });
    }

    @PostMapping("/attendance/conflicts/{conflictId}/resolve")
    public Map<String, Object> resolve(RequestContext ctx, @PathVariable UUID conflictId, @Valid @RequestBody ResolveRequest req) {
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            UUID sectionId = jdbc.queryForList("""
                select a.class_section_id from attendance_conflict c join attendance_mark m on m.id = c.mark_id
                  join attendance_session a on a.id = m.session_id where c.id = ?
                """, UUID.class, conflictId).stream().findFirst().orElseThrow(ApiException::notFound);
            ScopePath path = sectionPath(jdbc, ctx, sectionId);
            authz.requireOrNotFound(jdbc, ctx, Permission.ATTENDANCE_READ, Resource.ordinary("attendance", sectionId, path, null));
            authz.requireOrForbidden(jdbc, ctx, Permission.ATTENDANCE_CORRECT, Resource.ordinary("attendance", sectionId, path, null));
            jdbc.queryForList("select attendance_resolve(?, ?, ?)", conflictId, req.useAttempt(), ctx.actor().personId());
            audit.write(jdbc, ctx, "attendance.conflict_resolved", "attendance_conflict", conflictId.toString(), "SUCCESS",
                req.reason().trim(), Map.of("useAttempt", req.useAttempt()));
            return Map.<String, Object>of("id", conflictId, "resolution", req.useAttempt() ? "USED_ATTEMPT" : "KEPT_CURRENT");
        }));
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SCHOOL_ZONE));
    }

    /** A section the caller cannot see is 404; path comes from the section's year and campus. */
    private ScopePath sectionPath(JdbcTemplate jdbc, RequestContext ctx, UUID sectionId) {
        UUID yearId = jdbc.queryForList("select academic_year_id from class_section where id = ?", UUID.class, sectionId)
            .stream().findFirst().orElseThrow(ApiException::notFound);
        AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
        return access.campusPath(jdbc, ctx, year.campusId());
    }

    private String write(BatchResult r) {
        try {
            return json.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private BatchResult read(String text) {
        try {
            return json.readValue(text, BatchResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void outbox(JdbcTemplate jdbc, RequestContext ctx, String aggregateType, String aggregateId, String event,
                        Map<String, Object> payload) {
        try {
            jdbc.update("""
                insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                values (?, ?, ?, ?, ?::jsonb)
                """, ctx.tenant().value(), aggregateType, aggregateId, event, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The database's own refusals (future day, outside the year, closed year...) become plain messages. */
    private static <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataIntegrityViolationException e) {
            String raw = e.getMostSpecificCause().getMessage();
            String first = raw == null ? "" : raw.lines().findFirst().orElse("");
            if (first.contains("attendance_submission_pkey")) {
                throw new ApiException(HttpStatus.CONFLICT, "request_in_progress",
                    "This batch is already being processed. Try again in a moment.");
            }
            String message = "That change is not allowed.";
            if (first.startsWith("ERROR: ") && first.length() > 7) {
                String text = first.substring(7).trim();
                message = Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
            }
            throw new ApiException(HttpStatus.CONFLICT, "attendance_rejected", message);
        }
    }
}
