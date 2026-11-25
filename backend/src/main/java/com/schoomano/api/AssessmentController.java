package com.schoomano.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.domain.academic.GradeScale;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.assessment.ReportCalculator;
import com.schoomano.domain.assessment.ReportCalculator.Kind;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * B11 assessment and report cards (M09). A teacher creates an assessment (subject, term, maximum, weight), enters
 * marks, and locks it; a locked mark can still be corrected, but only with a written reason, and every change is
 * kept. Report cards are published for a whole class and term once every assessment is locked and every enrolled
 * child has a mark. A published card is a frozen snapshot (with the grade scale that applied); correcting it
 * publishes a new revision with a reason and the earlier ones stay.
 */
@RestController
@RequestMapping("/api/v1")
public class AssessmentController {

    private static final int MAX_BATCH = 200;

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ScopeDirectory scopes;
    private final ObjectMapper json;

    public AssessmentController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access,
                                ScopeDirectory scopes, ObjectMapper json) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.scopes = scopes;
        this.json = json;
    }

    public record CreateRequest(@NotNull UUID subjectId, @NotNull UUID termId,
                                @NotBlank @Size(min = 2, max = 80) String name,
                                @NotNull @DecimalMin("0.01") BigDecimal maxMarks,
                                @Min(1) @Max(100) int weightPercent, LocalDate heldOn) { }

    public record AssessmentView(UUID id, UUID sectionId, UUID subjectId, String subjectName, UUID termId, String termLabel,
                                 String name, BigDecimal maxMarks, int weightPercent, LocalDate heldOn, String status,
                                 int marksEntered, int students) { }

    public record MarkRow(UUID studentId, String schoolRef, String name, String kind, BigDecimal marks, Integer version) { }

    public record MarksView(AssessmentView assessment, List<MarkRow> marks) { }

    public record MarkEntry(@NotNull UUID studentId, @NotBlank String kind, BigDecimal marks) { }

    public record MarksRequest(@NotNull @Size(min = 1, max = MAX_BATCH) List<@Valid MarkEntry> entries,
                               @Size(max = 200) String reason) { }

    public record MarksResult(int created, int changed, int unchanged) { }

    public record SubjectLine(UUID subjectId, String name, BigDecimal percent, String grade, int assessments) { }

    public record StudentPreview(UUID studentId, String name, List<SubjectLine> subjects, BigDecimal overallPercent,
                                 String overallGrade, Integer publishedRevision, boolean changed) { }

    public record Preview(UUID sectionId, UUID termId, boolean ready, List<String> blockers, Integer gradeScaleVersion,
                          List<StudentPreview> students) { }

    public record PublishRequest(@Size(max = 200) String reason) { }

    public record PublishResult(int published, int unchanged) { }

    public record ReportCardView(UUID id, UUID termId, String termLabel, String sectionLabel, int revision,
                                 String revisionReason, List<SubjectLine> subjects, BigDecimal overallPercent,
                                 String overallGrade, JsonNode gradeScale, OffsetDateTime publishedAt) { }

    // ---- assessments ----------------------------------------------------------------------------------------

    @PostMapping("/sections/{sectionId}/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssessmentView create(RequestContext ctx, @PathVariable UUID sectionId, @Valid @RequestBody CreateRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireWrite(jdbc, ctx, sectionId);
            UUID id = UUID.randomUUID();
            try {
                jdbc.update("""
                    insert into assessment (id, tenant_id, class_section_id, subject_id, term_id, name, max_marks, weight_percent, held_on, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, ctx.tenant().value(), sectionId, req.subjectId(), req.termId(), req.name().trim(),
                    req.maxMarks(), req.weightPercent(), req.heldOn(), ctx.actor().personId());
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "duplicate_assessment", "This class already has an assessment with that name for this subject and term.");
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw ApiException.badRequest("invalid_assessment", "The subject and term must belong to this class's programme and year.");
            }
            audit.write(jdbc, ctx, "assessment.created", "assessment", id.toString(), "SUCCESS", null,
                Map.of("sectionId", sectionId, "name", req.name().trim(), "maxMarks", req.maxMarks(), "weightPercent", req.weightPercent()));
            return view(jdbc, id);
        });
    }

    @GetMapping("/sections/{sectionId}/assessments")
    public List<AssessmentView> list(RequestContext ctx, @PathVariable UUID sectionId, @RequestParam(required = false) UUID termId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireRead(jdbc, ctx, sectionId);
            return jdbc.query(VIEW_SQL + " where a.class_section_id = ? and (?::uuid is null or a.term_id = ?) order by t.starts_on, sub.name, a.name",
                (rs, i) -> mapView(rs), sectionId, termId, termId);
        });
    }

    @GetMapping("/assessments/{id}/marks")
    public MarksView marks(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AssessmentView a = viewOrNotFound(jdbc, id);
            requireRead(jdbc, ctx, a.sectionId());
            return new MarksView(a, jdbc.query("""
                select p.id, p.school_ref, trim(p.given_name || ' ' || coalesce(p.family_name, '')), m.kind, m.marks, m.version
                  from student_enrollment e
                  join person p on p.id = e.student_person_id
                  left join assessment_mark m on m.assessment_id = ? and m.student_person_id = p.id
                 where e.class_section_id = ? and e.status = 'ENROLLED'
                 order by p.given_name, p.family_name
                """, (rs, i) -> new MarkRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getBigDecimal(5), (Integer) rs.getObject(6)), id, a.sectionId()));
        });
    }

    @PutMapping("/assessments/{id}/marks")
    public MarksResult enter(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody MarksRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AssessmentView a = viewOrNotFound(jdbc, id);
            requireWrite(jdbc, ctx, a.sectionId());
            boolean locked = "LOCKED".equals(a.status());
            String reason = req.reason() == null ? null : req.reason().trim();
            if (locked && (reason == null || reason.length() < 5)) {
                throw ApiException.badRequest("reason_required", "This assessment is locked. Say why the marks are changing (at least 5 characters).");
            }
            Set<UUID> enrolled = Set.copyOf(jdbc.queryForList(
                "select student_person_id from student_enrollment where class_section_id = ? and status = 'ENROLLED'", UUID.class, a.sectionId()));
            Map<UUID, MarkEntry> wanted = new LinkedHashMap<>();
            for (MarkEntry e : req.entries()) {
                if (!enrolled.contains(e.studentId())) {
                    throw ApiException.badRequest("not_in_class", "A student in this list is not in this class.");
                }
                Kind kind = kind(e.kind());
                if (kind == Kind.SCORED) {
                    if (e.marks() == null || e.marks().signum() < 0 || e.marks().compareTo(a.maxMarks()) > 0 || e.marks().scale() > 2) {
                        throw ApiException.badRequest("invalid_marks", "Marks must be between 0 and " + a.maxMarks().stripTrailingZeros().toPlainString() + ", with at most two decimals.");
                    }
                } else if (e.marks() != null) {
                    throw ApiException.badRequest("invalid_marks", "Absent and exempt students have no marks.");
                }
                if (wanted.put(e.studentId(), e) != null) {
                    throw ApiException.badRequest("duplicate_student", "A student appears twice in this list.");
                }
            }
            Map<UUID, Object[]> current = new HashMap<>();
            jdbc.query("select student_person_id, id, kind, marks from assessment_mark where assessment_id = ?",
                rs -> { current.put(rs.getObject(1, UUID.class), new Object[] {rs.getObject(2, UUID.class), rs.getString(3), rs.getBigDecimal(4)}); }, id);
            int created = 0;
            int changed = 0;
            int unchanged = 0;
            for (MarkEntry e : wanted.values()) {
                Object[] cur = current.get(e.studentId());
                if (cur == null) {
                    jdbc.update("""
                        insert into assessment_mark (tenant_id, assessment_id, student_person_id, kind, marks, change_reason, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """, ctx.tenant().value(), id, e.studentId(), e.kind(), e.marks(), locked ? reason : null, ctx.actor().personId());
                    created++;
                } else if (cur[1].equals(e.kind()) && sameMarks((BigDecimal) cur[2], e.marks())) {
                    unchanged++;
                } else {
                    jdbc.update("update assessment_mark set kind = ?, marks = ?, change_reason = ?, updated_by = ? where id = ?",
                        e.kind(), e.marks(), locked ? reason : null, ctx.actor().personId(), cur[0]);
                    changed++;
                }
            }
            if (locked && (created + changed) > 0) {
                audit.write(jdbc, ctx, "assessment.marks_corrected", "assessment", id.toString(), "SUCCESS", reason,
                    Map.of("created", created, "changed", changed));
            }
            return new MarksResult(created, changed, unchanged);
        });
    }

    @PostMapping("/assessments/{id}/lock")
    public AssessmentView lock(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AssessmentView a = viewOrNotFound(jdbc, id);
            requireWrite(jdbc, ctx, a.sectionId());
            if ("LOCKED".equals(a.status())) {
                return a;
            }
            jdbc.update("update assessment set status = 'LOCKED', locked_by = ?, locked_at = now() where id = ?", ctx.actor().personId(), id);
            audit.write(jdbc, ctx, "assessment.locked", "assessment", id.toString(), "SUCCESS", null, Map.of("sectionId", a.sectionId()));
            return view(jdbc, id);
        });
    }

    // ---- report cards ---------------------------------------------------------------------------------------

    @GetMapping("/sections/{sectionId}/report-preview")
    public Preview preview(RequestContext ctx, @PathVariable UUID sectionId, @RequestParam UUID termId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireRead(jdbc, ctx, sectionId);
            return toPreview(compute(jdbc, sectionId, termId));
        });
    }

    @PostMapping("/sections/{sectionId}/report-cards/publish")
    public PublishResult publish(RequestContext ctx, @PathVariable UUID sectionId, @RequestParam UUID termId,
                                 @Valid @RequestBody(required = false) PublishRequest req) {
        String reason = req == null || req.reason() == null ? null : req.reason().trim();
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = access.sectionPath(jdbc, ctx, sectionId);
            authz.requireOrNotFound(jdbc, ctx, Permission.ASSESSMENT_READ, Resource.ordinary("assessment", sectionId, path, null));
            authz.requireOrForbidden(jdbc, ctx, Permission.REPORT_PUBLISH, Resource.ordinary("report_card", sectionId, path, null));
            Computation c = compute(jdbc, sectionId, termId);
            if (!c.blockers().isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "not_ready", "Report cards cannot be published yet.", Map.of("problems", c.blockers()));
            }
            List<StudentResult> todo = c.students().stream().filter(StudentResult::changed).toList();
            boolean revising = todo.stream().anyMatch(s -> s.publishedRevision() != null);
            if (revising && (reason == null || reason.length() < 5)) {
                throw ApiException.badRequest("reason_required", "Say why these report cards are being corrected (at least 5 characters).");
            }
            String scaleJson = write(c.scaleJson());
            for (StudentResult s : todo) {
                int revision = s.publishedRevision() == null ? 1 : s.publishedRevision() + 1;
                jdbc.update("""
                    insert into report_card (tenant_id, student_person_id, term_id, class_section_id, revision, revision_reason,
                                             grade_scale_version, grade_scale, subjects, overall_percent, overall_grade, published_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                    """, ctx.tenant().value(), s.studentId(), termId, sectionId, revision, revision == 1 ? null : reason,
                    c.scaleVersion(), scaleJson, write(s.lines()), s.overall(), s.grade(), ctx.actor().personId());
            }
            if (!todo.isEmpty()) {
                audit.write(jdbc, ctx, revising ? "report_card.revised" : "report_card.published", "class_section", sectionId.toString(),
                    "SUCCESS", reason, Map.of("termId", termId, "count", todo.size(), "gradeScaleVersion", c.scaleVersion()));
            }
            return new PublishResult(todo.size(), c.students().size() - todo.size());
        });
    }

    /** Latest revision of each term's card for one child. Guardians need view-academic; staff need academic read in scope. */
    @GetMapping("/students/{studentId}/report-cards")
    public List<ReportCardView> forStudent(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.STUDENT_READ_ACADEMIC, Resource.ordinary("report_card", null, path, studentId));
            return jdbc.query("""
                select distinct on (r.term_id) r.id, r.term_id, t.label, g.name || ' ' || s.code, r.revision, r.revision_reason,
                       r.subjects::text, r.overall_percent, r.overall_grade, r.grade_scale::text, r.published_at
                  from report_card r
                  join academic_term t on t.id = r.term_id
                  join class_section s on s.id = r.class_section_id
                  join grade_level g on g.id = s.grade_level_id
                 where r.student_person_id = ?
                 order by r.term_id, r.revision desc
                """, (rs, i) -> new ReportCardView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    rs.getInt(5), rs.getString(6), lines(rs.getString(7)), rs.getBigDecimal(8), rs.getString(9), parse(rs.getString(10)),
                    rs.getObject(11, OffsetDateTime.class)), studentId).stream()
                .sorted(java.util.Comparator.comparing(ReportCardView::publishedAt).reversed()).toList();
        });
    }

    // ---- computation ----------------------------------------------------------------------------------------

    private record StudentResult(UUID studentId, String name, List<SubjectLine> lines, BigDecimal overall, String grade,
                                 Integer publishedRevision, boolean changed) { }

    private record Computation(UUID sectionId, UUID termId, List<String> blockers, JsonNode scaleJson, Integer scaleVersion,
                               List<StudentResult> students) { }

    private Computation compute(JdbcTemplate jdbc, UUID sectionId, UUID termId) {
        Map<String, Object> term = jdbc.queryForList("""
            select t.ends_on, g.programme_id from class_section s
              join grade_level g on g.id = s.grade_level_id
              join academic_term t on t.id = ? and t.programme_id = g.programme_id and t.academic_year_id = s.academic_year_id
             where s.id = ?
            """, termId, sectionId).stream().findFirst().orElseThrow(ApiException::notFound);
        List<String> blockers = new ArrayList<>();

        List<Object[]> assessments = jdbc.query("""
            select a.id, a.name, a.status, a.max_marks, a.weight_percent, a.subject_id, sub.name
              from assessment a join subject sub on sub.id = a.subject_id
             where a.class_section_id = ? and a.term_id = ? order by sub.name, a.name
            """, (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5), rs.getObject(6, UUID.class), rs.getString(7)}, sectionId, termId);
        if (assessments.isEmpty()) {
            blockers.add("There are no assessments for this class and term.");
        }
        List<String> open = assessments.stream().filter(a -> "OPEN".equals(a[2])).map(a -> a[6] + " – " + a[1]).toList();
        if (!open.isEmpty()) {
            blockers.add("Not locked yet: " + String.join(", ", open) + ".");
        }

        List<Object[]> students = jdbc.query("""
            select p.id, trim(p.given_name || ' ' || coalesce(p.family_name, '')) from student_enrollment e
              join person p on p.id = e.student_person_id
             where e.class_section_id = ? and e.status = 'ENROLLED' order by p.given_name, p.family_name
            """, (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}, sectionId);
        if (students.isEmpty()) {
            blockers.add("There are no enrolled students in this class.");
        }

        Map<String, Object[]> marks = new HashMap<>();
        jdbc.query("""
            select m.assessment_id, m.student_person_id, m.kind, m.marks from assessment_mark m
              join assessment a on a.id = m.assessment_id where a.class_section_id = ? and a.term_id = ?
            """, rs -> { marks.put(rs.getObject(1, UUID.class) + "|" + rs.getObject(2, UUID.class), new Object[] {rs.getString(3), rs.getBigDecimal(4)}); },
            sectionId, termId);
        int missing = 0;
        for (Object[] s : students) {
            for (Object[] a : assessments) {
                if (!marks.containsKey(a[0] + "|" + s[0])) {
                    missing++;
                }
            }
        }
        if (missing > 0) {
            blockers.add(missing + (missing == 1 ? " mark is" : " marks are") + " missing (enter a score, absent or exempt for every student).");
        }

        List<Object[]> scale = jdbc.query("""
            select version, value::text from config_version
             where config_key = ? and scope_level = 'PROGRAMME' and scope_id = ? and status = 'PUBLISHED'
               and effective_from <= ?::date::timestamptz and (effective_to is null or effective_to > ?::date::timestamptz)
             order by version desc limit 1
            """, (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2)}, GradeScale.CONFIG_KEY, term.get("programme_id"),
            term.get("ends_on"), term.get("ends_on"));
        GradeScale gradeScale = null;
        JsonNode scaleJson = null;
        Integer scaleVersion = null;
        if (scale.isEmpty()) {
            blockers.add("No grade scale is in force for this programme at the end of the term.");
        } else {
            scaleJson = parse((String) scale.get(0)[1]);
            scaleVersion = (Integer) scale.get(0)[0];
            List<GradeScale.Band> bands = new ArrayList<>();
            for (JsonNode b : scaleJson.get("bands")) {
                bands.add(new GradeScale.Band(b.get("grade").asText(), b.get("minPercent").asInt()));
            }
            gradeScale = new GradeScale(bands);
        }

        List<StudentResult> results = new ArrayList<>();
        if (blockers.isEmpty()) {
            Map<UUID, Object[]> published = new HashMap<>();
            jdbc.query("""
                select distinct on (student_person_id) student_person_id, revision, subjects::text, overall_percent
                  from report_card where class_section_id = ? and term_id = ? order by student_person_id, revision desc
                """, rs -> { published.put(rs.getObject(1, UUID.class), new Object[] {rs.getInt(2), rs.getString(3), rs.getBigDecimal(4)}); },
                sectionId, termId);
            for (Object[] s : students) {
                List<ReportCalculator.Result> inputs = new ArrayList<>();
                for (Object[] a : assessments) {
                    Object[] m = marks.get(a[0] + "|" + s[0]);
                    inputs.add(new ReportCalculator.Result((UUID) a[5], (String) a[6], (UUID) a[0], (BigDecimal) a[3], (BigDecimal) a[4],
                        Kind.valueOf((String) m[0]), (BigDecimal) m[1]));
                }
                ReportCalculator.Outcome out = ReportCalculator.calculate(inputs, gradeScale);
                if (out == null) {
                    blockers.add(s[1] + " has nothing to grade (every assessment is exempt).");
                    continue;
                }
                List<SubjectLine> lines = out.subjects().stream()
                    .map(x -> new SubjectLine(x.subjectId(), x.subjectName(), x.percent(), x.grade(), x.assessments())).toList();
                Object[] pub = published.get((UUID) s[0]);
                boolean changed = pub == null || !sameLines(lines((String) pub[1]), lines)
                    || ((BigDecimal) pub[2]).compareTo(out.overallPercent()) != 0;
                results.add(new StudentResult((UUID) s[0], (String) s[1], lines, out.overallPercent(), out.overallGrade(),
                    pub == null ? null : (Integer) pub[0], changed));
            }
        }
        return new Computation(sectionId, termId, List.copyOf(blockers), scaleJson, scaleVersion, results);
    }

    private static Preview toPreview(Computation c) {
        return new Preview(c.sectionId(), c.termId(), c.blockers().isEmpty(), c.blockers(), c.scaleVersion(),
            c.students().stream().map(s -> new StudentPreview(s.studentId(), s.name(), s.lines(), s.overall(), s.grade(),
                s.publishedRevision(), s.changed())).toList());
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static final String VIEW_SQL = """
        select a.id, a.class_section_id, a.subject_id, sub.name, a.term_id, t.label, a.name, a.max_marks, a.weight_percent,
               a.held_on, a.status,
               (select count(*) from assessment_mark m where m.assessment_id = a.id),
               (select count(*) from student_enrollment e where e.class_section_id = a.class_section_id and e.status = 'ENROLLED')
          from assessment a
          join subject sub on sub.id = a.subject_id
          join academic_term t on t.id = a.term_id
        """;

    private static AssessmentView mapView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AssessmentView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
            rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getInt(9),
            rs.getObject(10, LocalDate.class), rs.getString(11), rs.getInt(12), rs.getInt(13));
    }

    private AssessmentView view(JdbcTemplate jdbc, UUID id) {
        return viewOrNotFound(jdbc, id);
    }

    private AssessmentView viewOrNotFound(JdbcTemplate jdbc, UUID id) {
        return jdbc.query(VIEW_SQL + " where a.id = ?", (rs, i) -> mapView(rs), id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private void requireRead(JdbcTemplate jdbc, RequestContext ctx, UUID sectionId) {
        ScopePath path = access.sectionPath(jdbc, ctx, sectionId);
        authz.requireOrNotFound(jdbc, ctx, Permission.ASSESSMENT_READ, Resource.ordinary("assessment", sectionId, path, null));
    }

    private void requireWrite(JdbcTemplate jdbc, RequestContext ctx, UUID sectionId) {
        ScopePath path = access.sectionPath(jdbc, ctx, sectionId);
        authz.requireOrNotFound(jdbc, ctx, Permission.ASSESSMENT_READ, Resource.ordinary("assessment", sectionId, path, null));
        authz.requireOrForbidden(jdbc, ctx, Permission.ASSESSMENT_WRITE, Resource.ordinary("assessment", sectionId, path, null));
    }

    private static Kind kind(String s) {
        try {
            return Kind.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_kind", "Kind must be SCORED, ABSENT or EXEMPT.");
        }
    }

    private static boolean sameLines(List<SubjectLine> a, List<SubjectLine> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            SubjectLine x = a.get(i);
            SubjectLine y = b.get(i);
            if (!x.subjectId().equals(y.subjectId()) || x.percent().compareTo(y.percent()) != 0
                || !x.grade().equals(y.grade()) || x.assessments() != y.assessments()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameMarks(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<SubjectLine> lines(String text) {
        List<SubjectLine> out = new ArrayList<>();
        for (JsonNode n : parse(text)) {
            out.add(new SubjectLine(UUID.fromString(n.get("subjectId").asText()), n.get("name").asText(),
                n.get("percent").decimalValue(), n.get("grade").asText(), n.get("assessments").asInt()));
        }
        return out;
    }
}
