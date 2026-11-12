package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;

/** Uses the existing profile-bound provider and BA1 calculation once per candidate. */
public final class Ba1AlleleRuntime {
    private final ObjectMapper mapper;
    private final GnomadPopulationInputProfile profile;
    private final VariantEvidenceProvider provider;
    private final Ba1SpecificationCatalog catalog;
    private final Ba1SubtypeFilterSupplement subtypeFilters;
    private final ObjectNode sourceIdentity;
    private final java.util.Map<String, ObjectNode> executionSubtypeFacts = new java.util.HashMap<>();
    /** Business runtime is request-scoped; never retain transient failures into a later explicit execution. */
    public void beginExecution() {
        executionSubtypeFacts.clear();
    }
    public java.util.List<java.util.Map<String, Object>> queryDiagnostics() {
        return provider instanceof GnomadTabixProvider tabix ? tabix.queryDiagnostics()
                : java.util.List.of();
    }
    public Ba1AlleleRuntime(ObjectMapper mapper, VariantAnalysisProperties settings) {
        this(mapper, settings, settings.getGnomad().isBa1LocalExomesEnabled() ? Ba1SpecificationCatalog.bundled(mapper) : null, null);
    }
    public Ba1AlleleRuntime(ObjectMapper mapper, GnomadPopulationInputProfile profile, VariantEvidenceProvider provider) {
        this(mapper, profile, provider, null);
    }
    public Ba1AlleleRuntime(ObjectMapper mapper, VariantAnalysisProperties settings, Ba1SpecificationCatalog catalog) {
        this(mapper, settings, catalog, null);
    }
    public Ba1AlleleRuntime(ObjectMapper mapper, VariantAnalysisProperties settings, Ba1SpecificationCatalog catalog, ObjectNode sourceIdentity) {
        this(mapper, settings.getGnomad().isBa1LocalExomesEnabled() ? GnomadPopulationInputProfile.loadLocalBa1() : GnomadPopulationInputProfile.loadExecutableV2(),
                settings.getGnomad().isBa1LocalExomesEnabled() ? Ba1LocalExomes.provider(settings)
                        : new GnomadTabixProvider(settings, GnomadPopulationInputProfile.loadExecutableV2(), true, catalog != null), catalog,
                catalog == null || settings.getGnomad().isBa1LocalExomesEnabled() ? null : Ba1SubtypeFilterSupplement.bundled(mapper, settings),
                settings.getGnomad().isBa1LocalExomesEnabled() ? localIdentity(mapper, settings, sourceIdentity) : sourceIdentity);
    }

