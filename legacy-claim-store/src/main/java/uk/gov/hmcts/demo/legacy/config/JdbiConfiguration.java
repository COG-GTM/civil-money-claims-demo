package uk.gov.hmcts.demo.legacy.config;

import javax.sql.DataSource;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import uk.gov.hmcts.demo.legacy.repository.ClaimRepository;

@Configuration
@EnableScheduling
public class JdbiConfiguration {
    @Bean
    public DBI dbi(DataSource dataSource) {
        return new DBI(dataSource);
    }

    @Bean
    public ApplicationRunner verifyLegacyRepositories(DBI dbi) {
        return arguments -> {
            // Fail startup rather than advertise a working API with unusable Java 21 SQL proxies.
            try (Handle handle = dbi.open()) {
                handle.attach(ClaimRepository.class).countAll();
            } catch (LinkageError exception) {
                throw new IllegalStateException("JDBI 2 on Java 21 requires --add-opens=java.base/java.lang=ALL-UNNAMED", exception);
            }
        };
    }
}
