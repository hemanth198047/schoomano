package com.schoomano;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B19: routes, stops and capacity set by the campus; boarding recorded by the attendant; guardians see their own child. */
class TransportApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String CAMPUS = "12000000-0000-4000-8000-000000000001";
    static final String YEAR = "15000000-0000-4000-8000-000000000001";
    static final String ANAYA = "13000000-0000-4000-8000-000000000020";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    private static void staff(String id, String subject, String role) throws Exception {
        ownerUpdate("""
            insert into person (id, tenant_id, school_ref, given_name, family_name) values ('13000000-0000-4000-8000-000000000%1$s', '%2$s', 'STF-IT-%1$s', '%3$s', 'Staff')
              on conflict (id) do nothing;
            insert into identity (tenant_id, person_id, issuer, subject, email)
              values ('%2$s', '13000000-0000-4000-8000-000000000%1$s', 'dev', '%3$s', '%3$s@example.invalid') on conflict do nothing;
            insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason)
              select '%2$s', '13000000-0000-4000-8000-000000000%1$s', '%4$s', 'CAMPUS', '%5$s', 'it'
               where not exists (select 1 from role_grant where person_id = '13000000-0000-4000-8000-000000000%1$s');
            """.formatted(id, TENANT, subject, role, CAMPUS));
    }

    private String create(String url, String who, String body) throws Exception {
        return mvc.perform(json(post(url), who, body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void campusSetsUpRoutesAttendantRecordsBoardingGuardianSeesOwnChild() throws Exception {
        staff("d01", "dev-tr-d01", "campus_admin");
        staff("d02", "dev-att-d02", "transport_attendant");
        String vehicles = "/api/v1/campuses/" + CAMPUS + "/vehicles";
        String routes = "/api/v1/campuses/" + CAMPUS + "/routes";

        // Setup is for the campus administrator only.
        mvc.perform(json(post(vehicles), "dev-att-d02", "{\"registration\":\"KA01IT0001\",\"capacity\":1}")).andExpect(status().isNotFound());
        mvc.perform(json(post(vehicles), "dev-meera", "{\"registration\":\"KA01IT0001\",\"capacity\":1}")).andExpect(status().isNotFound());
        mvc.perform(json(post(vehicles), "dev-tr-d01", "{\"registration\":\"KA1\",\"capacity\":1}")).andExpect(status().isBadRequest());
        String vehicle = JsonPath.read(create(vehicles, "dev-tr-d01", "{\"registration\":\"ka01it0001\",\"capacity\":1}"), "$.id");
        mvc.perform(json(post(vehicles), "dev-tr-d01", "{\"registration\":\"KA01IT0001\",\"capacity\":5}")).andExpect(status().isConflict());
        mvc.perform(json(post(routes), "dev-tr-d01", "{\"name\":\"IT bad\",\"vehicleId\":\"" + ANAYA + "\",\"driverName\":\"Ramesh\"}")).andExpect(status().isBadRequest());
        String route = JsonPath.read(create(routes, "dev-tr-d01", "{\"name\":\"IT Route North\",\"vehicleId\":\"" + vehicle
            + "\",\"driverName\":\"Ramesh Driver\",\"driverPhone\":\"9812345678\"}"), "$.id");
        mvc.perform(json(post(routes), "dev-tr-d01", "{\"name\":\"IT Route North\",\"driverName\":\"Other\"}")).andExpect(status().isConflict());
        String stops = "/api/v1/routes/" + route + "/stops";
        mvc.perform(json(post(stops), "dev-att-d02", "{\"name\":\"Lake Gate\",\"pickupTime\":\"07:10\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(stops), "dev-tr-d01", "{\"name\":\"Lake Gate\",\"pickupTime\":\"07:10\"}")).andExpect(status().isCreated());
        String stopBody = create(stops, "dev-tr-d01", "{\"name\":\"Market Corner\",\"pickupTime\":\"07:25\"}");
        String stop = JsonPath.read(stopBody, "$.stops[1].id");
        mvc.perform(as(get(routes), "dev-att-d02")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + route + "')].stops.length()").value(hasItem(2)))
            .andExpect(jsonPath("$[?(@.id=='" + route + "')].capacity").value(hasItem(1)));
        mvc.perform(as(get(routes), "dev-meera")).andExpect(status().isNotFound());

        // Assigning: within capacity, stop on the route, one route per child.
        String assign = "/api/v1/academic-years/" + YEAR + "/transport-assignments";
        mvc.perform(json(post(assign), "dev-att-d02", "{\"studentId\":\"" + ANAYA + "\",\"routeId\":\"" + route + "\",\"stopId\":\"" + stop + "\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(json(post(assign), "dev-tr-d01", "{\"studentId\":\"13000000-0000-4000-8000-000000000004\",\"routeId\":\"" + route + "\",\"stopId\":\"" + stop + "\"}"))
            .andExpect(status().isBadRequest());
        String assignment = JsonPath.read(create(assign, "dev-tr-d01", "{\"studentId\":\"" + ANAYA + "\",\"routeId\":\"" + route + "\",\"stopId\":\"" + stop + "\"}"), "$.id");
        mvc.perform(json(post(assign), "dev-tr-d01", "{\"studentId\":\"" + ANAYA + "\",\"routeId\":\"" + route + "\",\"stopId\":\"" + stop + "\"}"))
            .andExpect(status().isConflict());

        // The attendant sees the roster and records boarding; a retry is harmless; strangers are refused.
        String board = "/api/v1/routes/" + route + "/boarding";
        String boarded = "{\"studentId\":\"" + ANAYA + "\",\"trip\":\"MORNING\",\"event\":\"BOARDED\"}";
        mvc.perform(as(get("/api/v1/routes/" + route + "/roster"), "dev-att-d02")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].studentId").value(ANAYA)).andExpect(jsonPath("$[0].stop").value("Market Corner"))
            .andExpect(jsonPath("$[0].today.length()").value(0));
        mvc.perform(json(post(board), "dev-meera", boarded)).andExpect(status().isNotFound());
        mvc.perform(json(post(board), "dev-other", boarded)).andExpect(status().isNotFound());
        mvc.perform(json(post(board), "dev-att-d02", boarded.replace(ANAYA, "13000000-0000-4000-8000-000000000021"))).andExpect(status().isBadRequest());
        mvc.perform(json(post(board), "dev-att-d02", boarded.replace("}", ",\"tripDate\":\"2999-01-01\"}"))).andExpect(status().isBadRequest());
        mvc.perform(json(post(board), "dev-att-d02", boarded)).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(json(post(board), "dev-att-d02", boarded)).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertEquals(1, ownerCount("select count(*) from outbox_event where event_type = 'transport.boarded' and aggregate_id = '" + ANAYA + "'"));

        // Guardians see their own child's route and today's boarding; the restricted guardian and other schools do not.
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/transport"), "dev-meera")).andExpect(status().isOk())
            .andExpect(jsonPath("$.route").value("IT Route North")).andExpect(jsonPath("$.stop").value("Market Corner"))
            .andExpect(jsonPath("$.driverName").value("Ramesh Driver")).andExpect(jsonPath("$.vehicle").value("KA01IT0001"))
            .andExpect(jsonPath("$.today[0].event").value("BOARDED"));
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/transport"), "dev-karan")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/transport"), "dev-other")).andExpect(status().isNotFound());

        // Ending the assignment stops boarding and frees the seat.
        mvc.perform(post("/api/v1/transport-assignments/" + assignment + "/end").header("Authorization", dev("dev-att-d02"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/transport-assignments/" + assignment + "/end").header("Authorization", dev("dev-tr-d01"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/transport-assignments/" + assignment + "/end").header("Authorization", dev("dev-tr-d01"))).andExpect(status().isConflict());
        mvc.perform(json(post(board), "dev-att-d02", boarded.replace("MORNING", "AFTERNOON"))).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/transport"), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(json(post(assign), "dev-tr-d01", "{\"studentId\":\"" + ANAYA + "\",\"routeId\":\"" + route + "\",\"stopId\":\"" + stop + "\"}"))
            .andExpect(status().isCreated());
    }
}
