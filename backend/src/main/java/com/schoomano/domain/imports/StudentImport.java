package com.schoomano.domain.imports;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Plans a repeatable student import from the old school system (coexistence, decision D5).
 *
 * <p>Each imported field is compared three ways: what the old system sent last time ({@code last}), what
 * Schoomano holds now ({@code current}) and what this file says ({@code incoming}):
 * <ul>
 *   <li>incoming = current: nothing to do;</li>
 *   <li>current = last: only the old system changed it, so apply the incoming value;</li>
 *   <li>incoming = last: only Schoomano changed it, so keep Schoomano's value;</li>
 *   <li>otherwise both changed: a conflict, listed and never overwritten.</li>
 * </ul>
 * A row with any conflict is skipped entirely. Re-importing the same file therefore changes nothing.
 */
public final class StudentImport {

    private StudentImport() {
    }

    public static final String GIVEN_NAME = "givenName";
    public static final String FAMILY_NAME = "familyName";
    public static final String DATE_OF_BIRTH = "dateOfBirth";
    public static final String PLACEMENT = "placement";
    /** Fields owned by the old system during coexistence (read-only in Schoomano's UI). */
    public static final List<String> FIELDS = List.of(GIVEN_NAME, FAMILY_NAME, DATE_OF_BIRTH, PLACEMENT);

    public enum Action { CREATE, UPDATE, UNCHANGED, CONFLICT, ERROR }

    public record FieldChange(String field, String before, String after) { }

    /** One file row after planning. {@code incoming} holds the canonical values for the four fields. */
    public record RowPlan(int rowNo, String externalRef, Action action, List<String> problems,
                          List<FieldChange> changes, Map<String, String> incoming, UUID personId) { }

    /** A student already linked to the old system: the last values imported and the values held now. */
    public record Linked(UUID personId, Map<String, String> last, Map<String, String> current) { }

    /** One section a student can be placed in. Grades may be written as code, name or number. */
    public record Placement(String programmeCode, String gradeCode, String gradeName, int gradeOrdinal,
                            String sectionCode) {
        String canonical() {
            return programmeCode + "/" + gradeCode + "/" + sectionCode;
        }
    }

    public record Plan(List<RowPlan> rows, List<String> fileProblems, List<String> missingFromFile) {
        public Map<Action, Integer> counts() {
            Map<Action, Integer> c = new LinkedHashMap<>();
            for (Action a : Action.values()) {
                c.put(a, 0);
            }
            rows.forEach(r -> c.merge(r.action(), 1, Integer::sum));
            return c;
        }
    }

    private static final Map<String, String> HEADERS = Map.ofEntries(
        Map.entry("admissionno", "ref"), Map.entry("admissionnumber", "ref"), Map.entry("admno", "ref"),
        Map.entry("studentid", "ref"), Map.entry("externalref", "ref"), Map.entry("srno", "ref"),
        Map.entry("givenname", GIVEN_NAME), Map.entry("firstname", GIVEN_NAME), Map.entry("name", GIVEN_NAME),
        Map.entry("studentname", GIVEN_NAME),
        Map.entry("familyname", FAMILY_NAME), Map.entry("lastname", FAMILY_NAME), Map.entry("surname", FAMILY_NAME),
        Map.entry("dateofbirth", DATE_OF_BIRTH), Map.entry("dob", DATE_OF_BIRTH), Map.entry("birthdate", DATE_OF_BIRTH),
        Map.entry("programme", "programme"), Map.entry("program", "programme"), Map.entry("board", "programme"),
        Map.entry("programmecode", "programme"),
        Map.entry("grade", "grade"), Map.entry("class", "grade"), Map.entry("standard", "grade"),
        Map.entry("section", "section"), Map.entry("division", "section"));

