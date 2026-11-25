package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.people.IndianMobile;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * B12 admissions (M02). An enquiry becomes an application, an offer, an acceptance and finally an enrolment, one
 * stage at a time. Ending an application needs a reason, an offer checks the seats left (an administrator may
 * override with a reason), and enrolling creates the child, the guardian and the class placement in one step.
 * Every move is kept in a permanent history.
 */
@RestController
@RequestMapping("/api/v1")
public class AdmissionController {

    private static final Set<String> STAGES = Set.of("APPLIED", "OFFERED", "ACCEPTED", "DECLINED", "WITHDRAWN", "REJECTED");
    private static final Set<String> NEEDS_REASON = Set.of("DECLINED", "WITHDRAWN", "REJECTED");

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;

    public AdmissionController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
    }

    public record CreateRequest(@NotNull UUID gradeLevelId,
                                @NotBlank @Size(max = 80) String childGivenName, @Size(max = 80) String childFamilyName,
                                LocalDate dateOfBirth,
                                @NotBlank @Size(max = 80) String guardianGivenName, @Size(max = 80) String guardianFamilyName,
                                @NotBlank String guardianMobile, @Size(max = 200) String guardianEmail,
                                @NotNull @Pattern(regexp = "^(WALK_IN|PHONE|WEBSITE|REFERRAL|OTHER)$") String source,
                                @Size(max = 1000) String notes) { }

    public record Application(UUID id, UUID yearId, UUID gradeLevelId, String gradeName, String programmeCode,
                              String childName, LocalDate dateOfBirth, String guardianName, String guardianMobile,
                              String guardianEmail, String source, String notes, String status, UUID studentId,
                              OffsetDateTime createdAt) { }

    public record Seats(UUID gradeLevelId, String gradeName, String programmeCode, Integer capacity, int enrolled, int held) { }

    public record Listing(List<Application> items, List<Seats> seats) { }

    public record EventView(String from, String to, String reason, String by, OffsetDateTime at) { }

    public record Detail(Application application, List<EventView> history) { }

    public record TransitionRequest(@NotBlank String to, @Size(max = 500) String reason, boolean overrideSeats) { }

    public record EnrolRequest(@NotNull UUID sectionId) { }

    public record Enrolled(UUID applicationId, UUID studentId, String schoolRef) { }

    // ---- create and read ------------------------------------------------------------------------------------

    @PostMapping("/academic-years/{yearId}/admissions")
    @ResponseStatus(HttpStatus.CREATED)
    public Application create(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody CreateRequest req) {
        String mobile = IndianMobile.normalise(req.guardianMobile()).orElseThrow(() -> ApiException.badRequest("invalid_mobile",
            "Enter a 10-digit Indian mobile number, for example 98450 12345."));
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, access.campusPath(jdbc, ctx, year.campusId()), null);
            boolean gradeOk = !jdbc.queryForList("""
                select g.id from grade_level g join programme p on p.id = g.programme_id where g.id = ? and p.campus_id = ?
                """, UUID.class, req.gradeLevelId(), year.campusId()).isEmpty();
            if (!gradeOk) {
                throw ApiException.badRequest("invalid_grade", "That grade is not offered on this campus.");
            }
            UUID id;
            try {
                id = jdbc.queryForObject("""
                    insert into admission_application (tenant_id, campus_id, academic_year_id, grade_level_id, child_given_name,
                        child_family_name, date_of_birth, guardian_given_name, guardian_family_name, guardian_mobile_e164,
                        guardian_email, source, notes, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), year.campusId(), yearId, req.gradeLevelId(), req.childGivenName().trim(),
                    blank(req.childFamilyName()), req.dateOfBirth(), req.guardianGivenName().trim(), blank(req.guardianFamilyName()),
                    mobile, blank(req.guardianEmail()), req.source(), blank(req.notes()), ctx.actor().personId());
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "already_open",
                    "This child already has an open application for this year with that guardian mobile.");
            }
            event(jdbc, ctx, id, null, "ENQUIRY", null);
            audit.write(jdbc, ctx, "admission.created", "admission_application", id.toString(), "SUCCESS", null,
                Map.of("yearId", yearId.toString(), "source", req.source()));
            return load(jdbc, id);
        });
    }

    @GetMapping("/academic-years/{yearId}/admissions")
    public Listing list(RequestContext ctx, @PathVariable UUID yearId, @RequestParam(required = false) String status) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireRead(jdbc, ctx, access.campusPath(jdbc, ctx, year.campusId()), null);
            List<Application> items = jdbc.query(SELECT + " where a.academic_year_id = ? and (?::text is null or a.status = ?) order by a.created_at desc",
                (rs, i) -> map(rs), yearId, status, status);
            List<Seats> seats = jdbc.query("""
                select g.id, g.name, p.code, case when bool_or(s.capacity is null) then null else sum(s.capacity)::int end,
                       (select count(*) from student_enrollment e join class_section cs on cs.id = e.class_section_id
                         where cs.grade_level_id = g.id and cs.academic_year_id = ? and e.status = 'ENROLLED'),
                       (select count(*) from admission_application a where a.grade_level_id = g.id and a.academic_year_id = ?
                           and a.status in ('OFFERED','ACCEPTED'))
                  from grade_level g
                  join programme p on p.id = g.programme_id and p.campus_id = ?
                  left join class_section s on s.grade_level_id = g.id and s.academic_year_id = ?
                 group by g.id, g.name, g.ordinal, p.code
                having count(s.id) > 0
                 order by p.code, g.ordinal
                """, (rs, i) -> new Seats(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4),
                    rs.getInt(5), rs.getInt(6)), yearId, yearId, year.campusId(), yearId);
            return new Listing(items, seats);
        });
    }

    @GetMapping("/admissions/{id}")
    public Detail detail(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Application a = loadOrNotFound(jdbc, id);
            requireRead(jdbc, ctx, campusOf(jdbc, ctx, a.yearId()), id);
            List<EventView> history = jdbc.query("""
                select e.from_status, e.to_status, e.reason, coalesce(p.given_name || ' ' || coalesce(p.family_name, ''), ''), e.at
                  from admission_event e left join person p on p.id = e.actor_person_id
                 where e.application_id = ? order by e.at, e.id
                """, (rs, i) -> new EventView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4).trim(),
                    rs.getObject(5, OffsetDateTime.class)), id);
            return new Detail(a, history);
        });
    }

    // ---- moves ----------------------------------------------------------------------------------------------

    @PostMapping("/admissions/{id}/transition")
    public Application transition(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody TransitionRequest req) {
        if (!STAGES.contains(req.to())) {
            throw ApiException.badRequest("invalid_stage", "Move to APPLIED, OFFERED, ACCEPTED, DECLINED, WITHDRAWN or REJECTED. Use enrol to enrol.");
        }
        String reason = blank(req.reason());
        if (NEEDS_REASON.contains(req.to()) && (reason == null || reason.length() < 5)) {
            throw ApiException.badRequest("reason_required", "Say why (at least 5 characters).");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Application a = loadOrNotFound(jdbc, id);
            requireManage(jdbc, ctx, campusOf(jdbc, ctx, a.yearId()), id);
            if ("OFFERED".equals(req.to())) {
                Seats s = seatsFor(jdbc, a);
                if (s.capacity() != null && s.enrolled() + s.held() >= s.capacity()) {
                    if (!req.overrideSeats() || reason == null || reason.length() < 5) {
                        throw new ApiException(HttpStatus.CONFLICT, "no_seats", "No seats are left in " + a.gradeName()
                            + " (" + s.enrolled() + " enrolled, " + s.held() + " offered, " + s.capacity() + " places). "
                            + "An administrator can offer anyway by giving a reason.");
                    }
                }
            }
            try {
                jdbc.update("update admission_application set status = ? where id = ?", req.to(), id);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "invalid_move", "An application cannot move from " + a.status() + " to " + req.to() + ".");
            }
            event(jdbc, ctx, id, a.status(), req.to(), reason);
            audit.write(jdbc, ctx, "admission." + req.to().toLowerCase(java.util.Locale.ROOT), "admission_application", id.toString(),
                "SUCCESS", reason, Map.of("from", a.status(), "overrideSeats", req.overrideSeats()));
            return load(jdbc, id);
        });
    }

    @PostMapping("/admissions/{id}/enrol")
    public Enrolled enrol(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody EnrolRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Application a = loadOrNotFound(jdbc, id);
            requireManage(jdbc, ctx, campusOf(jdbc, ctx, a.yearId()), id);
            if (!"ACCEPTED".equals(a.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_accepted", "Only an accepted offer can be enrolled.");
            }
            List<Object[]> sec = jdbc.query("""
                select s.capacity, g.programme_id, p.campus_id,
                       (select count(*) from student_enrollment e where e.class_section_id = s.id and e.status = 'ENROLLED')
                  from class_section s join grade_level g on g.id = s.grade_level_id join programme p on p.id = g.programme_id
                 where s.id = ? and s.academic_year_id = ? and s.grade_level_id = ?
                """, (rs, i) -> new Object[] {(Integer) rs.getObject(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4)},
                req.sectionId(), a.yearId(), a.gradeLevelId());
            if (sec.isEmpty()) {
                throw ApiException.badRequest("invalid_section", "Choose a class of the grade applied for, in this year.");
            }
            Integer capacity = (Integer) sec.get(0)[0];
            if (capacity != null && (Integer) sec.get(0)[3] >= capacity) {
                throw new ApiException(HttpStatus.CONFLICT, "class_full", "That class is full.");
            }
            UUID programmeId = (UUID) sec.get(0)[1];
            UUID campusId = (UUID) sec.get(0)[2];
            Map<String, Object> raw = jdbc.queryForMap("""
                select child_given_name, child_family_name, date_of_birth, guardian_given_name, guardian_family_name,
                       guardian_mobile_e164, guardian_email from admission_application where id = ?
                """, id);
            String ref = jdbc.queryForObject("""
                select 'STU-' || lpad((coalesce(max(substring(school_ref from '^STU-([0-9]+)$')::int), 0) + 1)::text, 5, '0') from person
                """, String.class);
            UUID student = jdbc.queryForObject("""
                insert into person (tenant_id, school_ref, given_name, family_name, date_of_birth, created_by, provenance)
                values (?, ?, ?, ?, ?, ?, '{"source":"ADMISSION"}'::jsonb) returning id
                """, UUID.class, ctx.tenant().value(), ref, raw.get("child_given_name"), raw.get("child_family_name"),
                raw.get("date_of_birth"), ctx.actor().personId());
            jdbc.update("""
                insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id, programme_id, class_section_id)
                values (?, ?, ?, ?, ?, ?)
                """, ctx.tenant().value(), student, campusId, a.yearId(), programmeId, req.sectionId());
            String mobile = (String) raw.get("guardian_mobile_e164");
            List<UUID> existing = jdbc.queryForList("select id from person where mobile_e164 = ?", UUID.class, mobile);
            UUID guardian;
            if (existing.isEmpty()) {
                String gref = jdbc.queryForObject("""
                    select 'GRD-' || lpad((coalesce(max(substring(school_ref from '^GRD-([0-9]+)$')::int), 0) + 1)::text, 5, '0') from person
                    """, String.class);
                guardian = jdbc.queryForObject("""
                    insert into person (tenant_id, school_ref, given_name, family_name, mobile_e164, email, created_by, provenance)
                    values (?, ?, ?, ?, ?, ?, ?, '{"source":"ADMISSION"}'::jsonb) returning id
                    """, UUID.class, ctx.tenant().value(), gref, raw.get("guardian_given_name"), raw.get("guardian_family_name"), mobile,
                    raw.get("guardian_email"), ctx.actor().personId());
            } else {
                guardian = existing.get(0);
            }
            jdbc.update("""
                insert into guardian_relationship (tenant_id, guardian_person_id, student_person_id, relationship_type,
                    can_view_attendance, can_view_academic, can_pay_fees, can_give_consent, can_receive_notices, can_pickup,
                    status, updated_by, updated_at)
                values (?, ?, ?, 'GUARDIAN', true, true, true, true, true, false, 'ACTIVE', ?, now())
                """, ctx.tenant().value(), guardian, student, ctx.actor().personId());
            UUID household = jdbc.queryForObject("insert into household (tenant_id, name) values (?, ?) returning id", UUID.class,
                ctx.tenant().value(), (raw.get("child_family_name") != null ? raw.get("child_family_name") : raw.get("child_given_name")) + " household");
            jdbc.update("insert into household_member (tenant_id, household_id, person_id, member_role) values (?, ?, ?, 'STUDENT')",
                ctx.tenant().value(), household, student);
            jdbc.update("insert into household_member (tenant_id, household_id, person_id, member_role) values (?, ?, ?, 'GUARDIAN') on conflict do nothing",
                ctx.tenant().value(), household, guardian);
            jdbc.update("update admission_application set status = 'ENROLLED', student_person_id = ?, class_section_id = ? where id = ?",
                student, req.sectionId(), id);
            event(jdbc, ctx, id, "ACCEPTED", "ENROLLED", null);
            audit.write(jdbc, ctx, "admission.enrolled", "admission_application", id.toString(), "SUCCESS", null,
                Map.of("studentId", student.toString(), "sectionId", req.sectionId().toString(), "guardianReused", !existing.isEmpty()));
            return new Enrolled(id, student, ref);
        });
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static final String SELECT = """
        select a.id, a.academic_year_id, a.grade_level_id, g.name, p.code,
               trim(a.child_given_name || ' ' || coalesce(a.child_family_name, '')), a.date_of_birth,
               trim(a.guardian_given_name || ' ' || coalesce(a.guardian_family_name, '')), a.guardian_mobile_e164,
               a.guardian_email, a.source, a.notes, a.status, a.student_person_id, a.created_at
          from admission_application a
          join grade_level g on g.id = a.grade_level_id
          join programme p on p.id = g.programme_id
        """;

    private static Application map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Application(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getString(8), IndianMobile.display(rs.getString(9)),
            rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getObject(14, UUID.class),
            rs.getObject(15, OffsetDateTime.class));
    }

    private Application load(JdbcTemplate jdbc, UUID id) {
        return loadOrNotFound(jdbc, id);
    }

    private Application loadOrNotFound(JdbcTemplate jdbc, UUID id) {
        return jdbc.query(SELECT + " where a.id = ?", (rs, i) -> map(rs), id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private ScopePath campusOf(JdbcTemplate jdbc, RequestContext ctx, UUID yearId) {
        return access.campusPath(jdbc, ctx, access.loadYear(jdbc, ctx, yearId).campusId());
    }

    private Seats seatsFor(JdbcTemplate jdbc, Application a) {
        return jdbc.query("""
            select (case when bool_or(s.capacity is null) then null else sum(s.capacity)::int end),
                   (select count(*) from student_enrollment e join class_section cs on cs.id = e.class_section_id
                     where cs.grade_level_id = ? and cs.academic_year_id = ? and e.status = 'ENROLLED'),
                   (select count(*) from admission_application x where x.grade_level_id = ? and x.academic_year_id = ?
                       and x.status in ('OFFERED','ACCEPTED'))
              from class_section s where s.grade_level_id = ? and s.academic_year_id = ?
            """, (rs, i) -> new Seats(a.gradeLevelId(), a.gradeName(), a.programmeCode(), (Integer) rs.getObject(1), rs.getInt(2), rs.getInt(3)),
            a.gradeLevelId(), a.yearId(), a.gradeLevelId(), a.yearId(), a.gradeLevelId(), a.yearId()).get(0);
    }

    private void event(JdbcTemplate jdbc, RequestContext ctx, UUID id, String from, String to, String reason) {
        jdbc.update("""
            insert into admission_event (tenant_id, application_id, from_status, to_status, reason, actor_person_id)
            values (?, ?, ?, ?, ?, ?)
            """, ctx.tenant().value(), id, from, to, reason, ctx.actor().personId());
    }

    private void requireRead(JdbcTemplate jdbc, RequestContext ctx, ScopePath campus, UUID id) {
        authz.requireOrNotFound(jdbc, ctx, Permission.ADMISSION_READ, Resource.ordinary("admission", id, campus, null));
    }

    private void requireManage(JdbcTemplate jdbc, RequestContext ctx, ScopePath campus, UUID id) {
        requireRead(jdbc, ctx, campus, id);
        authz.requireOrForbidden(jdbc, ctx, Permission.ADMISSION_MANAGE, Resource.ordinary("admission", id, campus, null));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
