package uk.gov.hmcts.demo.civil.domain;

import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ClaimReferenceGenerator {
    private final JdbcTemplate jdbc;
    public ClaimReferenceGenerator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // A database sequence prevents collisions across concurrent service instances.
    public String generate() {
        Long sequence = jdbc.queryForObject("select nextval('civil.claim_reference_seq')", Long.class);
        return format(sequence);
    }

    public static String format(long sequence) {
        if (sequence <= 0) throw new IllegalArgumentException("Reference sequence must be positive");
        return String.format(Locale.ROOT, "000MC%03d", sequence);
    }
}
