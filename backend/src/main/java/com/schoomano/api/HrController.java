package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M16 HR and workforce. HR staff (campus administrators; group administrators across campuses) keep a profile per member
 * of staff and set the yearly leave allowances. Any member of staff requests leave; someone else with LEAVE_APPROVE
 * approves it, within the balance. Days count Monday to Saturday. Staff read only their own leave.
 */
@RestController
@RequestMapping("/api/v1")
public class HrController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ApprovalNotifier notifier;

    public HrController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access, ApprovalNotifier notifier) {
        this.notifier = notifier;
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
    }

    public record ProfileSet(@NotBlank @Size(min = 2, max = 30) String employeeNo, @NotBlank @Size(min = 2, max = 80) String designation,
                             @Size(max = 80) String department, @NotNull @Pattern(regexp = "FULL_TIME|PART_TIME|CONTRACT") String employmentType,
                             @NotNull LocalDate joinedOn, LocalDate leftOn) { }

    public record StaffView(UUID personId, String name, String employeeNo, String designation, String department,
                            String employmentType, LocalDate joinedOn, String status, LocalDate leftOn) { }

    public record AllowanceSet(@NotNull @Pattern(regexp = "CASUAL|SICK|EARNED") String leaveType,
                               @NotNull @DecimalMin("0") @DecimalMax("366") BigDecimal days) { }

    public record BalanceView(String leaveType, BigDecimal allowance, BigDecimal approved, BigDecimal pending, BigDecimal remaining) { }

    public record LeaveCreate(@NotNull @Pattern(regexp = "CASUAL|SICK|EARNED|UNPAID") String leaveType, @NotNull LocalDate fromDate,
                              @NotNull LocalDate toDate, @Size(max = 1000) String reason, UUID chainId) { }

    public record Decision(@NotNull @Pattern(regexp = "APPROVE|REJECT|CANCEL") String decision, @Size(max = 1000) String note) { }

    public record LeaveView(UUID id, UUID personId, String name, String leaveType, LocalDate fromDate, LocalDate toDate, BigDecimal days,
                            String reason, String status, String decidedBy, String decisionNote, OffsetDateTime requestedAt,
                            UUID workflowRequestId, String waitingFor) { }

    private record Leave(UUID id, UUID personId, UUID campusId, String status) { }

    // ---- staff profiles --------------------------------------------------------------------------------------

    @PutMapping("/campuses/{campusId}/staff/{personId}")
    public StaffView saveProfile(RequestContext ctx, @PathVariable UUID campusId, @PathVariable UUID personId, @Valid @RequestBody ProfileSet req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            authz.requireOrNotFound(jdbc, ctx, Permission.HR_MANAGE, Resource.ordinary("staff_profile", personId, access.campusPath(jdbc, ctx, campusId), null));
            if (jdbc.queryForObject("select count(*) from person where id = ?", Long.class, personId) == 0) {
                throw ApiException.notFound();
            }
            if (req.leftOn() != null && req.leftOn().isBefore(req.joinedOn())) {
                throw ApiException.badRequest("invalid_dates", "The leaving date cannot be before the joining date.");
            }
            try {
                jdbc.update("""
                    insert into staff_profile (tenant_id, person_id, campus_id, employee_no, designation, department, employment_type, joined_on, status, left_on)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, person_id) do update
                       set campus_id = excluded.campus_id, employee_no = excluded.employee_no, designation = excluded.designation,
                           department = excluded.department, employment_type = excluded.employment_type, joined_on = excluded.joined_on,
                           status = excluded.status, left_on = excluded.left_on
                    """, ctx.tenant().value(), personId, campusId, req.employeeNo().trim(), req.designation().trim(), blank(req.department()),
                    req.employmentType(), req.joinedOn(), req.leftOn() == null ? "ACTIVE" : "LEFT", req.leftOn());
            } catch (DataIntegrityViolationException e) {
                String m = String.valueOf(e.getMostSpecificCause().getMessage());
                if (m.contains("employee_no")) {
                    throw new ApiException(HttpStatus.CONFLICT, "duplicate_employee_no", "Another member of staff has that employee number.");
                }
                throw ApiException.badRequest("invalid_profile", "Staff profiles are for people who are not students.");
            }
            audit.write(jdbc, ctx, "hr.profile_saved", "person", personId.toString(), "SUCCESS", null, Map.of());
            return staff(jdbc, campusId).stream().filter(s -> s.personId().equals(personId)).findFirst().orElseThrow();
        });
    }

    @GetMapping("/campuses/{campusId}/staff")
    public List<StaffView> staffList(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            authz.requireOrNotFound(jdbc, ctx, Permission.HR_READ, Resource.ordinary("staff_profile", null, access.campusPath(jdbc, ctx, campusId), null));
            audit.write(jdbc, ctx, "hr.staff_listed", "campus", campusId.toString(), "SUCCESS", null, Map.of());
            return staff(jdbc, campusId);
        });
    }

    public record PersonMatch(UUID personId, String name, String schoolRef, boolean hasProfile) { }

    /** HR finds a person (not a student) by name or reference to give them a staff profile. */
    @GetMapping("/campuses/{campusId}/people")
    public List<PersonMatch> findPeople(RequestContext ctx, @PathVariable UUID campusId, @org.springframework.web.bind.annotation.RequestParam String q) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            authz.requireOrNotFound(jdbc, ctx, Permission.HR_MANAGE, Resource.ordinary("staff_profile", null, access.campusPath(jdbc, ctx, campusId), null));
            String term = q.trim();
            if (term.length() < 2) {
                throw ApiException.badRequest("query_too_short", "Type at least 2 characters.");
            }
            return jdbc.query("""
                select p.id, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')) as name, p.school_ref,
                       exists (select 1 from staff_profile s where s.person_id = p.id) as has_profile
                  from person p
                 where not exists (select 1 from student_enrollment e where e.student_person_id = p.id)
                   and not exists (select 1 from guardian_relationship g where g.guardian_person_id = p.id)
                   and (lower(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')) like ? or lower(coalesce(p.school_ref, '')) like ?)
                 order by name limit 10
                """, (rs, i) -> new PersonMatch(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4)),
                "%" + term.toLowerCase().replace("%", "") + "%", "%" + term.toLowerCase().replace("%", "") + "%");
        });
    }

    // ---- allowances and balances -----------------------------------------------------------------------------

    @PutMapping("/academic-years/{yearId}/leave-allowances")
    public List<BalanceView> setAllowance(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody AllowanceSet req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            authz.requireOrNotFound(jdbc, ctx, Permission.HR_MANAGE, Resource.ordinary("leave_allowance", null, access.campusPath(jdbc, ctx, year.campusId()), null));
            jdbc.update("""
                insert into leave_allowance (tenant_id, campus_id, academic_year_id, leave_type, days) values (?, ?, ?, ?, ?)
                on conflict (tenant_id, campus_id, academic_year_id, leave_type) do update set days = excluded.days
                """, ctx.tenant().value(), year.campusId(), yearId, req.leaveType(), req.days());
            audit.write(jdbc, ctx, "hr.allowance_set", "academic_year", yearId.toString(), "SUCCESS", null, Map.of("leaveType", req.leaveType()));
            return balances(jdbc, year.campusId(), yearId, null);
        });
    }

    /** The caller's own leave balance for the year. */
    @GetMapping("/academic-years/{yearId}/leave-balance")
    public List<BalanceView> myBalance(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            authz.requireOrNotFound(jdbc, ctx, Permission.LEAVE_REQUEST, Resource.ordinary("leave_request", null, access.campusPath(jdbc, ctx, year.campusId()), null));
            return balances(jdbc, year.campusId(), yearId, ctx.actor().personId());
        });
    }

    // ---- leave requests --------------------------------------------------------------------------------------

    @PostMapping("/academic-years/{yearId}/leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveView request(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody LeaveCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            authz.requireOrNotFound(jdbc, ctx, Permission.LEAVE_REQUEST, Resource.ordinary("leave_request", null, access.campusPath(jdbc, ctx, year.campusId()), null));
            if (req.toDate().isBefore(req.fromDate())) {
                throw ApiException.badRequest("invalid_dates", "The last day cannot be before the first day.");
            }
            if (req.fromDate().isBefore(year.dates().startsOn()) || req.toDate().isAfter(year.dates().endsOn())) {
                throw ApiException.badRequest("outside_year", "Leave must fall inside the academic year.");
            }
            if (jdbc.queryForObject("select count(*) from staff_profile where person_id = ? and status = 'ACTIVE'", Long.class, ctx.actor().personId()) == 0) {
                throw ApiException.badRequest("no_profile", "Ask HR to set up your staff profile first.");
            }
            long days = workingDays(req.fromDate(), req.toDate());
            if (days == 0) {
                throw ApiException.badRequest("no_working_days", "Those dates are all Sundays.");
            }
            UUID workflowId = null;
            if (req.chainId() != null) {
                authz.requireOrNotFound(jdbc, ctx, Permission.WORKFLOW_SUBMIT, Resource.ordinary("workflow", null, access.campusPath(jdbc, ctx, year.campusId()), null));
                try {
                    workflowId = jdbc.queryForObject("""
                        insert into workflow_request (tenant_id, campus_id, definition_id, requester_id, subject, details) values (?, ?, ?, ?, ?, ?) returning id
                        """, UUID.class, ctx.tenant().value(), year.campusId(), req.chainId(), ctx.actor().personId(),
                        "Leave: " + req.leaveType().toLowerCase() + " " + req.fromDate() + " to " + req.toDate(), days + " working days");
                } catch (DataIntegrityViolationException e) {
                    throw ApiException.badRequest("unknown_chain", "Choose a published approval chain of this campus.");
                }
            }
            UUID id;
            try {
                id = jdbc.queryForObject("""
                    insert into leave_request (tenant_id, person_id, campus_id, academic_year_id, leave_type, from_date, to_date, days, reason, workflow_request_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), ctx.actor().personId(), year.campusId(), yearId, req.leaveType(), req.fromDate(),
                    req.toDate(), days, blank(req.reason()), workflowId);
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "overlap", "You already have leave requested or approved on some of those days.");
            }
            audit.write(jdbc, ctx, "hr.leave_requested", "leave_request", id.toString(), "SUCCESS", null, Map.of("days", days));
            if (workflowId != null) {
                notifier.waiting(jdbc, workflowId);
            }
            return view(jdbc, id);
        });
    }

    /** HR sees every request of the campus year; anyone else sees their own. */
    @GetMapping("/academic-years/{yearId}/leave-requests")
    public List<LeaveView> list(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            Resource scope = Resource.ordinary("leave_request", null, access.campusPath(jdbc, ctx, year.campusId()), null);
            boolean all = authz.check(jdbc, ctx, Permission.HR_READ, scope).allowed();
            if (!all) {
                authz.requireOrNotFound(jdbc, ctx, Permission.LEAVE_REQUEST, scope);
            }
            List<UUID> ids = jdbc.query("""
                select id from leave_request where academic_year_id = ? and (? or person_id = ?)
                order by (status = 'PENDING') desc, from_date desc limit 200
                """, (rs, i) -> rs.getObject(1, UUID.class), yearId, all, ctx.actor().personId());
            return ids.stream().map(id -> view(jdbc, id)).toList();
        });
    }

    @PostMapping("/leave-requests/{id}/decision")
    public LeaveView decide(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody Decision req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Leave l = jdbc.query("select id, person_id, campus_id, status from leave_request where id = ?",
                (rs, i) -> new Leave(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4)), id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            boolean mine = l.personId().equals(ctx.actor().personId());
            String note = blank(req.note());
            if ("CANCEL".equals(req.decision())) {
                if (!mine) {
                    throw ApiException.notFound();
                }
            } else {
                authz.requireOrNotFound(jdbc, ctx, Permission.LEAVE_APPROVE,
                    Resource.ordinary("leave_request", id, access.campusPath(jdbc, ctx, l.campusId()), null));
                if (mine) {
                    throw ApiException.forbidden("Someone else must decide your leave.");
                }
            }
            if ("REJECT".equals(req.decision()) && (note == null || note.length() < 5)) {
                throw ApiException.badRequest("note_required", "Say why the leave is rejected (5 characters or more).");
            }
            if (!"PENDING".equals(l.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_pending", "This request is already " + l.status().toLowerCase() + ".");
            }
            UUID linked = jdbc.queryForObject("select workflow_request_id from leave_request where id = ?", UUID.class, id);
            if (linked != null && !"CANCEL".equals(req.decision())) {
                throw new ApiException(HttpStatus.CONFLICT, "use_approvals", "This leave is decided through its approval chain on the Approvals page.");
            }
            if (linked != null) {
                jdbc.update("update workflow_request set status = 'WITHDRAWN', closed_at = clock_timestamp() where id = ? and status = 'PENDING'", linked);
            }
            String to = switch (req.decision()) { case "APPROVE" -> "APPROVED"; case "REJECT" -> "REJECTED"; default -> "CANCELLED"; };
            try {
                jdbc.update("update leave_request set status = ?, decided_by = ?, decision_note = ? where id = ?", to, ctx.actor().personId(), note, id);
            } catch (DataIntegrityViolationException e) {
                String m = String.valueOf(e.getMostSpecificCause().getMessage());
                if (m.contains("insufficient leave balance")) {
                    throw new ApiException(HttpStatus.CONFLICT, "insufficient_balance", "There is not enough leave balance for this request.");
                }
                throw new ApiException(HttpStatus.CONFLICT, "invalid_move", "This request cannot be changed.");
            }
            audit.write(jdbc, ctx, "hr.leave_" + to.toLowerCase(), "leave_request", id.toString(), "SUCCESS", null, Map.of());
            return view(jdbc, id);
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** Monday to Saturday. */
    static long workingDays(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).filter(d -> d.getDayOfWeek() != DayOfWeek.SUNDAY).count();
    }

    private List<StaffView> staff(JdbcTemplate jdbc, UUID campusId) {
        return jdbc.query("""
            select s.person_id, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), s.employee_no, s.designation,
                   s.department, s.employment_type, s.joined_on, s.status, s.left_on
              from staff_profile s join person p on p.tenant_id = s.tenant_id and p.id = s.person_id
             where s.campus_id = ? order by s.status, 2
            """, (rs, i) -> new StaffView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getObject(7, LocalDate.class), rs.getString(8), rs.getObject(9, LocalDate.class)), campusId);
    }

    /** Allowance, approved and pending days per leave type; for one person, or just the allowances when personId is null. */
    private List<BalanceView> balances(JdbcTemplate jdbc, UUID campusId, UUID yearId, UUID personId) {
        return jdbc.query("""
            select a.leave_type, a.days,
                   coalesce((select sum(l.days) from leave_request l where l.person_id = ?::uuid and l.academic_year_id = a.academic_year_id
                              and l.leave_type = a.leave_type and l.status = 'APPROVED'), 0),
                   coalesce((select sum(l.days) from leave_request l where l.person_id = ?::uuid and l.academic_year_id = a.academic_year_id
                              and l.leave_type = a.leave_type and l.status = 'PENDING'), 0)
              from leave_allowance a where a.campus_id = ? and a.academic_year_id = ? order by a.leave_type
            """, (rs, i) -> new BalanceView(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                rs.getBigDecimal(2).subtract(rs.getBigDecimal(3))), personId, personId, campusId, yearId);
    }

    private LeaveView view(JdbcTemplate jdbc, UUID id) {
        return jdbc.query("""
            select l.id, l.person_id, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), l.leave_type, l.from_date, l.to_date,
                   l.days, l.reason, l.status, trim(coalesce(d.given_name, '') || ' ' || coalesce(d.family_name, '')), l.decision_note, l.requested_at,
                   l.workflow_request_id,
                   (select s.label from workflow_request w join workflow_step s on s.tenant_id = w.tenant_id and s.definition_id = w.definition_id and s.position = w.current_step
                     where w.id = l.workflow_request_id and w.status = 'PENDING')
              from leave_request l
              join person p on p.tenant_id = l.tenant_id and p.id = l.person_id
              left join person d on d.tenant_id = l.tenant_id and d.id = l.decided_by
             where l.id = ?
            """, (rs, i) -> new LeaveView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class), rs.getBigDecimal(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11), rs.getObject(12, OffsetDateTime.class), rs.getObject(13, UUID.class), rs.getString(14)), id).get(0);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
