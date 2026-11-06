package uk.gov.hmcts.demo.civil.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
public class InterestCalculator {
    private static final BigDecimal RATE = new BigDecimal("0.08");
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    public record InterestResult(BigDecimal interestAmount, BigDecimal dailyInterest, long days) {}

    public InterestResult calculate(BigDecimal principal, LocalDate startDate, LocalDate calculationDate) {
        if (principal == null || principal.signum() < 0) {
            throw new IllegalArgumentException("Principal must not be negative");
        }
        if (startDate == null || calculationDate == null || startDate.isAfter(calculationDate)) {
            throw new IllegalArgumentException("Interest start date must not be after the calculation date");
        }
        long days = ChronoUnit.DAYS.between(startDate, calculationDate);
        BigDecimal annualInterest = principal.multiply(RATE);
        // Round only the final total, not the daily rate used to calculate it.
        BigDecimal total = annualInterest.multiply(BigDecimal.valueOf(days))
                .divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_UP);
        BigDecimal daily = annualInterest.divide(DAYS_PER_YEAR, 6, RoundingMode.HALF_UP);
        return new InterestResult(total, daily, days);
    }
}
