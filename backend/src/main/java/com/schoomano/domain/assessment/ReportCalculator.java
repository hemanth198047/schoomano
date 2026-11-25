package com.schoomano.domain.assessment;

import com.schoomano.domain.academic.GradeScale;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns one student's assessment results for a term into subject percentages, grades and an overall result.
 * Rules (stated on the report card so a parent can check them):
 * <ul>
 *   <li>An absent student scores zero; an exempt assessment is left out of the subject entirely.</li>
 *   <li>A subject's percentage is the weight-averaged (marks ÷ maximum) of its non-exempt assessments.</li>
 *   <li>The overall percentage is the plain average of the subject percentages as shown (one decimal).</li>
 *   <li>Percentages are rounded half-up to one decimal, and graded on that rounded figure.</li>
 * </ul>
 */
public final class ReportCalculator {

    public enum Kind { SCORED, ABSENT, EXEMPT }

    public record Result(UUID subjectId, String subjectName, UUID assessmentId, BigDecimal maxMarks,
                         BigDecimal weightPercent, Kind kind, BigDecimal marks) {
        public Result {
            if (kind == Kind.SCORED && marks == null) {
                throw new IllegalArgumentException("A scored result needs marks.");
            }
            if (maxMarks.signum() <= 0 || weightPercent.signum() <= 0) {
                throw new IllegalArgumentException("Maximum marks and weight must be positive.");
            }
        }
    }

    public record SubjectResult(UUID subjectId, String subjectName, BigDecimal percent, String grade, int assessments) { }

    public record Outcome(List<SubjectResult> subjects, BigDecimal overallPercent, String overallGrade) { }

    private ReportCalculator() { }

    /** @return null when nothing could be graded (no subject has a non-exempt result). */
    public static Outcome calculate(List<Result> results, GradeScale scale) {
        Map<UUID, List<Result>> bySubject = new LinkedHashMap<>();
        for (Result r : results) {
            bySubject.computeIfAbsent(r.subjectId(), k -> new ArrayList<>()).add(r);
        }
        List<SubjectResult> subjects = new ArrayList<>();
        for (List<Result> rs : bySubject.values()) {
            BigDecimal weighted = BigDecimal.ZERO;
            BigDecimal weights = BigDecimal.ZERO;
            int counted = 0;
            for (Result r : rs) {
                if (r.kind() == Kind.EXEMPT) {
                    continue;
                }
                BigDecimal got = r.kind() == Kind.ABSENT ? BigDecimal.ZERO : r.marks();
                weighted = weighted.add(got.divide(r.maxMarks(), MathContext.DECIMAL128).multiply(r.weightPercent()));
                weights = weights.add(r.weightPercent());
                counted++;
            }
            if (counted == 0) {
                continue;
            }
            BigDecimal pct = round1(weighted.divide(weights, MathContext.DECIMAL128).multiply(BigDecimal.valueOf(100)));
            subjects.add(new SubjectResult(rs.get(0).subjectId(), rs.get(0).subjectName(), pct,
                    scale.gradeFor(pct.doubleValue()), counted));
        }
        if (subjects.isEmpty()) {
            return null;
        }
        BigDecimal sum = subjects.stream().map(SubjectResult::percent).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overall = round1(sum.divide(BigDecimal.valueOf(subjects.size()), MathContext.DECIMAL128));
        return new Outcome(List.copyOf(subjects), overall, scale.gradeFor(overall.doubleValue()));
    }

    private static BigDecimal round1(BigDecimal v) {
        BigDecimal r = v.setScale(1, RoundingMode.HALF_UP);
        return r.compareTo(BigDecimal.valueOf(100)) > 0 ? BigDecimal.valueOf(1000, 1) : r;
    }
}
