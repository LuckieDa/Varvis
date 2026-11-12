package org.nbscn.varvis.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTest {

    @Test
    void databaseFailureReturns503WithoutLeakingSqlOrConnectionDetails() {
        var response = new ApiExceptionHandler().handleVariantAnalysisPersistence(
                new VariantAnalysisPersistenceException("ANALYSIS_DATABASE_SCHEMA_INCOMPATIBLE",
                        "schema mismatch", new IllegalStateException("private connection details")));
        var body = Objects.requireNonNull(response.getBody());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("ANALYSIS_DATABASE_SCHEMA_INCOMPATIBLE", body.get("errorCode"));
        assertEquals("schema mismatch", body.get("message"));
        org.junit.jupiter.api.Assertions.assertFalse(body.toString().contains("private connection details"));
    }

    @Test
    void returnsSafeStructuredInputError() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        ResponseEntity<Map<String, Object>> response = handler.handleVariantAnalysisInput(
                new VariantAnalysisInputException("INVALID_INPUT", "A compressed VCF could not be indexed."));
        Map<String, Object> body = Objects.requireNonNull(response.getBody(), "response body");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_INPUT", body.get("errorCode"));
        assertEquals("A compressed VCF could not be indexed.", body.get("message"));
    }
}
