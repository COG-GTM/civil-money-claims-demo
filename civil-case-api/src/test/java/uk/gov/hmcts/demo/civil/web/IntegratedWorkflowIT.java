package uk.gov.hmcts.demo.civil.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the merged domain, real BPMN delegates, PDF storage and seed reset without mocks. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"camunda.bpm.job-execution.enabled=false",
    "demo.reset-enabled=true", "demo.reset-token=integrated-reset"})
@AutoConfigureMockMvc
class IntegratedWorkflowIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final Path RESET_SQL = resetFile();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("demo.reset-sql", () -> RESET_SQL.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RuntimeService runtime;
    @Autowired ManagementService management;
    @Autowired JdbcTemplate jdbc;

    @Test void realIssueResponseAndRepeatedReset() throws Exception {
        JsonNode claim = json.readTree(mvc.perform(post("/api/claims")
            .contentType(MediaType.APPLICATION_JSON).content("""
            {"claimantId":"demo-claimant",
             "claimant":{"type":"INDIVIDUAL","name":"Alex Example",
               "address":{"line1":"1 Example Road","city":"London","postcode":"SW1A 1AA"}},
             "defendant":{"type":"BUSINESS","name":"Example Repairs Ltd",
               "address":{"line1":"2 Sample Street","city":"Bristol","postcode":"BS1 1AA"}},
             "amountBreakdown":[{"reason":"Unpaid invoice","amount":1000}],
             "interest":{"claimed":false},"reason":"An invoice remains unpaid",
             "timeline":[{"date":"2026-01-01","description":"Invoice sent"}]}
            """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String ref = claim.path("reference").asText();
        mvc.perform(post("/api/claims/" + ref + "/issue").contentType(MediaType.APPLICATION_JSON)
            .content("{\"paymentReference\":\"DEMO-PAY-INTEGRATED\",\"statementOfTruth\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("AWAITING_RESPONSE"));
        var process = runtime.createProcessInstanceQuery().processInstanceBusinessKey(ref).singleResult();
        assertThat(process).isNotNull();
        assertThat(management.createJobQuery().processInstanceId(process.getId()).timers().count()).isEqualTo(1);
        JsonNode docs = json.readTree(mvc.perform(get("/api/claims/" + ref + "/documents"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(docs.size()).isEqualTo(1);
        byte[] pdf = mvc.perform(get("/api/claims/" + ref + "/documents/" + docs.get(0).path("id").asText()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var document = Loader.loadPDF(pdf)) { assertThat(document.getNumberOfPages()).isEqualTo(1); }
        mvc.perform(post("/api/claims/" + ref + "/response").contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"ADMIT_ALL\",\"paymentOption\":\"IMMEDIATELY\",\"statementOfTruth\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RESPONDED"));
        assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(ref).count()).isZero();
        assertThat(management.createJobQuery().processInstanceId(process.getId()).count()).isZero();
        mvc.perform(get("/api/claims/" + ref + "/events")).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].type", org.hamcrest.Matchers.hasItem("DEFENDANT_RESPONSE")));
        Files.writeString(RESET_SQL, Files.readString(Path.of("../seed/modern/reset.sql")));
        for (int round = 0; round < 2; round++) {
            reset().andExpect(status().isOk()).andExpect(jsonPath("$.claims").value(160));
            mvc.perform(get("/api/claims/000MC001")).andExpect(jsonPath("$.state").value("AWAITING_RESPONSE"));
            assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey("000MC001").count()).isEqualTo(1);
        }
    }

    @Test void failedResetPreservesBothBusinessRowsAndCamundaState() throws Exception {
        Files.writeString(RESET_SQL, "delete from civil.claims; select * from civil.missing_reset_table;");
        long processes = runtime.createProcessInstanceQuery().count();
        long timers = management.createJobQuery().timers().count();
        int claims = jdbc.queryForObject("select count(*) from civil.claims", Integer.class);
        assertThat(processes).isPositive();
        reset().andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("select count(*) from civil.claims", Integer.class)).isEqualTo(claims);
        assertThat(runtime.createProcessInstanceQuery().count()).isEqualTo(processes);
        assertThat(management.createJobQuery().timers().count()).isEqualTo(timers);
    }

    private org.springframework.test.web.servlet.ResultActions reset() throws Exception {
        return mvc.perform(post("/api/ops/reset").header("X-Demo-Reset-Token", "integrated-reset")
            .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }
    private static Path resetFile() {
        try { return Files.createTempFile("integrated-reset-", ".sql"); }
        catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
}
