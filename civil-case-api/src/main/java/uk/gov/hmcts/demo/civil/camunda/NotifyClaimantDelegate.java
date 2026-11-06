package uk.gov.hmcts.demo.civil.camunda;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component("notifyClaimantDelegate")
public class NotifyClaimantDelegate implements JavaDelegate {
    private final ClaimNotifications notifications;

    public NotifyClaimantDelegate(ClaimNotifications notifications) {
        this.notifications = notifications;
    }

    @Override
    public void execute(DelegateExecution execution) {
        notifications.claimantNotified(execution.getProcessBusinessKey());
    }
}
