package uk.gov.hmcts.demo.legacy.service;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.springframework.stereotype.Component;

@Component
public class LegacyResponseDeadlineCalculator {
    private static final TimeZone LONDON = TimeZone.getTimeZone("Europe/London");

    public Date serviceDate(Date issuedAt) {
        return addDays(issuedAt, 5);
    }

    public Date responseDeadline(Date serviceDate, boolean acknowledged, Collection<String> bankHolidays) {
        Set<String> holidays = new HashSet<String>(bankHolidays);
        Calendar day = calendar(addDays(serviceDate, acknowledged ? 42 : 14));
        // Add calendar days before rolling: DST must never lose or duplicate a day.
        while (day.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
                || day.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY || holidays.contains(format(day.getTime()))) {
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        return day.getTime();
    }

    public Date addDays(Date start, int days) {
        Calendar day = calendar(start);
        day.add(Calendar.DAY_OF_MONTH, days);
        return day.getTime();
    }

    public Date today() {
        return calendar(new Date()).getTime();
    }

    public Date parse(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("Expected a date in YYYY-MM-DD format");
        }
        SimpleDateFormat parser = formatter();
        ParsePosition position = new ParsePosition(0);
        Date date = parser.parse(value, position);
        if (date == null || position.getIndex() != value.length() || !format(date).equals(value)) {
            throw new IllegalArgumentException("Enter a valid calendar date");
        }
        return date;
    }

    public String format(Date date) {
        return formatter().format(date);
    }

    public long daysBetween(Date start, Date end) {
        // UTC copies of the London calendar fields avoid dividing a 23/25-hour day.
        return (utcCalendar(end).getTimeInMillis() - utcCalendar(start).getTimeInMillis()) / 86_400_000L;
    }

    private Calendar utcCalendar(Date date) {
        Calendar local = calendar(date);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.UK);
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        return utc;
    }

    private Calendar calendar(Date date) {
        Calendar calendar = Calendar.getInstance(LONDON, Locale.UK);
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private SimpleDateFormat formatter() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.UK);
        format.setTimeZone(LONDON);
        format.setLenient(false);
        return format;
    }
}
