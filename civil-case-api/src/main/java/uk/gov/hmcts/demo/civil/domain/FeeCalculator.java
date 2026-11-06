package uk.gov.hmcts.demo.civil.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class FeeCalculator {
    private static final int[] LIMITS = {300, 500, 1000, 1500, 3000, 5000, 10000};
    private static final int[] FEES = {35, 50, 70, 80, 115, 205, 455};

    // The fee base includes interest; online and paper use the same current table.
    public BigDecimal calculate(BigDecimal amountIncludingInterest) {
        if (amountIncludingInterest == null || amountIncludingInterest.signum() <= 0) {
            throw new IllegalArgumentException("Claim amount must be greater than zero");
        }
        for (int i = 0; i < LIMITS.length; i++) {
            if (amountIncludingInterest.compareTo(BigDecimal.valueOf(LIMITS[i])) <= 0) {
                return BigDecimal.valueOf(FEES[i]).setScale(2);
            }
        }
        return amountIncludingInterest.multiply(new BigDecimal("0.05"))
                .min(new BigDecimal("10000")).setScale(2, RoundingMode.DOWN);
    }
}
