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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
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
 * M21 visitor log. The campus office records who came in, why and whom they came to see, and closes the visit at
 * check-out. Entries are permanent; a badge number can be on only one person inside at a time.
 */
@RestController
@RequestMapping("/api/v1")
public class VisitorController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;

    public VisitorController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
    }

    public record VisitCreate(@NotBlank @Size(min = 2, max = 120) String visitorName, @Size(max = 120) String organisation,
                              @Pattern(regexp = "^[0-9+ -]{6,20}$") String phone, @NotBlank @Size(min = 2, max = 200) String purpose,
                              UUID hostPersonId, @Pattern(regexp = "^[A-Za-z0-9-]{1,12}$") String badgeNo) { }

    public record VisitView(UUID id, String visitorName, String organisation, String phone, String purpose, String host, String badgeNo,
                            OffsetDateTime checkedInAt, String checkedInBy, OffsetDateTime checkedOutAt, boolean inside) { }

    public record ExpectedCreate(@NotBlank @Size(min = 2, max = 120) String visitorName, @Size(max = 120) String organisation,
                                 @NotBlank @Size(min = 2, max = 200) String purpose, @NotNull LocalDate expectedOn) { }

    public record Arrival(@Pattern(regexp = "^[0-9+ -]{6,20}$") String phone, @Pattern(regexp = "^[A-Za-z0-9-]{1,12}$") String badgeNo) { }

    public record ExpectedView(UUID id, String visitorName, String organisation, String purpose, LocalDate expectedOn, String host, String status) { }

    @PostMapping("/campuses/{campusId}/visits")
    @ResponseStatus(HttpStatus.CREATED)
    public VisitView checkIn(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody VisitCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.VISITOR_RECORD, campusId);
            if (req.hostPersonId() != null && jdbc.queryForObject(
                "select count(*) from staff_profile where person_id = ? and campus_id = ? and status = 'ACTIVE'", Long.class, req.hostPersonId(), campusId) == 0) {
                throw ApiException.badRequest("unknown_host", "Choose a member of this campus's staff to visit.");
            }
            try {
                UUID id = jdbc.queryForObject("""
                    insert into visitor_visit (tenant_id, campus_id, visitor_name, organisation, phone, purpose, host_person_id, badge_no, checked_in_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), campusId, req.visitorName().trim(), blank(req.organisation()), blank(req.phone()),
                    req.purpose().trim(), req.hostPersonId(), blank(req.badgeNo()), ctx.actor().personId());
                audit.write(jdbc, ctx, "visitor.checked_in", "visitor_visit", id.toString(), "SUCCESS", null, Map.of());
                if (req.hostPersonId() != null) {
                    jdbc.queryForObject("select notice_tell_staff('VISITOR', ?, ?, ?, ?::uuid[], null)", Integer.class, campusId,
                        "Visitor here to see you: " + req.visitorName().trim(),
                        req.visitorName().trim() + " has arrived at the gate. Purpose: " + req.purpose().trim() + ".", "{" + req.hostPersonId() + "}");
                }
                return visits(jdbc, "v.id = ?", id).get(0);
            } catch (DataIntegrityViolationException e) {
                if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("visitor_badge_in_use")) {
                    throw new ApiException(HttpStatus.CONFLICT, "badge_in_use", "That badge is already on someone inside.");
                }
                throw e;
            }
        });
    }

    /** view=inside (default) lists who is on campus now; view=today lists every visit since midnight, India time. */
    @GetMapping("/campuses/{campusId}/visits")
    public List<VisitView> list(RequestContext ctx, @PathVariable UUID campusId, @RequestParam(defaultValue = "inside") String view) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.VISITOR_READ, campusId);
            return switch (view) {
                case "inside" -> visits(jdbc, "v.campus_id = ? and v.checked_out_at is null", campusId);
                case "today" -> visits(jdbc, "v.campus_id = ? and v.checked_in_at >= ?", campusId, LocalDate.now(IST).atStartOfDay(IST).toOffsetDateTime());
                default -> throw ApiException.badRequest("invalid_view", "View must be inside or today.");
            };
        });
    }

    public record HostView(UUID personId, String name, String designation) { }

    /** Who a visitor can be here to see. The front desk needs the names but not the rest of the staff records. */
    @GetMapping("/campuses/{campusId}/visit-hosts")
    public List<HostView> hosts(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.VISITOR_RECORD, campusId);
            return jdbc.query("""
                select s.person_id, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), s.designation
                  from staff_profile s join person p on p.tenant_id = s.tenant_id and p.id = s.person_id
                 where s.campus_id = ? and s.status = 'ACTIVE' order by 2 limit 500
                """, (rs, i) -> new HostView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), campusId);
        });
    }

    @PostMapping("/visits/{id}/checkout")
    public VisitView checkOut(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID campusId = jdbc.queryForList("select campus_id from visitor_visit where id = ?", UUID.class, id).stream().findFirst().orElseThrow(ApiException::notFound);
            require(jdbc, ctx, Permission.VISITOR_RECORD, campusId);
            if (jdbc.update("update visitor_visit set checked_out_at = clock_timestamp(), checked_out_by = ? where id = ? and checked_out_at is null",
                ctx.actor().personId(), id) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_out", "This visitor has already checked out.");
            }
            audit.write(jdbc, ctx, "visitor.checked_out", "visitor_visit", id.toString(), "SUCCESS", null, Map.of());
            return visits(jdbc, "v.id = ?", id).get(0);
        });
    }

    // ---- pre-registered visitors -----------------------------------------------------------------------------

    /** A staff member announces a guest for today or up to 30 days ahead. The host is always the person announcing. */
    @PostMapping("/campuses/{campusId}/expected-visitors")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpectedView announce(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody ExpectedCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.VISITOR_EXPECT, campusId);
            LocalDate today = LocalDate.now(IST);
            if (req.expectedOn().isBefore(today) || req.expectedOn().isAfter(today.plusDays(30))) {
                throw ApiException.badRequest("invalid_date", "Choose today or a day within the next 30 days.");
            }
            if (jdbc.queryForObject("select count(*) from staff_profile where person_id = ? and campus_id = ? and status = 'ACTIVE'", Long.class,
                ctx.actor().personId(), campusId) == 0) {
                throw ApiException.badRequest("no_profile", "Only staff of this campus can announce a visitor.");
            }
            UUID id = jdbc.queryForObject("""
                insert into expected_visitor (tenant_id, campus_id, host_person_id, visitor_name, organisation, purpose, expected_on) values (?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), campusId, ctx.actor().personId(), req.visitorName().trim(), blank(req.organisation()), req.purpose().trim(), req.expectedOn());
            audit.write(jdbc, ctx, "visitor.announced", "expected_visitor", id.toString(), "SUCCESS", null, Map.of());
            return expected(jdbc, "e.id = ?", id).get(0);
        });
    }

    /** view=today (the gate: everyone still expected today) or view=mine (the caller's own announcements from today on). */
    @GetMapping("/campuses/{campusId}/expected-visitors")
    public List<ExpectedView> expectedList(RequestContext ctx, @PathVariable UUID campusId, @RequestParam(defaultValue = "mine") String view) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            LocalDate today = LocalDate.now(IST);
            return switch (view) {
                case "today" -> {
                    require(jdbc, ctx, Permission.VISITOR_RECORD, campusId);
                    yield expected(jdbc, "e.campus_id = ? and e.expected_on = ? and e.status = 'EXPECTED'", campusId, today);
                }
                case "mine" -> {
                    require(jdbc, ctx, Permission.VISITOR_EXPECT, campusId);
                    yield expected(jdbc, "e.campus_id = ? and e.host_person_id = ? and e.expected_on >= ? and e.status = 'EXPECTED'", campusId, ctx.actor().personId(), today);
                }
                default -> throw ApiException.badRequest("invalid_view", "View must be today or mine.");
            };
        });
    }

    @PostMapping("/expected-visitors/{id}/cancel")
    public ExpectedView cancelExpected(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID host = jdbc.queryForList("select host_person_id from expected_visitor where id = ?", UUID.class, id).stream().findFirst().orElseThrow(ApiException::notFound);
            if (!host.equals(ctx.actor().personId())) {
                throw ApiException.notFound();
            }
            if (jdbc.update("update expected_visitor set status = 'CANCELLED' where id = ? and status = 'EXPECTED'", id) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_closed", "This announcement is already closed.");
            }
            audit.write(jdbc, ctx, "visitor.announcement_cancelled", "expected_visitor", id.toString(), "SUCCESS", null, Map.of());
            return expected(jdbc, "e.id = ?", id).get(0);
        });
    }

    /** The gate checks an announced guest in: the visit is created from the announcement and the host is told. */
    @PostMapping("/expected-visitors/{id}/arrive")
    public VisitView arrive(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody(required = false) Arrival req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            var row = jdbc.queryForList("select campus_id, host_person_id, visitor_name, organisation, purpose, status from expected_visitor where id = ?", id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            UUID campusId = (UUID) row.get("campus_id");
            require(jdbc, ctx, Permission.VISITOR_RECORD, campusId);
            if (!"EXPECTED".equals(row.get("status"))) {
                throw new ApiException(HttpStatus.CONFLICT, "already_closed", "This announcement is already closed.");
            }
            if (!LocalDate.now(IST).equals(jdbc.queryForObject("select expected_on from expected_visitor where id = ?", LocalDate.class, id))) {
                throw new ApiException(HttpStatus.CONFLICT, "not_today", "This guest is expected on another day.");
            }
            UUID host = (UUID) row.get("host_person_id");
            String name = (String) row.get("visitor_name");
            String purpose = (String) row.get("purpose");
            try {
                UUID visit = jdbc.queryForObject("""
                    insert into visitor_visit (tenant_id, campus_id, visitor_name, organisation, phone, purpose, host_person_id, badge_no, checked_in_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), campusId, name, row.get("organisation"), req == null ? null : blank(req.phone()), purpose, host,
                    req == null ? null : blank(req.badgeNo()), ctx.actor().personId());
                jdbc.update("update expected_visitor set status = 'ARRIVED', visit_id = ? where id = ?", visit, id);
                audit.write(jdbc, ctx, "visitor.checked_in", "visitor_visit", visit.toString(), "SUCCESS", null, Map.of("announced", true));
                jdbc.queryForObject("select notice_tell_staff('VISITOR', ?, ?, ?, ?::uuid[], null)", Integer.class, campusId,
                    "Visitor here to see you: " + name, name + " has arrived at the gate. Purpose: " + purpose + ".", "{" + host + "}");
                return visits(jdbc, "v.id = ?", visit).get(0);
            } catch (DataIntegrityViolationException e) {
                if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("visitor_badge_in_use")) {
                    throw new ApiException(HttpStatus.CONFLICT, "badge_in_use", "That badge is already on someone inside.");
                }
                throw e;
            }
        });
    }

    private List<ExpectedView> expected(JdbcTemplate jdbc, String where, Object... args) {
        return jdbc.query("""
            select e.id, e.visitor_name, e.organisation, e.purpose, e.expected_on, trim(coalesce(h.given_name, '') || ' ' || coalesce(h.family_name, '')), e.status
              from expected_visitor e join person h on h.tenant_id = e.tenant_id and h.id = e.host_person_id
             where """ + " " + where + " order by e.expected_on, e.created_at limit 300",
            (rs, i) -> new ExpectedView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class),
                rs.getString(6), rs.getString(7)), args);
    }

    private void require(JdbcTemplate jdbc, RequestContext ctx, Permission permission, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, permission, Resource.ordinary("visitor", null, access.campusPath(jdbc, ctx, campusId), null));
    }

    private List<VisitView> visits(JdbcTemplate jdbc, String where, Object... args) {
        return jdbc.query("""
            select v.id, v.visitor_name, v.organisation, v.phone, v.purpose, trim(coalesce(h.given_name, '') || ' ' || coalesce(h.family_name, '')),
                   v.badge_no, v.checked_in_at, trim(coalesce(b.given_name, '') || ' ' || coalesce(b.family_name, '')), v.checked_out_at
              from visitor_visit v
              left join person h on h.tenant_id = v.tenant_id and h.id = v.host_person_id
              left join person b on b.tenant_id = v.tenant_id and b.id = v.checked_in_by
             where """ + " " + where + " order by v.checked_in_at desc limit 300",
            (rs, i) -> new VisitView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6) == null || rs.getString(6).isBlank() ? null : rs.getString(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class),
                rs.getString(9), rs.getObject(10, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class) == null), args);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
