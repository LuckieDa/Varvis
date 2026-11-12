package org.nbscn.varvis.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(VariantAnalysisPersistenceException.class)
    public ResponseEntity<Map<String, Object>> handleVariantAnalysisPersistence(
            VariantAnalysisPersistenceException ex) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", "Service Unavailable");
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(VariantAnalysisInputException.class)
    public ResponseEntity<Map<String, Object>> handleVariantAnalysisInput(
            VariantAnalysisInputException ex) {
        return badRequest(ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(InvalidVcfUploadException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidVcfUpload(InvalidVcfUploadException ex) {
        return badRequest("INVALID_INPUT", ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.PAYLOAD_TOO_LARGE.value());
        body.put("error", "Payload Too Large");
        body.put(
                "message",
                "Uploaded VCF exceeds the configured request size limit. "
                        + "Current default allows up to 512MB per file and 600MB per multipart request."
        );
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Bad Request");
        body.put("errorCode", errorCode);
        body.put("message", message);
        return ResponseEntity.badRequest().body(body);
    }
}
