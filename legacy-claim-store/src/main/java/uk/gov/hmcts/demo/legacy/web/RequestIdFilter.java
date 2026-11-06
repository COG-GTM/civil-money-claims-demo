package uk.gov.hmcts.demo.legacy.web;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-ID");
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9_-]{1,100}") ? supplied : UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", requestId);
        request.setAttribute("requestId", requestId);
        MDC.put("request_id", requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            LOG.atInfo().addKeyValue("method", request.getMethod()).addKeyValue("path", request.getRequestURI())
                .addKeyValue("status", response.getStatus()).addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000)
                .log("HTTP request");
            MDC.remove("request_id");
        }
    }
}
