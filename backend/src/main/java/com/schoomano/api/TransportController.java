package com.schoomano.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M17 transport. Campus administrators set up vehicles, routes and stops and assign students within vehicle capacity;
 * the transport attendant records who boarded and got off; a guardian sees their own child's route, stop, driver and
 * today's boarding. Boarding records are permanent and idempotent, so a phone that retries is harmless.
 */
@RestController
@RequestMapping("/api/v1")
public class TransportController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ScopeDirectory scopes;
    private final ObjectMapper json;

    public TransportController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access, ScopeDirectory scopes,
                               ObjectMapper json) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.scopes = scopes;
        this.json = json;
    }

    public record VehicleCreate(@NotBlank @Size(min = 4, max = 20) String registration, @Min(1) @Max(100) int capacity) { }

    public record VehicleView(UUID id, String registration, int capacity) { }

    public record RouteCreate(@NotBlank @Size(min = 2, max = 80) String name, UUID vehicleId, @NotBlank @Size(min = 2, max = 80) String driverName,
                              @Pattern(regexp = "^\\+?[0-9]{8,15}$") String driverPhone) { }

    public record StopCreate(@NotBlank @Size(min = 2, max = 80) String name, @NotNull LocalTime pickupTime) { }

    public record StopView(UUID id, int sequence, String name, LocalTime pickupTime) { }

    public record RouteView(UUID id, String name, String vehicle, Integer capacity, String driverName, String driverPhone,
                            int assigned, List<StopView> stops) { }

    public record AssignmentCreate(@NotNull UUID studentId, @NotNull UUID routeId, @NotNull UUID stopId) { }

    public record BoardingCreate(@NotNull UUID studentId, @NotNull @Pattern(regexp = "MORNING|AFTERNOON") String trip,
                                 @NotNull @Pattern(regexp = "BOARDED|ALIGHTED") String event, LocalDate tripDate) { }

    public record BoardingView(UUID studentId, String trip, String event, LocalDate tripDate, OffsetDateTime recordedAt, boolean replayed) { }

    public record RosterEntry(UUID assignmentId, UUID studentId, String name, String stop, LocalTime pickupTime, List<BoardingView> today) { }

    public record ChildTransport(UUID studentId, String route, String stop, LocalTime pickupTime, String vehicle, String driverName,
                                 String driverPhone, List<BoardingView> today) { }

    // ---- setup -----------------------------------------------------------------------------------------------

    @PostMapping("/campuses/{campusId}/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleView addVehicle(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody VehicleCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            manage(jdbc, ctx, campusId);
            try {
                UUID id = jdbc.queryForObject("insert into vehicle (tenant_id, campus_id, registration, capacity) values (?, ?, ?, ?) returning id",
                    UUID.class, ctx.tenant().value(), campusId, req.registration().trim().toUpperCase(), req.capacity());
                audit.write(jdbc, ctx, "transport.vehicle_added", "vehicle", id.toString(), "SUCCESS", null, Map.of());
                return new VehicleView(id, req.registration().trim().toUpperCase(), req.capacity());
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "duplicate_vehicle", "A vehicle with that registration already exists.");
            }
        });
    }

    @GetMapping("/campuses/{campusId}/vehicles")
    public List<VehicleView> vehicles(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            read(jdbc, ctx, campusId);
            return jdbc.query("select id, registration, capacity from vehicle where campus_id = ? and active order by registration",
                (rs, i) -> new VehicleView(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)), campusId);
        });
    }

    @PostMapping("/campuses/{campusId}/routes")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteView addRoute(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody RouteCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            manage(jdbc, ctx, campusId);
            if (req.vehicleId() != null && jdbc.queryForObject("select count(*) from vehicle where id = ? and campus_id = ? and active", Long.class,
                req.vehicleId(), campusId) == 0) {
                throw ApiException.badRequest("unknown_vehicle", "Choose a vehicle of this campus.");
            }
            try {
                UUID id = jdbc.queryForObject("""
                    insert into transport_route (tenant_id, campus_id, name, vehicle_id, driver_name, driver_phone) values (?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), campusId, req.name().trim(), req.vehicleId(), req.driverName().trim(), req.driverPhone());
                audit.write(jdbc, ctx, "transport.route_added", "route", id.toString(), "SUCCESS", null, Map.of());
                return routes(jdbc, campusId, id).get(0);
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "duplicate_route", "This campus already has a route with that name.");
            }
        });
    }

    @PostMapping("/routes/{routeId}/stops")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteView addStop(RequestContext ctx, @PathVariable UUID routeId, @Valid @RequestBody StopCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID campusId = routeCampus(jdbc, routeId);
            manage(jdbc, ctx, campusId);
            jdbc.update("""
                insert into route_stop (tenant_id, route_id, sequence, name, pickup_time)
                values (?, ?, coalesce((select max(sequence) from route_stop where route_id = ?), 0) + 1, ?, ?)
                """, ctx.tenant().value(), routeId, routeId, req.name().trim(), req.pickupTime());
            return routes(jdbc, campusId, routeId).get(0);
        });
    }

    @GetMapping("/campuses/{campusId}/routes")
    public List<RouteView> routeList(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            read(jdbc, ctx, campusId);
            return routes(jdbc, campusId, null);
        });
    }

    // ---- assignments -----------------------------------------------------------------------------------------

    @PostMapping("/academic-years/{yearId}/transport-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> assign(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody AssignmentCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            manage(jdbc, ctx, year.campusId());
            if (jdbc.queryForObject("select count(*) from transport_route where id = ? and campus_id = ?", Long.class, req.routeId(), year.campusId()) == 0) {
                throw ApiException.badRequest("unknown_route", "Choose a route of this campus.");
            }
            try {
                UUID id = jdbc.queryForObject("""
                    insert into transport_assignment (tenant_id, academic_year_id, student_person_id, route_id, stop_id) values (?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), yearId, req.studentId(), req.routeId(), req.stopId());
                audit.write(jdbc, ctx, "transport.assigned", "student", req.studentId().toString(), "SUCCESS", null, Map.of("routeId", req.routeId().toString()));
                return Map.of("id", id);
            } catch (DataIntegrityViolationException e) {
                String m = String.valueOf(e.getMostSpecificCause().getMessage());
                if (m.contains("route full")) {
                    throw new ApiException(HttpStatus.CONFLICT, "route_full", "The vehicle on this route is full.");
                }
                if (m.contains("transport_one_active")) {
                    throw new ApiException(HttpStatus.CONFLICT, "already_assigned", "This student already has a route this year. End it first.");
                }
                throw ApiException.badRequest("invalid_assignment", "The student must be enrolled this year and the stop must be on the route.");
            }
        });
    }

    @PostMapping("/transport-assignments/{id}/end")
    public Map<String, String> end(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID campusId = jdbc.query("select r.campus_id from transport_assignment a join transport_route r on r.tenant_id = a.tenant_id and r.id = a.route_id where a.id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), id).stream().findFirst().orElseThrow(ApiException::notFound);
            manage(jdbc, ctx, campusId);
            int n = jdbc.update("update transport_assignment set status = 'ENDED', ended_on = current_date where id = ? and status = 'ACTIVE'", id);
            if (n == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_ended", "This assignment has already ended.");
            }
            audit.write(jdbc, ctx, "transport.assignment_ended", "transport_assignment", id.toString(), "SUCCESS", null, Map.of());
            return Map.of("status", "ENDED");
        });
    }

    // ---- boarding --------------------------------------------------------------------------------------------

    @GetMapping("/routes/{routeId}/roster")
    public List<RosterEntry> roster(RequestContext ctx, @PathVariable UUID routeId, @RequestParam(required = false) LocalDate date) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            read(jdbc, ctx, routeCampus(jdbc, routeId));
            LocalDate day = date == null ? LocalDate.now(IST) : date;
            return jdbc.query("""
                select a.id, a.student_person_id, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), s.name, s.pickup_time
                  from transport_assignment a
                  join person p on p.tenant_id = a.tenant_id and p.id = a.student_person_id
                  join route_stop s on s.tenant_id = a.tenant_id and s.id = a.stop_id
                 where a.route_id = ? and a.status = 'ACTIVE' order by s.sequence, 3
                """, (rs, i) -> new RosterEntry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    rs.getObject(5, LocalTime.class), events(jdbc, routeId, rs.getObject(2, UUID.class), day)), routeId);
        });
    }

    @PostMapping("/routes/{routeId}/boarding")
    public BoardingView board(RequestContext ctx, @PathVariable UUID routeId, @Valid @RequestBody BoardingCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID campusId = routeCampus(jdbc, routeId);
            authz.requireOrNotFound(jdbc, ctx, Permission.BOARDING_RECORD, Resource.ordinary("route", routeId, access.campusPath(jdbc, ctx, campusId), null));
            LocalDate day = req.tripDate() == null ? LocalDate.now(IST) : req.tripDate();
            int n;
            try {
                n = jdbc.update("""
                    insert into boarding_event (tenant_id, route_id, student_person_id, trip_date, trip, event, recorded_by)
                    values (?, ?, ?, ?, ?, ?, ?) on conflict (tenant_id, route_id, student_person_id, trip_date, trip, event) do nothing
                    """, ctx.tenant().value(), routeId, req.studentId(), day, req.trip(), req.event(), ctx.actor().personId());
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("not_on_route", "This student is not assigned to this route, or the trip is in the future.");
            }
            if (n == 1) {
                outbox(jdbc, ctx, req.studentId(), "BOARDED".equals(req.event()) ? "transport.boarded" : "transport.alighted",
                    Map.of("routeId", routeId.toString(), "trip", req.trip(), "tripDate", day.toString()));
            }
            BoardingView v = events(jdbc, routeId, req.studentId(), day).stream()
                .filter(e -> e.trip().equals(req.trip()) && e.event().equals(req.event())).findFirst().orElseThrow();
            return new BoardingView(v.studentId(), v.trip(), v.event(), v.tripDate(), v.recordedAt(), n == 0);
        });
    }

    /** A guardian's (or staff member's) view of one child's route and today's boarding. */
    @GetMapping("/students/{studentId}/transport")
    public ChildTransport child(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.TRANSPORT_READ, Resource.ordinary("transport", studentId, path, studentId));
            return jdbc.query("""
                select a.route_id, r.name, s.name, s.pickup_time, v.registration, r.driver_name, r.driver_phone
                  from transport_assignment a
                  join transport_route r on r.tenant_id = a.tenant_id and r.id = a.route_id
                  join route_stop s on s.tenant_id = a.tenant_id and s.id = a.stop_id
                  left join vehicle v on v.tenant_id = r.tenant_id and v.id = r.vehicle_id
                 where a.student_person_id = ? and a.status = 'ACTIVE' order by a.started_on desc limit 1
                """, (rs, i) -> new ChildTransport(studentId, rs.getString(2), rs.getString(3), rs.getObject(4, LocalTime.class), rs.getString(5),
                    rs.getString(6), rs.getString(7), events(jdbc, rs.getObject(1, UUID.class), studentId, LocalDate.now(IST))), studentId)
                .stream().findFirst().orElseThrow(ApiException::notFound);
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void manage(JdbcTemplate jdbc, RequestContext ctx, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, Permission.TRANSPORT_MANAGE, Resource.ordinary("transport", null, access.campusPath(jdbc, ctx, campusId), null));
    }

    private void read(JdbcTemplate jdbc, RequestContext ctx, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, Permission.TRANSPORT_READ, Resource.ordinary("transport", null, access.campusPath(jdbc, ctx, campusId), null));
    }

    private UUID routeCampus(JdbcTemplate jdbc, UUID routeId) {
        return jdbc.query("select campus_id from transport_route where id = ?", (rs, i) -> rs.getObject(1, UUID.class), routeId)
            .stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private List<RouteView> routes(JdbcTemplate jdbc, UUID campusId, UUID only) {
        return jdbc.query("""
            select r.id, r.name, v.registration, v.capacity, r.driver_name, r.driver_phone,
                   (select count(*) from transport_assignment a where a.route_id = r.id and a.status = 'ACTIVE')
              from transport_route r left join vehicle v on v.tenant_id = r.tenant_id and v.id = r.vehicle_id
             where r.campus_id = ? and r.active and (?::uuid is null or r.id = ?::uuid) order by r.name
            """, (rs, i) -> new RouteView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), jdbc.query("select id, sequence, name, pickup_time from route_stop where route_id = ? order by sequence",
                (r2, j) -> new StopView(r2.getObject(1, UUID.class), r2.getInt(2), r2.getString(3), r2.getObject(4, LocalTime.class)), rs.getObject(1, UUID.class))),
            campusId, only, only);
    }

    private List<BoardingView> events(JdbcTemplate jdbc, UUID routeId, UUID studentId, LocalDate day) {
        return jdbc.query("""
            select student_person_id, trip, event, trip_date, recorded_at from boarding_event
             where route_id = ? and student_person_id = ? and trip_date = ? order by recorded_at
            """, (rs, i) -> new BoardingView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                rs.getObject(5, OffsetDateTime.class), false), routeId, studentId, day);
    }

    private void outbox(JdbcTemplate jdbc, RequestContext ctx, UUID studentId, String event, Map<String, Object> payload) {
        try {
            jdbc.update("insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload) values (?, 'student', ?, ?, ?::jsonb)",
                ctx.tenant().value(), studentId.toString(), event, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
