package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.ExplicitCaseInputCoordinator.*;
import static org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.*;

/** Synthetic tool output; the real reference-equivalence verifier and source/context assembly execute. */
class Bs1LocalNormalizationTest {
    @TempDir Path temporary;
    @Test void selectedNormalizationPreservesPublishedIdentityAndAssemblesBs1Context() throws Exception { check(false); }
    @Test void failedToolCannotQueryOriginalKeyOrProduceContext() throws Exception { check(true); }
    void check(boolean unavailable) throws Exception {
        var fixture = new TopNSelectedNormalizationTest(); fixture.temporary = temporary; fixture.mapper.findAndRegisterModules();
        var f = fixture.fixture("ATCG"); var run = fixture.run(2, "T", "TT", "TT", "0|1");
        var candidates = run.execution().getInterpretations(); var candidate = candidates.get(0);
        var mapping = new ExplicitCaseInputCoordinatorTest(); mapping.temporary = temporary; mapping.setup();
        candidate.applyTranscriptAnnotations(mapping.annotations.get("public-hbb-compatible-e7v"));
        var before = fixture.mapper.valueToTree(candidate); var calls = new AtomicInteger();
        VcfNormalizer tool = (input, directory, assembly) -> {
            calls.incrementAndGet();
            if (unavailable) throw new IllegalStateException("Could not start bcftools");
            var output = Files.writeString(directory.resolve("synthetic-normalized.vcf"),
                    "##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr11\t1\tVARVIS_SELECTED_ALT\tA\tAT\t.\tPASS\t.\n");
            var manifest = new NormalizationRunManifest("PASSED", "GRCh38", "SYNTHETIC", f.properties().getNormalization().referenceSha256For(GenomeAssembly.GRCh38),
                    "bcftools", "SYNTHETIC", "SYNTHETIC", false, 1, 1, 0, 1, 0, 1, "2026-10-09T00:00:00Z", "2026-10-09T00:00:01Z", null, null, "SYNTHETIC");
            return new NormalizationResult(output, true, "SYNTHETIC", List.of(), manifest);
        };
        ReflectionTestUtils.setField(requireNonNull(f.producer()), "selectedNormalizer", new SelectedAlleleNormalizer(fixture.mapper, tool));
        var provenance = new HypothesisProvenance("SYNTHETIC_CALLER", "1.0.0", "synthetic/bs1-normalization");
        var choice = mapping.choices.path("fixedChoices").get(0);
        var hypothesis = new PatientDiseaseHypothesis(candidate.getCandidateId(), "test-hypothesis", "HGNC:4827",
                choice.path("syntheticHypothesisMondoId").asText(), "AR", provenance);
        var declaration = new CaseDeclaration(run.job().getId(), run.execution().getInputSource(), "synthetic-proband", "SYNTHETIC",
                new PatientDeclaration("patient-context-bs1-normalization", "PROBAND", "UNKNOWN", null, provenance), List.of(new HypothesisInput(hypothesis, "UNKNOWN", null)));
        var coordinator = new ExplicitCaseInputCoordinator(fixture.mapper, mapping.resolver, f.producer(), mapping.sources);
        var result = coordinator.assembleBs1(run.job(), run.conversion(), run.execution(), new AnalysisContext(run.job().getId(), GenomeAssembly.GRCh38, AnalysisMode.WES),
                candidates, 1, List.of(declaration), Map.of(), true);
        var attempt = result.assembly().candidates().get(0).attempts().get(0);
        assertEquals(1, calls.get(), result.production().provenance().toPrettyString());
        assertEquals(before, fixture.mapper.valueToTree(candidate));
        if (unavailable) {
            assertNull(attempt.context());
            assertTrue(attempt.blockers().stream().anyMatch(b -> "NORMALIZATION_TOOL_UNAVAILABLE".equals(b.reasonCode())), attempt.blockers().toString());
        } else {
            assertTrue(attempt.blockers().isEmpty(), attempt.blockers().toString());
            assertEquals(2, attempt.context().at("/variant/inputAllele/position").asInt());
            assertEquals(1, attempt.context().at("/variant/evidenceQueryAllele/position").asInt());
            assertEquals("AT", attempt.context().at("/variant/evidenceQueryAllele/alternate").asText());
            assertEquals("NORMALIZED", attempt.context().at("/variant/normalization/status").asText());
        }
    }
}
