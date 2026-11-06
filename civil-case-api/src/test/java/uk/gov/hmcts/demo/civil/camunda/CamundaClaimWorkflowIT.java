package uk.gov.hmcts.demo.civil.camunda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.test.assertions.bpmn.BpmnAwareTests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.demo.civil.web.HealthController;
import uk.gov.hmcts.demo.civil.workflow.ClaimWorkflow;
import uk.gov.hmcts.demo.civil.workflow.WorkflowCasePort;

/** Tests the real BPMN, Spring transaction boundary and registry on the production database engine. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = CamundaClaimWorkflowIT.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "camunda.bpm.job-execution.enabled=false",
        "spring.flyway.locations=classpath:db/migration/core,classpath:db/migration/camunda"
    })
class CamundaClaimWorkflowIT {
    private static final String REFERENCE = "000MC001";
    private static final LocalDate DEADLINE = LocalDate.of(2030, 7, 9);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("claims").withUsername("claims").withPassword("claims");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({CamundaClaimWorkflow.class, ClaimWorkflowStartup.class, ClaimNotifications.class,
        GenerateClaimFormDelegate.class, NotifyDefendantDelegate.class, MarkAwaitingResponseDelegate.class,
        NotifyClaimantDelegate.class, MarkAwaitingDefaultJudgmentDelegate.class, HealthController.class})
    static class Application {}

    @Autowired ClaimWorkflow workflow;
    @Autowired ClaimWorkflowStartup startup;
    @Autowired ProcessEngine engine;
    @Autowired RuntimeService runtime;
    @Autowired ManagementService management;
    @Autowired RepositoryService repository;
    @Autowired IdentityService identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @MockitoBean WorkflowCasePort cases;
    @MockitoSpyBean ClaimNotifications notifications;

    @BeforeEach
    void cleanProcesses() {
        workflow.reset();
        reset(cases);
        clearInvocations(notifications);
        BpmnAwareTests.init(engine);
    }

    @Test
    void issueGeneratesFormNotifiesAndWaitsUntilAfterFinalResponseDate() {
        workflow.issued(REFERENCE, DEADLINE);
        ProcessInstance instance = instance(REFERENCE);

        BpmnAwareTests.assertThat(instance).isStarted().hasBusinessKey(REFERENCE)
            .hasProcessDefinitionKey(ClaimProcess.KEY)
            .hasPassedInOrder("claimIssued", "generateClaimForm", "notifyDefendant", "markAwaitingResponse")
            .isWaitingAt("awaitResponse");
        assertThat(runtime.createEventSubscriptionQuery().processInstanceId(instance.getId())
            .eventName(ClaimProcess.RESPONSE).count()).isEqualTo(1);
        BpmnAwareTests.assertThat(instance).variables()
            .containsEntry("claimReference", REFERENCE)
            .containsEntry("responseDeadline", "2030-07-09")
            .containsEntry("responseDeadlineAt", "2030-07-10T00:00:00+01:00");
        assertThat(timer(instance).getDuedate().toInstant()).isEqualTo(Instant.parse("2030-07-09T23:00:00Z"));
        var order = inOrder(cases, notifications);
        order.verify(cases).generateClaimForm(REFERENCE);
        order.verify(notifications).defendantNotified(REFERENCE);
        order.verify(cases).markAwaitingResponse(REFERENCE);
        verify(cases, never()).markAwaitingDefaultJudgment(anyString());
    }

    @Test
    void responseCancelsOnlyItsTimerAndNotifiesClaimantExactlyOnce() {
        workflow.issued(REFERENCE, DEADLINE);
        workflow.issued("000MC002", DEADLINE);
        ProcessInstance instance = instance(REFERENCE);

        workflow.responded(REFERENCE);
        workflow.responded(REFERENCE);

        BpmnAwareTests.assertThat(instance).isEnded().hasPassed("responseReceived", "notifyClaimant", "responded")
            .hasNotPassed("markAwaitingDefaultJudgment");
        assertThat(management.createJobQuery().processInstanceId(instance.getId()).count()).isZero();
        assertThat(runtime.createEventSubscriptionQuery().processInstanceId(instance.getId()).count()).isZero();
        assertThat(timer(instance("000MC002"))).isNotNull();
        verify(notifications).claimantNotified(REFERENCE);
        verify(cases, never()).markAwaitingDefaultJudgment(anyString());
    }

    @Test
    void deadlineEndsWorkflowAndRemovesResponseSubscription() {
        workflow.issued(REFERENCE, DEADLINE);
        ProcessInstance instance = instance(REFERENCE);
        management.executeJob(timer(instance).getId());

        BpmnAwareTests.assertThat(instance).isEnded()
            .hasPassed("responseDeadlineReached", "markAwaitingDefaultJudgment", "awaitingDefaultJudgment")
            .hasNotPassed("notifyClaimant", "responded");
        verify(cases).markAwaitingDefaultJudgment(REFERENCE);
        assertThat(runtime.createEventSubscriptionQuery().processInstanceId(instance.getId()).count()).isZero();
        verify(notifications, never()).claimantNotified(anyString());
    }

    @Test
    void jobExecutorAutomaticallyExpiresAnOverdueClaim() {
        var executor = ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getJobExecutor();
        executor.start();
        try {
            workflow.issued(REFERENCE, LocalDate.now(ClaimProcess.COURT_ZONE).minusDays(1));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(REFERENCE).count()).isZero());
            verify(cases).markAwaitingDefaultJudgment(REFERENCE);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void acknowledgmentReschedulesTheExistingTimerAcrossDstWithoutRegeneratingForm() {
        workflow.issued(REFERENCE, LocalDate.of(2026, 10, 24));
        ProcessInstance instance = instance(REFERENCE);
        Job original = timer(instance);
        assertThat(original.getDuedate().toInstant()).isEqualTo(Instant.parse("2026-10-24T23:00:00Z"));

        workflow.acknowledged(REFERENCE, LocalDate.of(2026, 11, 23));
        workflow.acknowledged(REFERENCE, LocalDate.of(2026, 11, 23));

        assertThat(timer(instance).getId()).isEqualTo(original.getId());
        assertThat(timer(instance).getDuedate().toInstant()).isEqualTo(Instant.parse("2026-11-24T00:00:00Z"));
        assertThat(runtime.getVariable(instance.getId(), ClaimProcess.DEADLINE)).isEqualTo("2026-11-23");
        assertThat(runtime.getVariable(instance.getId(), ClaimProcess.DEADLINE_AT)).isEqualTo("2026-11-24T00:00:00Z");
        verify(cases).generateClaimForm(REFERENCE);
        verify(notifications).defendantNotified(REFERENCE);
        workflow.responded(REFERENCE);
        BpmnAwareTests.assertThat(instance).isEnded().hasPassed("responded");
    }

    @Test
    void duplicateIssueCannotRestartCompletedClaimEvenAfterHistoryCleanup() {
        workflow.issued(REFERENCE, DEADLINE);
        workflow.issued(REFERENCE, DEADLINE);
        ProcessInstance original = instance(REFERENCE);
        workflow.responded(REFERENCE);
        engine.getHistoryService().deleteHistoricProcessInstance(original.getId());

        workflow.issued(REFERENCE, DEADLINE);
        workflow.acknowledged(REFERENCE, DEADLINE.plusDays(28));

        assertThat(runtime.createProcessInstanceQuery().count()).isZero();
        verify(cases).generateClaimForm(REFERENCE);
        verify(notifications).claimantNotified(REFERENCE);
    }

    @Test
    void parallelDuplicateIssuesProduceOneProcess() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            Callable<Void> issue = () -> { workflow.issued(REFERENCE, DEADLINE); return null; };
            for (var future : pool.invokeAll(List.of(issue, issue, issue, issue))) {
                future.get(20, TimeUnit.SECONDS);
            }
        }
        assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(REFERENCE).count()).isEqualTo(1);
        verify(cases).generateClaimForm(REFERENCE);
        verify(notifications).defendantNotified(REFERENCE);
    }

    @Test
    void failedGenerationRollsBackRegistryAndEngineSoIssueCanRetry() {
        doThrow(new IllegalStateException("PDF store unavailable")).when(cases).generateClaimForm(REFERENCE);
        assertThatThrownBy(() -> workflow.issued(REFERENCE, DEADLINE)).hasMessageContaining("PDF store unavailable");
        assertThat(runtime.createProcessInstanceQuery().count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM civil.claim_workflows", Long.class)).isZero();

        doNothing().when(cases).generateClaimForm(REFERENCE);
        workflow.issued(REFERENCE, DEADLINE);
        assertThat(instance(REFERENCE)).isNotNull();
        verify(cases, times(2)).generateClaimForm(REFERENCE);
    }

    @Test
    void failedResponseNotificationRollsBackTimerCancellationAndCanRetry() {
        workflow.issued(REFERENCE, DEADLINE);
        ProcessInstance instance = instance(REFERENCE);
        doThrow(new IllegalStateException("Notification stub unavailable")).doCallRealMethod()
            .when(notifications).claimantNotified(REFERENCE);

        assertThatThrownBy(() -> workflow.responded(REFERENCE)).hasMessageContaining("Notification stub unavailable");
        assertThat(timer(instance)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT response_received FROM civil.claim_workflows WHERE reference = ?",
            Boolean.class, REFERENCE)).isFalse();
        workflow.responded(REFERENCE);
        BpmnAwareTests.assertThat(instance).isEnded().hasPassed("responded");
    }

    @Test
    void lateResponseAfterExpiryNotifiesOnceWithoutRestartingTimer() {
        workflow.issued(REFERENCE, DEADLINE);
        management.executeJob(timer(instance(REFERENCE)).getId());

        workflow.responded(REFERENCE);
        workflow.responded(REFERENCE);

        verify(notifications).claimantNotified(REFERENCE);
        assertThat(runtime.createProcessInstanceQuery().count()).isZero();
        assertThat(management.createJobQuery().timers().count()).isZero();
    }

    @Test
    void acknowledgmentCanRestoreAMissingActiveSeedWorkflow() {
        workflow.acknowledged(REFERENCE, DEADLINE.plusDays(28));
        ProcessInstance instance = instance(REFERENCE);
        assertThat(timer(instance).getDuedate().toInstant()).isEqualTo(Instant.parse("2030-08-06T23:00:00Z"));
        verify(cases).generateClaimForm(REFERENCE);
        verify(cases).markAwaitingResponse(REFERENCE);
    }

    @Test
    void responseWithoutASeededProcessDoesNotStartAnAlreadyAnsweredClaimLater() {
        workflow.responded(REFERENCE);
        workflow.responded(REFERENCE);
        workflow.issued(REFERENCE, DEADLINE);

        assertThat(runtime.createProcessInstanceQuery().count()).isZero();
        verify(notifications).claimantNotified(REFERENCE);
        verify(cases, never()).generateClaimForm(anyString());
    }

    @Test
    void failedDeadlineCallbackCanRetryWithoutLosingTheResponseSubscription() {
        workflow.issued(REFERENCE, DEADLINE);
        ProcessInstance instance = instance(REFERENCE);
        String timerId = timer(instance).getId();
        doThrow(new IllegalStateException("Case store unavailable")).when(cases)
            .markAwaitingDefaultJudgment(REFERENCE);

        assertThatThrownBy(() -> management.executeJob(timerId)).hasMessageContaining("Case store unavailable");
        assertThat(timer(instance)).isNotNull();
        assertThat(runtime.createEventSubscriptionQuery().processInstanceId(instance.getId())
            .eventName(ClaimProcess.RESPONSE).count()).isEqualTo(1);

        doNothing().when(cases).markAwaitingDefaultJudgment(REFERENCE);
        management.executeJob(timerId);
        BpmnAwareTests.assertThat(instance).isEnded().hasPassed("awaitingDefaultJudgment");
    }

    @Test
    void rejectsLegacyKeysBeforeWritingWorkflowState() {
        assertThatThrownBy(() -> workflow.issued("000LC001", DEADLINE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM civil.claim_workflows", Long.class)).isZero();
        assertThat(runtime.createProcessInstanceQuery().count()).isZero();
    }

    @Test
    void reconcilesSeededClaimsOnReadyAndCanResetAndReseed() {
        when(cases.activeClaims()).thenReturn(List.of(
            new WorkflowCasePort.ActiveClaim(REFERENCE, DEADLINE),
            new WorkflowCasePort.ActiveClaim("000MC002", DEADLINE.plusDays(1))));

        startup.seedActiveClaims();
        startup.seedActiveClaims();
        assertThat(runtime.createProcessInstanceQuery().processDefinitionKey(ClaimProcess.KEY).count()).isEqualTo(2);
        verify(cases).generateClaimForm(REFERENCE);
        workflow.responded(REFERENCE);
        workflow.reset();

        assertThat(runtime.createProcessInstanceQuery().count()).isZero();
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM civil.claim_workflows", Long.class)).isZero();
        workflow.seedActiveClaims();
        assertThat(runtime.createProcessInstanceQuery().count()).isEqualTo(2);
        verify(cases, times(2)).generateClaimForm(REFERENCE);
    }

    @Test
    void resetDoesNotRemoveOtherProcessDefinitionsOrInstances() {
        String deployment = repository.createDeployment().addString("unrelated.bpmn", """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="test">
              <process id="unrelated-process" isExecutable="true" camunda:historyTimeToLive="30">
                <startEvent id="start"/><sequenceFlow id="flow" sourceRef="start" targetRef="wait"/>
                <userTask id="wait" name="Unrelated work"/>
              </process>
            </definitions>
            """).deploy().getId();
        try {
            ProcessInstance unrelated = runtime.startProcessInstanceByKey("unrelated-process");
            workflow.issued(REFERENCE, DEADLINE);
            workflow.reset();
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(unrelated.getId()).count()).isEqualTo(1);
            assertThat(repository.createProcessDefinitionQuery().processDefinitionKey(ClaimProcess.KEY).count()).isEqualTo(1);
        } finally {
            repository.deleteDeployment(deployment, true);
        }
    }

    @Test
    void embeddedEngineAndCockpitUseTheSamePostgresWithDemoAdmin() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM civil.act_re_procdef WHERE key_ = ?", Long.class,
            ClaimProcess.KEY)).isEqualTo(1);
        assertThat(identity.checkPassword("admin", "admin")).isTrue();
        assertThat(identity.checkPassword("admin", "wrong-password")).isFalse();
        assertThat(http.getForEntity("/health", String.class).getStatusCode().value()).isEqualTo(200);
        var cockpit = http.getForEntity("/camunda/app/cockpit/default/", String.class);
        assertThat(cockpit.getStatusCode().value()).isEqualTo(200);
        assertThat(cockpit.getBody()).contains("Camunda");
    }

    private ProcessInstance instance(String reference) {
        return runtime.createProcessInstanceQuery().processDefinitionKey(ClaimProcess.KEY)
            .processInstanceBusinessKey(reference).singleResult();
    }

    private Job timer(ProcessInstance instance) {
        return management.createJobQuery().processInstanceId(instance.getId()).timers().singleResult();
    }
}
