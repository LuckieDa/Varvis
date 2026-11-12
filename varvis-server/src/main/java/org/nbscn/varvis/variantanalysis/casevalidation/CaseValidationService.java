package org.nbscn.varvis.variantanalysis.casevalidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.ranking.RankingContract;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class CaseValidationService {

    private static final String PHENOTYPE_SUPPORT = "phenotype_support";

    private final ObjectMapper objectMapper;

    public CaseValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public CaseValidationService() {
        this(new ObjectMapper());
    }

    public CaseValidationCase load(Path caseFile) throws IOException {
        return objectMapper.readValue(caseFile.toFile(), CaseValidationCase.class);
    }

    public void validateCase(CaseValidationCase validationCase) {
        List<String> errors = validationErrors(validationCase);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid case validation schema: " + String.join("; ", errors));
        }
    }

    public CaseValidationResult validate(CaseValidationCase validationCase,
                                         CaseValidationRun noHpoRun,
                                         CaseValidationRun withHpoRun) {
        validateCase(validationCase);
        if (noHpoRun == null || withHpoRun == null) {
            throw new IllegalArgumentException("Both no-HPO and with-HPO runs are required.");
        }
        RankedRun noHpo = ranked(noHpoRun.getVariants());
        RankedRun withHpo = ranked(withHpoRun.getVariants());
        List<String> warnings = new ArrayList<String>();
        warnings.addAll(noHpoRun.getWarnings());
        warnings.addAll(withHpoRun.getWarnings());

        List<CaseValidationResult.TruthVariantResult> variantResults =
                new ArrayList<CaseValidationResult.TruthVariantResult>();
        for (CaseValidationCase.TruthVariant truth : validationCase.getTruthVariants()) {
            variantResults.add(validateTruthVariant(validationCase, truth, noHpo, withHpo, withHpoRun));
        }

        List<CaseValidationResult.TruthGeneResult> geneResults =
                new ArrayList<CaseValidationResult.TruthGeneResult>();
        for (CaseValidationCase.TruthGene truthGene : validationCase.getTruthGenes()) {
            geneResults.add(validateTruthGene(truthGene, noHpo, withHpo, withHpoRun));
        }

        return new CaseValidationResult(
                validationCase.getCaseId(),
                Instant.now().toString(),
                currentGitCommit(),
                validationCase.getAssembly(),
                validationCase.getProbandSampleId(),
                validationCase.getHpoTerms(),
                noHpo.ordered.size(),
                withHpo.ordered.size(),
                annotatedCount(noHpo.ordered),
                annotatedCount(withHpo.ordered),
                withHpoRun.getCacheHitCount(),
                withHpoRun.getCacheMissCount(),
                withHpoRun.getProviderBatchCount(),
                firstText(withHpoRun.getProviderName(), noHpoRun.getProviderName()),
                firstText(withHpoRun.getProviderVersion(), noHpoRun.getProviderVersion()),
                firstText(withHpoRun.getCacheVersion(), noHpoRun.getCacheVersion()),
                firstText(withHpoRun.getOptionsHash(), noHpoRun.getOptionsHash()),
                firstText(withHpoRun.getInputAssembly(), noHpoRun.getInputAssembly()),
                firstText(withHpoRun.getAnnotationAssembly(), noHpoRun.getAnnotationAssembly()),
                firstText(withHpoRun.getResourceAssembly(), noHpoRun.getResourceAssembly()),
                firstText(withHpoRun.getKnowledgeAssembly(), noHpoRun.getKnowledgeAssembly()),
                withHpoRun.isLiftoverApplied() || noHpoRun.isLiftoverApplied(),
                firstText(withHpoRun.getLiftoverStatus(), noHpoRun.getLiftoverStatus()),
                variantResults,
                geneResults,
                warnings
        );
    }

    public List<String> validationErrors(CaseValidationCase validationCase) {
        List<String> errors = new ArrayList<String>();
        if (validationCase == null) {
            errors.add("case is null");
            return errors;
        }
        if (!hasText(validationCase.getCaseId())) {
            errors.add("caseId is required");
        }
        if (!hasText(validationCase.getAssembly())) {
            errors.add("assembly is required");
        } else if (!"GRCh38".equals(validationCase.getAssembly())
                && !"GRCh37".equals(validationCase.getAssembly())) {
            errors.add("assembly must be GRCh37 or GRCh38");
        }
        if (hasText(validationCase.getVcfPath())
                && !Files.exists(Paths.get(validationCase.getVcfPath()))) {
            errors.add("vcfPath does not exist");
        }
        for (String hpo : validationCase.getHpoTerms()) {
            if (!isHpoTerm(hpo)) {
                errors.add("invalid HPO term: " + hpo);
            }
        }
        for (CaseValidationCase.TruthVariant truth : validationCase.getTruthVariants()) {
            if (!hasText(truth.getChrom())) {
                errors.add("truthVariant.chrom is required");
            }
            if (truth.getPos() <= 0) {
                errors.add("truthVariant.pos must be positive");
            }
            if (!hasText(truth.getRef())) {
                errors.add("truthVariant.ref is required");
            }
            if (!hasText(truth.getAlt())) {
                errors.add("truthVariant.alt is required");
            }
        }
        for (Integer k : validationCase.getExpected().getHitK()) {
            if (k == null || k.intValue() <= 0) {
                errors.add("expected.hitK values must be positive");
            }
        }
        return errors;
    }

    private CaseValidationResult.TruthVariantResult validateTruthVariant(
            CaseValidationCase validationCase,
            CaseValidationCase.TruthVariant truth,
            RankedRun noHpo,
            RankedRun withHpo,
            CaseValidationRun withHpoRun) {
        String truthKey = truthKey(validationCase.getAssembly(), truth);
        RankedVariant noExact = noHpo.byVariantKey.get(truthKey);
        RankedVariant withExact = withHpo.byVariantKey.get(truthKey);
        RankedVariant noMatch = noExact;
        RankedVariant withMatch = withExact;
        boolean geneFallback = false;
        if (withMatch == null && hasText(truth.getGeneSymbol())) {
            withMatch = bestByGene(withHpo, truth.getGeneSymbol());
            noMatch = noMatch == null ? bestByGene(noHpo, truth.getGeneSymbol()) : noMatch;
            geneFallback = withMatch != null;
        }
        Integer rankNoHpo = rank(noMatch);
        Integer rankWithHpo = rank(withMatch);
        RankStatus rankStatusNoHpo = rankStatus(noMatch);
        RankStatus rankStatusWithHpo = rankStatus(withMatch);
        Integer rankDelta = rankStatusNoHpo != RankStatus.RANKED
                || rankStatusWithHpo != RankStatus.RANKED ? null : rankNoHpo - rankWithHpo;
        VariantInterpretation withVariant = variant(withMatch);
        boolean phenotypeSupportPresent = phenotypeSupportPresent(withVariant);
        Double phenotypeContribution = phenotypeContribution(withVariant);
        String hardGate = hardGateStatus(withVariant);
        CaseValidationMissReason missReason = missReason(
                validationCase,
                truth,
                noExact,
                withExact,
                withMatch,
                withHpoRun,
                phenotypeSupportPresent,
                hardGate
        );
        return new CaseValidationResult.TruthVariantResult(
                truth.getChrom(),
                truth.getPos(),
                truth.getRef(),
                truth.getAlt(),
                truth.getGeneSymbol(),
                withExact != null,
                geneFallback,
                rankNoHpo,
                rankWithHpo,
                rankDelta,
                rankStatusNoHpo,
                rankStatusWithHpo,
                hitMap(rankNoHpo, validationCase.getExpected().getHitK()),
                hitMap(rankWithHpo, validationCase.getExpected().getHitK()),
                geneAnnotationStatus(withVariant, truth.getGeneSymbol()),
                phenotypeSupportPresent,
                phenotypeContribution,
                hardGate,
                missReason,
                truth.getNote()
        );
    }

    private CaseValidationResult.TruthGeneResult validateTruthGene(CaseValidationCase.TruthGene truthGene,
                                                                   RankedRun noHpo,
                                                                   RankedRun withHpo,
                                                                   CaseValidationRun withHpoRun) {
        RankedVariant noMatch = bestByGene(noHpo, truthGene.getGeneSymbol());
        RankedVariant withMatch = bestByGene(withHpo, truthGene.getGeneSymbol());
        Integer rankNoHpo = rank(noMatch);
        Integer rankWithHpo = rank(withMatch);
        RankStatus rankStatusNoHpo = rankStatus(noMatch);
        RankStatus rankStatusWithHpo = rankStatus(withMatch);
        Integer rankDelta = rankStatusNoHpo != RankStatus.RANKED
                || rankStatusWithHpo != RankStatus.RANKED ? null : rankNoHpo - rankWithHpo;
        boolean support = phenotypeSupportPresent(variant(withMatch));
        CaseValidationMissReason missReason = null;
        if (withMatch == null) {
            missReason = CaseValidationMissReason.GENE_ANNOTATION_MISMATCH;
        } else if (phenotypePriorUnavailable(withHpoRun)) {
            missReason = CaseValidationMissReason.PHENOTYPE_PRIOR_UNAVAILABLE;
        } else if (!support && hasHpo(withHpoRun)) {
            missReason = CaseValidationMissReason.PHENOTYPE_NO_PRIOR_FOR_GENE;
        }
        return new CaseValidationResult.TruthGeneResult(
                truthGene.getGeneSymbol(),
                rankNoHpo,
                rankWithHpo,
                rankDelta,
                rankStatusNoHpo,
                rankStatusWithHpo,
                support,
                missReason
        );
    }

    private CaseValidationMissReason missReason(CaseValidationCase validationCase,
                                                CaseValidationCase.TruthVariant truth,
                                                RankedVariant noExact,
                                                RankedVariant withExact,
                                                RankedVariant withMatch,
                                                CaseValidationRun withHpoRun,
                                                boolean phenotypeSupportPresent,
                                                String hardGate) {
        if (withMatch == null) {
            return hasText(truth.getGeneSymbol())
                    ? CaseValidationMissReason.GENE_ANNOTATION_MISMATCH
                    : CaseValidationMissReason.VARIANT_NOT_PARSED;
        }
        if (withExact == null && withMatch != null) {
            return CaseValidationMissReason.VARIANT_NORMALIZATION_MISMATCH;
        }
        if (hardGate != null) {
            if ("FILTERED_COMMON".equals(hardGate)) {
                return CaseValidationMissReason.FILTERED_COMMON;
            }
            if ("FILTERED_QC".equals(hardGate)) {
                return CaseValidationMissReason.FILTERED_QC;
            }
            if ("BENIGN_LIKELY".equals(hardGate)) {
                return CaseValidationMissReason.BENIGN_LIKELY;
            }
            if ("GT=0/0".equals(hardGate)) {
                return CaseValidationMissReason.GT_REF_OR_MISSING;
            }
        }
        if ("GENE_ANNOTATION_UNAVAILABLE".equals(geneAnnotationStatus(variant(withMatch), truth.getGeneSymbol()))) {
            return CaseValidationMissReason.GENE_ANNOTATION_UNAVAILABLE;
        }
        if (phenotypePriorUnavailable(withHpoRun)) {
            return CaseValidationMissReason.PHENOTYPE_PRIOR_UNAVAILABLE;
        }
        if (validationCase.getExpected().isRequirePhenotypeSupport()
                && !phenotypeSupportPresent
                && hasHpo(withHpoRun)) {
            return CaseValidationMissReason.PHENOTYPE_SUPPORT_NOT_APPLIED;
        }
        Integer rank = rank(withMatch);
        int maxK = max(validationCase.getExpected().getHitK());
        if (rank != null && maxK > 0 && rank > maxK) {
            return CaseValidationMissReason.RANKED_BELOW_CUTOFF;
        }
        return null;
    }

    private RankedRun ranked(List<VariantInterpretation> variants) {
        List<VariantInterpretation> ordered = RankingContract.order(variants);
        Map<String, RankedVariant> byVariantKey = new LinkedHashMap<String, RankedVariant>();
        Map<String, List<RankedVariant>> byGene = new LinkedHashMap<String, List<RankedVariant>>();
        for (int index = 0; index < ordered.size(); index++) {
            RankedVariant ranked = new RankedVariant(ordered.get(index), index + 1);
            String key = variantKey(ordered.get(index));
            if (key != null && !byVariantKey.containsKey(key)) {
                byVariantKey.put(key, ranked);
            }
            for (String gene : genes(ordered.get(index))) {
                String normalizedGene = normalizeGene(gene);
                List<RankedVariant> values = byGene.get(normalizedGene);
                if (values == null) {
                    values = new ArrayList<RankedVariant>();
                    byGene.put(normalizedGene, values);
                }
                values.add(ranked);
            }
        }
        return new RankedRun(ordered, byVariantKey, byGene);
    }

    private RankedVariant bestByGene(RankedRun run, String geneSymbol) {
        if (!hasText(geneSymbol)) {
            return null;
        }
        List<RankedVariant> values = run.byGene.get(normalizeGene(geneSymbol));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private Map<Integer, Boolean> hitMap(Integer rank, List<Integer> hitK) {
        Map<Integer, Boolean> result = new LinkedHashMap<Integer, Boolean>();
        for (Integer k : hitK) {
            if (k != null && k.intValue() > 0) {
                result.put(k, rank != null && rank.intValue() <= k.intValue());
            }
        }
        return result;
    }

    private int annotatedCount(List<VariantInterpretation> variants) {
        int count = 0;
        for (VariantInterpretation variant : variants) {
            if (!genes(variant).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private String geneAnnotationStatus(VariantInterpretation variant, String expectedGene) {
        if (variant == null) {
            return "GENE_ANNOTATION_UNAVAILABLE";
        }
        Set<String> genes = genes(variant);
        if (genes.isEmpty()) {
            return "GENE_ANNOTATION_UNAVAILABLE";
        }
        if (hasText(expectedGene) && !genes.contains(normalizeGene(expectedGene))) {
            return "GENE_ANNOTATION_MISMATCH";
        }
        return "AVAILABLE";
    }

    private boolean phenotypeSupportPresent(VariantInterpretation variant) {
        return variant != null && variant.getScoreComponents().containsKey(PHENOTYPE_SUPPORT);
    }

    private Double phenotypeContribution(VariantInterpretation variant) {
        if (variant == null) {
            return null;
        }
        ScoreBreakdownComponent component = variant.getScoreComponents().get(PHENOTYPE_SUPPORT);
        if (component != null) {
            return component.getScore();
        }
        return variant.getScoreBreakdown().get(PHENOTYPE_SUPPORT);
    }

    private String hardGateStatus(VariantInterpretation variant) {
        if (variant == null) {
            return null;
        }
        VariantAnalysisBucket bucket = variant.getAnalysisBucket();
        String tier = variant.getPriorityTier();
        if (bucket == VariantAnalysisBucket.FILTERED_COMMON || "FILTERED_COMMON".equals(tier)) {
            return "FILTERED_COMMON";
        }
        if (bucket == VariantAnalysisBucket.FILTERED_QC || "FILTERED_QC".equals(tier)
                || bucket == VariantAnalysisBucket.FILTERED_NOT_CARRIED) {
            if (isReferenceGenotype(variant)) {
                return "GT=0/0";
            }
            return "FILTERED_QC";
        }
        if ("BENIGN_LIKELY".equals(tier)) {
            return "BENIGN_LIKELY";
        }
        if (variant.isSummaryOnly() || !variant.isFullInterpretationAvailable()) {
            return "PRESENT_BUT_NOT_REVIEWABLE";
        }
        return null;
    }

    private boolean phenotypePriorUnavailable(CaseValidationRun run) {
        PhenotypeAwareSummary summary = run == null ? null : run.getPhenotypeSummary();
        return summary != null
                && summary.getGenePriorSummary() != null
                && summary.getGenePriorSummary().getStatus() == GenePhenotypePriorStatus.UNAVAILABLE;
    }

    private boolean hasHpo(CaseValidationRun run) {
        PhenotypeAwareSummary summary = run == null ? null : run.getPhenotypeSummary();
        return summary != null && summary.getInputHpoCount() > 0;
    }

    private Set<String> genes(VariantInterpretation variant) {
        Set<String> genes = new LinkedHashSet<String>();
        if (variant == null) {
            return genes;
        }
        addGenes(genes, variant.getConsequences());
        addGenes(genes, variant.getTranscriptAnnotations());
        return genes;
    }

    private void addGenes(Set<String> genes, List<TranscriptConsequence> consequences) {
        if (consequences == null) {
            return;
        }
        for (TranscriptConsequence consequence : consequences) {
            if (consequence != null && hasText(consequence.getGeneSymbol())) {
                genes.add(normalizeGene(consequence.getGeneSymbol()));
            }
        }
    }

    private String variantKey(VariantInterpretation variant) {
        VariantQcObservation observation = variant == null ? null : variant.getObservation();
        NormalizedVariantKey key = observation == null ? null : observation.getKey();
        if (key == null) {
            return null;
        }
        return normalizeAssembly(key.getGenomeAssembly()) + "|" + normalizeChromosome(key.getChromosome())
                + "|" + key.getPosition() + "|" + normalizeAllele(key.getReference())
                + "|" + normalizeAllele(key.getAlternate());
    }

    private String truthKey(String assembly, CaseValidationCase.TruthVariant truth) {
        return normalizeAssembly(assembly) + "|" + normalizeChromosome(truth.getChrom())
                + "|" + truth.getPos() + "|" + normalizeAllele(truth.getRef())
                + "|" + normalizeAllele(truth.getAlt());
    }

    private boolean isReferenceGenotype(VariantInterpretation variant) {
        VariantQcObservation observation = variant.getObservation();
        String genotype = observation == null ? null : observation.getGenotype();
        if (genotype == null) {
            return false;
        }
        String trimmedGenotype = genotype.trim();
        if (trimmedGenotype.isEmpty()) {
            return false;
        }
        String[] alleles = trimmedGenotype.split("[/|]");
        boolean sawCalled = false;
        for (String allele : alleles) {
            if (!hasText(allele) || ".".equals(allele.trim())) {
                continue;
            }
            sawCalled = true;
            if (!"0".equals(allele.trim())) {
                return false;
            }
        }
        return sawCalled;
    }

    private Integer rank(RankedVariant variant) {
        return variant == null ? null : variant.rank;
    }

    private RankStatus rankStatus(RankedVariant variant) {
        return variant == null ? RankStatus.NOT_IN_RANKING_UNIVERSE : RankStatus.RANKED;
    }

    private VariantInterpretation variant(RankedVariant variant) {
        return variant == null ? null : variant.variant;
    }

    private String currentGitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            byte[] output = new byte[64];
            int length = process.getInputStream().read(output);
            int exit = process.waitFor();
            if (exit == 0 && length > 0) {
                return new String(output, 0, length).trim();
            }
        } catch (Exception ignored) {
            // Git commit is provenance metadata only; validation must still run.
        }
        return "unknown";
    }

    private int max(List<Integer> values) {
        int max = 0;
        for (Integer value : values) {
            if (value != null && value.intValue() > max) {
                max = value.intValue();
            }
        }
        return max;
    }

    private boolean isHpoTerm(String value) {
        return value != null && value.matches("HP:\\d{7}");
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first : second;
    }

    private String normalizeAssembly(String value) {
        return hasText(value) ? value.trim() : "";
    }

    private String normalizeChromosome(String value) {
        if (!hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.toLowerCase(Locale.ROOT).startsWith("chr")) {
            normalized = normalized.substring(3);
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeAllele(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String normalizeGene(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private static class RankedRun {
        private final List<VariantInterpretation> ordered;
        private final Map<String, RankedVariant> byVariantKey;
        private final Map<String, List<RankedVariant>> byGene;

        private RankedRun(List<VariantInterpretation> ordered,
                          Map<String, RankedVariant> byVariantKey,
                          Map<String, List<RankedVariant>> byGene) {
            this.ordered = ordered;
            this.byVariantKey = byVariantKey;
            this.byGene = byGene;
        }
    }

    private static class RankedVariant {
        private final VariantInterpretation variant;
        private final int rank;

        private RankedVariant(VariantInterpretation variant, int rank) {
            this.variant = variant;
            this.rank = rank;
        }
    }
}