    private static final List<DateTimeFormatter> DATES = List.of(
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT));

    /**
     * @param table          parsed file, first row is the header
     * @param placements     sections of the target academic year
     * @param linked         students already linked, by external reference (admission number)
     * @param unlinkedRefs   external references that exist in Schoomano without a link (would collide)
     * @param today          for birth-date sanity checks
     */
    public static Plan plan(List<List<String>> table, Collection<Placement> placements, Map<String, Linked> linked,
                            Set<String> unlinkedRefs, LocalDate today) {
        List<String> fileProblems = new ArrayList<>();
        if (table.isEmpty()) {
            return new Plan(List.of(), List.of("The file is empty."), List.of());
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> header = table.get(0);
        for (int i = 0; i < header.size(); i++) {
            String key = HEADERS.get(normalise(header.get(i)));
            if (key != null) {
                col.putIfAbsent(key, i);
            }
        }
        for (String required : List.of("ref", GIVEN_NAME)) {
            if (!col.containsKey(required)) {
                fileProblems.add("Missing column: " + (required.equals("ref") ? "admission number" : "first name") + ".");
            }
        }
        if (!fileProblems.isEmpty()) {
            return new Plan(List.of(), fileProblems, List.of());
        }

        Map<String, Integer> seen = new HashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (int r = 1; r < table.size(); r++) {
            String ref = cell(table.get(r), col.get("ref"));
            if (!ref.isEmpty() && seen.put(ref.toUpperCase(Locale.ROOT), r) != null) {
                duplicated.add(ref.toUpperCase(Locale.ROOT));
            }
        }

        List<RowPlan> rows = new ArrayList<>();
        Set<String> inFile = new HashSet<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> raw = table.get(r);
            int rowNo = r + 1;  // as spreadsheet tools number rows (header = 1)
            List<String> problems = new ArrayList<>();
            String ref = cell(raw, col.get("ref"));
            if (ref.isEmpty()) {
                problems.add("Admission number is missing.");
            } else if (!ref.matches("^[A-Za-z0-9/_-]{1,40}$")) {
                problems.add("Admission number may use letters, digits, / _ and - only.");
            } else if (duplicated.contains(ref.toUpperCase(Locale.ROOT))) {
                problems.add("Admission number " + ref + " appears more than once in this file.");
            }
            String given = clean(cell(raw, col.get(GIVEN_NAME)));
            if (given.isEmpty()) {
                problems.add("First name is missing.");
            } else if (given.length() > 80) {
                problems.add("First name is longer than 80 characters.");
            }
            String family = clean(cell(raw, col.get(FAMILY_NAME)));
            if (family.length() > 80) {
                problems.add("Last name is longer than 80 characters.");
            }
            String dob = "";
            String rawDob = cell(raw, col.get(DATE_OF_BIRTH));
            if (!rawDob.isEmpty()) {
                LocalDate d = parseDate(rawDob);
                if (d == null) {
                    problems.add("Date of birth \"" + rawDob + "\" is not a date (use DD/MM/YYYY or YYYY-MM-DD).");
                } else if (d.isAfter(today) || d.isBefore(today.minusYears(30))) {
                    problems.add("Date of birth " + d + " is not plausible for a school student.");
                } else {
                    dob = d.toString();
                }
            }
            String placement = resolvePlacement(cell(raw, col.get("programme")), cell(raw, col.get("grade")),
                cell(raw, col.get("section")), placements, problems);

            Map<String, String> incoming = new LinkedHashMap<>();
            incoming.put(GIVEN_NAME, given);
            incoming.put(FAMILY_NAME, family);
            incoming.put(DATE_OF_BIRTH, dob);
            incoming.put(PLACEMENT, placement);
            String key = ref.toUpperCase(Locale.ROOT);
            if (!problems.isEmpty()) {
                rows.add(new RowPlan(rowNo, ref, Action.ERROR, problems, List.of(), incoming, null));
                continue;
            }
            inFile.add(key);
            Linked link = linked.get(key);
            if (link == null) {
                if (unlinkedRefs.contains(key)) {
                    rows.add(new RowPlan(rowNo, ref, Action.CONFLICT, List.of("A student with admission number " + ref
                        + " already exists in Schoomano but was not imported. Link or rename it before importing."),
                        List.of(), incoming, null));
                } else {
                    List<FieldChange> changes = new ArrayList<>();
                    incoming.forEach((f, v) -> {
                        if (!v.isEmpty()) {
                            changes.add(new FieldChange(f, "", v));
                        }
                    });
                    rows.add(new RowPlan(rowNo, ref, Action.CREATE, List.of(), changes, incoming, null));
                }
                continue;
            }
            rows.add(compare(rowNo, ref, link, incoming));
        }

        List<String> missing = linked.keySet().stream().filter(k -> !inFile.contains(k)).sorted().toList();
        return new Plan(rows, fileProblems, missing);
    }

    private static RowPlan compare(int rowNo, String ref, Linked link, Map<String, String> incoming) {
        List<FieldChange> changes = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String f : FIELDS) {
            String inc = incoming.get(f);
            String cur = Objects.requireNonNullElse(link.current().get(f), "");
            String last = Objects.requireNonNullElse(link.last().get(f), "");
            if (inc.equals(cur)) {
                continue;
            }
            if (cur.equals(last)) {
                changes.add(new FieldChange(f, cur, inc));
            } else if (!inc.equals(last)) {
                problems.add(label(f) + " was changed in Schoomano (now \"" + cur + "\") and in the old system (now \""
                    + inc + "\"). Decide which is right and correct one of them.");
            }
            // inc == last and cur != last: Schoomano's own edit stands; nothing to do.
        }
        Action action = !problems.isEmpty() ? Action.CONFLICT : changes.isEmpty() ? Action.UNCHANGED : Action.UPDATE;
        return new RowPlan(rowNo, ref, action, problems, action == Action.CONFLICT ? List.of() : changes, incoming,
            link.personId());
    }

    static String resolvePlacement(String programme, String grade, String section, Collection<Placement> placements,
                                   List<String> problems) {
        if (programme.isEmpty() && grade.isEmpty() && section.isEmpty()) {
            return "";
        }
        if (programme.isEmpty() || grade.isEmpty() || section.isEmpty()) {
            problems.add("Give programme, grade and section together, or leave all three empty.");
            return "";
        }
        String g = normalise(grade);
        String gradeNumber = g.replaceFirst("^(grade|class|std|standard|g)", "");
        for (Placement p : placements) {
            boolean programmeMatches = p.programmeCode().equalsIgnoreCase(programme.trim());
            boolean gradeMatches = normalise(p.gradeCode()).equals(g) || normalise(p.gradeName()).equals(g)
                || gradeNumber.equals(String.valueOf(p.gradeOrdinal()));
            if (programmeMatches && gradeMatches && p.sectionCode().equalsIgnoreCase(section.trim())) {
                return p.canonical();
            }
        }
        problems.add("No section " + section.trim() + " in " + programme.trim() + " grade " + grade.trim()
            + " for this academic year.");
        return "";
    }

    static LocalDate parseDate(String s) {
        // Spreadsheet exports may carry dates as Excel serial numbers (days since 1899-12-30).
        if (s.trim().matches("^\\d{5}(\\.0+)?$")) {
            return LocalDate.of(1899, 12, 30).plusDays((long) Double.parseDouble(s.trim()));
        }
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(s.trim(), f);
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return null;
    }

    private static String label(String field) {
        return switch (field) {
            case GIVEN_NAME -> "First name";
            case FAMILY_NAME -> "Last name";
            case DATE_OF_BIRTH -> "Date of birth";
            default -> "Section";
        };
    }

    private static String cell(List<String> row, Integer index) {
        return index == null || index >= row.size() ? "" : row.get(index).trim();
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
