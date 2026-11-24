package com.schoomano.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.domain.timetable.BellSchedule;
import com.schoomano.domain.timetable.ClashText;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * B5 timetable and substitutions (M05). Reading needs TIMETABLE_READ and changing TIMETABLE_MANAGE, in the
 * campus scope of the academic year (a reader who cannot see the campus gets 404, a reader who may not change
 * gets 403). The database refuses double-booking once a year is published; the API lists clashes first and
 * turns the database's refusals into plain messages. Every change is audited in the same transaction.
 */
@RestController
@RequestMapping("/api/v1")
public class TimetableController {

    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Kolkata");

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ObjectMapper json;
    private final Clock clock;

    public TimetableController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access,
                               ObjectMapper json, Clock clock) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    // ---- views and requests ---------------------------------------------------------------------------------

    public record PeriodView(UUID id, int ordinal, String label, LocalTime startsAt, LocalTime endsAt, String kind) { }

    public record PeriodRequest(@Min(1) @Max(12) int ordinal, @NotBlank @Size(max = 40) String label,
                                @NotNull LocalTime startsAt, @NotNull LocalTime endsAt,
                                @NotNull @Pattern(regexp = "LESSON|BREAK") String kind) { }

    public record PeriodsRequest(@NotNull @Size(min = 1, max = 12) List<@Valid PeriodRequest> periods) { }

    public record RoomView(UUID id, String code, String name, Integer capacity, String status) { }

    public record RoomRequest(@NotBlank @Pattern(regexp = "[A-Z0-9-]{1,12}") String code,
                              @NotBlank @Size(max = 80) String name, @Min(1) @Max(500) Integer capacity) { }

    public record EntryView(UUID id, UUID sectionId, String sectionLabel, int day, UUID periodId, int periodOrdinal,
                            String periodLabel, UUID subjectId, String subjectName, UUID teacherId, String teacherName,
                            UUID roomId, String roomName) { }

    public record EntryRequest(@NotNull UUID sectionId, @Min(1) @Max(7) int day, @NotNull UUID periodId,
                               @NotNull UUID subjectId, @NotNull UUID teacherId, UUID roomId) { }

    public record EntryPatch(UUID subjectId, UUID teacherId, UUID roomId, Boolean clearRoom) { }

    public record ClashView(String kind, int day, String period, String resource, List<String> sections, String text) { }

    public record StatusView(String status, int version, OffsetDateTime publishedAt, List<ClashView> clashes) { }

    public record SubstitutionRequest(@NotNull UUID entryId, @NotNull LocalDate onDate, @NotNull UUID substituteId,
                                      @NotBlank @Size(min = 3, max = 200) String reason) { }

    public record SubstitutionView(UUID id, UUID entryId, LocalDate onDate, String sectionLabel, int periodOrdinal,
                                   String periodLabel, String subjectName, UUID absentTeacherId, String absentTeacherName,
                                   UUID substituteId, String substituteName, String reason) { }

    public record TeacherView(UUID id, String name) { }

    public record MyLesson(LocalDate date, int periodOrdinal, String periodLabel, LocalTime startsAt, LocalTime endsAt,
                           String sectionLabel, String subjectName, String roomName, String coverFor) { }

    public record UnpublishRequest(@NotBlank @Size(min = 10, max = 500) String reason) { }

    // ---- bell schedule --------------------------------------------------------------------------------------

    @GetMapping("/campuses/{campusId}/periods")
    public List<PeriodView> periods(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireRead(jdbc, ctx, "period", access.campusPath(jdbc, ctx, campusId));
            return loadPeriods(jdbc, campusId);
        });
    }

    /** Replaces the whole bell schedule. Periods that lessons use can be re-timed but not removed. */
    @PutMapping("/campuses/{campusId}/periods")
    public List<PeriodView> replacePeriods(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody PeriodsRequest req) {
        List<BellSchedule.Slot> slots = req.periods().stream()
            .map(p -> new BellSchedule.Slot(p.ordinal(), p.label().trim(), p.startsAt(), p.endsAt(), "BREAK".equals(p.kind()))).toList();
        List<String> problems = BellSchedule.problems(slots);
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_schedule", "The school day needs a few changes.",
                Map.of("problems", problems));
        }
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            requireManage(jdbc, ctx, "period", access.campusPath(jdbc, ctx, campusId));
            // Re-timing several periods at once may overlap in between; the overlap check runs at commit instead.
            jdbc.execute("set constraints period_no_overlap deferred");
            List<Integer> keep = slots.stream().map(BellSchedule.Slot::ordinal).toList();
            List<String> inUse = jdbc.queryForList("""
                select p.label from period p
                 where p.campus_id = ? and p.ordinal <> all (?::int[])
                   and exists (select 1 from timetable_entry e where e.period_id = p.id)
                """, String.class, campusId, keep.toArray(new Integer[0]));
            if (!inUse.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "period_in_use",
                    "These periods still have lessons and cannot be removed: " + String.join(", ", inUse) + ".");
            }
            jdbc.update("delete from period where campus_id = ? and ordinal <> all (?::int[])", campusId, keep.toArray(new Integer[0]));
            for (BellSchedule.Slot s : slots) {
                jdbc.update("""
                    insert into period (tenant_id, campus_id, ordinal, label, starts_at, ends_at, is_break)
                    values (?, ?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, campus_id, ordinal)
                    do update set label = excluded.label, starts_at = excluded.starts_at, ends_at = excluded.ends_at,
                                  is_break = excluded.is_break
                    """, ctx.tenant().value(), campusId, s.ordinal(), s.label(), s.startsAt(), s.endsAt(), s.isBreak());
            }
            audit.write(jdbc, ctx, "timetable.periods_replaced", "campus", campusId.toString(), "SUCCESS", null,
                Map.of("periods", slots.size()));
            return loadPeriods(jdbc, campusId);
        }));
    }

    // ---- rooms ----------------------------------------------------------------------------------------------

    @GetMapping("/campuses/{campusId}/rooms")
    public List<RoomView> rooms(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireRead(jdbc, ctx, "room", access.campusPath(jdbc, ctx, campusId));
            return jdbc.query("select id, code, name, capacity, status from room where campus_id = ? order by code",
                (rs, i) -> new RoomView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    (Integer) rs.getObject(4), rs.getString(5)), campusId);
        });
    }

    @PostMapping("/campuses/{campusId}/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomView createRoom(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody RoomRequest req) {
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            requireManage(jdbc, ctx, "room", access.campusPath(jdbc, ctx, campusId));
            UUID id = jdbc.queryForObject("""
                insert into room (tenant_id, campus_id, code, name, capacity) values (?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), campusId, req.code(), req.name().trim(), req.capacity());
            audit.write(jdbc, ctx, "timetable.room_created", "room", id.toString(), "SUCCESS", null, Map.of("code", req.code()));
            return new RoomView(id, req.code(), req.name().trim(), req.capacity(), "ACTIVE");
        }));
    }

    /** Rooms are retired, never deleted, so old timetables keep their history. */
    @PostMapping("/rooms/{roomId}/retire")
    public RoomView retireRoom(RequestContext ctx, @PathVariable UUID roomId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID campusId = jdbc.queryForList("select campus_id from room where id = ?", UUID.class, roomId)
                .stream().findFirst().orElseThrow(ApiException::notFound);
            requireManage(jdbc, ctx, "room", access.campusPath(jdbc, ctx, campusId));
            if (jdbc.queryForObject("select count(*) from timetable_entry where room_id = ?", Integer.class, roomId) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "room_in_use",
                    "This room is used in the timetable. Move those lessons first.");
            }
            jdbc.update("update room set status = 'RETIRED' where id = ?", roomId);
            audit.write(jdbc, ctx, "timetable.room_retired", "room", roomId.toString(), "SUCCESS", null, Map.of());
            return jdbc.queryForObject("select id, code, name, capacity, status from room where id = ?",
                (rs, i) -> new RoomView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    (Integer) rs.getObject(4), rs.getString(5)), roomId);
        });
    }

    /** Active teachers of the campus (for choosing who teaches a lesson). */
    @GetMapping("/campuses/{campusId}/teachers")
    public List<TeacherView> teachers(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireRead(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, campusId));
            return jdbc.query("""
                select distinct t.id, trim(t.given_name || ' ' || coalesce(t.family_name, ''))
                  from person t join role_grant r on r.person_id = t.id and r.role_key = 'teacher'
                 where t.status = 'ACTIVE' and r.valid_from <= now() and (r.valid_to is null or r.valid_to > now())
                   and (r.scope_level = 'GROUP' or r.scope_id = ?
                        or (r.scope_level = 'PROGRAMME' and exists (select 1 from programme g where g.id = r.scope_id and g.campus_id = ?)))
                 order by 2
                """, (rs, i) -> new TeacherView(rs.getObject(1, UUID.class), rs.getString(2)), campusId, campusId);
        });
    }

    // ---- lessons --------------------------------------------------------------------------------------------

    private static final String ENTRY_SQL = """
        select e.id, e.class_section_id, g.name || ' ' || s.code, e.day_of_week, e.period_id, p.ordinal, p.label,
               e.subject_id, sub.name, e.teacher_person_id, trim(t.given_name || ' ' || coalesce(t.family_name, '')),
               e.room_id, r.name
          from timetable_entry e
          join class_section s on s.id = e.class_section_id
          join grade_level g on g.id = s.grade_level_id
          join period p on p.id = e.period_id
          join subject sub on sub.id = e.subject_id
          join person t on t.id = e.teacher_person_id
          left join room r on r.id = e.room_id
        """;

    private static EntryView entry(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new EntryView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
            rs.getObject(5, UUID.class), rs.getInt(6), rs.getString(7), rs.getObject(8, UUID.class), rs.getString(9),
            rs.getObject(10, UUID.class), rs.getString(11), rs.getObject(12, UUID.class), rs.getString(13));
    }

    /** All lessons of the year, optionally narrowed to one section, teacher or room. */
    @GetMapping("/academic-years/{yearId}/timetable")
    public List<EntryView> timetable(RequestContext ctx, @PathVariable UUID yearId, @RequestParam(required = false) UUID sectionId,
                                     @RequestParam(required = false) UUID teacherId, @RequestParam(required = false) UUID roomId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireRead(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            return jdbc.query(ENTRY_SQL + """
                 where e.academic_year_id = ? and (?::uuid is null or e.class_section_id = ?::uuid)
                   and (?::uuid is null or e.teacher_person_id = ?::uuid) and (?::uuid is null or e.room_id = ?::uuid)
                 order by e.day_of_week, p.ordinal, g.ordinal, s.code
                """, TimetableController::entry, yearId, sectionId, sectionId, teacherId, teacherId, roomId, roomId);
        });
    }

    @PostMapping("/academic-years/{yearId}/timetable/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryView addLesson(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody EntryRequest req) {
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            UUID id = jdbc.queryForObject("""
                insert into timetable_entry (tenant_id, academic_year_id, class_section_id, day_of_week, period_id,
                                             subject_id, teacher_person_id, room_id)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), yearId, req.sectionId(), req.day(), req.periodId(), req.subjectId(),
                req.teacherId(), req.roomId());
            audit.write(jdbc, ctx, "timetable.lesson_added", "timetable_entry", id.toString(), "SUCCESS", null,
                Map.of("yearId", yearId.toString(), "sectionId", req.sectionId().toString(), "day", req.day()));
            return jdbc.queryForObject(ENTRY_SQL + " where e.id = ?", TimetableController::entry, id);
        }));
    }

    @PatchMapping("/timetable/entries/{entryId}")
    public EntryView changeLesson(RequestContext ctx, @PathVariable UUID entryId, @RequestBody EntryPatch req) {
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            UUID yearId = yearOf(jdbc, entryId);
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            boolean clearRoom = Boolean.TRUE.equals(req.clearRoom());
            jdbc.update("""
                update timetable_entry set subject_id = coalesce(?, subject_id), teacher_person_id = coalesce(?, teacher_person_id),
                       room_id = case when ? then null else coalesce(?, room_id) end
                 where id = ?
                """, req.subjectId(), req.teacherId(), clearRoom, req.roomId(), entryId);
            audit.write(jdbc, ctx, "timetable.lesson_changed", "timetable_entry", entryId.toString(), "SUCCESS", null, Map.of());
            return jdbc.queryForObject(ENTRY_SQL + " where e.id = ?", TimetableController::entry, entryId);
        }));
    }

    @DeleteMapping("/timetable/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeLesson(RequestContext ctx, @PathVariable UUID entryId) {
        tenantTx.run(ctx.tenant(), jdbc -> {
            UUID yearId = yearOf(jdbc, entryId);
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            jdbc.update("delete from timetable_entry where id = ?", entryId);
            audit.write(jdbc, ctx, "timetable.lesson_removed", "timetable_entry", entryId.toString(), "SUCCESS", null, Map.of());
            return null;
        });
    }

    // ---- publishing -----------------------------------------------------------------------------------------

    @GetMapping("/academic-years/{yearId}/timetable/status")
    public StatusView status(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireRead(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            return statusOf(jdbc, yearId);
        });
    }

    /** Clashes block publishing and are listed in words; once published, edits that would clash are refused. */
    @PostMapping("/academic-years/{yearId}/timetable/publish")
    public StatusView publish(RequestContext ctx, @PathVariable UUID yearId) {
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            StatusView now = statusOf(jdbc, yearId);
            if (!now.clashes().isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "publish_rejected",
                    "The timetable has clashes. Fix them and publish again.",
                    Map.of("problems", now.clashes().stream().map(ClashView::text).toList()));
            }
            if (jdbc.queryForObject("select count(*) from timetable_entry where academic_year_id = ?", Integer.class, yearId) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "publish_rejected", "There are no lessons to publish yet.",
                    Map.of("problems", List.of("Add lessons to the timetable first.")));
            }
            int version = now.version() + 1;
            jdbc.update("""
                insert into timetable_publication (tenant_id, academic_year_id, status, version, published_at, published_by)
                values (?, ?, 'PUBLISHED', ?, now(), ?)
                on conflict (tenant_id, academic_year_id)
                do update set status = 'PUBLISHED', version = excluded.version, published_at = now(), published_by = excluded.published_by
                """, ctx.tenant().value(), yearId, version, ctx.actor().personId());
            audit.write(jdbc, ctx, "timetable.published", "academic_year", yearId.toString(), "SUCCESS", null, Map.of("version", version));
            outbox(jdbc, ctx, "academic_year", yearId.toString(), "timetable.published", Map.of("yearId", yearId.toString(), "version", version));
            return statusOf(jdbc, yearId);
        }));
    }

    /** Back to draft for a big rework. Needs a reason and is audited. */
    @PostMapping("/academic-years/{yearId}/timetable/unpublish")
    public StatusView unpublish(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody UnpublishRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            jdbc.update("update timetable_publication set status = 'DRAFT' where academic_year_id = ?", yearId);
            audit.write(jdbc, ctx, "timetable.unpublished", "academic_year", yearId.toString(), "SUCCESS", req.reason().trim(), Map.of());
            return statusOf(jdbc, yearId);
        });
    }

    // ---- substitutions --------------------------------------------------------------------------------------

    @GetMapping("/academic-years/{yearId}/substitutions")
    public List<SubstitutionView> substitutions(RequestContext ctx, @PathVariable UUID yearId,
                                                @RequestParam LocalDate from, @RequestParam(required = false) LocalDate to) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireRead(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            return jdbc.query(SUBSTITUTION_SQL + " where e.academic_year_id = ? and x.on_date between ? and ? order by x.on_date, p.ordinal",
                TimetableController::substitution, yearId, from, to == null ? from : to);
        });
    }

    /** Teachers who are free for that lesson on that date (not teaching then and not already covering). */
    @GetMapping("/academic-years/{yearId}/substitutions/available")
    public List<TeacherView> available(RequestContext ctx, @PathVariable UUID yearId, @RequestParam UUID entryId,
                                       @RequestParam LocalDate date) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            return jdbc.query("""
                select t.id, trim(t.given_name || ' ' || coalesce(t.family_name, ''))
                  from person t
                  join timetable_entry me on me.id = ? and me.academic_year_id = ?
                 where t.status = 'ACTIVE' and t.id <> me.teacher_person_id
                   and exists (select 1 from role_grant r where r.person_id = t.id and r.role_key = 'teacher'
                                and r.valid_from <= now() and (r.valid_to is null or r.valid_to > now()))
                   and not exists (select 1 from timetable_entry o
                                    where o.academic_year_id = me.academic_year_id and o.day_of_week = me.day_of_week
                                      and o.period_id = me.period_id and o.teacher_person_id = t.id
                                      and not exists (select 1 from substitution s where s.timetable_entry_id = o.id and s.on_date = ?))
                   and not exists (select 1 from substitution s join timetable_entry o on o.id = s.timetable_entry_id
                                    where s.on_date = ? and s.substitute_person_id = t.id
                                      and o.day_of_week = me.day_of_week and o.period_id = me.period_id)
                 order by 2
                """, (rs, i) -> new TeacherView(rs.getObject(1, UUID.class), rs.getString(2)), entryId, yearId, date, date);
        });
    }

    @PostMapping("/academic-years/{yearId}/substitutions")
    @ResponseStatus(HttpStatus.CREATED)
    public SubstitutionView substitute(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody SubstitutionRequest req) {
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            Integer day = jdbc.queryForList("select day_of_week from timetable_entry where id = ? and academic_year_id = ?",
                Integer.class, req.entryId(), yearId).stream().findFirst().orElseThrow(ApiException::notFound);
            if (ClashText.isoDay(req.onDate()) != day) {
                throw new ApiException(HttpStatus.CONFLICT, "wrong_day", "That lesson is on " + ClashText.dayName(day)
                    + "s, but " + req.onDate() + " is a " + ClashText.dayName(ClashText.isoDay(req.onDate())) + ".");
            }
            UUID id = jdbc.queryForObject("""
                insert into substitution (tenant_id, timetable_entry_id, on_date, substitute_person_id, reason, created_by)
                values (?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), req.entryId(), req.onDate(), req.substituteId(), req.reason().trim(),
                ctx.actor().personId());
            audit.write(jdbc, ctx, "timetable.substitution_created", "substitution", id.toString(), "SUCCESS", req.reason().trim(),
                Map.of("entryId", req.entryId().toString(), "date", req.onDate().toString()));
            // Teachers, and later parents, are told through the outbox (B7 delivers it).
            outbox(jdbc, ctx, "substitution", id.toString(), "timetable.substitution_created",
                Map.of("substitutionId", id.toString(), "entryId", req.entryId().toString(),
                    "date", req.onDate().toString(), "substituteId", req.substituteId().toString()));
            return jdbc.queryForObject(SUBSTITUTION_SQL + " where x.id = ?", TimetableController::substitution, id);
        }));
    }

    @DeleteMapping("/substitutions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelSubstitution(RequestContext ctx, @PathVariable UUID id) {
        tenantTx.run(ctx.tenant(), jdbc -> {
            UUID yearId = jdbc.queryForList("""
                select e.academic_year_id from substitution x join timetable_entry e on e.id = x.timetable_entry_id where x.id = ?
                """, UUID.class, id).stream().findFirst().orElseThrow(ApiException::notFound);
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, "timetable", access.campusPath(jdbc, ctx, year.campusId()));
            jdbc.update("delete from substitution where id = ?", id);
            audit.write(jdbc, ctx, "timetable.substitution_cancelled", "substitution", id.toString(), "SUCCESS", null, Map.of());
            outbox(jdbc, ctx, "substitution", id.toString(), "timetable.substitution_cancelled", Map.of("substitutionId", id.toString()));
            return null;
        });
    }

    private static final String SUBSTITUTION_SQL = """
        select x.id, x.timetable_entry_id, x.on_date, g.name || ' ' || s.code, p.ordinal, p.label, sub.name,
               e.teacher_person_id, trim(a.given_name || ' ' || coalesce(a.family_name, '')),
               x.substitute_person_id, trim(b.given_name || ' ' || coalesce(b.family_name, '')), x.reason
          from substitution x
          join timetable_entry e on e.id = x.timetable_entry_id
          join class_section s on s.id = e.class_section_id
          join grade_level g on g.id = s.grade_level_id
          join period p on p.id = e.period_id
          join subject sub on sub.id = e.subject_id
          join person a on a.id = e.teacher_person_id
          join person b on b.id = x.substitute_person_id
        """;

    private static SubstitutionView substitution(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SubstitutionView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, LocalDate.class),
            rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7), rs.getObject(8, UUID.class), rs.getString(9),
            rs.getObject(10, UUID.class), rs.getString(11), rs.getString(12));
    }

    // ---- my day ---------------------------------------------------------------------------------------------

    /**
     * The signed-in teacher's lessons for one date (default today, school time): their own lessons that are not
     * covered by someone else that day, plus lessons they are covering. Only published timetables are shown.
     */
    @GetMapping("/timetable/me")
    public List<MyLesson> myDay(RequestContext ctx, @RequestParam(required = false) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(SCHOOL_ZONE));
        return tenantTx.run(ctx.tenant(), jdbc -> {
            List<MyLesson> rows = jdbc.query("""
                select ?::date, p.ordinal, p.label, p.starts_at, p.ends_at, g.name || ' ' || s.code, sub.name, r.name,
                       case when e.teacher_person_id = ? then null
                            else trim(a.given_name || ' ' || coalesce(a.family_name, '')) end,
                       y.campus_id
                  from timetable_entry e
                  join academic_year y on y.id = e.academic_year_id and ? between y.starts_on and y.ends_on
                  join timetable_publication tp on tp.academic_year_id = e.academic_year_id and tp.status = 'PUBLISHED'
                  join class_section s on s.id = e.class_section_id
                  join grade_level g on g.id = s.grade_level_id
                  join period p on p.id = e.period_id
                  join subject sub on sub.id = e.subject_id
                  join person a on a.id = e.teacher_person_id
                  left join room r on r.id = e.room_id
                  left join substitution x on x.timetable_entry_id = e.id and x.on_date = ?
                 where e.day_of_week = ?
                   and ((e.teacher_person_id = ? and x.id is null) or x.substitute_person_id = ?)
                 order by p.ordinal
                """, (rs, i) -> new Object[] {new MyLesson(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getString(3),
                    rs.getObject(4, LocalTime.class), rs.getObject(5, LocalTime.class), rs.getString(6), rs.getString(7),
                    rs.getString(8), rs.getString(9)), rs.getObject(10, UUID.class)},
                day, ctx.actor().personId(), day, day, ClashText.isoDay(day), ctx.actor().personId(), ctx.actor().personId())
                .stream().filter(o -> canRead(jdbc, ctx, (UUID) o[1])).map(o -> (MyLesson) o[0]).toList();
            return rows;
        });
    }

    private boolean canRead(JdbcTemplate jdbc, RequestContext ctx, UUID campusId) {
        ScopePath path = access.campusPath(jdbc, ctx, campusId);
        return authz.policy().decide(ctx.actor(), Permission.TIMETABLE_READ, Resource.ordinary("timetable", null, path, null),
            clock.instant()).allowed();
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private void requireRead(JdbcTemplate jdbc, RequestContext ctx, String type, ScopePath path) {
        authz.requireOrNotFound(jdbc, ctx, Permission.TIMETABLE_READ, Resource.ordinary(type, null, path, null));
    }

    private void requireManage(JdbcTemplate jdbc, RequestContext ctx, String type, ScopePath path) {
        requireRead(jdbc, ctx, type, path);
        authz.requireOrForbidden(jdbc, ctx, Permission.TIMETABLE_MANAGE, Resource.ordinary(type, null, path, null));
    }

    private static UUID yearOf(JdbcTemplate jdbc, UUID entryId) {
        return jdbc.queryForList("select academic_year_id from timetable_entry where id = ?", UUID.class, entryId)
            .stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private static List<PeriodView> loadPeriods(JdbcTemplate jdbc, UUID campusId) {
        return jdbc.query("select id, ordinal, label, starts_at, ends_at, is_break from period where campus_id = ? order by ordinal",
            (rs, i) -> new PeriodView(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                rs.getObject(4, LocalTime.class), rs.getObject(5, LocalTime.class), rs.getBoolean(6) ? "BREAK" : "LESSON"), campusId);
    }

    private StatusView statusOf(JdbcTemplate jdbc, UUID yearId) {
        var pub = jdbc.query("select status, version, published_at from timetable_publication where academic_year_id = ?",
            (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getObject(3, OffsetDateTime.class)}, yearId)
            .stream().findFirst().orElse(new Object[] {"DRAFT", 0, null});
        List<ClashView> clashes = new ArrayList<>();
        jdbc.query("""
            select c.kind, c.day_of_week, p.label,
                   case c.kind when 'TEACHER' then (select trim(given_name || ' ' || coalesce(family_name, '')) from person where id = c.resource_id)
                               else (select code from room where id = c.resource_id) end,
                   (select array_agg(g.name || ' ' || s.code order by g.ordinal, s.code)
                      from timetable_entry e join class_section s on s.id = e.class_section_id
                      join grade_level g on g.id = s.grade_level_id where e.id = any (c.entry_ids))
              from timetable_clashes(?) c join period p on p.id = c.period_id
             order by c.day_of_week, p.ordinal, c.kind
            """, rs -> {
                List<String> sections = List.of((String[]) rs.getArray(5).getArray());
                String resource = rs.getString(4);
                clashes.add(new ClashView(rs.getString(1), rs.getInt(2), rs.getString(3), resource, sections,
                    ClashText.describe(rs.getString(1), rs.getInt(2), rs.getString(3), resource, sections)));
            }, yearId);
        return new StatusView((String) pub[0], (Integer) pub[1], (OffsetDateTime) pub[2], clashes);
    }

    private void outbox(JdbcTemplate jdbc, RequestContext ctx, String aggregateType, String aggregateId, String event,
                        Map<String, Object> payload) {
        try {
            jdbc.update("""
                insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                values (?, ?, ?, ?, ?::jsonb)
                """, ctx.tenant().value(), aggregateType, aggregateId, event, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The database's own refusals (clash, wrong campus, retired room...) become plain, specific messages. */
    private static <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataIntegrityViolationException e) {
            String raw = e.getMostSpecificCause().getMessage();
            String first = raw == null ? "" : raw.lines().findFirst().orElse("");
            String message;
            if (first.contains("duplicate key") && first.contains("day_of_week")) {
                message = "That class already has a lesson in this period.";
            } else if (first.contains("duplicate key")) {
                message = "That already exists.";
            } else if (first.startsWith("ERROR: ") && first.length() > 7) {
                // Our own triggers word their refusals for people ("teacher is already teaching at that time").
                String text = first.substring(7).trim();
                message = Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
            } else {
                message = "That change is not allowed.";
            }
            throw new ApiException(HttpStatus.CONFLICT, "timetable_rejected", message);
        }
    }
}