    private static ObjectNode localIdentity(ObjectMapper mapper, VariantAnalysisProperties settings, ObjectNode original) {
        var identity = original == null ? mapper.createObjectNode() : original.deepCopy();
        identity.set("transport", Ba1LocalExomes.identity(mapper, settings));
        return identity;
    }
    public Ba1AlleleRuntime(ObjectMapper mapper, GnomadPopulationInputProfile profile, VariantEvidenceProvider provider,
                           Ba1SpecificationCatalog catalog) {
        this(mapper, profile, provider, catalog, null);
    }
    public Ba1AlleleRuntime(ObjectMapper mapper, GnomadPopulationInputProfile profile, VariantEvidenceProvider provider,
                           Ba1SpecificationCatalog catalog, Ba1SubtypeFilterSupplement subtypeFilters) {
        this(mapper, profile, provider, catalog, subtypeFilters, null);
    }
    private Ba1AlleleRuntime(ObjectMapper mapper, GnomadPopulationInputProfile profile, VariantEvidenceProvider provider,
                            Ba1SpecificationCatalog catalog, Ba1SubtypeFilterSupplement subtypeFilters, ObjectNode sourceIdentity) {
        this.mapper = mapper; this.profile = profile; this.provider = provider;
        this.catalog = profile.isLocalBa1() && catalog == null ? Ba1SpecificationCatalog.unavailable(mapper, "BA1_CATALOG_REQUIRED") : catalog;
        this.subtypeFilters = subtypeFilters;
        this.sourceIdentity = sourceIdentity == null ? null : sourceIdentity.deepCopy();
    }
    public ObjectNode identity() {
        var result = mapper.createObjectNode().put("schemaVersion", catalog == null ? "ba1-runtime-identity-v2" : "ba1-runtime-identity-v3")
                .put("algorithmVersion", catalog == null ? "1.0.0" : Ba1SpecificationCatalog.VERSION)
                .put("populationInputProfileSha256", profile.getProfileSha256());
        if (profile.isLocalBa1()) result.set("dataScope", profile.getDocument().path("localDataPolicy"));
        if (catalog != null) {
            if (sourceIdentity != null) result.set("sourceConfiguration", sourceIdentity.deepCopy());
            result.put("conversionSourceVersion", org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.VERSION);
            var catalogIdentity = catalog.identity();
            result.set("catalog", catalogIdentity);
            if (catalogIdentity.has("conditionalFrequencyContract")) {
                result.put("conditionalConsumerVersion", GnomadPopulationCriterionEvaluator.BA1_CONDITIONAL_EVALUATOR_VERSION);
                result.set("nativeIndividualCounts", Ba1NativeIndividualCountSupply.identity(mapper));
            }
            result.set("nativeSubtypeFilters", subtypeFilters == null
                    ? mapper.createObjectNode().put("status", "DISABLED_NO_PROVIDER") : subtypeFilters.identity());
            result.put("exceptionPolicySha256", new RuntimeEvidenceV2Adapter(mapper)
                    .canonicalSha256(catalog.baselinePolicy().at("/frequencyRule/exceptionPolicy")));
        }
        return result;
    }
    public ObjectNode evaluate(String alleleId, VariantInterpretation candidate, AnalysisContext analysis) {
        return evaluate(alleleId, candidate, analysis, null, null);
    }

