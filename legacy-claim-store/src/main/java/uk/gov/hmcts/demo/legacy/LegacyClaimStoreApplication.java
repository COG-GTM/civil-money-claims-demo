package uk.gov.hmcts.demo.legacy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import uk.gov.hmcts.demo.legacy.config.DatabaseUrlInitializer;

@SpringBootApplication
public class LegacyClaimStoreApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(LegacyClaimStoreApplication.class);
        application.addInitializers(new DatabaseUrlInitializer());
        application.run(args);
    }
}
