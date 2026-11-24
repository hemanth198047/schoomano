package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.notice.SmsText;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * B7 notices (M13). Staff with NOTICE_SEND write announcements to one class, or (office roles only) a whole campus.
 * The database decides who is reached (see V10). Anyone signed in reads their own inbox; a person can only see and
 * mark their own deliveries.
 */
@RestController
@RequestMapping("/api/v1")
public class NoticeController {

    private static final Set<String> OFFICE_ROLES = Set.of("group_admin", "campus_admin", "principal");

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;

    public NoticeController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
    }

    public record NoticeRequest(@NotBlank @Size(min = 3, max = 120) String title, @NotBlank @Size(max = 2000) String body,
                                UUID sectionId) { }

    public record SentNotice(UUID id, String title, String audience, OffsetDateTime createdAt, int people, int smsSent,
                             int smsWaiting, int smsFailed, int smsSkipped) { }

    public record InboxItem(UUID id, String kind, String title, String body, OffsetDateTime receivedAt, boolean read) { }

    @PostMapping("/campuses/{campusId}/notices")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> send(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody NoticeRequest req) {
        String sms = SmsText.forAnnouncement(req.title());
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = access.campusPath(jdbc, ctx, campusId);
            authz.requireOrNotFound(jdbc, ctx, Permission.CAMPUS_READ, Resource.ordinary("notice", campusId, path, null));
            authz.requireOrForbidden(jdbc, ctx, Permission.NOTICE_SEND, Resource.ordinary("notice", campusId, path, null));
            if (req.sectionId() == null) {
                boolean office = ctx.actor().grants().stream().anyMatch(g -> OFFICE_ROLES.contains(g.roleKey()));
                if (!office) {
                    throw ApiException.forbidden("Choose a class. Only office staff may write to a whole campus.");
                }
            } else {
                Integer match = jdbc.queryForObject("""
                    select count(*) from class_section s join academic_year y on y.id = s.academic_year_id
                     where s.id = ? and y.campus_id = ?
                    """, Integer.class, req.sectionId(), campusId);
                if (match == null || match == 0) {
                    throw ApiException.notFound();
                }
            }
            UUID id = jdbc.queryForObject("""
                insert into notice (tenant_id, campus_id, class_section_id, kind, title, body, sms_text, created_by)
                values (?, ?, ?, 'ANNOUNCEMENT', ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), campusId, req.sectionId(), req.title().trim(), req.body().trim(), sms,
                ctx.actor().personId());
            Integer people = jdbc.queryForObject("select notice_publish(?)", Integer.class, id);
            audit.write(jdbc, ctx, "notice.sent", "notice", id.toString(), "SUCCESS", null,
                Map.of("campusId", campusId.toString(), "sectionId", String.valueOf(req.sectionId()), "people", people));
            return Map.<String, Object>of("id", id, "people", people);
        });
    }

    @GetMapping("/campuses/{campusId}/notices")
    public List<SentNotice> sent(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = access.campusPath(jdbc, ctx, campusId);
            authz.requireOrNotFound(jdbc, ctx, Permission.CAMPUS_READ, Resource.ordinary("notice", campusId, path, null));
            authz.requireOrForbidden(jdbc, ctx, Permission.NOTICE_SEND, Resource.ordinary("notice", campusId, path, null));
            return jdbc.query("""
                select n.id, n.title, coalesce(g.name || ' ' || s.code, 'Whole campus'), n.created_at,
                       count(*) filter (where d.channel = 'IN_APP'),
                       count(*) filter (where d.channel = 'SMS' and d.status = 'SENT'),
                       count(*) filter (where d.channel = 'SMS' and d.status = 'PENDING'),
                       count(*) filter (where d.channel = 'SMS' and d.status = 'FAILED'),
                       count(*) filter (where d.channel = 'SMS' and d.status = 'SKIPPED')
                  from notice n
                  left join class_section s on s.id = n.class_section_id
                  left join grade_level g on g.id = s.grade_level_id
                  left join notice_delivery d on d.notice_id = n.id
                 where n.campus_id = ? and n.kind = 'ANNOUNCEMENT'
                 group by n.id, g.name, s.code
                 order by n.created_at desc limit 50
                """, (rs, i) -> new SentNotice(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getObject(4, OffsetDateTime.class), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9)),
                campusId);
        });
    }

    /** The signed-in person's own notices, newest first. */
    @GetMapping("/notices/mine")
    public List<InboxItem> inbox(RequestContext ctx) {
        return tenantTx.run(ctx.tenant(), jdbc -> jdbc.query("""
            select d.id, n.kind, n.title, n.body, d.created_at, d.read_at is not null
              from notice_delivery d join notice n on n.id = d.notice_id
             where d.recipient_person_id = ? and d.channel = 'IN_APP'
             order by d.created_at desc limit 50
            """, (rs, i) -> new InboxItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, OffsetDateTime.class), rs.getBoolean(6)), ctx.actor().personId()));
    }

    @PostMapping("/notices/{deliveryId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(RequestContext ctx, @PathVariable UUID deliveryId) {
        tenantTx.run(ctx.tenant(), jdbc -> {
            int n = jdbc.update("update notice_delivery set read_at = coalesce(read_at, now()) where id = ? and recipient_person_id = ? and channel = 'IN_APP'",
                deliveryId, ctx.actor().personId());
            if (n == 0) {
                throw ApiException.notFound();
            }
            return null;
        });
    }
}
