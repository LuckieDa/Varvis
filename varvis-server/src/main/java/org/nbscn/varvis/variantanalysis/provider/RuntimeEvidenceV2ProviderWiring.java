package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Minimal provider-to-carrier wiring established in E2.1 and reused by E3-P0.
 * It requires a caller-supplied complete context and never evaluates an ACMG criterion.
 */
public final class RuntimeEvidenceV2ProviderWiring {

    private static final String CLINVAR_COMPARATOR_DISCOVERY_ROLE =
            "BOUNDED_COMPARATOR_DISCOVERY_AND_ALLELE_IDENTITY_CROSSCHECK_ONLY";
    private static final String CLINVAR_COMPARATOR_VEP_ROLE =
            "SELECTED_TRANSCRIPT_PROTEIN_RELATION";
    private static final String CLINVAR_COMPARATOR_SIDECAR_ROLE =
            "CONDITION_LINKED_ASSERTION_SEMANTICS_ONLY";

    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final Clock clock;

    public RuntimeEvidenceV2ProviderWiring(ObjectMapper mapper, RuntimeEvidenceV2Adapter adapter) {
        this(mapper, adapter, Clock.systemUTC());
    }

    RuntimeEvidenceV2ProviderWiring(ObjectMapper mapper,
                                    RuntimeEvidenceV2Adapter adapter,
                                    Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Resolves and verifies one canonical resource identity from the E2 manifest. */
    public ResourceBinding bind(JsonNode manifest, String resourceId) {
        if (manifest == null || !manifest.path("resources").isArray()) {
            throw invalid("resource identity manifest is missing resources");
        }
        JsonNode match = null;
        for (JsonNode resource : manifest.path("resources")) {
            if (resourceId != null && resourceId.equals(resource.path("resourceId").asText())) {
                if (match != null) throw invalid("resourceId is duplicated: " + resourceId);
                match = resource;
            }
        }
        if (match == null) throw invalid("resourceId is not declared: " + resourceId);

        String provider = requiredText(match, "provider");
        String assembly = requiredText(match, "assembly");
        String resourceVersion = requiredText(match, "resourceVersion");
        String fingerprint = requiredText(match, "identityFingerprintSha256");
        JsonNode identity = match.path("identity");
        if (!identity.isObject()
                || !provider.equals(identity.path("provider").asText())
                || !assembly.equals(identity.path("assembly").asText())
                || !fingerprint.equals(adapter.canonicalSha256(identity))) {
            throw invalid("resource identity fingerprint or binding is invalid: " + resourceId);
        }
        String providerVersion = "VEP".equals(provider)
                ? requiredText(identity.path("tool"), "version")
                : resourceVersion;
        return new ResourceBinding(
                requiredText(match, "resourceId"),
                provider,
                providerVersion,
                assembly,
                resourceVersion,
                fingerprint
        );
    }

    /**
     * Projects the explicit E3-K1A comparator input into one atomic record per
     * submitted assertion. This remains an opt-in runtime boundary and does not
     * evaluate PS1 or PM5.
     */
    public List<ObjectNode> projectClinVarComparatorEvidence(
            JsonNode evidenceContext,
            ClinVarConditionAwareComparatorInput.Result result,
            JsonNode profile) {
        if (evidenceContext == null || result == null || profile == null) {
            throw invalid("context, comparator input and profile are required");
        }
        NormalizedVariantKey target = result.getTargetAllele();
        if (target == null
                || !evidenceContext.path("contextId").asText().equals(result.getContextId())
                || !allele(target).equals(
                evidenceContext.path("variant").path("evidenceQueryAllele"))) {
            throw invalid("comparator input does not match the evidence context");
        }
        ComparatorProjectionBindings bindings = comparatorProjectionBindings(profile);
        if (!bindings.sidecar.assembly.equals(target.getGenomeAssembly())) {
            throw invalid("comparator profile assembly does not match the target allele");
        }

        Instant attemptedAt = clock.instant();
        Instant completedAt = clock.instant();
        List<ObjectNode> evidence = new ArrayList<ObjectNode>();
        if ("READY".equals(result.getStatus())) {
            for (ClinVarConditionAwareComparatorInput.ComparatorRecord comparator
                    : result.getComparators()) {
                ArrayNode submissions = comparator.getSubmissions();
                if (submissions.isEmpty()) {
                    evidence.add(adapter.adapt(evidenceContext, comparatorAssertionCarrier(
                            target, comparator, mapper.createObjectNode(), 0, bindings,
                            attemptedAt, completedAt, profile)));
                    continue;
                }
                for (int index = 0; index < submissions.size(); index++) {
                    JsonNode submission = submissions.get(index);
                    if (submission == null || !submission.isObject()) {
                        throw invalid("ClinVar comparator submission must be an object");
                    }
                    evidence.add(adapter.adapt(evidenceContext, comparatorAssertionCarrier(
                            target, comparator, submission, index, bindings,
                            attemptedAt, completedAt, profile)));
                }
            }
            if (evidence.isEmpty()) {
                throw invalid("READY comparator input contains no comparator records");
            }
        } else if ("NO_RECORD".equals(result.getStatus())) {
            Axes axes = new Axes("SUCCEEDED", null, "AVAILABLE", null,
                    "NO_RECORD", "APPLICABLE", null);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("inputSchema", result.getSchemaVersion());
            payload.put("inputStatus", result.getStatus());
            payload.put("profileSha256", adapter.canonicalSha256(profile));
            ObjectNode carrier = carrier(bindings.sidecar, target, "CLINICAL_DATABASE",
                    axes, null, mapper.createArrayNode(), attemptedAt, completedAt,
                    adapter.canonicalSha256(payload));
            setComparatorResourceLocators(carrier, bindings, completedAt);
            evidence.add(adapter.adapt(evidenceContext, carrier));
        } else if ("NOT_EVALUATED".equals(result.getStatus())) {
            ObjectNode carrier = comparatorNotEvaluatedCarrier(
                    target, result, bindings, attemptedAt, completedAt, profile);
            evidence.add(adapter.adapt(evidenceContext, carrier));
        } else {
            throw invalid("unsupported comparator input status: " + result.getStatus());
        }
        evidence.sort((left, right) -> left.path("evidenceId").asText()
                .compareTo(right.path("evidenceId").asText()));
        return Collections.unmodifiableList(evidence);
    }

    /** Runs one VariantEvidenceProvider and converts each atomic provider result. */
    public List<ObjectNode> annotateVariantProvider(JsonNode evidenceContext,
                                                    VariantEvidenceProvider provider,
                                                    VariantInterpretation variant,
                                                    AnalysisContext analysisContext,
                                                    ResourceBinding binding) {
        if (variant == null || variant.getObservation() == null
                || variant.getObservation().getKey() == null) {
            throw invalid("context, variant and resource binding are required");
        }
        NormalizedVariantKey key = variant.getObservation().getKey();
        Map<NormalizedVariantKey, JsonNode> contexts =
                Collections.singletonMap(key, evidenceContext);
        Map<NormalizedVariantKey, List<ObjectNode>> evidence = annotateVariantProviderBatch(
                contexts,
                provider,
                Collections.singletonList(variant),
                analysisContext,
                binding
        );
        List<ObjectNode> result = evidence.get(key);
        return result == null ? Collections.emptyList() : result;
    }

    /** One bounded candidate query for allele-scoped BA1; uses the same carrier validation as evidence-v2. */
    ObjectNode queryPopulationCarrier(VariantEvidenceProvider provider, VariantInterpretation variant,
                                      AnalysisContext analysis, ResourceBinding binding) {
        return queryPopulationCarrier(provider, variant, analysis, binding, false);
    }

    ObjectNode queryNeutralPopulationCarrier(VariantEvidenceProvider provider, VariantInterpretation variant,
            AnalysisContext analysis, ResourceBinding binding, java.util.function.Consumer<String> rawRecord) {
        return queryPopulationCarrier(provider, variant, analysis, binding, false, rawRecord);
    }

    ObjectNode queryNeutralPopulationCarrier(VariantEvidenceProvider provider, VariantInterpretation variant,
            AnalysisContext analysis, ResourceBinding binding, java.util.function.Consumer<String> rawRecord,
            java.util.function.Consumer<Map<String, Object>> sourceDetails) {
        return queryPopulationCarrier(provider, variant, analysis, binding, false, rawRecord, sourceDetails);
    }

    /** Exact raw/ALT/field-contract verification shared by the independent BS1 observation boundary. */
    void verifyPopulationRecord(ObjectNode carrier, JsonNode allele, String raw, GnomadPopulationInputProfile profile) {
        adapter.validatePopulationCarrier(carrier, allele);
        var resource = profile.getDocument().path("resources").get(0);
        if (resource == null || !resource.path("resourceId").equals(carrier.at("/source/sourceId"))
                || !resource.path("resourceVersion").equals(carrier.at("/source/resourceVersion"))
                || !resource.path("identityFingerprintSha256").equals(carrier.at("/source/resourceSha256")))
            throw invalid("POPULATION_OBSERVATION_RESOURCE_MISMATCH");
        if (!"HIT".equals(carrier.at("/queryOutcome/status").asText())) {
            if (!raw.isEmpty() || !carrier.path("measurements").isEmpty()) throw invalid("POPULATION_NONHIT_MEASUREMENTS_INVALID");
            return;
        }
        if (!"SUCCEEDED".equals(carrier.at("/resourceExecution/status").asText())
                || carrier.at("/queryOutcome/recordCount").asInt() != 1 || raw.length() > 1_048_576
                || raw.contains("\n") || raw.contains("\r")) throw invalid("POPULATION_OUTPUT_INCOMPLETE");
        String[] columns = raw.split("\t", -1);
        if (columns.length < 8 || !columns[0].replaceFirst("^chr", "").equals(allele.path("contig").asText())
                || !columns[1].equals(allele.path("position").asText()) || !columns[3].equals(allele.path("reference").asText()))
            throw invalid("POPULATION_RAW_ALLELE_MISMATCH");
        String[] alts = columns[4].split(",", -1); int index = -1;
        for (int i = 0; i < alts.length; i++) if (alts[i].equals(allele.path("alternate").asText())) {
            if (index >= 0) throw invalid("POPULATION_RAW_ALT_AMBIGUOUS");
            index = i;
        }
        if (index < 0) throw invalid("POPULATION_RAW_ALLELE_MISMATCH");
        Map<String, String> info = new LinkedHashMap<>();
        for (String field : columns[7].split(";", -1)) {
            String[] pair = field.split("=", 2);
            if (pair[0].isBlank() || info.put(pair[0], pair.length == 2 ? pair[1] : "true") != null)
                throw invalid("POPULATION_RAW_INFO_INVALID");
        }
        var parsed = mapper.createArrayNode();
        addPopulationMeasurements(parsed, profile.parse(columns[6], info, index, alts.length).getMeasurements());
        if (!parsed.equals(carrier.path("measurements"))) throw invalid("POPULATION_RAW_MEASUREMENT_MISMATCH");
    }

    ObjectNode queryBa1PopulationCarrier(VariantEvidenceProvider provider, VariantInterpretation variant,
                                         AnalysisContext analysis, ResourceBinding binding) {
        return queryPopulationCarrier(provider, variant, analysis, binding, true);
    }

    ObjectNode queryBa1PopulationCarrier(VariantEvidenceProvider provider, VariantInterpretation variant,
                                         AnalysisContext analysis, ResourceBinding binding, java.util.function.Consumer<String> rawRecord) {
        return queryPopulationCarrier(provider, variant, analysis, binding, true, rawRecord);
    }

    private ObjectNode queryPopulationCarrier(VariantEvidenceProvider provider, VariantInterpretation variant,
                                               AnalysisContext analysis, ResourceBinding binding, boolean ba1Presence) {
        return queryPopulationCarrier(provider, variant, analysis, binding, ba1Presence, ignored -> { });
    }

    private ObjectNode queryPopulationCarrier(VariantEvidenceProvider provider, VariantInterpretation variant,
                                               AnalysisContext analysis, ResourceBinding binding, boolean ba1Presence,
                                               java.util.function.Consumer<String> rawRecord) {
        return queryPopulationCarrier(provider, variant, analysis, binding, ba1Presence, rawRecord, ignored -> { });
    }

    private ObjectNode queryPopulationCarrier(VariantEvidenceProvider provider, VariantInterpretation variant,
            AnalysisContext analysis, ResourceBinding binding, boolean ba1Presence,
            java.util.function.Consumer<String> rawRecord, java.util.function.Consumer<Map<String, Object>> sourceDetails) {
        var key = variant.getObservation().getKey();
        if (!"GNOMAD".equals(binding.provider) || !binding.assembly.equals(key.getGenomeAssembly()))
            throw invalid("population resource assembly mismatch");
        validateAnalysisContext(analysis, key); validateProviderFamily(provider.source(), binding);
        Instant started = clock.instant();
        ObjectNode result;
        try {
            var values = provider.annotate(List.of(variant), analysis);
            var items = values == null ? null : values.get(key);
            if (items == null || items.size() != 1 || items.get(0) == null) throw invalid("provider payload invalid");
            var item = items.get(0);
            if (!EvidenceItem.CURRENT_SCHEMA_VERSION.equals(item.getSchemaVersion())
                    || !binding.resourceVersion.equals(item.getEffectiveResourceVersion())
                    || item.getAssembly() != null && !binding.assembly.equals(item.getAssembly()))
                throw invalid("provider identity mismatch");
            validateProviderFamily(item.getSource(), binding);
            result = evidenceItemCarrier(item, binding, key, started, clock.instant());
            if (ba1Presence) preserveBa1SubtypeFilters(result,
                    Objects.toString(item.getDetails().get("rawPopulationRecord"), ""));
            adapter.validatePopulationCarrier(result, allele(key));
            rawRecord.accept(Objects.toString(item.getDetails().get("rawPopulationRecord"), ""));
            sourceDetails.accept(item.getDetails());
        } catch (RuntimeException failed) {
            boolean timeout = SpecialistProviderSupport.isTimeout(failed);
            String code = timeout ? "PROVIDER_TIMEOUT" : "PROVIDER_RUNTIME_OR_PAYLOAD_FAILURE";
            result = carrier(binding, key, "POPULATION_FREQUENCY", new Axes(timeout ? "TIMEOUT" : "FAILED", code,
                    "UNKNOWN", "NOT_CHECKED", "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED"), null, mapper.createArrayNode(),
                    started, clock.instant(), adapter.canonicalSha256(mapper.createObjectNode().put("failureCode", code)));
        }
        adapter.validatePopulationCarrier(result, allele(key));
        return result;
    }

    /** V2 BA1 alone retains the distinction between an absent INFO key and explicit '.'. */
    void preserveBa1SubtypeFilters(ObjectNode carrier, String rawRecord) {
        if (!"HIT".equals(carrier.at("/queryOutcome/status").asText())) return;
        String calibration = null;
        for (JsonNode measurement : carrier.path("measurements"))
            if ("gnomad.inputProfileId".equals(measurement.path("metricId").asText()))
                calibration = measurement.path("calibrationProfileId").asText();
        if (calibration == null || calibration.isBlank()) throw invalid("BA1_PROFILE_CALIBRATION_REQUIRED");
        Map<String, String> fields = new LinkedHashMap<>();
        String[] columns = rawRecord.split("\\t", -1);
        if (columns.length >= 8) for (String item : columns[7].split(";")) {
            String[] pair = item.split("=", 2);
            if (fields.put(pair[0], pair.length == 2 ? pair[1] : "") != null)
                throw invalid("POPULATION_RAW_INFO_AMBIGUOUS");
        }
        for (String subtype : List.of("exomes", "genomes")) {
            String value = fields.get(subtype + "_filters");
            ((ArrayNode) carrier.path("measurements")).addObject()
                    .put("metricId", "gnomad.joint." + subtype + ".filtersPresence")
                    .put("rawValue", value == null ? "MISSING" : value)
                    .put("normalizedValue", value == null ? "MISSING" : value)
                    .put("unit", "vcf-filter-list").put("calibrationProfileId", calibration);
        }
        ObjectNode binding = mapper.createObjectNode().put("originalPayloadSha256", carrier.path("sourcePayloadSha256").asText());
        binding.set("measurements", carrier.path("measurements"));
        carrier.put("sourcePayloadSha256", adapter.canonicalSha256(binding));
    }

    /** Runs one provider batch and converts each queried allele through the existing carrier and adapter. */
    public Map<NormalizedVariantKey, List<ObjectNode>> annotateVariantProviderBatch(
            Map<NormalizedVariantKey, JsonNode> evidenceContexts,
            VariantEvidenceProvider provider,
            List<VariantInterpretation> variants,
            AnalysisContext analysisContext,
            ResourceBinding binding) {
        if (evidenceContexts == null || provider == null || variants == null || variants.isEmpty()) {
            throw invalid("evidence contexts, provider and variants are required");
        }
        Map<NormalizedVariantKey, JsonNode> contexts =
                new LinkedHashMap<NormalizedVariantKey, JsonNode>();
        for (VariantInterpretation variant : variants) {
            if (variant == null || variant.getObservation() == null
                    || variant.getObservation().getKey() == null) {
                throw invalid("context, variant and resource binding are required");
            }
            NormalizedVariantKey key = variant.getObservation().getKey();
            JsonNode evidenceContext = evidenceContexts.get(key);
            if (contexts.containsKey(key)) {
                throw invalid("queried allele is duplicated in the provider batch");
            }
            contexts.put(key, evidenceContext);
            validateInputs(evidenceContext, variant, binding);
            validateAnalysisContext(analysisContext, key);
        }
        validateProviderFamily(provider.source(), binding);
        Instant attemptedAt = clock.instant();
        Map<NormalizedVariantKey, List<EvidenceItem>> providerResult;
        try {
            providerResult = "ALPHAMISSENSE".equals(binding.provider)
                    ? annotateSelectedAlphaMissense(
                    provider, variants, analysisContext, contexts)
                    : provider.annotate(new ArrayList<VariantInterpretation>(variants), analysisContext);
        } catch (RuntimeException failure) {
            Instant completedAt = clock.instant();
            Map<NormalizedVariantKey, List<ObjectNode>> failed =
                    new LinkedHashMap<NormalizedVariantKey, List<ObjectNode>>();
            for (Map.Entry<NormalizedVariantKey, JsonNode> entry : contexts.entrySet()) {
                failed.put(entry.getKey(), failure(
                        entry.getValue(), binding, entry.getKey(), evidenceType(binding),
                        attemptedAt, completedAt, failure, "PROVIDER_RUNTIME_EXCEPTION"));
            }
            return failed;
        }

        Instant completedAt = clock.instant();
        Map<NormalizedVariantKey, List<ObjectNode>> evidenceByKey =
                new LinkedHashMap<NormalizedVariantKey, List<ObjectNode>>();
        for (Map.Entry<NormalizedVariantKey, JsonNode> entry : contexts.entrySet()) {
            NormalizedVariantKey key = entry.getKey();
            JsonNode evidenceContext = entry.getValue();
            List<EvidenceItem> items = providerResult == null ? null : providerResult.get(key);
            if (items == null || items.isEmpty()) {
                evidenceByKey.put(key, failure(
                        evidenceContext, binding, key, evidenceType(binding),
                        attemptedAt, completedAt,
                        invalid("provider returned no result for the queried allele"),
                        "PROVIDER_PAYLOAD_INVALID"));
                continue;
            }
            try {
                List<ObjectNode> evidence = new ArrayList<ObjectNode>();
                for (EvidenceItem item : items) {
                    if (item == null) throw invalid("provider returned a null evidence item");
                    if ("CONFLICTING".equals(item.getEffectiveStatus())
                            && !"CLINVAR".equals(binding.provider)) {
                        throw invalid("conflicting provider payload requires the governed conflict workflow");
                    }
                    if (!EvidenceItem.CURRENT_SCHEMA_VERSION.equals(item.getSchemaVersion())) {
                        throw invalid("provider result is not evidence-item-v1");
                    }
                    validateProviderFamily(item.getSource(), binding);
                    if (!binding.resourceVersion.equals(item.getEffectiveResourceVersion())) {
                        throw invalid("provider result version does not match the resource binding");
                    }
                    if (item.getAssembly() != null && !binding.assembly.equals(item.getAssembly())) {
                        throw invalid("provider result assembly does not match the resource binding");
                    }
                    ObjectNode carrier = evidenceItemCarrier(item, binding, key, attemptedAt, completedAt);
                    evidence.add(adapter.adapt(evidenceContext, carrier));
                }
                evidenceByKey.put(key, evidence);
            } catch (RuntimeException failure) {
                evidenceByKey.put(key, failure(
                        evidenceContext, binding, key, evidenceType(binding),
                        attemptedAt, completedAt, failure, "PROVIDER_PAYLOAD_INVALID"));
            }
        }
        return evidenceByKey;
    }

    /**
     * Runs the explicit SpliceAI raw-score batch path while preserving the existing
     * provider-to-carrier conversion. The provider's default masked lookup is not used.
     */
    public Map<NormalizedVariantKey, List<ObjectNode>> annotateSpliceAiRawBatch(
            Map<NormalizedVariantKey, JsonNode> evidenceContexts,
            SpliceAiProvider provider,
            List<VariantInterpretation> variants,
            AnalysisContext analysisContext,
            ResourceBinding binding,
            SpliceAiRawBatchRunner runner) {
        if (provider == null || runner == null || binding == null
                || !"SPLICEAI".equals(binding.provider)) {
            throw invalid("SpliceAI provider, runner and resource binding are required");
        }
        Map<NormalizedVariantKey, TranscriptConsequence> selected =
                new LinkedHashMap<NormalizedVariantKey, TranscriptConsequence>();
        if (variants != null) {
            for (VariantInterpretation variant : variants) {
                if (variant == null || variant.getObservation() == null
                        || variant.getObservation().getKey() == null) continue;
                NormalizedVariantKey key = variant.getObservation().getKey();
                TranscriptConsequence consequence = selectedVepConsequence(
                        evidenceContexts == null ? null : evidenceContexts.get(key), variant);
                if (consequence != null) selected.put(key, consequence);
            }
        }
        VariantEvidenceProvider rawProvider = new VariantEvidenceProvider() {
            @Override
            public String source() {
                return provider.source();
            }

            @Override
            public String version() {
                return binding.resourceVersion;
            }

            @Override
            public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
                    List<VariantInterpretation> values,
                    AnalysisContext context) {
                return provider.annotateRawBatch(
                        values, context, selected, runner, binding.resourceVersion);
            }
        };
        return annotateVariantProviderBatch(
                evidenceContexts, rawProvider, variants, analysisContext, binding);
    }

