# syntax=docker/dockerfile:1
# Schoomano backend: Spring Boot API. Tests run in CI (`mvn verify`); the image build skips them for speed.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package \
 && cp target/schoomano.jar /src/app.jar

FROM eclipse-temurin:21-jre
# curl is only for the container health check. Unpinned on purpose: exact apt pins break the build
# whenever Ubuntu ships a security update; we prefer receiving those patches.
# hadolint ignore=DL3008
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && groupadd --system schoomano && useradd --system --gid schoomano --home /app schoomano
WORKDIR /app
COPY --from=build /src/app.jar /app/app.jar
USER schoomano
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
