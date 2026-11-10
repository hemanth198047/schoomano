package com.schoomano.domain.academic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A percentage-to-grade scale, stored as effective-dated config ('assessment.grade_scale') per programme,
 * so a mid-year change never alters grades already computed under the previous version.
 * Each band names the lowest whole percentage that still earns its grade; the lowest band starts at 0.
 */
public record GradeScale(List<Band> bands) {

    public static final String CONFIG_KEY = "assessment.grade_scale";

    public record Band(String grade, int minPercent) { }

    public GradeScale {
        List<String> problems = problems(bands);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        bands = bands.stream().sorted(Comparator.comparingInt(Band::minPercent).reversed()).toList();
    }

    /** Empty when the bands form a valid scale; otherwise user-readable reasons. */
    public static List<String> problems(List<Band> bands) {
        List<String> out = new ArrayList<>();
        if (bands == null || bands.size() < 2) {
            out.add("A grade scale needs at least two bands.");
            return out;
        }
        Set<String> grades = new HashSet<>();
        Set<Integer> mins = new HashSet<>();
        for (Band b : bands) {
            if (b.grade() == null || !b.grade().matches("^[A-Za-z0-9+\\-]{1,6}$")) {
                out.add("Grade labels are 1–6 letters, digits, + or -.");
            } else if (!grades.add(b.grade())) {
                out.add("Grade " + b.grade() + " appears twice.");
            }
            if (b.minPercent() < 0 || b.minPercent() > 100) {
                out.add("Minimum percentages must be between 0 and 100.");
            } else if (!mins.add(b.minPercent())) {
                out.add("Two bands start at " + b.minPercent() + "%.");
            }
        }
        if (!mins.contains(0)) {
            out.add("The lowest band must start at 0% so every score has a grade.");
        }
        return out;
    }

    public String gradeFor(double percent) {
        if (percent < 0 || percent > 100 || Double.isNaN(percent)) {
            throw new IllegalArgumentException("percent out of range: " + percent);
        }
        return bands.stream().filter(b -> percent >= b.minPercent()).findFirst().orElseThrow().grade();
    }
}
