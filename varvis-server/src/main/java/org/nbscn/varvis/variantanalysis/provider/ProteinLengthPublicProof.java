package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Bounded evidence projection, issued only while the runtime holds its actual immutable material.
 * Content bindings permit re-projection of a saved module; they are not source authentication or
 * a replacement for the original material, reference, regional and policy qualification checks. */
public final class ProteinLengthPublicProof {
    public static final String VERSION = "1.0.0";
    private static final String RECEIPT = "proteinLengthPublicProof";
    private static final String COMPLETE = "COMPLETE_SEQUENCE_COMPARISON_PASSED";
    private static final Set<String> KINDS = Set.of("VERIFIED_INFRAME_LENGTH_CHANGE", "VERIFIED_STOP_LOSS_SMALL_ALLELE");
    private ProteinLengthPublicProof() { }

    /** Code-owned projection identity for the lazy central configuration/request cache.
     * Constructing it neither opens resources nor grants source/scientific qualification. */
    public static ObjectNode identity(ObjectMapper mapper) {
        var capability = mapper.createObjectNode().put("capabilityId", "PROTEIN_LENGTH_CURRENT_SEQUENCE_AND_ADOPTION_PROJECTION")
                .put("projectionVersion", VERSION).put("currentSequenceSchema", "protein-length-current-sequence-v1")
                .put("adoptionProofSchema", "protein-length-adoption-proof-v1")
                .put("runtimeReceiptSchema", "protein-length-runtime-proof-binding-v1")
                .put("actualMaterialRequired", true).put("canonicalModuleResultBinding", true)
                .put("maximumModelSources", 16).put("maximumRepeatObservations", 16).put("maximumCoverageIntervals", 16)
                .put("createsScientificApproval", false).put("rescoringPerformed", false);
        capability.put("canonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(capability));
        return capability;
    }

    /** Called by material acquisition, never from a request or a saved JSON approval label. */
    static ObjectNode materialBinding(ObjectMapper mapper, ObjectNode model, ObjectNode context) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var out = mapper.createObjectNode().put("modelCanonicalSha256", adapter.canonicalSha256(model))
                .put("modelSourceRecordsSha256", adapter.canonicalSha256(model.path("sourceRecords")))
                .put("contextSha256", adapter.canonicalSha256(context));
        var sources = out.putArray("sources");
        JsonNode records = model.path("sourceRecords");
        out.put("sourceCount", records.size()).put("sourcesTruncated", records.size() > 16);
        if (records.isArray() && records.size() <= 16) for (var record : records) {
            var row = sources.addObject().put("kind", "TRANSCRIPT_MODEL");
            copyId(row, record, "sourceId"); copyId(row, record, "version");
            copyHash(row, record, "sha256"); copyUrl(row, record, "url");
        }
        return out;
    }

    static boolean supported(JsonNode out) {
        return KINDS.contains(out.at("/sequenceMaterial/associationKind").asText())
                && ProteinLengthMaterialFactory.CAPABILITY_ID.equals(out.at("/sequenceMaterial/sequenceCapability/capabilityId").asText());
    }

