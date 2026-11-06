package uk.gov.hmcts.demo.civil.web;

import java.util.Map;

public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;
    public final String code;
    public final transient Map<String, String> fieldErrors;
    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }
    public ApiException(int status, String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }
    public static ApiException invalid(String field, String message) {
        return new ApiException(400, "VALIDATION_ERROR", message, Map.of(field, message));
    }
    public static ApiException missing() {
        return new ApiException(404, "NOT_FOUND", "The requested resource was not found");
    }
    public static ApiException conflict(String message) {
        return new ApiException(409, "ILLEGAL_STATE", message);
    }
}
