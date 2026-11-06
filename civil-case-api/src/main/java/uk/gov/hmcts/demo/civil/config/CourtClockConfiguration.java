package uk.gov.hmcts.demo.civil.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.demo.civil.domain.DeadlineCalculator;

@Configuration
public class CourtClockConfiguration {
    @Bean
    public Clock courtClock() {
        return Clock.system(DeadlineCalculator.COURT_ZONE);
    }
}
