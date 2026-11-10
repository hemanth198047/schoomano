package com.schoomano.domain.academic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proposes next year's structure from the current one: dates shifted by a year, terms and sections
 * carried over, students promoted one grade within their programme. Pure computation; the preview
 * writes nothing. Committing a rollover is a later step that will reuse this plan.
 */
public final class RolloverPlanner {

    private static final Pattern SPLIT_YEAR = Pattern.compile("^(\\d{4})-(\\d{2})$");

    private RolloverPlanner() {
    }

    public record Grade(UUID id, UUID programmeId, String programmeCode, String code, int ordinal) { }
    public record ProgrammeTerm(String programmeCode, TermRules.Term term) { }
    public record Section(UUID gradeId, String code, Integer capacity, int enrolledStudents) { }
    public record Current(String label, DateRange dates, List<Grade> grades, List<ProgrammeTerm> terms,
                          List<Section> sections, List<DateRange> otherYearsOnCampus) { }

    public record ProposedTerm(String programmeCode, String label, DateRange dates) { }
    public record ProposedSection(String programmeCode, String gradeCode, String sectionCode, Integer capacity) { }
    public record Promotion(String programmeCode, String fromGrade, String toGrade, int students) { }
    public record Preview(String label, DateRange dates, List<ProposedTerm> terms, List<ProposedSection> sections,
                          List<Promotion> promotions, List<String> warnings) { }

    public static Preview preview(Current current) {
        List<String> warnings = new ArrayList<>();
        DateRange next = current.dates().plusYears(1);
        String label = nextLabel(current.label());

        for (DateRange other : current.otherYearsOnCampus()) {
            if (other.overlaps(next)) {
                warnings.add("Next year (" + next.startsOn() + " to " + next.endsOn()
                    + ") overlaps an academic year that already exists on this campus.");
            }
        }

        List<ProposedTerm> terms = current.terms().stream()
            .map(t -> new ProposedTerm(t.programmeCode(), t.term().label(), t.term().dates().plusYears(1)))
            .sorted(Comparator.comparing(ProposedTerm::programmeCode).thenComparing(p -> p.dates().startsOn()))
            .toList();

        Map<UUID, Grade> gradeById = new TreeMap<>();
        current.grades().forEach(g -> gradeById.put(g.id(), g));

        List<ProposedSection> sections = current.sections().stream()
            .map(s -> {
                Grade g = gradeById.get(s.gradeId());
                return new ProposedSection(g.programmeCode(), g.code(), s.code(), s.capacity());
            })
            .sorted(Comparator.comparing(ProposedSection::programmeCode).thenComparing(ProposedSection::gradeCode)
                .thenComparing(ProposedSection::sectionCode))
            .toList();

        // Students per grade, then each grade's students move to the next ordinal of the same programme.
        Map<UUID, Integer> studentsByGrade = new TreeMap<>();
        for (Section s : current.sections()) {
            studentsByGrade.merge(s.gradeId(), s.enrolledStudents(), Integer::sum);
        }
        List<Promotion> promotions = new ArrayList<>();
        for (Grade g : sorted(current.grades())) {
            int students = studentsByGrade.getOrDefault(g.id(), 0);
            if (students == 0) {
                continue;
            }
            Grade to = nextGrade(current.grades(), g).orElse(null);
            promotions.add(new Promotion(g.programmeCode(), g.code(), to == null ? null : to.code(), students));
            if (to == null) {
                warnings.add(students + " student(s) in " + g.programmeCode() + " " + g.code()
                    + " complete the programme; they will not be carried into next year.");
            }
        }
        return new Preview(label, next, terms, sections, List.copyOf(promotions), List.copyOf(warnings));
    }

    /** The next grade of the same programme by ordinal; empty for the programme's final grade. */
    public static java.util.Optional<Grade> nextGrade(Collection<Grade> grades, Grade from) {
        return grades.stream()
            .filter(o -> o.programmeId().equals(from.programmeId()) && o.ordinal() > from.ordinal())
            .min(Comparator.comparingInt(Grade::ordinal));
    }

    /**
     * Section a promoted student joins: the same section code in the next grade (A stays A) when it exists,
     * otherwise the alphabetically first section of that grade, otherwise none (placed by the office later).
     */
    public static java.util.Optional<String> placeIn(Collection<String> nextGradeSectionCodes, String currentCode) {
        if (nextGradeSectionCodes.contains(currentCode)) {
            return java.util.Optional.of(currentCode);
        }
        return nextGradeSectionCodes.stream().sorted().findFirst();
    }

    /** "2026-27" → "2027-28"; any other label gets " (next)" so the admin renames it explicitly. */
    static String nextLabel(String label) {
        Matcher m = SPLIT_YEAR.matcher(label);
        if (m.matches()) {
            int start = Integer.parseInt(m.group(1)) + 1;
            return start + "-" + String.format("%02d", (start + 1) % 100);
        }
        return label + " (next)";
    }

    private static List<Grade> sorted(Collection<Grade> grades) {
        return grades.stream().sorted(Comparator.comparing(Grade::programmeCode).thenComparingInt(Grade::ordinal)).toList();
    }
}
