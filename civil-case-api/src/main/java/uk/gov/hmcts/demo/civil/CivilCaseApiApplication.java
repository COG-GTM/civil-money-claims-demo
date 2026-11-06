package uk.gov.hmcts.demo.civil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import uk.gov.hmcts.demo.civil.config.DatabaseUrlInitializer;

@SpringBootApplication
public class CivilCaseApiApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CivilCaseApiApplication.class);
        application.addInitializers(new DatabaseUrlInitializer());
        application.run(args);
    }
}
