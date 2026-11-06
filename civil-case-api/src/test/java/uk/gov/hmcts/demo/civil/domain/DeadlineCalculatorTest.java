package uk.gov.hmcts.demo.civil.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeadlineCalculatorTest {
    private final DeadlineCalculator calculator=new DeadlineCalculator();
    private final Set<LocalDate> holidays=Set.of(LocalDate.of(2026,4,3),LocalDate.of(2026,4,6),
        LocalDate.of(2026,12,25),LocalDate.of(2026,12,28),LocalDate.of(2027,1,1));
    @Test void serviceIsFiveCalendarDaysAfterIssue() {
        assertThat(calculator.serviceDate(LocalDate.of(2026,3,26))).isEqualTo(LocalDate.of(2026,3,31));
    }
    @Test void rollsGoodFridayAcrossEasterMonday() {
        assertThat(calculator.calculate(LocalDate.of(2026,3,20),false,holidays)).isEqualTo(LocalDate.of(2026,4,7));
    }
    @Test void rollsChristmasAcrossSubstituteBoxingDay() {
        assertThat(calculator.calculate(LocalDate.of(2026,12,11),false,holidays)).isEqualTo(LocalDate.of(2026,12,29));
    }
    @Test void extendsOriginalUnrolledDeadlineBy28AdditionalDays() {
        assertThat(calculator.calculate(LocalDate.of(2026,3,20),true,holidays)).isEqualTo(LocalDate.of(2026,5,1));
    }
    @Test void rollsWeekendsRatherThanCountingWorkingDays() {
        assertThat(calculator.calculate(LocalDate.of(2026,2,7),false,holidays)).isEqualTo(LocalDate.of(2026,2,23));
    }
    @Test void crossesBothDstChangesAsCalendarDates() {
        assertThat(calculator.calculate(LocalDate.of(2026,3,22),false,holidays)).isEqualTo(LocalDate.of(2026,4,7));
        assertThat(calculator.calculate(LocalDate.of(2026,10,18),false,holidays)).isEqualTo(LocalDate.of(2026,11,2));
    }
    @Test void courtDateUsesLondonNotUtc() {
        Instant nearMidnight=Instant.parse("2026-07-01T23:30:00Z");
        assertThat(LocalDate.ofInstant(nearMidnight,DeadlineCalculator.COURT_ZONE)).isEqualTo(LocalDate.of(2026,7,2));
    }
}
