package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M25 analytics. Read-only summaries of one academic year on one campus, calculated from data already in the system
 * (enrolment, attendance, fees). Everything here is a count or a total: no figure names a child, and restricted
 * records (safeguarding, health, support cases) are deliberately not summarised.
 */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_DAYS = 92;

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AcademicAccess access;

    public AnalyticsController(TenantTx tenantTx, Authorizer authz, AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.access = access;
    }

    public record Enrolment(int enrolled, int withdrawn, int transferredOut, int completed) { }

    public record AttendanceDay(LocalDate date, int marks, int attended, Double percent) { }

    public record AttendanceSection(String section, int marks, int attended, Double percent) { }

    public record Attendance(LocalDate from, LocalDate to, int sessions, int marks, int attended, int absent, Double percent,
                             List<AttendanceDay> days, List<AttendanceSection> sections) { }

    public record Fees(int invoices, long billedPaise, long collectedPaise, long outstandingPaise, Double collectedPercent,
                       int overdueInvoices, long overduePaise) { }

    public record Overview(UUID yearId, String yearLabel, Enrolment enrolment, Attendance attendance, Fees fees) { }

    @GetMapping("/academic-years/{yearId}/analytics")
    public Overview overview(RequestContext ctx, @PathVariable UUID yearId,
                             @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate today = LocalDate.now(IST);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(29);
        if (end.isBefore(start) || end.isAfter(start.plusDays(MAX_DAYS))) {
            throw ApiException.badRequest("invalid_range", "Choose a range of up to three months.");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            authz.requireOrNotFound(jdbc, ctx, Permission.ANALYTICS_READ,
                Resource.ordinary("analytics", null, access.campusPath(jdbc, ctx, year.campusId()), null));

            int[] e = new int[4];
            jdbc.query("select status, count(*) from student_enrollment where campus_id = ? and academic_year_id = ? group by status", rs -> {
                switch (rs.getString(1)) {
                    case "ENROLLED" -> e[0] = rs.getInt(2);
                    case "WITHDRAWN" -> e[1] = rs.getInt(2);
                    case "TRANSFERRED_OUT" -> e[2] = rs.getInt(2);
                    default -> e[3] = rs.getInt(2);
                }
            }, year.campusId(), yearId);

            List<AttendanceDay> days = jdbc.query("""
                select s.on_date, count(*), count(*) filter (where m.status in ('PRESENT','LATE'))
                  from attendance_mark m join attendance_session s on s.tenant_id = m.tenant_id and s.id = m.session_id
                       join class_section c on c.tenant_id = s.tenant_id and c.id = s.class_section_id
                 where c.academic_year_id = ? and s.on_date between ? and ?
                 group by s.on_date order by s.on_date
                """, (rs, i) -> new AttendanceDay(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3), percent(rs.getInt(3), rs.getInt(2))),
                yearId, start, end);
            List<AttendanceSection> sections = jdbc.query("""
                select g.name || ' ' || c.code, count(*), count(*) filter (where m.status in ('PRESENT','LATE'))
                  from attendance_mark m join attendance_session s on s.tenant_id = m.tenant_id and s.id = m.session_id
                       join class_section c on c.tenant_id = s.tenant_id and c.id = s.class_section_id
                       join grade_level g on g.tenant_id = c.tenant_id and g.id = c.grade_level_id
                 where c.academic_year_id = ? and s.on_date between ? and ?
                 group by g.name, c.code, g.ordinal order by count(*) filter (where m.status in ('PRESENT','LATE'))::float / count(*), g.ordinal, c.code
                """, (rs, i) -> new AttendanceSection(rs.getString(1), rs.getInt(2), rs.getInt(3), percent(rs.getInt(3), rs.getInt(2))),
                yearId, start, end);
            int sessions = jdbc.queryForObject("""
                select count(*) from attendance_session s join class_section c on c.tenant_id = s.tenant_id and c.id = s.class_section_id
                 where c.academic_year_id = ? and s.on_date between ? and ?
                """, Integer.class, yearId, start, end);
            int marks = days.stream().mapToInt(AttendanceDay::marks).sum();
            int attended = days.stream().mapToInt(AttendanceDay::attended).sum();

            Fees fees = jdbc.queryForObject("""
                select count(*), coalesce(sum(total_paise), 0), coalesce(sum(paid_paise), 0),
                       count(*) filter (where status in ('ISSUED','PARTIALLY_PAID') and due_on < ?),
                       coalesce(sum(total_paise - paid_paise) filter (where status in ('ISSUED','PARTIALLY_PAID') and due_on < ?), 0)
                  from invoice where campus_id = ? and academic_year_id = ? and status <> 'CANCELLED'
                """, (rs, i) -> {
                long billed = rs.getLong(2);
                long paid = rs.getLong(3);
                return new Fees(rs.getInt(1), billed, paid, billed - paid, percent(paid, billed), rs.getInt(4), rs.getLong(5));
            }, today, today, year.campusId(), yearId);

            return new Overview(yearId, year.label(), new Enrolment(e[0], e[1], e[2], e[3]),
                new Attendance(start, end, sessions, marks, attended, marks - attended, percent(attended, marks), days, sections), fees);
        });
    }

    /** One decimal place, or null when there is nothing to divide by. */
    private static Double percent(long part, long whole) {
        return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
    }
}
