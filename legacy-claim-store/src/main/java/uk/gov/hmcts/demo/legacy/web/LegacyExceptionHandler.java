package uk.gov.hmcts.demo.legacy.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class LegacyExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyExceptionHandler.class);

    @ExceptionHandler(LegacyApiException.class)
    public ResponseEntity<Map<String, Object>> domain(LegacyApiException exception, HttpServletRequest request) {
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(), exception.getFieldErrors(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(Exception exception, HttpServletRequest request) {
        return response(413, "FILE_TOO_LARGE", "The file must be 10 MiB or smaller", Map.of(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class, MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception exception, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", "Check the request format and required fields", Map.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> unsupported(Exception exception, HttpServletRequest request) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "Use the supported content type for this endpoint", Map.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> missing(Exception exception, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Resource not found", Map.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> wrongMethod(Exception exception, HttpServletRequest request) {
        return response(405, "METHOD_NOT_ALLOWED", "This method is not supported", Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unhandled(Exception exception, HttpServletRequest request) {
        // JDBI exception messages contain bound JSON and security codes; retain frames, not values.
        LOG.atError().addKeyValue("error.kind", exception.getClass().getName())
            .addKeyValue("error.message", "Unhandled legacy claim request failure")
            .addKeyValue("error.stack", safeStack(exception)).log("Unhandled legacy claim request failure");
        return response(500, "INTERNAL_ERROR", "Sorry, there is a problem with the service. Reference: " + request.getAttribute("requestId"), Map.of(), request);
    }

    public static String safeStack(Throwable exception) {
        StringBuilder stack = new StringBuilder();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (exception != null && seen.add(exception)) {
            stack.append(exception.getClass().getName()).append('\n');
            for (StackTraceElement frame : exception.getStackTrace()) stack.append("\tat ").append(frame).append('\n');
            exception = exception.getCause();
            if (exception != null) stack.append("Caused by: ");
        }
        return stack.toString();
    }

    private ResponseEntity<Map<String, Object>> response(int status, String code, String message,
            Map<String, String> errors, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", request.getAttribute("requestId"));
        if (!errors.isEmpty()) body.put("fieldErrors", errors);
        return ResponseEntity.status(status).body(body);
    }
}
