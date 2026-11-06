package uk.gov.hmcts.demo.civil.domain;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ClaimStateMachine {
    private static final Map<ClaimState, Set<ClaimState>> ALLOWED = Map.of(
        ClaimState.DRAFT, Set.of(ClaimState.ISSUED),
        ClaimState.ISSUED, Set.of(ClaimState.AWAITING_RESPONSE),
        ClaimState.AWAITING_RESPONSE, Set.of(ClaimState.RESPONDED, ClaimState.AWAITING_DEFAULT_JUDGMENT),
        ClaimState.RESPONDED, Set.of(ClaimState.SETTLED, ClaimState.TRANSFERRED),
        ClaimState.AWAITING_DEFAULT_JUDGMENT, Set.of(ClaimState.RESPONDED, ClaimState.SETTLED, ClaimState.TRANSFERRED)
    );

    public ClaimState transition(ClaimState current, ClaimState target, boolean judgmentRequested) {
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)
                || (current == ClaimState.AWAITING_DEFAULT_JUDGMENT
                    && target == ClaimState.RESPONDED && judgmentRequested)) {
            throw new IllegalStateException("Cannot change claim state from " + current + " to " + target);
        }
        return target;
    }
}
