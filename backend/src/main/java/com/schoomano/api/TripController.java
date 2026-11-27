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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M19 trips and events. Campus staff plan a trip and invite students; a guardian with the consent capability gives or
 * declines consent for their own child until the deadline (every decision is kept, the latest counts); on the day staff
 * record departure and return headcounts, only for children whose latest consent is "given".
 */
@RestController
@RequestMapping("/api/v1")
public class TripController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ScopeDirectory scopes;

    public TripController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access, ScopeDirectory scopes) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.scopes = scopes;
    }

    public record TripCreate(@NotBlank @Size(min = 2, max = 120) String name, @NotBlank @Size(min = 2, max = 200) String destination,
                             @NotNull LocalDate tripDate, @Min(0) long costPaise, @NotNull LocalDate consentDeadline,
                             @Min(1) @Max(1000) Integer capacity) { }

    public record TripView(UUID id, String name, String destination, LocalDate tripDate, long costPaise, LocalDate consentDeadline,
                           Integer capacity, String status, int invited, int given, int declined, int departed, int returned) { }

    public record InviteBatch(@NotEmpty @Size(max = 200) List<@NotNull UUID> studentIds) { }

    public record InviteResult(int added, int alreadyOnList) { }

    public record Participant(UUID id, UUID studentId, String name, String consent, boolean departed, boolean returned) { }

    public record ConsentCreate(@NotNull UUID studentId, @NotNull @Pattern(regexp = "GIVEN|DECLINED") String decision, @Size(max = 500) String note) { }

    public record ConsentView(UUID tripId, UUID studentId, String decision, boolean replacedEarlier) { }

    public record HeadcountCreate(@NotNull UUID studentId, @NotNull @Pattern(regexp = "DEPARTED|RETURNED") String checkpoint) { }

    public record HeadcountResult(UUID studentId, String checkpoint, boolean replayed) { }

    public record ChildTrip(UUID tripId, String name, String destination, LocalDate tripDate, long costPaise, LocalDate consentDeadline,
                            String status, String consent, boolean canDecide) { }

    // ---- planning --------------------------------------------------------------------------------------------

    @PostMapping("/academic-years/{yearId}/trips")
    @ResponseStatus(HttpStatus.CREATED)
    public TripView create(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody TripCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            manage(jdbc, ctx, year.campusId());
            if (req.consentDeadline().isAfter(req.tripDate())) {
                throw ApiException.badRequest("deadline_after_trip", "Consent must close on or before the day of the trip.");
            }
            if (req.tripDate().isBefore(LocalDate.now(IST))) {
                throw ApiException.badRequest("trip_in_past", "Plan trips for today or later.");
            }
            UUID id = jdbc.queryForObject("""
                insert into trip (tenant_id, campus_id, academic_year_id, name, destination, trip_date, cost_paise, consent_deadline, capacity, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), year.campusId(), yearId, req.name().trim(), req.destination().trim(), req.tripDate(),
                req.costPaise(), req.consentDeadline(), req.capacity(), ctx.actor().personId());
            audit.write(jdbc, ctx, "trip.planned", "trip", id.toString(), "SUCCESS", null, Map.of());
            return one(jdbc, id);
        });
    }

    @GetMapping("/academic-years/{yearId}/trips")
    public List<TripView> list(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            read(jdbc, ctx, year.campusId());
            return jdbc.query(VIEW_SQL + " where t.academic_year_id = ? order by t.trip_date desc, t.name", VIEW, yearId);
        });
    }

    @PostMapping("/trips/{id}/cancel")
    public TripView cancel(RequestContext ctx, @PathVariable UUID id) {
        return finish(ctx, id, "CANCELLED", "trip.cancelled");
    }

    @PostMapping("/trips/{id}/complete")
    public TripView complete(RequestContext ctx, @PathVariable UUID id) {
        return finish(ctx, id, "COMPLETED", "trip.completed");
    }

    private TripView finish(RequestContext ctx, UUID id, String status, String action) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            manage(jdbc, ctx, tripCampus(jdbc, id));
            try {
                int n = jdbc.update("update trip set status = ? where id = ? and status = 'PLANNED'", status, id);
                if (n == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "trip_finished", "This trip has already been completed or cancelled.");
                }
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "too_early", "A trip cannot be completed before its day.");
            }
            audit.write(jdbc, ctx, action, "trip", id.toString(), "SUCCESS", null, Map.of());
            return one(jdbc, id);
        });
    }

    // ---- the list of students --------------------------------------------------------------------------------

    @PostMapping("/trips/{id}/participants")
    public InviteResult invite(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody InviteBatch req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            manage(jdbc, ctx, tripCampus(jdbc, id));
            int added = 0;
            for (UUID studentId : req.studentIds()) {
                try {
                    added += jdbc.update("""
                        insert into trip_participant (tenant_id, trip_id, student_person_id) values (?, ?, ?)
                        on conflict (tenant_id, trip_id, student_person_id) do nothing
                        """, ctx.tenant().value(), id, studentId);
                } catch (DataIntegrityViolationException e) {
                    String m = String.valueOf(e.getMostSpecificCause().getMessage());
                    if (m.contains("trip full")) {
                        throw new ApiException(HttpStatus.CONFLICT, "trip_full", "This trip has no seats left.");
                    }
                    if (m.contains("not open")) {
                        throw new ApiException(HttpStatus.CONFLICT, "trip_closed", "This trip is no longer open.");
                    }
                    throw ApiException.badRequest("invalid_student", "Only students enrolled this academic year can be invited.");
                }
            }
            audit.write(jdbc, ctx, "trip.students_invited", "trip", id.toString(), "SUCCESS", null, Map.of("count", added));
            return new InviteResult(added, req.studentIds().size() - added);
        });
    }

    @PostMapping("/trip-participants/{id}/remove")
    public Map<String, String> remove(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID tripId = jdbc.query("select trip_id from trip_participant where id = ?", (rs, i) -> rs.getObject(1, UUID.class), id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            manage(jdbc, ctx, tripCampus(jdbc, tripId));
            int n = jdbc.update("update trip_participant set status = 'REMOVED' where id = ? and status = 'INVITED'", id);
            if (n == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_removed", "This student is already off the list.");
            }
            audit.write(jdbc, ctx, "trip.student_removed", "trip_participant", id.toString(), "SUCCESS", null, Map.of());
            return Map.of("status", "REMOVED");
        });
    }

    @GetMapping("/trips/{id}/participants")
    public List<Participant> participants(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            read(jdbc, ctx, tripCampus(jdbc, id));
            return jdbc.query("""
                select p.id, p.student_person_id, trim(coalesce(s.given_name, '') || ' ' || coalesce(s.family_name, '')),
                       coalesce((select c.decision from trip_consent c where c.trip_id = p.trip_id and c.student_person_id = p.student_person_id
                                  order by c.decided_at desc, c.id limit 1), 'NONE'),
                       exists (select 1 from trip_headcount h where h.trip_id = p.trip_id and h.student_person_id = p.student_person_id and h.checkpoint = 'DEPARTED'),
                       exists (select 1 from trip_headcount h where h.trip_id = p.trip_id and h.student_person_id = p.student_person_id and h.checkpoint = 'RETURNED')
                  from trip_participant p join person s on s.tenant_id = p.tenant_id and s.id = p.student_person_id
                 where p.trip_id = ? and p.status = 'INVITED' order by 3
                """, (rs, i) -> new Participant(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    rs.getBoolean(5), rs.getBoolean(6)), id);
        });
    }

    // ---- consent ---------------------------------------------------------------------------------------------

    @PostMapping("/trips/{id}/consent")
    public ConsentView consent(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody ConsentCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), req.studentId()).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.CONSENT_GIVE, Resource.ordinary("trip", id, path, req.studentId()));
            boolean earlier = jdbc.queryForObject("select count(*) from trip_consent where trip_id = ? and student_person_id = ?", Long.class, id, req.studentId()) > 0;
            try {
                jdbc.update("insert into trip_consent (tenant_id, trip_id, student_person_id, guardian_person_id, decision, note) values (?, ?, ?, ?, ?, ?)",
                    ctx.tenant().value(), id, req.studentId(), ctx.actor().personId(), req.decision(), req.note() == null ? null : req.note().trim());
            } catch (DataIntegrityViolationException e) {
                String m = String.valueOf(e.getMostSpecificCause().getMessage());
                if (m.contains("deadline")) {
                    throw new ApiException(HttpStatus.CONFLICT, "deadline_passed", "The consent deadline for this trip has passed. Please contact the school.");
                }
                if (m.contains("not open")) {
                    throw new ApiException(HttpStatus.CONFLICT, "trip_closed", "This trip is no longer open.");
                }
                throw ApiException.notFound();
            }
            audit.write(jdbc, ctx, "trip.consent_" + req.decision().toLowerCase(), "trip", id.toString(), "SUCCESS", null, Map.of("studentId", req.studentId().toString()));
            return new ConsentView(id, req.studentId(), req.decision(), earlier);
        });
    }

    @GetMapping("/students/{studentId}/trips")
    public List<ChildTrip> child(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.TRIP_READ, Resource.ordinary("trip", studentId, path, studentId));
            LocalDate today = LocalDate.now(IST);
            return jdbc.query("""
                select t.id, t.name, t.destination, t.trip_date, t.cost_paise, t.consent_deadline, t.status,
                       coalesce((select c.decision from trip_consent c where c.trip_id = t.id and c.student_person_id = p.student_person_id
                                  order by c.decided_at desc, c.id limit 1), 'NONE')
                  from trip_participant p join trip t on t.tenant_id = p.tenant_id and t.id = p.trip_id
                 where p.student_person_id = ? and p.status = 'INVITED' and t.status <> 'CANCELLED' and t.trip_date >= ? - 30
                 order by t.trip_date
                """, (rs, i) -> new ChildTrip(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                    rs.getLong(5), rs.getObject(6, LocalDate.class), rs.getString(7), rs.getString(8),
                    "PLANNED".equals(rs.getString(7)) && !today.isAfter(rs.getObject(6, LocalDate.class))), studentId, today);
        });
    }

    // ---- headcount -------------------------------------------------------------------------------------------

    @PostMapping("/trips/{id}/headcount")
    public HeadcountResult headcount(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody HeadcountCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID campusId = tripCampus(jdbc, id);
            authz.requireOrNotFound(jdbc, ctx, Permission.TRIP_HEADCOUNT, Resource.ordinary("trip", id, access.campusPath(jdbc, ctx, campusId), null));
            int n;
            try {
                n = jdbc.update("""
                    insert into trip_headcount (tenant_id, trip_id, student_person_id, checkpoint, recorded_by) values (?, ?, ?, ?, ?)
                    on conflict (tenant_id, trip_id, student_person_id, checkpoint) do nothing
                    """, ctx.tenant().value(), id, req.studentId(), req.checkpoint(), ctx.actor().personId());
            } catch (DataIntegrityViolationException e) {
                String m = String.valueOf(e.getMostSpecificCause().getMessage());
                if (m.contains("consent")) {
                    throw ApiException.badRequest("no_consent", "This child does not have a guardian's consent for the trip.");
                }
                if (m.contains("departed")) {
                    throw ApiException.badRequest("not_departed", "Record that the child left before recording their return.");
                }
                if (m.contains("not started")) {
                    throw ApiException.badRequest("not_started", "The trip has not started yet.");
                }
                throw ApiException.badRequest("trip_cancelled", "This trip was cancelled.");
            }
            return new HeadcountResult(req.studentId(), req.checkpoint(), n == 0);
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static final String VIEW_SQL = """
        select t.id, t.name, t.destination, t.trip_date, t.cost_paise, t.consent_deadline, t.capacity, t.status,
               (select count(*) from trip_participant p where p.trip_id = t.id and p.status = 'INVITED'),
               (select count(*) from trip_participant p where p.trip_id = t.id and p.status = 'INVITED'
                   and (select c.decision from trip_consent c where c.trip_id = p.trip_id and c.student_person_id = p.student_person_id order by c.decided_at desc, c.id limit 1) = 'GIVEN'),
               (select count(*) from trip_participant p where p.trip_id = t.id and p.status = 'INVITED'
                   and (select c.decision from trip_consent c where c.trip_id = p.trip_id and c.student_person_id = p.student_person_id order by c.decided_at desc, c.id limit 1) = 'DECLINED'),
               (select count(*) from trip_headcount h where h.trip_id = t.id and h.checkpoint = 'DEPARTED'),
               (select count(*) from trip_headcount h where h.trip_id = t.id and h.checkpoint = 'RETURNED')
          from trip t
        """;

    private static final org.springframework.jdbc.core.RowMapper<TripView> VIEW = (rs, i) -> new TripView(rs.getObject(1, UUID.class), rs.getString(2),
        rs.getString(3), rs.getObject(4, LocalDate.class), rs.getLong(5), rs.getObject(6, LocalDate.class), (Integer) rs.getObject(7), rs.getString(8),
        rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getInt(13));

    private TripView one(JdbcTemplate jdbc, UUID id) {
        return jdbc.query(VIEW_SQL + " where t.id = ?", VIEW, id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private UUID tripCampus(JdbcTemplate jdbc, UUID id) {
        return jdbc.query("select campus_id from trip where id = ?", (rs, i) -> rs.getObject(1, UUID.class), id)
            .stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private void read(JdbcTemplate jdbc, RequestContext ctx, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, Permission.TRIP_READ, Resource.ordinary("trip", null, access.campusPath(jdbc, ctx, campusId), null));
    }

    private void manage(JdbcTemplate jdbc, RequestContext ctx, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, Permission.TRIP_MANAGE, Resource.ordinary("trip", null, access.campusPath(jdbc, ctx, campusId), null));
    }
}
