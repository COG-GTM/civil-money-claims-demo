package uk.gov.hmcts.demo.civil.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.demo.civil.domain.*;
import uk.gov.hmcts.demo.civil.web.ApiException;
import uk.gov.hmcts.demo.civil.web.ClaimModels.*;

@Service
public class FeeQuoteService {
    private final FeeCalculator fees;
    private final InterestCalculator interestCalculator;
    private final Clock clock;
    public FeeQuoteService(FeeCalculator fees, InterestCalculator interestCalculator, Clock clock) {
        this.fees = fees; this.interestCalculator = interestCalculator; this.clock = clock;
    }

    public FeeQuote quote(List<AmountItem> amounts, Interest interest, LocalDate calculationDate, boolean allowEmpty) {
        LocalDate date = calculationDate == null ? LocalDate.now(clock) : calculationDate;
        BigDecimal principal = amounts.stream().map(AmountItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (principal.signum() < 0 || (!allowEmpty && principal.signum() == 0)) {
            throw ApiException.invalid("amountBreakdown", "Enter a claim amount greater than zero");
        }
        if (principal.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw ApiException.invalid("amountBreakdown", "The total amount is too large");
        }
        BigDecimal accrued = BigDecimal.ZERO.setScale(2);
        BigDecimal daily = BigDecimal.ZERO.setScale(6);
        if (interest != null && Boolean.TRUE.equals(interest.claimed())) {
            if (interest.startDate() == null || interest.startDate().isAfter(date)) {
                throw ApiException.invalid("interest.startDate", "Enter an interest start date on or before the calculation date");
            }
            InterestCalculator.InterestResult result = interestCalculator.calculate(principal, interest.startDate(), date);
            accrued = result.interestAmount(); daily = result.dailyInterest();
        }
        BigDecimal fee = principal.signum() == 0 ? BigDecimal.ZERO.setScale(2) : fees.calculate(principal.add(accrued));
        BigDecimal total = principal.add(accrued).add(fee);
        if (total.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw ApiException.invalid("amountBreakdown", "The total including interest and fee is too large");
        }
        return new FeeQuote(principal.setScale(2), accrued, daily, fee, total.setScale(2), date);
    }
}
