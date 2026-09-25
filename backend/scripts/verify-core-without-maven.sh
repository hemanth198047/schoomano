#!/usr/bin/env bash
# Compiles and tests schoomano-core with plain javac + the JUnit console launcher.
# Useful where Maven Central is unreachable. Normal path: `cd backend && mvn verify`.
# Requires: JDK 21 and a junit-platform-console-standalone jar (set JUNIT_JAR).
set -euo pipefail
cd "$(dirname "$0")/../schoomano-core"
JUNIT_JAR="${JUNIT_JAR:-/usr/share/java/junit-platform-console-standalone.jar}"
OUT="$(mktemp -d)"
find src/main -name '*.java' >"$OUT/main-sources.txt"
find src/test -name '*.java' >"$OUT/test-sources.txt"
javac -d "$OUT/main" -Xlint:all -Werror @"$OUT/main-sources.txt"
javac -d "$OUT/test" -cp "$OUT/main:$JUNIT_JAR" @"$OUT/test-sources.txt"
java -jar "$JUNIT_JAR" execute --class-path "$OUT/main:$OUT/test" --scan-class-path --disable-banner --details=summary
