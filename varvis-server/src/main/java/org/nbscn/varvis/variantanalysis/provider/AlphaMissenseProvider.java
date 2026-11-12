package org.nbscn.varvis.variantanalysis.provider;

import htsjdk.tribble.readers.TabixReader;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!${variant-analysis.acmg-business.saved-observation-calibration-only:false}")
public class AlphaMissenseProvider implements VariantEvidenceProvider {

    static final String SUPPORTING_PROFILE_ID = "ALPHAMISSENSE_PP3_BP4_SUPPORTING@1.0.0";

    private final VariantAnalysisProperties properties;
    private final PredictionLocalResourceBinding rawBinding;
    private AlphaMissensePhaseBudget phaseBudget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPhaseBudget(AlphaMissensePhaseBudget budget) { this.phaseBudget = budget; }
    private final ConcurrentMap<NormalizedVariantKey, EvidenceItem> cache =
            new ConcurrentHashMap<NormalizedVariantKey, EvidenceItem>();

    @org.springframework.beans.factory.annotation.Autowired
    public AlphaMissenseProvider(VariantAnalysisProperties properties) {
        this(properties, null);
    }

    public AlphaMissenseProvider(VariantAnalysisProperties properties, PredictionLocalResourceBinding rawBinding) {
        this.properties = properties; this.rawBinding = rawBinding;
    }
    public AlphaMissenseProvider(VariantAnalysisProperties properties, PredictionLocalResourceBinding rawBinding, AlphaMissensePhaseBudget budget) {
        this(properties, rawBinding); phaseBudget = budget;
    }

    public com.fasterxml.jackson.databind.node.ObjectNode rawResourceIdentity(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (rawBinding != null) return rawBinding.identity();
        var out = mapper.createObjectNode().put("model", "ALPHAMISSENSE").put("dataVersion", version()).put("calibrationCompatibility", "UNVERIFIED");
        out.putObject("license").put("status", "UNVERIFIED"); return out;
    }

    @Override
    public String source() {
        return "ALPHAMISSENSE";
    }

    @Override
    public String version() {
        return properties.getAlphaMissense().getVersion();
    }

    /** Explicit isolated raw acquisition. No disease, transcript selection, score-based ranking or legacy cache. */
    public com.fasterxml.jackson.databind.node.ObjectNode lookupRecords(com.fasterxml.jackson.databind.ObjectMapper mapper,
            NormalizedVariantKey key, com.fasterxml.jackson.databind.JsonNode resource, String declaration, String dataClass) {
        return lookupRecords(mapper, key, resource, declaration, dataClass, false);
    }
    /** Cheap eligibility/metadata recheck before reusing a raw capture within this request. */
    String generalReuseIssue(com.fasterxml.jackson.databind.node.ObjectNode request, com.fasterxml.jackson.databind.JsonNode resource) {
        if (!properties.getAlphaMissense().isEnabled() || rawBinding == null) return "PREDICTION_RESOURCE_UNAVAILABLE";
        var a = request.at("/context/variant/evidenceQueryAllele");
        var key = new NormalizedVariantKey(a.path("assembly").asText(), a.path("contig").asText(), a.path("position").asInt(),
                a.path("reference").asText(), a.path("alternate").asText());
        if (!version().equals(resource.path("dataVersion").asText())) return "PREDICTION_RESOURCE_IDENTITY_MISMATCH";
        return rawBinding.generalIssue(properties.getAlphaMissense().getGrch38File(), key, resource, request.at("/context/dataClass").asText());
    }

