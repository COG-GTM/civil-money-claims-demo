package uk.gov.hmcts.demo.legacy.web;

import java.util.Map;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final HealthEndpoint healthEndpoint;

    public HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        String status = healthEndpoint.health().getStatus().getCode();
        return ResponseEntity.status("UP".equals(status) ? 200 : 503)
            .body(Map.of("status", status, "service", "legacy-claim-store"));
    }
}
