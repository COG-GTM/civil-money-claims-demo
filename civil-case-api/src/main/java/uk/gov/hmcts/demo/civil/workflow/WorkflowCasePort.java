package uk.gov.hmcts.demo.civil.workflow;

import java.time.LocalDate;
import java.util.List;

// B1 implements this bridge; B2 delegates never depend on JPA entities.
public interface WorkflowCasePort {
    void generateClaimForm(String reference);
    void markAwaitingResponse(String reference);
    void markAwaitingDefaultJudgment(String reference);
    List<ActiveClaim> activeClaims();
    record ActiveClaim(String reference, LocalDate responseDeadline) {}
}
