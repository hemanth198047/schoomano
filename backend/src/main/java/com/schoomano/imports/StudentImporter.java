package com.schoomano.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.domain.imports.StudentImport;
import com.schoomano.domain.imports.StudentImport.Action;
import com.schoomano.domain.imports.StudentImport.Contact;
import com.schoomano.domain.imports.StudentImport.FieldChange;
import com.schoomano.domain.imports.StudentImport.GuardianAdd;
import com.schoomano.domain.imports.StudentImport.GuardianBook;
import com.schoomano.domain.imports.StudentImport.Linked;
import com.schoomano.domain.imports.StudentImport.Placement;
import com.schoomano.domain.imports.StudentImport.RowPlan;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Database side of the repeatable student import. Must be called inside TenantTx.run (RLS applies).
 * Planning lives in {@link StudentImport}; this class loads its inputs and applies or reverts its output.
 */
@Component
public class StudentImporter {

    public static final String SOURCE = "LEGACY";
    public static final String ENTITY = "STUDENT";
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() { };

    private final ObjectMapper json;

    public StudentImporter(ObjectMapper json) {
        this.json = json;
    }

    record SectionRef(UUID sectionId, UUID programmeId) { }

    record Catalog(List<Placement> placements, Map<String, SectionRef> byCanonical) { }

    public StudentImport.Plan plan(JdbcTemplate jdbc, List<List<String>> table, UUID yearId, LocalDate today) {
        return StudentImport.plan(table, catalog(jdbc, yearId).placements(), linked(jdbc, yearId), unlinkedRefs(jdbc), today,
            guardianBook(jdbc));
    }

