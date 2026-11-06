package uk.gov.hmcts.demo.civil.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DeadlineCalculator {
    public static final ZoneId COURT_ZONE = ZoneId.of("Europe/London");

    public LocalDate serviceDate(LocalDate issueDate) {
        return issueDate.plusDays(5);
    }

    // Calendar-day offsets are applied before rolling the final date to a working day.
    public LocalDate calculate(LocalDate serviceDate, boolean acknowledged, Set<LocalDate> bankHolidays) {
        LocalDate result = serviceDate.plusDays(14 + (acknowledged ? 28 : 0));
        while (result.getDayOfWeek() == DayOfWeek.SATURDAY
                || result.getDayOfWeek() == DayOfWeek.SUNDAY || bankHolidays.contains(result)) {
            result = result.plusDays(1);
        }
        return result;
    }
}
