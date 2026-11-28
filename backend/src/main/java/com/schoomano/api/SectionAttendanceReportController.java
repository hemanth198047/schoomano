package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.attendance.AttendanceFigures;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
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
 * B64 attendance report for one section over a date range (ATTENDANCE_READ for the section, at most 92 days). One row per
 * enrolled student with counts and the attendance percentage (see AttendanceFigures), plus how many days were taken.
 */
@RestController
@RequestMapping("/api/v1")
public class SectionAttendanceReportController {

    static final int MAX_DAYS = 92;

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AcademicAccess access;

    public SectionAttendanceReportController(TenantTx tenantTx, Authorizer authz, AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.access = access;
    }

    public record Row(UUID studentId, String schoolRef, String name, int present, int late, int absent, int leave, Double percent) { }

    public record Report(UUID sectionId, String sectionLabel, LocalDate from, LocalDate to, int daysTaken, List<Row> students) { }

    @GetMapping("/sections/{sectionId}/attendance/report")
    public Report report(RequestContext ctx, @PathVariable UUID sectionId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw ApiException.badRequest("invalid_range", "Choose a range of at most " + MAX_DAYS + " days.");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID yearId = jdbc.queryForList("select academic_year_id from class_section where id = ?", UUID.class, sectionId)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            ScopePath path = access.campusPath(jdbc, ctx, access.loadYear(jdbc, ctx, yearId).campusId());
            authz.requireOrNotFound(jdbc, ctx, Permission.ATTENDANCE_READ, Resource.ordinary("attendance", sectionId, path, null));
            String label = jdbc.queryForObject("select g.name || ' ' || s.code from class_section s join grade_level g on g.id = s.grade_level_id where s.id = ?",
                String.class, sectionId);
            Integer days = jdbc.queryForObject("select count(*) from attendance_session where class_section_id = ? and on_date between ? and ?",
                Integer.class, sectionId, from, to);
            List<Row> rows = jdbc.query("""
                select p.id, p.school_ref, trim(p.given_name || ' ' || coalesce(p.family_name, '')),
                       count(*) filter (where m.status = 'PRESENT'), count(*) filter (where m.status = 'LATE'),
                       count(*) filter (where m.status = 'ABSENT'), count(*) filter (where m.status = 'LEAVE')
                  from student_enrollment e
                  join person p on p.id = e.student_person_id
                  left join attendance_session a on a.class_section_id = e.class_section_id and a.on_date between ? and ?
                  left join attendance_mark m on m.session_id = a.id and m.student_person_id = p.id
                 where e.class_section_id = ? and e.status = 'ENROLLED'
                 group by p.id, p.school_ref, p.given_name, p.family_name
                 order by p.given_name, p.family_name, p.id
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                    rs.getInt(7), AttendanceFigures.percent(rs.getInt(4), rs.getInt(5), rs.getInt(6))), from, to, sectionId);
            return new Report(sectionId, label, from, to, days == null ? 0 : days, rows);
        });
    }
}
