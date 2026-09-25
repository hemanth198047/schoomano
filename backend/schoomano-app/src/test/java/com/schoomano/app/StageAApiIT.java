package com.schoomano.app;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stage A acceptance through the real HTTP stack, real PostgreSQL (RLS on) and the unprivileged app role.
 * Requires Docker. Run: ./mvnw -pl schoomano-app verify   (or mvn -pl schoomano-app verify)
 */
@Testcontainers
@SpringBootTest(properties = "schoomano.auth.dev-mode=true")
@AutoConfigureMockMvc
class StageAApiIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("schoomano").withUsername("schoomano_owner").withPassword("owner-test-only");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "schoomano_app");
        r.add("spring.datasource.password", () -> "app-test-only");
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
    }

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var s = c.createStatement()) {
            s.execute("create role schoomano_app login password 'app-test-only'");
        }
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        // The seed starts with a psql-only re-run guard; JDBC executes the SQL from "begin;" onwards.
        String file = Files.readString(Path.of("../db/seed/dev_seed.sql"));
        String seed = file.substring(file.indexOf("\nbegin;") + 1);
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var s = c.createStatement()) {
            s.execute(seed);
        }
    }

    @Autowired
    MockMvc mvc;

    private static String dev(String subject) {
        return "Bearer dev:" + subject;
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyResponseCarriesACorrelationId() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void guardianWithTwoChildrenOnTwoCampusesSeesExactlyThoseChildren() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", dev("dev-meera")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.children", hasSize(2)))
            .andExpect(jsonPath("$.children[*].campusName", containsInAnyOrder("North Campus", "South Campus")));
    }

    @Test
    void separatedGuardianSeesNoticeCapabilityOnly() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", dev("dev-karan")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.children", hasSize(1)))
            .andExpect(jsonPath("$.children[0].capabilities", containsInAnyOrder("RECEIVE_NOTICES")));
    }

    @Test
    void otherTenantNeverSeesTenantACampuses() throws Exception {
        mvc.perform(get("/api/v1/campuses").header("Authorization", dev("dev-other")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].code", not(hasItem("BLR-N"))))
            .andExpect(jsonPath("$[*].code", not(hasItem("BLR-S"))));
    }

    @Test
    void clientCannotSelectATenantItDoesNotBelongTo() throws Exception {
        mvc.perform(get("/api/v1/campuses").header("Authorization", dev("dev-other"))
                .header("X-Schoomano-Tenant", "demo-group"))
            .andExpect(status().isConflict());
    }

    @Test
    void guardianSeesNoCampusDirectory() throws Exception {
        mvc.perform(get("/api/v1/campuses").header("Authorization", dev("dev-meera")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void teacherCannotDraftConfiguration() throws Exception {
        mvc.perform(post("/api/v1/config/attendance.late_after_minutes/drafts")
                .header("Authorization", dev("dev-ravi")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"scopeLevel":"GROUP","scopeId":"10000000-0000-4000-8000-000000000001","value":1,
                     "effectiveFrom":"2027-01-01T00:00:00+05:30","changeNote":"attempt"}"""))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminDraftsPreviewsAndPublishesWithoutRewritingHistory() throws Exception {
        String created = mvc.perform(post("/api/v1/config/attendance.late_after_minutes/drafts")
                .header("Authorization", dev("dev-asha")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"scopeLevel":"GROUP","scopeId":"10000000-0000-4000-8000-000000000001","value":5,
                     "effectiveFrom":"2026-12-01T00:00:00+05:30","changeNote":"Stricter from term 3"}"""))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        // South keeps its campus override; North changes.
        mvc.perform(post("/api/v1/config/drafts/" + id + "/preview").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.campusName=='North Campus')].changed").value(true))
            .andExpect(jsonPath("$[?(@.campusName=='South Campus')].changed").value(false));

        mvc.perform(post("/api/v1/config/drafts/" + id + "/publish").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"));

        String north = "12000000-0000-4000-8000-000000000001";
        mvc.perform(get("/api/v1/config/attendance.late_after_minutes").header("Authorization", dev("dev-asha"))
                .param("campusId", north).param("asOf", "2026-09-01T09:00:00+05:30"))
            .andExpect(jsonPath("$.value").value(10));
        mvc.perform(get("/api/v1/config/attendance.late_after_minutes").header("Authorization", dev("dev-asha"))
                .param("campusId", north).param("asOf", "2026-12-15T09:00:00+05:30"))
            .andExpect(jsonPath("$.value").value(5));

        // Publishing the same draft twice is refused.
        mvc.perform(post("/api/v1/config/drafts/" + id + "/publish").header("Authorization", dev("dev-asha")))
            .andExpect(status().isConflict());
    }
}
