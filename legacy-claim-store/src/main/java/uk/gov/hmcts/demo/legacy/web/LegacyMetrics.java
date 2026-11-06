package uk.gov.hmcts.demo.legacy.web;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LegacyMetrics {
    private static final int WINDOW_SECONDS = 300;
    private final long[] seconds = new long[WINDOW_SECONDS];
    private final long[] requests = new long[WINDOW_SECONDS];
    private final long[] errors = new long[WINDOW_SECONDS];
    private final Deque<Map<String, Object>> latestErrors = new ArrayDeque<Map<String, Object>>();

    public synchronized void record(String requestId, String method, String path, int status) {
        long now = Instant.now().getEpochSecond();
        int index = (int) (now % WINDOW_SECONDS);
        if (seconds[index] != now) {
            seconds[index] = now;
            requests[index] = 0;
            errors[index] = 0;
        }
        requests[index]++;
        if (status >= 500) {
            errors[index]++;
            latestErrors.addFirst(Map.of("timestamp", Instant.now().toString(), "requestId", requestId,
                "method", method, "path", safePath(path), "status", status, "message", "The service could not complete the request"));
            while (latestErrors.size() > 20) latestErrors.removeLast();
        }
    }

    public synchronized Map<String, Object> snapshot() {
        long now = Instant.now().getEpochSecond();
        long requestCount = 0;
        long errorCount = 0;
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            if (seconds[i] > now - WINDOW_SECONDS && seconds[i] <= now) {
                requestCount += requests[i];
                errorCount += errors[i];
            }
        }
        return Map.of("service", "legacy-claim-store", "windowSeconds", WINDOW_SECONDS, "requestCount", requestCount,
            "errorCount", errorCount, "requestRate", requestCount / (double) WINDOW_SECONDS,
            "errorRate", requestCount == 0 ? 0.0 : errorCount / (double) requestCount, "latestErrors", new ArrayList<>(latestErrors));
    }

    // Do not let arbitrary URLs or query strings become a personal-data log sink.
    public static String safePath(String path) {
        return path.matches("/(health|api/(fees/quote|ops/(metrics|reset)|claims(/lookup|/000LC[0-9]{3,}(/(issue|acknowledge|response|default-judgment|state|events|notes|documents|offers)(/[0-9a-fA-F-]{36})?)?)?))")
            ? path : "/other";
    }
}
