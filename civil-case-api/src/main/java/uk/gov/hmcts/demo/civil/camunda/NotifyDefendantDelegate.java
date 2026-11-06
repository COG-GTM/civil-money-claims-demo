package uk.gov.hmcts.demo.civil.camunda;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component("notifyDefendantDelegate")
public class NotifyDefendantDelegate implements JavaDelegate {
    private final ClaimNotifications notifications;

    public NotifyDefendantDelegate(ClaimNotifications notifications) {
        this.notifications = notifications;
    }

    @Override
    public void execute(DelegateExecution execution) {
        notifications.defendantNotified(execution.getProcessBusinessKey());
    }
}
