package uk.gov.hmcts.demo.civil.web;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import uk.gov.hmcts.demo.civil.service.*;

@RestController
@RequestMapping("/api/ops")
public class OpsController {
    private final OpsMetrics metrics;
    private final DemoResetService reset;
    public OpsController(OpsMetrics metrics,DemoResetService reset) { this.metrics=metrics; this.reset=reset; }
    @GetMapping("/metrics")
    public OpsMetrics.Snapshot metrics() { return metrics.snapshot(); }
    @PostMapping("/reset")
    public Map<String,Object> reset(@RequestHeader(value="X-Demo-Reset-Token",required=false) String token,
            @RequestBody Map<String,Object> input) { return reset.reset(token); }
}
