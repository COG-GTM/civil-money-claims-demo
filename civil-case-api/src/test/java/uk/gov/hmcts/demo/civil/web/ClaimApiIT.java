package uk.gov.hmcts.demo.civil.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import uk.gov.hmcts.demo.civil.document.*;
import uk.gov.hmcts.demo.civil.service.ClaimService;
import uk.gov.hmcts.demo.civil.workflow.ClaimWorkflow;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "camunda.bpm.enabled=false","spring.flyway.locations=classpath:db/migration/core",
    "demo.reset-enabled=true","demo.reset-token=integration-reset-token"})
@AutoConfigureMockMvc
class ClaimApiIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    static final Path RESET_SQL=createResetFile();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("demo.reset-sql",() -> RESET_SQL.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClaimService service;
    @Autowired TestRestTemplate http;
    @MockitoBean ClaimWorkflow workflow;
    @MockitoBean VirusScanner scanner;
    @MockitoBean Clock clock;

    @BeforeEach void setUp() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-03-20T12:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Europe/London"));
        jdbc.execute("truncate table civil.claims cascade");
        jdbc.execute("alter sequence civil.claim_reference_seq restart with 161");
        jdbc.update("insert into civil.bank_holidays(date,title) values ('2026-04-03','Good Friday'),('2026-04-06','Easter Monday') on conflict do nothing");
        Files.writeString(RESET_SQL,"delete from civil.claims; alter sequence civil.claim_reference_seq restart with 161;");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String reference=invocation.getArgument(0);
            assertThat(jdbc.queryForObject("select state from civil.claims where reference=?",String.class,reference)).isEqualTo("ISSUED");
            service.generateClaimForm(reference);
            service.markAwaitingResponse(reference);
            return null;
        }).when(workflow).issued(anyString(),any(LocalDate.class));
    }

    @Test void healthAndEmptyDraftAreRealAndRequestIdsPropagate() throws Exception {
        assertThat(http.getForObject("/health",JsonNode.class).path("status").asText()).isEqualTo("UP");
        mvc.perform(post("/api/claims").header("X-Request-ID","request-123").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated()).andExpect(header().string("X-Request-ID","request-123"))
            .andExpect(jsonPath("$.reference").value("000MC161")).andExpect(jsonPath("$.state").value("DRAFT"))
            .andExpect(jsonPath("$.securityCode").value(org.hamcrest.Matchers.matchesPattern("[A-Z0-9]{8}")))
            .andExpect(jsonPath("$.principalAmount").value(0)).andExpect(jsonPath("$.fee").value(0));
    }

    @Test void issueCommitsThenStartsWorkflowAndProducesAValidIdempotentPdf() throws Exception {
        JsonNode draft=createClaim();
        String ref=draft.path("reference").asText();
        JsonNode issued=issue(ref);
        assertThat(issued.path("state").asText()).isEqualTo("AWAITING_RESPONSE");
        assertThat(issued.path("serviceDate").asText()).isEqualTo("2026-03-25");
        assertThat(issued.path("responseDeadline").asText()).isEqualTo("2026-04-08");
        assertThat(issued.path("fee").decimalValue()).isEqualByComparingTo("70");
        verify(workflow).issued(ref,LocalDate.of(2026,4,8));
        service.generateClaimForm(ref);
        JsonNode documents=getJson("/api/claims/"+ref+"/documents");
        assertThat(documents.size()).isEqualTo(1);
        byte[] body=mvc.perform(get("/api/claims/"+ref+"/documents/"+documents.get(0).path("id").asText()))
            .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andReturn().getResponse().getContentAsByteArray();
        try(var pdf=Loader.loadPDF(body)) { assertThat(pdf.getNumberOfPages()).isEqualTo(1); }
        JsonNode history=getJson("/api/claims/"+ref+"/events");
        assertThat(history.findValuesAsText("type")).contains("CLAIM_CREATED","CLAIM_ISSUED","CLAIM_FORM_GENERATED","AWAITING_RESPONSE");
        mvc.perform(put("/api/claims/"+ref).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/claims/"+ref+"/issue").contentType(MediaType.APPLICATION_JSON).content(issueBody()))
            .andExpect(status().isConflict());
    }

    @Test void acceptsPartialDraftUpdatesAndRequiresCompleteDetailsOnIssue() throws Exception {
        String ref=json.readTree(mvc.perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("reference").asText();
        mvc.perform(post("/api/claims/"+ref+"/issue").contentType(MediaType.APPLICATION_JSON).content(issueBody()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['claimant.name']").exists());
        mvc.perform(put("/api/claims/"+ref).contentType(MediaType.APPLICATION_JSON).content(validClaim()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.principalAmount").value(1000));
        mvc.perform(put("/api/claims/"+ref).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Updated explanation\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.claimant.name").value("Alex Example"))
            .andExpect(jsonPath("$.reason").value("Updated explanation"));
        issue(ref);
    }

    @Test void quotesInterestBeforeApplyingFeeBandAndRejectsBadMoneyDatesAndRate() throws Exception {
        mvc.perform(post("/api/fees/quote").contentType(MediaType.APPLICATION_JSON).content("""
            {"amountBreakdown":[{"reason":"Debt","amount":300}],
             "interest":{"claimed":true,"rate":8,"startDate":"2025-03-20"},"calculationDate":"2026-03-20"}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.principalAmount").value(300))
            .andExpect(jsonPath("$.interestAmount").value(24)).andExpect(jsonPath("$.fee").value(50))
            .andExpect(jsonPath("$.dailyInterest").value(0.065753)).andExpect(jsonPath("$.totalAmount").value(374));
        for(String invalid:List.of(
            "{\"amountBreakdown\":[{\"reason\":\"Debt\",\"amount\":-1}]}",
            "{\"amountBreakdown\":[{\"reason\":\"Debt\",\"amount\":1.001}]}",
            "{\"amountBreakdown\":[{\"reason\":\"Debt\",\"amount\":0}]}",
            "{\"amountBreakdown\":[{\"reason\":\"Debt\",\"amount\":100}],\"interest\":{\"claimed\":true,\"startDate\":\"2027-01-01\"}}",
            "{\"amountBreakdown\":[{\"reason\":\"Debt\",\"amount\":100}],\"interest\":{\"claimed\":true,\"rate\":0.08,\"startDate\":\"2025-01-01\"}}")) {
            mvc.perform(post("/api/fees/quote").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.requestId").isNotEmpty());
        }
    }

    @Test void lookupNormalizesButDoesNotAcceptWrongCodeOrDraft() throws Exception {
        JsonNode claim=createClaim(); String ref=claim.path("reference").asText();
        String code=claim.path("securityCode").asText();
        mvc.perform(post("/api/claims/lookup").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reference",ref,"securityCode",code)))).andExpect(status().isForbidden());
        issue(ref);
        mvc.perform(post("/api/claims/lookup").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reference"," "+ref.toLowerCase(Locale.ROOT)+" ",
                "securityCode"," "+code.toLowerCase(Locale.ROOT)+" ")))).andExpect(status().isOk());
        mvc.perform(post("/api/claims/lookup").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reference",ref,"securityCode","WRONG123")))).andExpect(status().isForbidden());
    }

    @Test void acknowledgmentExtendsOnceFromOriginalServiceDate() throws Exception {
        String ref=createIssued();
        JsonNode acknowledged=postJson("/api/claims/"+ref+"/acknowledge","{}");
        assertThat(acknowledged.path("responseDeadline").asText()).isEqualTo("2026-05-06");
        assertThat(acknowledged.path("acknowledged").asBoolean()).isTrue();
        assertThat(postJson("/api/claims/"+ref+"/acknowledge","{}").path("responseDeadline").asText()).isEqualTo("2026-05-06");
        verify(workflow,times(2)).acknowledged(ref,LocalDate.of(2026,5,6));
        assertThat(getJson("/api/claims/"+ref+"/events").findValuesAsText("type").stream().filter("CLAIM_ACKNOWLEDGED"::equals)).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings={"ADMIT_ALL","ADMIT_PART","REJECT"})
    void responsesArePersistedAndNeverAutomaticallySettled(String type) throws Exception {
        String ref=createIssued();
        JsonNode response=postJson("/api/claims/"+ref+"/response",responseBody(type));
        assertThat(response.path("state").asText()).isEqualTo("RESPONDED");
        assertThat(response.path("response").path("type").asText()).isEqualTo(type);
        assertThat(response.path("response").path("admittedAmount").decimalValue())
            .isEqualByComparingTo(switch(type) { case "ADMIT_ALL" -> "1070"; case "ADMIT_PART" -> "100"; default -> "0"; });
        assertThat(response.path("response").path("submittedAt").asText()).isEqualTo("2026-03-20T12:00:00Z");
        verify(workflow).responded(ref);
        mvc.perform(post("/api/claims/"+ref+"/response").contentType(MediaType.APPLICATION_JSON).content(responseBody(type)))
            .andExpect(status().isConflict());
        JsonNode settled=postJson("/api/claims/"+ref+"/state","{\"state\":\"SETTLED\",\"reason\":\"Payment received\"}");
        assertThat(settled.path("state").asText()).isEqualTo("SETTLED");
        mvc.perform(post("/api/claims/"+ref+"/state").contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"TRANSFERRED\"}"))
            .andExpect(status().isConflict());
    }

    @Test void rejectsMissingTruthIncompletePartialAdmissionAndImpossiblePayments() throws Exception {
        String ref=createIssued();
        for(String invalid:List.of(
            "{\"type\":\"REJECT\",\"reason\":\"Not owed\",\"statementOfTruth\":false}",
            "{\"type\":\"ADMIT_ALL\"}",
            "{\"type\":\"ADMIT_PART\",\"reason\":\"Some owed\",\"statementOfTruth\":true}",
            "{\"type\":\"ADMIT_PART\",\"admittedAmount\":2000,\"reason\":\"Some owed\",\"statementOfTruth\":true}",
            "{\"type\":\"REJECT\",\"statementOfTruth\":true}",
            "{\"type\":\"ADMIT_ALL\",\"paymentOption\":\"BY_DATE\",\"paymentDate\":\"2025-01-01\",\"statementOfTruth\":true}",
            "{\"type\":\"ADMIT_ALL\",\"paymentOption\":\"INSTALMENTS\",\"statementOfTruth\":true}")) {
            mvc.perform(post("/api/claims/"+ref+"/response").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        }
        assertThat(getJson("/api/claims/"+ref).path("state").asText()).isEqualTo("AWAITING_RESPONSE");
    }

    @Test void defaultJudgmentRequiresStrictlyExpiredDateAndBlocksLaterResponse() throws Exception {
        String ref=createIssued();
        jdbc.update("update civil.claims set response_deadline='2026-03-20' where reference=?",ref);
        assertThat(getJson("/api/claims/"+ref).path("eligibleForDefaultJudgment").asBoolean()).isFalse();
        mvc.perform(post("/api/claims/"+ref+"/default-judgment").contentType(MediaType.APPLICATION_JSON)
            .content("{\"statementOfTruth\":true}")).andExpect(status().isConflict());
        jdbc.update("update civil.claims set response_deadline='2026-03-19' where reference=?",ref);
        service.markAwaitingDefaultJudgment(ref); service.markAwaitingDefaultJudgment(ref);
        assertThat(getJson("/api/claims/"+ref).path("eligibleForDefaultJudgment").asBoolean()).isTrue();
        JsonNode result=postJson("/api/claims/"+ref+"/default-judgment","{\"statementOfTruth\":true}");
        assertThat(result.path("state").asText()).isEqualTo("AWAITING_DEFAULT_JUDGMENT");
        assertThat(result.path("judgmentRequestedAt").asText()).isNotBlank();
        mvc.perform(post("/api/claims/"+ref+"/response").contentType(MediaType.APPLICATION_JSON).content(responseBody("REJECT")))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/claims/"+ref+"/default-judgment").contentType(MediaType.APPLICATION_JSON)
            .content("{\"statementOfTruth\":true}")).andExpect(status().isConflict());
    }

    @Test void permitsLateResponseBeforeJudgmentAndIgnoresStaleTimerCallbacks() throws Exception {
        String ref=createIssued();
        jdbc.update("update civil.claims set response_deadline='2026-03-19' where reference=?",ref);
        service.markAwaitingDefaultJudgment(ref);
        assertThat(postJson("/api/claims/"+ref+"/response",responseBody("REJECT")).path("state").asText()).isEqualTo("RESPONDED");
        service.markAwaitingDefaultJudgment(ref); service.markAwaitingResponse(ref);
        assertThat(getJson("/api/claims/"+ref).path("state").asText()).isEqualTo("RESPONDED");
        assertThat(service.activeClaims()).isEmpty();
    }

    @Test void uploadsRoundTripsAndDeletesDraftEvidenceWithClaimIsolation() throws Exception {
        String ref=createClaim().path("reference").asText();
        String other=createClaim().path("reference").asText();
        byte[] content="%PDF-1.7\nsynthetic-evidence\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        JsonNode uploaded=json.readTree(mvc.perform(multipart("/api/claims/"+ref+"/documents")
            .file(new MockMultipartFile("file","../../proof.pdf","application/pdf",content)).param("description","Receipt"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.fileName").value("proof.pdf"))
            .andExpect(jsonPath("$.scanStatus").value("CLEAN")).andReturn().getResponse().getContentAsString());
        String id=uploaded.path("id").asText();
        assertThat(mvc.perform(get("/api/claims/"+ref+"/documents/"+id)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray()).isEqualTo(content);
        assertThat(jdbc.queryForObject("select description from civil.documents where id=?::uuid",String.class,id)).isEqualTo("Receipt");
        mvc.perform(get("/api/claims/"+other+"/documents/"+id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/claims/"+other+"/documents/"+id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/claims/"+ref+"/documents/"+id)).andExpect(status().isNoContent());
        assertThat(getJson("/api/claims/"+ref+"/documents")).isEmpty();
        verify(scanner).scan(content);
    }

    @Test void rejectsUnsupportedMismatchedAndOversizedEvidence() throws Exception {
        String ref=createClaim().path("reference").asText();
        mvc.perform(multipart("/api/claims/"+ref+"/documents").file(new MockMultipartFile("file","fake.pdf","application/pdf","hello".getBytes())))
            .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/claims/"+ref+"/documents").file(new MockMultipartFile("file","fake.jpg","image/jpeg","%PDF-1.7".getBytes())))
            .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/claims/"+ref+"/documents").file(new MockMultipartFile("file","large.pdf","application/pdf",new byte[10*1024*1024+1])))
            .andExpect(status().isPayloadTooLarge());
        assertThat(getJson("/api/claims/"+ref+"/documents")).isEmpty();
    }

    @Test void actualHttpMultipartLimitReturnsContractError() throws Exception {
        String ref=createClaim().path("reference").asText();
        var multipart=new LinkedMultiValueMap<String,Object>();
        multipart.add("file",new ByteArrayResource(new byte[10*1024*1024+1]) {
            @Override public String getFilename() { return "large.pdf"; }
        });
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> response=http.postForEntity("/api/claims/"+ref+"/documents",new HttpEntity<>(multipart,headers),JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody().path("code").asText()).isEqualTo("FILE_TOO_LARGE");
        assertThat(response.getBody().path("requestId").asText()).isNotBlank();
    }

    @Test void notesFiltersAndPaginationUseTheFrozenShapes() throws Exception {
        String ref=createIssued(); createClaim();
        mvc.perform(post("/api/claims/"+ref+"/notes").header("X-Demo-User-Id","demo-caseworker")
            .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Reviewed evidence\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.author").value("demo-caseworker"));
        assertThat(getJson("/api/claims/"+ref+"/notes").get(0).path("text").asText()).isEqualTo("Reviewed evidence");
        JsonNode page=getJson("/api/claims?state=AWAITING_RESPONSE&claimantId=demo-claimant&from=2026-03-20&to=2026-03-20&page=0&size=1");
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("reference").asText()).isEqualTo(ref);
        mvc.perform(get("/api/claims?size=501")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/claims?from=2026-03-21&to=2026-03-20")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/claims/000MC99999")).andExpect(status().isNotFound());
    }

    @Test void concurrentCreatesHaveUniqueSequentialReferences() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<String>> calls=new ArrayList<>();
            for(int i=0;i<24;i++) calls.add(() -> createClaim().path("reference").asText());
            Set<String> references=new HashSet<>();
            for(Future<String> future:pool.invokeAll(calls)) references.add(future.get());
            assertThat(references).hasSize(24).contains("000MC161","000MC184");
        }
    }

    @Test void concurrentResponsesAreSerializedAndOnlyOneIsAccepted() throws Exception {
        String ref=createIssued();
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> respond=() -> mvc.perform(post("/api/claims/"+ref+"/response")
                .contentType(MediaType.APPLICATION_JSON).content(responseBody("REJECT"))).andReturn().getResponse().getStatus();
            List<Integer> statuses=new ArrayList<>();
            for(Future<Integer> future:pool.invokeAll(List.of(respond,respond))) statuses.add(future.get());
            assertThat(statuses).containsExactlyInAnyOrder(200,409);
        }
        assertThat(getJson("/api/claims/"+ref+"/events").findValuesAsText("type").stream().filter("DEFENDANT_RESPONSE"::equals)).hasSize(1);
    }

    @Test void unhandledErrorsAreSafeCorrelatedAndCountedInOps() throws Exception {
        String ref=createClaim().path("reference").asText();
        doThrow(new IllegalStateException("internal scanner failure")).when(scanner).scan(any(byte[].class));
        mvc.perform(multipart("/api/claims/"+ref+"/documents").file(new MockMultipartFile("file","proof.pdf","application/pdf","%PDF-1.7".getBytes()))
            .header("X-Request-ID","test-failure"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.requestId").value("test-failure"))
            .andExpect(jsonPath("$.message").value("Sorry, there is a problem with the service. Reference: test-failure"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal scanner"))));
        JsonNode metrics=getJson("/api/ops/metrics");
        assertThat(metrics.path("errorCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.path("latestErrors").get(0).path("requestId").asText()).isEqualTo("test-failure");
    }

    @Test void resetRequiresTokenAndCallsWorkflowAroundCommittedSql() throws Exception {
        createIssued();
        mvc.perform(post("/api/ops/reset").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ops/reset").header("X-Demo-Reset-Token","wrong").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from civil.claims",Integer.class)).isEqualTo(1);
        doAnswer(inv -> { assertThat(jdbc.queryForObject("select count(*) from civil.claims",Integer.class)).isZero(); return null; })
            .when(workflow).seedActiveClaims();
        mvc.perform(post("/api/ops/reset").header("X-Demo-Reset-Token","integration-reset-token").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("reset")).andExpect(jsonPath("$.claims").value(0));
        var order=inOrder(workflow); order.verify(workflow).reset(); order.verify(workflow).seedActiveClaims();
    }

    @Test void failedResetRollsBackAllBusinessChanges() throws Exception {
        createClaim();
        Files.writeString(RESET_SQL,"delete from civil.claims; select * from civil.no_such_table;");
        mvc.perform(post("/api/ops/reset").header("X-Demo-Reset-Token","integration-reset-token").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("select count(*) from civil.claims",Integer.class)).isEqualTo(1);
        verify(workflow,never()).seedActiveClaims();
    }

    private JsonNode createClaim() throws Exception {
        return json.readTree(mvc.perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content(validClaim()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private String createIssued() throws Exception {
        String ref=createClaim().path("reference").asText(); issue(ref); return ref;
    }
    private JsonNode issue(String ref) throws Exception { return postJson("/api/claims/"+ref+"/issue",issueBody()); }
    private JsonNode postJson(String path,String body) throws Exception {
        return json.readTree(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private String issueBody() { return "{\"paymentReference\":\"DEMO-PAY-123\",\"statementOfTruth\":true}"; }
    private String responseBody(String type) {
        return "{\"type\":\""+type+"\",\"admittedAmount\":100,\"reason\":\"Synthetic response\",\"paymentOption\":\"IMMEDIATELY\",\"statementOfTruth\":true}";
    }
    private String validClaim() {
        return """
            {"claimantId":"demo-claimant",
             "claimant":{"type":"INDIVIDUAL","name":"Alex Example","email":"alex@example.test",
                "address":{"line1":"1 Example Road","city":"London","postcode":"SW1A 1AA","country":"England"}},
             "defendant":{"type":"BUSINESS","name":"Example Repairs Ltd",
                "address":{"line1":"2 Sample Street","city":"Bristol","postcode":"BS1 1AA","country":"England"}},
             "amountBreakdown":[{"reason":"Unpaid invoice","amount":1000}],
             "interest":{"claimed":false,"rate":8},"reason":"An invoice remains unpaid",
             "timeline":[{"date":"2026-01-01","description":"Invoice sent"}]}
            """;
    }
    private static Path createResetFile() {
        try { return Files.createTempFile("civil-reset-test-",".sql"); }
        catch(java.io.IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
}
