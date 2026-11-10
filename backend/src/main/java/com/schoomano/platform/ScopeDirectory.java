package com.schoomano.platform;

import com.schoomano.domain.tenancy.ScopeLevel;
import com.schoomano.domain.tenancy.ScopeNode;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.domain.tenancy.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Builds scope paths from persisted hierarchy. Must be called inside {@link TenantTx#run}. */
@Component
public class ScopeDirectory {

    public Optional<ScopePath> campusPath(JdbcTemplate jdbc, TenantId tenant, UUID campusId) {
        List<UUID> entity = jdbc.queryForList("select legal_entity_id from campus where id = ?", UUID.class, campusId);
        return entity.stream().findFirst().map(le -> ScopePath.group(tenant)
            .child(new ScopeNode(ScopeLevel.LEGAL_ENTITY, le))
            .child(new ScopeNode(ScopeLevel.CAMPUS, campusId)));
    }

    /** GROUP / LEGAL_ENTITY / CAMPUS / PROGRAMME. */
    public Optional<ScopePath> programmePath(JdbcTemplate jdbc, TenantId tenant, UUID programmeId) {
        List<UUID> campus = jdbc.queryForList("select campus_id from programme where id = ?", UUID.class, programmeId);
        return campus.stream().findFirst().flatMap(c -> campusPath(jdbc, tenant, c))
            .map(p -> p.child(new ScopeNode(ScopeLevel.PROGRAMME, programmeId)));
    }

    /** A student's scope is the campus of their most recent enrollment. */
    public Optional<ScopePath> studentPath(JdbcTemplate jdbc, TenantId tenant, UUID studentId) {
        List<UUID> campus = jdbc.queryForList("""
            select e.campus_id from student_enrollment e join academic_year y on y.id = e.academic_year_id
             where e.student_person_id = ? order by y.starts_on desc limit 1
            """, UUID.class, studentId);
        return campus.stream().findFirst().flatMap(c -> campusPath(jdbc, tenant, c));
    }

    public ScopePath forNode(JdbcTemplate jdbc, TenantId tenant, ScopeNode node) {
        return switch (node.level()) {
            case GROUP -> {
                if (!node.id().equals(tenant.value())) {
                    throw ApiException.notFound();
                }
                yield ScopePath.group(tenant);
            }
            case CAMPUS -> campusPath(jdbc, tenant, node.id()).orElseThrow(ApiException::notFound);
            case PROGRAMME -> programmePath(jdbc, tenant, node.id()).orElseThrow(ApiException::notFound);
            default -> throw ApiException.badRequest("unsupported_scope",
                "Only GROUP, CAMPUS and PROGRAMME scopes are supported in this release.");
        };
    }
}
