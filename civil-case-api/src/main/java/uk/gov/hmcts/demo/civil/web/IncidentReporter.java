package uk.gov.hmcts.demo.civil.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Sends unhandled 500s to the on-call incident endpoint when ONCALL_INCIDENT_URL is set. */
@Component
public class IncidentReporter {
    private static final Logger LOG=LoggerFactory.getLogger(IncidentReporter.class);
    private final String url;
    private final String version;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public IncidentReporter(@Value("${ONCALL_INCIDENT_URL:}") String url, @Value("${DD_VERSION:unknown}") String version) {
        this.url=url==null?"":url.strip();
        this.version=version;
    }

    public void report(Throwable ex,HttpServletRequest request) {
        if (url.isEmpty()) return;
        try {
            StringWriter stack=new StringWriter();
            ex.printStackTrace(new PrintWriter(stack));
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("service","civil-case-api");
            body.put("requestId",Objects.toString(request.getAttribute("requestId"),"unknown"));
            body.put("method",request.getMethod());
            body.put("path",request.getRequestURI());
            body.put("status",500);
            body.put("errorKind",ex.getClass().getName());
            body.put("errorMessage",truncate(Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()),500));
            body.put("stack",truncate(stack.toString(),4000));
            body.put("gitSha",version==null||version.isBlank()?"unknown":truncate(version,64));
            body.put("occurredAt",Instant.now().toString());
            HttpRequest post=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            http.sendAsync(post,HttpResponse.BodyHandlers.discarding())
                .whenComplete((response,error) -> {
                    if (error!=null) LOG.atWarn().addKeyValue("error.kind",error.getClass().getName()).log("Incident report failed");
                    else LOG.atInfo().addKeyValue("status",response.statusCode()).log("Incident reported");
                });
        } catch (Exception reportFailure) {
            LOG.atWarn().addKeyValue("error.kind",reportFailure.getClass().getName()).log("Incident report failed");
        }
    }

    private static String truncate(String value,int max) {
        return value.length()<=max?value:value.substring(0,max);
    }
}
