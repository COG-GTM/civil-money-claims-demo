package uk.gov.hmcts.demo.civil.camunda;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.demo.civil.workflow.ClaimWorkflow;

/** Flyway and BPMN deployment have completed before seeded claims are reconciled. */
@Component
@ConditionalOnProperty(name = "camunda.bpm.enabled", havingValue = "true", matchIfMissing = true)
public class ClaimWorkflowStartup {
    private final ClaimWorkflow workflow;

    public ClaimWorkflowStartup(ClaimWorkflow workflow) {
        this.workflow = workflow;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedActiveClaims() {
        workflow.seedActiveClaims();
    }
}
