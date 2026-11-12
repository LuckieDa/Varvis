package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import htsjdk.tribble.readers.TabixReader;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SpliceAiProvider implements VariantEvidenceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpliceAiProvider.class);
    static final String RAW_SUPPORTING_PROFILE_ID = "SPLICEAI_PP3_BP4_SUPPORTING@1.0.0";

    private final VariantAnalysisProperties properties;
    private final ConcurrentMap<NormalizedVariantKey, EvidenceItem> cache =
            new ConcurrentHashMap<NormalizedVariantKey, EvidenceItem>();

    public SpliceAiProvider(VariantAnalysisProperties properties) {
        this.properties = properties;
    }

    @Override
    public String source() {
        return "SPLICEAI";
    }

    @Override
    public String version() {
        return properties.getSpliceAi().getVersion();
    }

    /** Request-scoped, lossless local facts for prediction and comparator consumers. No rule is applied. */
    public SpliceAiLocalObservationSession openSharedSession(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new SpliceAiLocalObservationSession(mapper, properties.getSpliceAi());
    }

    @Override
    public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
            List<VariantInterpretation> variants,
            AnalysisContext context) {
        long started = System.nanoTime();
        Map<NormalizedVariantKey, List<EvidenceItem>> result =
                new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        if (!ProviderAssemblySupport.supportsOnlyGrch38(variants, context)) {
            Map<NormalizedVariantKey, List<EvidenceItem>> unsupported =
                    ProviderAssemblySupport.unsupportedForGrch38Only(
                            variants,
                            context,
                            source(),
                            version(),
                            "COMPUTATIONAL",
                            "SpliceAI GRCh38 local resources"
                    );
            logMetrics(variants == null ? 0 : variants.size(), 0, 0, unsupported, started);
            return unsupported;
        }
        Map<NormalizedVariantKey, VariantInterpretation> pending =
                new LinkedHashMap<NormalizedVariantKey, VariantInterpretation>();
        int cacheHits = 0;
        int queued = 0;
        int maxCandidates = properties.getSpliceAi().normalizedMaxCandidates();

        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            if (!SpecialistProviderSupport.isSpliceRelevant(variant)) {
                result.put(key, SpecialistProviderSupport.single(EvidenceItem.notApplicable(
                        source(), version(), "COMPUTATIONAL",
                        "SpliceAI is queried only for splice-relevant consequences"
                )));
                continue;
            }
            if (!properties.getSpliceAi().isEnabled()) {
                result.put(key, SpecialistProviderSupport.single(statusEvidence(
                        "DISABLED",
                        "Disabled",
                        "SpliceAI local lookup is disabled",
                        "SpliceAI local lookup is disabled; VCF INFO fallback may still be used when present"
                )));
                continue;
            }
            EvidenceItem cached = cache.get(key);
            if (cached != null) {
                cacheHits++;
                result.put(key, SpecialistProviderSupport.single(cached));
                continue;
            }
            if (maxCandidates > 0 && queued >= maxCandidates) {
                result.put(key, SpecialistProviderSupport.single(skippedEvidence(
                        "SpliceAI lookup skipped because variant-analysis.splice-ai.max-candidates was reached"
                )));
                continue;
            }
            pending.put(key, variant);
            queued++;
        }
        if (!pending.isEmpty()) {
            annotatePending(result, pending);
        }
        logMetrics(variants.size(), queued, cacheHits, result, started);
        return result;
    }

    /**
     * Explicit E3-C2 raw-score path. The legacy {@link #annotate(List, AnalysisContext)}
     * method remains bound to the existing masked Tabix resources.
     */
    public Map<NormalizedVariantKey, List<EvidenceItem>> annotateRawBatch(
            List<VariantInterpretation> variants,
            AnalysisContext context,
            Map<NormalizedVariantKey, TranscriptConsequence> selectedConsequences,
            SpliceAiRawBatchRunner runner,
            String resourceVersion) {
        Map<NormalizedVariantKey, List<EvidenceItem>> result =
                new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        if (!ProviderAssemblySupport.supportsOnlyGrch38(variants, context)) {
            return rawAssemblyUnsupported(variants, resourceVersion);
        }
        if (runner == null || resourceVersion == null || resourceVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("raw runner and resource version are required");
        }

        Map<String, RawPending> pendingByQuery = new LinkedHashMap<String, RawPending>();
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            TranscriptConsequence selected = selectedConsequences == null
                    ? null : selectedConsequences.get(key);
            if (selected == null || selected.getGeneSymbol() == null
                    || selected.getVersionedTranscriptId() == null) {
                result.put(key, SpecialistProviderSupport.single(rawStatus(
                        key, selected, resourceVersion,
                        "NOT_RUN", "SELECTED_VEP_CONTEXT_MISSING_OR_AMBIGUOUS",
                        "AVAILABLE", null, "NOT_QUERIED", "UNKNOWN",
                        "SELECTED_VEP_CONTEXT_MISSING_OR_AMBIGUOUS",
                        "The unique selected VEP gene/transcript context is unavailable",
                        null, null, null, null)));
                continue;
            }
            if (!rawSpliceApplicable(selected.getConsequence())) {
                result.put(key, SpecialistProviderSupport.single(rawStatus(
                        key, selected, resourceVersion,
                        "NOT_RUN", "COMPUTATIONAL_CHANNEL_NOT_SPLICEAI",
                        "AVAILABLE", null, "NOT_QUERIED", "NOT_APPLICABLE",
                        "COMPUTATIONAL_CHANNEL_NOT_SPLICEAI",
                        "The selected VEP consequence is outside the E3-C2 SpliceAI channel",
                        null, null, null, null)));
                continue;
            }
            String queryId = SpliceAiRawBatchRunner.stableQueryId(key);
            RawPending pending = new RawPending(key, selected);
            if (pendingByQuery.put(queryId, pending) != null) {
                throw new IllegalArgumentException("raw SpliceAI batch contains a duplicate allele");
            }
        }
        if (pendingByQuery.isEmpty()) return result;

        List<SpliceAiRawBatchRunner.Query> queries = new ArrayList<SpliceAiRawBatchRunner.Query>();
        for (Map.Entry<String, RawPending> entry : pendingByQuery.entrySet()) {
            RawPending pending = entry.getValue();
            queries.add(new SpliceAiRawBatchRunner.Query(
                    entry.getKey(), pending.key, pending.selected.getGeneSymbol(),
                    pending.selected.getVersionedTranscriptId()));
        }
        SpliceAiRawBatchRunner.BatchResult batch = runner.run(queries);
        projectRawResult(result, pendingByQuery, batch, resourceVersion);
        return result;
    }

    /** Rebind one already computed, allele-keyed raw batch to a separately verified context. */
    List<EvidenceItem> projectRawResult(VariantInterpretation variant, TranscriptConsequence selected,
                                       SpliceAiRawBatchRunner.BatchResult batch, String resourceVersion) {
        var key = variant.getObservation().getKey();
        var pending = new LinkedHashMap<String, RawPending>();
        pending.put(SpliceAiRawBatchRunner.stableQueryId(key), new RawPending(key, selected));
        var result = new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        projectRawResult(result, pending, batch, resourceVersion);
        return result.get(key);
    }

    private void projectRawResult(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                  Map<String, RawPending> pendingByQuery,
                                  SpliceAiRawBatchRunner.BatchResult batch, String resourceVersion) {
        if (!"SUCCEEDED".equals(batch.getStatus()) || batch.getResult() == null) {
            String execution = "TIMEOUT".equals(batch.getStatus()) ? "TIMEOUT" : "FAILED";
            String reason = batch.getReasonCode() == null
                    ? "SPLICEAI_BATCH_EXECUTION_FAILED" : batch.getReasonCode();
            addRawFailure(result, pendingByQuery.values(), resourceVersion, execution, reason);
            return;
        }
        addRawResults(result, pendingByQuery, batch.getResult(), resourceVersion);
    }

    private void addRawResults(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                               Map<String, RawPending> pendingByQuery,
                               JsonNode batch,
                               String resourceVersion) {
        Map<String, JsonNode> byQuery = new LinkedHashMap<String, JsonNode>();
        for (JsonNode item : batch.path("results")) {
            String queryId = text(item, "queryId");
            if (queryId != null && pendingByQuery.containsKey(queryId)
                    && byQuery.put(queryId, item) != null) {
                byQuery.put(queryId, null);
            }
        }
        for (Map.Entry<String, RawPending> entry : pendingByQuery.entrySet()) {
            RawPending pending = entry.getValue();
            JsonNode item = byQuery.get(entry.getKey());
            EvidenceItem evidence;
            if (item == null || !matchesAllele(item.path("allele"), pending.key)
                    || !item.path("exactAlleleMatch").isBoolean()) {
                evidence = rawStatus(
                        pending.key, pending.selected, resourceVersion,
                        "FAILED", "SPLICEAI_BATCH_RESULT_INVALID",
                        "AVAILABLE", null, "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED",
                        "SpliceAI raw batch output did not contain one exact-allele result",
                        null, null, null, null);
            } else if ("NO_RECORD".equals(text(item, "status"))
                    && !item.path("exactAlleleMatch").asBoolean()) {
                evidence = rawStatus(
                        pending.key, pending.selected, resourceVersion,
                        "SUCCEEDED", null, "AVAILABLE", null, "NO_RECORD", "APPLICABLE", null,
                        "SpliceAI returned no raw annotation for the exact allele",
                        false, null, null, null);
            } else if ("HIT".equals(text(item, "status"))
                    && item.path("exactAlleleMatch").asBoolean()) {
                evidence = rawHit(pending, item.path("annotations"), resourceVersion);
            } else {
                evidence = rawStatus(
                        pending.key, pending.selected, resourceVersion,
                        "FAILED", "SPLICEAI_BATCH_RESULT_INVALID",
                        "AVAILABLE", null, "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED",
                        "SpliceAI raw batch output has an invalid query status",
                        null, null, null, null);
            }
            result.put(pending.key, SpecialistProviderSupport.single(evidence));
        }
    }

    private EvidenceItem rawHit(RawPending pending,
                                JsonNode annotations,
                                String resourceVersion) {
        if (!annotations.isArray() || annotations.isEmpty()) {
            return rawStatus(
                    pending.key, pending.selected, resourceVersion,
                    "FAILED", "SPLICEAI_BATCH_RESULT_INVALID",
                    "AVAILABLE", null, "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED",
                    "SpliceAI HIT has no annotation",
                    null, null, null, null);
        }
        Set<String> genes = new LinkedHashSet<String>();
        JsonNode selectedAnnotation = null;
        for (JsonNode annotation : annotations) {
            String gene = text(annotation, "geneSymbol");
            if (gene != null) genes.add(gene);
            if (pending.selected.getGeneSymbol().equals(gene)) {
                if (selectedAnnotation != null) {
                    return rawGeneNotEvaluated(pending, resourceVersion, genes,
                            "SPLICEAI_GENE_MATCH_AMBIGUOUS");
                }
                selectedAnnotation = annotation;
            }
        }
        if (genes.size() != 1 || selectedAnnotation == null
                || !pending.selected.getGeneSymbol().equals(genes.iterator().next())) {
            return rawGeneNotEvaluated(pending, resourceVersion, genes,
                    genes.isEmpty() ? "SPLICEAI_GENE_MISSING" : "SPLICEAI_GENE_CONTEXT_MISMATCH");
        }
        RawScores scores = rawScores(selectedAnnotation);
        if (scores == null) {
            return rawStatus(
                    pending.key, pending.selected, resourceVersion,
                    "SUCCEEDED", null, "AVAILABLE", null, "HIT", "UNKNOWN",
                    "SPLICEAI_SCORE_FIELDS_INVALID",
                    "SpliceAI exact gene annotation has incomplete or invalid DS/DP fields",
                    true, true, genes.iterator().next(), null);
        }

        Map<String, Object> details = rawDetails(pending.selected);
        details.put("exactAlleleMatch", true);
        details.put("geneContextMatch", true);
        details.put("geneSymbol", pending.selected.getGeneSymbol());
        details.put("maximumRawDeltaScore", scores.maximumRaw);
        for (Map.Entry<String, String> score : scores.deltaScores.entrySet()) {
            details.put(score.getKey(), score.getValue());
        }
        for (Map.Entry<String, String> position : scores.deltaPositions.entrySet()) {
            details.put(position.getKey(), position.getValue());
        }
        addRawAxes(details, "SUCCEEDED", null, "AVAILABLE", null,
                "HIT", "APPLICABLE", null);
        String recordId = source() + ":" + pending.key.canonicalId()
                + ":" + pending.selected.getGeneSymbol();
        Map<String, Object> provenance = new LinkedHashMap<String, Object>();
        provenance.put("queryAllele", pending.key.canonicalId());
        provenance.put("geneSymbol", pending.selected.getGeneSymbol());
        provenance.put("selectedTranscriptId", pending.selected.getVersionedTranscriptId());
        return rawEvidence(
                pending.key, pending.selected, resourceVersion, recordId,
                "APPLICABLE", "APPLICABLE", scores.maximumRaw,
                "SpliceAI raw maximum delta score",
                "SpliceAI raw prediction is computational evidence, not a clinical classification",
                provenance, null, details);
    }

    private EvidenceItem rawGeneNotEvaluated(RawPending pending,
                                               String resourceVersion,
                                               Set<String> genes,
                                               String reason) {
        return rawStatus(
                pending.key, pending.selected, resourceVersion,
                "SUCCEEDED", null, "AVAILABLE", null, "HIT", "UNKNOWN", reason,
                "SpliceAI exact-allele annotations do not uniquely match the selected VEP gene",
                true, false, null, new ArrayList<String>(genes));
    }

    private RawScores rawScores(JsonNode annotation) {
        JsonNode deltaScores = annotation.path("deltaScores");
        JsonNode deltaPositions = annotation.path("deltaPositions");
        if (!deltaScores.isObject() || !deltaPositions.isObject()) return null;
        Map<String, String> scores = new LinkedHashMap<String, String>();
        BigDecimal maximum = null;
        String maximumRaw = null;
        for (String field : List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) {
            String raw = text(deltaScores, field);
            BigDecimal value = decimal(raw);
            if (value == null || value.compareTo(BigDecimal.ZERO) < 0
                    || value.compareTo(BigDecimal.ONE) > 0) return null;
            scores.put(field, raw);
            if (maximum == null || value.compareTo(maximum) > 0) {
                maximum = value;
                maximumRaw = raw;
            }
        }
        Map<String, String> positions = new LinkedHashMap<String, String>();
        for (String field : List.of("DP_AG", "DP_AL", "DP_DG", "DP_DL")) {
            JsonNode value = deltaPositions.get(field);
            String raw = value != null && (value.isIntegralNumber() || value.isTextual())
                    ? value.asText() : null;
            try {
                int position = Integer.parseInt(raw);
                if (Math.abs(position) > SpliceAiRawBatchRunner.DISTANCE_BP) return null;
            } catch (NumberFormatException failure) {
                return null;
            }
            positions.put(field, raw);
        }
        String declaredMaximum = text(annotation, "maximumRawScore");
        BigDecimal declared = decimal(declaredMaximum);
        if (declared == null || declared.compareTo(maximum) != 0) return null;
        return new RawScores(scores, positions, maximumRaw);
    }

    private void addRawFailure(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                               Iterable<RawPending> pending,
                               String resourceVersion,
                               String execution,
                               String reason) {
        for (RawPending item : pending) {
            result.put(item.key, SpecialistProviderSupport.single(rawStatus(
                    item.key, item.selected, resourceVersion,
                    execution, reason, "AVAILABLE", null,
                    "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED",
                    "SpliceAI raw batch execution did not produce query evidence",
                    null, null, null, null)));
        }
    }

    private Map<NormalizedVariantKey, List<EvidenceItem>> rawAssemblyUnsupported(
            List<VariantInterpretation> variants,
            String resourceVersion) {
        Map<NormalizedVariantKey, List<EvidenceItem>> result =
                new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        if (variants == null) return result;
        for (VariantInterpretation variant : variants) {
            if (variant == null || variant.getObservation() == null
                    || variant.getObservation().getKey() == null) continue;
            NormalizedVariantKey key = variant.getObservation().getKey();
            result.put(key, SpecialistProviderSupport.single(rawStatus(
                    key, null, resourceVersion,
                    "NOT_RUN", "ASSEMBLY_UNSUPPORTED", "AVAILABLE", null,
                    "NOT_QUERIED", "NOT_APPLICABLE", "ASSEMBLY_UNSUPPORTED",
                    "SpliceAI raw prediction supports native GRCh38 only",
                    null, null, null, null)));
        }
        return result;
    }

    private EvidenceItem rawStatus(NormalizedVariantKey key,
                                   TranscriptConsequence selected,
                                   String resourceVersion,
                                   String execution,
                                   String executionReason,
                                   String availability,
                                   String availabilityReason,
                                   String query,
                                   String applicability,
                                   String applicabilityReason,
                                   String summary,
                                   Boolean exactAlleleMatch,
                                   Boolean geneContextMatch,
                                   String geneSymbol,
                                   List<String> observedGenes) {
        Map<String, Object> details = rawDetails(selected);
        if (exactAlleleMatch != null) details.put("exactAlleleMatch", exactAlleleMatch);
        if (geneContextMatch != null) details.put("geneContextMatch", geneContextMatch);
        if (geneSymbol != null) details.put("geneSymbol", geneSymbol);
        if (observedGenes != null) details.put("observedGeneSymbols", observedGenes);
        addRawAxes(details, execution, executionReason, availability, availabilityReason,
                query, applicability, applicabilityReason);
        String recordId = "HIT".equals(query)
                ? source() + ":" + key.canonicalId() + ":ALLELE_ANNOTATIONS" : null;
        return rawEvidence(
                key, selected, resourceVersion, recordId,
                "NOT_APPLICABLE".equals(applicability) ? "NOT_APPLICABLE" : "UNAVAILABLE",
                "NOT_APPLICABLE".equals(applicability) ? "NOT_APPLICABLE" : "UNAVAILABLE",
                null,
                "NOT_APPLICABLE".equals(applicability) ? "Not applicable" : "Not evaluated",
                summary, null, applicabilityReason, details);
    }

    private EvidenceItem rawEvidence(NormalizedVariantKey key,
                                     TranscriptConsequence selected,
                                     String resourceVersion,
                                     String sourceRecordId,
                                     String applicability,
                                     String status,
                                     String rawValue,
                                     String label,
                                     String summary,
                                     Map<String, Object> provenance,
                                     String explanationCode,
                                     Map<String, Object> details) {
        return new EvidenceItem(
                EvidenceItem.CURRENT_SCHEMA_VERSION,
                null,
                key.canonicalId(),
                key.getChromosome(),
                key.getPosition(),
                key.getReference(),
                key.getAlternate(),
                null,
                selected == null ? null : selected.getVersionedTranscriptId(),
                null,
                source(),
                sourceRecordId,
                resourceVersion,
                resourceVersion,
                null,
                key.getGenomeAssembly(),
                "COMPUTATIONAL",
                applicability,
                status,
                rawValue == null ? null : decimal(rawValue).doubleValue(),
                rawValue,
                null,
                RAW_SUPPORTING_PROFILE_ID,
                EvidenceItem.INDETERMINATE_STRENGTH,
                label,
                summary,
                provenance,
                Instant.now().toString(),
                explanationCode,
                details
        );
    }

    private Map<String, Object> rawDetails(TranscriptConsequence selected) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("calibrationProfileId", RAW_SUPPORTING_PROFILE_ID);
        if (selected != null) {
            details.put("selectedGeneSymbol", selected.getGeneSymbol());
            details.put("selectedTranscriptId", selected.getVersionedTranscriptId());
            details.put("selectedConsequence", selected.getConsequence());
        }
        return details;
    }

    private void addRawAxes(Map<String, Object> details,
                            String execution,
                            String executionReason,
                            String availability,
                            String availabilityReason,
                            String query,
                            String applicability,
                            String applicabilityReason) {
        details.put("resourceExecutionStatus", execution);
        if (executionReason != null) details.put("resourceExecutionReasonCode", executionReason);
        details.put("resourceAvailabilityStatus", availability);
        if (availabilityReason != null) details.put("resourceAvailabilityReasonCode", availabilityReason);
        details.put("queryOutcomeStatus", query);
        details.put("resourceApplicabilityStatus", applicability);
        if (applicabilityReason != null) {
            details.put("resourceApplicabilityReasonCode", applicabilityReason);
        }
    }

    private boolean rawSpliceApplicable(String consequence) {
        if (consequence == null) return false;
        Set<String> terms = new LinkedHashSet<String>();
        for (String term : consequence.split("&")) terms.add(term.trim());
        if (terms.contains("missense_variant") || terms.contains("splice_donor_variant")
                || terms.contains("splice_acceptor_variant")) return false;
        return terms.contains("splice_region_variant") || terms.contains("intron_variant")
                || terms.contains("synonymous_variant");
    }

    private boolean matchesAllele(JsonNode allele, NormalizedVariantKey key) {
        return allele.isObject()
                && key.getGenomeAssembly().equals(text(allele, "assembly"))
                && SpecialistProviderSupport.matchesCoordinates(
                text(allele, "contig"), allele.path("position").asText(),
                text(allele, "reference"), text(allele, "alternate"), key);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().trim().isEmpty()
                ? value.asText().trim() : null;
    }

    private BigDecimal decimal(String value) {
        try {
            return value == null ? null : new BigDecimal(value);
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private record RawPending(NormalizedVariantKey key, TranscriptConsequence selected) {}

    private record RawScores(Map<String, String> deltaScores,
                             Map<String, String> deltaPositions,
                             String maximumRaw) {}

    private void annotatePending(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                 Map<NormalizedVariantKey, VariantInterpretation> pending) {
        Map<NormalizedVariantKey, VariantInterpretation> snvPending =
                new LinkedHashMap<NormalizedVariantKey, VariantInterpretation>();
        Map<NormalizedVariantKey, VariantInterpretation> indelPending =
                new LinkedHashMap<NormalizedVariantKey, VariantInterpretation>();
        for (Map.Entry<NormalizedVariantKey, VariantInterpretation> entry : pending.entrySet()) {
            if (isSingleNucleotide(entry.getKey())) {
                snvPending.put(entry.getKey(), entry.getValue());
            } else {
                indelPending.put(entry.getKey(), entry.getValue());
            }
        }
        annotateFileGroup(
                result,
                snvPending,
                properties.getSpliceAi().getSnvResourcePath(),
                properties.getSpliceAi().getSnvIndexPath(),
                "SNV"
        );
        annotateFileGroup(
                result,
                indelPending,
                properties.getSpliceAi().getIndelResourcePath(),
                properties.getSpliceAi().getIndelIndexPath(),
                "indel"
        );
    }

    private void annotateFileGroup(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                   Map<NormalizedVariantKey, VariantInterpretation> pending,
                                   Path sourceFile,
                                   Path indexFile,
                                   String resourceType) {
        if (pending.isEmpty()) {
            return;
        }
        if (!indexedFileExists(sourceFile, indexFile)) {
            addUnavailable(result, pending,
                    "Configured SpliceAI GRCh38 " + resourceType + " resource or Tabix index is unavailable");
            return;
        }
        long deadline = System.nanoTime() + properties.getSpliceAi().normalizedTimeout().toNanos();
        try (TabixReader reader = new TabixReader(sourceFile.toString())) {
            for (NormalizedVariantKey key : pending.keySet()) {
                EvidenceItem evidence = System.nanoTime() > deadline
                        ? unavailableEvidence("SpliceAI local lookup timed out")
                        : query(reader, key);
                cache.putIfAbsent(key, evidence);
                result.put(key, SpecialistProviderSupport.single(evidence));
            }
        } catch (IOException | RuntimeException ex) {
            addUnavailable(
                    result,
                    pending,
                    "SpliceAI local " + resourceType + " lookup failed: "
                            + SpecialistProviderSupport.rootMessage(ex)
            );
        }
    }

    private boolean isSingleNucleotide(NormalizedVariantKey key) {
        return key.getReference() != null
                && key.getAlternate() != null
                && key.getReference().length() == 1
                && key.getAlternate().length() == 1;
    }

    private EvidenceItem query(TabixReader reader, NormalizedVariantKey key) throws IOException {
        List<SpliceAiEvidenceSupport.Prediction> predictions =
                new java.util.ArrayList<SpliceAiEvidenceSupport.Prediction>();
        for (String region : SpecialistProviderSupport.tabixRegions(key)) {
            TabixReader.Iterator iterator = reader.query(region);
            String line;
            while (iterator != null && (line = iterator.next()) != null) {
                String[] columns = line.split("\t");
                if (columns.length < 8 || !matchesVcfCoordinates(columns, key)) {
                    continue;
                }
                String spliceAi = infoValue(columns[7], "SpliceAI");
                predictions.addAll(SpliceAiEvidenceSupport.predictionsFromInfoValue(
                        spliceAi,
                        key.getAlternate()
                ));
            }
        }
        SpliceAiEvidenceSupport.Prediction best = SpliceAiEvidenceSupport.best(predictions);
        if (best == null) {
            return noRecordEvidence("Variant has no exact allele match in the configured SpliceAI GRCh38 resource");
        }
        String interpretation = SpliceAiEvidenceSupport.interpretation(
                best.getMaximumDeltaScore(),
                properties.getSpliceAi().getHighThreshold(),
                properties.getSpliceAi().getModerateThreshold(),
                properties.getSpliceAi().getLowThreshold()
        );
        return new EvidenceItem(
                source(),
                version(),
                "COMPUTATIONAL",
                "APPLICABLE",
                best.getMaximumDeltaScore(),
                "SpliceAI maximum delta score",
                "SpliceAI delta score is a splice-effect prioritization aid, not a clinical classification",
                SpliceAiEvidenceSupport.applicableDetails(
                        SpliceAiEvidenceSupport.SOURCE_LOCAL,
                        best,
                        predictions,
                        interpretation
                )
        );
    }

    static boolean matchesVcfCoordinates(String[] columns, NormalizedVariantKey key) {
        for (String alternate : columns[4].split(",")) {
            if (SpecialistProviderSupport.matchesCoordinates(
                    columns[0], columns[1], columns[3], alternate, key)) {
                return true;
            }
        }
        return false;
    }

    static String infoValue(String info, String key) {
        String prefix = key + "=";
        for (String value : info.split(";")) {
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length());
            }
        }
        return null;
    }

    private boolean indexedFileExists(Path sourceFile, Path indexFile) {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            return false;
        }
        if (indexFile != null) {
            return Files.isRegularFile(indexFile);
        }
        Path tbi = sourceFile.resolveSibling(sourceFile.getFileName() + ".tbi");
        Path csi = sourceFile.resolveSibling(sourceFile.getFileName() + ".csi");
        return Files.isRegularFile(tbi) || Files.isRegularFile(csi);
    }

    private EvidenceItem statusEvidence(String status,
                                        String label,
                                        String summary,
                                        String interpretation) {
        Map<String, Object> details = SpliceAiEvidenceSupport.baseDetails(
                SpliceAiEvidenceSupport.SOURCE_LOCAL,
                status,
                interpretation
        );
        details.put("value", null);
        return new EvidenceItem(
                source(),
                version(),
                "COMPUTATIONAL",
                status,
                null,
                label,
                summary,
                details
        );
    }

    private EvidenceItem noRecordEvidence(String summary) {
        return statusEvidence(
                "NO_RECORD",
                "No record",
                summary,
                "No matching local SpliceAI allele record was found; this is not interpreted as low splice risk"
        );
    }

    private EvidenceItem unavailableEvidence(String summary) {
        return statusEvidence(
                "UNAVAILABLE",
                "Unavailable",
                summary,
                "SpliceAI local evidence is unavailable; this is not interpreted as low risk"
        );
    }

    private EvidenceItem skippedEvidence(String reason) {
        return statusEvidence(
                "NOT_APPLICABLE",
                "SpliceAI lookup skipped",
                reason,
                "SpliceAI lookup was skipped by provider configuration; this is not interpreted as low risk"
        );
    }

    private void addUnavailable(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                Map<NormalizedVariantKey, VariantInterpretation> variants,
                                String summary) {
        EvidenceItem evidence = unavailableEvidence(summary);
        for (NormalizedVariantKey key : variants.keySet()) {
            result.put(key, Collections.singletonList(evidence));
        }
    }

    private void logMetrics(int input,
                            int queued,
                            int cacheHits,
                            Map<NormalizedVariantKey, List<EvidenceItem>> result,
                            long started) {
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        LOGGER.info(
                "SpliceAI provider lookup completed: input={}, queued={}, cacheHits={}, APPLICABLE={}, NO_RECORD={}, UNAVAILABLE={}, NOT_APPLICABLE={}, DISABLED={}, elapsedMs={}, batchSize={}",
                input,
                queued,
                cacheHits,
                count(result, "APPLICABLE"),
                count(result, "NO_RECORD"),
                count(result, "UNAVAILABLE"),
                count(result, "NOT_APPLICABLE"),
                count(result, "DISABLED"),
                elapsedMs,
                properties.getSpliceAi().normalizedBatchSize()
        );
    }

    private int count(Map<NormalizedVariantKey, List<EvidenceItem>> result, String status) {
        int count = 0;
        for (List<EvidenceItem> evidenceItems : result.values()) {
            for (EvidenceItem evidence : evidenceItems) {
                if (status.equals(evidence.getApplicability())) {
                    count++;
                }
            }
        }
        return count;
    }
}
