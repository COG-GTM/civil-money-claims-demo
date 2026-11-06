package uk.gov.hmcts.demo.civil.camunda;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

/** Names and date conversions shared by the workflow adapter and BPMN. */
final class ClaimProcess {
    static final String KEY = "money-claim";
    static final String ISSUED = "CLAIM_ISSUED";
    static final String RESPONSE = "DEFENDANT_RESPONSE";
    static final String REFERENCE = "claimReference";
    static final String DEADLINE = "responseDeadline";
    static final String DEADLINE_AT = "responseDeadlineAt";
    static final ZoneId COURT_ZONE = ZoneId.of("Europe/London");

    private ClaimProcess() {}

    static String reference(String reference) {
        if (reference == null || !reference.matches("^000MC[0-9]{3,}$")) {
            throw new IllegalArgumentException("A modern claim reference is required");
        }
        return reference;
    }

    static Map<String, Object> deadlineVariables(LocalDate deadline) {
        Objects.requireNonNull(deadline, "Response deadline is required");
        // A claimant is eligible only after the whole final response date has elapsed.
        String expiresAt = deadline.plusDays(1).atStartOfDay(COURT_ZONE)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return Map.of(DEADLINE, deadline.toString(), DEADLINE_AT, expiresAt);
    }
}
