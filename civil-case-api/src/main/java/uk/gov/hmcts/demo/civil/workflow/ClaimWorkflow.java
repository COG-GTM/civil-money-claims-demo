package uk.gov.hmcts.demo.civil.workflow;

import java.time.LocalDate;

// B2 implements this bridge; B1 invokes it after persisted business changes.
public interface ClaimWorkflow {
    void issued(String reference, LocalDate responseDeadline);
    void responded(String reference);
    void acknowledged(String reference, LocalDate responseDeadline);
    void reset();
    void seedActiveClaims();
}
