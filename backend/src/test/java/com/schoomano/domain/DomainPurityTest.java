package com.schoomano.domain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The rules that protect children (tenancy, access policy, configuration) stay plain Java:
 * no Spring, no JDBC, no web, no JSON library, and no dependency on the application layers.
 * This keeps them fast to test and impossible to bypass through infrastructure.
 * (It replaces the separate Maven module that previously enforced the same boundary.)
 */
class DomainPurityTest {

    private static final Path DOMAIN = Path.of("src/main/java/com/schoomano/domain");
    private static final Pattern FORBIDDEN = Pattern.compile(
        "^import\\s+(static\\s+)?(org\\.springframework|jakarta\\.|javax\\.|java\\.sql|com\\.fasterxml|org\\.slf4j"
            + "|com\\.schoomano\\.(api|platform|security))\\b.*", Pattern.MULTILINE);

    @Test
    void domainCodeHasNoFrameworkOrApplicationImports() throws IOException {
        assertTrue(Files.isDirectory(DOMAIN), "run from the backend directory: " + DOMAIN.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DOMAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var m = FORBIDDEN.matcher(Files.readString(f));
                while (m.find()) {
                    violations.add(DOMAIN.relativize(f) + ": " + m.group().trim());
                }
            }
        }
        assertTrue(violations.isEmpty(), "domain must stay framework-free:\n" + String.join("\n", violations));
    }
}
