package org.nbscn.varvis.ps1pm5acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ps1Pm5PageBoundaryTest {
    private static final String REVIEW_URI = "/variant-analyses/13bce19e-eab3-41d3-8218-5a38c0ae7aa3/acmg/runs/01d0b25d-cf4b-40c8-96fe-51dc045fe2bc/functional-reviews";

    private @org.springframework.lang.NonNull ObjectNode profile() {
        var value = new ObjectMapper().createObjectNode();
        value.put("profileId", "acmg-explicit-business-joint-v8");
        value.put("manifestSha256", "a".repeat(64));
        return java.util.Objects.requireNonNull(value);
    }

    @Test void admittedFunctionalRequestPreservesStale409OnItsServletErrorDispatch() throws Exception {
        var current = profile();
        var saved = new ObjectMapper().createObjectNode();
        saved.set("executionProfile", current.deepCopy());
        var reads = new AtomicInteger();
        var boundary = Ps1Pm5NetworkApplication.publishedArtifactInterceptor(true, false, true, current,
                (analysisId, runId) -> { reads.incrementAndGet(); return saved; });
        var request = new MockHttpServletRequest("POST", REVIEW_URI);
        request.setDispatcherType(DispatcherType.REQUEST);
        var response = new MockHttpServletResponse();
        assertTrue(boundary.preHandle(request, response, this));
        response.sendError(409, "FUNCTIONAL_REVIEW_PREDECESSOR_STALE");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setRequestURI("/error");
        assertTrue(boundary.preHandle(request, response, this));
        assertEquals(409, response.getStatus());
        assertEquals("FUNCTIONAL_REVIEW_PREDECESSOR_STALE", response.getErrorMessage());
        assertEquals(1, reads.get());
    }

    @Test void directOrFabricatedErrorCannotAcquireAdmission() throws Exception {
        var boundary = Ps1Pm5NetworkApplication.publishedArtifactInterceptor(true, false, true, profile(),
                (analysisId, runId) -> { throw new AssertionError("Error routes must not read a saved run"); });
        for (DispatcherType type : new DispatcherType[]{DispatcherType.REQUEST, DispatcherType.ERROR}) {
            var request = new MockHttpServletRequest("POST", "/error");
            request.setDispatcherType(java.util.Objects.requireNonNull(type));
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, REVIEW_URI);
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 409);
            request.addHeader("publishedArtifactBoundaryAdmitted", "true");
            var response = new MockHttpServletResponse();
            assertFalse(boundary.preHandle(request, response, this));
            assertEquals(403, response.getStatus());
        }
    }

    @Test void admissionCannotBeReusedByAnotherRequestOrAnotherErrorTarget() throws Exception {
        var boundary = Ps1Pm5NetworkApplication.publishedArtifactInterceptor(true, false, true, profile(),
                (analysisId, runId) -> { throw new AssertionError("These routes must not read a saved run"); });
        for (DispatcherType type : new DispatcherType[]{DispatcherType.REQUEST, DispatcherType.ERROR}) {
            var request = new MockHttpServletRequest("GET", "/variant-analyses");
            request.setDispatcherType(DispatcherType.REQUEST);
            assertTrue(boundary.preHandle(request, new MockHttpServletResponse(), this));
            request.setMethod("POST");
            request.setDispatcherType(java.util.Objects.requireNonNull(type));
            request.setRequestURI(type == DispatcherType.REQUEST ? "/error" : REVIEW_URI);
            var response = new MockHttpServletResponse();
            assertFalse(boundary.preHandle(request, response, this));
            assertEquals(403, response.getStatus());
            if (type == DispatcherType.REQUEST) {
                request.setDispatcherType(DispatcherType.ERROR);
                assertFalse(boundary.preHandle(request, new MockHttpServletResponse(), this));
            }
        }
    }

    @Test void historicalFunctionalRunAndItsErrorDispatchStayForbidden() throws Exception {
        var current = profile();
        var historical = current.deepCopy().put("manifestSha256", "b".repeat(64));
        var saved = new ObjectMapper().createObjectNode();
        saved.set("executionProfile", historical);
        var reads = new AtomicInteger();
        var boundary = Ps1Pm5NetworkApplication.publishedArtifactInterceptor(true, false, true, current,
                (analysisId, runId) -> { reads.incrementAndGet(); return saved; });
        var request = new MockHttpServletRequest("POST", REVIEW_URI);
        request.setDispatcherType(DispatcherType.REQUEST);
        var response = new MockHttpServletResponse();
        assertFalse(boundary.preHandle(request, response, this));
        assertEquals(403, response.getStatus());
        request.setDispatcherType(DispatcherType.ERROR);
        request.setRequestURI("/error");
        assertFalse(boundary.preHandle(request, new MockHttpServletResponse(), this));
        assertEquals(1, reads.get());
    }

    @Test void readonlyPageNeverAdmitsAFunctionalPostOrItsErrorDispatch() throws Exception {
        var boundary = Ps1Pm5NetworkApplication.publishedArtifactInterceptor(true, true, true, profile(),
                (analysisId, runId) -> { throw new AssertionError("Read-only pages must not read a writable run"); });
        var request = new MockHttpServletRequest("POST", REVIEW_URI);
        request.setDispatcherType(DispatcherType.REQUEST);
        assertFalse(boundary.preHandle(request, new MockHttpServletResponse(), this));
        request.setDispatcherType(DispatcherType.ERROR);
        request.setRequestURI("/error");
        assertFalse(boundary.preHandle(request, new MockHttpServletResponse(), this));
    }

    @Test void jointAdoptionIsLimitedToTheExactNewExecutionIdentity() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var current = mapper.createObjectNode();
        current.put("profileId", "acmg-explicit-business-joint-v8");
        current.put("manifestSha256", "a".repeat(64));
        assertTrue(Ps1Pm5NetworkApplication.allowsFunctionalReview(current.deepCopy(), current));
        var changed = current.deepCopy();
        changed.put("manifestSha256", "b".repeat(64));
        assertFalse(Ps1Pm5NetworkApplication.allowsFunctionalReview(changed, current));
        changed.put("profileId", "acmg-explicit-business-joint-v1");
        assertFalse(Ps1Pm5NetworkApplication.allowsFunctionalReview(changed, current));
        assertFalse(Ps1Pm5NetworkApplication.allowsRequest(true, true, "POST",
                "/variant-analyses/13bce19e-eab3-41d3-8218-5a38c0ae7aa3/acmg/runs/run/functional-review"));
    }

    @Test void restoredPageAllowsReadsAndDeniesEveryWriteIncludingEvaluation() {
        String run = "/variant-analyses/13bce19e-eab3-41d3-8218-5a38c0ae7aa3/acmg/runs";
        assertTrue(Ps1Pm5NetworkApplication.allowsRequest(true, true, "GET", run));
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            assertFalse(Ps1Pm5NetworkApplication.allowsRequest(true, true, method, run));
            assertFalse(Ps1Pm5NetworkApplication.allowsRequest(true, true, method, "/variant-analyses"));
        }
        assertTrue(Ps1Pm5NetworkApplication.allowsRequest(true, false, "POST", run));
        assertFalse(Ps1Pm5NetworkApplication.allowsRequest(true, false, "POST", "/variant-analyses"));
    }
}
