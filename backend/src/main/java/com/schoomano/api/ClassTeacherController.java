package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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
 * B62 class teachers (M06). Reading needs ATTENDANCE_READ for the section; assigning and ending need
 * ACADEMIC_STRUCTURE_MANAGE for the section's campus. Ending keeps the record, with who and why. Audited.
 */
@RestController
@RequestMapping("/api/v1")
public class ClassTeacherController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;

    public ClassTeacherController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
    }

    public record Assign(@NotNull UUID teacherPersonId) { }

    public record End(@NotBlank @Size(min = 10, max = 300) String reason) { }

    public record ClassTeacherView(UUID id, UUID teacherPersonId, String teacherName, OffsetDateTime assignedAt) { }

    @GetMapping("/sections/{sectionId}/class-teachers")
    public List<ClassTeacherView> list(RequestContext ctx, @PathVariable UUID sectionId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = sectionPath(jdbc, ctx, sectionId);
            authz.requireOrNotFound(jdbc, ctx, Permission.ATTENDANCE_READ, Resource.ordinary("class_teacher", sectionId, path, null));
            return load(jdbc, sectionId);
        });
    }

    @PostMapping("/sections/{sectionId}/class-teachers")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ClassTeacherView> assign(RequestContext ctx, @PathVariable UUID sectionId, @Valid @RequestBody Assign req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = sectionPath(jdbc, ctx, sectionId);
            authz.requireOrNotFound(jdbc, ctx, Permission.ATTENDANCE_READ, Resource.ordinary("class_teacher", sectionId, path, null));
            authz.requireOrForbidden(jdbc, ctx, Permission.ACADEMIC_STRUCTURE_MANAGE, Resource.ordinary("class_teacher", sectionId, path, null));
            Boolean isTeacher = jdbc.queryForObject("""
                select exists (select 1 from role_grant g where g.person_id = ? and g.role_key = 'teacher'
                                 and g.valid_from <= now() and (g.valid_to is null or g.valid_to > now()))
                """, Boolean.class, req.teacherPersonId());
            if (!Boolean.TRUE.equals(isTeacher)) {
                throw ApiException.badRequest("not_a_teacher", "That person does not hold the teacher role.");
            }
            UUID id;
            try {
                id = jdbc.queryForObject("""
                    insert into class_teacher (tenant_id, class_section_id, teacher_person_id, assigned_by)
                    values (?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), sectionId, req.teacherPersonId(), ctx.actor().personId());
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "already_assigned", "That teacher is already a class teacher of this section.");
            }
            audit.write(jdbc, ctx, "class_teacher.assigned", "class_section", sectionId.toString(), "SUCCESS", null,
                Map.of("teacherPersonId", req.teacherPersonId().toString(), "assignmentId", id.toString()));
            return load(jdbc, sectionId);
        });
    }

    @PostMapping("/sections/{sectionId}/class-teachers/{assignmentId}/end")
    public List<ClassTeacherView> end(RequestContext ctx, @PathVariable UUID sectionId, @PathVariable UUID assignmentId, @Valid @RequestBody End req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = sectionPath(jdbc, ctx, sectionId);
            authz.requireOrNotFound(jdbc, ctx, Permission.ATTENDANCE_READ, Resource.ordinary("class_teacher", sectionId, path, null));
            authz.requireOrForbidden(jdbc, ctx, Permission.ACADEMIC_STRUCTURE_MANAGE, Resource.ordinary("class_teacher", sectionId, path, null));
            int n = jdbc.update("""
                update class_teacher set ended_at = now(), ended_by = ?, end_reason = ?
                 where id = ? and class_section_id = ? and ended_at is null
                """, ctx.actor().personId(), req.reason().trim(), assignmentId, sectionId);
            if (n == 0) {
                throw ApiException.notFound();
            }
            audit.write(jdbc, ctx, "class_teacher.ended", "class_section", sectionId.toString(), "SUCCESS", req.reason().trim(),
                Map.of("assignmentId", assignmentId.toString()));
            return load(jdbc, sectionId);
        });
    }

    private List<ClassTeacherView> load(JdbcTemplate jdbc, UUID sectionId) {
        return jdbc.query("""
            select c.id, c.teacher_person_id, trim(p.given_name || ' ' || coalesce(p.family_name, '')), c.assigned_at
              from class_teacher c join person p on p.id = c.teacher_person_id
             where c.class_section_id = ? and c.ended_at is null
             order by c.assigned_at, c.id
            """, (rs, i) -> new ClassTeacherView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class)), sectionId);
    }

    private ScopePath sectionPath(JdbcTemplate jdbc, RequestContext ctx, UUID sectionId) {
        UUID yearId = jdbc.queryForList("select academic_year_id from class_section where id = ?", UUID.class, sectionId)
            .stream().findFirst().orElseThrow(ApiException::notFound);
        return access.campusPath(jdbc, ctx, access.loadYear(jdbc, ctx, yearId).campusId());
    }
}
