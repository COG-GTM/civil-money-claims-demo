package uk.gov.hmcts.demo.civil.camunda;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.demo.civil.workflow.WorkflowCasePort;

/** The domain owns valid PDF generation and storage, not the process engine. */
@Component("generateClaimFormDelegate")
public class GenerateClaimFormDelegate implements JavaDelegate {
    private final WorkflowCasePort cases;

    public GenerateClaimFormDelegate(@Lazy WorkflowCasePort cases) {
        this.cases = cases;
    }

    @Override
    public void execute(DelegateExecution execution) {
        cases.generateClaimForm(execution.getProcessBusinessKey());
    }
}
