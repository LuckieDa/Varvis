package org.nbscn.varvis.variantanalysis.benchmark.selection;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthVariant;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.PublicPilotTruthPolicy;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketCaseData;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketEligibilityStatus;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketHpoTerm;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketImportResult;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.springframework.lang.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBenchmarkPilotSelectorTest {
    private final PublicBenchmarkPilotSelector selector = new PublicBenchmarkPilotSelector();

    @Test
    void selectsStableTwentyFiveMainAndFiveBackupsWithoutGeneConcentration() {
        List<PublicBenchmarkSmokeCandidate> candidates = new ArrayList<PublicBenchmarkSmokeCandidate>();
        for (int index = 1; index <= 40; index++) {
            String chromosome = index % 23 == 0 ? "X" : String.valueOf(((index - 1) % 22) + 1);
            boolean indel = index % 4 == 0;
            candidates.add(candidate("case-" + index, chromosome, 1000 + index,
                    indel ? "AT" : "A", indel ? "A" : "G", "GENE" + index,
                    (index % 10) + 1, true));
        }
        PilotSelectionResult first = selector.select(candidates,
                new PublicBenchmarkPilotExclusionRegistry(), 25, 5);
        Collections.reverse(candidates);
        PilotSelectionResult second = selector.select(candidates,
                new PublicBenchmarkPilotExclusionRegistry(), 25, 5);
        assertTrue(first.isComplete());
        assertEquals(25, count(first, PilotCandidateRole.MAIN));
        assertEquals(5, count(first, PilotCandidateRole.BACKUP));
        assertEquals(selectedCaseIds(first), selectedCaseIds(second));
        assertEquals(30, selected(first).stream().map(this::gene).distinct().count());
        assertTrue(selected(first).stream().anyMatch(value -> "SNV".equals(value.getVariantStratum())));
        assertTrue(selected(first).stream().anyMatch(value -> value.getVariantStratum().contains("INDEL")));
    }

    @Test
    void excludesPriorSmokeAcrossAllFourIdentityDimensions() {
        PublicBenchmarkSmokeCandidate byCase = candidate("old-case", "1", 101, "A", "G", "G1", 2, true);
        PublicBenchmarkSmokeCandidate bySource = candidate("new-source", "2", 102, "A", "G", "G2", 2, true);
        PublicBenchmarkSmokeCandidate byTruth = candidate("new-truth", "3", 103, "A", "G", "G3", 2, true);
        PublicBenchmarkSmokeCandidate byTruthSet = candidate("new-set", "4", 104, "A", "G", "G4", 2, true);
        PublicBenchmarkPilotExclusionRegistry prior = new PublicBenchmarkPilotExclusionRegistry();
        prior.getCaseIds().add("old-case");
        prior.getSourceChecksums().add(bySource.getSourceSha256());
        prior.getTruthCanonicalIdentities().add("GRCh38|3|103|A|G");
        prior.getTruthSetFingerprints().add(PublicPilotTruthPolicy.assess(
                byTruthSet.getImported().getCaseData().getDatasetCase()).getTruthSetFingerprint());
        PilotSelectionResult result = selector.select(Arrays.asList(byCase, bySource, byTruth, byTruthSet), prior, 1, 0);
        assertReason(result, "old-case", PilotSelectionExclusionReason.PRIOR_DEVELOPMENT_SMOKE_CASE_ID);
        assertReason(result, "new-source", PilotSelectionExclusionReason.PRIOR_DEVELOPMENT_SMOKE_SOURCE_CHECKSUM);
        assertReason(result, "new-truth", PilotSelectionExclusionReason.PRIOR_DEVELOPMENT_SMOKE_TRUTH_IDENTITY);
        assertReason(result, "new-set", PilotSelectionExclusionReason.PRIOR_DEVELOPMENT_SMOKE_TRUTH_SET);
        assertFalse(result.isComplete());
    }

    @Test
    void requiresFrozenTruthPolicyObservedHpoAndCompleteBedSpan() {
        PublicBenchmarkSmokeCandidate unresolved = candidate("unresolved", "1", 101, "A", "G", "G1", 2, true);
        addTruth(unresolved, "2", 202, "C", "T");
        unresolved.getImported().getCaseData().getDatasetCase().setTruthRelationship("MULTIPLE_VARIANTS");
        PublicBenchmarkSmokeCandidate noHpo = candidate("no-hpo", "3", 303, "A", "G", "G2", 1, true);
        noHpo.getImported().getCaseData().setObservedHpo(Collections.<PhenopacketHpoTerm>emptyList());
        PublicBenchmarkSmokeCandidate outside = candidate("outside", "4", 404, "AT", "A", "G3", 3, false);
        PilotSelectionResult result = selector.select(Arrays.asList(unresolved, noHpo, outside),
                new PublicBenchmarkPilotExclusionRegistry(), 1, 0);
        assertReason(result, "unresolved", PilotSelectionExclusionReason.TRUTH_POLICY_NOT_ELIGIBLE);
        assertReason(result, "no-hpo", PilotSelectionExclusionReason.MISSING_OBSERVED_HPO);
        assertReason(result, "outside", PilotSelectionExclusionReason.TRUTH_OUTSIDE_BENCHMARK_REGION);
    }

    private PublicBenchmarkSmokeCandidate candidate(String id, String chromosome, int position,
                                                     String reference, String alternate, String gene,
                                                     int hpoCount, boolean inside) {
        BenchmarkDatasetCase datasetCase = new BenchmarkDatasetCase();
        datasetCase.setCaseId(id);
        datasetCase.setAssembly("GRCh38");
        datasetCase.setTruthGenes(Collections.singletonList(gene));
        datasetCase.setTruthGeneIds(Collections.singletonList("HGNC:" + Math.abs(gene.hashCode())));
        datasetCase.setDiseaseIds(Collections.singletonList("MONDO:" + Math.abs(id.hashCode())));
        datasetCase.setTruthRelationship("SINGLE_VARIANT");
        datasetCase.setTruthProvenance("synthetic source");
        BenchmarkTruthVariant truth = new BenchmarkTruthVariant();
        truth.setCanonicalIdentity(new NormalizedVariantKey("GRCh38", chromosome, position,
                reference, alternate));
        datasetCase.setTruthVariants(new ArrayList<BenchmarkTruthVariant>(Collections.singletonList(truth)));
        PhenopacketCaseData data = new PhenopacketCaseData();
        data.setCaseId(id);
        data.setDatasetCase(datasetCase);
        data.setTruthGenes(Collections.singletonList(gene));
        data.setDiseaseIds(datasetCase.getDiseaseIds());
        List<PhenopacketHpoTerm> hpo = new ArrayList<PhenopacketHpoTerm>();
        for (int index = 0; index < hpoCount; index++)
            hpo.add(new PhenopacketHpoTerm(String.format("HP:%07d", index + 1),
                    "Synthetic term " + index, false, null));
        data.setObservedHpo(hpo);
        PhenopacketImportResult imported = new PhenopacketImportResult();
        imported.setStatus(PhenopacketEligibilityStatus.ELIGIBLE);
        imported.setCaseData(data);
        PublicBenchmarkSmokeCandidate candidate = new PublicBenchmarkSmokeCandidate();
        candidate.setImported(imported);
        candidate.setSourceRelativePath("public/" + id + ".json");
        candidate.setSourceSha256(String.format("%064x", Math.abs(id.hashCode())));
        candidate.setAllTruthInsideBenchmarkRegion(inside);
        return candidate;
    }

    private void addTruth(PublicBenchmarkSmokeCandidate candidate, String chromosome, int position,
                          String reference, String alternate) {
        BenchmarkTruthVariant truth = new BenchmarkTruthVariant();
        truth.setCanonicalIdentity(new NormalizedVariantKey("GRCh38", chromosome, position,
                reference, alternate));
        candidate.getImported().getCaseData().getDatasetCase().getTruthVariants().add(truth);
    }

    private long count(PilotSelectionResult result, PilotCandidateRole role) { return result.getDecisions().stream().filter(value -> value.getRole() == role).count(); }
    private List<PilotSelectionDecision> selected(PilotSelectionResult result) { return result.getDecisions().stream().filter(value -> value.getRole() == PilotCandidateRole.MAIN || value.getRole() == PilotCandidateRole.BACKUP).sorted(java.util.Comparator.comparingInt((@NonNull PilotSelectionDecision decision) -> decision.getSelectionOrder())).collect(Collectors.toList()); }
    private List<String> selectedCaseIds(PilotSelectionResult result) { return selected(result).stream().map((@NonNull PilotSelectionDecision decision) -> decision.getCaseId()).collect(Collectors.toList()); }
    private String gene(PilotSelectionDecision decision) { return decision.getCandidate().getImported().getCaseData().getTruthGenes().get(0); }
    private void assertReason(PilotSelectionResult result, String caseId, PilotSelectionExclusionReason reason) { assertTrue(result.getDecisions().stream().filter(value -> caseId.equals(value.getCaseId())).findFirst().get().getReasons().contains(reason)); }
}
