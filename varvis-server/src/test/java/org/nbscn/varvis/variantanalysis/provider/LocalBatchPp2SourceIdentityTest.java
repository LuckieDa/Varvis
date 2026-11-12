package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks;
import org.springframework.core.io.ClassPathResource;

/** Request lifecycle controls. No live model, native fetch, clinical case or positive source review is produced. */
class LocalBatchPp2SourceIdentityTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    final AtomicInteger scientificFactories = new AtomicInteger(), sourceFactories = new AtomicInteger(), scopeChecks = new AtomicInteger();

    @Test void rasOnlySelectionDoesNotOpenSourceOrScientificMaterialBeforeCacheLookup() {
        var source = source();
        var runtime = runtime(source, scope -> { scopeChecks.incrementAndGet(); return false; });
        var identity = runtime.preparePp2OtherPhenotypeSources(List.of(target("RAS", "h-ras", "HGNC:1", "MONDO:0000001")));
        assertEquals("NOT_REQUIRED_FOR_SELECTED_BACKGROUNDS", identity.path("status").asText());
        assertEquals(1, scopeChecks.get()); assertEquals(0, sourceFactories.get()); assertEquals(0, scientificFactories.get());
    }

    @Test void unselectedPp2DoesNotInspectBackgroundOrAcquireAnyPp2Dependency() {
        var runtime = runtime(source(), scope -> { throw new AssertionError("PP2 was not selected"); });
        runtime.bindExecutionCriteria(List.of("ACMG_AMP_2015:PM2"));
        assertEquals("NOT_SELECTED", runtime.preparePp2OtherPhenotypeSources(List.of(target("TPM1", "h", "HGNC:12010", "MONDO:0005045"))).path("status").asText());
        assertEquals(0, sourceFactories.get()); assertEquals(0, scientificFactories.get());
    }

    @Test void oneFrozenObservationFiltersTargetsAndPreservesFailureSourcesBeforeExecution() {
        var source = source(); var runtime = runtime(source, hcmScope());
        var targets = List.of(target("TPM1", "h-hcm", "HGNC:12010", "MONDO:0005045"), target("RAS", "h-ras", "HGNC:1", "MONDO:0000001"));
        var first = runtime.preparePp2OtherPhenotypeSources(targets);
        assertTrue(first.path("observationSha256").asText().matches("[a-f0-9]{64}"));
        assertEquals(first, runtime.preparePp2OtherPhenotypeSources(targets));
        assertEquals(1, sourceFactories.get()); assertEquals(0, scientificFactories.get());
        var batch = runtime.notExecutedFullRnaSnapshot("UNAVAILABLE", "ENGINEERING_SOURCE_RECOVERY_FAILURE");
        var archive = batch.path("pp2OtherPhenotypeArchive");
        assertEquals(1, archive.at("/sourceArchive/observation/discoveryTargets").size());
        assertEquals("h-hcm", archive.at("/sourceArchive/observation/discoveryTargets/0/hypothesisId").asText());
        assertTrue(archive.path("judgments").isEmpty());
        assertEquals(archive, Pp2OtherPhenotypeArchive.verifyAndExport(mapper, archive, batch));
        assertFalse(LocalEvidenceBatchRuntime.publicView(mapper, batch).has("pp2OtherPhenotypeArchive"));
    }

    @Test void changedTargetHintsCannotReplaceTheAlreadyFrozenRequestObservation() {
        var runtime = runtime(source(), hcmScope());
        var original = target("TPM1", "h", "HGNC:12010", "MONDO:0005045");
        var changedHints = original.formalCandidate(); changedHints.put("publicAlias", "公开检索别名");
        runtime.preparePp2OtherPhenotypeSources(List.of(original));
        var changed = new Pp2OtherPhenotypeSource.DiscoveryTarget(original.candidateId(), original.hypothesisId(), original.genomicAllele(),
                original.hgncId(), original.geneSymbol(), original.mondoId(), original.moi(), changedHints);
        var failure = assertThrows(IllegalStateException.class, () -> runtime.preparePp2OtherPhenotypeSources(List.of(changed)));
        assertEquals("PP2_DISCOVERY_TARGETS_CHANGED_DURING_REQUEST", failure.getMessage());
        assertEquals(1, sourceFactories.get()); assertEquals(0, scientificFactories.get());
    }

    @Test void aDifferentSourceCapabilityDoesNotBecomeTheDeclaredSource() {
        var source = source(); var wrong = source.identity().put("methodVersion", "UNDECLARED");
        var runtime = base().withPp2OtherPhenotypePreparation(() -> source, wrong, hcmScope());
        var identity = runtime.preparePp2OtherPhenotypeSources(List.of(target("TPM1", "h", "HGNC:12010", "MONDO:0005045")));
        assertEquals("UNAVAILABLE", identity.path("status").asText());
        assertEquals("PP2_SOURCE_CAPABILITY_CHANGED_DURING_REQUEST", identity.path("reasonCode").asText());
        assertEquals(0, scientificFactories.get());
    }

    @Test void scopeAndSourceVersionsAreBoundWithoutOpeningTheScientificFactory() {
        var runtime = runtime(source(), hcmScope());
        assertEquals(GeneralPp2Runtime.HCM_TRANSCRIPT_SCOPE_VERSION, runtime.identity().at("/pp2HcmExecution/transcriptScopeVersion").asText());
        assertEquals(Pp2OtherPhenotypeSource.VERSION, runtime.identity().at("/pp2HcmExecution/sourceVersion").asText());
        assertEquals(0, sourceFactories.get()); assertEquals(0, scientificFactories.get());
    }

    private Predicate<ObjectNode> hcmScope() {
        return scope -> "HGNC:12010".equals(scope.at("/gene/hgncId").asText()) && "MONDO:0005045".equals(scope.at("/disease/mondoId").asText());
    }
    private Pp2OtherPhenotypeSource source() {
        return Pp2OtherPhenotypeSource.forWorkflow(mapper, temporary.resolve("missing-workflow-config.json"), "a".repeat(64),
                temporary.resolve("source-work"), () -> { throw new AssertionError("Missing config cannot open a native source"); });
    }
    private LocalEvidenceBatchRuntime runtime(Pp2OtherPhenotypeSource source, Predicate<ObjectNode> scope) {
        return base().withPp2OtherPhenotypePreparation(() -> { sourceFactories.incrementAndGet(); return source; }, source.identity(), scope);
    }
    private LocalEvidenceBatchRuntime base() {
        var checks = new SplicingExperimentChecks(mapper, new ClassPathResource("acmg/public-splicing-experiments-v1.json"),
                temporary.resolve("unused-experiments"), (a, r) -> { throw new AssertionError("No experiment acquisition"); });
        Supplier<GeneralPp2Runtime> pp2 = () -> { scientificFactories.incrementAndGet(); throw new AssertionError("No pre-request scientific factory"); };
        return new LocalEvidenceBatchRuntime(mapper, mapper.createObjectNode(), null, null, null, null,
                new Bp7SelectedBatch(mapper, checks.captureCatalog()),
                () -> { throw new AssertionError("No PM4"); }, () -> { throw new AssertionError("No PVS1"); },
                () -> { throw new AssertionError("No BP3"); }, () -> { throw new AssertionError("No PM1"); }, pp2,
                () -> { throw new AssertionError("No BP1"); }, true, null);
    }
    private Pp2OtherPhenotypeSource.DiscoveryTarget target(String symbol, String hypothesis, String hgnc, String mondo) {
        var allele = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "15").put("position", 63042833)
                .put("reference", "G").put("alternate", "A");
        return new Pp2OtherPhenotypeSource.DiscoveryTarget("engineering-" + symbol, hypothesis, allele, hgnc, symbol, mondo,
                "AUTOSOMAL_DOMINANT", mapper.createObjectNode().put("scope", "PUBLIC_VARIANT_DISCOVERY_HINTS_ONLY"));
    }
}
