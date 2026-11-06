package uk.gov.hmcts.demo.civil.domain;

import static org.assertj.core.api.Assertions.*;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClaimStateMachineTest {
    private final ClaimStateMachine machine=new ClaimStateMachine();
    private static final Set<String> ALLOWED=Set.of("DRAFT:ISSUED","ISSUED:AWAITING_RESPONSE",
        "AWAITING_RESPONSE:RESPONDED","AWAITING_RESPONSE:AWAITING_DEFAULT_JUDGMENT",
        "RESPONDED:SETTLED","RESPONDED:TRANSFERRED","AWAITING_DEFAULT_JUDGMENT:RESPONDED",
        "AWAITING_DEFAULT_JUDGMENT:SETTLED","AWAITING_DEFAULT_JUDGMENT:TRANSFERRED");
    static Stream<Arguments> transitions() {
        return Stream.of(ClaimState.values()).flatMap(from -> Stream.of(ClaimState.values()).map(to -> Arguments.of(from,to)));
    }
    @ParameterizedTest @MethodSource("transitions")
    void onlyAllowsContractedTransitions(ClaimState from,ClaimState to) {
        if (ALLOWED.contains(from+":"+to)) assertThat(machine.transition(from,to,false)).isEqualTo(to);
        else assertThatThrownBy(() -> machine.transition(from,to,false)).isInstanceOf(IllegalStateException.class);
    }
    @Test void preventsLateResponseAfterJudgmentWasRequested() {
        assertThatThrownBy(() -> machine.transition(ClaimState.AWAITING_DEFAULT_JUDGMENT,ClaimState.RESPONDED,true))
            .isInstanceOf(IllegalStateException.class);
        assertThat(machine.transition(ClaimState.AWAITING_DEFAULT_JUDGMENT,ClaimState.SETTLED,true)).isEqualTo(ClaimState.SETTLED);
    }
}
