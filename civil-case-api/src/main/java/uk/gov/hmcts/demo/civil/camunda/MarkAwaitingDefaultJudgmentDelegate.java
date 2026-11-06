package uk.gov.hmcts.demo.civil.camunda;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.demo.civil.workflow.WorkflowCasePort;

@Component("markAwaitingDefaultJudgmentDelegate")
public class MarkAwaitingDefaultJudgmentDelegate implements JavaDelegate {
    private final WorkflowCasePort cases;

    public MarkAwaitingDefaultJudgmentDelegate(@Lazy WorkflowCasePort cases) {
        this.cases = cases;
    }

    @Override
    public void execute(DelegateExecution execution) {
        cases.markAwaitingDefaultJudgment(execution.getProcessBusinessKey());
    }
}
