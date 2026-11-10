package com.schoomano.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoomano.domain.academic.GradeScale;
import com.schoomano.domain.tenancy.ScopeLevel;
import com.schoomano.platform.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Shape checks for configuration keys the server understands. Unknown keys are accepted as any JSON
 * (flags and future settings); known keys are rejected with user-readable problems before a draft exists.
 */
final class ConfigValues {

    private ConfigValues() {
    }

    static void validate(String key, ScopeLevel level, JsonNode value) {
        List<String> problems = switch (key) {
            case "attendance.late_after_minutes" -> value.isInt() && value.asInt() >= 0 && value.asInt() <= 240
                ? List.of() : List.of("Use a whole number of minutes between 0 and 240.");
            case GradeScale.CONFIG_KEY -> gradeScaleProblems(level, value);
            default -> key.startsWith("flag.") && !value.isBoolean() ? List.of("Feature flags are true or false.") : List.of();
        };
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_value", "This value is not valid for " + key + ".",
                Map.of("problems", problems));
        }
    }

    private static List<String> gradeScaleProblems(ScopeLevel level, JsonNode value) {
        if (level != ScopeLevel.PROGRAMME) {
            return List.of("Grade scales are set per programme (CBSE, State board, ...).");
        }
        JsonNode bands = value.path("bands");
        if (!bands.isArray()) {
            return List.of("Expected {\"bands\": [{\"grade\": \"A1\", \"minPercent\": 91}, ...]}.");
        }
        List<GradeScale.Band> parsed = new ArrayList<>();
        for (JsonNode b : bands) {
            if (!b.path("grade").isTextual() || !b.path("minPercent").isInt()) {
                return List.of("Each band needs a text \"grade\" and a whole-number \"minPercent\".");
            }
            parsed.add(new GradeScale.Band(b.get("grade").asText(), b.get("minPercent").asInt()));
        }
        return GradeScale.problems(parsed);
    }
}
