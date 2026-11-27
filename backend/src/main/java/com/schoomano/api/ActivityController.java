package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M18 co-curricular. Campus administrators run clubs, sports and arts activities with an optional seat limit and put
 * enrolled students in them; whoever leads a session records who came. Membership is made and withdrawn, attendance is
 * permanent and idempotent, and a guardian sees their own child's activities and attendance.
 */
@RestController
@RequestMapping("/api/v1")
public class ActivityController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ScopeDirectory scopes;

    public ActivityController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access, ScopeDirectory scopes) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.scopes = scopes;
    }

    public record ActivityCreate(@NotBlank @Size(min = 2, max = 80) String name, @NotNull @Pattern(regexp = "CLUB|SPORT|ARTS|OTHER") String kind,
                                 @Min(1) @Max(500) Integer capacity, @Size(min = 2, max = 80) String leadName) { }

    public record ActivityView(UUID id, String name, String kind, Integer capacity, String leadName, String status, int members, int sessions) { }

    public record MemberCreate(@NotNull UUID studentId) { }

    public record MemberView(UUID id, UUID studentId, String name, LocalDate joinedOn) { }

    public record SessionCreate(@NotNull LocalDate sessionDate, @Size(min = 2, max = 200) String topic) { }

    public record SessionView(UUID id, LocalDate sessionDate, String topic, int present, int absent) { }

    public record Mark(@NotNull UUID studentId, boolean present) { }

    public record MarkBatch(@NotEmpty @Size(max = 200) List<@Valid Mark> marks) { }

    public record MarkResult(int recorded, int alreadyRecorded) { }

    public record AttendanceView(UUID studentId, String name, boolean present) { }

    public record ChildActivity(UUID activityId, String name, String kind, String leadName, LocalDate joinedOn, int sessions, int attended) { }

    // ---- activities ------------------------------------------------------------------------------------------

    @PostMapping("/academic-years/{yearId}/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityView create(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody ActivityCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            manage(jdbc, ctx, year.campusId());
            try {
                UUID id = jdbc.queryForObject("""
                    insert into activity (tenant_id, campus_id, academic_year_id, name, kind, capacity, lead_name) values (?, ?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), year.campusId(), yearId, req.name().trim(), req.kind(), req.capacity(),
                    req.leadName() == null ? null : req.leadName().trim());
                audit.write(jdbc, ctx, "activity.created", "activity", id.toString(), "SUCCESS", null, Map.of());
                return one(jdbc, id);
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "duplicate_activity", "This year already has an activity with that name.");
            }
        });
    }

    @GetMapping("/academic-years/{yearId}/activities")
    public List<ActivityView> list(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            read(jdbc, ctx, year.campusId());
            return jdbc.query(VIEW_SQL + " where a.academic_year_id = ? order by a.status, a.name", VIEW, yearId);
        });
    }

    @PostMapping("/activities/{id}/close")
    public ActivityView close(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            manage(jdbc, ctx, activityCampus(jdbc, id));
            int n = jdbc.update("update activity set status = 'CLOSED' where id = ? and status = 'OPEN'", id);
            if (n == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_closed", "This activity is already closed.");
            }
            audit.write(jdbc, ctx, "activity.closed", "activity", id.toString(), "SUCCESS", null, Map.of());
            return one(jdbc, id);
        });
    }

    // ---- members ---------------------------------------------------------------------------------------------

    @PostMapping("/activities/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> join(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody MemberCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            manage(jdbc, ctx, activityCampus(jdbc, id));
            try {
                UUID memberId = jdbc.queryForObject("insert into activity_member (tenant_id, activity_id, student_person_id) values (?, ?, ?) returning id",
                    UUID.class, ctx.tenant().value(), id, req.studentId());
                audit.write(jdbc, ctx, "activity.member_joined", "student", req.studentId().toString(), "SUCCESS", null, Map.of("activityId", id.toString()));
                return Map.of("id", memberId);
            } catch (DataIntegrityViolationException e) {
                String m = String.valueOf(e.getMostSpecificCause().getMessage());
                if (m.contains("activity full")) {
                    throw new ApiException(HttpStatus.CONFLICT, "activity_full", "This activity has no seats left.");
                }
                if (m.contains("activity_one_active_member")) {
                    throw new ApiException(HttpStatus.CONFLICT, "already_member", "This student is already in the activity.");
                }
                if (m.contains("closed")) {
                    throw new ApiException(HttpStatus.CONFLICT, "activity_closed", "This activity is closed.");
                }
                throw ApiException.badRequest("invalid_member", "Only students enrolled in this academic year can join.");
            }
        });
    }

    @GetMapping("/activities/{id}/members")
    public List<MemberView> members(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            read(jdbc, ctx, activityCampus(jdbc, id));
            return jdbc.query("""
                select m.id, m.student_person_id, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), m.joined_on
                  from activity_member m join person p on p.tenant_id = m.tenant_id and p.id = m.student_person_id
                 where m.activity_id = ? and m.status = 'ACTIVE' order by 3
                """, (rs, i) -> new MemberView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, LocalDate.class)), id);
        });
    }

    @PostMapping("/activity-members/{id}/withdraw")
    public Map<String, String> withdraw(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID activityId = jdbc.query("select activity_id from activity_member where id = ?", (rs, i) -> rs.getObject(1, UUID.class), id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            manage(jdbc, ctx, activityCampus(jdbc, activityId));
            int n = jdbc.update("update activity_member set status = 'WITHDRAWN', left_on = current_date where id = ? and status = 'ACTIVE'", id);
            if (n == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_withdrawn", "This student has already left the activity.");
            }
            audit.write(jdbc, ctx, "activity.member_withdrawn", "activity_member", id.toString(), "SUCCESS", null, Map.of());
            return Map.of("status", "WITHDRAWN");
        });
    }

    // ---- sessions and attendance -----------------------------------------------------------------------------

    @PostMapping("/activities/{id}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView addSession(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody SessionCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            attend(jdbc, ctx, activityCampus(jdbc, id));
            try {
                UUID sid = jdbc.queryForObject("insert into activity_session (tenant_id, activity_id, session_date, topic) values (?, ?, ?, ?) returning id",
                    UUID.class, ctx.tenant().value(), id, req.sessionDate(), req.topic() == null ? null : req.topic().trim());
                return new SessionView(sid, req.sessionDate(), req.topic(), 0, 0);
            } catch (DataIntegrityViolationException e) {
                if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("closed")) {
                    throw new ApiException(HttpStatus.CONFLICT, "activity_closed", "This activity is closed.");
                }
                throw new ApiException(HttpStatus.CONFLICT, "duplicate_session", "This activity already has a session on that day.");
            }
        });
    }

    @GetMapping("/activities/{id}/sessions")
    public List<SessionView> sessions(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            read(jdbc, ctx, activityCampus(jdbc, id));
            return jdbc.query("""
                select s.id, s.session_date, s.topic,
                       (select count(*) from activity_attendance t where t.session_id = s.id and t.present),
                       (select count(*) from activity_attendance t where t.session_id = s.id and not t.present)
                  from activity_session s where s.activity_id = ? order by s.session_date desc
                """, (rs, i) -> new SessionView(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getInt(4), rs.getInt(5)), id);
        });
    }

    @PostMapping("/activity-sessions/{id}/attendance")
    public MarkResult mark(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody MarkBatch req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID activityId = jdbc.query("select activity_id from activity_session where id = ?", (rs, i) -> rs.getObject(1, UUID.class), id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            attend(jdbc, ctx, activityCampus(jdbc, activityId));
            int recorded = 0;
            for (Mark m : req.marks()) {
                try {
                    recorded += jdbc.update("""
                        insert into activity_attendance (tenant_id, session_id, student_person_id, present, recorded_by) values (?, ?, ?, ?, ?)
                        on conflict (tenant_id, session_id, student_person_id) do nothing
                        """, ctx.tenant().value(), id, m.studentId(), m.present(), ctx.actor().personId());
                } catch (DataIntegrityViolationException e) {
                    throw ApiException.badRequest("not_a_member", "Everyone marked must have been a member on the day, and the day cannot be in the future.");
                }
            }
            audit.write(jdbc, ctx, "activity.attendance_recorded", "activity_session", id.toString(), "SUCCESS", null, Map.of("count", recorded));
            return new MarkResult(recorded, req.marks().size() - recorded);
        });
    }

    @GetMapping("/activity-sessions/{id}/attendance")
    public List<AttendanceView> attendance(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID activityId = jdbc.query("select activity_id from activity_session where id = ?", (rs, i) -> rs.getObject(1, UUID.class), id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            read(jdbc, ctx, activityCampus(jdbc, activityId));
            return jdbc.query("""
                select t.student_person_id, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), t.present
                  from activity_attendance t join person p on p.tenant_id = t.tenant_id and p.id = t.student_person_id
                 where t.session_id = ? order by 2
                """, (rs, i) -> new AttendanceView(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)), id);
        });
    }

    // ---- a child's activities --------------------------------------------------------------------------------

    @GetMapping("/students/{studentId}/activities")
    public List<ChildActivity> child(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.ACTIVITY_READ, Resource.ordinary("activity", studentId, path, studentId));
            return jdbc.query("""
                select a.id, a.name, a.kind, a.lead_name, m.joined_on,
                       (select count(*) from activity_attendance t join activity_session s on s.tenant_id = t.tenant_id and s.id = t.session_id
                         where s.activity_id = a.id and t.student_person_id = m.student_person_id),
                       (select count(*) from activity_attendance t join activity_session s on s.tenant_id = t.tenant_id and s.id = t.session_id
                         where s.activity_id = a.id and t.student_person_id = m.student_person_id and t.present)
                  from activity_member m join activity a on a.tenant_id = m.tenant_id and a.id = m.activity_id
                 where m.student_person_id = ? and m.status = 'ACTIVE' order by a.name
                """, (rs, i) -> new ChildActivity(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getObject(5, LocalDate.class), rs.getInt(6), rs.getInt(7)), studentId);
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static final String VIEW_SQL = """
        select a.id, a.name, a.kind, a.capacity, a.lead_name, a.status,
               (select count(*) from activity_member m where m.activity_id = a.id and m.status = 'ACTIVE'),
               (select count(*) from activity_session s where s.activity_id = a.id)
          from activity a
        """;

    private static final org.springframework.jdbc.core.RowMapper<ActivityView> VIEW = (rs, i) -> new ActivityView(rs.getObject(1, UUID.class),
        rs.getString(2), rs.getString(3), (Integer) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8));

    private ActivityView one(JdbcTemplate jdbc, UUID id) {
        return jdbc.query(VIEW_SQL + " where a.id = ?", VIEW, id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private UUID activityCampus(JdbcTemplate jdbc, UUID id) {
        return jdbc.query("select campus_id from activity where id = ?", (rs, i) -> rs.getObject(1, UUID.class), id)
            .stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private void read(JdbcTemplate jdbc, RequestContext ctx, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, Permission.ACTIVITY_READ, Resource.ordinary("activity", null, access.campusPath(jdbc, ctx, campusId), null));
    }

    private void manage(JdbcTemplate jdbc, RequestContext ctx, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, Permission.ACTIVITY_MANAGE, Resource.ordinary("activity", null, access.campusPath(jdbc, ctx, campusId), null));
    }

    private void attend(JdbcTemplate jdbc, RequestContext ctx, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, Permission.ACTIVITY_ATTENDANCE, Resource.ordinary("activity", null, access.campusPath(jdbc, ctx, campusId), null));
    }
}