    private Map<NormalizedVariantKey, List<EvidenceItem>> annotateSelectedAlphaMissense(
            VariantEvidenceProvider provider,
            List<VariantInterpretation> variants,
            AnalysisContext analysisContext,
            Map<NormalizedVariantKey, JsonNode> evidenceContexts) {
        if (!(provider instanceof AlphaMissenseProvider)) {
            throw invalid("AlphaMissense resource binding requires AlphaMissenseProvider");
        }
        Map<NormalizedVariantKey, TranscriptConsequence> selected =
                new LinkedHashMap<NormalizedVariantKey, TranscriptConsequence>();
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            TranscriptConsequence consequence = selectedVepConsequence(
                    evidenceContexts.get(key), variant);
            if (consequence != null) selected.put(key, consequence);
        }
        return ((AlphaMissenseProvider) provider).annotateSelectedTranscripts(
                new ArrayList<VariantInterpretation>(variants), analysisContext, selected);
    }

    // Shared with the explicit dispatcher; no transcript fallback or version stripping.
    TranscriptConsequence selectedVepConsequence(JsonNode evidenceContext,
                                                          VariantInterpretation variant) {
        String accession = requiredText(evidenceContext.path("transcript"), "accession");
        int version = requiredPositiveInteger(evidenceContext.path("transcript"), "version");
        String expectedTranscript = accession + "." + version;
        String expectedGene = requiredText(evidenceContext.path("gene"), "symbol");
        String expectedAnnotationVersion = requiredText(
                evidenceContext.path("transcript"), "annotationSourceVersion");
        List<TranscriptConsequence> matches = new ArrayList<TranscriptConsequence>();
        addSelectedVepMatches(matches, variant.getTranscriptAnnotations(),
                expectedTranscript, expectedGene, expectedAnnotationVersion);
        addSelectedVepMatches(matches, variant.getConsequences(),
                expectedTranscript, expectedGene, expectedAnnotationVersion);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private void addSelectedVepMatches(List<TranscriptConsequence> matches,
                                       List<TranscriptConsequence> candidates,
                                       String expectedTranscript,
                                       String expectedGene,
                                       String expectedAnnotationVersion) {
        if (candidates == null) return;
        for (TranscriptConsequence candidate : candidates) {
            if (candidate == null
                    || candidate.getSource() == null
                    || !candidate.getSource().startsWith("VEP")
                    || !"COMPLETED".equals(candidate.getStatus())
                    || !expectedTranscript.equals(candidate.getVersionedTranscriptId())
                    || !expectedGene.equals(candidate.getGeneSymbol())
                    || !expectedAnnotationVersion.equals(candidate.getVersion())) {
                continue;
            }
            matches.add(candidate);
        }
    }

    /**
     * Runs one bounded Hail process for a variant batch and emits separate exome/genome
     * all-sites atomic evidence for every supplied context.
     */
    public Map<NormalizedVariantKey, List<ObjectNode>> annotateGnomadAllSitesBatch(
            Map<NormalizedVariantKey, JsonNode> evidenceContexts,
            List<VariantInterpretation> variants,
            GnomadAllSitesHailBatchRunner runner,
            GnomadPopulationInputProfile profile) {
        if (evidenceContexts == null || variants == null || variants.isEmpty()
                || runner == null || profile == null) {
            throw invalid("all-sites contexts, variants, runner and profile are required");
        }
        ObjectNode profileDocument = profile.getDocument();
        ResourceBinding exomeBinding = bind(profileDocument,
                GnomadPopulationInputProfile.EXOME_ALL_SITES_RESOURCE_ID);
        ResourceBinding genomeBinding = bind(profileDocument,
                GnomadPopulationInputProfile.GENOME_ALL_SITES_RESOURCE_ID);
        Map<String, BatchVariant> byLocusId = new LinkedHashMap<String, BatchVariant>();
        ArrayNode loci = mapper.createArrayNode();
        Set<NormalizedVariantKey> keys = new LinkedHashSet<NormalizedVariantKey>();
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = validateInputs(
                    evidenceContexts.get(variant == null || variant.getObservation() == null
                            ? null : variant.getObservation().getKey()), variant, exomeBinding);
            if (!keys.add(key)) throw invalid("all-sites batch contains a duplicate variant key");
            JsonNode context = evidenceContexts.get(key);
            ObjectNode locusProjection = mapper.createObjectNode();
            locusProjection.set("allele", allele(key));
            String locusId = "locus-sha256-" + adapter.canonicalSha256(locusProjection);
            ObjectNode locus = loci.addObject();
            locus.put("locusId", locusId);
            locus.put("canonicalContig", key.getChromosome());
            locus.put("position", key.getPosition());
            byLocusId.put(locusId, new BatchVariant(key, context));
        }
        ObjectNode requestProjection = mapper.createObjectNode();
        requestProjection.put("profileId", profile.getProfileId());
        requestProjection.put("profileVersion", profile.getProfileVersion());
        requestProjection.put("profileSha256", profile.getProfileSha256());
        requestProjection.set("loci", loci.deepCopy());
        ObjectNode request = mapper.createObjectNode();
        request.put("requestSchemaVersion", GnomadAllSitesHailBatchRunner.REQUEST_SCHEMA_VERSION);
        request.put("requestId", "hail-batch-sha256-"
                + adapter.canonicalSha256(requestProjection));
        request.put("assembly", "GRCh38");
        request.set("loci", loci);

        Instant attemptedAt = clock.instant();
        GnomadAllSitesHailBatchRunner.BatchResult batch = runner.run(request);
        Instant completedAt = clock.instant();
        if (!"SUCCEEDED".equals(batch.getStatus()) || batch.getResult() == null) {
            return allSitesBatchFailure(byLocusId, exomeBinding, genomeBinding,
                    attemptedAt, completedAt, batch.getStatus(), batch.getReasonCode());
        }
        try {
            return allSitesBatchEvidence(byLocusId, batch.getResult(), profile,
                    exomeBinding, genomeBinding, attemptedAt, completedAt);
        } catch (RuntimeException invalidPayload) {
            return allSitesBatchFailure(byLocusId, exomeBinding, genomeBinding,
                    attemptedAt, completedAt, "FAILED", "HAIL_BATCH_RESULT_INVALID");
        }
    }

    private Map<NormalizedVariantKey, List<ObjectNode>> allSitesBatchEvidence(
            Map<String, BatchVariant> variants,
            ObjectNode result,
            GnomadPopulationInputProfile profile,
            ResourceBinding exomeBinding,
            ResourceBinding genomeBinding,
            Instant attemptedAt,
            Instant completedAt) {
        JsonNode resultProfile = result.path("profile");
        JsonNode execution = result.path("execution");
        JsonNode boundary = result.path("boundary");
        if (!profile.getProfileId().equals(resultProfile.path("profileId").asText())
                || !profile.getProfileVersion().equals(resultProfile.path("profileVersion").asText())
                || !profile.getProfileSha256().equals(resultProfile.path("profileSha256").asText())
                || !"HAIL_FILTER_INTERVALS_BATCH_LOCI".equals(
                execution.path("queryMode").asText())
                || execution.path("requestedLocusCount").asInt(-1) != variants.size()
                || execution.path("uniqueLocusCount").asInt(-1) < 1
                || execution.path("uniqueLocusCount").asInt(-1) > variants.size()
                || execution.path("hailInitializationCount").asInt(-1) != 1
                || execution.path("resourceQueryCount").asInt(-1) != 2
                || boundary.path("patientDataUsed").asBoolean(true)
                || boundary.path("clinicalRuleEvaluationRun").asBoolean(true)
                || boundary.path("machinePathsIncluded").asBoolean(true)) {
            throw invalid("Hail batch profile, execution or boundary is invalid");
        }
        JsonNode resources = result.path("resources");
        if (!resources.isArray() || resources.size() != 2) {
            throw invalid("Hail batch must contain exactly two all-sites resources");
        }
        Map<String, JsonNode> resourcesById = new LinkedHashMap<String, JsonNode>();
        for (JsonNode resource : resources) {
            String id = requiredText(resource, "resourceId");
            if (resourcesById.put(id, resource) != null) {
                throw invalid("Hail batch resource is duplicated");
            }
        }
        JsonNode exomes = validatedAllSitesResource(
                resourcesById.get(exomeBinding.resourceId), exomeBinding, profile, variants);
        JsonNode genomes = validatedAllSitesResource(
                resourcesById.get(genomeBinding.resourceId), genomeBinding, profile, variants);

        Map<NormalizedVariantKey, List<ObjectNode>> evidence =
                new LinkedHashMap<NormalizedVariantKey, List<ObjectNode>>();
        for (Map.Entry<String, BatchVariant> entry : variants.entrySet()) {
            ObjectNode exome = allSitesAtomicEvidence(entry.getValue(), entry.getKey(),
                    exomes, exomeBinding, result, profile, attemptedAt, completedAt, true);
            ObjectNode genome = allSitesAtomicEvidence(entry.getValue(), entry.getKey(),
                    genomes, genomeBinding, result, profile, attemptedAt, completedAt, false);
            evidence.put(entry.getValue().key(), List.of(exome, genome));
        }
        return evidence;
    }

    private JsonNode validatedAllSitesResource(JsonNode resource,
                                               ResourceBinding binding,
                                               GnomadPopulationInputProfile profile,
                                               Map<String, BatchVariant> variants) {
        if (resource == null || !resource.isObject()
                || !binding.resourceVersion.equals(resource.path("resourceVersion").asText())
                || !binding.assembly.equals(resource.path("assembly").asText())
                || !binding.identityFingerprintSha256.equals(
                resource.path("identityFingerprintSha256").asText())) {
            throw invalid("Hail batch resource identity is invalid");
        }
        JsonNode declared = findResource(profile.getDocument(), binding.resourceId);
        JsonNode declaredSelector = declared.path("identity")
                .path("strataResolution").path("selector");
        JsonNode strata = resource.path("strata");
        if (!declared.path("identity").path("tableUri").asText()
                .equals(resource.path("officialTableUri").asText())
                || !declaredSelector.isObject()
                || !declaredSelector.equals(strata.path("selector"))
                || !declaredSelector.equals(strata.path("selectedMetadata"))
                || !strata.path("selectedIndex").isIntegralNumber()
                || !strata.path("selectedIndex").canConvertToInt()
                || strata.path("selectedIndex").asInt(-1) < 0
                || !resource.path("loci").isArray()
                || resource.path("loci").size() != variants.size()) {
            throw invalid("Hail batch resource provenance is invalid");
        }
        Set<String> locusIds = new LinkedHashSet<String>();
        for (JsonNode locus : resource.path("loci")) {
            String locusId = requiredText(locus, "locusId");
            BatchVariant variant = variants.get(locusId);
            JsonNode query = locus.path("queryLocus");
            if (variant == null || !locusIds.add(locusId)
                    || !"GRCh38".equals(query.path("assembly").asText())
                    || !variant.key().getChromosome().equals(
                    query.path("canonicalContig").asText())
                    || !("chr" + variant.key().getChromosome()).equals(
                    query.path("resourceContig").asText())
                    || variant.key().getPosition() != query.path("position").asInt(-1)) {
                throw invalid("Hail batch locus mapping is invalid");
            }
        }
        if (!locusIds.equals(variants.keySet())) {
            throw invalid("Hail batch locus set is incomplete");
        }
        return resource;
    }

    private ObjectNode allSitesAtomicEvidence(BatchVariant variant,
                                              String locusId,
                                              JsonNode resourceResult,
                                              ResourceBinding binding,
                                              ObjectNode batchResult,
                                              GnomadPopulationInputProfile profile,
                                              Instant attemptedAt,
                                              Instant completedAt,
                                              boolean exomes) {
        JsonNode locus = findByText(resourceResult.path("loci"), "locusId", locusId);
        JsonNode axesValue = locus.path("axes");
        String executionStatus = axesValue.path("resourceExecution").path("status").asText();
        String availabilityStatus = axesValue.path("resourceAvailability").path("status").asText();
        String queryStatus = axesValue.path("queryOutcome").path("status").asText();
        int recordCount = axesValue.path("queryOutcome").path("recordCount").asInt(-1);
        String applicabilityStatus = axesValue.path("resourceApplicability").path("status").asText();
        String queryFingerprint = axesValue.path("queryOutcome")
                .path("queryFingerprint").asText();
        if (!"SUCCEEDED".equals(executionStatus)
                || !"AVAILABLE".equals(availabilityStatus)
                || !Set.of("HIT", "NO_RECORD").contains(queryStatus)
                || !("HIT".equals(queryStatus) ? recordCount == 1 : recordCount == 0)
                || !"APPLICABLE".equals(applicabilityStatus)
                || !queryFingerprint.matches("^[0-9a-f]{64}$")) {
            throw invalid("Hail all-sites four-axis result is invalid");
        }
        ArrayNode measurements = mapper.createArrayNode();
        if ("HIT".equals(queryStatus)) {
            JsonNode value = locus.path("measurement");
            if (!value.isObject() || !value.path("adjAlleleNumber").isIntegralNumber()
                    || value.path("adjAlleleNumber").bigIntegerValue().signum() < 0
                    || !value.path("adjStrataIndex").isIntegralNumber()
                    || !value.path("adjStrataIndex").canConvertToInt()
                    || value.path("adjStrataIndex").asInt(-1)
                    != resourceResult.path("strata").path("selectedIndex").asInt(-1)) {
                throw invalid("Hail all-sites HIT measurement is invalid");
            }
            String prefix = exomes ? "gnomad.exomes.allSites" : "gnomad.genomes.allSites";
            calibratedMeasurement(measurements, prefix + ".adjAn",
                    value.path("adjAlleleNumber").bigIntegerValue().toString(), "alleles", profile);
            calibratedMeasurement(measurements, prefix + ".adjStrataIndex",
                    value.path("adjStrataIndex").asText(), "index", profile);
            calibratedMeasurement(measurements, prefix + ".selectedSampleCount",
                    resourceResult.path("strata").path("selectedSampleCount").asText(),
                    "samples", profile);
            if (exomes) {
                JsonNode flags = value.path("regionFlags");
                if (!flags.isObject()) {
                    throw invalid("Hail all-sites exome region flags are invalid");
                }
                for (String flag : List.of(
                        "outside_broad_capture_region", "outside_ukb_capture_region",
                        "outside_broad_calling_region", "outside_ukb_calling_region")) {
                    JsonNode flagValue = flags.get(flag);
                    if (flagValue != null && !flagValue.isNull() && !flagValue.isBoolean()) {
                        throw invalid("Hail all-sites exome region flag is invalid: " + flag);
                    }
                    Object raw = flagValue == null || flagValue.isNull()
                            ? null : flagValue.booleanValue();
                    calibratedMeasurement(measurements,
                            "gnomad.exomes.allSites.regionFlag." + flag,
                            raw, "boolean", profile);
                }
            }
            calibratedMeasurement(measurements, "gnomad.allSites.hailVersion",
                    batchResult.path("tool").path("hailVersion").asText(), "version", profile);
            calibratedMeasurement(measurements, "gnomad.allSites.queryMode",
                    batchResult.path("execution").path("queryMode").asText(), "mode", profile);
        } else if (locus.has("measurement")) {
            throw invalid("Hail all-sites NO_RECORD cannot contain measurements");
        }
        Axes axes = new Axes("SUCCEEDED", null, "AVAILABLE", null,
                queryStatus, "APPLICABLE", null);
        String recordId = "HIT".equals(queryStatus)
                ? binding.resourceId + ":GRCh38:" + variant.key().getChromosome()
                + ":" + variant.key().getPosition() : null;
        ObjectNode payload = mapper.createObjectNode();
        payload.set("profile", batchResult.path("profile").deepCopy());
        payload.putObject("tool")
                .put("hailVersion", batchResult.path("tool").path("hailVersion").asText());
        payload.put("queryMode", batchResult.path("execution").path("queryMode").asText());
        ObjectNode resourcePayload = payload.putObject("resource");
        for (String field : List.of("resourceId", "resourceVersion", "assembly",
                "identityFingerprintSha256", "officialTableUri")) {
            resourcePayload.set(field, resourceResult.path(field).deepCopy());
        }
        resourcePayload.set("strata", resourceResult.path("strata").deepCopy());
        payload.set("locus", locus.deepCopy());
        ObjectNode carrier = carrier(binding, variant.key(), "POPULATION_FREQUENCY",
                axes, recordId, measurements, attemptedAt, completedAt,
                adapter.canonicalSha256(payload));
        ((ObjectNode) carrier.path("queryOutcome")).put("queryFingerprint", queryFingerprint);
        setAllSitesLocators(carrier, binding, variant.key(), recordId, completedAt.toString());
        return adapter.adapt(variant.context(), carrier);
    }

    private void calibratedMeasurement(ArrayNode values,
                                       String metricId,
                                       Object rawValue,
                                       String unit,
                                       GnomadPopulationInputProfile profile) {
        ObjectNode value = values.addObject();
        value.put("metricId", metricId);
        if (rawValue == null) {
            value.putNull("rawValue");
            value.putNull("normalizedValue");
        } else if (rawValue instanceof Boolean) {
            value.put("rawValue", (Boolean) rawValue);
            value.put("normalizedValue", (Boolean) rawValue);
        } else {
            value.put("rawValue", rawValue.toString());
            value.put("normalizedValue", rawValue.toString());
        }
        value.put("unit", unit);
        value.put("calibrationProfileId",
                profile.getProfileId() + "@" + profile.getProfileVersion());
    }

    private void setAllSitesLocators(ObjectNode carrier,
                                     ResourceBinding binding,
                                     NormalizedVariantKey key,
                                     String recordId,
                                     String retrievedAt) {
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "GENOMIC_REGION");
        locator.put("locatorValue", binding.resourceId + ":GRCh38:"
                + key.getChromosome() + ":" + key.getPosition());
        locator.put("artifactSha256", binding.identityFingerprintSha256);
        locator.put("retrievedAt", retrievedAt);
        if (recordId != null) locator.put("sourceRecordId", recordId);
        ArrayNode locators = mapper.createArrayNode().add(locator.deepCopy());
        ((ObjectNode) carrier.path("source")).set("sourceLocators", locators);
        if (recordId != null) {
            ObjectNode record = (ObjectNode) carrier.path("source")
                    .path("sourceRecords").get(0);
            record.set("locator", locator.deepCopy());
        }
    }

    private Map<NormalizedVariantKey, List<ObjectNode>> allSitesBatchFailure(
            Map<String, BatchVariant> variants,
            ResourceBinding exomeBinding,
            ResourceBinding genomeBinding,
            Instant attemptedAt,
            Instant completedAt,
            String status,
            String reasonCode) {
        String executionStatus = "TIMEOUT".equals(status) ? "TIMEOUT" : "FAILED";
        String reason = reasonCode == null
                ? ("TIMEOUT".equals(status) ? "HAIL_BATCH_TIMEOUT" : "HAIL_BATCH_EXECUTION_FAILED")
                : reasonCode;
        Map<NormalizedVariantKey, List<ObjectNode>> evidence =
                new LinkedHashMap<NormalizedVariantKey, List<ObjectNode>>();
        for (BatchVariant variant : variants.values()) {
            List<ObjectNode> values = new ArrayList<ObjectNode>();
            for (ResourceBinding binding : List.of(exomeBinding, genomeBinding)) {
                Axes axes = new Axes(executionStatus, reason, "UNKNOWN", "NOT_CHECKED",
                        "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED");
                ObjectNode payload = mapper.createObjectNode();
                payload.put("resourceId", binding.resourceId);
                payload.put("failureCode", reason);
                ObjectNode carrier = carrier(binding, variant.key(), "POPULATION_FREQUENCY",
                        axes, null, mapper.createArrayNode(), attemptedAt, completedAt,
                        adapter.canonicalSha256(payload));
                values.add(adapter.adapt(variant.context(), carrier));
            }
            evidence.put(variant.key(), Collections.unmodifiableList(values));
        }
        return evidence;
    }

    private JsonNode findResource(JsonNode profile, String resourceId) {
        return findByText(profile.path("resources"), "resourceId", resourceId);
    }

    private JsonNode findByText(JsonNode values, String field, String expected) {
        JsonNode found = null;
        if (values.isArray()) {
            for (JsonNode value : values) {
                if (expected.equals(value.path(field).asText())) {
                    if (found != null) throw invalid(field + " is duplicated: " + expected);
                    found = value;
                }
            }
        }
        if (found == null) throw invalid(field + " is missing: " + expected);
        return found;
    }

    /** Projects an execution-snapshot annotation; this does not invoke or claim a fresh VEP run. */
    ObjectNode projectStoredTranscript(JsonNode context, VariantInterpretation variant,
                                       TranscriptConsequence annotation, ResourceBinding binding) {
        var key = validateInputs(context, variant, binding);
        if (!binding.providerVersion.equals(annotation.getVersion()) || annotation.getTimestamp() == null
                || selectedVepConsequence(context, variant) != annotation) throw invalid("stored VEP association invalid");
        return adapter.adapt(context, transcriptCarrier(annotation, binding, key,
                annotation.getTimestamp(), annotation.getTimestamp()));
    }

    /** Runs VEP's transcript provider and converts its compatibility payload. */
    public List<ObjectNode> annotateTranscriptProvider(JsonNode evidenceContext,
                                                       TranscriptAnnotationProvider provider,
                                                       VariantInterpretation variant,
                                                       AnalysisContext analysisContext,
                                                       ResourceBinding binding) {
        NormalizedVariantKey key = validateInputs(evidenceContext, variant, binding);
        validateAnalysisContext(analysisContext, key);
        validateProviderFamily(provider.source(), binding);
        if (!binding.providerVersion.equals(provider.version())) {
            throw invalid("provider version does not match the resource binding");
        }
        Instant attemptedAt = clock.instant();
        List<TranscriptConsequence> consequences;
        try {
            consequences = provider.annotate(variant, analysisContext);
        } catch (RuntimeException failure) {
            return failure(evidenceContext, binding, key, "VARIANT_CONSEQUENCE",
                    attemptedAt, clock.instant(), failure, "PROVIDER_RUNTIME_EXCEPTION");
        }
        if (consequences == null || consequences.isEmpty()) {
            return failure(evidenceContext, binding, key, "VARIANT_CONSEQUENCE",
                    attemptedAt, clock.instant(), invalid("provider returned no transcript status"),
                    "PROVIDER_PAYLOAD_INVALID");
        }

        Instant completedAt = clock.instant();
        try {
            List<ObjectNode> evidence = new ArrayList<ObjectNode>();
            String requestedTranscript = requiredText(evidenceContext.path("transcript"), "accession");
            int requestedTranscriptVersion = requiredPositiveInteger(
                    evidenceContext.path("transcript"), "version");
            String requestedGeneSymbol = requiredText(evidenceContext.path("gene"), "symbol");
            boolean completedResultSeen = false;
            for (TranscriptConsequence consequence : consequences) {
                if (consequence == null) throw invalid("provider returned a null transcript consequence");
                if (consequence.getVersion() != null
                        && !binding.providerVersion.equals(consequence.getVersion())) {
                    throw invalid("transcript result version does not match the resource binding");
                }
                if ("COMPLETED".equals(consequence.getStatus())) {
                    completedResultSeen = true;
                    if (!matchesTranscript(
                            consequence, requestedTranscript, requestedTranscriptVersion)) {
                        continue;
                    }
                    if (!requestedGeneSymbol.equals(consequence.getGeneSymbol())) {
                        throw invalid("VEP consequence gene does not match the evidence context");
                    }
                }
                ObjectNode carrier = transcriptCarrier(consequence, binding, key, attemptedAt, completedAt);
                evidence.add(adapter.adapt(evidenceContext, carrier));
            }
            if (evidence.isEmpty() && completedResultSeen) {
                ObjectNode carrier = transcriptNoRecordCarrier(
                        binding, key, requestedTranscript, requestedTranscriptVersion,
                        attemptedAt, completedAt);
                evidence.add(adapter.adapt(evidenceContext, carrier));
            }
            return evidence;
        } catch (RuntimeException failure) {
            return failure(evidenceContext, binding, key, "VARIANT_CONSEQUENCE",
                    attemptedAt, completedAt, failure, "PROVIDER_PAYLOAD_INVALID");
        }
    }

    private ObjectNode evidenceItemCarrier(EvidenceItem item,
                                           ResourceBinding binding,
                                           NormalizedVariantKey key,
                                           Instant attemptedAt,
                                           Instant completedAt) {
        Axes axes = axes(item.getDetails());
        String recordId = "HIT".equals(axes.query)
                ? recordId(first(item.getSourceRecordId(), item.getId()), binding.provider, key, null)
                : null;
        String declaredPopulationResourceId = "GNOMAD".equals(binding.provider)
                ? optionalDetail(item.getDetails(), "populationResourceId")
                : null;
        boolean profileBoundPopulationResult = (GnomadPopulationInputProfile.FULL_SITES_RESOURCE_ID
                .equals(binding.resourceId)
                || GnomadPopulationInputProfile.JOINT_RESOURCE_ID.equals(binding.resourceId))
                && ("HIT".equals(axes.query) || "NO_RECORD".equals(axes.query));
        if (profileBoundPopulationResult && declaredPopulationResourceId == null) {
            throw invalid("gnomAD profile-bound result is missing its population resource identity");
        }
        if (declaredPopulationResourceId != null
                && !binding.resourceId.equals(declaredPopulationResourceId)) {
            throw invalid("gnomAD population input resource does not match the binding");
        }
        if (profileBoundPopulationResult) {
            requireDetail(item.getDetails(), "populationInputProfileId");
            requireDetail(item.getDetails(), "populationInputProfileVersion");
            requireDetail(item.getDetails(), "populationInputProfileSha256");
            String declaredFingerprint = requireDetail(
                    item.getDetails(), "populationResourceIdentityFingerprintSha256");
            if (!binding.identityFingerprintSha256.equals(declaredFingerprint)) {
                throw invalid("gnomAD population resource identity fingerprint does not match the binding");
            }
        }
        ArrayNode measurements = mapper.createArrayNode();
        if ("HIT".equals(axes.query)) {
            if ("GNOMAD".equals(binding.provider)) {
                if (item.getDetails().get("populationMeasurements") != null) {
                    addPopulationMeasurements(
                            measurements, item.getDetails().get("populationMeasurements"));
                } else if (item.getRawValue() != null) {
                    measurement(measurements, "maximumAlleleFrequency", item.getRawValue(), "fraction");
                    String readiness = optionalDetail(
                            item.getDetails(), "populationInputReadinessStatus");
                    if (readiness != null) {
                        measurement(measurements,
                                "gnomad.recordInputReadinessStatus", readiness, null);
                    }
                    String reasons = joinedDetail(
                            item.getDetails().get("populationInputReadinessReasonCodes"));
                    if (reasons != null) {
                        measurement(measurements,
                                "gnomad.recordInputReadinessReasonCodes", reasons, null);
                    }
                }
            } else if ("CLINVAR".equals(binding.provider)) {
                addClinVarMeasurements(measurements, item);
            } else if ("ALPHAMISSENSE".equals(binding.provider)) {
                addAlphaMissenseMeasurements(measurements, item);
            } else if ("SPLICEAI".equals(binding.provider)) {
                addSpliceAiMeasurements(measurements, item);
            }
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("inputSchema", item.getSchemaVersion());
        payload.put("provider", binding.provider);
        payload.put("providerStatus", item.getEffectiveStatus());
        if (item.getId() != null) payload.put("stableProviderEvidenceId", item.getId());
        if (recordId != null) payload.put("sourceRecordId", recordId);
        if (item.getRawValue() != null) payload.put("rawValue", item.getRawValue());
        if (!measurements.isEmpty()) payload.set("measurements", measurements.deepCopy());
        String populationProfileId = optionalDetail(item.getDetails(), "populationInputProfileId");
        if (populationProfileId != null) payload.put("populationInputProfileId", populationProfileId);
        String populationProfileSha256 = optionalDetail(
                item.getDetails(), "populationInputProfileSha256");
        if (populationProfileSha256 != null) {
            payload.put("populationInputProfileSha256", populationProfileSha256);
        }
        String populationResourceId = optionalDetail(item.getDetails(), "populationResourceId");
        if (populationResourceId != null) payload.put("populationResourceId", populationResourceId);
        String populationResourceFingerprint = optionalDetail(
                item.getDetails(), "populationResourceIdentityFingerprintSha256");
        if (populationResourceFingerprint != null) {
            payload.put("populationResourceIdentityFingerprintSha256",
                    populationResourceFingerprint);
        }
        return carrier(binding, key, evidenceType(binding), axes, recordId, measurements,
                attemptedAt, completedAt, adapter.canonicalSha256(payload));
    }

    private ObjectNode comparatorAssertionCarrier(
            NormalizedVariantKey target,
            ClinVarConditionAwareComparatorInput.ComparatorRecord comparator,
            JsonNode assertion,
            int assertionIndex,
            ComparatorProjectionBindings bindings,
            Instant attemptedAt,
            Instant completedAt,
            JsonNode profile) {
        ArrayNode measurements = mapper.createArrayNode();
        NormalizedVariantKey observed = comparator.getExactAllele();
        comparatorMeasurement(measurements, "clinvar.comparator.assembly",
                observed == null ? null : observed.getGenomeAssembly());
        comparatorMeasurement(measurements, "clinvar.comparator.contig",
                observed == null ? null : observed.getChromosome());
        comparatorMeasurement(measurements, "clinvar.comparator.position",
                observed == null ? null : String.valueOf(observed.getPosition()));
        comparatorMeasurement(measurements, "clinvar.comparator.reference",
                observed == null ? null : observed.getReference());
        comparatorMeasurement(measurements, "clinvar.comparator.alternate",
                observed == null ? null : observed.getAlternate());
        comparatorMeasurement(measurements, "clinvar.comparator.variationId",
                comparator.getVariationId());
        comparatorMeasurement(measurements, "clinvar.comparator.recordConflictState",
                comparator.getConflictState());
        comparatorMeasurement(measurements, "clinvar.comparator.geneSymbols",
                comparator.getGeneSymbols().isEmpty()
                        ? null : String.join("|", comparator.getGeneSymbols()));

        VepProteinRelationClassifier.ProteinChange targetProtein =
                comparator.getTargetProteinChange();
        VepProteinRelationClassifier.ProteinChange comparatorProtein =
                comparator.getComparatorProteinChange();
        comparatorMeasurement(measurements, "vep.target.consequence",
                comparator.getSelectedTargetConsequence());
        proteinMeasurements(measurements, "vep.target", targetProtein);
        comparatorMeasurement(measurements, "vep.comparator.consequence",
                comparator.getSelectedComparatorConsequence());
        proteinMeasurements(measurements, "vep.comparator", comparatorProtein);
        comparatorMeasurement(measurements, "vep.proteinRelation",
                comparator.getProteinRelation());

        comparatorMeasurement(measurements, "clinvar.assertion.scv",
                scalarText(assertion, "scv"));
        comparatorMeasurement(measurements, "clinvar.assertion.classification",
                scalarText(assertion, "classification"));
        comparatorMeasurement(measurements, "clinvar.assertion.reviewStatus",
                scalarText(assertion, "reviewStatus"));
        comparatorMeasurement(measurements, "clinvar.assertion.dateLastEvaluated",
                scalarText(assertion, "dateLastEvaluated"));
        comparatorMeasurement(measurements, "clinvar.assertion.geneSymbol",
                scalarText(assertion, "geneSymbol"));
        JsonNode condition = assertion.path("condition");
        comparatorMeasurement(measurements, "clinvar.assertion.condition.submittedRaw",
                scalarText(condition, "submittedRaw"));
        comparatorMeasurement(measurements, "clinvar.assertion.condition.reportedRaw",
                scalarText(condition, "reportedRaw"));
        JsonNode sourceLocator = assertion.path("sourceLocator");
        comparatorMeasurement(measurements, "clinvar.assertion.source.logicalName",
                scalarText(sourceLocator, "logicalName"));
        comparatorMeasurement(measurements, "clinvar.assertion.source.line",
                scalarValue(sourceLocator, "line"));

        resourceMeasurements(measurements, "resource.clinvarVcf", bindings.clinVarVcf);
        resourceMeasurements(measurements, "resource.vep", bindings.vep);
        resourceMeasurements(measurements, "resource.sidecar", bindings.sidecar);

        String scv = scalarText(assertion, "scv");
        String recordId = scv == null
                ? "VariationID:" + comparator.getVariationId() + ":submission:"
                + assertionIndex + ":" + adapter.canonicalSha256(assertion).substring(0, 16)
                : scv;
        Axes axes = new Axes("SUCCEEDED", null, "AVAILABLE", null,
                "HIT", "APPLICABLE", null);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("inputSchema", comparator.getSchemaVersion());
        payload.put("profileSha256", adapter.canonicalSha256(profile));
        payload.put("variationId", comparator.getVariationId());
        payload.set("assertion", assertion.deepCopy());
        payload.set("measurements", measurements.deepCopy());
        ObjectNode carrier = carrier(bindings.sidecar, target, "CLINICAL_DATABASE",
                axes, recordId, measurements, attemptedAt, completedAt,
                adapter.canonicalSha256(payload));
        setComparatorResourceLocators(carrier, bindings, completedAt);
        ((ObjectNode) carrier.path("source").path("sourceRecords").path(0))
                .set("locator", assertionRecordLocator(
                recordId, comparator, assertion, completedAt));
        return carrier;
    }

    private ObjectNode comparatorNotEvaluatedCarrier(
            NormalizedVariantKey target,
            ClinVarConditionAwareComparatorInput.Result result,
            ComparatorProjectionBindings bindings,
            Instant attemptedAt,
            Instant completedAt,
            JsonNode profile) {
        ClinVarConditionAwareComparatorInput.DiscoverySummary discovery = result.getDiscovery();
        String reason = first(result.getReasonCode(), "CLINVAR_COMPARATOR_INPUT_NOT_EVALUATED");
        String execution = discovery == null
                ? "NOT_RUN" : first(discovery.getResourceExecutionStatus(), "NOT_RUN");
        String availability = discovery == null
                ? "UNKNOWN" : first(discovery.getResourceAvailabilityStatus(), "UNKNOWN");
        String query = discovery == null
                ? "NOT_QUERIED" : first(discovery.getQueryOutcomeStatus(), "NOT_QUERIED");
        String applicability = discovery == null
                ? "UNKNOWN" : first(discovery.getResourceApplicabilityStatus(), "UNKNOWN");

        // A provider failure cannot carry a successful query under atomic-evidence-v2.
        if (!"SUCCEEDED".equals(execution)) {
            query = "NOT_QUERIED";
            if ("FAILED".equals(execution) || "TIMEOUT".equals(execution)
                    || "DISABLED".equals(execution)) {
                applicability = "UNKNOWN";
            }
        }
        String executionReason = "SUCCEEDED".equals(execution) ? null : reason;
        String availabilityReason = "AVAILABLE".equals(availability) ? null : reason;
        String applicabilityReason = "APPLICABLE".equals(applicability) ? null : reason;
        Axes axes = new Axes(execution, executionReason, availability,
                availabilityReason, query, applicability, applicabilityReason);

        ArrayNode measurements = mapper.createArrayNode();
        String recordId = null;
        if ("HIT".equals(query)) {
            recordId = "K1A_INPUT:" + reason;
            comparatorMeasurement(measurements,
                    "clinvar.comparator.inputReasonCode", reason);
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("inputSchema", result.getSchemaVersion());
        payload.put("inputStatus", result.getStatus());
        payload.put("reasonCode", reason);
        payload.put("profileSha256", adapter.canonicalSha256(profile));
        ObjectNode carrier = carrier(bindings.sidecar, target, "CLINICAL_DATABASE",
                axes, recordId, measurements, attemptedAt, completedAt,
                adapter.canonicalSha256(payload));
        setComparatorResourceLocators(carrier, bindings, completedAt);
        return carrier;
    }

    private void proteinMeasurements(ArrayNode measurements,
                                     String prefix,
                                     VepProteinRelationClassifier.ProteinChange protein) {
        comparatorMeasurement(measurements, prefix + ".transcriptId",
                protein == null ? null : protein.getTranscriptId());
        comparatorMeasurement(measurements, prefix + ".proteinPosition",
                protein == null ? null : protein.getProteinPosition());
        comparatorMeasurement(measurements, prefix + ".referenceAminoAcid",
                protein == null ? null : protein.getReferenceAminoAcid());
        comparatorMeasurement(measurements, prefix + ".alternateAminoAcid",
                protein == null ? null : protein.getAlternateAminoAcid());
    }

    private void resourceMeasurements(ArrayNode measurements,
                                      String prefix,
                                      ResourceBinding binding) {
        comparatorMeasurement(measurements, prefix + ".id", binding.resourceId);
        comparatorMeasurement(measurements, prefix + ".version", binding.resourceVersion);
        comparatorMeasurement(measurements, prefix + ".identityFingerprintSha256",
                binding.identityFingerprintSha256);
    }

    private void comparatorMeasurement(ArrayNode measurements,
                                       String metricId,
                                       String rawValue) {
        ObjectNode measurement = measurements.addObject();
        measurement.put("metricId", metricId);
        if (rawValue == null) measurement.putNull("rawValue");
        else measurement.put("rawValue", rawValue);
    }

    private String scalarText(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value != null && value.isTextual() && !value.asText().trim().isEmpty()
                ? value.asText() : null;
    }

    private String scalarValue(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value != null && (value.isTextual() || value.isIntegralNumber()
                || value.isBoolean()) ? value.asText() : null;
    }

    private ObjectNode assertionRecordLocator(
            String recordId,
            ClinVarConditionAwareComparatorInput.ComparatorRecord comparator,
            JsonNode assertion,
            Instant completedAt) {
        String logicalName = scalarText(assertion.path("sourceLocator"), "logicalName");
        String line = scalarValue(assertion.path("sourceLocator"), "line");
        String locatorValue = logicalName != null && line != null
                ? logicalName + "#line=" + line
                : "condition-aware-sidecar:VariationID:" + comparator.getVariationId();
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "DATABASE_RECORD");
        locator.put("locatorValue", locatorValue);
        locator.put("sourceRecordId", recordId);
        locator.put("retrievedAt", completedAt.toString());
        return locator;
    }

    private void setComparatorResourceLocators(ObjectNode carrier,
                                               ComparatorProjectionBindings bindings,
                                               Instant completedAt) {
        ArrayNode locators = mapper.createArrayNode();
        locators.add(comparatorResourceLocator(bindings.clinVarVcf, completedAt));
        locators.add(comparatorResourceLocator(bindings.vep, completedAt));
        locators.add(comparatorResourceLocator(bindings.sidecar, completedAt));
        ((ObjectNode) carrier.path("source")).set("sourceLocators", locators);
    }

    private ObjectNode comparatorResourceLocator(ResourceBinding binding,
                                                 Instant completedAt) {
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "REPOSITORY_ARTIFACT");
        locator.put("locatorValue", "resource-identity:" + binding.resourceId
                + "@" + binding.assembly);
        locator.put("artifactSha256", binding.identityFingerprintSha256);
        locator.put("retrievedAt", completedAt.toString());
        return locator;
    }

    private ComparatorProjectionBindings comparatorProjectionBindings(JsonNode profile) {
        if (!"clinvar-missense-ps1-pm5-profile-v1".equals(
                profile.path("profileSchemaVersion").asText())
                || !profile.path("resources").isArray()) {
            throw invalid("ClinVar comparator executable profile is invalid");
        }
        ResourceBinding clinVar = comparatorResource(
                profile.path("resources"), CLINVAR_COMPARATOR_DISCOVERY_ROLE, "CLINVAR");
        ResourceBinding vep = comparatorResource(
                profile.path("resources"), CLINVAR_COMPARATOR_VEP_ROLE, "VEP");
        ResourceBinding sidecar = comparatorResource(
                profile.path("resources"), CLINVAR_COMPARATOR_SIDECAR_ROLE, "CLINVAR");
        return new ComparatorProjectionBindings(clinVar, vep, sidecar);
    }

    private ResourceBinding comparatorResource(JsonNode resources,
                                               String role,
                                               String provider) {
        JsonNode match = null;
        for (JsonNode resource : resources) {
            if (role.equals(resource.path("role").asText())) {
                if (match != null) throw invalid("comparator profile resource role is duplicated");
                match = resource;
            }
        }
        if (match == null) throw invalid("comparator profile resource role is missing: " + role);
        String assembly = requiredText(match, "assembly");
        String fingerprint = requiredText(match, "identityFingerprintSha256");
        if (!"GRCh38".equals(assembly) || !fingerprint.matches("[0-9a-f]{64}")) {
            throw invalid("comparator profile resource identity is invalid: " + role);
        }
        String version = requiredText(match, "resourceVersion");
        return new ResourceBinding(requiredText(match, "resourceId"), provider,
                version, assembly, version, fingerprint);
    }

    /** Reparse a verified observation under another pinned profile. No provider call or result copying.
     * The caller supplies the verified execution-source identity; saved runs must pass store integrity first.
     */
    public ReparsedPopulation reparsePopulationObservation(JsonNode context, JsonNode observation,
            String executionSourceSha256, GnomadPopulationInputProfile sourceProfile,
            GnomadPopulationInputProfile targetProfile) {
        adapter.validateCompleteContext(context);
        if (executionSourceSha256 == null || !executionSourceSha256.matches("[0-9a-f]{64}")
                || !executionSourceSha256.equals(observation.path("executionSourceSha256").asText())
                || !sourceProfile.getProfileSha256().equals(observation.path("profileSha256").asText())
                || !context.equals(observation.path("context"))) throw invalid("POPULATION_OBSERVATION_BINDING_INVALID");
        JsonNode original = observation.path("evidence");
        JsonNode resource = sourceProfile.getDocument().path("resources").get(0);
        JsonNode targetResource = targetProfile.getDocument().path("resources").get(0);
        if (!resource.path("identity").equals(targetResource.path("identity"))
                || !resource.path("resourceId").equals(original.at("/source/sourceId"))
                || !resource.path("resourceVersion").equals(original.at("/source/resourceVersion"))
                || !resource.path("identityFingerprintSha256").equals(original.at("/source/resourceSha256")))
            throw invalid("POPULATION_OBSERVATION_RESOURCE_MISMATCH");
        // Requiredness can tighten in a successor. Field meaning, cardinality and units cannot drift.
        for (JsonNode targetField : targetProfile.getDocument().at("/fieldContract/fields")) {
            JsonNode found = null;
            for (JsonNode sourceField : sourceProfile.getDocument().at("/fieldContract/fields"))
                if (targetField.path("infoId").equals(sourceField.path("infoId"))) found = sourceField;
            if (found == null) throw invalid("POPULATION_FIELD_CONTRACT_MISMATCH");
            for (String key : List.of("metricId", "infoId", "number", "valueType", "unit"))
                if (!targetField.path(key).equals(found.path(key))) throw invalid("POPULATION_FIELD_CONTRACT_MISMATCH");
        }
        ObjectNode carrier = mapper.createObjectNode();
        for (String field : List.of("evidenceType", "source", "resourceExecution", "resourceAvailability",
                "queryOutcome", "resourceApplicability", "measurements")) carrier.set(field, original.path(field).deepCopy());
        carrier.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        carrier.put("producedAt", original.at("/provenance/producedAt").asText());
        carrier.put("sourcePayloadSha256", original.at("/provenance/sourcePayloadSha256").asText());
        if (!adapter.adapt(context, carrier).equals(original)) throw invalid("POPULATION_OBSERVATION_EVIDENCE_INVALID");
        ObjectNode identity = mapper.createObjectNode().put("schemaVersion", "population-observation-binding-v1")
                .put("executionSourceSha256", executionSourceSha256).put("dataClass", context.path("dataClass").asText())
                .put("sourceProfileSha256", sourceProfile.getProfileSha256())
                .put("sourceEvidenceSha256", adapter.canonicalSha256(original));
        identity.set("variant", context.path("variant").deepCopy());
        identity.set("resource", original.path("source").deepCopy());
        ArrayNode measurements = mapper.createArrayNode();
        if ("HIT".equals(original.at("/queryOutcome/status").asText())) {
            String raw = observation.path("rawPopulationRecord").asText();
            if (raw.isBlank() || raw.length() > 1_048_576 || raw.contains("\n") || raw.contains("\r"))
                throw invalid("POPULATION_RAW_RECORD_REQUIRED");
            String[] columns = raw.split("\t", -1);
            JsonNode allele = context.at("/variant/evidenceQueryAllele");
            if (columns.length < 8 || !columns[0].replaceFirst("^chr", "").equals(allele.path("contig").asText())
                    || !columns[1].equals(allele.path("position").asText()) || !columns[3].equals(allele.path("reference").asText())
                    || original.at("/queryOutcome/recordCount").asInt() != 1) throw invalid("POPULATION_RAW_ALLELE_MISMATCH");
            String[] alts = columns[4].split(",", -1); int index = -1;
            for (int i = 0; i < alts.length; i++) if (alts[i].equals(allele.path("alternate").asText())) {
                if (index >= 0) throw invalid("POPULATION_RAW_ALT_AMBIGUOUS"); index = i;
            }
            if (index < 0) throw invalid("POPULATION_RAW_ALLELE_MISMATCH");
            Map<String, String> info = new LinkedHashMap<>();
            for (String field : columns[7].split(";")) {
                String[] pair = field.split("=", 2);
                if (info.put(pair[0], pair.length == 2 ? pair[1] : "true") != null) throw invalid("POPULATION_RAW_INFO_AMBIGUOUS");
            }
            ArrayNode sourceMeasurements = mapper.createArrayNode();
            addPopulationMeasurements(sourceMeasurements, sourceProfile.parse(columns[6], info, index, alts.length).getMeasurements());
            if (!sourceMeasurements.equals(original.path("measurements"))) throw invalid("POPULATION_RAW_MEASUREMENT_MISMATCH");
            addPopulationMeasurements(measurements, targetProfile.parse(columns[6], info, index, alts.length).getMeasurements());
            try { identity.put("rawRecordSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        } else if (!original.path("measurements").isEmpty()) throw invalid("POPULATION_NONHIT_MEASUREMENTS_INVALID");
        identity.put("observationId", "population-observation-sha256-" + adapter.canonicalSha256(identity));
        ObjectNode payload = mapper.createObjectNode().set("observation", identity);
        payload.put("targetProfileSha256", targetProfile.getProfileSha256());
        payload.set("measurements", measurements);
        carrier.set("measurements", measurements);
        carrier.put("sourcePayloadSha256", adapter.canonicalSha256(payload));
        adapter.validatePopulationCarrier(carrier, context.at("/variant/evidenceQueryAllele"));
        return new ReparsedPopulation(carrier, identity);
    }

    public record ReparsedPopulation(ObjectNode carrier, ObjectNode identity) { }

    private void addPopulationMeasurements(ArrayNode target, Object carrierValue) {
        if (!(carrierValue instanceof List<?>)) {
            throw invalid("gnomAD populationMeasurements must be a list");
        }
        Set<String> metricIds = new LinkedHashSet<String>();
        for (Object item : (List<?>) carrierValue) {
            if (!(item instanceof Map<?, ?>)) {
                throw invalid("gnomAD population measurement must be an object");
            }
            Map<?, ?> measurement = (Map<?, ?>) item;
            String metricId = mapText(measurement, "metricId", true);
            String rawValue = mapText(measurement, "rawValue", true);
            if (!metricIds.add(metricId)) {
                throw invalid("gnomAD population metricId is duplicated: " + metricId);
            }
            ObjectNode value = mapper.createObjectNode();
            value.put("metricId", metricId);
            value.put("rawValue", rawValue);
            String normalizedValue = mapText(measurement, "normalizedValue", false);
            if (normalizedValue != null) value.put("normalizedValue", normalizedValue);
            String unit = mapText(measurement, "unit", false);
            if (unit != null) value.put("unit", unit);
            String calibrationProfileId = mapText(
                    measurement, "calibrationProfileId", false);
            if (calibrationProfileId != null) {
                value.put("calibrationProfileId", calibrationProfileId);
            }
            target.add(value);
        }
    }

    private void addClinVarMeasurements(ArrayNode target, EvidenceItem item) {
        Map<String, Object> details = item.getDetails();
        measurement(target, "clinicalSignificance", item.getRawValue(), null);
        measurement(target, "variationId", optionalDetail(details, "variationId"), null);
        measurement(target, "reviewStatus", optionalDetail(details, "reviewStatus"), null);
        measurement(target, "geneSymbol", optionalDetail(details, "geneSymbol"), null);
        measurement(target, "conflictState", optionalDetail(details, "conflictState"), null);
        measurement(target, "lastEvaluated", optionalDetail(details, "lastEvaluated"), null);

        String contributingSubmissionIdentifiers = joinedInSourceOrder(
                details.get("contributingSubmissionIdentifiers"), "|");
        measurement(target, "contributingSubmissionIdentifiers",
                contributingSubmissionIdentifiers, null);

        Object conditionValue = details.get("conditions");
        if (conditionValue == null) return;
        if (!(conditionValue instanceof List<?>)) {
            throw invalid("ClinVar conditions must be a list");
        }
        int index = 0;
        for (Object value : (List<?>) conditionValue) {
            if (!(value instanceof Map<?, ?>)) {
                throw invalid("ClinVar condition must be an object");
            }
            Map<?, ?> condition = (Map<?, ?>) value;
            measurement(target, "condition." + index + ".name",
                    optionalMapText(condition, "name"), null);
            measurement(target, "condition." + index + ".identifiers",
                    optionalMapText(condition, "id"), null);
            index++;
        }
    }

    private String optionalMapText(Map<?, ?> values, String field) {
        Object value = values.get(field);
        if (value == null || value.toString().trim().isEmpty()) return null;
        return value.toString().trim();
    }

    private String joinedInSourceOrder(Object value, String delimiter) {
        if (value == null) return null;
        if (!(value instanceof List<?>)) {
            throw invalid("ClinVar contributing submission identifiers must be a list");
        }
        List<String> values = new ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (item != null && !item.toString().trim().isEmpty()) {
                values.add(item.toString().trim());
            }
        }
        return values.isEmpty() ? null : String.join(delimiter, values);
    }

    private void addAlphaMissenseMeasurements(ArrayNode target, EvidenceItem item) {
        Map<String, Object> details = item.getDetails();
        String calibrationProfileId = optionalDetail(details, "calibrationProfileId");
        if (!AlphaMissenseProvider.SUPPORTING_PROFILE_ID.equals(calibrationProfileId)) {
            throw invalid("AlphaMissense result is not bound to the Supporting profile");
        }
        Object exactAlleleMatch = details.get("exactAlleleMatch");
        Object transcriptProteinMatch = details.get("transcriptProteinMatch");
        if (!Boolean.TRUE.equals(exactAlleleMatch)
                || !(transcriptProteinMatch instanceof Boolean)) {
            throw invalid("AlphaMissense HIT is missing its exact-match result");
        }
        alphaMeasurement(target, "alphamissense.exactAlleleMatch", true,
                true, "boolean", calibrationProfileId);
        alphaMeasurement(target, "alphamissense.transcriptProteinMatch",
                transcriptProteinMatch, transcriptProteinMatch,
                "boolean", calibrationProfileId);

        String rawScore = item.getRawValue();
        if (rawScore == null) {
            return;
        }
        BigDecimal score;
        try {
            score = new BigDecimal(rawScore);
        } catch (NumberFormatException failure) {
            score = null;
        }
        if (score == null || score.compareTo(BigDecimal.ZERO) < 0
                || score.compareTo(BigDecimal.ONE) > 0) {
            throw invalid("AlphaMissense HIT has an invalid decimal score");
        }
        if (!Boolean.TRUE.equals(transcriptProteinMatch)) {
            throw invalid("AlphaMissense HIT is not an exact allele and transcript/protein match");
        }
        String transcriptId = requiredAlphaDetail(details, "transcriptId");
        String proteinVariant = requiredAlphaDetail(details, "proteinVariant");
        String uniprotId = requiredAlphaDetail(details, "uniprotId");

        alphaMeasurement(target, "alphamissense.amPathogenicity", rawScore,
                canonicalDecimal(score), "score", calibrationProfileId);
        alphaMeasurement(target, "alphamissense.transcriptId", transcriptId,
                transcriptId, "transcript-id", calibrationProfileId);
        alphaMeasurement(target, "alphamissense.proteinVariant", proteinVariant,
                proteinVariant, "protein-change", calibrationProfileId);
        alphaMeasurement(target, "alphamissense.uniprotId", uniprotId,
                uniprotId, "protein-id", calibrationProfileId);
    }

    private void addSpliceAiMeasurements(ArrayNode target, EvidenceItem item) {
        Map<String, Object> details = item.getDetails();
        String calibrationProfileId = optionalDetail(details, "calibrationProfileId");
        if (!SpliceAiProvider.RAW_SUPPORTING_PROFILE_ID.equals(calibrationProfileId)) {
            throw invalid("SpliceAI raw result is not bound to the Supporting profile");
        }
        Object exactAlleleMatch = details.get("exactAlleleMatch");
        Object geneContextMatch = details.get("geneContextMatch");
        if (!(exactAlleleMatch instanceof Boolean) || !(geneContextMatch instanceof Boolean)) {
            throw invalid("SpliceAI HIT is missing its exact allele/gene match result");
        }
        spliceMeasurement(target, "spliceai.exactAlleleMatch",
                exactAlleleMatch.toString(), exactAlleleMatch.toString(),
                "boolean", calibrationProfileId);
        spliceMeasurement(target, "spliceai.geneContextMatch",
                geneContextMatch.toString(), geneContextMatch.toString(),
                "boolean", calibrationProfileId);
        String selectedTranscript = optionalDetail(details, "selectedTranscriptId");
        if (selectedTranscript != null) {
            spliceMeasurement(target, "spliceai.selectedTranscriptId",
                    selectedTranscript, selectedTranscript, "transcript-id", calibrationProfileId);
        }
        String gene = optionalDetail(details, "geneSymbol");
        if (gene != null) {
            spliceMeasurement(target, "spliceai.geneSymbol",
                    gene, gene, "gene-symbol", calibrationProfileId);
        }

        String maximumRaw = item.getRawValue();
        if (maximumRaw == null) return;
        if (!Boolean.TRUE.equals(exactAlleleMatch) || !Boolean.TRUE.equals(geneContextMatch)) {
            throw invalid("SpliceAI score is not bound to the exact allele and selected VEP gene");
        }
        BigDecimal maximum = decimal(maximumRaw, "SpliceAI maximum raw delta score");
        spliceMeasurement(target, "spliceai.maximumRawDeltaScore",
                maximumRaw, canonicalDecimal(maximum), "score", calibrationProfileId);
        for (String field : List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) {
            String raw = requiredSpliceDetail(details, field);
            BigDecimal score = decimal(raw, "SpliceAI " + field);
            if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
                throw invalid("SpliceAI delta score is outside [0,1]");
            }
            spliceMeasurement(target, "spliceai." + field,
                    raw, canonicalDecimal(score), "score", calibrationProfileId);
        }
        for (String field : List.of("DP_AG", "DP_AL", "DP_DG", "DP_DL")) {
            String raw = requiredSpliceDetail(details, field);
            BigInteger position;
            try {
                position = new BigInteger(raw);
            } catch (NumberFormatException failure) {
                throw invalid("SpliceAI delta position is invalid");
            }
            spliceMeasurement(target, "spliceai." + field,
                    raw, position.toString(), "base-offset", calibrationProfileId);
        }
    }

    private BigDecimal decimal(String raw, String label) {
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                throw invalid(label + " is outside [0,1]");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw invalid(label + " is invalid");
        }
    }

    private String requiredSpliceDetail(Map<String, Object> details, String field) {
        String value = optionalDetail(details, field);
        if (value == null) throw invalid("SpliceAI HIT is missing " + field);
        return value;
    }

    private void spliceMeasurement(ArrayNode values,
                                   String metricId,
                                   String rawValue,
                                   String normalizedValue,
                                   String unit,
                                   String calibrationProfileId) {
        ObjectNode value = values.addObject();
        value.put("metricId", metricId);
        value.put("rawValue", rawValue);
        value.put("normalizedValue", normalizedValue);
        value.put("unit", unit);
        value.put("calibrationProfileId", calibrationProfileId);
    }

    private String requiredAlphaDetail(Map<String, Object> details, String field) {
        String value = optionalDetail(details, field);
        if (value == null) throw invalid("AlphaMissense HIT is missing " + field);
        return value;
    }

    private void alphaMeasurement(ArrayNode values,
                                  String metricId,
                                  Object rawValue,
                                  Object normalizedValue,
                                  String unit,
                                  String calibrationProfileId) {
        ObjectNode value = values.addObject();
        value.put("metricId", metricId);
        if (rawValue instanceof Boolean) value.put("rawValue", (Boolean) rawValue);
        else value.put("rawValue", rawValue.toString());
        if (normalizedValue instanceof Boolean) {
            value.put("normalizedValue", (Boolean) normalizedValue);
        } else {
            value.put("normalizedValue", normalizedValue.toString());
        }
        value.put("unit", unit);
        value.put("calibrationProfileId", calibrationProfileId);
    }

    private String canonicalDecimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }

    private String mapText(Map<?, ?> value, String field, boolean required) {
        Object item = value.get(field);
        String text = item == null ? null : item.toString().trim();
        if (text == null || text.isEmpty()) {
            if (required) throw invalid("gnomAD population measurement is missing " + field);
            return null;
        }
        return text;
    }

    private String requireDetail(Map<String, Object> details, String field) {
        String value = optionalDetail(details, field);
        if (value == null) throw invalid("gnomAD population result is missing " + field);
        return value;
    }

    private String joinedDetail(Object value) {
        if (value == null) return null;
        if (!(value instanceof List<?>)) return value.toString().trim();
        List<String> items = new ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (item != null && !item.toString().trim().isEmpty()) {
                items.add(item.toString().trim());
            }
        }
        Collections.sort(items);
        return items.isEmpty() ? null : String.join(",", items);
    }

    private ObjectNode transcriptCarrier(TranscriptConsequence consequence,
                                         ResourceBinding binding,
                                         NormalizedVariantKey key,
                                         Instant attemptedAt,
                                         Instant completedAt) {
        Axes axes = transcriptAxes(consequence.getStatus());
        String transcriptId = first(consequence.getTranscriptId(), consequence.getTranscriptAccession());
        String transcriptVersionId = consequence.getVersionedTranscriptId();
        if ("HIT".equals(axes.query)
                && (transcriptId == null || transcriptVersionId == null
                || consequence.getConsequence() == null)) {
            throw invalid("VEP HIT is missing transcript version or consequence identity");
        }
        String recordId = "HIT".equals(axes.query)
                ? recordId(null, binding.provider, key, transcriptVersionId)
                : null;
        ArrayNode measurements = mapper.createArrayNode();
        if ("HIT".equals(axes.query)) {
            measurement(measurements, "vep.selectedTranscriptId", transcriptVersionId,
                    "transcript-id");
            measurement(measurements, "consequenceTerm", consequence.getConsequence(), null);
            measurement(measurements, "impact", consequence.getImpact(), null);
            measurement(measurements, "hgvsc", consequence.getHgvsc(), null);
            measurement(measurements, "hgvsp", consequence.getHgvsp(), null);
            measurement(measurements, "proteinIdentity", consequence.getProteinIdentity(), null);
            measurement(measurements, "proteinPosition", consequence.getProteinPosition(), null);
            measurement(measurements, "aminoAcids", consequence.getAminoAcids(), null);
            measurement(measurements, "aminoAcidChange", consequence.getAminoAcidChange(), null);
            if (consequence.getExon() != null && !consequence.getExon().trim().isEmpty()) {
                ObjectNode exon = measurements.addObject();
                exon.put("metricId", "vep.exon");
                exon.put("rawValue", consequence.getExon());
                exon.put("normalizedValue", consequence.getExon());
            }
            measurement(measurements, "vep.synonymousAtExonFirstBase",
                    text(consequence.getBp7SynonymousAtExonFirstBase()), "boolean");
            measurement(measurements, "vep.synonymousDistanceFromExonEnd",
                    text(consequence.getBp7SynonymousDistanceFromExonEnd()), "base-offset");
            measurement(measurements, "vep.intronicSignedOffset",
                    text(consequence.getBp7IntronicSignedOffset()), "base-offset");
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("inputSchema", "transcript-consequence-v2-compatibility");
        payload.put("provider", binding.provider);
        payload.put("providerStatus", first(consequence.getStatus(), "UNKNOWN"));
        if (recordId != null) payload.put("sourceRecordId", recordId);
        if (consequence.getConsequence() != null) payload.put("consequence", consequence.getConsequence());
        if (transcriptId != null) payload.put("transcriptId", transcriptId);
        if (transcriptVersionId != null) payload.put("transcriptVersionId", transcriptVersionId);
        if (consequence.getImpact() != null) payload.put("impact", consequence.getImpact());
        if (consequence.getHgvsc() != null) payload.put("hgvsc", consequence.getHgvsc());
        if (consequence.getHgvsp() != null) payload.put("hgvsp", consequence.getHgvsp());
        if (consequence.getExon() != null) payload.put("exon", consequence.getExon());
        if (consequence.getSynonymousExonPosition() != null)
            payload.set("synonymousExonPosition", mapper.valueToTree(consequence.getSynonymousExonPosition()));
        if (consequence.getProteinIdentity() != null) {
            payload.put("proteinIdentity", consequence.getProteinIdentity());
        }
        if (consequence.getAminoAcidChange() != null) {
            payload.put("aminoAcidChange", consequence.getAminoAcidChange());
        }
        return carrier(binding, key, "VARIANT_CONSEQUENCE", axes, recordId, measurements,
                attemptedAt, completedAt, adapter.canonicalSha256(payload));
    }

    private ObjectNode transcriptNoRecordCarrier(ResourceBinding binding,
                                                  NormalizedVariantKey key,
                                                  String requestedTranscript,
                                                  int requestedTranscriptVersion,
                                                  Instant attemptedAt,
                                                  Instant completedAt) {
        Axes axes = transcriptAxes("NO_RECORD");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("inputSchema", "transcript-consequence-v2-compatibility");
        payload.put("provider", binding.provider);
        payload.put("providerStatus", "NO_RECORD");
        payload.put("requestedTranscript", requestedTranscript);
        payload.put("requestedTranscriptVersion", requestedTranscriptVersion);
        return carrier(binding, key, "VARIANT_CONSEQUENCE", axes, null, mapper.createArrayNode(),
                attemptedAt, completedAt, adapter.canonicalSha256(payload));
    }

    private ObjectNode carrier(ResourceBinding binding,
                               NormalizedVariantKey key,
                               String evidenceType,
                               Axes axes,
                               String recordId,
                               ArrayNode measurements,
                               Instant attemptedAt,
                               Instant completedAt,
                               String sourcePayloadSha256) {
        String retrievedAt = completedAt.toString();
        ObjectNode carrier = mapper.createObjectNode();
        carrier.put("evidenceType", evidenceType);
        carrier.set("queryAllele", allele(key));

        ObjectNode source = mapper.createObjectNode();
        source.put("sourceId", binding.resourceId);
        source.put("resourceVersion", binding.resourceVersion);
        source.put("resourceSha256", binding.identityFingerprintSha256);
        source.put("retrievedAt", retrievedAt);
        ArrayNode records = mapper.createArrayNode();
        if (recordId != null) {
            ObjectNode record = mapper.createObjectNode();
            record.put("recordId", recordId);
            record.set("locator", recordLocator(binding, recordId, retrievedAt));
            records.add(record);
        }
        source.set("sourceRecords", records);
        ArrayNode locators = mapper.createArrayNode();
        locators.add(resourceLocator(binding, retrievedAt));
        source.set("sourceLocators", locators);
        carrier.set("source", source);

        carrier.set("resourceExecution", execution(axes, attemptedAt, completedAt));
        carrier.set("resourceAvailability", status(axes.availability, axes.availabilityReason));
        ObjectNode query = mapper.createObjectNode();
        query.put("status", axes.query);
        query.put("recordCount", recordId == null ? 0 : 1);
        if (!"NOT_QUERIED".equals(axes.query)) {
            ObjectNode projection = mapper.createObjectNode();
            projection.put("resourceId", binding.resourceId);
            projection.set("queryAllele", allele(key));
            query.put("queryFingerprint", adapter.canonicalSha256(projection));
        }
        carrier.set("queryOutcome", query);
        carrier.set("resourceApplicability", status(axes.applicability, axes.applicabilityReason));
        carrier.set("measurements", measurements);
        carrier.put("producedAt", completedAt.toString());
        carrier.put("sourcePayloadSha256", sourcePayloadSha256);
        return carrier;
    }

    private List<ObjectNode> failure(JsonNode evidenceContext,
                                     ResourceBinding binding,
                                     NormalizedVariantKey key,
                                     String evidenceType,
                                     Instant attemptedAt,
                                     Instant completedAt,
                                     RuntimeException failure,
                                     String reasonCode) {
        boolean timeout = SpecialistProviderSupport.isTimeout(failure);
        String execution = timeout ? "TIMEOUT" : "FAILED";
        String executionReason = timeout ? "PROVIDER_TIMEOUT" : reasonCode;
        boolean providerReturnedInvalidPayload = "PROVIDER_PAYLOAD_INVALID".equals(reasonCode);
        Axes axes = new Axes(execution, executionReason,
                providerReturnedInvalidPayload ? "AVAILABLE" : "UNKNOWN",
                providerReturnedInvalidPayload ? null : "NOT_CHECKED",
                "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("provider", binding.provider);
        payload.put("failureCode", executionReason);
        ObjectNode carrier = carrier(binding, key, evidenceType, axes, null, mapper.createArrayNode(),
                attemptedAt, completedAt, adapter.canonicalSha256(payload));
        return Collections.singletonList(adapter.adapt(evidenceContext, carrier));
    }

    private NormalizedVariantKey validateInputs(JsonNode context,
                                                VariantInterpretation variant,
                                                ResourceBinding binding) {
        if (context == null || variant == null || variant.getObservation() == null
                || variant.getObservation().getKey() == null || binding == null) {
            throw invalid("context, variant and resource binding are required");
        }
        NormalizedVariantKey key = variant.getObservation().getKey();
        if (!binding.assembly.equals(key.getGenomeAssembly()) || !allele(key).equals(
                context.path("variant").path("evidenceQueryAllele"))) {
            throw invalid("query allele or assembly does not match the resource binding");
        }
        return key;
    }

    private void validateProviderFamily(String providerSource, ResourceBinding binding) {
        String normalized = providerSource != null && providerSource.startsWith("VEP")
                ? "VEP" : providerSource;
        if (!binding.provider.equals(normalized)) {
            throw invalid("provider does not match the resource binding");
        }
    }

    private void validateAnalysisContext(AnalysisContext analysisContext,
                                         NormalizedVariantKey key) {
        if (analysisContext == null || analysisContext.getGenomeAssembly() == null
                || !key.getGenomeAssembly().equals(analysisContext.getGenomeAssembly().name())) {
            throw invalid("analysis context assembly does not match the query allele");
        }
    }

    private Axes axes(Map<String, Object> details) {
        return new Axes(
                detail(details, "resourceExecutionStatus"),
                optionalDetail(details, "resourceExecutionReasonCode"),
                detail(details, "resourceAvailabilityStatus"),
                optionalDetail(details, "resourceAvailabilityReasonCode"),
                detail(details, "queryOutcomeStatus"),
                detail(details, "resourceApplicabilityStatus"),
                optionalDetail(details, "resourceApplicabilityReasonCode")
        );
    }

    private Axes transcriptAxes(String status) {
        if ("COMPLETED".equals(status)) {
            return new Axes("SUCCEEDED", null, "AVAILABLE", null, "HIT", "APPLICABLE", null);
        }
        if ("NO_RECORD".equals(status)) {
            return new Axes("SUCCEEDED", null, "AVAILABLE", null, "NO_RECORD", "APPLICABLE", null);
        }
        if ("ASSEMBLY_UNSUPPORTED".equals(status)) {
            return new Axes("NOT_RUN", "ASSEMBLY_UNSUPPORTED", "AVAILABLE", null,
                    "NOT_QUERIED", "NOT_APPLICABLE", "ASSEMBLY_UNSUPPORTED");
        }
        if ("DISABLED".equals(status)) {
            return new Axes("DISABLED", "PROVIDER_DISABLED", "UNKNOWN", "NOT_CHECKED",
                    "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED");
        }
        if ("TIMEOUT".equals(status)) {
            return new Axes("TIMEOUT", "PROVIDER_TIMEOUT", "AVAILABLE", null,
                    "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED");
        }
        if ("FAILED".equals(status)) {
            return new Axes("FAILED", "PROVIDER_FAILED", "AVAILABLE", null,
                    "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED");
        }
        return new Axes("NOT_RUN", "RESOURCE_UNAVAILABLE", "UNAVAILABLE", "RESOURCE_UNAVAILABLE",
                "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED");
    }

    private ObjectNode execution(Axes axes, Instant attemptedAt, Instant completedAt) {
        ObjectNode value = status(axes.execution, axes.executionReason);
        if ("SUCCEEDED".equals(axes.execution) || "FAILED".equals(axes.execution)
                || "TIMEOUT".equals(axes.execution)) {
            value.put("attemptedAt", attemptedAt.toString());
            value.put("completedAt", completedAt.toString());
        }
        return value;
    }

    private ObjectNode status(String value, String reason) {
        ObjectNode result = mapper.createObjectNode();
        result.put("status", value);
        if (reason != null) result.put("reasonCode", reason);
        return result;
    }

    private ObjectNode resourceLocator(ResourceBinding binding, String retrievedAt) {
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "REPOSITORY_ARTIFACT");
        locator.put("locatorValue", "resource-identity:" + binding.resourceId + "@" + binding.assembly);
        locator.put("retrievedAt", retrievedAt);
        return locator;
    }

    private ObjectNode recordLocator(ResourceBinding binding, String recordId, String retrievedAt) {
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "DATABASE_RECORD");
        locator.put("locatorValue", binding.resourceId + ":" + recordId);
        locator.put("sourceRecordId", recordId);
        locator.put("retrievedAt", retrievedAt);
        return locator;
    }

    private ObjectNode allele(NormalizedVariantKey key) {
        ObjectNode value = mapper.createObjectNode();
        value.put("assembly", key.getGenomeAssembly());
        value.put("contig", key.getChromosome());
        value.put("position", key.getPosition());
        value.put("reference", key.getReference());
        value.put("alternate", key.getAlternate());
        return value;
    }

    private String recordId(String sourceRecordId,
                            String provider,
                            NormalizedVariantKey key,
                            String transcriptId) {
        String value = first(sourceRecordId,
                provider + ":" + key.getGenomeAssembly() + ":" + key.getChromosome() + ":"
                        + key.getPosition() + ":" + key.getReference() + ":" + key.getAlternate()
                        + (transcriptId == null ? "" : ":" + transcriptId));
        if (value.length() > 256) throw invalid("source record id is too long");
        return value;
    }

    private void measurement(ArrayNode values, String metricId, String rawValue, String unit) {
        if (rawValue == null || rawValue.trim().isEmpty()) return;
        ObjectNode value = mapper.createObjectNode();
        value.put("metricId", metricId);
        value.put("rawValue", rawValue);
        if (unit != null) value.put("unit", unit);
        values.add(value);
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private String evidenceType(ResourceBinding binding) {
        if ("GNOMAD".equals(binding.provider)) return "POPULATION_FREQUENCY";
        if ("CLINVAR".equals(binding.provider)) return "CLINICAL_DATABASE";
        if ("VEP".equals(binding.provider)) return "VARIANT_CONSEQUENCE";
        if ("ALPHAMISSENSE".equals(binding.provider)) return "COMPUTATIONAL_PREDICTION";
        if ("SPLICEAI".equals(binding.provider)) return "COMPUTATIONAL_PREDICTION";
        throw invalid("provider is outside the runtime v2 boundary");
    }

    private String detail(Map<String, Object> details, String key) {
        String value = optionalDetail(details, key);
        if (value == null) throw invalid("provider result is missing " + key);
        return value;
    }

    private String optionalDetail(Map<String, Object> details, String key) {
        Object value = details == null ? null : details.get(key);
        return value == null || value.toString().trim().isEmpty() ? null : value.toString().trim();
    }

    private String requiredText(JsonNode value, String field) {
        JsonNode item = value == null ? null : value.get(field);
        if (item == null || !item.isTextual() || item.asText().trim().isEmpty()) {
            throw invalid("manifest field is missing: " + field);
        }
        return item.asText();
    }

    private int requiredPositiveInteger(JsonNode value, String field) {
        JsonNode item = value == null ? null : value.get(field);
        if (item == null || !item.isIntegralNumber() || !item.canConvertToInt()
                || item.intValue() < 1) {
            throw invalid("context field is not a positive integer: " + field);
        }
        return item.intValue();
    }

    private String first(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private boolean matchesTranscript(TranscriptConsequence consequence,
                                      String requestedTranscript,
                                      int requestedVersion) {
        String transcriptId = first(
                consequence.getTranscriptId(), consequence.getTranscriptAccession());
        if (!requestedTranscript.equals(transcriptId)) return false;
        String hgvsc = consequence.getHgvsc();
        return (requestedTranscript + "." + requestedVersion).equals(hgvscTranscriptId(hgvsc));
    }

    private String hgvscTranscriptId(String hgvsc) {
        if (hgvsc == null) return null;
        int separator = hgvsc.indexOf(':');
        return separator > 0 ? hgvsc.substring(0, separator) : null;
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid runtime provider wiring: " + message);
    }

    private static final class Axes {
        private final String execution;
        private final String executionReason;
        private final String availability;
        private final String availabilityReason;
        private final String query;
        private final String applicability;
        private final String applicabilityReason;

        private Axes(String execution,
                     String executionReason,
                     String availability,
                     String availabilityReason,
                     String query,
                     String applicability,
                     String applicabilityReason) {
            this.execution = execution;
            this.executionReason = executionReason;
            this.availability = availability;
            this.availabilityReason = availabilityReason;
            this.query = query;
            this.applicability = applicability;
            this.applicabilityReason = applicabilityReason;
        }
    }

    private record BatchVariant(NormalizedVariantKey key, JsonNode context) {}

    private static final class ComparatorProjectionBindings {
        private final ResourceBinding clinVarVcf;
        private final ResourceBinding vep;
        private final ResourceBinding sidecar;

        private ComparatorProjectionBindings(ResourceBinding clinVarVcf,
                                             ResourceBinding vep,
                                             ResourceBinding sidecar) {
            this.clinVarVcf = clinVarVcf;
            this.vep = vep;
            this.sidecar = sidecar;
        }
    }

    public static final class ResourceBinding {
        private final String resourceId;
        private final String provider;
        private final String providerVersion;
        private final String assembly;
        private final String resourceVersion;
        private final String identityFingerprintSha256;

        private ResourceBinding(String resourceId,
                                String provider,
                                String providerVersion,
                                String assembly,
                                String resourceVersion,
                                String identityFingerprintSha256) {
            this.resourceId = resourceId;
            this.provider = provider;
            this.providerVersion = providerVersion;
            this.assembly = assembly;
            this.resourceVersion = resourceVersion;
            this.identityFingerprintSha256 = identityFingerprintSha256;
        }

        public String getResourceId() { return resourceId; }
        public String getProvider() { return provider; }
        public String getProviderVersion() { return providerVersion; }
        public String getAssembly() { return assembly; }
        public String getResourceVersion() { return resourceVersion; }
        public String getIdentityFingerprintSha256() { return identityFingerprintSha256; }
    }
}
