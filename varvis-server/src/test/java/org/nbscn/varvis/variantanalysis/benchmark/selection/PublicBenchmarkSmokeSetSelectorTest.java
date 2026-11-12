package org.nbscn.varvis.variantanalysis.benchmark.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthVariant;
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
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBenchmarkSmokeSetSelectorTest {
    private final PublicBenchmarkSmokeSetSelector selector = new PublicBenchmarkSmokeSetSelector();

    @Test
    void selectsExactlyFiveDeterministicallyAcrossInputOrder() {
        List<PublicBenchmarkSmokeCandidate> candidates = Arrays.asList(
                candidate("case-e", "5", 500, "AT", "A", "GENE5", "MONDO:5", true, true),
                candidate("case-c", "3", 300, "G", "A", "GENE3", "MONDO:3", true, false),
                candidate("case-a", "1", 100, "A", "G", "GENE1", "MONDO:1", true, true),
                candidate("case-f", "6", 600, "C", "T", "GENE6", "MONDO:6", true, false),
                candidate("case-b", "2", 200, "AT", "A", "GENE2", "MONDO:2", true, false),
                candidate("case-d", "4", 400, "C", "T", "GENE4", "MONDO:4", true, false));
        SmokeSetSelectionResult first = selector.select(candidates);
        List<PublicBenchmarkSmokeCandidate> reversed = new ArrayList<PublicBenchmarkSmokeCandidate>(candidates);
        Collections.reverse(reversed);
        SmokeSetSelectionResult second = selector.select(reversed);
        assertTrue(first.isComplete());
        assertEquals(5, selected(first).size());
        assertEquals(selected(first), selected(second));
        assertEquals(first.getAlgorithmFingerprint(), second.getAlgorithmFingerprint());
        assertTrue(first.getAlgorithmFingerprint().matches("[0-9a-f]{64}"));
        assertTrue(selectedCandidates(first).stream().anyMatch(this::isIndel));
        assertTrue(selectedCandidates(first).stream().anyMatch(this::isSnv));
        assertTrue(selectedCandidates(first).stream().map(this::chromosome).distinct().count() >= 3);
    }

    @Test
    void excludesDuplicateIdentityNonGrch38OutsideBedAndUnsafeSource() {
        PublicBenchmarkSmokeCandidate duplicateA = candidate("dup-a", "1", 100, "A", "G", "GA", "MONDO:A", true, false);
        PublicBenchmarkSmokeCandidate duplicateB = candidate("dup-b", "1", 100, "A", "G", "GB", "MONDO:B", true, false);
        PublicBenchmarkSmokeCandidate grch37 = candidate("grch37", "2", 200, "A", "T", "GC", "MONDO:C", true, false);
        grch37.getImported().getCaseData().getDatasetCase().setAssembly("GRCh37");
        PublicBenchmarkSmokeCandidate outside = candidate("outside", "3", 300, "A", "T", "GD", "MONDO:D", false, false);
        PublicBenchmarkSmokeCandidate unsafe = candidate("unsafe", "4", 400, "A", "T", "GE", "MONDO:E", true, false);
        unsafe.setSourceRelativePath("../escape.json");
        SmokeSetSelectionResult result = selector.select(Arrays.asList(duplicateA, duplicateB, grch37, outside, unsafe));
        assertReason(result, "dup-a", SmokeSetExclusionReason.DUPLICATE_TRUTH_IDENTITY);
        assertReason(result, "dup-b", SmokeSetExclusionReason.DUPLICATE_TRUTH_IDENTITY);
        assertReason(result, "grch37", SmokeSetExclusionReason.NON_GRCH38);
        assertReason(result, "outside", SmokeSetExclusionReason.TRUTH_OUTSIDE_BENCHMARK_REGION);
        assertReason(result, "unsafe", SmokeSetExclusionReason.UNSAFE_SOURCE_REFERENCE);
        assertFalse(result.isComplete());
    }

    @Test
    void rejectsDuplicateCaseIdsEvenWhenTruthDiffers() {
        PublicBenchmarkSmokeCandidate first = candidate("same-case", "1", 100, "A", "G", "GA", "MONDO:A", true, false);
        PublicBenchmarkSmokeCandidate second = candidate("same-case", "2", 200, "C", "T", "GB", "MONDO:B", true, false);
        SmokeSetSelectionResult result = selector.select(Arrays.asList(first, second));
        assertEquals(2, result.getDecisions().stream()
                .filter(decision -> decision.getReasons().contains(SmokeSetExclusionReason.DUPLICATE_CASE_ID)).count());
    }

    @Test
    void bedIndexRequiresCompleteReferenceSpan(@TempDir Path tempDirectory) throws Exception {
        Path bed = tempDirectory.resolve("regions.bed");
        Files.write(bed, Collections.singletonList("1\t99\t103"), StandardCharsets.UTF_8);
        PublicBenchmarkSmokeSetFreezer.BedIndex index = PublicBenchmarkSmokeSetFreezer.BedIndex.read(bed);
        assertTrue(index.contains("chr1", 99, 103));
        assertFalse(index.contains("1", 99, 104));
        assertFalse(index.contains("1", 98, 103));
    }

    @Test
    void registryBlocksCaseChecksumAndTruthDespiteRenameAndRoundTrips() throws Exception {
        PublicBenchmarkSmokeCandidate selected = candidate("case-a", "1", 100, "A", "G", "GENE1", "MONDO:1", true, false);
        SmokeLockedTestExclusionRegistry registry = new SmokeLockedTestExclusionRegistry();
        registry.setDatasetId("phenopacket-store-0.1.27-grch38-singleton-smoke-v1");
        registry.getCaseIds().add("case-a");
        registry.getPhenopacketSha256().add(selected.getSourceSha256());
        registry.getTruthCanonicalIdentities().add("GRCh38|1|100|A|G");
        assertTrue(registry.excludes(selected));
        PublicBenchmarkSmokeCandidate renamed = candidate("renamed", "9", 900, "A", "C", "OTHER", "MONDO:9", true, false);
        renamed.setSourceSha256(selected.getSourceSha256());
        assertTrue(registry.excludes(renamed));
        PublicBenchmarkSmokeCandidate sameTruth = candidate("another", "1", 100, "A", "G", "OTHER2", "MONDO:10", true, false);
        assertTrue(registry.excludes(sameTruth));
        String json = new ObjectMapper().writeValueAsString(registry);
        SmokeLockedTestExclusionRegistry decoded = new ObjectMapper().readValue(json, SmokeLockedTestExclusionRegistry.class);
        assertEquals(registry.getCaseIds(), decoded.getCaseIds());
        assertEquals(1, decoded.getCaseIds().size());
    }

    @Test
    void insufficientCandidatesAndMultiTruthRemainExplicit() {
        PublicBenchmarkSmokeCandidate multi = candidate("multi", "1", 100, "A", "G", "G1", "MONDO:1", true, false);
        addTruth(multi, "2", 200, "AT", "A");
        SmokeSetSelectionResult result = selector.select(Arrays.asList(multi,
                candidate("single", "3", 300, "C", "T", "G2", "MONDO:2", true, false)));
        assertFalse(result.isComplete());
        assertTrue(result.getDecisions().stream().filter(d -> d.getStatus() == SmokeSelectionStatus.SELECTED)
                .allMatch(d -> d.getReasons().contains(SmokeSetExclusionReason.INSUFFICIENT_COVERAGE)));
    }

    @Test
    void inputPlanKeepsG2PendingAndOnlyMaterializesFrozenG0AndG1Hpo() {
        PublicBenchmarkSmokeCandidate candidate = candidate("case-a", "1", 100, "A", "G", "GENE1", "MONDO:1", true, true);
        SmokeSetSelectionDecision decision = new SmokeSetSelectionDecision();
        decision.setCaseId("case-a"); decision.setCandidate(candidate); decision.setStatus(SmokeSelectionStatus.SELECTED);
        Map<String,Object> plan = new PublicBenchmarkSmokeSetFreezer().inputPlan(
                Collections.singletonList(decision), String.format("%064x", 1));
        Map<?,?> casePlan = (Map<?,?>) ((List<?>) plan.get("cases")).get(0);
        List<?> groups = (List<?>) casePlan.get("groups");
        assertEquals(Collections.emptyList(), ((Map<?,?>) groups.get(0)).get("hpoTerms"));
        assertEquals(Collections.singletonList("HP:0000001"), ((Map<?,?>) groups.get(1)).get("hpoTerms"));
        assertEquals("HUMAN_REVIEW_PENDING", ((Map<?,?>) groups.get(2)).get("status"));
        assertEquals(Collections.emptyList(), ((Map<?,?>) groups.get(2)).get("hpoTerms"));
        assertEquals(Boolean.FALSE, plan.get("executable"));
    }

    private PublicBenchmarkSmokeCandidate candidate(String id, String chromosome, int position,
                                                     String ref, String alt, String gene, String disease,
                                                     boolean inside, boolean excludedHpo) {
        BenchmarkDatasetCase datasetCase = new BenchmarkDatasetCase();
        datasetCase.setCaseId(id); datasetCase.setAssembly("GRCh38");
        datasetCase.setTruthGenes(Collections.singletonList(gene));
        datasetCase.setDiseaseIds(Collections.singletonList(disease));
        BenchmarkTruthVariant truth = new BenchmarkTruthVariant();
        truth.setCanonicalIdentity(new NormalizedVariantKey("GRCh38", chromosome, position, ref, alt));
        datasetCase.setTruthVariants(new ArrayList<BenchmarkTruthVariant>(Collections.singletonList(truth)));
        PhenopacketCaseData data = new PhenopacketCaseData();
        data.setCaseId(id); data.setDatasetCase(datasetCase);
        data.setTruthGenes(Collections.singletonList(gene)); data.setDiseaseIds(Collections.singletonList(disease));
        data.setObservedHpo(Collections.singletonList(new PhenopacketHpoTerm("HP:0000001", "synthetic", false, null)));
        data.setExcludedHpo(excludedHpo ? Collections.singletonList(new PhenopacketHpoTerm("HP:0000002", "synthetic excluded", true, null)) : Collections.<PhenopacketHpoTerm>emptyList());
        PhenopacketImportResult imported = new PhenopacketImportResult(); imported.setStatus(PhenopacketEligibilityStatus.ELIGIBLE); imported.setCaseData(data);
        PublicBenchmarkSmokeCandidate candidate = new PublicBenchmarkSmokeCandidate(); candidate.setImported(imported);
        candidate.setSourceRelativePath("public/" + id + ".json"); candidate.setSourceSha256(String.format("%064x", Math.abs(id.hashCode())));
        candidate.setAllTruthInsideBenchmarkRegion(inside); return candidate;
    }

    private void addTruth(PublicBenchmarkSmokeCandidate candidate, String chromosome, int position, String ref, String alt) {
        BenchmarkTruthVariant truth = new BenchmarkTruthVariant(); truth.setCanonicalIdentity(new NormalizedVariantKey("GRCh38", chromosome, position, ref, alt));
        candidate.getImported().getCaseData().getDatasetCase().getTruthVariants().add(truth);
    }
    private List<String> selected(SmokeSetSelectionResult r) { return r.getDecisions().stream().filter(d -> d.getStatus()==SmokeSelectionStatus.SELECTED).map((@NonNull SmokeSetSelectionDecision decision) -> decision.getCaseId()).sorted().collect(Collectors.toList()); }
    private List<PublicBenchmarkSmokeCandidate> selectedCandidates(SmokeSetSelectionResult r) { return r.getDecisions().stream().filter(d -> d.getStatus()==SmokeSelectionStatus.SELECTED).map((@NonNull SmokeSetSelectionDecision decision) -> decision.getCandidate()).collect(Collectors.toList()); }
    private String chromosome(PublicBenchmarkSmokeCandidate c) { return c.getImported().getCaseData().getDatasetCase().getTruthVariants().get(0).getCanonicalIdentity().getChromosome(); }
    private boolean isIndel(PublicBenchmarkSmokeCandidate c) { NormalizedVariantKey k=c.getImported().getCaseData().getDatasetCase().getTruthVariants().get(0).getCanonicalIdentity(); return k.getReference().length()!=k.getAlternate().length(); }
    private boolean isSnv(PublicBenchmarkSmokeCandidate c) { NormalizedVariantKey k=c.getImported().getCaseData().getDatasetCase().getTruthVariants().get(0).getCanonicalIdentity(); return k.getReference().length()==1&&k.getAlternate().length()==1; }
    private void assertReason(SmokeSetSelectionResult r, String id, SmokeSetExclusionReason reason) { assertTrue(r.getDecisions().stream().filter(d -> id.equals(d.getCaseId())).findFirst().get().getReasons().contains(reason)); }
}
