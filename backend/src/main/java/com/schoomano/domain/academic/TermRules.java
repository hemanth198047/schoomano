package com.schoomano.domain.academic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Validates a proposed term against its academic year and the programme's other terms in that year.
 * The database enforces the same rules (exclusion constraint + trigger); this gives users precise
 * messages before the write and lets the rollover preview flag problems without writing anything.
 */
public final class TermRules {

    private TermRules() {
    }

    public record Term(String label, DateRange dates) { }

    public static List<String> problems(DateRange year, Collection<Term> existing, Term candidate) {
        List<String> out = new ArrayList<>();
        if (candidate.label() == null || candidate.label().isBlank()) {
            out.add("A term needs a label.");
        }
        if (!year.contains(candidate.dates())) {
            out.add("Term dates must lie within the academic year (" + year.startsOn() + " to " + year.endsOn() + ").");
        }
        for (Term t : existing) {
            if (t.label().equalsIgnoreCase(candidate.label())) {
                out.add("A term called \"" + t.label() + "\" already exists in this year.");
            }
            if (t.dates().overlaps(candidate.dates())) {
                out.add("Overlaps " + t.label() + " (" + t.dates().startsOn() + " to " + t.dates().endsOn() + ").");
            }
        }
        return List.copyOf(out);
    }
}
