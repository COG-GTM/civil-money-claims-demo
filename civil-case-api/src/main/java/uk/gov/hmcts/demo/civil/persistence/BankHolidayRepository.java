package uk.gov.hmcts.demo.civil.persistence;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BankHolidayRepository {
    private final JdbcTemplate jdbc;
    public BankHolidayRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Set<LocalDate> dates() {
        return new HashSet<>(jdbc.query("select date from civil.bank_holidays",
                (rs, row) -> rs.getObject("date", LocalDate.class)));
    }
}
