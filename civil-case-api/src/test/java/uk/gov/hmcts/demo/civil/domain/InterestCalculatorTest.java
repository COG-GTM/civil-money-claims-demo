package uk.gov.hmcts.demo.civil.domain;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InterestCalculatorTest {
    private final InterestCalculator calculator=new InterestCalculator();
    @Test void computesEightPercentSimpleAnnualInterestAndSixDecimalDailyRate() {
        var result=calculator.calculate(new BigDecimal("1000"),LocalDate.of(2025,1,1),LocalDate.of(2026,1,1));
        assertThat(result.interestAmount()).isEqualByComparingTo("80.00");
        assertThat(result.dailyInterest()).isEqualByComparingTo("0.219178");
        assertThat(result.days()).isEqualTo(365);
    }
    @Test void uses365DivisorInLeapYearsWithoutCompounding() {
        var result=calculator.calculate(new BigDecimal("3650"),LocalDate.of(2024,1,1),LocalDate.of(2025,1,1));
        assertThat(result.interestAmount()).isEqualByComparingTo("292.80");
        assertThat(result.dailyInterest()).isEqualByComparingTo("0.800000");
    }
    @Test void doesNotMultiplyRoundedDailyInterest() {
        var result=calculator.calculate(new BigDecimal("1"),LocalDate.of(1900,1,1),LocalDate.of(2000,1,1));
        assertThat(result.interestAmount()).isEqualByComparingTo("8.01");
    }
    @Test void roundsFinalPenniesHalfUp() {
        var result=calculator.calculate(new BigDecimal("22.82"),LocalDate.of(2026,1,1),LocalDate.of(2026,1,2));
        assertThat(result.interestAmount()).isEqualByComparingTo("0.01");
    }
    @Test void supportsSameDayAndZeroPrincipal() {
        var today=LocalDate.of(2026,1,1);
        assertThat(calculator.calculate(BigDecimal.TEN,today,today).interestAmount()).isEqualByComparingTo("0");
        assertThat(calculator.calculate(BigDecimal.ZERO,today,today.plusDays(20)).dailyInterest()).isEqualByComparingTo("0");
    }
    @Test void rejectsFutureDatesMissingDatesAndNegativePrincipal() {
        var today=LocalDate.of(2026,1,1);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE,today.plusDays(1),today)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE,null,today)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE.negate(),today,today)).isInstanceOf(IllegalArgumentException.class);
    }
}
