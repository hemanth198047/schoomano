package com.schoomano.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.domain.access.AccessDecision;
import com.schoomano.domain.access.GuardianCapability;
import com.schoomano.domain.access.GuardianLink;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.people.IndianMobile;
import com.schoomano.domain.people.RelationshipRules;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * B3 students and guardians (M03, M01). Staff see and manage students through role grants; guardians see
 * their own child through the relationship, limited to its capabilities, and never see other guardians.
 * Fields owned by the old school system during coexistence (see B2) cannot be edited here.
 * Every change is audited; relationship changes require a reason.
 */
@RestController
@RequestMapping("/api/v1")
public class StudentController {

    private final TenantTx tenantTx;
    private final ScopeDirectory scopes;
    private final Authorizer authz;
    private final AcademicAccess access;
    private final AuditWriter audit;
    private final ObjectMapper json;
    private final Clock clock;

    public StudentController(TenantTx tenantTx, ScopeDirectory scopes, Authorizer authz, AcademicAccess access,
                             AuditWriter audit, ObjectMapper json, Clock clock) {
        this.tenantTx = tenantTx;
        this.scopes = scopes;
        this.authz = authz;
        this.access = access;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---- views ----------------------------------------------------------------------------------

    public record StudentListItem(UUID id, String schoolRef, String displayName, String programmeCode, String gradeName,
                                  String sectionCode, UUID sectionId, int guardians, String source, String status) { }
    public record StudentPage(int total, List<StudentListItem> students) { }
    public record Placement(UUID academicYearId, String yearLabel, String campusName, String programmeCode,
                            String gradeName, String sectionCode, UUID sectionId, String status) { }
    public record GuardianView(UUID relationshipId, UUID guardianId, String displayName, String givenName,
                               String familyName, String mobile, String email, String relationshipType, String status,
                               List<String> capabilities, boolean sharesHousehold) { }
    public record StudentProfile(UUID id, String schoolRef, String givenName, String familyName, String preferredName,
                                 LocalDate dateOfBirth, String status, String view, String source,
                                 List<String> ownedFields, List<Placement> placements, List<GuardianView> guardians,
                                 List<String> households, List<String> yourCapabilities) { }

    // ---- requests -------------------------------------------------------------------------------

    public record StudentCreate(@NotBlank @Size(max = 80) String givenName, @Size(max = 80) String familyName,
                                @Size(max = 80) String preferredName, LocalDate dateOfBirth,
                                @Pattern(regexp = "^[A-Za-z0-9/_-]{1,40}$") String schoolRef, UUID sectionId) { }
    public record StudentUpdate(@Size(min = 1, max = 80) String givenName, @Size(max = 80) String familyName,
                                @Size(max = 80) String preferredName, LocalDate dateOfBirth, UUID academicYearId,
                                UUID sectionId, Boolean clearSection, @Size(max = 500) String reason) { }
    public record GuardianAdd(@NotBlank @Size(max = 80) String givenName, @Size(max = 80) String familyName,
                              @NotBlank String mobile, @Size(max = 200) String email,
                              @NotNull @Pattern(regexp = "^(MOTHER|FATHER|GUARDIAN|GRANDPARENT|OTHER)$") String relationshipType,
                              @NotNull @Pattern(regexp = "^(ACTIVE|RESTRICTED)$") String status,
                              @NotNull List<GuardianCapability> capabilities, boolean pickupConfirmed,
                              @NotBlank @Size(max = 500) String reason) { }
    public record RelationshipUpdate(@NotNull @Pattern(regexp = "^(ACTIVE|RESTRICTED|SUSPENDED|ENDED)$") String status,
                                     @NotNull List<GuardianCapability> capabilities, boolean pickupConfirmed,
                                     @NotBlank @Size(max = 500) String reason) { }
    public record GuardianUpdate(@Size(min = 1, max = 80) String givenName, @Size(max = 80) String familyName,
                                 String mobile, @Size(max = 200) String email, @NotBlank @Size(max = 500) String reason) { }

    // ---- reads ----------------------------------------------------------------------------------

    @GetMapping("/academic-years/{yearId}/students")
    public StudentPage list(RequestContext ctx, @PathVariable UUID yearId, @RequestParam(required = false) String q,
                            @RequestParam(required = false) UUID sectionId,
                            @RequestParam(name = "offset", defaultValue = "0") int offsetParam,
                            @RequestParam(name = "limit", defaultValue = "50") int limitParam) {
        int offset = Math.max(0, offsetParam);
        int limit = Math.min(Math.max(1, limitParam), 500);
        String like = q == null || q.isBlank() ? null : "%" + q.trim().replace("%", "").replace("_", "") + "%";
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            ScopePath campus = access.campusPath(jdbc, ctx, year.campusId());
            authz.requireOrNotFound(jdbc, ctx, Permission.STUDENT_READ_BASIC, Resource.ordinary("student", null, campus, null));
            String where = """
                  from student_enrollment e join person p on p.id = e.student_person_id
                  left join class_section s on s.id = e.class_section_id
                  left join grade_level g on g.id = s.grade_level_id
                  left join programme pr on pr.id = coalesce(g.programme_id, e.programme_id)
                 where e.academic_year_id = ?
                   and (?::text is null or p.given_name ilike ? or p.family_name ilike ? or p.preferred_name ilike ?
                        or p.school_ref ilike ?)
                   and (?::uuid is null or s.id = ?)
                """;
            Object[] args = {yearId, like, like, like, like, like, sectionId, sectionId};
            Integer total = jdbc.queryForObject("select count(*)::int " + where, Integer.class, args);
            List<Object> page = new ArrayList<>(java.util.Arrays.asList(args));  // args may hold nulls
            page.add(offset);
            page.add(limit);
            List<StudentListItem> rows = jdbc.query("""
                select p.id, p.school_ref, coalesce(p.preferred_name, p.given_name) || coalesce(' ' || p.family_name, ''),
                       pr.code, g.name, s.code, s.id,
                       (select count(*)::int from guardian_relationship r where r.student_person_id = p.id and r.status <> 'ENDED'),
                       p.provenance->>'source', e.status
                """ + where + " order by pr.code nulls last, g.ordinal nulls last, s.code nulls last, p.given_name, p.id offset ? limit ?",
                (rs, i) -> new StudentListItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getInt(8),
                    rs.getString(9), rs.getString(10)), page.toArray());
            return new StudentPage(total == null ? 0 : total, rows);
        });
    }

    @GetMapping("/students/{studentId}")
    public StudentProfile profile(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> loadProfile(jdbc, ctx, studentId));
    }

    private StudentProfile loadProfile(JdbcTemplate jdbc, RequestContext ctx, UUID studentId) {
        ScopePath path = studentPath(jdbc, ctx, studentId);
        AccessDecision basic = authz.check(jdbc, ctx, Permission.STUDENT_READ_BASIC,
            Resource.ordinary("student", studentId, path, studentId));
        if (!basic.allowed()) {
            throw ApiException.notFound();
        }
        boolean staff = basic.basis() == AccessDecision.Basis.ROLE;
        boolean academic = authz.policy().decide(ctx.actor(), Permission.STUDENT_READ_ACADEMIC,
            Resource.ordinary("student", studentId, path, studentId), clock.instant()).allowed();

        Map<String, Object> p = jdbc.queryForMap("""
            select school_ref, given_name, family_name, preferred_name, date_of_birth, status, provenance::text
              from person where id = ?
            """, studentId);
        Map<String, Object> provenance = readMap((String) p.get("provenance"));
        @SuppressWarnings("unchecked")
        List<String> owned = provenance.get("ownedFields") instanceof List<?> l ? (List<String>) l : List.of();

        List<Placement> placements = academic || staff ? jdbc.query("""
            select y.id, y.label, c.name, pr.code, g.name, s.code, s.id, e.status
              from student_enrollment e join academic_year y on y.id = e.academic_year_id join campus c on c.id = e.campus_id
              left join class_section s on s.id = e.class_section_id left join grade_level g on g.id = s.grade_level_id
              left join programme pr on pr.id = coalesce(g.programme_id, e.programme_id)
             where e.student_person_id = ? order by y.starts_on desc
            """, (rs, i) -> new Placement(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8)), studentId) : List.of();

        List<GuardianView> guardians = List.of();
        List<String> households = List.of();
        if (staff) {
            Set<UUID> studentHouseholds = new HashSet<>(jdbc.queryForList(
                "select household_id from household_member where person_id = ?", UUID.class, studentId));
            guardians = jdbc.query("""
                select r.id, g.id, g.given_name, g.family_name, g.mobile_e164, g.email, r.relationship_type, r.status,
                       r.can_view_attendance, r.can_view_academic, r.can_pay_fees, r.can_give_consent,
                       r.can_receive_notices, r.can_pickup,
                       array(select household_id from household_member m where m.person_id = g.id)::text
                  from guardian_relationship r join person g on g.id = r.guardian_person_id
                 where r.student_person_id = ? order by r.status = 'ENDED', r.created_at
                """, (rs, i) -> {
                    String hh = rs.getString(15);
                    boolean shares = studentHouseholds.stream().anyMatch(h -> hh != null && hh.contains(h.toString()));
                    String family = rs.getString(4);
                    return new GuardianView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3) + (family == null ? "" : " " + family), rs.getString(3), family,
                        IndianMobile.display(rs.getString(5)), rs.getString(6), rs.getString(7), rs.getString(8),
                        capabilities(rs.getBoolean(9), rs.getBoolean(10), rs.getBoolean(11), rs.getBoolean(12),
                            rs.getBoolean(13), rs.getBoolean(14)), shares);
                }, studentId);
            households = jdbc.queryForList("""
                select h.name from household h join household_member m on m.household_id = h.id where m.person_id = ?
                 order by h.name
                """, String.class, studentId);
        }
        List<String> yours = staff ? List.of() : ctx.actor().guardianLinks().stream()
            .filter(l -> l.studentId().equals(studentId) && l.isUsableAt(clock.instant()))
            .flatMap(l -> l.capabilities().stream()).map(Enum::name).distinct().sorted().toList();

        return new StudentProfile(studentId, (String) p.get("school_ref"), (String) p.get("given_name"),
            (String) p.get("family_name"), (String) p.get("preferred_name"),
            staff || academic ? (LocalDate) toLocalDate(p.get("date_of_birth")) : null, (String) p.get("status"),
            staff ? "STAFF" : "GUARDIAN", (String) provenance.get("source"), staff ? owned : List.of(), placements,
            guardians, households, yours);
    }

    // ---- student writes -------------------------------------------------------------------------

    @PostMapping("/academic-years/{yearId}/students")
    @ResponseStatus(HttpStatus.CREATED)
    public StudentProfile create(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody StudentCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            ScopePath campus = access.campusPath(jdbc, ctx, year.campusId());
            requireWrite(jdbc, ctx, null, campus);
            UUID programmeId = null;
            if (req.sectionId() != null) {
                programmeId = sectionProgramme(jdbc, req.sectionId(), yearId);
            }
            String ref = req.schoolRef() != null ? req.schoolRef() : jdbc.queryForObject("""
                select 'STU-' || lpad((coalesce(max(substring(school_ref from '^STU-([0-9]+)$')::int), 0) + 1)::text, 5, '0')
                  from person
                """, String.class);
            UUID id;
            try {
                id = jdbc.queryForObject("""
                    insert into person (tenant_id, school_ref, given_name, family_name, preferred_name, date_of_birth,
                                        created_by, provenance)
                    values (?, ?, ?, ?, ?, ?, ?, '{"source":"SCHOOMANO"}'::jsonb) returning id
                    """, UUID.class, ctx.tenant().value(), ref, req.givenName().trim(), blank(req.familyName()),
                    blank(req.preferredName()), req.dateOfBirth(), ctx.actor().personId());
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "school_ref_in_use", "Another person already has school reference " + ref + ".");
            }
            jdbc.update("""
                insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id, programme_id,
                                                class_section_id)
                values (?, ?, ?, ?, ?, ?)
                """, ctx.tenant().value(), id, year.campusId(), yearId, programmeId, req.sectionId());
            audit.write(jdbc, ctx, "student.created", "student", id.toString(), "SUCCESS", null,
                Map.of("schoolRef", ref, "yearId", yearId.toString()));
            return loadProfile(jdbc, ctx, id);
        });
    }

    @PatchMapping("/students/{studentId}")
    public StudentProfile update(RequestContext ctx, @PathVariable UUID studentId, @Valid @RequestBody StudentUpdate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = studentPath(jdbc, ctx, studentId);
            requireWrite(jdbc, ctx, studentId, path);
            Map<String, Object> current = jdbc.queryForMap(
                "select given_name, family_name, preferred_name, date_of_birth, provenance::text from person where id = ?",
                studentId);
            Map<String, Object> provenance = readMap((String) current.get("provenance"));
            @SuppressWarnings("unchecked")
            Set<String> owned = new HashSet<>(provenance.get("ownedFields") instanceof List<?> l ? (List<String>) l : List.of());
            Set<String> requested = new HashSet<>();
            if (req.givenName() != null) {
                requested.add("givenName");
            }
            if (req.familyName() != null) {
                requested.add("familyName");
            }
            if (req.dateOfBirth() != null) {
                requested.add("dateOfBirth");
            }
            if (req.sectionId() != null || Boolean.TRUE.equals(req.clearSection())) {
                requested.add("placement");
            }
            if (req.preferredName() != null) {
                requested.add("preferredName");
            }
            if (requested.isEmpty()) {
                throw ApiException.badRequest("nothing_to_change", "Send at least one field to change.");
            }
            List<String> locked = RelationshipRules.lockedEdits(requested, owned);
            if (!locked.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "owned_by_old_system",
                    "These details come from the old school system. Change them there; the next import brings them across.",
                    Map.of("problems", locked.stream().map(f -> "Locked: " + f).toList()));
            }
            Map<String, Object> changed = new LinkedHashMap<>();
            if (req.givenName() != null) {
                jdbc.update("update person set given_name = ? where id = ?", req.givenName().trim(), studentId);
                changed.put("givenName", true);
            }
            if (req.familyName() != null) {
                jdbc.update("update person set family_name = ? where id = ?", blank(req.familyName()), studentId);
                changed.put("familyName", true);
            }
            if (req.preferredName() != null) {
                jdbc.update("update person set preferred_name = ? where id = ?", blank(req.preferredName()), studentId);
                changed.put("preferredName", true);
            }
            if (req.dateOfBirth() != null) {
                jdbc.update("update person set date_of_birth = ? where id = ?", req.dateOfBirth(), studentId);
                changed.put("dateOfBirth", true);
            }
            if (requested.contains("placement")) {
                if (req.academicYearId() == null) {
                    throw ApiException.badRequest("year_required", "Say which academic year the section change is for.");
                }
                UUID programmeId = req.sectionId() == null ? null : sectionProgramme(jdbc, req.sectionId(), req.academicYearId());
                int n = jdbc.update("""
                    update student_enrollment set class_section_id = ?, programme_id = coalesce(?, programme_id)
                     where student_person_id = ? and academic_year_id = ?
                    """, req.sectionId(), programmeId, studentId, req.academicYearId());
                if (n == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "not_enrolled", "The student is not enrolled in that year.");
                }
                changed.put("placement", req.sectionId() == null ? "none" : req.sectionId().toString());
            }
            jdbc.update("update person set updated_at = now(), updated_by = ? where id = ?", ctx.actor().personId(), studentId);
            audit.write(jdbc, ctx, "student.updated", "student", studentId.toString(), "SUCCESS", blank(req.reason()), changed);
            return loadProfile(jdbc, ctx, studentId);
        });
    }

    // ---- guardians ------------------------------------------------------------------------------

    @PostMapping("/students/{studentId}/guardians")
    @ResponseStatus(HttpStatus.CREATED)
    public StudentProfile addGuardian(RequestContext ctx, @PathVariable UUID studentId, @Valid @RequestBody GuardianAdd req) {
        String mobile = IndianMobile.normalise(req.mobile()).orElseThrow(() -> ApiException.badRequest("invalid_mobile",
            "Enter a 10-digit Indian mobile number, for example 98450 12345."));
        Set<GuardianCapability> caps = EnumSet.noneOf(GuardianCapability.class);
        caps.addAll(req.capabilities());
        GuardianLink.Status status = GuardianLink.Status.valueOf(req.status());
        List<String> problems = RelationshipRules.problems(null, new RelationshipRules.State(status, caps), req.reason(),
            req.pickupConfirmed());
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "relationship_rejected", "This guardian cannot be added yet.",
                Map.of("problems", problems));
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = studentPath(jdbc, ctx, studentId);
            requireGuardianManage(jdbc, ctx, studentId, path);
            List<UUID> existing = jdbc.queryForList("select id from person where mobile_e164 = ?", UUID.class, mobile);
            UUID guardian;
            boolean reused = !existing.isEmpty();
            if (reused) {
                guardian = existing.get(0);
                if (guardian.equals(studentId)) {
                    throw ApiException.badRequest("self_guardian", "A student cannot be their own guardian.");
                }
            } else {
                String ref = jdbc.queryForObject("""
                    select 'GRD-' || lpad((coalesce(max(substring(school_ref from '^GRD-([0-9]+)$')::int), 0) + 1)::text, 5, '0')
                      from person
                    """, String.class);
                guardian = jdbc.queryForObject("""
                    insert into person (tenant_id, school_ref, given_name, family_name, mobile_e164, email, created_by,
                                        provenance)
                    values (?, ?, ?, ?, ?, ?, ?, '{"source":"SCHOOMANO"}'::jsonb) returning id
                    """, UUID.class, ctx.tenant().value(), ref, req.givenName().trim(), blank(req.familyName()), mobile,
                    blank(req.email()), ctx.actor().personId());
            }
            UUID rel;
            try {
                rel = jdbc.queryForObject("""
                    insert into guardian_relationship (tenant_id, guardian_person_id, student_person_id, relationship_type,
                        can_view_attendance, can_view_academic, can_pay_fees, can_give_consent, can_receive_notices,
                        can_pickup, status, updated_by, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) returning id
                    """, UUID.class, ctx.tenant().value(), guardian, studentId, req.relationshipType(),
                    caps.contains(GuardianCapability.VIEW_ATTENDANCE), caps.contains(GuardianCapability.VIEW_ACADEMIC),
                    caps.contains(GuardianCapability.PAY_FEES), caps.contains(GuardianCapability.GIVE_CONSENT),
                    caps.contains(GuardianCapability.RECEIVE_NOTICES), caps.contains(GuardianCapability.PICKUP),
                    status.name(), ctx.actor().personId());
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "already_linked",
                    "This guardian is already linked to the student. Change the existing relationship instead.");
            }
            if (status == GuardianLink.Status.ACTIVE) {
                shareHousehold(jdbc, ctx, studentId, guardian);
            }
            audit.write(jdbc, ctx, "guardian.linked", "guardian_relationship", rel.toString(), "SUCCESS", req.reason().trim(),
                Map.of("studentId", studentId.toString(), "guardianId", guardian.toString(), "existingPerson", reused,
                    "status", status.name(), "capabilities", sortedNames(caps)));
            return loadProfile(jdbc, ctx, studentId);
        });
    }

    @PatchMapping("/guardian-relationships/{relationshipId}")
    public StudentProfile updateRelationship(RequestContext ctx, @PathVariable UUID relationshipId,
                                             @Valid @RequestBody RelationshipUpdate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> r = jdbc.queryForList("""
                select student_person_id, guardian_person_id, status, can_view_attendance, can_view_academic, can_pay_fees,
                       can_give_consent, can_receive_notices, can_pickup
                  from guardian_relationship where id = ?
                """, relationshipId).stream().findFirst().orElseThrow(ApiException::notFound);
            UUID studentId = (UUID) r.get("student_person_id");
            requireGuardianManage(jdbc, ctx, studentId, studentPath(jdbc, ctx, studentId));
            Set<GuardianCapability> before = EnumSet.noneOf(GuardianCapability.class);
            before.addAll(capabilities((Boolean) r.get("can_view_attendance"), (Boolean) r.get("can_view_academic"),
                (Boolean) r.get("can_pay_fees"), (Boolean) r.get("can_give_consent"), (Boolean) r.get("can_receive_notices"),
                (Boolean) r.get("can_pickup")).stream().map(GuardianCapability::valueOf).toList());
            Set<GuardianCapability> after = EnumSet.noneOf(GuardianCapability.class);
            after.addAll(req.capabilities());
            GuardianLink.Status oldStatus = GuardianLink.Status.valueOf((String) r.get("status"));
            GuardianLink.Status newStatus = GuardianLink.Status.valueOf(req.status());
            List<String> problems = RelationshipRules.problems(new RelationshipRules.State(oldStatus, before),
                new RelationshipRules.State(newStatus, after), req.reason(), req.pickupConfirmed());
            if (!problems.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "relationship_rejected", "This change cannot be made.",
                    Map.of("problems", problems));
            }
            jdbc.update("""
                update guardian_relationship set status = ?, can_view_attendance = ?, can_view_academic = ?, can_pay_fees = ?,
                       can_give_consent = ?, can_receive_notices = ?, can_pickup = ?, updated_at = now(), updated_by = ?,
                       ended_at = case when ? = 'ENDED' then now() else ended_at end
                 where id = ?
                """, newStatus.name(), after.contains(GuardianCapability.VIEW_ATTENDANCE),
                after.contains(GuardianCapability.VIEW_ACADEMIC), after.contains(GuardianCapability.PAY_FEES),
                after.contains(GuardianCapability.GIVE_CONSENT), after.contains(GuardianCapability.RECEIVE_NOTICES),
                after.contains(GuardianCapability.PICKUP), ctx.actor().personId(), newStatus.name(), relationshipId);
            if (newStatus != GuardianLink.Status.ACTIVE) {
                // A restricted, suspended or ended guardian no longer shares the child's household record.
                jdbc.update("""
                    delete from household_member m where m.person_id = ?
                       and m.household_id in (select household_id from household_member where person_id = ?)
                    """, r.get("guardian_person_id"), studentId);
            }
            audit.write(jdbc, ctx, "guardian.relationship_changed", "guardian_relationship", relationshipId.toString(),
                "SUCCESS", req.reason().trim(), Map.of("studentId", studentId.toString(),
                    "statusBefore", oldStatus.name(), "statusAfter", newStatus.name(),
                    "capabilitiesBefore", sortedNames(before), "capabilitiesAfter", sortedNames(after)));
            return loadProfile(jdbc, ctx, studentId);
        });
    }

    @PatchMapping("/guardians/{guardianId}")
    public Map<String, Object> updateGuardian(RequestContext ctx, @PathVariable UUID guardianId,
                                              @Valid @RequestBody GuardianUpdate req) {
        String mobile = req.mobile() == null ? null : IndianMobile.normalise(req.mobile()).orElseThrow(() ->
            ApiException.badRequest("invalid_mobile", "Enter a 10-digit Indian mobile number, for example 98450 12345."));
        if (req.reason().trim().length() < RelationshipRules.MIN_REASON) {
            throw ApiException.badRequest("reason_required",
                "Give a reason of at least " + RelationshipRules.MIN_REASON + " characters. It is kept in the audit trail.");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            List<UUID> students = jdbc.queryForList(
                "select student_person_id from guardian_relationship where guardian_person_id = ? and status <> 'ENDED'",
                UUID.class, guardianId);
            boolean allowed = false;
            for (UUID s : students) {
                ScopePath path = studentPath(jdbc, ctx, s);
                if (authz.policy().decide(ctx.actor(), Permission.PERSON_MANAGE,
                    Resource.ordinary("guardian", guardianId, path, null), clock.instant()).allowed()) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw ApiException.notFound();
            }
            try {
                jdbc.update("""
                    update person set given_name = coalesce(?, given_name), family_name = coalesce(?, family_name),
                           mobile_e164 = coalesce(?, mobile_e164), email = coalesce(?, email),
                           updated_at = now(), updated_by = ?
                     where id = ?
                    """, req.givenName() == null ? null : req.givenName().trim(), req.familyName(), mobile,
                    blank(req.email()), ctx.actor().personId(), guardianId);
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "mobile_in_use", "This mobile number belongs to another person.");
            }
            Map<String, Object> changed = new HashMap<>();
            if (mobile != null) {
                changed.put("mobile", "changed");
            }
            if (req.givenName() != null || req.familyName() != null) {
                changed.put("name", "changed");
            }
            if (req.email() != null) {
                changed.put("email", "changed");
            }
            audit.write(jdbc, ctx, "guardian.updated", "person", guardianId.toString(), "SUCCESS", req.reason().trim(), changed);
            return Map.of("id", guardianId, "updated", changed.keySet());
        });
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ScopePath studentPath(JdbcTemplate jdbc, RequestContext ctx, UUID studentId) {
        if (jdbc.queryForList("select id from person where id = ?", UUID.class, studentId).isEmpty()) {
            throw ApiException.notFound();
        }
        return scopes.studentPath(jdbc, ctx.tenant(), studentId).orElse(ScopePath.group(ctx.tenant()));
    }

    private void requireWrite(JdbcTemplate jdbc, RequestContext ctx, UUID studentId, ScopePath path) {
        authz.requireOrNotFound(jdbc, ctx, Permission.STUDENT_READ_BASIC, Resource.ordinary("student", studentId, path, null));
        authz.requireOrForbidden(jdbc, ctx, Permission.STUDENT_WRITE, Resource.ordinary("student", studentId, path, null));
    }

    private void requireGuardianManage(JdbcTemplate jdbc, RequestContext ctx, UUID studentId, ScopePath path) {
        requireWrite(jdbc, ctx, studentId, path);
        authz.requireOrForbidden(jdbc, ctx, Permission.PERSON_MANAGE, Resource.ordinary("guardian", null, path, null));
    }

    /** The section's programme, checking it belongs to the given year. */
    private static UUID sectionProgramme(JdbcTemplate jdbc, UUID sectionId, UUID yearId) {
        return jdbc.queryForList("""
            select g.programme_id from class_section s join grade_level g on g.id = s.grade_level_id
             where s.id = ? and s.academic_year_id = ?
            """, UUID.class, sectionId, yearId).stream().findFirst().orElseThrow(() ->
            new ApiException(HttpStatus.CONFLICT, "wrong_year", "That section is not part of this academic year."));
    }

    /** Puts an active guardian into the child's household, creating one for the child if needed. */
    private static void shareHousehold(JdbcTemplate jdbc, RequestContext ctx, UUID studentId, UUID guardian) {
        UUID household = jdbc.queryForList("select household_id from household_member where person_id = ? limit 1",
            UUID.class, studentId).stream().findFirst().orElse(null);
        if (household == null) {
            String name = jdbc.queryForObject(
                "select coalesce(family_name, given_name) || ' household' from person where id = ?", String.class, studentId);
            household = jdbc.queryForObject("insert into household (tenant_id, name) values (?, ?) returning id", UUID.class,
                ctx.tenant().value(), name);
            jdbc.update("insert into household_member (tenant_id, household_id, person_id, member_role) values (?, ?, ?, 'STUDENT')",
                ctx.tenant().value(), household, studentId);
        }
        jdbc.update("""
            insert into household_member (tenant_id, household_id, person_id, member_role) values (?, ?, ?, 'GUARDIAN')
            on conflict do nothing
            """, ctx.tenant().value(), household, guardian);
    }

    private static List<String> capabilities(boolean attendance, boolean academic, boolean fees, boolean consent,
                                             boolean notices, boolean pickup) {
        List<String> out = new ArrayList<>();
        if (attendance) {
            out.add(GuardianCapability.VIEW_ATTENDANCE.name());
        }
        if (academic) {
            out.add(GuardianCapability.VIEW_ACADEMIC.name());
        }
        if (fees) {
            out.add(GuardianCapability.PAY_FEES.name());
        }
        if (consent) {
            out.add(GuardianCapability.GIVE_CONSENT.name());
        }
        if (notices) {
            out.add(GuardianCapability.RECEIVE_NOTICES.name());
        }
        if (pickup) {
            out.add(GuardianCapability.PICKUP.name());
        }
        return out;
    }

    private static List<String> sortedNames(Set<GuardianCapability> caps) {
        return caps.stream().map(Enum::name).sorted().toList();
    }

    private static Object toLocalDate(Object o) {
        return o instanceof java.sql.Date d ? d.toLocalDate() : o;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Map<String, Object> readMap(String s) {
        try {
            return s == null ? Map.of() : json.readValue(s, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
