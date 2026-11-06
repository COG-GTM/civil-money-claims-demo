import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.StandardEnvironment;

/** Standalone smoke check of the exact Spring Boot JSON encoder configuration. */
class LoggingSmoke {
    public static void main(String[] args) {
        LoggingSystem system = LoggingSystem.get(LoggingSmoke.class.getClassLoader());
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(new StandardEnvironment()), args[0], null);
        MDC.put("request_id", "logging-smoke");
        MDC.put("dd.trace_id", "123");
        MDC.put("dd.span_id", "456");
        LoggerFactory.getLogger(LoggingSmoke.class).atError().addKeyValue("method", "POST")
            .addKeyValue("path", "/synthetic").addKeyValue("status", 500).addKeyValue("duration_ms", 12)
            .setCause(new IllegalStateException("Synthetic logging smoke failure")).log("Unhandled request error");
        MDC.clear();
        system.cleanUp();
    }
}
