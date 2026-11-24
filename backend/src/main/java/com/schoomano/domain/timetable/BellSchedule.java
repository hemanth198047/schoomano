package com.schoomano.domain.timetable;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A campus's bell schedule: numbered periods (and breaks) through the school day. Validated as a whole so the
 * office gets every problem at once, in plain words, instead of one database error at a time.
 */
public final class BellSchedule {

    public static final int MAX_PERIODS = 12;

    public record Slot(int ordinal, String label, LocalTime startsAt, LocalTime endsAt, boolean isBreak) { }

    private BellSchedule() {
    }

    /** User-readable problems; empty when the schedule is acceptable. */
    public static List<String> problems(List<Slot> slots) {
        List<String> out = new ArrayList<>();
        if (slots == null || slots.isEmpty()) {
            return List.of("Add at least one period.");
        }
        if (slots.size() > MAX_PERIODS) {
            out.add("A day can have at most " + MAX_PERIODS + " periods and breaks.");
        }
        Set<Integer> seen = new HashSet<>();
        for (Slot s : slots) {
            String name = s.label() == null || s.label().isBlank() ? "Period " + s.ordinal() : s.label();
            if (s.ordinal() < 1 || s.ordinal() > MAX_PERIODS) {
                out.add(name + ": the number must be between 1 and " + MAX_PERIODS + ".");
            }
            if (!seen.add(s.ordinal())) {
                out.add("Two entries share the number " + s.ordinal() + ".");
            }
            if (s.label() == null || s.label().isBlank() || s.label().length() > 40) {
                out.add("Period " + s.ordinal() + ": give it a name of up to 40 characters.");
            }
            if (s.startsAt() == null || s.endsAt() == null || !s.endsAt().isAfter(s.startsAt())) {
                out.add(name + ": it must end after it starts.");
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        List<Slot> byTime = slots.stream().sorted(Comparator.comparing(Slot::startsAt)).toList();
        for (int i = 1; i < byTime.size(); i++) {
            Slot a = byTime.get(i - 1);
            Slot b = byTime.get(i);
            if (b.startsAt().isBefore(a.endsAt())) {
                out.add(a.label() + " (" + a.startsAt() + "–" + a.endsAt() + ") overlaps " + b.label() + " ("
                    + b.startsAt() + "–" + b.endsAt() + ").");
            }
            if (b.ordinal() < a.ordinal()) {
                out.add(b.label() + " starts after " + a.label() + " but is numbered before it.");
            }
        }
        if (slots.stream().allMatch(Slot::isBreak)) {
            out.add("At least one entry must be a lesson period, not a break.");
        }
        return out;
    }
}
