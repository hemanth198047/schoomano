package com.schoomano.domain.academic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.schoomano.domain.academic.GradeScale.Band;
import com.schoomano.domain.academic.RolloverPlanner.Current;
import com.schoomano.domain.academic.RolloverPlanner.Grade;
import com.schoomano.domain.academic.RolloverPlanner.ProgrammeTerm;
import com.schoomano.domain.academic.RolloverPlanner.Section;
import com.schoomano.domain.academic.TermRules.Term;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AcademicRulesTest {

    static DateRange r(String from, String to) {
        return new DateRange(LocalDate.parse(from), LocalDate.parse(to));
    }

    static final DateRange YEAR = r("2026-04-01", "2027-03-31");

    @Nested
    class Terms {
        final List<Term> existing = List.of(new Term("Term 1", r("2026-04-01", "2026-09-30")));

        @Test
        void adjacentTermInsideTheYearIsAccepted() {
            assertTrue(TermRules.problems(YEAR, existing, new Term("Term 2", r("2026-10-01", "2027-03-31"))).isEmpty());
        }

        @Test
        void overlappingTermIsReportedWithTheOtherTermsName() {
            var p = TermRules.problems(YEAR, existing, new Term("Term 2", r("2026-09-30", "2027-03-31")));
            assertEquals(1, p.size());
            assertTrue(p.get(0).contains("Term 1"));
        }

        @Test
        void termOutsideTheYearAndDuplicateLabelAreReported() {
            var p = TermRules.problems(YEAR, existing, new Term("term 1", r("2027-03-01", "2027-04-10")));
            assertEquals(2, p.size());
        }

        @Test
        void rangeEndingBeforeItStartsCannotBeBuilt() {
            assertThrows(IllegalArgumentException.class, () -> r("2026-05-02", "2026-05-01"));
        }
    }

    @Nested
    class GradeScales {
        final GradeScale cbse = new GradeScale(List.of(new Band("E", 0), new Band("A1", 91), new Band("A2", 81),
            new Band("B1", 71), new Band("B2", 61), new Band("C1", 51), new Band("C2", 41), new Band("D", 33)));

        @Test
        void boundariesAreInclusiveOfTheMinimum() {
            assertEquals("A1", cbse.gradeFor(91));
            assertEquals("A2", cbse.gradeFor(90.99));
            assertEquals("D", cbse.gradeFor(33));
            assertEquals("E", cbse.gradeFor(32.5));
            assertEquals("A1", cbse.gradeFor(100));
            assertEquals("E", cbse.gradeFor(0));
        }

        @Test
        void scaleWithoutAZeroBandIsRejected() {
            var p = GradeScale.problems(List.of(new Band("A", 50), new Band("B", 10)));
            assertTrue(p.stream().anyMatch(s -> s.contains("0%")));
        }

        @Test
        void duplicateGradesAndDuplicateMinimumsAreRejected() {
            assertEquals(2, GradeScale.problems(List.of(new Band("A", 0), new Band("A", 50), new Band("B", 50))).size());
        }

        @Test
        void outOfRangeScoreIsRefused() {
            assertThrows(IllegalArgumentException.class, () -> cbse.gradeFor(101));
        }
    }

    @Nested
    class Rollover {
        final UUID cbse = UUID.randomUUID();
        final Grade g11 = new Grade(UUID.randomUUID(), cbse, "CBSE-K12", "G11", 11);
        final Grade g12 = new Grade(UUID.randomUUID(), cbse, "CBSE-K12", "G12", 12);
        final Current current = new Current("2026-27", YEAR, List.of(g12, g11),
            List.of(new ProgrammeTerm("CBSE-K12", new Term("Term 1", r("2026-04-01", "2026-09-30")))),
            List.of(new Section(g11.id(), "A", 40, 30), new Section(g11.id(), "B", 40, 5),
                new Section(g12.id(), "A", 40, 28)),
            List.of());

        @Test
        void datesTermsAndSectionsShiftByOneYear() {
            var p = RolloverPlanner.preview(current);
            assertEquals("2027-28", p.label());
            assertEquals(r("2027-04-01", "2028-03-31"), p.dates());
            assertEquals(r("2027-04-01", "2027-09-30"), p.terms().get(0).dates());
            assertEquals(3, p.sections().size());
        }

        @Test
        void studentsArePromotedWithinTheProgrammeAndFinalGradeCompletes() {
            var p = RolloverPlanner.preview(current);
            var fromG11 = p.promotions().stream().filter(x -> x.fromGrade().equals("G11")).findFirst().orElseThrow();
            assertEquals("G12", fromG11.toGrade());
            assertEquals(35, fromG11.students());
            var fromG12 = p.promotions().stream().filter(x -> x.fromGrade().equals("G12")).findFirst().orElseThrow();
            assertNull(fromG12.toGrade());
            assertTrue(p.warnings().stream().anyMatch(w -> w.contains("28 student(s)")));
        }

        @Test
        void overlapWithAnExistingYearIsWarned() {
            var withNext = new Current(current.label(), current.dates(), current.grades(), current.terms(),
                current.sections(), List.of(r("2027-06-01", "2028-05-31")));
            assertTrue(RolloverPlanner.preview(withNext).warnings().stream().anyMatch(w -> w.contains("overlaps")));
        }

        @Test
        void labelsRollForwardAcrossTheCentury() {
            assertEquals("2099-00", RolloverPlanner.nextLabel("2098-99"));
            assertEquals("Year A (next)", RolloverPlanner.nextLabel("Year A"));
        }

        @Test
        void promotedStudentsKeepTheirSectionLetterWhenItExists() {
            assertEquals("B", RolloverPlanner.placeIn(List.of("A", "B"), "B").orElseThrow());
            assertEquals("A", RolloverPlanner.placeIn(List.of("C", "A"), "B").orElseThrow());
            assertTrue(RolloverPlanner.placeIn(List.of(), "A").isEmpty());
        }

        @Test
        void nextGradeStaysWithinTheProgramme() {
            var other = new Grade(UUID.randomUUID(), UUID.randomUUID(), "STATE", "G12", 12);
            assertEquals(g12, RolloverPlanner.nextGrade(List.of(g11, g12, other), g11).orElseThrow());
            assertTrue(RolloverPlanner.nextGrade(List.of(g11, g12, other), g12).isEmpty());
        }

        @Test
        void leapDayEndShiftsToTheLastDayOfFebruary() {
            var leap = new Current("2027-28", r("2027-03-01", "2028-02-29"), List.of(), List.of(), List.of(), List.of());
            assertEquals(LocalDate.parse("2029-02-28"), RolloverPlanner.preview(leap).dates().endsOn());
        }
    }
}