    /** Issue once from the actual runtime state. No public method can issue from JSON alone. */
    static void attach(ObjectMapper mapper, ObjectNode out, @Nullable CodingTranscriptMaterialSupply.Material material,
            @Nullable ObjectNode context, String criterion) {
        if (!supported(out)) return; // All legacy simple public facts keep their previous shape.
        out.remove(RECEIPT);
        var receipt = mapper.createObjectNode().put("schemaVersion", "protein-length-runtime-proof-binding-v1");
        try {
            check(material != null && material.ready() && context != null, "SEQUENCE_MATERIAL_NOT_READY");
            var actual = java.util.Objects.requireNonNull(material);
            var selected = java.util.Objects.requireNonNull(context);
            ObjectNode model = java.util.Objects.requireNonNull(actual.model(), "MODEL_REQUIRED");
            check(actual.snapshot().equals(out.path("sequenceMaterial")), "SEQUENCE_MATERIAL_SNAPSHOT_MISMATCH");
            check(materialBinding(mapper, model, selected).equals(out.at("/sequenceMaterial/publicMaterialBinding")), "MODEL_SOURCE_BINDING_MISMATCH");
            check(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(model).equals(out.at("/sequenceMaterial/modelCanonicalSha256").asText()), "MODEL_IDENTITY_MISMATCH");
            check(model.path("transcript").equals(out.at("/sequenceMaterial/transcript"))
                    && model.path("protein").equals(out.at("/sequenceMaterial/alleleAssociation/protein")), "MODEL_TRANSCRIPT_PROTEIN_MISMATCH");
            receipt.set("payload", build(mapper, out, selected, criterion));
            receipt.put("status", "ISSUED_FROM_ACTUAL_MATERIAL");
        } catch (RuntimeException unavailable) {
            receipt.put("status", "UNAVAILABLE").put("reasonCode", safeReason(unavailable));
        }
        try {
            receipt.put("resultSha256", resultSha(mapper, out));
            receipt.put("receiptSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(receipt));
        } catch (RuntimeException invalidIdentity) {
            receipt.removeAll(); receipt.put("status", "UNAVAILABLE").put("reasonCode", "PUBLIC_RESULT_CANONICAL_BINDING_UNAVAILABLE");
        }
        out.set(RECEIPT, receipt);
    }

    /** Re-project an issued module receipt. The caller must locate/bind the actual saved module,
     * rather than accepting this object or a self hash as authentication of an external source. */
    public static ObjectNode project(ObjectMapper mapper, JsonNode raw) {
        if (!supported(raw)) return raw.has("adoptionProof") || raw.has(RECEIPT)
                ? unavailable(mapper, "SUPPORTED_ACTUAL_MATERIAL_REQUIRED") : mapper.createObjectNode();
        try {
            check(raw instanceof ObjectNode, "MODULE_RESULT_REQUIRED");
            var out = (ObjectNode) raw;
            check(out.path(RECEIPT) instanceof ObjectNode, "ACTUAL_MATERIAL_RECEIPT_REQUIRED");
            var receipt = (ObjectNode) out.path(RECEIPT);
            var unsigned = receipt.deepCopy(); unsigned.remove("receiptSha256");
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            check(hash(receipt.path("receiptSha256")) && adapter.canonicalSha256(unsigned).equals(receipt.path("receiptSha256").asText()), "RECEIPT_BINDING_MISMATCH");
            check(resultSha(mapper, out).equals(receipt.path("resultSha256").asText()), "MODULE_RESULT_BINDING_MISMATCH");
            if (!"ISSUED_FROM_ACTUAL_MATERIAL".equals(receipt.path("status").asText()))
                return unavailable(mapper, receipt.path("reasonCode").asText("SEQUENCE_MATERIAL_NOT_READY"));
            JsonNode context = out.at("/sourceProjection/evaluationContext");
            if (!context.isObject()) context = receipt.at("/payload/bindingContext");
            check(context instanceof ObjectNode, "EVALUATION_CONTEXT_REQUIRED");
            var expected = build(mapper, out, (ObjectNode) context, out.at("/evaluation/criterionId").asText());
            check(expected.equals(receipt.path("payload")), "PUBLIC_PROOF_PAYLOAD_MISMATCH");
            var projection = expected.deepCopy(); projection.remove("bindingContext");
            return projection;
        } catch (RuntimeException invalid) { return unavailable(mapper, safeReason(invalid)); }
    }

    private static ObjectNode build(ObjectMapper mapper, ObjectNode out, ObjectNode context, String criterion) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        check(Set.of(GeneralPm4Runtime.CRITERION, GeneralBp3Runtime.CRITERION).contains(criterion), "CRITERION_NOT_SUPPORTED");
        adapter.validateCompleteContext(context);
        var seq = out.path("sequenceMaterial"); var edit = seq.path("alleleAssociation");
        check("READY".equals(seq.path("status").asText()) && COMPLETE.equals(out.path("annotationCorrespondence").asText()), "CURRENT_SEQUENCE_COMPARISON_NOT_COMPLETE");
        check(out.path("contextId").equals(context.path("contextId")) && seq.path("contextId").equals(context.path("contextId")), "EVALUATION_CONTEXT_MISMATCH");
        check(context.at("/variant/evidenceQueryAllele").equals(seq.path("queryAllele")), "QUERY_ALLELE_MISMATCH");
        check(context.at("/transcript/accession").asText().concat(".").concat(context.at("/transcript/version").asText()).equals(seq.path("transcript").asText())
                && seq.path("transcript").equals(edit.path("transcript")), "TRANSCRIPT_IDENTITY_MISMATCH");
        check(criterion.equals(out.at("/evaluation/criterionId").asText()) && criterion.equals(out.at("/policy/criterionId").asText())
                && out.path("status").equals(out.at("/evaluation/evaluationResult/status"))
                && out.path("reasonCode").equals(out.at("/evaluation/evaluationResult/reasonCode")), "FINAL_RESULT_MISMATCH");
        JsonNode expectedSupply = GeneralBp3Runtime.CRITERION.equals(criterion) ? out.at("/policy/factSupply/sequenceSupply")
                : "VERIFIED_STOP_LOSS_SMALL_ALLELE".equals(seq.path("associationKind").asText())
                    ? out.at("/policy/stopLossSequenceSupply") : out.at("/policy/sequenceSupply");
        check(expectedSupply.isObject(), "POLICY_SEQUENCE_SUPPLY_REQUIRED");
        for (String field : List.of("method", "version", "manifestSha256", "associationKind", "associationVersion", "materialPurpose", "gencode", "sequenceCapability"))
            check(expectedSupply.path(field).equals(seq.path(field)), "POLICY_SEQUENCE_SUPPLY_MISMATCH");
        var materialBinding = seq.path("publicMaterialBinding");
        JsonNode modelManifest = hash(seq.path("manifestSha256")) ? seq.path("manifestSha256") : seq.at("/gencode/manifestSha256");
        check(hash(modelManifest) && hash(seq.path("modelCanonicalSha256"))
                && seq.path("modelCanonicalSha256").equals(materialBinding.path("modelCanonicalSha256"))
                && adapter.canonicalSha256(context).equals(materialBinding.path("contextSha256").asText()), "MODEL_CONTEXT_BINDING_MISMATCH");
        check(hash(materialBinding.path("modelSourceRecordsSha256")) && materialBinding.path("sources").isArray()
                && !materialBinding.path("sources").isEmpty() && !materialBinding.path("sourcesTruncated").asBoolean(true), "MODEL_SOURCE_BINDING_UNAVAILABLE");
        for (var source : materialBinding.path("sources")) check(hash(source.path("sha256")), "MODEL_SOURCE_PIN_REQUIRED");
        var verification = seq.path("verification"); var reference = verification.path("referenceIdentity");
        check(hash(reference.path("expectedSha256")) && reference.path("expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")), "REFERENCE_IDENTITY_MISMATCH");
        if (reference.has("observedSha256")) check(reference.path("expectedSha256").equals(reference.path("observedSha256")), "REFERENCE_OBSERVATION_MISMATCH");
        check(hash(verification.path("fullSplicedTranscriptSha256")) && hash(verification.path("cdsSha256"))
                && verification.path("cdsSha256").equals(edit.path("referenceCdsSha256")), "REFERENCE_CDS_BINDING_MISMATCH");
        for (String name : List.of("referenceCdsSha256", "alteredCdsSha256", "referenceProteinSha256", "alteredProteinSha256", "storedAnnotationSha256"))
            check(hash(edit.path(name)), "SEQUENCE_OR_ANNOTATION_PIN_REQUIRED");
        check(edit.path("hgvscSequenceEquivalent").asBoolean() && edit.path("hgvspSequenceEquivalent").asBoolean(), "STORED_ANNOTATION_COMPARISON_REQUIRED");
        var binding = mapper.createObjectNode();
        copyRequiredId(binding, out, "candidateId"); copyRequiredId(binding, out, "hypothesisId");
        binding.put("evaluationContextId", context.path("contextId").asText());
        JsonNode projection = out.path("sourceProjection");
        if (projection.isObject()) {
            var original = projection.path("originalContext"); var projected = projection.path("evaluationContext");
            adapter.validateVersionedContext(original);
            check("evidence-context-v3".equals(original.path("schemaVersion").asText()) && projected.equals(context), "SELECTED_CONTEXT_MISMATCH");
            var expected = ((ObjectNode) original).deepCopy(); expected.put("schemaVersion", "evidence-context-v2");
            expected.put("contextId", adapter.canonicalContextId(expected));
            check(expected.equals(context), "CONTEXT_PROJECTION_MISMATCH");
            var unsigned = ((ObjectNode) projection).deepCopy(); unsigned.remove("bindingSha256");
            check(adapter.canonicalSha256(unsigned).equals(projection.path("bindingSha256").asText())
                    && out.path("candidateId").equals(projection.path("candidateId")) && out.path("hypothesisId").equals(projection.path("hypothesisId")), "SOURCE_PROJECTION_BINDING_MISMATCH");
            binding.put("selectedContextId", original.path("contextId").asText()).put("projectionBindingSha256", projection.path("bindingSha256").asText())
                    .put("bindingMode", "SELECTED_V3_AND_EVALUATION_V2");
        } else binding.put("bindingMode", "DIRECT_EVALUATION_CONTEXT");
        binding.set("queryAllele", boundedAllele(mapper, seq.path("queryAllele")));
        binding.put("queryAlleleSha256", adapter.canonicalSha256(seq.path("queryAllele")));
        copyRequiredId(binding, seq, "transcript"); copyRequiredId(binding, edit, "protein");
        binding.set("modelCanonicalSha256", seq.path("modelCanonicalSha256"));
        binding.set("manifestSha256", modelManifest);
        binding.put("manifestOrigin", hash(seq.path("manifestSha256")) ? "PREBUILT_MODEL_MANIFEST" : "GENCODE_INDEX_MANIFEST");
        binding.set("modelSourceRecordsSha256", materialBinding.path("modelSourceRecordsSha256"));
        binding.set("referenceSha256", reference.path("expectedSha256"));
        if (hash(reference.path("indexSha256"))) binding.set("referenceIndexSha256", reference.path("indexSha256"));
        binding.set("storedAnnotationSha256", edit.path("storedAnnotationSha256"));
        binding.put("policySha256", adapter.canonicalSha256(out.path("policy"))).put("materialSha256", adapter.canonicalSha256(seq))
                .put("resultSha256", resultSha(mapper, out));
        var current = mapper.createObjectNode().put("schemaVersion", "protein-length-current-sequence-v1")
                .put("status", "READY").put("reasonCode", seq.path("reasonCode").asText()).put("associationKind", seq.path("associationKind").asText());
        current.set("binding", binding.deepCopy()); current.set("change", change(mapper, edit));
        var pins = current.putArray("sourcePins"); pins.addAll((ArrayNode) materialBinding.path("sources"));
        var refPin = pins.addObject().put("kind", "REFERENCE").put("sha256", reference.path("expectedSha256").asText());
        if (hash(reference.at("/acceptedChecksumSource/artifactSha256"))) refPin.set("receiptSourceSha256", reference.at("/acceptedChecksumSource/artifactSha256"));
        pins.addObject().put("kind", "SELECTED_TRANSCRIPT_ANNOTATION").put("sha256", edit.path("storedAnnotationSha256").asText());
        var adoption = mapper.createObjectNode().put("schemaVersion", "protein-length-adoption-proof-v1")
                .put("status", "UNAVAILABLE").put("reasonCode", "FINAL_RESULT_NOT_ADOPTED")
                .put("criterionId", criterion).put("clinicalValidationCompleted", false).put("createsScientificApproval", false)
                .put("rescoringPerformed", false).put("dataClass", context.path("dataClass").asText("UNDECLARED"));
        adoption.set("binding", binding.deepCopy()); adoption.set("result", result(mapper, out));
        adoption.set("change", current.path("change").deepCopy()); adoption.set("sourcePins", pins.deepCopy());
        if (Set.of("MET", "NOT_MET").contains(out.path("status").asText())) {
            try { adoption.set("rule", rule(mapper, out, criterion)); adoption.put("status", "AVAILABLE").put("reasonCode", "CURRENT_MATERIAL_AND_RULE_RESULT_BOUND"); }
            catch (RuntimeException incomplete) { adoption.put("reasonCode", safeReason(incomplete)); }
        } else adoption.put("unmetCondition", out.path("reasonCode").asText());
        var envelope = mapper.createObjectNode(); envelope.set("sequenceMaterial", current);
        envelope.put("annotationCorrespondence", COMPLETE); envelope.set("adoptionProof", adoption);
        // Direct module tests have no selected projection. This remains private and is removed on projection.
        if (!projection.isObject()) envelope.set("bindingContext", context.deepCopy());
        return envelope;
    }

    private static ObjectNode rule(ObjectMapper mapper, ObjectNode out, String criterion) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper); var seq = out.path("sequenceMaterial");
        var edit = seq.path("alleleAssociation"); var policy = out.path("policy"); var region = out.path("regionAssessment");
        String reason = out.path("reasonCode").asText(), status = out.path("status").asText();
        String strength = out.at("/evaluation/appliedStrength").asText();
        check("NOT_MET".equals(status) ? "NONE".equals(strength)
                : GeneralPm4Runtime.CRITERION.equals(criterion) ? "MODERATE".equals(strength) : "SUPPORTING".equals(strength), "RULE_RESULT_STRENGTH_MISMATCH");
        var rule = mapper.createObjectNode(); copyRequiredId(rule, policy, "policyId"); copyRequiredId(rule, policy, "policyVersion");
        rule.put("reasonCode", reason).put("status", status).put("policySha256", adapter.canonicalSha256(policy));
        if (GeneralPm4Runtime.CRITERION.equals(criterion) && "PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET".equals(reason)) {
            check("MET".equals(status) && "MODERATE".equals(out.at("/evaluation/appliedStrength").asText())
                    && policy.path("stopLossSupported").asBoolean() && edit.path("newTerminationResolved").asBoolean()
                    && edit.path("netAminoAcidChange").asInt() > 0 && edit.path("alteredStopCdsPosition").asInt() > edit.path("referenceStopCdsPosition").asInt()
                    && "VERIFIED_STOP_LOSS_SMALL_ALLELE".equals(edit.path("branch").asText()), "STOP_LOSS_RULE_WITNESS_REQUIRED");
            check(out.at("/stopLossAssessment/source").asText().equals("https://doi.org/10.1038/gim.2015.30")
                    && !out.at("/stopLossAssessment/regionAnnotationRequired").asBoolean(true), "STOP_LOSS_RULE_SOURCE_REQUIRED");
            rule.put("branch", "SEQUENCE_DERIVED_STOP_LOSS").put("basis", "REFERENCE_BASED_PREDICTED_EXTENSION_NOT_OBSERVED_RNA_OR_PROTEIN")
                    .put("source", out.at("/stopLossAssessment/source").asText()).put("regionAnnotationRequired", false);
            return rule;
        }
        JsonNode mapped = out.path("proteinAnnotations");
        boolean published = GeneralBp3Runtime.CRITERION.equals(criterion) && out.has("knowledgeSelection")
                && "READY".equals(out.at("/knowledgeSelection/status").asText());
        if (published) {
            var selection = out.path("knowledgeSelection"); var record = selection.path("record");
            check(hash(selection.at("/binding/rawSha256")) && selection.at("/binding/rawSha256").equals(policy.at("/publishedKnowledge/rawSha256"))
                    && hash(record.path("clauseSha256")) && record.path("interpretation").equals(region.path("selectedRule")), "PUBLISHED_RULE_SOURCE_BINDING_REQUIRED");
            rule.put("branch", "PUBLISHED_EXPERT_SPECIFICATION").set("sourcePin", selection.at("/binding/rawSha256").deepCopy());
            copyRequiredId(rule, record, "specificationKey"); copyHash(rule, record, "clauseSha256");
            check(hash(selection.at("/specification/source/rawSha256")), "PUBLISHED_ORIGINAL_SOURCE_PIN_REQUIRED");
            check("MET".equals(status) ? "QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION".equals(region.path("regionState").asText())
                    : "EXCLUDED".equals(region.path("regionState").asText()), "PUBLISHED_RULE_RESULT_MISMATCH");
            rule.put("selectedRuleSha256", adapter.canonicalSha256(region.path("selectedRule")))
                    .put("specificationSha256", adapter.canonicalSha256(selection.path("specification")))
                    .put("specificationRawSha256", selection.at("/specification/source/rawSha256").asText())
                    .put("regionState", region.path("regionState").asText());
            var interpretation = rule.putObject("conditions");
            for (String field : List.of("start", "end", "modelRegionStart", "modelRegionEnd"))
                if (region.at("/selectedRule/" + field).isIntegralNumber()) interpretation.set(field, region.at("/selectedRule/" + field));
            interpretation.put("qualificationBasis", "PUBLISHED_EXPERT_SPECIFICATION");
            return rule;
        }
        check("READY".equals(mapped.path("status").asText()) && mapped.path("modelCanonicalSha256").equals(seq.path("modelCanonicalSha256"))
                && mapped.path("sequenceMaterialSha256").asText().equals(adapter.canonicalSha256(seq))
                && mapped.path("contextId").equals(seq.path("contextId")) && mapped.path("queryAllele").equals(seq.path("queryAllele")), "PROTEIN_REGION_MATERIAL_BINDING_REQUIRED");
        JsonNode factPolicy = GeneralBp3Runtime.CRITERION.equals(criterion) ? policy.path("factSupply") : policy;
        check(hash(mapped.at("/source/rawSha256")) && (mapped.path("manifestSha256").equals(factPolicy.at("/uniprot/manifestSha256"))
                || factPolicy.has("onDemand")), "PROTEIN_REGION_SOURCE_PIN_REQUIRED");
        check(region.path("annotationSnapshotSha256").asText().equals(adapter.canonicalSha256(mapped)), "REGION_ANNOTATION_BINDING_MISMATCH");
        rule.set("queryRange", range(mapper, mapped.path("queryRange")));
        rule.put("annotationSnapshotSha256", adapter.canonicalSha256(mapped)).put("uniProtRawSha256", mapped.at("/source/rawSha256").asText());
        if (GeneralPm4Runtime.CRITERION.equals(criterion) && "PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET".equals(reason)) {
            check("NOT_MET".equals(status) && "NONE".equals(out.at("/evaluation/appliedStrength").asText()), "REPEAT_EXCLUSION_RESULT_MISMATCH");
            var observations = rule.putArray("repeatObservations");
            for (var feature : mapped.path("features")) {
                if (!"Repeat".equals(feature.path("type").asText()) || !overlap(feature, mapped.path("queryRange"))) continue;
                check(feature.path("sourceLocator").asText().matches("/features/[0-9]+") && hash(feature.path("featureJsonSha256"))
                        && CodingTranscriptMaterialSupply.sha(feature.path("rawFeatureJson").asText()).equals(feature.path("featureJsonSha256").asText()), "REPEAT_ORIGINAL_FEATURE_BINDING_REQUIRED");
                check(observations.size() < 16, "REPEAT_PROOF_LIMIT_EXCEEDED");
                var row = observations.addObject(); row.setAll(range(mapper, feature));
                row.set("sourceLocator", feature.path("sourceLocator")); row.set("featureJsonSha256", feature.path("featureJsonSha256"));
            }
            check(!observations.isEmpty(), "REPEAT_ORIGINAL_FEATURE_REQUIRED");
            rule.put("branch", "OBSERVED_REPEAT_OVERLAP_EXCLUDES_PM4"); return rule;
        }
        var combined = out.path("combinedRegions");
        check("READY".equals(combined.path("status").asText()) && hash(combined.path("sha256")), "COMBINED_SOURCE_REQUIRED");
        if (GeneralBp3Runtime.CRITERION.equals(criterion))
            check(region.path("combinedSnapshotSha256").asText().equals(adapter.canonicalSha256(combined)), "COMBINED_REGION_BINDING_MISMATCH");
        rule.set("combinedRawSha256", combined.path("sha256"));
        var qualified = region.path("qualifiedCoverage");
        if (GeneralPm4Runtime.CRITERION.equals(criterion) && out.at("/operationalNonRepeat/qualified").asBoolean()) {
            check("MET".equals(status) && Pm4OperationalNonRepeatPolicy.MODE.equals(policy.path("nonRepeatPolicy").asText())
                    && "COMPLETE".equals(out.at("/shortRepeats/status").asText()) && !out.at("/shortRepeats/candidateFound").asBoolean(true)
                    && combined.path("operationalSourceComplete").asBoolean() && combined.path("repeatOverlaps").isEmpty(), "OPERATIONAL_NON_REPEAT_WITNESS_REQUIRED");
            rule.put("branch", "OPERATIONAL_NON_REPEAT").put("basis", "OPERATIONAL_INFERENCE_NOT_ABSOLUTE_ABSENCE"); return rule;
        }
        check("READY".equals(qualified.path("status").asText()) && hash(qualified.path("acceptedRawSha256"))
                && qualified.path("acceptedRawSha256").equals(policy.at("/regionAssessment/assertionsSha256")), "REVIEWED_REGION_SOURCE_BINDING_REQUIRED");
        String state = region.path("regionState").asText();
        check(GeneralPm4Runtime.CRITERION.equals(criterion) ? "MET".equals(status) && "NON_REPEAT_SUPPORTED".equals(state)
                : "MET".equals(status) ? "QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION".equals(state) : "EXCLUDED".equals(state), "REVIEWED_REGION_CONDITION_REQUIRED");
        rule.put("branch", GeneralPm4Runtime.CRITERION.equals(criterion) ? "REVIEWED_NON_REPEAT" : "REVIEWED_REPEAT_AND_FUNCTION")
                .set("acceptedRawSha256", qualified.path("acceptedRawSha256"));
        rule.put("qualificationSha256", adapter.canonicalSha256(qualified)).put("regionState", region.path("regionState").asText());
        if (qualified.path("recordLocator").asText().matches("/records/[0-9]+")) rule.set("recordLocator", qualified.path("recordLocator"));
        var conditions = rule.putArray("coverageIntervals");
        check(qualified.path("intervals").isArray() && !qualified.path("intervals").isEmpty() && qualified.path("intervals").size() <= 16, "QUALIFIED_COVERAGE_PROOF_LIMIT_OR_MISSING");
        for (var interval : qualified.path("intervals")) {
            var condition = conditions.addObject(); condition.setAll(range(mapper, interval));
            if (Set.of("REPEAT", "NON_REPEAT", "UNKNOWN").contains(interval.path("repeatStatus").asText())) condition.set("repeatStatus", interval.path("repeatStatus"));
            if (Set.of("NO_KNOWN_FUNCTION", "KNOWN_FUNCTION", "UNKNOWN").contains(interval.path("functionalStatus").asText())) condition.set("functionalStatus", interval.path("functionalStatus"));
            condition.put("sourceLocatorsSha256", adapter.canonicalSha256(interval.path("sourceLocators")));
        }
        return rule;
    }

    private static ObjectNode change(ObjectMapper mapper, JsonNode edit) {
        var out = mapper.createObjectNode();
        for (String field : List.of("netNucleotideChange", "netAminoAcidChange", "referenceCdsLength", "alteredCdsLength", "referenceProteinLength", "alteredProteinLength",
                "referenceEditStart0", "referenceEditEnd0", "referenceStopCdsPosition", "alteredStopCdsPosition", "availableThreePrimeBases", "inspectedThreePrimeBases")) {
            if (edit.path(field).isIntegralNumber() && edit.path(field).canConvertToInt()) out.set(field, edit.path(field));
        }
        for (String field : List.of("referenceCdsSha256", "alteredCdsSha256", "referenceProteinSha256", "alteredProteinSha256", "threePrimeWindowSha256")) copyHash(out, edit, field);
        if (edit.path("newTerminationResolved").isBoolean()) out.set("newTerminationResolved", edit.path("newTerminationResolved"));
        if (edit.at("/equivalentEditEnvelope/proteinStart").isIntegralNumber()) {
            var range = out.putObject("queryRange").put("coordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE");
            range.set("start", edit.at("/equivalentEditEnvelope/proteinStart")); range.set("end", edit.at("/equivalentEditEnvelope/proteinEnd"));
        }
        out.put("editCoordinateSystem", "REFERENCE_CDS_ZERO_BASED_HALF_OPEN");
        if ("VERIFIED_STOP_LOSS_SMALL_ALLELE".equals(edit.path("branch").asText()))
            out.put("netNucleotideChangeMeaning", "EDIT_DELTA_EXCLUDING_PREDICTED_THREE_PRIME_EXTENSION");
        return out;
    }
    private static ObjectNode result(ObjectMapper mapper, JsonNode out) {
        return mapper.createObjectNode().put("status", out.path("status").asText()).put("reasonCode", out.path("reasonCode").asText())
                .put("appliedStrength", out.at("/evaluation/appliedStrength").asText());
    }
    private static ObjectNode range(ObjectMapper mapper, JsonNode raw) {
        check(raw.path("start").isIntegralNumber() && raw.path("end").isIntegralNumber() && raw.path("start").asInt() > 0
                && raw.path("end").asInt() >= raw.path("start").asInt(), "PROTEIN_RANGE_REQUIRED");
        return mapper.createObjectNode().put("start", raw.path("start").asInt()).put("end", raw.path("end").asInt())
                .put("coordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE");
    }
    private static boolean overlap(JsonNode a, JsonNode b) { return a.path("start").asInt() <= b.path("end").asInt() && a.path("end").asInt() >= b.path("start").asInt(); }
    private static ObjectNode boundedAllele(ObjectMapper mapper, JsonNode raw) {
        check(raw.path("reference").asText().matches("[ACGT]{1,50}") && raw.path("alternate").asText().matches("[ACGT]{1,50}")
                && raw.path("position").isIntegralNumber() && raw.path("position").canConvertToInt() && raw.path("position").asInt() > 0, "QUERY_ALLELE_OUTSIDE_PUBLIC_SCOPE");
        var out = mapper.createObjectNode(); for (String field : List.of("assembly", "contig")) copyRequiredId(out, raw, field);
        for (String field : List.of("position", "reference", "alternate")) out.set(field, raw.path(field)); return out;
    }
    private static ObjectNode unavailable(ObjectMapper mapper, String reason) {
        String safe = reason.matches("[A-Z0-9_]{1,120}") ? reason : "PUBLIC_PROOF_UNAVAILABLE";
        var out = mapper.createObjectNode(); out.putObject("sequenceMaterial").put("schemaVersion", "protein-length-current-sequence-v1")
                .put("status", "UNAVAILABLE").put("reasonCode", safe);
        out.put("annotationCorrespondence", "UNAVAILABLE"); out.putObject("adoptionProof").put("schemaVersion", "protein-length-adoption-proof-v1")
                .put("status", "UNAVAILABLE").put("reasonCode", safe).put("clinicalValidationCompleted", false)
                .put("createsScientificApproval", false).put("rescoringPerformed", false); return out;
    }
    private static String resultSha(ObjectMapper mapper, ObjectNode out) {
        var body = out.deepCopy(); body.remove(List.of(RECEIPT, "publicSummary")); return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(body);
    }
    private static boolean hash(JsonNode node) { return node.isTextual() && node.asText().matches("[0-9a-f]{64}"); }
    private static void copyHash(ObjectNode out, JsonNode source, String field) { if (hash(source.path(field))) out.set(field, source.path(field)); }
    private static void copyRequiredId(ObjectNode out, JsonNode source, String field) { check(id(source.path(field)), "PUBLIC_IDENTIFIER_REQUIRED"); out.set(field, source.path(field)); }
    private static void copyId(ObjectNode out, JsonNode source, String field) { if (id(source.path(field))) out.set(field, source.path(field)); }
    private static boolean id(JsonNode node) { return node.isTextual() && node.asText().matches("[A-Za-z0-9_.:@+-]{1,160}"); }
    private static void copyUrl(ObjectNode out, JsonNode source, String field) {
        try { var uri = URI.create(source.path(field).asText()); if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.toString().length() <= 512) out.put(field, uri.toString()); }
        catch (IllegalArgumentException invalid) { /* A hash pin remains usable without exporting an unsafe locator. */ }
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
    private static String safeReason(RuntimeException error) { var message = error.getMessage(); return message != null && message.matches("[A-Z0-9_]{1,120}") ? message : "PUBLIC_PROOF_UNAVAILABLE"; }
}
