package org.nbscn.varvis.variantanalysis.benchmark.selection;

import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthVariant;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketCaseData;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketEligibilityStatus;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PublicBenchmarkSmokeSetSelector {
    public static final String ALGORITHM_VERSION = "public-smoke-selector-v1";
    private static final String ALGORITHM_CONTRACT =
            "GRCh38;adapter-eligible;single-gene;disease;observed-HPO;SNV-small-indel;all-truth-in-BED;"
            + "deduplicate-case-and-truth;max-one-multi-truth;greedy-new-chromosome-gene-disease-excluded-HPO;"
            + "stable-case-canonical-gene-order;target-five;require-SNV-indel-three-chromosomes-five-genes";

    public SmokeSetSelectionResult select(List<PublicBenchmarkSmokeCandidate> candidates) {
        List<PublicBenchmarkSmokeCandidate> ordered = candidates == null
                ? new ArrayList<PublicBenchmarkSmokeCandidate>()
                : new ArrayList<PublicBenchmarkSmokeCandidate>(candidates);
        Collections.sort(ordered, Comparator.comparing(this::stableKey));
        Map<String, Integer> caseCounts = counts(ordered, true);
        Map<String, Integer> truthCounts = counts(ordered, false);
        List<SmokeSetSelectionDecision> decisions = new ArrayList<SmokeSetSelectionDecision>();
        List<PublicBenchmarkSmokeCandidate> eligible = new ArrayList<PublicBenchmarkSmokeCandidate>();
        for (PublicBenchmarkSmokeCandidate candidate : ordered) {
            SmokeSetSelectionDecision decision = decision(candidate);
            validate(candidate, decision, caseCounts, truthCounts);
            if (decision.getReasons().isEmpty()) {
                decision.setStatus(SmokeSelectionStatus.ELIGIBLE_NOT_SELECTED);
                eligible.add(candidate);
            } else {
                decision.setStatus(candidate == null || candidate.getImported() == null
                        ? SmokeSelectionStatus.INVALID : SmokeSelectionStatus.EXCLUDED);
            }
            decisions.add(decision);
        }

        List<PublicBenchmarkSmokeCandidate> selected = choose(eligible, 5);
        Set<PublicBenchmarkSmokeCandidate> selectedSet = new HashSet<PublicBenchmarkSmokeCandidate>(selected);
        for (SmokeSetSelectionDecision decision : decisions) {
            if (selectedSet.contains(decision.getCandidate())) { decision.setStatus(SmokeSelectionStatus.SELECTED); }
        }
        boolean complete = coverageComplete(selected);
        if (!complete) {
            for (SmokeSetSelectionDecision decision : decisions) {
                if (decision.getStatus() == SmokeSelectionStatus.SELECTED) {
                    decision.getReasons().add(SmokeSetExclusionReason.INSUFFICIENT_COVERAGE);
                }
            }
        }
        SmokeSetSelectionResult result = new SmokeSetSelectionResult();
        result.setAlgorithmVersion(ALGORITHM_VERSION);
        result.setAlgorithmFingerprint(sha256(ALGORITHM_CONTRACT));
        result.setComplete(complete);
        result.setDecisions(decisions);
        return result;
    }

    private void validate(PublicBenchmarkSmokeCandidate candidate,
                          SmokeSetSelectionDecision decision,
                          Map<String, Integer> caseCounts,
                          Map<String, Integer> truthCounts) {
        if (candidate == null || candidate.getImported() == null
                || candidate.getImported().getStatus() != PhenopacketEligibilityStatus.ELIGIBLE
                || candidate.getImported().getCaseData() == null) {
            decision.getReasons().add(SmokeSetExclusionReason.ADAPTER_NOT_ELIGIBLE); return;
        }
        PhenopacketCaseData data = candidate.getImported().getCaseData();
        BenchmarkDatasetCase datasetCase = data.getDatasetCase();
        if (caseCounts.get(data.getCaseId()) > 1) { decision.getReasons().add(SmokeSetExclusionReason.DUPLICATE_CASE_ID); }
        for (String truth : truthIds(candidate)) {
            if (truthCounts.get(truth) > 1) { decision.getReasons().add(SmokeSetExclusionReason.DUPLICATE_TRUTH_IDENTITY); break; }
        }
        if (datasetCase == null || !"GRCh38".equals(datasetCase.getAssembly())) { decision.getReasons().add(SmokeSetExclusionReason.NON_GRCH38); }
        if (data.getTruthGenes() == null || data.getTruthGenes().size() != 1) { decision.getReasons().add(SmokeSetExclusionReason.NOT_SINGLE_GENE); }
        if (data.getDiseaseIds() == null || data.getDiseaseIds().isEmpty()) { decision.getReasons().add(SmokeSetExclusionReason.MISSING_DISEASE); }
        if (data.getObservedHpo() == null || data.getObservedHpo().isEmpty()) { decision.getReasons().add(SmokeSetExclusionReason.MISSING_OBSERVED_HPO); }
        if (candidate.getSourceRelativePath() == null || candidate.getSourceRelativePath().startsWith("/")
                || candidate.getSourceRelativePath().startsWith("\\")
                || candidate.getSourceRelativePath().matches("^[A-Za-z]:.*")
                || candidate.getSourceRelativePath().contains("..")) {
            decision.getReasons().add(SmokeSetExclusionReason.UNSAFE_SOURCE_REFERENCE);
        }
        if (candidate.getSourceSha256() == null || !candidate.getSourceSha256().matches("[0-9a-f]{64}")) {
            decision.getReasons().add(SmokeSetExclusionReason.INVALID_SOURCE_CHECKSUM);
        }
        if (!supportedTruth(datasetCase)) { decision.getReasons().add(SmokeSetExclusionReason.UNSUPPORTED_VARIANT_TYPE); }
        if (!candidate.isAllTruthInsideBenchmarkRegion()) { decision.getReasons().add(SmokeSetExclusionReason.TRUTH_OUTSIDE_BENCHMARK_REGION); }
    }

    private List<PublicBenchmarkSmokeCandidate> choose(List<PublicBenchmarkSmokeCandidate> eligible, int target) {
        List<PublicBenchmarkSmokeCandidate> remaining = new ArrayList<PublicBenchmarkSmokeCandidate>(eligible);
        List<PublicBenchmarkSmokeCandidate> selected = new ArrayList<PublicBenchmarkSmokeCandidate>();
        Set<String> chromosomes = new LinkedHashSet<String>();
        Set<String> genes = new LinkedHashSet<String>();
        Set<String> diseases = new LinkedHashSet<String>();
        boolean multiTruthSelected = false;
        while (selected.size() < target && !remaining.isEmpty()) {
            PublicBenchmarkSmokeCandidate best = null;
            int bestScore = Integer.MIN_VALUE;
            for (PublicBenchmarkSmokeCandidate candidate : remaining) {
                boolean multi = truthIds(candidate).size() > 1;
                if (multi && multiTruthSelected) { continue; }
                String chromosome = primaryTruth(candidate).getChromosome();
                String gene = candidate.getImported().getCaseData().getTruthGenes().get(0);
                String disease = candidate.getImported().getCaseData().getDiseaseIds().get(0);
                int score = (chromosomes.contains(chromosome) ? 0 : 16)
                        + (genes.contains(gene) ? 0 : 8)
                        + (diseases.contains(disease) ? 0 : 4)
                        + (candidate.getImported().getCaseData().getExcludedHpo().isEmpty() ? 0 : 2)
                        + (containsIndel(candidate) && !containsIndel(selected) ? 32 : 0)
                        + (containsSnv(candidate) && !containsSnv(selected) ? 32 : 0);
                if (best == null || score > bestScore || (score == bestScore && stableKey(candidate).compareTo(stableKey(best)) < 0)) {
                    best = candidate; bestScore = score;
                }
            }
            if (best == null) { break; }
            selected.add(best); remaining.remove(best);
            chromosomes.add(primaryTruth(best).getChromosome());
            genes.add(best.getImported().getCaseData().getTruthGenes().get(0));
            diseases.add(best.getImported().getCaseData().getDiseaseIds().get(0));
            multiTruthSelected |= truthIds(best).size() > 1;
        }
        return selected;
    }

    private boolean coverageComplete(List<PublicBenchmarkSmokeCandidate> selected) {
        Set<String> chromosomes = new HashSet<String>(); Set<String> genes = new HashSet<String>();
        for (PublicBenchmarkSmokeCandidate candidate : selected) {
            chromosomes.add(primaryTruth(candidate).getChromosome());
            genes.add(candidate.getImported().getCaseData().getTruthGenes().get(0));
        }
        return selected.size() == 5 && containsSnv(selected) && containsIndel(selected)
                && chromosomes.size() >= 3 && genes.size() == 5;
    }

    private boolean supportedTruth(BenchmarkDatasetCase datasetCase) {
        if (datasetCase == null || datasetCase.getTruthVariants() == null || datasetCase.getTruthVariants().isEmpty()) return false;
        for (BenchmarkTruthVariant truth : datasetCase.getTruthVariants()) {
            if (truth == null || truth.getCanonicalIdentity() == null) return false;
            NormalizedVariantKey key = truth.getCanonicalIdentity();
            if (!key.getReference().matches("[ACGT]+") || !key.getAlternate().matches("[ACGT]+")) return false;
        }
        return true;
    }

    private Map<String, Integer> counts(List<PublicBenchmarkSmokeCandidate> candidates, boolean cases) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (PublicBenchmarkSmokeCandidate candidate : candidates) {
            List<String> values = cases ? Collections.singletonList(caseId(candidate)) : truthIds(candidate);
            for (String value : values) { if (value != null) counts.put(value, counts.containsKey(value) ? counts.get(value) + 1 : 1); }
        }
        return counts;
    }

    private SmokeSetSelectionDecision decision(PublicBenchmarkSmokeCandidate candidate) {
        SmokeSetSelectionDecision decision = new SmokeSetSelectionDecision();
        decision.setCandidate(candidate); decision.setCaseId(caseId(candidate)); return decision;
    }
    private String caseId(PublicBenchmarkSmokeCandidate c) { return c == null || c.getImported() == null || c.getImported().getCaseData() == null ? null : c.getImported().getCaseData().getCaseId(); }
    private List<String> truthIds(PublicBenchmarkSmokeCandidate c) {
        List<String> ids = new ArrayList<String>();
        if (c != null && c.getImported() != null && c.getImported().getCaseData() != null && c.getImported().getCaseData().getDatasetCase() != null) {
            for (BenchmarkTruthVariant truth : c.getImported().getCaseData().getDatasetCase().getTruthVariants()) if (truth != null && truth.getCanonicalIdentity() != null) ids.add(truth.getCanonicalIdentity().canonicalId());
        }
        return ids;
    }
    private NormalizedVariantKey primaryTruth(PublicBenchmarkSmokeCandidate c) { return c.getImported().getCaseData().getDatasetCase().getTruthVariants().get(0).getCanonicalIdentity(); }
    private boolean containsIndel(PublicBenchmarkSmokeCandidate c) { for (String id : truthIds(c)) { String[] p=id.split("\\|"); if (p[3].length()!=p[4].length()) return true; } return false; }
    private boolean containsSnv(PublicBenchmarkSmokeCandidate c) { for (String id : truthIds(c)) { String[] p=id.split("\\|"); if (p[3].length()==1 && p[4].length()==1) return true; } return false; }
    private boolean containsIndel(List<PublicBenchmarkSmokeCandidate> list) { for (PublicBenchmarkSmokeCandidate c:list) if(containsIndel(c)) return true; return false; }
    private boolean containsSnv(List<PublicBenchmarkSmokeCandidate> list) { for (PublicBenchmarkSmokeCandidate c:list) if(containsSnv(c)) return true; return false; }
    private String stableKey(PublicBenchmarkSmokeCandidate c) { return String.valueOf(caseId(c)) + "|" + String.join(",", truthIds(c)) + "|" + (c == null ? "" : String.valueOf(c.getSourceSha256())); }
    private String sha256(String value) { try { byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder s=new StringBuilder(); for(byte x:b)s.append(String.format("%02x",x)); return s.toString(); } catch(Exception e){throw new IllegalStateException(e);} }
}