    /** Detached transport view: formal ranking and sample/source facts are never mutated. */
    public ObjectNode evaluateNormalized(String alleleId, VariantInterpretation candidate, AnalysisContext analysis,
            com.fasterxml.jackson.databind.JsonNode observation, String executionSourceSha256,
            com.fasterxml.jackson.databind.JsonNode declaredContexts, com.fasterxml.jackson.databind.JsonNode queryAllele,
            com.fasterxml.jackson.databind.JsonNode normalizer, com.fasterxml.jackson.databind.JsonNode proof) {
        return evaluateNormalized(alleleId,candidate,analysis,observation,executionSourceSha256,declaredContexts,queryAllele,normalizer,proof,true);
    }
    public ObjectNode evaluateNormalized(String alleleId, VariantInterpretation candidate, AnalysisContext analysis,
            com.fasterxml.jackson.databind.JsonNode observation, String executionSourceSha256,
            com.fasterxml.jackson.databind.JsonNode declaredContexts, com.fasterxml.jackson.databind.JsonNode queryAllele,
            com.fasterxml.jackson.databind.JsonNode normalizer, com.fasterxml.jackson.databind.JsonNode proof, boolean evaluateBa1) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        if (!alleleId.equals(adapter.canonicalAlleleId(queryAllele, normalizer))
                || !"MATCH".equals(normalizer.path("refValidation").asText())
                || !candidate.getObservation().getKey().getGenomeAssembly().equals(queryAllele.path("assembly").asText())
                || !candidate.getCandidateId().equals(proof.path("candidateId").asText())
                || !queryAllele.equals(proof.path("normalizedAllele"))
                || !normalizer.path("method").equals(proof.path("method"))
                || !normalizer.path("methodVersion").equals(proof.path("methodVersion"))
                || !"SUCCEEDED".equals(proof.path("executionStatus").asText())
                || !"MATCH".equals(proof.path("refValidation").asText()))
            throw new IllegalArgumentException("BA1_NORMALIZED_QUERY_IDENTITY_INVALID");
        var original = candidate.getObservation().getKey();
        if (original.getChromosome().equals(queryAllele.path("contig").asText())
                && original.getPosition() == queryAllele.path("position").asInt()
                && original.getReference().equals(queryAllele.path("reference").asText())
                && original.getAlternate().equals(queryAllele.path("alternate").asText()))
            return evaluate(alleleId, candidate, analysis, observation, executionSourceSha256, declaredContexts, evaluateBa1);
        var key = new NormalizedVariantKey(
                queryAllele.path("assembly").asText(), queryAllele.path("contig").asText(), queryAllele.path("position").asInt(),
                queryAllele.path("reference").asText(), queryAllele.path("alternate").asText());
        var source = candidate.getObservation();
        var detachedObservation = new VariantQcObservation(key, source.getSampleId(), source.getGenotype(), source.getDepth(),
                source.getGenotypeQuality(), source.getAlleleDepths(), source.getAlleleBalance(), source.getFilterStatus(),
                source.getVariantType(), source.getOriginalRecord());
        var originalIdentity = candidate.getCandidateIdentity();
        var queryIdentity = new CandidateIdentity(CandidateIdentity.SCHEMA_VERSION, candidate.getCandidateId(), key.canonicalId(),
                "NORMALIZATION_QUERY", null, originalIdentity.getGeneSymbol(), originalIdentity.getModeOfInheritance(), null);
        var detached = new VariantInterpretation(queryIdentity, detachedObservation,
                candidate.getConsequences(), java.util.List.of());
        detached.applyTranscriptAnnotations(candidate.getTranscriptAnnotations());
        return evaluate(alleleId, detached, analysis, observation, executionSourceSha256, declaredContexts, evaluateBa1);
    }
    public ObjectNode evaluate(String alleleId, VariantInterpretation candidate, AnalysisContext analysis,
                               com.fasterxml.jackson.databind.JsonNode observation, String executionSourceSha256) {
        return evaluate(alleleId, candidate, analysis, observation, executionSourceSha256, null);
    }
    public ObjectNode evaluate(String alleleId, VariantInterpretation candidate, AnalysisContext analysis,
                               com.fasterxml.jackson.databind.JsonNode observation, String executionSourceSha256,
                               com.fasterxml.jackson.databind.JsonNode declaredContexts) {
        return evaluate(alleleId,candidate,analysis,observation,executionSourceSha256,declaredContexts,true);
    }
    private ObjectNode evaluate(String alleleId, VariantInterpretation candidate, AnalysisContext analysis,
            com.fasterxml.jackson.databind.JsonNode observation, String executionSourceSha256,
            com.fasterxml.jackson.databind.JsonNode declaredContexts, boolean evaluateBa1) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        boolean generalized = catalog != null && "GRCh38".equals(candidate.getObservation().getKey().getGenomeAssembly());
        ObjectNode carrier = null, sharedIdentity = null;
        String neutralRaw = null;
        // Explicit successor envelope from the trusted population route. Verify the immutable
        // neutral parent before adding any BA1-only projections to a detached carrier.
        if (observation != null && observation.path("populationObservation").isObject()) {
            var parent = observation.path("populationObservation"); var context = observation.path("context");
            PopulationObservation.verify(mapper, parent, context, executionSourceSha256, profile);
            var allele = context.at("/variant/evidenceQueryAllele"); var normalizer = context.at("/variant/normalization");
            var key = candidate.getObservation().getKey();
            if (!alleleId.equals(adapter.canonicalAlleleId(allele, normalizer))
                    || !key.getGenomeAssembly().equals(allele.path("assembly").asText())
                    || !key.getChromosome().equals(allele.path("contig").asText())
                    || key.getPosition() != allele.path("position").asLong()
                    || !key.getReference().equals(allele.path("reference").asText())
                    || !key.getAlternate().equals(allele.path("alternate").asText()))
                throw new IllegalArgumentException("BA1_SHARED_NEUTRAL_IDENTITY_MISMATCH");
            carrier = ((ObjectNode) parent.path("carrier")).deepCopy(); neutralRaw = parent.path("rawRecord").asText();
            sharedIdentity = mapper.createObjectNode().put("schemaVersion", "ba1-neutral-parent-reuse-v1")
                    .put("parentObservationId", parent.path("observationId").asText())
                    .put("profileSha256", parent.path("profileSha256").asText())
                    .put("rawRecordSha256", parent.path("rawRecordSha256").asText());
            if (generalized && !profile.isLocalBa1()) wiring.preserveBa1SubtypeFilters(carrier, neutralRaw);
        }
        if (carrier == null && !profile.isLocalBa1() && observation != null && observation.path("context").isObject()
                && !(sourceIdentity != null && sourceIdentity.has("transport"))) try {
            var key = candidate.getObservation().getKey();
            var context = observation.path("context");
            var normalizer = mapper.createObjectNode();
            for (String name : java.util.List.of("method", "methodVersion", "referenceSequenceSha256", "refValidation"))
                normalizer.set(name, context.at("/variant/normalization").path(name));
            var allele = context.at("/variant/evidenceQueryAllele");
            if (!alleleId.equals(adapter.canonicalAlleleId(allele, normalizer))
                    || !key.getGenomeAssembly().equals(allele.path("assembly").asText())
                    || !key.getChromosome().equals(allele.path("contig").asText())
                    || key.getPosition() != allele.path("position").asLong()
                    || !key.getReference().equals(allele.path("reference").asText())
                    || !key.getAlternate().equals(allele.path("alternate").asText())) throw new IllegalArgumentException();
            var parsed = wiring.reparsePopulationObservation(context, observation, executionSourceSha256,
                    GnomadPopulationInputProfile.loadBusinessPm2Supporting(), profile);
            carrier = parsed.carrier(); sharedIdentity = parsed.identity();
            if (generalized) wiring.preserveBa1SubtypeFilters(carrier, observation.path("rawPopulationRecord").asText());
        } catch (IllegalArgumentException invalid) {
            // An incompatible observation cannot replace a fresh query.
            carrier = null; sharedIdentity = null;
        }
        boolean reused = carrier != null;
        String[] rawRecord = { neutralRaw != null ? neutralRaw : reused && observation != null ? observation.path("rawPopulationRecord").asText() : "" };
        if (carrier == null) carrier = generalized && !profile.isLocalBa1() ? wiring.queryBa1PopulationCarrier(provider, candidate, analysis,
                wiring.bind(profile.getDocument(), profile.getPopulationResourceId()), raw -> rawRecord[0] = raw)
                : wiring.queryNeutralPopulationCarrier(provider, candidate, analysis, wiring.bind(profile.getDocument(), profile.getPopulationResourceId()), raw -> rawRecord[0] = raw);
        if (profile.isLocalBa1()) wiring.verifyPopulationRecord(carrier, carrier.path("queryAllele"), rawRecord[0], profile);
        var result = mapper.createObjectNode();
        if (sourceIdentity != null && sourceIdentity.has("transport")) {
            result.set("transportIdentity", sourceIdentity.path("transport").deepCopy());
            result.set("transportDiagnostics", mapper.valueToTree(queryDiagnostics()));
        }
        if (evaluateBa1 && generalized && catalog != null) evaluateSelected(result, alleleId, candidate, carrier, declaredContexts, catalog, rawRecord[0]);
        else if (evaluateBa1) result.set("evaluation", new GnomadPopulationCriterionEvaluator(mapper).evaluateBa1Allele(alleleId, carrier, profile));
        result.set("evidence", carrier);
        if (profile.isLocalBa1()) result.set("dataScope", profile.getDocument().path("localDataPolicy"));
        if (generalized && "HIT".equals(carrier.at("/queryOutcome/status").asText()) && !rawRecord[0].isBlank())
            result.put("rawPopulationRecord", rawRecord[0]);
        var readiness = result.putArray("recordInputReadinessReasonCodes");
        for (var measurement : carrier.path("measurements"))
            if ("gnomad.recordInputReadinessReasonCodes".equals(measurement.path("metricId").asText()))
                for (String reason : measurement.path("rawValue").asText().split("[|,;]")) if (!reason.isBlank()) readiness.add(reason.trim());
        for (var criterion : profile.getDocument().path("evaluation").path("criteria"))
            if (!generalized)
            if ("ACMG_AMP_2015:BA1".equals(criterion.path("criterionId").asText())) result.set("policy", criterion.path("frequencyRule"));
        result.put("evidenceId", "population-evidence-sha256-" + adapter.canonicalSha256(carrier));
        result.put("providerCalls", (reused ? 0 : 1) + result.path("subtypeFilterProviderCalls").asInt())
                .put("evaluatorCalls", evaluateBa1 ? 1 + result.path("contextResults").size() : 0);
        if (sharedIdentity != null) result.set("observationIdentity", sharedIdentity);
        result.put("bundleStatus", "ALLELE_SCOPED_RESULT_NO_FABRICATED_CLINICAL_CONTEXT");
        return result;
    }

    /** Source preparation is shared; the central evaluator must still consume the complete OR. */
    private static boolean selectedForSourceInputs(com.fasterxml.jackson.databind.JsonNode selection) {
        return "SELECTED".equals(selection.path("status").asText())
                || "CONDITIONAL_SELECTED".equals(selection.path("status").asText())
                && Ba1ConditionalFrequencyRule.VERSION.equals(selection.at("/policy/conditionRuntimeContract").asText());
    }

    private void evaluateSelected(ObjectNode result, String alleleId, VariantInterpretation candidate, ObjectNode carrier,
                                  com.fasterxml.jackson.databind.JsonNode contexts, Ba1SpecificationCatalog activeCatalog, String rawRecord) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var evaluator = new GnomadPopulationCriterionEvaluator(mapper);
        var mapping = activeCatalog.mapping(candidate);
        var selection = activeCatalog.select(mapping, null);
        if (profile.isLocalBa1()) selection = Ba1LocalExomes.selection(mapper, selection);
        var key = candidate.getObservation().getKey();
        String ref = key.getReference(), alt = key.getAlternate();
        boolean supported = ref != null && alt != null && ref.matches("[ACGT]{1,50}") && alt.matches("[ACGT]{1,50}")
                && !ref.equals(alt) && (ref.length() == alt.length()
                || Math.min(ref.length(), alt.length()) == 1 && ref.charAt(0) == alt.charAt(0));
        if (!supported) selection = blockedSelection(selection, "NOT_APPLICABLE", "BA1_VARIANT_TYPE_UNSUPPORTED", adapter);
        result.set("policy", selection.at("/policy/frequencyRule").deepCopy());
        var generalization = result.putObject("generalization").put("runtimeVersion", Ba1SpecificationCatalog.VERSION);
        generalization.set("selection", selection);
        generalization.set("effectivePolicy", selection.path("policy").deepCopy());
        generalization.put("scope", selection.path("scope").asText());
        var outputs = result.putArray("contextResults");
        var seen = new java.util.HashSet<String>();
        if (contexts != null) for (var context : contexts) {
            String contextId = context.path("contextId").asText();
            if (!seen.add(contextId)) continue;
            ObjectNode contextMapping = mapping.deepCopy();
            ObjectNode contextSelection;
            try {
                adapter.validateCompleteContext(context);
                var normalizer = mapper.createObjectNode();
                for (String name : java.util.List.of("method", "methodVersion", "referenceSequenceSha256", "refValidation"))
                    normalizer.set(name, context.at("/variant/normalization").path(name));
                boolean converted = "MAPPED".equals(context.at("/variant/assemblyLineage/mappingStatus").asText());
                var query = context.at("/variant/evidenceQueryAllele");
                boolean verifiedConvertedQuery = converted
                        && "VERIFIED_GRCH37_TO_GRCH38_SMALL_VARIANT".equals(context.at("/variant/assemblyLineage/coordinateMapping/method").asText())
                        && "1.0.0".equals(context.at("/variant/assemblyLineage/coordinateMapping/methodVersion").asText())
                        && "GRCh37".equals(context.at("/variant/normalizedAllele/assembly").asText())
                        && key.getGenomeAssembly().equals(query.path("assembly").asText())
                        && key.getChromosome().equals(query.path("contig").asText())
                        && query.path("position").isIntegralNumber() && key.getPosition() == query.path("position").asInt()
                        && key.getReference().equals(query.path("reference").asText()) && key.getAlternate().equals(query.path("alternate").asText());
                if (converted ? !verifiedConvertedQuery : !alleleId.equals(adapter.canonicalAlleleId(query, normalizer)))
                    throw new IllegalArgumentException("BA1_CONTEXT_ALLELE_MISMATCH");
                // The business assembler has independently verified this exact declared gene/allele.
                // It does not establish all other possible genes for the candidate-level result.
                if (!"RESOLVED".equals(contextMapping.path("status").asText())) {
                    contextMapping.put("status", "RESOLVED");
                    contextMapping.putArray("hgncIds").add(context.at("/gene/hgncId").asText());
                    contextMapping.put("mappingBasis", "VALIDATED_EXACT_EVIDENCE_CONTEXT").put("contextId", contextId);
                }
                contextSelection = activeCatalog.select(contextMapping, context);
                if (profile.isLocalBa1()) contextSelection = Ba1LocalExomes.selection(mapper, contextSelection);
                if (!supported) contextSelection = blockedSelection(contextSelection, "NOT_APPLICABLE", "BA1_VARIANT_TYPE_UNSUPPORTED", adapter);
            } catch (IllegalArgumentException invalid) {
                contextSelection = blockedSelection(selection.deepCopy(), "BLOCKED", "BA1_CONTEXT_IDENTITY_INVALID", adapter);
            }
            var output = outputs.addObject().put("contextId", contextId);
            output.set("selection", contextSelection);
            output.set("policy", contextSelection.at("/policy/frequencyRule").deepCopy());
        }
        boolean selected = selectedForSourceInputs(selection);
        for (var output : outputs) selected |= selectedForSourceInputs(output.path("selection"));
        String rawSha256 = rawRecord.isBlank() ? "" : sha256(rawRecord);
        ObjectNode supplement = null;
        var missing = new java.util.LinkedHashSet<String>();
        for (String subtype : java.util.List.of("exomes", "genomes"))
            for (var measurement : carrier.path("measurements"))
                if (("gnomad.joint." + subtype + ".filtersPresence").equals(measurement.path("metricId").asText())
                        && "MISSING".equals(measurement.path("rawValue").asText())) missing.add(subtype);
        if (selected && subtypeFilters != null && "HIT".equals(carrier.at("/queryOutcome/status").asText())
                && !rawSha256.isBlank() && !missing.isEmpty() && supplementMayRepairMissingFilters(carrier)) {
            var factIdentity = mapper.createObjectNode().put("jointRawSha256", rawSha256);
            factIdentity.set("queryAllele", carrier.path("queryAllele"));
            factIdentity.set("sourcePolicyIdentity", subtypeFilters.identity());
            String factKey = adapter.canonicalSha256(factIdentity);
            var savedFact = executionSubtypeFacts.get(factKey);
            if (savedFact != null) {
                supplement = savedFact.deepCopy();
                result.put("subtypeFilterReusedWithinExecution", true).put("subtypeFilterProviderCalls", 0);
            } else {
                supplement = subtypeFilters.supplement(carrier.path("queryAllele"), missing, "4.1", rawSha256);
                executionSubtypeFacts.put(factKey, supplement.deepCopy());
                result.put("subtypeFilterProviderCalls", supplement.path("queryCalls").asInt());
            }
            result.set("subtypeFilterEvidence", supplement);
        }
        boolean conditional = "CONDITIONAL_SELECTED".equals(selection.path("status").asText());
        for (var output : outputs) conditional |= "CONDITIONAL_SELECTED".equals(output.at("/selection/status").asText());
        // One immutable source-bound material is shared by this allele's selected
        // contexts. It is not reconstructed from public results or declarations.
        var countMaterial = conditional
                ? Ba1NativeIndividualCountSupply.read(mapper, profile, alleleId, carrier, rawRecord) : null;
        result.set("evaluation", evaluator.evaluateCatalogBa1(alleleId, carrier, profile, selection, supplement, rawSha256, countMaterial));
        generalization.set("requiredInputs", requiredInputs(selection, result.path("evaluation")));
        for (var node : outputs) {
            var output = (ObjectNode) node;
            var evaluation = evaluator.evaluateCatalogBa1(alleleId, carrier, profile, output.path("selection"), supplement, rawSha256, countMaterial);
            output.set("evaluation", evaluation);
            output.set("requiredInputs", requiredInputs(output.path("selection"), evaluation));
        }
    }

    private static String sha256(String raw) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean supplementMayRepairMissingFilters(ObjectNode carrier) {
        for (String metric : java.util.List.of("gnomad.joint.filter", "gnomad.joint.exomes.filtersPresence", "gnomad.joint.genomes.filtersPresence")) {
            String value = null;
            for (var measurement : carrier.path("measurements")) if (metric.equals(measurement.path("metricId").asText())) {
                if (value != null) return false;
                value = measurement.path("rawValue").asText();
            }
            if (value == null || ("gnomad.joint.filter".equals(metric) ? !"PASS".equals(value)
                    : !java.util.Set.of("PASS", ".", "MISSING").contains(value))) return false;
        }
        return true;
    }

    private ObjectNode blockedSelection(ObjectNode selection, String status, String reason, RuntimeEvidenceV2Adapter adapter) {
        selection.put("status", status).put("reasonCode", reason);
        selection.remove("selectionId");
        selection.put("selectionId", "ba1-selection-sha256-" + adapter.canonicalSha256(selection));
        return selection;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode requiredInputs(com.fasterxml.jackson.databind.JsonNode selection,
                                                                         com.fasterxml.jackson.databind.JsonNode evaluation) {
        var result = mapper.createArrayNode();
        for (var input : selection.path("requiredInputs")) result.add(input.deepCopy());
        if ("NOT_EVALUATED".equals(evaluation.at("/evaluationResult/status").asText())
                && !"NOT_APPLICABLE".equals(evaluation.at("/criterionApplicability/status").asText()) && result.isEmpty()) {
            String reason = evaluation.at("/evaluationResult/reasonCode").asText();
            String field = reason.contains("EXCEPTION") ? "verifiedExceptionSnapshot" : reason.contains("ANCESTRY_AN")
                    ? "selectedAncestryAlleleNumber" : reason.contains("FILTER") ? "jointExomeGenomeFilterFields"
                    : reason.contains("DISCORDAN") ? "exomeGenomeConcordance" : reason.contains("COUNT_AF") ? "consistentAncestryAcAnAf"
                    : reason.contains("NO_RECORD") ? "populationObservationWithUsableFrequency" : "completePopulationObservation";
            result.addObject().put("field", field).put("reason", reason);
        }
        return result;
    }
}
