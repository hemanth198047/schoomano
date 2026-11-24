package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import com.schoomano.platform.TenantTx;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One child's attendance over a date range, for a guardian with the view-attendance capability or staff with
 * ATTENDANCE_READ in scope. A guardian never sees classmates, and a denied read looks like absence (404).
 */
@RestController
@RequestMapping("/api/v1")
public class StudentAttendanceController {

    static final int MAX_DAYS = 92;

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final ScopeDirectory scopes;

    public StudentAttendanceController(TenantTx tenantTx, Authorizer authz, ScopeDirectory scopes) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.scopes = scopes;
    }

    public record Day(LocalDate date, String status, String classLabel) { }

    public record StudentAttendance(UUID studentId, LocalDate from, LocalDate to, List<Day> days, int present, int absent, int late, int leave) { }

    @GetMapping("/students/{studentId}/attendance")
    public StudentAttendance forStudent(RequestContext ctx, @PathVariable UUID studentId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw ApiException.badRequest("invalid_range", "Choose a range of at most " + MAX_DAYS + " days.");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.ATTENDANCE_READ, Resource.ordinary("attendance", null, path, studentId));
            List<Day> days = jdbc.query("""
                select a.on_date, m.status, g.name || ' ' || s.code
                  from attendance_mark m
                  join attendance_session a on a.id = m.session_id
                  join class_section s on s.id = a.class_section_id
                  join grade_level g on g.id = s.grade_level_id
                 where m.student_person_id = ? and a.on_date between ? and ?
                 order by a.on_date
                """, (rs, i) -> new Day(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3)), studentId, from, to);
            return new StudentAttendance(studentId, from, to, days, count(days, "PRESENT"), count(days, "ABSENT"), count(days, "LATE"), count(days, "LEAVE"));
        });
    }

    private static int count(List<Day> days, String status) {
        return (int) days.stream().filter(d -> d.status().equals(status)).count();
    }
}
