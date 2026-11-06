package uk.gov.hmcts.demo.civil.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.demo.civil.domain.ClaimState;

// Shared JSON records keep stored JSONB and the external API shapes consistent.
public final class ClaimModels {
    private ClaimModels() {}
    public enum PartyType { INDIVIDUAL, BUSINESS }
    public enum ResponseType { ADMIT_ALL, ADMIT_PART, REJECT }
    public enum PaymentOption { IMMEDIATELY, BY_DATE, INSTALMENTS }

    public record Address(@NotBlank @Size(max=300) String line1, @Size(max=300) String line2,
            @NotBlank @Size(max=100) String city, @NotBlank @Size(max=20) String postcode,
            @Size(max=100) String country) {}
    public record Party(@NotNull PartyType type, @NotBlank @Size(max=200) String name,
            @Email @Size(max=254) String email, @Size(max=50) String phone, @NotNull @Valid Address address) {}
    public record AmountItem(@NotBlank @Size(max=1000) String reason,
            @NotNull @DecimalMin("0.00") @Digits(integer=12,fraction=2) BigDecimal amount) {}
    public record Interest(@NotNull Boolean claimed, @DecimalMin("8") @DecimalMax("8") BigDecimal rate,
            LocalDate startDate) {
        public Interest { if (rate == null) rate = BigDecimal.valueOf(8); }
        public static Interest none() { return new Interest(false, BigDecimal.valueOf(8), null); }
    }
    public record TimelineEntry(@NotNull LocalDate date, @NotBlank @Size(max=5000) String description) {}
    public record ClaimInput(@Size(min=1,max=100) String claimantId, @Valid Party claimant, @Valid Party defendant,
            @Size(max=100) List<@NotNull @Valid AmountItem> amountBreakdown, @Valid Interest interest,
            @Size(max=20000) String reason, @Size(max=100) List<@NotNull @Valid TimelineEntry> timeline) {}
    public record FeeQuoteInput(@NotEmpty @Size(max=100) List<@NotNull @Valid AmountItem> amountBreakdown,
            @Valid Interest interest, LocalDate calculationDate) {}
    public record FeeQuote(BigDecimal principalAmount, BigDecimal interestAmount, BigDecimal dailyInterest,
            BigDecimal fee, BigDecimal totalAmount, LocalDate calculationDate) {}
    public record DefendantResponse(@NotNull ResponseType type,
            @DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal admittedAmount, @Size(max=20000) String reason,
            PaymentOption paymentOption, LocalDate paymentDate,
            @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal instalmentAmount,
            @NotNull @AssertTrue Boolean statementOfTruth, Instant submittedAt) {}
    public record LookupInput(@NotBlank String reference, @NotBlank String securityCode) {}
    public record IssueInput(@NotBlank @Size(max=100) String paymentReference,
            @NotNull @AssertTrue Boolean statementOfTruth) {}
    public record JudgmentInput(@NotNull @AssertTrue Boolean statementOfTruth) {}
    public record StateInput(@NotNull ClaimState state, @Size(max=5000) String reason) {}
    public record NoteInput(@NotBlank @Size(max=10000) String text, @Size(min=1,max=100) String author) {}
    public record ClaimPage(List<ClaimView> items, long total, int page, int size) {}
    public record ClaimView(UUID id, String reference, String claimantId, ClaimState state,
            Party claimant, Party defendant, List<AmountItem> amountBreakdown,
            BigDecimal principalAmount, Interest interest, BigDecimal interestAmount, BigDecimal dailyInterest,
            BigDecimal fee, BigDecimal totalAmount, String reason, List<TimelineEntry> timeline,
            String securityCode, Instant createdAt, Instant updatedAt, Instant issuedAt, LocalDate serviceDate,
            LocalDate responseDeadline, boolean acknowledged, boolean eligibleForDefaultJudgment,
            DefendantResponse response, Instant judgmentRequestedAt, long version) {}
}
