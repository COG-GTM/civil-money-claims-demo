package uk.gov.hmcts.demo.civil.camunda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Demo notification transport: no letters or email are actually sent. */
@Component
public class ClaimNotifications {
    private static final Logger LOG = LoggerFactory.getLogger(ClaimNotifications.class);

    public void defendantNotified(String reference) {
        LOG.atInfo().addKeyValue("claim_reference", reference)
            .addKeyValue("workflow_event", "DEFENDANT_NOTIFIED")
            .log("Demo notification stub: claim issued; notify defendant");
    }

    public void claimantNotified(String reference) {
        LOG.atInfo().addKeyValue("claim_reference", reference)
            .addKeyValue("workflow_event", "CLAIMANT_NOTIFIED")
            .log("Demo notification stub: defendant responded; notify claimant");
    }
}
