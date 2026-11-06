package uk.gov.hmcts.demo.civil.domain;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeeCalculatorTest {
    private final FeeCalculator calculator=new FeeCalculator();
    @ParameterizedTest
    @CsvSource({"0.01,35","300,35","300.01,50","500,50","500.01,70","1000,70","1000.01,80",
        "1500,80","1500.01,115","3000,115","3000.01,205","5000,205","5000.01,455","10000,455",
        "10000.01,500.00","10000.19,500.00","10000.20,500.01","25000,1250","200000,10000","200001,10000"})
    void calculatesCurrentInclusiveBandsAndFloorsPercentage(String base,String expected) {
        assertThat(calculator.calculate(new BigDecimal(base))).isEqualByComparingTo(expected);
    }
    @Test void rejectsNonpositiveOrMissingAmounts() {
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE.negate())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
