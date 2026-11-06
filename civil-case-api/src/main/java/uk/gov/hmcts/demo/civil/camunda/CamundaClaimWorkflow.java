package uk.gov.hmcts.demo.civil.camunda;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.demo.civil.workflow.ClaimWorkflow;
import uk.gov.hmcts.demo.civil.workflow.WorkflowCasePort;

/** Keeps business callbacks, engine commands and the deduplication record in one transaction. */
@Service
@ConditionalOnProperty(name = "camunda.bpm.enabled", havingValue = "true", matchIfMissing = true)
@Transactional
public class CamundaClaimWorkflow implements ClaimWorkflow {
    private final RuntimeService runtime;
    private final ManagementService management;
    private final HistoryService history;
    private final JdbcTemplate jdbc;
    private final WorkflowCasePort cases;
    private final ClaimNotifications notifications;

    public CamundaClaimWorkflow(RuntimeService runtime, ManagementService management,
                                HistoryService history, JdbcTemplate jdbc,
                                @Lazy WorkflowCasePort cases, ClaimNotifications notifications) {
        this.runtime = runtime;
        this.management = management;
        this.history = history;
        this.jdbc = jdbc;
        this.cases = cases;
        this.notifications = notifications;
    }

    @Override
    public void issued(String reference, LocalDate responseDeadline) {
        Map<String, Object> variables = ClaimProcess.deadlineVariables(responseDeadline);
        WorkflowRecord record = lock(reference);
        if (record.processInstanceId() == null && !record.responseReceived()) {
            start(reference, variables);
        }
    }

    @Override
    public void responded(String reference) {
        WorkflowRecord record = lock(reference);
        if (record.responseReceived()) {
            return;
        }
        if (isRunning(record)) {
            runtime.createMessageCorrelation(ClaimProcess.RESPONSE)
                .processInstanceId(record.processInstanceId()).correlate();
        } else {
            // The domain permits a late response before judgment; the deadline path already ended.
            notifications.claimantNotified(reference);
        }
        jdbc.update("UPDATE civil.claim_workflows SET response_received = TRUE WHERE reference = ?", reference);
    }

    @Override
    public void acknowledged(String reference, LocalDate responseDeadline) {
        Map<String, Object> variables = ClaimProcess.deadlineVariables(responseDeadline);
        WorkflowRecord record = lock(reference);
        if (record.responseReceived()) {
            return;
        }
        if (record.processInstanceId() == null) {
            start(reference, variables);
        } else if (isRunning(record)) {
            Job timer = management.createJobQuery().processInstanceId(record.processInstanceId())
                .timers().singleResult();
            if (timer == null) {
                throw new IllegalStateException("Active claim workflow has no response deadline timer");
            }
            runtime.setVariables(record.processInstanceId(), variables);
            // Updating the variable alone does not change an already-created Camunda timer job.
            Date dueAt = Date.from(OffsetDateTime.parse((String) variables.get(ClaimProcess.DEADLINE_AT)).toInstant());
            management.setJobDuedate(timer.getId(), dueAt);
        }
    }

    @Override
    public void reset() {
        runtime.createProcessInstanceQuery().processDefinitionKey(ClaimProcess.KEY).list()
            .forEach(instance -> runtime.deleteProcessInstance(instance.getId(), "Demo data reset"));
        history.createHistoricProcessInstanceQuery().processDefinitionKey(ClaimProcess.KEY).list()
            .forEach(instance -> history.deleteHistoricProcessInstance(instance.getId()));
        jdbc.update("DELETE FROM civil.claim_workflows");
    }

    @Override
    public void seedActiveClaims() {
        for (WorkflowCasePort.ActiveClaim claim : cases.activeClaims()) {
            issued(claim.reference(), claim.responseDeadline());
        }
    }

    private void start(String reference, Map<String, Object> deadlineVariables) {
        Map<String, Object> variables = new HashMap<>(deadlineVariables);
        variables.put(ClaimProcess.REFERENCE, reference);
        ProcessInstance instance = runtime.startProcessInstanceByMessage(ClaimProcess.ISSUED, reference, variables);
        jdbc.update("UPDATE civil.claim_workflows SET process_instance_id = ? WHERE reference = ?",
            instance.getId(), reference);
    }

    private boolean isRunning(WorkflowRecord record) {
        return record.processInstanceId() != null && runtime.createProcessInstanceQuery()
            .processInstanceId(record.processInstanceId()).count() != 0;
    }

    private WorkflowRecord lock(String reference) {
        ClaimProcess.reference(reference);
        // The business key is not unique in Camunda; retain this record even after history cleanup.
        jdbc.update("INSERT INTO civil.claim_workflows (reference) VALUES (?) ON CONFLICT (reference) DO NOTHING",
            reference);
        return jdbc.queryForObject(
            "SELECT process_instance_id, response_received FROM civil.claim_workflows WHERE reference = ? FOR UPDATE",
            (rs, row) -> new WorkflowRecord(rs.getString("process_instance_id"), rs.getBoolean("response_received")),
            reference);
    }

    private record WorkflowRecord(String processInstanceId, boolean responseReceived) {}
}
