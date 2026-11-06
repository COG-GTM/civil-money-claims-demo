package uk.gov.hmcts.demo.civil.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.*;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record ErrorBody(int status,String code,String message,String requestId,Map<String,String> fieldErrors) {}

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> api(ApiException ex,HttpServletRequest request) {
        return error(ex.status,ex.code,ex.getMessage(),ex.fieldErrors,request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException ex,HttpServletRequest request) {
        Map<String,String> fields=new TreeMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(),e.getDefaultMessage()));
        return error(400,"VALIDATION_ERROR","Check the information you entered",fields,request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,
        MissingServletRequestPartException.class,MissingServletRequestParameterException.class,ConstraintViolationException.class})
    public ResponseEntity<ErrorBody> badRequest(Exception ex,HttpServletRequest request) {
        return error(400,"INVALID_REQUEST","Check the request fields and their formats",Map.of(),request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorBody> tooLarge(MaxUploadSizeExceededException ex,HttpServletRequest request) {
        return error(413,"FILE_TOO_LARGE","The file must be 10 MB or smaller",Map.of("file","The file must be 10 MB or smaller"),request);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorBody> multipart(MultipartException ex,HttpServletRequest request) {
        return error(400,"INVALID_REQUEST","The multipart upload could not be read",Map.of(),request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorBody> media(HttpMediaTypeNotSupportedException ex,HttpServletRequest request) {
        return error(415,"UNSUPPORTED_MEDIA_TYPE","This request media type is not supported",Map.of(),request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> notFound(NoResourceFoundException ex,HttpServletRequest request) {
        return error(404,"NOT_FOUND","The requested resource was not found",Map.of(),request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> method(HttpRequestMethodNotSupportedException ex,HttpServletRequest request) {
        return error(405,"METHOD_NOT_ALLOWED","This request method is not supported",Map.of(),request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorBody> conflict(OptimisticLockingFailureException ex,HttpServletRequest request) {
        return error(409,"VERSION_CONFLICT","The claim changed. Refresh it and try again",Map.of(),request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unhandled(Exception ex,HttpServletRequest request) {
        LOG.atError().addKeyValue("error.kind",ex.getClass().getName())
            .addKeyValue("error.message",ex.getMessage()).setCause(ex).log("Unhandled request failure");
        return error(500,"INTERNAL_ERROR","Sorry, there is a problem with the service. Reference: "+request.getAttribute("requestId"),
            Map.of(),request);
    }

    private ResponseEntity<ErrorBody> error(int status,String code,String message,Map<String,String> fields,HttpServletRequest request) {
        // Ops retains a safe public message, never request payloads or exception details.
        request.setAttribute("errorMessage",message);
        return ResponseEntity.status(status).body(new ErrorBody(status,code,message,
            Objects.toString(request.getAttribute("requestId"),"unknown"),fields));
    }
}