    /** Only the server's explicitly configured general runtime calls this entry. */
    com.fasterxml.jackson.databind.node.ObjectNode lookupGeneralRecords(com.fasterxml.jackson.databind.ObjectMapper mapper,
            NormalizedVariantKey key, com.fasterxml.jackson.databind.JsonNode resource, String dataClass) {
        return lookupRecords(mapper, key, resource, PredictionDevelopmentRuntime.DECLARATION, dataClass, true);
    }
    private com.fasterxml.jackson.databind.node.ObjectNode lookupRecords(com.fasterxml.jackson.databind.ObjectMapper mapper,
            NormalizedVariantKey key, com.fasterxml.jackson.databind.JsonNode resource, String declaration, String dataClass, boolean general) {
        if (phaseBudget != null) phaseBudget.invocation(AlphaMissensePhaseBudget.RAW, null);
        var out = mapper.createObjectNode().put("schemaVersion", "prediction-observation-v1").put("status", "UNAVAILABLE")
                .put("dataClass", dataClass).put("observedAt", java.time.Instant.now().toString());
        out.set("resource", resource.deepCopy()); out.set("queryAllele", mapper.valueToTree(key)); out.putArray("rows");
        if (!PredictionDevelopmentRuntime.DECLARATION.equals(declaration)
                || dataClass == null || !Set.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(dataClass)) return out.put("reasonCode", "PREDICTION_EXPLICIT_PURPOSE_REQUIRED");
        String issue = PredictionResourceCompatibility.acquisitionIssue(resource);
        if (issue != null) return out.put("reasonCode", issue);
        if (!"ALPHAMISSENSE".equals(resource.path("model").asText()) || !version().equals(resource.path("dataVersion").asText())) return out.put("reasonCode", "PREDICTION_RESOURCE_IDENTITY_MISMATCH");
        if (key == null || !"GRCh38".equals(key.getGenomeAssembly()) || key.getChromosome() == null || key.getPosition() <= 0 || key.getReference() == null || key.getAlternate() == null
                || !key.getReference().matches("[ACGT]") || !key.getAlternate().matches("[ACGT]") || key.getReference().equals(key.getAlternate()))
            return out.put("reasonCode", "PREDICTION_NATIVE_SNV_REQUIRED");
        Path file = properties.getAlphaMissense().getGrch38File();
        if (!properties.getAlphaMissense().isEnabled() || !indexedFileExists(file)) return out.put("reasonCode", "PREDICTION_RESOURCE_UNAVAILABLE");
        if (rawBinding == null) return out.put("reasonCode", "PREDICTION_LOCAL_BINDING_UNCONFIGURED");
        issue = general ? rawBinding.generalIssue(file, key, resource, dataClass) : rawBinding.issue(file, key, resource, dataClass);
        if (issue != null) return out.put("reasonCode", issue);
        if (phaseBudget != null) phaseBudget.reserve(AlphaMissensePhaseBudget.RAW, key, file);
        out.put("transportAttempts", 1).put("scoreOrigin", "REAL_LOCAL_PRECOMPUTED_RESOURCE").put("referenceVerified", true);
        try (TabixReader reader = new TabixReader(file.toString())) {
            long deadline = System.nanoTime() + properties.getAlphaMissense().getTimeout().toNanos(); int count = 0;
            var rows = out.withArray("/rows");
            for (String region : SpecialistProviderSupport.tabixRegions(key)) {
                var iterator = reader.query(region); String line;
                while (iterator != null && (line = iterator.next()) != null) {
                    if (++count > 512 || System.nanoTime() > deadline) return out.put("reasonCode", "PREDICTION_QUERY_LIMIT_OR_TIMEOUT");
                    String[] fields = line.split("\t", -1);
                    if (fields.length < 10 || !SpecialistProviderSupport.matchesCoordinates(fields[0], fields[1], fields[2], fields[3], key)) continue;
                    if (!Set.of("hg38", "GRCh38").contains(fields[4])) return out.put("reasonCode", "PREDICTION_RECORD_ASSEMBLY_MISMATCH");
                    rows.addObject().put("sourceRow", line).put("transcriptId", fields[6]).put("proteinVariant", fields[7])
                            .put("uniprotId", fields[5]).put("rawScore", fields[8]).put("rawClass", fields[9]);
                }
            }
            out.put("status", rows.isEmpty() ? "NO_RECORD" : "HIT").put("reasonCode", rows.isEmpty() ? "NO_RECORD" : "RAW_RECORDS_OBTAINED");
            out.put("observationSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(out));
            if (phaseBudget != null) phaseBudget.captureRaw(key, out);
            return out;
        } catch (AlphaMissensePhaseBudget.Exceeded stopped) { throw stopped; }
        catch (IOException | RuntimeException failed) {
            out.put("reasonCode", "PREDICTION_RAW_LOOKUP_FAILED").put("exceptionType", failed.getClass().getSimpleName());
            if (phaseBudget != null) phaseBudget.captureRaw(key, out);
            return out;
        }
    }

    /** Identity projection only. Never invokes annotate(), parses score/class, or retains their columns. */
    public record IdentityRecord(String transcriptId, String aminoAcidChange, String uniprotId) { }
    public record IdentityLookup(String status, String reasonCode, String resourceVersion,
                                 String resourceStamp, List<IdentityRecord> records) { }

    public IdentityLookup lookupIdentities(VariantInterpretation candidate) {
        if (phaseBudget != null) phaseBudget.invocation(AlphaMissensePhaseBudget.IDENTITY, null);
        NormalizedVariantKey key = candidate.getObservation() == null ? null : candidate.getObservation().getKey();
        if (key == null || !"GRCh38".equals(key.getGenomeAssembly()) || key.getReference() == null || key.getAlternate() == null
                || !key.getReference().matches("[ACGT]") || !key.getAlternate().matches("[ACGT]"))
            return new IdentityLookup("UNAVAILABLE", "ASSEMBLY_OR_ALLELE_UNSUPPORTED", version(), null, List.of());
        Path file = properties.getAlphaMissense().getGrch38File();
        if (!properties.getAlphaMissense().isEnabled() || !indexedFileExists(file))
            return new IdentityLookup("UNAVAILABLE", "ALPHAMISSENSE_IDENTITY_RESOURCE_UNAVAILABLE", version(), null, List.of());
        try (TabixReader reader = new TabixReader(file.toString())) {
            Path index = file.resolveSibling(file.getFileName() + ".tbi");
            // Applicability stamp, not a claim of a whole-resource checksum verification.
            String stamp = Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis()
                    + ":" + Files.size(index) + ":" + Files.getLastModifiedTime(index).toMillis();
            long deadline = System.nanoTime() + properties.getAlphaMissense().getTimeout().toNanos();
            List<IdentityRecord> identities = new ArrayList<>(); int count = 0;
            for (String region : SpecialistProviderSupport.tabixRegions(key)) {
                var iterator = reader.query(region); String line;
                while (iterator != null && (line = iterator.next()) != null) {
                    if (++count > 512 || System.nanoTime() > deadline)
                        return new IdentityLookup("UNAVAILABLE", "IDENTITY_QUERY_LIMIT_OR_TIMEOUT", version(), stamp, List.of());
                    IdentityRecord identity = identityProjection(line, key);
                    if (identity != null) identities.add(identity);
                }
            }
            return new IdentityLookup("AVAILABLE", identities.isEmpty() ? "NO_RECORD" : "IDENTITY_RECORDS_AVAILABLE",
                    version(), stamp, sortedIdentities(identities));
        } catch (IOException | RuntimeException failed) {
            return new IdentityLookup("UNAVAILABLE", "ALPHAMISSENSE_IDENTITY_QUERY_FAILED", version(), null, List.of());
        }
    }

    static IdentityRecord identityProjection(String line, NormalizedVariantKey key) {
        // Read only the first eight identity columns. The suffix containing score/class is never parsed.
        String[] identity = new String[8]; int start = 0;
        for (int i = 0; i < identity.length; i++) {
            int end = line.indexOf('\t', start);
            if (end < 0) return null;
            identity[i] = line.substring(start, end).trim(); start = end + 1;
        }
        if (!SpecialistProviderSupport.matchesCoordinates(identity[0], identity[1], identity[2], identity[3], key)) return null;
        return new IdentityRecord(identity[6], identity[7], identity[5]);
    }
    static List<IdentityRecord> sortedIdentities(List<IdentityRecord> records) {
        return records.stream().sorted(java.util.Comparator.comparing((IdentityRecord record) -> record.transcriptId())
                .thenComparing(record -> record.aminoAcidChange()).thenComparing(record -> record.uniprotId())).toList();
    }

    @Override
    public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
            List<VariantInterpretation> variants,
            AnalysisContext context) {
        if (phaseBudget != null) phaseBudget.invocation(AlphaMissensePhaseBudget.LEGACY, context.getAnalysisId());
        Map<NormalizedVariantKey, List<EvidenceItem>> result =
                new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        if (!ProviderAssemblySupport.supportsOnlyGrch38(variants, context)) {
            return ProviderAssemblySupport.unsupportedForGrch38Only(
                    variants,
                    context,
                    source(),
                    version(),
                    "COMPUTATIONAL",
                    "AlphaMissense GRCh38 local file"
            );
        }
        Map<NormalizedVariantKey, VariantInterpretation> pending =
                new LinkedHashMap<NormalizedVariantKey, VariantInterpretation>();
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            if (!SpecialistProviderSupport.isMissense(variant)) {
                result.put(key, SpecialistProviderSupport.single(EvidenceItem.notApplicable(
                        source(), version(), "COMPUTATIONAL",
                        "AlphaMissense is queried only for missense variants"
                )));
                continue;
            }
            if (!properties.getAlphaMissense().isEnabled()) {
                result.put(key, SpecialistProviderSupport.single(EvidenceItem.unavailable(
                        source(), version(), "COMPUTATIONAL", "AlphaMissense local lookup is disabled"
                )));
                continue;
            }
            EvidenceItem cached = cache.get(key);
            if (cached != null) {
                if (phaseBudget != null) phaseBudget.cacheHit(AlphaMissensePhaseBudget.LEGACY, key);
                result.put(key, SpecialistProviderSupport.single(cached));
            } else {
                pending.put(key, variant);
            }
        }
        if (pending.isEmpty()) {
            return result;
        }

