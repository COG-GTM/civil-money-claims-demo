package uk.gov.hmcts.demo.legacy.web;

import java.util.Collections;
import java.util.Map;

public class LegacyApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, String> fieldErrors;

    public LegacyApiException(int status, String code, String message) {
        this(status, code, message, Collections.emptyMap());
    }

    public LegacyApiException(int status, String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
}
