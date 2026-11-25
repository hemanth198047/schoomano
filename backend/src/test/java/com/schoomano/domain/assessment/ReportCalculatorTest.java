package com.schoomano.domain.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoomano.domain.academic.GradeScale;
import com.schoomano.domain.assessment.ReportCalculator.Kind;
import com.schoomano.domain.assessment.ReportCalculator.Result;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportCalculatorTest {

    private static final GradeScale SCALE = new GradeScale(List.of(
            new GradeScale.Band("A", 90), new GradeScale.Band("B", 75),
            new GradeScale.Band("C", 50), new GradeScale.Band("D", 0)));
    private static final UUID ENG = UUID.nameUUIDFromBytes("eng".getBytes());
    private static final UUID MATH = UUID.nameUUIDFromBytes("math".getBytes());

    private static Result r(UUID subject, String name, String max, String weight, Kind kind, String marks) {
        return new Result(subject, name, UUID.randomUUID(), new BigDecimal(max), new BigDecimal(weight), kind,
                marks == null ? null : new BigDecimal(marks));
    }

    @Test
    void weightsDecideHowMuchEachAssessmentCounts() {
        var out = ReportCalculator.calculate(List.of(
                r(ENG, "English", "20", "40", Kind.SCORED, "15"),     // 75% at weight 40
                r(ENG, "English", "100", "60", Kind.SCORED, "90")),    // 90% at weight 60
                SCALE);
        assertThat(out.subjects()).hasSize(1);
        assertThat(out.subjects().get(0).percent()).isEqualByComparingTo("84.0");
        assertThat(out.subjects().get(0).grade()).isEqualTo("B");
        assertThat(out.overallPercent()).isEqualByComparingTo("84.0");
    }

    @Test
    void absentCountsAsZeroButExemptIsLeftOut() {
        var out = ReportCalculator.calculate(List.of(
                r(ENG, "English", "10", "50", Kind.SCORED, "10"),
                r(ENG, "English", "10", "50", Kind.ABSENT, null),
                r(MATH, "Maths", "10", "50", Kind.SCORED, "8"),
                r(MATH, "Maths", "10", "50", Kind.EXEMPT, null)),
                SCALE);
        assertThat(out.subjects().get(0).percent()).isEqualByComparingTo("50.0");
        assertThat(out.subjects().get(0).grade()).isEqualTo("C");
        assertThat(out.subjects().get(1).percent()).isEqualByComparingTo("80.0");
        assertThat(out.subjects().get(1).assessments()).isEqualTo(1);
        assertThat(out.overallPercent()).isEqualByComparingTo("65.0");
    }

    @Test
    void aSubjectOfOnlyExemptionsIsNotGradedAndNothingGradedGivesNull() {
        var out = ReportCalculator.calculate(List.of(
                r(ENG, "English", "10", "50", Kind.EXEMPT, null),
                r(MATH, "Maths", "10", "50", Kind.SCORED, "9")), SCALE);
        assertThat(out.subjects()).hasSize(1);
        assertThat(out.overallGrade()).isEqualTo("A");
        assertThat(ReportCalculator.calculate(List.of(r(ENG, "English", "10", "50", Kind.EXEMPT, null)), SCALE)).isNull();
    }

    @Test
    void roundsHalfUpToOneDecimalAndGradesTheRoundedFigure() {
        // 74.95% rounds to 75.0 and so earns B, not C.
        var out = ReportCalculator.calculate(List.of(r(ENG, "English", "2000", "100", Kind.SCORED, "1499")), SCALE);
        assertThat(out.subjects().get(0).percent()).isEqualByComparingTo("75.0");
        assertThat(out.subjects().get(0).grade()).isEqualTo("B");
        var third = ReportCalculator.calculate(List.of(r(ENG, "English", "3", "100", Kind.SCORED, "2")), SCALE);
        assertThat(third.subjects().get(0).percent()).isEqualByComparingTo("66.7");
    }

    @Test
    void fullMarksIsOneHundredAndNeverMore() {
        var out = ReportCalculator.calculate(List.of(r(ENG, "English", "30", "100", Kind.SCORED, "30")), SCALE);
        assertThat(out.overallPercent()).isEqualByComparingTo("100.0");
        assertThat(out.overallGrade()).isEqualTo("A");
    }
}