        Path sourceFile = properties.getAlphaMissense().getGrch38File();
        if (!indexedFileExists(sourceFile)) {
            addUnavailable(result, pending, "Configured AlphaMissense local file or Tabix index is unavailable");
            return result;
        }
        long deadline = System.nanoTime() + properties.getAlphaMissense().getTimeout().toNanos();
        if (phaseBudget != null) {
            for (NormalizedVariantKey key : pending.keySet()) {
                // Isolated task only: one bounded reader per allele makes each actual resource open auditable.
                phaseBudget.reserve(AlphaMissensePhaseBudget.LEGACY, key, sourceFile);
                EvidenceItem evidence;
                try (TabixReader reader = new TabixReader(sourceFile.toString())) {
                    evidence = System.nanoTime() > deadline ? EvidenceItem.unavailable(source(), version(), "COMPUTATIONAL",
                            "AlphaMissense local lookup timed out") : query(reader, key);
                } catch (IOException | RuntimeException ex) {
                    evidence = EvidenceItem.unavailable(source(), version(), "COMPUTATIONAL",
                            "AlphaMissense local lookup failed: " + SpecialistProviderSupport.rootMessage(ex));
                }
                cache.putIfAbsent(key, evidence); result.put(key, SpecialistProviderSupport.single(evidence));
            }
            return result;
        }
        try (TabixReader reader = new TabixReader(sourceFile.toString())) {
            for (NormalizedVariantKey key : pending.keySet()) {
                EvidenceItem evidence = System.nanoTime() > deadline
                        ? EvidenceItem.unavailable(source(), version(), "COMPUTATIONAL",
                        "AlphaMissense local lookup timed out")
                        : query(reader, key);
                cache.putIfAbsent(key, evidence);
                result.put(key, SpecialistProviderSupport.single(evidence));
            }
        } catch (IOException | RuntimeException ex) {
            addUnavailable(
                    result,
                    pending,
                    "AlphaMissense local lookup failed: " + SpecialistProviderSupport.rootMessage(ex)
            );
        }
        return result;
    }

    /**
     * E3-C1's explicit-context lookup. The caller must select the VEP consequence
     * before querying AlphaMissense; this provider never chooses a transcript by score.
     * The legacy provider entry point above remains available for the existing ranking
     * workflow and is intentionally not used by the E3-C1 criterion path.
     */
    public Map<NormalizedVariantKey, List<EvidenceItem>> annotateSelectedTranscripts(
            List<VariantInterpretation> variants,
            AnalysisContext context,
            Map<NormalizedVariantKey, TranscriptConsequence> selectedConsequences) {
        if (phaseBudget != null) phaseBudget.invocation("SELECTED_TRANSCRIPT_LEGACY_ENTRY", context.getAnalysisId());
        Map<NormalizedVariantKey, List<EvidenceItem>> result =
                new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        if (!ProviderAssemblySupport.supportsOnlyGrch38(variants, context)) {
            return ProviderAssemblySupport.unsupportedForGrch38Only(
                    variants,
                    context,
                    source(),
                    version(),
                    "COMPUTATIONAL",
                    "AlphaMissense GRCh38 local file"
            );
        }

        Map<NormalizedVariantKey, TranscriptConsequence> pending =
                new LinkedHashMap<NormalizedVariantKey, TranscriptConsequence>();
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            TranscriptConsequence selected = selectedConsequences == null
                    ? null : selectedConsequences.get(key);
            if (!isMissenseSnv(variant, selected)) {
                result.put(key, SpecialistProviderSupport.single(exactNotApplicable(
                        selected,
                        "AlphaMissense is applicable only to native GRCh38 missense SNVs"
                )));
                continue;
            }
            if (!properties.getAlphaMissense().isEnabled()) {
                result.put(key, SpecialistProviderSupport.single(exactNotEvaluated(
                        selected,
                        "AlphaMissense local lookup is disabled",
                        "DISABLED", "PROVIDER_DISABLED",
                        "UNKNOWN", "NOT_CHECKED",
                        "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED",
                        null, null
                )));
                continue;
            }
            if (!completeSelection(selected)) {
                result.put(key, SpecialistProviderSupport.single(exactNotEvaluated(
                        selected,
                        "Selected VEP transcript/protein context is incomplete for AlphaMissense exact matching",
                        "NOT_RUN", "SELECTED_PROTEIN_CONTEXT_INCOMPLETE",
                        "UNKNOWN", "NOT_CHECKED",
                        "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED",
                        null, null
                )));
                continue;
            }
            pending.put(key, selected);
        }
        if (pending.isEmpty()) {
            return result;
        }

        Path sourceFile = properties.getAlphaMissense().getGrch38File();
        if (!indexedFileExists(sourceFile)) {
            addSelectedNotEvaluated(
                    result, pending,
                    "Configured AlphaMissense local file or Tabix index is unavailable",
                    "NOT_RUN", "RESOURCE_UNAVAILABLE",
                    "UNAVAILABLE", "RESOURCE_UNAVAILABLE"
            );
            return result;
        }
        long deadline = System.nanoTime() + properties.getAlphaMissense().getTimeout().toNanos();
        try (TabixReader reader = new TabixReader(sourceFile.toString())) {
            for (Map.Entry<NormalizedVariantKey, TranscriptConsequence> entry : pending.entrySet()) {
                NormalizedVariantKey key = entry.getKey();
                TranscriptConsequence selected = entry.getValue();
                EvidenceItem evidence = System.nanoTime() > deadline
                        ? exactNotEvaluated(
                        selected,
                        "AlphaMissense local lookup timed out",
                        "TIMEOUT", "PROVIDER_TIMEOUT",
                        "AVAILABLE", null,
                        "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED",
                        null, null)
                        : queryExact(reader, key, selected);
                result.put(key, SpecialistProviderSupport.single(evidence));
            }
        } catch (IOException | RuntimeException ex) {
            addSelectedNotEvaluated(
                    result, pending,
                    "AlphaMissense local lookup failed: " + SpecialistProviderSupport.rootMessage(ex),
                    SpecialistProviderSupport.isTimeout(ex) ? "TIMEOUT" : "FAILED",
                    SpecialistProviderSupport.isTimeout(ex) ? "PROVIDER_TIMEOUT" : "PROVIDER_FAILED",
                    "AVAILABLE", null
            );
        }
        return result;
    }

    private EvidenceItem query(TabixReader reader, NormalizedVariantKey key) throws IOException {
        EvidenceItem best = null;
        for (String region : SpecialistProviderSupport.tabixRegions(key)) {
            TabixReader.Iterator iterator = reader.query(region);
            String line;
            while (iterator != null && (line = iterator.next()) != null) {
                String[] columns = line.split("\t");
                if (columns.length < 10 || !SpecialistProviderSupport.matchesCoordinates(
                        columns[0], columns[1], columns[2], columns[3], key)) {
                    continue;
                }
                Double score = SpecialistProviderSupport.parseDouble(columns[8]);
                if (score == null || (best != null && best.getRawScore() >= score)) {
                    continue;
                }
                Map<String, Object> details = new LinkedHashMap<String, Object>();
                details.put("genome", columns[4]);
                details.put("uniprotId", columns[5]);
                details.put("transcriptId", columns[6]);
                details.put("proteinVariant", columns[7]);
                details.put("classification", columns[9]);
                best = new EvidenceItem(
                        source(),
                        version(),
                        "COMPUTATIONAL",
                        "APPLICABLE",
                        score,
                        columns[9],
                        "AlphaMissense score is a missense prioritization aid, not a clinical classification",
                        details
                );
            }
        }
        return best == null
                ? EvidenceItem.unavailable(
                source(), version(), "COMPUTATIONAL",
                "Variant is absent from the configured AlphaMissense GRCh38 file")
                : best;
    }

    private EvidenceItem queryExact(TabixReader reader,
                                    NormalizedVariantKey key,
                                    TranscriptConsequence selected) throws IOException {
        List<String> records = new ArrayList<String>();
        for (String region : SpecialistProviderSupport.tabixRegions(key)) {
            TabixReader.Iterator iterator = reader.query(region);
            String line;
            while (iterator != null && (line = iterator.next()) != null) {
                records.add(line);
            }
        }
        return resolveExactRecords(records, key, selected);
    }

    EvidenceItem resolveExactRecords(Iterable<String> records,
                                     NormalizedVariantKey key,
                                     TranscriptConsequence selected) {
        String selectedTranscript = selected.getVersionedTranscriptId();
        String selectedProteinVariant = selected.getAminoAcidChange();
        String[] match = null;
        List<String[]> alleleRecords = new ArrayList<String[]>();
        for (String line : records) {
            String[] columns = line.split("\\t", -1);
            if (columns.length < 10 || !SpecialistProviderSupport.matchesCoordinates(
                    columns[0], columns[1], columns[2], columns[3], key)) {
                continue;
            }
            alleleRecords.add(columns);
            if (!selectedTranscript.equals(columns[6].trim())
                    || !selectedProteinVariant.equals(columns[7].trim())) {
                continue;
            }
            if (match != null) {
                return exactNotEvaluated(
                        selected,
                        "Conflicting AlphaMissense records match the selected transcript and protein change",
                        "SUCCEEDED", null,
                        "AVAILABLE", null,
                        "HIT", "UNKNOWN", "ALPHAMISSENSE_MATCH_CONFLICT",
                        true, true
                );
            }
            match = columns;
        }
        if (match == null) {
            if (!alleleRecords.isEmpty()) {
                return transcriptProteinMismatch(key, selected, alleleRecords);
            }
            return exactNotEvaluated(
                    selected,
                    "Variant is absent from the configured AlphaMissense GRCh38 file",
                    "SUCCEEDED", null,
                    "AVAILABLE", null,
                    "NO_RECORD", "APPLICABLE", null,
                    false, false
            );
        }
        BigDecimal score = parseDecimal(match[8]);
        if (score == null) {
            return exactNotEvaluated(
                    selected,
                    "The exact AlphaMissense record has no valid decimal pathogenicity score",
                    "SUCCEEDED", null,
                    "AVAILABLE", null,
                    "HIT", "UNKNOWN", "ALPHAMISSENSE_SCORE_INVALID",
                    true, true
            );
        }
        return hit(key, match, score, selected);
    }

    private EvidenceItem transcriptProteinMismatch(NormalizedVariantKey key,
                                                   TranscriptConsequence selected,
                                                   List<String[]> alleleRecords) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        addSelectedIdentity(details, selected);
        details.put("exactAlleleMatch", true);
        details.put("transcriptProteinMatch", false);
        details.put("observedRecordCount", alleleRecords.size());
        details.put("observedTranscriptIds", observedValues(alleleRecords, 6));
        details.put("observedProteinVariants", observedValues(alleleRecords, 7));
        details.put("observedUniprotIds", observedValues(alleleRecords, 5));
        details.put("calibrationProfileId", SUPPORTING_PROFILE_ID);
        addAxes(details, "SUCCEEDED", null, "AVAILABLE", null,
                "HIT", "UNKNOWN", "TRANSCRIPT_PROTEIN_MISMATCH");

        Map<String, Object> provenance = new LinkedHashMap<String, Object>();
        provenance.put("queryAllele", key.canonicalId());
        provenance.put("sourceRecordScope", "ALLELE_RECORDS");
        provenance.put("observedRecordCount", alleleRecords.size());
        provenance.put("observedTranscriptIds", observedValues(alleleRecords, 6));
        provenance.put("observedProteinVariants", observedValues(alleleRecords, 7));
        String sourceRecordId = source() + ":" + key.canonicalId() + ":ALLELE_RECORDS";
        return new EvidenceItem(
                EvidenceItem.CURRENT_SCHEMA_VERSION,
                null,
                key.canonicalId(),
                key.getChromosome(),
                key.getPosition(),
                key.getReference(),
                key.getAlternate(),
                null,
                selected.getVersionedTranscriptId(),
                null,
                source(),
                sourceRecordId,
                version(),
                version(),
                null,
                key.getGenomeAssembly(),
                "COMPUTATIONAL",
                "UNAVAILABLE",
                "UNAVAILABLE",
                null,
                null,
                null,
                SUPPORTING_PROFILE_ID,
                EvidenceItem.INDETERMINATE_STRENGTH,
                "Not evaluated",
                "AlphaMissense has allele records, but none exactly matches the selected transcript and protein change",
                provenance,
                null,
                "TRANSCRIPT_PROTEIN_MISMATCH",
                details
        );
    }

    private List<String> observedValues(List<String[]> records, int column) {
        Set<String> values = new LinkedHashSet<String>();
        for (String[] record : records) {
            if (record.length <= column) continue;
            String value = record[column].trim();
            if (!value.isEmpty()) values.add(value);
        }
        List<String> sorted = new ArrayList<String>(values);
        Collections.sort(sorted);
        return sorted;
    }

    private EvidenceItem hit(NormalizedVariantKey key,
                             String[] columns,
                             BigDecimal score,
                             TranscriptConsequence selected) {
        String rawScore = columns[8].trim();
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("genome", columns[4]);
        details.put("uniprotId", columns[5]);
        details.put("transcriptId", columns[6]);
        details.put("proteinVariant", columns[7]);
        details.put("classification", columns[9]);
        details.put("rawScore", rawScore);
        details.put("exactAlleleMatch", true);
        addSelectedIdentity(details, selected);
        details.put("transcriptProteinMatch", true);
        details.put("calibrationProfileId", SUPPORTING_PROFILE_ID);
        addAxes(details, "SUCCEEDED", null, "AVAILABLE", null,
                "HIT", "APPLICABLE", null);

        Map<String, Object> provenance = new LinkedHashMap<String, Object>();
        provenance.put("queryAllele", key.canonicalId());
        provenance.put("transcriptId", columns[6]);
        provenance.put("proteinVariant", columns[7]);
        provenance.put("uniprotId", columns[5]);
        String sourceRecordId = source() + ":" + key.canonicalId()
                + ":" + columns[6] + ":" + columns[7];
        return new EvidenceItem(
                EvidenceItem.CURRENT_SCHEMA_VERSION,
                null,
                key.canonicalId(),
                key.getChromosome(),
                key.getPosition(),
                key.getReference(),
                key.getAlternate(),
                null,
                selected.getVersionedTranscriptId(),
                null,
                source(),
                sourceRecordId,
                version(),
                version(),
                null,
                key.getGenomeAssembly(),
                "COMPUTATIONAL",
                "APPLICABLE",
                "APPLICABLE",
                score.doubleValue(),
                rawScore,
                null,
                SUPPORTING_PROFILE_ID,
                EvidenceItem.INDETERMINATE_STRENGTH,
                columns[9],
                "AlphaMissense score is computational prediction evidence, not a clinical classification",
                provenance,
                null,
                null,
                details
        );
    }

    private EvidenceItem exactNotEvaluated(TranscriptConsequence selected,
                                           String summary,
                                           String execution,
                                           String executionReason,
                                           String availability,
                                           String availabilityReason,
                                           String query,
                                           String applicability,
                                           String applicabilityReason,
                                           Boolean exactAlleleMatch,
                                           Boolean transcriptProteinMatch) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        addSelectedIdentity(details, selected);
        if (exactAlleleMatch != null) details.put("exactAlleleMatch", exactAlleleMatch);
        if (transcriptProteinMatch != null) details.put("transcriptProteinMatch", transcriptProteinMatch);
        details.put("calibrationProfileId", SUPPORTING_PROFILE_ID);
        addAxes(details, execution, executionReason, availability, availabilityReason,
                query, applicability, applicabilityReason);
        return new EvidenceItem(
                source(), version(), "COMPUTATIONAL", "UNAVAILABLE", null,
                "Not evaluated", summary, details
        );
    }

    private EvidenceItem exactNotApplicable(TranscriptConsequence selected, String summary) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        addSelectedIdentity(details, selected);
        details.put("calibrationProfileId", SUPPORTING_PROFILE_ID);
        addAxes(details, "NOT_RUN", "VARIANT_NOT_MISSENSE_SNV",
                "AVAILABLE", null, "NOT_QUERIED", "NOT_APPLICABLE",
                "VARIANT_NOT_MISSENSE_SNV");
        return new EvidenceItem(
                source(), version(), "COMPUTATIONAL", "NOT_APPLICABLE", null,
                "Not applicable", summary, details
        );
    }

    private void addSelectedIdentity(Map<String, Object> details, TranscriptConsequence selected) {
        if (selected == null) return;
        if (selected.getVersionedTranscriptId() != null) {
            details.put("selectedTranscriptId", selected.getVersionedTranscriptId());
        }
        if (selected.getHgvsp() != null) details.put("selectedHgvsp", selected.getHgvsp());
        if (selected.getProteinIdentity() != null) {
            details.put("selectedProteinIdentity", selected.getProteinIdentity());
        }
        if (selected.getAminoAcidChange() != null) {
            details.put("selectedAminoAcidChange", selected.getAminoAcidChange());
        }
    }

    private void addAxes(Map<String, Object> details,
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

    private boolean completeSelection(TranscriptConsequence selected) {
        return selected != null
                && selected.getConsequence() != null
                && !selected.getConsequence().trim().isEmpty()
                && selected.getVersionedTranscriptId() != null
                && selected.getHgvsp() != null
                && selected.getProteinIdentity() != null
                && selected.getAminoAcidChange() != null;
    }

    private boolean isMissenseSnv(VariantInterpretation variant, TranscriptConsequence selected) {
        if (variant == null) return false;
        NormalizedVariantKey key = variant.getObservation() == null
                ? null : variant.getObservation().getKey();
        if (key == null || key.getReference() == null || key.getAlternate() == null
                || key.getReference().length() != 1 || key.getAlternate().length() != 1) {
            return false;
        }
        if (selected == null) {
            Boolean missense = missenseStatus(variant.getTranscriptAnnotations());
            if (missense == null) missense = missenseStatus(variant.getConsequences());
            // No selected or source consequence is incomplete context, not proof that
            // the variant is outside the missense profile.
            return missense == null || missense;
        }
        String consequence = selected.getConsequence();
        return consequence == null
                || consequence.trim().isEmpty()
                || hasMissenseTerm(consequence);
    }

    private Boolean missenseStatus(List<TranscriptConsequence> consequences) {
        if (consequences == null || consequences.isEmpty()) return null;
        boolean consequenceSeen = false;
        for (TranscriptConsequence consequence : consequences) {
            String value = consequence == null ? null : consequence.getConsequence();
            if (value == null || value.trim().isEmpty()) continue;
            consequenceSeen = true;
            if (hasMissenseTerm(value)) return true;
        }
        return consequenceSeen ? Boolean.FALSE : null;
    }

    private boolean hasMissenseTerm(String consequence) {
        for (String term : consequence.split("&")) {
            if ("missense_variant".equals(term.trim())) return true;
        }
        return false;
    }

    private BigDecimal parseDecimal(String value) {
        try {
            return value == null || value.trim().isEmpty() ? null : new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean indexedFileExists(Path sourceFile) {
        return sourceFile != null
                && Files.isRegularFile(sourceFile)
                && Files.isRegularFile(sourceFile.resolveSibling(sourceFile.getFileName() + ".tbi"));
    }

    private void addUnavailable(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                Map<NormalizedVariantKey, VariantInterpretation> variants,
                                String summary) {
        for (NormalizedVariantKey key : variants.keySet()) {
            result.put(key, Collections.singletonList(
                    EvidenceItem.unavailable(source(), version(), "COMPUTATIONAL", summary)
            ));
        }
    }

    private void addSelectedNotEvaluated(
            Map<NormalizedVariantKey, List<EvidenceItem>> result,
            Map<NormalizedVariantKey, TranscriptConsequence> selected,
            String summary,
            String execution,
            String executionReason,
            String availability,
            String availabilityReason) {
        for (Map.Entry<NormalizedVariantKey, TranscriptConsequence> entry : selected.entrySet()) {
            result.put(entry.getKey(), SpecialistProviderSupport.single(exactNotEvaluated(
                    entry.getValue(), summary,
                    execution, executionReason,
                    availability, availabilityReason,
                    "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED",
                    null, null
            )));
        }
    }
}
