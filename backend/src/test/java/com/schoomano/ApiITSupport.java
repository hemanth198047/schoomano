package com.schoomano;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared base for API tests: one PostgreSQL 16 container per test run (RLS on, unprivileged app role),
 * migrated with Flyway and loaded with the synthetic seeds. Requires Docker. Run from backend/: mvn verify
 */
@SpringBootTest(properties = "schoomano.auth.dev-mode=true")
@AutoConfigureMockMvc
abstract class ApiITSupport {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("schoomano").withUsername("schoomano_owner").withPassword("owner-test-only");

    static {
        PG.start();
        try {
            ownerSql("create role schoomano_app login password 'app-test-only'");
            Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
            // Seeds start with a psql-only re-run guard; JDBC executes the SQL from "begin;" onwards.
            for (String f : new String[] {"db/seed/dev_seed.sql", "db/seed/dev_seed_academic.sql"}) {
                String file = Files.readString(Path.of(f));
                ownerSql(file.substring(file.indexOf("\nbegin;") + 1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("test database setup failed", e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "schoomano_app");
        r.add("spring.datasource.password", () -> "app-test-only");
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
    }

    @Autowired
    MockMvc mvc;

    static String dev(String subject) {
        return "Bearer dev:" + subject;
    }

    /** As the schema owner (bypasses RLS): for assertions about what was or was not written. */
    static long ownerCount(String sql) throws Exception {
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var s = c.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String ownerString(String sql) throws Exception {
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var s = c.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void ownerSql(String sql) throws Exception {
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
