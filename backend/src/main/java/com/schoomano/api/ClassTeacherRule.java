package com.schoomano.api;

import com.schoomano.platform.ApiException;
import com.schoomano.platform.RequestContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who may mark a section: where a section has a class teacher, a person who is only a teacher may mark it only if they are
 * one of its class teachers. Administrators and principals are never held back, and a section with no class teacher keeps
 * working as before, so nothing stops working the day this ships.
 */
@Component
public class ClassTeacherRule {

    public void requireMayMark(JdbcTemplate jdbc, RequestContext ctx, UUID sectionId) {
        UUID me = ctx.actor().personId();
        Boolean blocked = jdbc.queryForObject("""
            select exists (select 1 from class_teacher c where c.class_section_id = ? and c.ended_at is null)
               and not exists (select 1 from class_teacher c where c.class_section_id = ? and c.ended_at is null and c.teacher_person_id = ?)
               and not exists (select 1 from role_grant g where g.person_id = ?
                                and g.role_key in ('group_admin', 'campus_admin', 'principal')
                                and g.valid_from <= now() and (g.valid_to is null or g.valid_to > now()))
            """, Boolean.class, sectionId, sectionId, me, me);
        if (Boolean.TRUE.equals(blocked)) {
            throw ApiException.forbidden("Only this class's teacher can mark it. Ask the office to add you as a class teacher.");
        }
    }
}
