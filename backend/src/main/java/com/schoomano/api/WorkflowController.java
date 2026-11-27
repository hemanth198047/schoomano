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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * M26 workflow. Campus leaders design approval chains (ordered steps, each naming the role that must approve), publish
 * them as fixed versions, and staff submit requests that walk the chain one step at a time. Decisions are permanent, a
 * requester never decides their own request, and the database checks that the decider holds the step's role.
 */
@RestController
@RequestMapping("/api/v1")
public class WorkflowController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;

    public WorkflowController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
    }

    public record StepInput(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{2,40}$") String roleKey, @NotBlank @Size(min = 2, max = 80) String label) { }

    public record ChainCreate(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{2,40}$") String chainKey, @NotBlank @Size(min = 3, max = 120) String name,
                              @NotEmpty @Size(max = 8) List<@Valid StepInput> steps) { }

    public record StepView(int position, String roleKey, String label, String decision, String decidedBy, String note, OffsetDateTime decidedAt) { }

    public record ChainView(UUID id, String chainKey, int version, String name, String status, List<StepView> steps) { }

    public record RequestCreate(@NotNull UUID chainId, @NotBlank @Size(min = 3, max = 160) String subject, @Size(max = 2000) String details) { }

    public record DecisionCreate(@NotNull @Pattern(regexp = "APPROVED|REJECTED") String decision, @Size(max = 1000) String note) { }

    public record RequestView(UUID id, String chainName, int chainVersion, String subject, String details, String status, int currentStep,
                              String waitingFor, String requester, boolean mine, OffsetDateTime submittedAt, OffsetDateTime closedAt, List<StepView> steps) { }

    // ---- chains ----------------------------------------------------------------------------------------------

    @PostMapping("/campuses/{campusId}/workflow-chains")
    @ResponseStatus(HttpStatus.CREATED)
    public ChainView draft(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody ChainCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.WORKFLOW_DESIGN, campusId);
            Integer last = jdbc.queryForObject("select max(version) from workflow_definition where campus_id = ? and chain_key = ?", Integer.class, campusId, req.chainKey());
            UUID id;
            try {
                id = jdbc.queryForObject("""
                    insert into workflow_definition (tenant_id, campus_id, chain_key, version, name, created_by) values (?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), campusId, req.chainKey(), last == null ? 1 : last + 1, req.name().trim(), ctx.actor().personId());
                int pos = 1;
                for (StepInput s : req.steps()) {
                    jdbc.update("insert into workflow_step (tenant_id, definition_id, position, role_key, label) values (?, ?, ?, ?, ?)",
                        ctx.tenant().value(), id, pos++, s.roleKey(), s.label().trim());
                }
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "chain_conflict", "Someone just drafted a version of this chain. Try again.");
            }
            audit.write(jdbc, ctx, "workflow.chain_drafted", "workflow_definition", id.toString(), "SUCCESS", null, Map.of("chainKey", req.chainKey()));
            return chain(jdbc, id);
        });
    }

    @GetMapping("/campuses/{campusId}/workflow-chains")
    public List<ChainView> chains(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            boolean reader = allowed(jdbc, ctx, Permission.WORKFLOW_READ, campusId);
            if (!reader) {
                require(jdbc, ctx, Permission.WORKFLOW_SUBMIT, campusId);
            }
            List<UUID> ids = jdbc.queryForList("select id from workflow_definition where campus_id = ? " + (reader ? "" : "and status = 'PUBLISHED' ")
                + "order by chain_key, version desc", UUID.class, campusId);
            List<ChainView> out = new ArrayList<>();
            for (UUID id : ids) {
                out.add(chain(jdbc, id));
            }
            return out;
        });
    }

    @PostMapping("/workflow-chains/{id}/publish")
    public ChainView publish(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> row = jdbc.queryForList("select campus_id, chain_key, status from workflow_definition where id = ?", id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            require(jdbc, ctx, Permission.WORKFLOW_DESIGN, (UUID) row.get("campus_id"));
            if (!"DRAFT".equals(row.get("status"))) {
                throw new ApiException(HttpStatus.CONFLICT, "not_a_draft", "Only a draft can be published.");
            }
            try {
                jdbc.update("update workflow_definition set status = 'RETIRED' where campus_id = ? and chain_key = ? and status = 'PUBLISHED'",
                    row.get("campus_id"), row.get("chain_key"));
                jdbc.update("update workflow_definition set status = 'PUBLISHED', published_at = clock_timestamp() where id = ?", id);
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("no_steps", "A chain needs at least one step before it is published.");
            }
            audit.write(jdbc, ctx, "workflow.chain_published", "workflow_definition", id.toString(), "SUCCESS", null, Map.of());
            return chain(jdbc, id);
        });
    }

    // ---- requests --------------------------------------------------------------------------------------------

    @PostMapping("/campuses/{campusId}/workflow-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView submit(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody RequestCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.WORKFLOW_SUBMIT, campusId);
            try {
                UUID id = jdbc.queryForObject("""
                    insert into workflow_request (tenant_id, campus_id, definition_id, requester_id, subject, details) values (?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), campusId, req.chainId(), ctx.actor().personId(), req.subject().trim(),
                    req.details() == null || req.details().isBlank() ? null : req.details().trim());
                audit.write(jdbc, ctx, "workflow.request_submitted", "workflow_request", id.toString(), "SUCCESS", null, Map.of());
                return request(jdbc, ctx, id);
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("unknown_chain", "Choose a published approval chain of this campus.");
            }
        });
    }

    @GetMapping("/campuses/{campusId}/workflow-requests")
    public List<RequestView> requests(RequestContext ctx, @PathVariable UUID campusId, @RequestParam(defaultValue = "mine") String view) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID me = ctx.actor().personId();
            List<UUID> ids;
            switch (view) {
                case "mine" -> {
                    require(jdbc, ctx, Permission.WORKFLOW_SUBMIT, campusId);
                    ids = jdbc.queryForList("select id from workflow_request where campus_id = ? and requester_id = ? order by submitted_at desc limit 100", UUID.class, campusId, me);
                }
                case "inbox" -> {
                    require(jdbc, ctx, Permission.WORKFLOW_DECIDE, campusId);
                    ids = jdbc.queryForList("""
                        select r.id from workflow_request r
                          join workflow_step s on s.tenant_id = r.tenant_id and s.definition_id = r.definition_id and s.position = r.current_step
                         where r.campus_id = ? and r.status = 'PENDING' and r.requester_id <> ?
                           and exists (select 1 from role_grant g where g.person_id = ? and g.role_key = s.role_key and (g.valid_to is null or g.valid_to > now()))
                         order by r.submitted_at limit 100
                        """, UUID.class, campusId, me, me);
                }
                case "all" -> {
                    require(jdbc, ctx, Permission.WORKFLOW_READ, campusId);
                    ids = jdbc.queryForList("select id from workflow_request where campus_id = ? order by submitted_at desc limit 200", UUID.class, campusId);
                }
                default -> throw ApiException.badRequest("invalid_view", "View must be mine, inbox or all.");
            }
            List<RequestView> out = new ArrayList<>();
            for (UUID id : ids) {
                out.add(request(jdbc, ctx, id));
            }
            return out;
        });
    }

    @PostMapping("/workflow-requests/{id}/decision")
    public RequestView decide(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody DecisionCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> r = jdbc.queryForList("select campus_id, requester_id, status, current_step, definition_id from workflow_request where id = ?", id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            require(jdbc, ctx, Permission.WORKFLOW_DECIDE, (UUID) r.get("campus_id"));
            if (ctx.actor().personId().equals(r.get("requester_id"))) {
                throw ApiException.forbidden("You cannot decide your own request.");
            }
            if (!"PENDING".equals(r.get("status"))) {
                throw new ApiException(HttpStatus.CONFLICT, "request_closed", "This request is already closed.");
            }
            if ("REJECTED".equals(req.decision()) && (req.note() == null || req.note().isBlank())) {
                throw ApiException.badRequest("note_required", "Say why the request is rejected.");
            }
            int step = (Integer) r.get("current_step");
            int steps = jdbc.queryForObject("select count(*) from workflow_step where definition_id = ?", Integer.class, r.get("definition_id"));
            try {
                jdbc.update("insert into workflow_decision (tenant_id, request_id, position, decider_id, decision, note) values (?, ?, ?, ?, ?, ?)",
                    ctx.tenant().value(), id, step, ctx.actor().personId(), req.decision(), req.note() == null || req.note().isBlank() ? null : req.note().trim());
            } catch (DataIntegrityViolationException e) {
                // Not the right role for this step: indistinguishable from not seeing the request at all.
                throw ApiException.notFound();
            }
            if ("REJECTED".equals(req.decision())) {
                jdbc.update("update workflow_request set status = 'REJECTED', closed_at = clock_timestamp() where id = ?", id);
            } else if (step == steps) {
                jdbc.update("update workflow_request set status = 'APPROVED', closed_at = clock_timestamp() where id = ?", id);
            } else {
                jdbc.update("update workflow_request set current_step = ? where id = ?", step + 1, id);
            }
            audit.write(jdbc, ctx, "workflow.request_" + req.decision().toLowerCase(), "workflow_request", id.toString(), "SUCCESS", null, Map.of("step", step));
            return request(jdbc, ctx, id);
        });
    }

    @PostMapping("/workflow-requests/{id}/withdraw")
    public RequestView withdraw(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> r = jdbc.queryForList("select campus_id, requester_id from workflow_request where id = ?", id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            if (!ctx.actor().personId().equals(r.get("requester_id"))) {
                throw ApiException.notFound();
            }
            if (jdbc.update("update workflow_request set status = 'WITHDRAWN', closed_at = clock_timestamp() where id = ? and status = 'PENDING'", id) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "request_closed", "This request is already closed.");
            }
            audit.write(jdbc, ctx, "workflow.request_withdrawn", "workflow_request", id.toString(), "SUCCESS", null, Map.of());
            return request(jdbc, ctx, id);
        });
    }

    @GetMapping("/workflow-requests/{id}")
    public RequestView one(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> r = jdbc.queryForList("select campus_id, requester_id, definition_id from workflow_request where id = ?", id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            UUID campusId = (UUID) r.get("campus_id");
            boolean mine = ctx.actor().personId().equals(r.get("requester_id"));
            boolean holdsRole = jdbc.queryForObject("""
                select count(*) from workflow_step s join role_grant g on g.role_key = s.role_key and g.person_id = ? and (g.valid_to is null or g.valid_to > now())
                 where s.definition_id = ?
                """, Long.class, ctx.actor().personId(), r.get("definition_id")) > 0;
            if (!mine && !(holdsRole && allowed(jdbc, ctx, Permission.WORKFLOW_DECIDE, campusId)) && !allowed(jdbc, ctx, Permission.WORKFLOW_READ, campusId)) {
                throw ApiException.notFound();
            }
            return request(jdbc, ctx, id);
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private boolean allowed(JdbcTemplate jdbc, RequestContext ctx, Permission permission, UUID campusId) {
        return authz.check(jdbc, ctx, permission, Resource.ordinary("workflow", null, access.campusPath(jdbc, ctx, campusId), null)).allowed();
    }

    private void require(JdbcTemplate jdbc, RequestContext ctx, Permission permission, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, permission, Resource.ordinary("workflow", null, access.campusPath(jdbc, ctx, campusId), null));
    }

    private ChainView chain(JdbcTemplate jdbc, UUID id) {
        List<StepView> steps = jdbc.query("select position, role_key, label from workflow_step where definition_id = ? order by position",
            (rs, i) -> new StepView(rs.getInt(1), rs.getString(2), rs.getString(3), null, null, null, null), id);
        return jdbc.query("select id, chain_key, version, name, status from workflow_definition where id = ?",
            (rs, i) -> new ChainView(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), steps), id)
            .stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private RequestView request(JdbcTemplate jdbc, RequestContext ctx, UUID id) {
        List<StepView> steps = jdbc.query("""
            select s.position, s.role_key, s.label, d.decision, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), d.note, d.decided_at
              from workflow_request r join workflow_step s on s.tenant_id = r.tenant_id and s.definition_id = r.definition_id
                   left join workflow_decision d on d.tenant_id = r.tenant_id and d.request_id = r.id and d.position = s.position
                   left join person p on p.tenant_id = d.tenant_id and p.id = d.decider_id
             where r.id = ? order by s.position
            """, (rs, i) -> new StepView(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class)), id);
        return jdbc.query("""
            select r.id, d.name, d.version, r.subject, r.details, r.status, r.current_step,
                   trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), r.requester_id, r.submitted_at, r.closed_at
              from workflow_request r join workflow_definition d on d.tenant_id = r.tenant_id and d.id = r.definition_id
                   join person p on p.tenant_id = r.tenant_id and p.id = r.requester_id
             where r.id = ?
            """, (rs, i) -> {
            String status = rs.getString(6);
            int current = rs.getInt(7);
            String waiting = "PENDING".equals(status) ? steps.stream().filter(s -> s.position() == current).map(StepView::label).findFirst().orElse(null) : null;
            return new RequestView(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), status, current, waiting,
                rs.getString(8), ctx.actor().personId().equals(rs.getObject(9, UUID.class)), rs.getObject(10, OffsetDateTime.class),
                rs.getObject(11, OffsetDateTime.class), steps);
        }, id).stream().findFirst().orElseThrow(ApiException::notFound);
    }
}
