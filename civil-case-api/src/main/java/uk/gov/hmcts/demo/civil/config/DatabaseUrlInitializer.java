package uk.gov.hmcts.demo.civil.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

// Render supplies postgres:// URLs, while Spring expects jdbc:postgresql://.
public class DatabaseUrlInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String value = context.getEnvironment().getProperty("DATABASE_URL", "postgresql://claims:claims@localhost:5433/claims");
        Map<String, Object> props = new HashMap<>();
        if (value.startsWith("jdbc:postgresql:")) {
            props.put("spring.datasource.url", value);
        } else {
            URI uri = URI.create(value);
            if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
                throw new IllegalArgumentException("DATABASE_URL must be a PostgreSQL URL");
            }
            String host = uri.getHost();
            if (host == null) throw new IllegalArgumentException("DATABASE_URL must include a host");
            String url = "jdbc:postgresql://" + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + uri.getRawPath();
            if (uri.getRawQuery() != null) url += "?" + uri.getRawQuery();
            props.put("spring.datasource.url", url);
            if (uri.getRawUserInfo() != null) {
                String[] credentials = uri.getRawUserInfo().split(":", 2);
                props.put("spring.datasource.username", URLDecoder.decode(credentials[0], StandardCharsets.UTF_8));
                if (credentials.length > 1) props.put("spring.datasource.password", URLDecoder.decode(credentials[1], StandardCharsets.UTF_8));
            }
        }
        for (String key : new String[]{"username", "password"}) {
            String override = context.getEnvironment().getProperty("DATABASE_" + key.toUpperCase());
            if (override != null && !override.isBlank()) props.put("spring.datasource." + key, override);
        }
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("databaseUrl", props));
    }
}