    Catalog catalog(JdbcTemplate jdbc, UUID yearId) {
        List<Placement> placements = new ArrayList<>();
        Map<String, SectionRef> refs = new HashMap<>();
        jdbc.query("""
            select p.code, g.code, g.name, g.ordinal, s.code, s.id, p.id
              from class_section s join grade_level g on g.id = s.grade_level_id join programme p on p.id = g.programme_id
             where s.academic_year_id = ?
            """, rs -> {
                Placement pl = new Placement(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5));
                placements.add(pl);
                refs.put(pl.programmeCode() + "/" + pl.gradeCode() + "/" + pl.sectionCode(),
                    new SectionRef(rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)));
            }, yearId);
        return new Catalog(placements, refs);
    }

    // Two simple queries joined in memory instead of one multi-way join: right after a large first import,
    // PostgreSQL has no statistics for the new rows and may pick a nested loop that scans enrollments once per
    // link (10 s for 5,000 students in testing). These shapes stay fast whatever the statistics say.
    private static final String LINKS_SQL = """
        select l.external_ref, l.person_id, l.last_values::text, l.last_year_id,
               p.given_name, coalesce(p.family_name, ''), coalesce(p.date_of_birth::text, '')
          from external_link l join person p on p.id = l.person_id
         where l.source = 'LEGACY' and l.entity = 'STUDENT'
        """;

    /** Placement ("PROGRAMME/GRADE/SECTION") of every student placed in a section in this year. */
    private static Map<UUID, String> placements(JdbcTemplate jdbc, UUID yearId, UUID onlyPerson) {
        Map<UUID, String> out = new HashMap<>();
        Map<UUID, String> sectionCodes = new HashMap<>();
        jdbc.query("""
            select s.id, p.code || '/' || g.code || '/' || s.code
              from class_section s join grade_level g on g.id = s.grade_level_id join programme p on p.id = g.programme_id
             where s.academic_year_id = ?
            """, rs -> { sectionCodes.put(rs.getObject(1, UUID.class), rs.getString(2)); }, yearId);
        jdbc.query("""
            select student_person_id, class_section_id from student_enrollment
             where academic_year_id = ? and class_section_id is not null and (?::uuid is null or student_person_id = ?)
            """, rs -> {
                String code = sectionCodes.get(rs.getObject(2, UUID.class));
                if (code != null) {
                    out.put(rs.getObject(1, UUID.class), code);
                }
            }, yearId, onlyPerson, onlyPerson);
        return out;
    }

    /** Linked students with the last imported values and the values held now (placement in this year). */
    Map<String, Linked> linked(JdbcTemplate jdbc, UUID yearId) {
        Map<UUID, String> placed = placements(jdbc, yearId, null);
        Map<String, Linked> out = new HashMap<>();
        jdbc.query(LINKS_SQL, rs -> {
            UUID person = rs.getObject(2, UUID.class);
            Map<String, String> current = values(rs.getString(5), rs.getString(6), rs.getString(7), placed.get(person));
            Map<String, String> last = new HashMap<>(read(rs.getString(3)));
            // Placement history is per year: in a year the old system has not filled yet, treat the
            // current placement as the baseline so the old system's placement applies.
            if (!yearId.equals(rs.getObject(4, UUID.class))) {
                last.put(StudentImport.PLACEMENT, current.get(StudentImport.PLACEMENT));
            }
            out.put(rs.getString(1), new Linked(person, last, current));
        });
        return out;
    }

    Map<String, String> current(JdbcTemplate jdbc, UUID personId, UUID yearId) {
        Map<UUID, String> placed = placements(jdbc, yearId, personId);
        return jdbc.query("""
            select given_name, coalesce(family_name, ''), coalesce(date_of_birth::text, '') from person where id = ?
            """, (rs, i) -> values(rs.getString(1), rs.getString(2), rs.getString(3), placed.get(personId)), personId)
            .stream().findFirst().orElse(Map.of());
    }

    /** Existing contacts by mobile number, and every student and guardian pair already related (any status). */
    GuardianBook guardianBook(JdbcTemplate jdbc) {
        Map<String, Contact> byMobile = new HashMap<>();
        jdbc.query("""
            select mobile_e164, id, trim(coalesce(given_name, '') || ' ' || coalesce(family_name, '')) from person
             where mobile_e164 is not null
            """, rs -> { byMobile.put(rs.getString(1), new Contact(rs.getObject(2, UUID.class), rs.getString(3))); });
        Set<String> pairs = new HashSet<>();
        jdbc.query("select student_person_id, guardian_person_id from guardian_relationship", rs -> {
            pairs.add(GuardianBook.pair(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
        });
        return new GuardianBook(byMobile, pairs);
    }

    /** School references of people not linked to the old system (an import must not collide with them). */
    Set<String> unlinkedRefs(JdbcTemplate jdbc) {
        return new HashSet<>(jdbc.queryForList("""
            select upper(p.school_ref) from person p
             where not exists (select 1 from external_link l where l.person_id = p.id)
            """, String.class));
    }

    /** Stores a validated plan's rows. */
    public void storeRows(JdbcTemplate jdbc, UUID tenant, UUID batchId, List<RowPlan> rows) {
        jdbc.batchUpdate("""
            insert into import_row (tenant_id, batch_id, row_no, external_ref, action, problems, changes, incoming, person_id)
            values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
            """, rows, 1000, (ps, r) -> {
                ps.setObject(1, tenant);
                ps.setObject(2, batchId);
                ps.setInt(3, r.rowNo());
                ps.setString(4, r.externalRef());
                ps.setString(5, r.action().name());
                ps.setString(6, write(r.problems()));
                ps.setString(7, write(r.changes()));
                ps.setString(8, write(r.incoming()));
                ps.setObject(9, r.personId());
            });
    }

    /** What changed at commit: counts by action as applied. */
    public record Applied(int created, int updated, int unchanged, int skipped) { }

    public Applied commit(JdbcTemplate jdbc, UUID tenant, UUID batchId, UUID yearId, UUID campusId, UUID actor,
                          List<RowPlan> rows) {
        Catalog catalog = catalog(jdbc, yearId);
        Map<String, Linked> linked = linked(jdbc, yearId);
        List<Object[]> persons = new ArrayList<>();
        List<Object[]> enrollments = new ArrayList<>();
        List<Object[]> links = new ArrayList<>();
        List<Object[]> applied = new ArrayList<>();
        Map<Integer, UUID> studentOf = new HashMap<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int skipped = 0;
        String provenance = write(Map.of("source", SOURCE, "batchId", batchId.toString(),
            "ownedFields", StudentImport.FIELDS));
        for (RowPlan r : rows) {
            String key = r.externalRef().toUpperCase(java.util.Locale.ROOT);
            Map<String, String> inc = r.incoming();
            SectionRef section = catalog.byCanonical().get(inc.get(StudentImport.PLACEMENT));
            switch (r.action()) {
                case CREATE -> {
                    UUID person = UUID.randomUUID();
                    studentOf.put(r.rowNo(), person);
                    persons.add(new Object[] {person, tenant, r.externalRef(), inc.get(StudentImport.GIVEN_NAME),
                        blankToNull(inc.get(StudentImport.FAMILY_NAME)), date(inc.get(StudentImport.DATE_OF_BIRTH)),
                        provenance, actor});
                    enrollments.add(new Object[] {tenant, person, campusId, yearId,
                        section == null ? null : section.programmeId(), section == null ? null : section.sectionId()});
                    links.add(new Object[] {tenant, key, person, write(inc), yearId, batchId});
                    applied.add(new Object[] {person, null, null, tenant, batchId, r.rowNo()});
                    created++;
                }
                case UPDATE -> {
                    Linked link = linked.get(key);
                    studentOf.put(r.rowNo(), link.personId());
                    for (FieldChange c : r.changes()) {
                        if (c.field().startsWith(StudentImport.GUARDIAN_PREFIX)) {
                            continue;   // guardians are added after the students exist
                        }
                        switch (c.field()) {
                            case StudentImport.GIVEN_NAME -> jdbc.update(
                                "update person set given_name = ?, updated_at = now(), updated_by = ? where id = ?",
                                c.after(), actor, link.personId());
                            case StudentImport.FAMILY_NAME -> jdbc.update(
                                "update person set family_name = ?, updated_at = now(), updated_by = ? where id = ?",
                                blankToNull(c.after()), actor, link.personId());
                            case StudentImport.DATE_OF_BIRTH -> jdbc.update(
                                "update person set date_of_birth = ?, updated_at = now(), updated_by = ? where id = ?",
                                date(c.after()), actor, link.personId());
                            default -> place(jdbc, tenant, link.personId(), campusId, yearId,
                                catalog.byCanonical().get(c.after()));
                        }
                    }
                    jdbc.update("""
                        update external_link set last_values = ?::jsonb, last_year_id = ?, last_batch_id = ?, updated_at = now()
                         where source = 'LEGACY' and entity = 'STUDENT' and external_ref = ?
                        """, write(inc), yearId, batchId, key);
                    applied.add(new Object[] {link.personId(), write(link.current()), write(link.last()), tenant, batchId,
                        r.rowNo()});
                    updated++;
                }
                case UNCHANGED -> {
                    Linked link = linked.get(key);
                    if (!inc.equals(normalised(link.last()))) {
                        jdbc.update("""
                            update external_link set last_values = ?::jsonb, last_year_id = ?, last_batch_id = ?,
                                   updated_at = now()
                             where source = 'LEGACY' and entity = 'STUDENT' and external_ref = ?
                            """, write(inc), yearId, batchId, key);
                        applied.add(new Object[] {link.personId(), null, write(link.last()), tenant, batchId, r.rowNo()});
                    }
                    unchanged++;
                }
                default -> skipped++;
            }
        }
        jdbc.batchUpdate("""
            insert into person (id, tenant_id, school_ref, given_name, family_name, date_of_birth, provenance, created_by)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """, persons);
        jdbc.batchUpdate("""
            insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id, programme_id,
                                            class_section_id)
            values (?, ?, ?, ?, ?, ?)
            """, enrollments);
        jdbc.batchUpdate("""
            insert into external_link (tenant_id, source, entity, external_ref, person_id, last_values, last_year_id,
                                       last_batch_id)
            values (?, 'LEGACY', 'STUDENT', ?, ?, ?::jsonb, ?, ?)
            """, links);
        jdbc.batchUpdate("""
            update import_row set applied = true, person_id = ?, before_values = ?::jsonb, link_before = ?::jsonb
             where tenant_id = ? and batch_id = ? and row_no = ?
            """, applied);
        for (RowPlan r : rows) {
            if (r.guardians().isEmpty() || (r.action() != Action.CREATE && r.action() != Action.UPDATE)) {
                continue;
            }
            Map<String, List<String>> ops = addGuardians(jdbc, tenant, studentOf.get(r.rowNo()), r.guardians(), actor, batchId);
            if (!ops.get("relationships").isEmpty()) {
                jdbc.update("update import_row set guardian_ops = ?::jsonb where tenant_id = ? and batch_id = ? and row_no = ?",
                    write(ops), tenant, batchId, r.rowNo());
            }
        }
        return new Applied(created, updated, unchanged, skipped);
    }

    /**
     * Links the guardians a row names to its student, creating a contact only when no one holds that mobile number. An existing
     * relationship in any status is left exactly as it is. Returns the ids created, for rollback.
     */
    private Map<String, List<String>> addGuardians(JdbcTemplate jdbc, UUID tenant, UUID student, List<GuardianAdd> adds, UUID actor,
                                                   UUID batchId) {
        List<String> people = new ArrayList<>();
        List<String> relationships = new ArrayList<>();
        List<UUID> linked = new ArrayList<>();
        String provenance = write(Map.of("source", SOURCE, "batchId", batchId.toString()));
        for (GuardianAdd a : adds) {
            List<UUID> found = jdbc.queryForList("select id from person where mobile_e164 = ?", UUID.class, a.mobile());
            UUID guardian;
            if (found.isEmpty()) {
                String ref = jdbc.queryForObject("""
                    select 'GRD-' || lpad((coalesce(max(substring(school_ref from '^GRD-([0-9]+)$')::int), 0) + 1)::text, 5, '0') from person
                    """, String.class);
                int cut = a.name().lastIndexOf(' ');
                guardian = jdbc.queryForObject("""
                    insert into person (tenant_id, school_ref, given_name, family_name, mobile_e164, email, created_by, provenance)
                    values (?, ?, ?, ?, ?, ?, ?, ?::jsonb) returning id
                    """, UUID.class, tenant, ref, cut < 0 ? a.name() : a.name().substring(0, cut),
                    cut < 0 ? null : a.name().substring(cut + 1), a.mobile(), blankToNull(a.email()), actor, provenance);
                people.add(guardian.toString());
            } else {
                guardian = found.get(0);
            }
            Integer related = jdbc.queryForObject(
                "select count(*)::int from guardian_relationship where guardian_person_id = ? and student_person_id = ?", Integer.class,
                guardian, student);
            if (guardian.equals(student) || (related != null && related > 0)) {
                continue;
            }
            UUID rel = jdbc.queryForObject("""
                insert into guardian_relationship (tenant_id, guardian_person_id, student_person_id, relationship_type,
                    can_view_attendance, can_view_academic, can_pay_fees, can_give_consent, can_receive_notices, can_pickup,
                    status, provenance)
                values (?, ?, ?, ?, true, true, true, true, true, false, 'ACTIVE', ?::jsonb) returning id
                """, UUID.class, tenant, guardian, student, a.relationship(), provenance);
            relationships.add(rel.toString());
            linked.add(guardian);
        }
        List<String> households = new ArrayList<>();
        List<String> memberships = new ArrayList<>();
        shareHousehold(jdbc, tenant, student, linked, households, memberships);
        return Map.of("people", people, "relationships", relationships, "households", households, "memberships", memberships);
    }

    /**
     * Puts the student in the household of a guardian just linked (so brothers and sisters imported with the same parent end up
     * together), or starts a new household named after the student's family. What was added is recorded for rollback.
     */
    private static void shareHousehold(JdbcTemplate jdbc, UUID tenant, UUID student, List<UUID> linked, List<String> households,
                                       List<String> memberships) {
        if (linked.isEmpty()) {
            return;
        }
        UUID household = null;
        for (UUID g : linked) {
            List<UUID> existing = jdbc.queryForList(
                "select household_id from household_member where person_id = ? and member_role = 'GUARDIAN' order by household_id limit 1", UUID.class, g);
            if (!existing.isEmpty()) {
                household = existing.get(0);
                break;
            }
        }
        if (household == null) {
            String family = jdbc.queryForObject("select coalesce(family_name, given_name) from person where id = ?", String.class, student);
            household = jdbc.queryForObject("insert into household (tenant_id, name) values (?, ?) returning id", UUID.class, tenant,
                family + " household");
            households.add(household.toString());
        }
        List<UUID> people = new ArrayList<>();
        people.add(student);
        people.addAll(linked);
        for (UUID p : people) {
            int added = jdbc.update("""
                insert into household_member (tenant_id, household_id, person_id, member_role) values (?, ?, ?, ?)
                on conflict do nothing
                """, tenant, household, p, p.equals(student) ? "STUDENT" : "GUARDIAN");
            if (added > 0) {
                memberships.add(household + ":" + p);
            }
        }
    }

    /** Removes the guardian links and contacts an import row created, unless someone has changed or used them since. */
    private void revertGuardians(JdbcTemplate jdbc, String ops, String ref, List<String> kept) {
        if (ops == null) {
            return;
        }
        Map<String, List<String>> m;
        try {
            m = json.readValue(ops, new TypeReference<Map<String, List<String>>>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (String rel : m.getOrDefault("relationships", List.of())) {
            UUID id = UUID.fromString(rel);
            int gone = jdbc.update("delete from guardian_relationship where id = ? and status = 'ACTIVE' and updated_at is null", id);
            Integer left = jdbc.queryForObject("select count(*)::int from guardian_relationship where id = ?", Integer.class, id);
            if (gone == 0 && left != null && left > 0) {
                kept.add(ref + ": a guardian link was kept, because it was changed after the import.");
            }
        }
        for (String member : m.getOrDefault("memberships", List.of())) {
            String[] parts = member.split(":");
            // Only for people who no longer have any guardian link: a contact or child that was kept stays in its household.
            jdbc.update("""
                delete from household_member where household_id = ? and person_id = ?
                   and not exists (select 1 from guardian_relationship where guardian_person_id = person_id or student_person_id = person_id)
                """, UUID.fromString(parts[0]), UUID.fromString(parts[1]));
        }
        for (String h : m.getOrDefault("households", List.of())) {
            jdbc.update("delete from household h where h.id = ? and not exists (select 1 from household_member where household_id = h.id)",
                UUID.fromString(h));
        }
        for (String p : m.getOrDefault("people", List.of())) {
            UUID id = UUID.fromString(p);
            Integer refs = jdbc.queryForObject("""
                select (select count(*) from guardian_relationship where guardian_person_id = ?)
                     + (select count(*) from identity where person_id = ?)
                     + (select count(*) from role_grant where person_id = ?)
                     + (select count(*) from household_member where person_id = ?)
                     + (select count(*) from notice_delivery where recipient_person_id = ?)
                """, Integer.class, id, id, id, id, id);
            if (refs != null && refs == 0) {
                jdbc.update("delete from person where id = ?", id);
            }
        }
    }

    private static void place(JdbcTemplate jdbc, UUID tenant, UUID person, UUID campusId, UUID yearId, SectionRef s) {
        int n = jdbc.update("""
            update student_enrollment set class_section_id = ?, programme_id = coalesce(?, programme_id)
             where student_person_id = ? and academic_year_id = ?
            """, s == null ? null : s.sectionId(), s == null ? null : s.programmeId(), person, yearId);
        if (n == 0) {
            jdbc.update("""
                insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id, programme_id,
                                                class_section_id)
                values (?, ?, ?, ?, ?, ?)
                """, tenant, person, campusId, yearId, s == null ? null : s.programmeId(), s == null ? null : s.sectionId());
        }
    }

    /** Result of a rollback: what was reverted and what was kept because it changed since (with reasons). */
    public record Reverted(int removed, int restored, int linksRestored, List<String> kept) { }

    public Reverted rollback(JdbcTemplate jdbc, UUID tenant, UUID batchId, UUID yearId, UUID campusId, UUID actor) {
        Catalog catalog = catalog(jdbc, yearId);
        int removed = 0;
        int restored = 0;
        int linksRestored = 0;
        List<String> kept = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select row_no, external_ref, action, person_id, incoming::text, changes::text, before_values::text,
                   link_before::text, guardian_ops::text
              from import_row where batch_id = ? and applied order by row_no
            """, batchId);
        for (Map<String, Object> row : rows) {
            UUID person = (UUID) row.get("person_id");
            String ref = (String) row.get("external_ref");
            String key = ref.toUpperCase(java.util.Locale.ROOT);
            revertGuardians(jdbc, (String) row.get("guardian_ops"), ref, kept);
            Map<String, String> incoming = read((String) row.get("incoming"));
            Map<String, String> now = current(jdbc, person, yearId);
            switch (Action.valueOf((String) row.get("action"))) {
                case CREATE -> {
                    Integer refs = jdbc.queryForObject("""
                        select (select count(*) from guardian_relationship where guardian_person_id = ? or student_person_id = ?)
                             + (select count(*) from identity where person_id = ?)
                             + (select count(*) from role_grant where person_id = ?)
                             + (select count(*) from household_member where person_id = ?)
                             + (select count(*) from student_enrollment where student_person_id = ? and academic_year_id <> ?)
                        """, Integer.class, person, person, person, person, person, person, yearId);
                    if (refs != null && refs > 0) {
                        kept.add(ref + ": kept, because the student is now linked to guardians, accounts or other years.");
                    } else if (!normalised(now).equals(normalised(incoming))) {
                        kept.add(ref + ": kept, because the student's details were edited after the import.");
                    } else {
                        jdbc.update("delete from student_enrollment where student_person_id = ?", person);
                        jdbc.update("delete from external_link where person_id = ?", person);
                        jdbc.update("delete from person where id = ?", person);
                        removed++;
                    }
                }
                case UPDATE -> {
                    List<FieldChange> changes = json.convertValue(readList((String) row.get("changes")),
                        new TypeReference<List<FieldChange>>() { });
                    boolean any = false;
                    for (FieldChange c : changes) {
                        if (c.field().startsWith(StudentImport.GUARDIAN_PREFIX)) {
                            continue;   // guardian links were reverted above
                        }
                        String cur = Objects.requireNonNullElse(now.get(c.field()), "");
                        if (!cur.equals(c.after())) {
                            kept.add(ref + ": " + c.field() + " kept as \"" + cur + "\", because it changed after the import.");
                            continue;
                        }
                        any = true;
                        switch (c.field()) {
                            case StudentImport.GIVEN_NAME -> jdbc.update(
                                "update person set given_name = ?, updated_at = now(), updated_by = ? where id = ?",
                                c.before(), actor, person);
                            case StudentImport.FAMILY_NAME -> jdbc.update(
                                "update person set family_name = ?, updated_at = now(), updated_by = ? where id = ?",
                                blankToNull(c.before()), actor, person);
                            case StudentImport.DATE_OF_BIRTH -> jdbc.update(
                                "update person set date_of_birth = ?, updated_at = now(), updated_by = ? where id = ?",
                                date(c.before()), actor, person);
                            default -> place(jdbc, tenant, person, campusId, yearId, catalog.byCanonical().get(c.before()));
                        }
                    }
                    if (any) {
                        restored++;
                    }
                    linksRestored += restoreLink(jdbc, key, (String) row.get("link_before"));
                }
                default -> linksRestored += restoreLink(jdbc, key, (String) row.get("link_before"));
            }
        }
        return new Reverted(removed, restored, linksRestored, kept);
    }

    private static int restoreLink(JdbcTemplate jdbc, String key, String linkBefore) {
        if (linkBefore == null) {
            return 0;
        }
        return jdbc.update("""
            update external_link set last_values = ?::jsonb, updated_at = now()
             where source = 'LEGACY' and entity = 'STUDENT' and external_ref = ?
            """, linkBefore, key);
    }

    static Map<String, String> values(String given, String family, String dob, String placement) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(StudentImport.GIVEN_NAME, given == null ? "" : given);
        m.put(StudentImport.FAMILY_NAME, family == null ? "" : family);
        m.put(StudentImport.DATE_OF_BIRTH, dob == null ? "" : dob);
        m.put(StudentImport.PLACEMENT, placement == null ? "" : placement);
        return m;
    }

    private static Map<String, String> normalised(Map<String, String> m) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String f : StudentImport.FIELDS) {
            out.put(f, Objects.requireNonNullElse(m.get(f), ""));
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static LocalDate date(String s) {
        return s == null || s.isEmpty() ? null : LocalDate.parse(s);
    }

    String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, String> read(String s) {
        try {
            return s == null ? Map.of() : json.readValue(s, MAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Object> readList(String s) {
        try {
            return json.readValue(s, new TypeReference<List<Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
