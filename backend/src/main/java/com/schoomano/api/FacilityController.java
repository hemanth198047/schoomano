package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M21 facilities. Staff book the campus's rooms for a purpose (two bookings can never overlap, enforced in the
 * database) and report maintenance problems; the campus office moves a problem OPEN, IN_PROGRESS, RESOLVED with a note.
 * Rooms themselves are the timetable's rooms.
 */
@RestController
@RequestMapping("/api/v1")
public class FacilityController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;

    public FacilityController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
    }

    public record BookingCreate(@NotBlank @Size(min = 2, max = 200) String purpose, @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt) { }

    public record BookingView(UUID id, UUID roomId, String roomName, String purpose, OffsetDateTime startsAt, OffsetDateTime endsAt,
                              String bookedBy, boolean mine, String status) { }

    public record IssueCreate(UUID roomId, @NotBlank @Size(min = 3, max = 120) String title, @Size(max = 2000) String description,
                              @NotNull @Pattern(regexp = "LOW|NORMAL|URGENT") String priority) { }

    public record IssueView(UUID id, UUID roomId, String roomName, String title, String description, String priority, String status,
                            String reportedBy, OffsetDateTime reportedAt, String resolution, OffsetDateTime resolvedAt) { }

    public record Resolution(@NotBlank @Size(min = 2, max = 1000) String resolution) { }

    // ---- bookings --------------------------------------------------------------------------------------------

    @PostMapping("/rooms/{roomId}/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView book(RequestContext ctx, @PathVariable UUID roomId, @Valid @RequestBody BookingCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID campusId = jdbc.query("select campus_id from room where id = ?", (rs, i) -> rs.getObject(1, UUID.class), roomId)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            require(jdbc, ctx, Permission.FACILITY_REQUEST, campusId);
            try {
                UUID id = jdbc.queryForObject("""
                    insert into room_booking (tenant_id, room_id, booked_by, purpose, starts_at, ends_at) values (?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), roomId, ctx.actor().personId(), req.purpose().trim(), req.startsAt(), req.endsAt());
                audit.write(jdbc, ctx, "facility.room_booked", "room", roomId.toString(), "SUCCESS", null, Map.of("bookingId", id.toString()));
                return bookings(jdbc, ctx, "b.id = ?", id).get(0);
            } catch (DataIntegrityViolationException e) {
                String m = String.valueOf(e.getMostSpecificCause().getMessage());
                if (m.contains("already booked")) {
                    throw new ApiException(HttpStatus.CONFLICT, "room_booked", "That room is already booked for part of that time.");
                }
                if (m.contains("retired")) {
                    throw new ApiException(HttpStatus.CONFLICT, "room_retired", "That room is no longer in use.");
                }
                throw ApiException.badRequest("invalid_time", "Bookings must start in the future, end after they start, and last at most 12 hours.");
            }
        });
    }

    @GetMapping("/campuses/{campusId}/bookings")
    public List<BookingView> bookings(RequestContext ctx, @PathVariable UUID campusId,
                                      @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.FACILITY_READ, campusId);
            LocalDate start = from != null ? from : LocalDate.now(IST);
            LocalDate end = to != null ? to : start.plusDays(14);
            if (end.isBefore(start) || end.isAfter(start.plusDays(92))) {
                throw ApiException.badRequest("invalid_range", "Choose a range of up to three months.");
            }
            return bookings(jdbc, ctx, "r.campus_id = ? and b.status = 'BOOKED' and b.starts_at >= ? and b.starts_at < ?",
                campusId, start.atStartOfDay(IST).toOffsetDateTime(), end.plusDays(1).atStartOfDay(IST).toOffsetDateTime());
        });
    }

    @PostMapping("/bookings/{id}/cancel")
    public BookingView cancel(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> row = jdbc.queryForList("""
                select r.campus_id, b.booked_by from room_booking b join room r on r.tenant_id = b.tenant_id and r.id = b.room_id where b.id = ?
                """, id).stream().findFirst().orElseThrow(ApiException::notFound);
            UUID campusId = (UUID) row.get("campus_id");
            boolean mine = ctx.actor().personId().equals(row.get("booked_by"));
            require(jdbc, ctx, mine ? Permission.FACILITY_REQUEST : Permission.FACILITY_MANAGE, campusId);
            if (jdbc.update("update room_booking set status = 'CANCELLED' where id = ? and status = 'BOOKED'", id) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_cancelled", "This booking is already cancelled.");
            }
            audit.write(jdbc, ctx, "facility.booking_cancelled", "room_booking", id.toString(), "SUCCESS", null, Map.of());
            return bookings(jdbc, ctx, "b.id = ?", id).get(0);
        });
    }

    // ---- maintenance -----------------------------------------------------------------------------------------

    @PostMapping("/campuses/{campusId}/maintenance")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueView report(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody IssueCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.FACILITY_REQUEST, campusId);
            try {
                UUID id = jdbc.queryForObject("""
                    insert into maintenance_request (tenant_id, campus_id, room_id, title, description, priority, reported_by) values (?, ?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), campusId, req.roomId(), req.title().trim(),
                    req.description() == null || req.description().isBlank() ? null : req.description().trim(), req.priority(), ctx.actor().personId());
                audit.write(jdbc, ctx, "facility.issue_reported", "maintenance_request", id.toString(), "SUCCESS", null, Map.of("priority", req.priority()));
                return issues(jdbc, "m.id = ?", id).get(0);
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("unknown_room", "That room is not on this campus.");
            }
        });
    }

    @GetMapping("/campuses/{campusId}/maintenance")
    public List<IssueView> issues(RequestContext ctx, @PathVariable UUID campusId, @RequestParam(required = false) String status) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.FACILITY_READ, campusId);
            if (status != null && !status.matches("OPEN|IN_PROGRESS|RESOLVED")) {
                throw ApiException.badRequest("invalid_status", "Status must be OPEN, IN_PROGRESS or RESOLVED.");
            }
            return status == null ? issues(jdbc, "m.campus_id = ?", campusId) : issues(jdbc, "m.campus_id = ? and m.status = ?", campusId, status);
        });
    }

    @PostMapping("/maintenance/{id}/start")
    public IssueView start(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            manage(jdbc, ctx, id);
            if (jdbc.update("update maintenance_request set status = 'IN_PROGRESS' where id = ? and status = 'OPEN'", id) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "not_open", "Only an open request can be started.");
            }
            audit.write(jdbc, ctx, "facility.issue_started", "maintenance_request", id.toString(), "SUCCESS", null, Map.of());
            return issues(jdbc, "m.id = ?", id).get(0);
        });
    }

    @PostMapping("/maintenance/{id}/resolve")
    public IssueView resolve(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody Resolution req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            manage(jdbc, ctx, id);
            if (jdbc.update("update maintenance_request set status = 'RESOLVED', resolution = ?, resolved_at = clock_timestamp() where id = ? and status = 'IN_PROGRESS'",
                    req.resolution().trim(), id) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "not_in_progress", "Start the work before resolving the request.");
            }
            audit.write(jdbc, ctx, "facility.issue_resolved", "maintenance_request", id.toString(), "SUCCESS", null, Map.of());
            return issues(jdbc, "m.id = ?", id).get(0);
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void manage(JdbcTemplate jdbc, RequestContext ctx, UUID requestId) {
        UUID campusId = jdbc.query("select campus_id from maintenance_request where id = ?", (rs, i) -> rs.getObject(1, UUID.class), requestId)
            .stream().findFirst().orElseThrow(ApiException::notFound);
        require(jdbc, ctx, Permission.FACILITY_MANAGE, campusId);
    }

    private void require(JdbcTemplate jdbc, RequestContext ctx, Permission permission, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, permission, Resource.ordinary("facility", null, access.campusPath(jdbc, ctx, campusId), null));
    }

    private List<BookingView> bookings(JdbcTemplate jdbc, RequestContext ctx, String where, Object... args) {
        UUID me = ctx.actor().personId();
        RowMapper<BookingView> map = (rs, i) -> new BookingView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
            rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), rs.getString(7), me.equals(rs.getObject(8, UUID.class)), rs.getString(9));
        return jdbc.query("""
            select b.id, b.room_id, r.name, b.purpose, b.starts_at, b.ends_at,
                   trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), b.booked_by, b.status
              from room_booking b join room r on r.tenant_id = b.tenant_id and r.id = b.room_id
                   join person p on p.tenant_id = b.tenant_id and p.id = b.booked_by
             where """ + " " + where + " order by b.starts_at", map, args);
    }

    private List<IssueView> issues(JdbcTemplate jdbc, String where, Object... args) {
        RowMapper<IssueView> map = (rs, i) -> new IssueView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getObject(9, OffsetDateTime.class), rs.getString(10),
            rs.getObject(11, OffsetDateTime.class));
        return jdbc.query("""
            select m.id, m.room_id, r.name, m.title, m.description, m.priority, m.status,
                   trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), m.reported_at, m.resolution, m.resolved_at
              from maintenance_request m left join room r on r.tenant_id = m.tenant_id and r.id = m.room_id
                   join person p on p.tenant_id = m.tenant_id and p.id = m.reported_by
             where """ + " " + where + " order by m.status = 'RESOLVED', case m.priority when 'URGENT' then 0 when 'NORMAL' then 1 else 2 end, m.reported_at desc", map, args);
    }
}
