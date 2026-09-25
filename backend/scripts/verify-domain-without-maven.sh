#!/usr/bin/env bash
# Compiles and tests the plain-Java domain rules (com.schoomano.domain) with javac + the JUnit console
# launcher, for environments where Maven Central is unreachable. Normal path: `mvn verify` in backend/.
# Requires JDK 21 and junit-platform-console-standalone (set JUNIT_JAR).
set -euo pipefail
cd "$(dirname "$0")/.."
JUNIT_JAR="${JUNIT_JAR:-/usr/share/java/junit-platform-console-standalone.jar}"
OUT="$(mktemp -d)"
find src/main/java/com/schoomano/domain -name '*.java' >"$OUT/main-sources.txt"
find src/test/java/com/schoomano/domain -name '*.java' >"$OUT/test-sources.txt"
javac -d "$OUT/main" -Xlint:all -Werror @"$OUT/main-sources.txt"
javac -d "$OUT/test" -cp "$OUT/main:$JUNIT_JAR" @"$OUT/test-sources.txt"
java -jar "$JUNIT_JAR" execute --class-path "$OUT/main:$OUT/test" --scan-class-path --disable-banner --details=summary
