package org.nbscn.varvis.variantanalysis.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.ExternalProcessRunner;
import org.nbscn.varvis.variantanalysis.provider.ProcessResult;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class Ps1Pm5NetworkObservationsTest {
    @TempDir Path output;

    @Test void referencePermissionDoesNotAuthorizePublicationOrAnOriginalAnalysis() throws Exception {
        var observations = new Ps1Pm5NetworkObservations(output, true, true, true);
        var delegated = new AtomicInteger();
        ExternalProcessRunner runner = (command, directory, timeout) -> {
            delegated.incrementAndGet();
            return new ProcessResult(0, "bounded reference fixture", false);
        };
        var reference = wrapped(observations, "vepComparator", runner);
        assertTrue(reference.run(List.of("reference"), output, Duration.ofSeconds(1)).isSuccess());
        assertEquals(1, delegated.get());
        var publication = wrapped(observations, "vepPublication", runner);
        assertThrows(IllegalStateException.class,
                () -> publication.run(List.of("publication"), output, Duration.ofSeconds(1)));
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(observations, "requireModelAuthorization"));
        assertEquals(1, delegated.get());
        assertEquals(1, observations.snapshot().get("vepComparator"));
        assertEquals(0, observations.snapshot().get("vepPublication"));
        assertEquals(0, observations.snapshot().get("exomiser"));
        assertEquals(2, observations.snapshot().get("blockedModelLaunch"));
    }

    @Test void publishedReuseWithoutReferencePermissionDoesNotDelegate() {
        var observations = new Ps1Pm5NetworkObservations(output, true, true);
        ExternalProcessRunner runner = (command, directory, timeout) -> {
            throw new AssertionError("An unapproved model must never run");
        };
        var reference = wrapped(observations, "vepComparator", runner);
        assertThrows(IllegalStateException.class,
                () -> reference.run(List.of("reference"), output, Duration.ofSeconds(1)));
        assertEquals(0, observations.snapshot().get("vepComparator"));
        assertEquals(1, observations.snapshot().get("blockedModelLaunch"));
    }

    private static ExternalProcessRunner wrapped(Ps1Pm5NetworkObservations observations, String name,
                                                 ExternalProcessRunner delegate) {
        return Objects.requireNonNull(ReflectionTestUtils.<ExternalProcessRunner>invokeMethod(
                Objects.requireNonNull(observations), "countedVep", name, delegate));
    }
}
