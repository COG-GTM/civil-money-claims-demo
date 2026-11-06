package uk.gov.hmcts.demo.civil.camunda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClaimProcessTest {
    @ParameterizedTest
    @CsvSource({
        "2026-01-09, 2026-01-10T00:00:00Z",
        "2026-07-09, 2026-07-10T00:00:00+01:00",
        "2026-03-28, 2026-03-29T00:00:00Z",
        "2026-03-29, 2026-03-30T00:00:00+01:00",
        "2026-10-24, 2026-10-25T00:00:00+01:00",
        "2026-10-25, 2026-10-26T00:00:00Z",
        "2026-12-31, 2027-01-01T00:00:00Z"
    })
    void expiresAfterTheWholeLondonResponseDateIncludingClockChanges(String date, String expiresAt) {
        assertThat(ClaimProcess.deadlineVariables(LocalDate.parse(date)))
            .containsEntry(ClaimProcess.DEADLINE, date)
            .containsEntry(ClaimProcess.DEADLINE_AT, expiresAt);
    }

    @Test
    void rejectsMissingDeadline() {
        assertThatThrownBy(() -> ClaimProcess.deadlineVariables(null))
            .isInstanceOf(NullPointerException.class).hasMessage("Response deadline is required");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"000LC001", "000MC01", "not-a-claim", "000MC001\n"})
    void rejectsInvalidOrLegacyBusinessKeys(String reference) {
        assertThatThrownBy(() -> ClaimProcess.reference(reference)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permitsReferencesBeyondThreeDigits() {
        assertThat(ClaimProcess.reference("000MC1000")).isEqualTo("000MC1000");
    }
}
