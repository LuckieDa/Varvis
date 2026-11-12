package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in E3-K1A public-resource smoke. It produces comparator input only and
 * never evaluates PS1, PM5, or any classification.
 */
class ClinVarConditionAwareComparatorLocalIntegrationTest {

    private static final String RUN_PROPERTY = "varvis.e3k1a.runtime-smoke";
    private static final String K1B_RUN_PROPERTY = "varvis.e3k1b.runtime-smoke";
    private static final String CLINVAR_RESOURCE_ID = "CLINVAR_2026_03_21_GRCH38";
    private static final String VEP_RESOURCE_ID = "VEP_116_GRCH38";
    private static final String TRANSCRIPT = "ENST00000646891";
    private static final String VERSIONED_TRANSCRIPT = TRANSCRIPT + ".2";
    private static final String K1B_SELECTION_ID = "cspec-selection-sha256-"
            + "d".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @TempDir
    Path tempDirectory;

    @Test
    @EnabledIfSystemProperty(named = RUN_PROPERTY, matches = "true")
    void publicBrafComparatorTraversesBoundedClinVarOneVepBatchAndConditionSidecar()
            throws Exception {
        VariantAnalysisProperties properties = localProperties();
        enableOnlyClinVarAndVep(properties);

        JsonNode resourceManifest = mapper.readTree(requiredResourceManifest().toFile());
        RuntimeEvidenceV2ProviderWiring wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding clinVarBinding =
                wiring.bind(resourceManifest, CLINVAR_RESOURCE_ID);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding vepBinding =
                wiring.bind(resourceManifest, VEP_RESOURCE_ID);

        Path sidecarPath = configuredFile("varvis.e3k1a.sidecar");
        JsonNode sidecarManifest = mapper.readTree(
                configuredFile("varvis.e3k1a.sidecar-manifest").toFile());
        verifySidecarIdentity(sidecarPath, sidecarManifest, clinVarBinding);
        ClinVarComparatorSidecar sidecar = new ClinVarComparatorSidecar(
                mapper,
                sidecarPath,
                requiredText(sidecarManifest, "resourceId"),
                requiredText(sidecarManifest, "resourceVersion"),
                requiredText(sidecarManifest, "identityFingerprintSha256"));

        NormalizedVariantKey targetKey = RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "7", 140753336, "A", "T");
        ObjectNode context = brafContext(targetKey, properties.getVep().getVersion());
        VariantInterpretation target = RuntimeEvidenceV2ProviderWiringTest.variant(targetKey);
        AnalysisContext analysisContext = RuntimeEvidenceV2ProviderWiringTest.analysisContext(targetKey);
        TranscriptConsequence selectedTarget = selectedBrafV600e(properties.getVep().getVersion());
        CountingVepProvider vep = new CountingVepProvider(properties);
        ClinVarTabixProvider clinVar = new ClinVarTabixProvider(properties);
        EvidenceItem targetClinVar = clinVar.annotate(List.of(target), analysisContext)
                .get(targetKey).get(0);
        assertEquals("CONFLICTING", targetClinVar.getEffectiveStatus());
        assertEquals("13961", targetClinVar.getDetails().get("variationId"));
        assertEquals("CONFLICTING_INTERPRETATIONS",
                targetClinVar.getDetails().get("conflictState"));
        assertEquals(17, ((List<?>) targetClinVar.getDetails().get("conditions")).size());
        ObjectNode targetClinVarAtomic = wiring.annotateVariantProvider(
                context, clinVar, target, analysisContext, clinVarBinding).get(0);
        assertEquals("SUCCEEDED",
                targetClinVarAtomic.path("resourceExecution").path("status").asText());
        assertEquals("HIT", targetClinVarAtomic.path("queryOutcome").path("status").asText());
        ClinVarConditionAwareComparatorInput input = new ClinVarConditionAwareComparatorInput(
                clinVar, vep, sidecar,
                new VepProteinRelationClassifier());

        ClinVarConditionAwareComparatorInput.Result result = input.build(
                context, target, selectedTarget);

        assertEquals("READY", result.getStatus(), result.getReasonCode());
        assertEquals("7:140753335-140753337", result.getDiscovery().getQueriedRegion());
        assertEquals(1, vep.batchCalls, "The bounded candidate set must use one VEP batch call");
        ClinVarConditionAwareComparatorInput.ComparatorRecord comparator = result.getComparators()
                .stream()
                .filter(record -> "40389".equals(record.getVariationId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing public BRAF V600G comparator"));
        assertEquals(RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "7", 140753336, "A", "C"), comparator.getExactAllele());
        assertEquals("SAME_RESIDUE_DIFFERENT_AMINO_ACID", comparator.getProteinRelation());
        assertEquals(VERSIONED_TRANSCRIPT,
                comparator.getComparatorProteinChange().getTranscriptId());
        assertEquals("600", comparator.getComparatorProteinChange().getProteinPosition());
        assertEquals("V", comparator.getComparatorProteinChange().getReferenceAminoAcid());
        assertEquals("G", comparator.getComparatorProteinChange().getAlternateAminoAcid());

        JsonNode assertion = completeAssertion(comparator.getSubmissions());
        assertNotNull(assertion,
                "VariationID 40389 must retain one complete condition-linked TSV assertion");
        assertTrue(hasText(assertion.path("scv").asText(null)));
        assertTrue(hasText(assertion.path("classification").asText(null)));
        assertTrue(hasText(assertion.path("reviewStatus").asText(null)));
        assertTrue(hasText(assertion.path("dateLastEvaluated").asText(null)));
        assertTrue(hasCondition(assertion.path("condition")));

        ObjectNode smoke = smoke(
                clinVarBinding, vepBinding, sidecarManifest, targetKey, targetClinVar,
                targetClinVarAtomic, result, comparator, assertion);
        String serialized = mapper.writeValueAsString(smoke);
        assertFalse(serialized.contains(sidecarPath.toAbsolutePath().normalize().toString()));
        assertFalse(serialized.contains("F:\\"));
        assertFalse(serialized.contains("D:\\"));
        assertFalse(serialized.contains("file:/"));
        assertFalse(serialized.contains("criterion-evaluation-v2"));
        assertFalse(smoke.path("criterionEvaluationProduced").asBoolean(true));
        assertFalse(smoke.path("ps1Implemented").asBoolean(true));
        assertFalse(smoke.path("pm5Implemented").asBoolean(true));
        assertFalse(smoke.path("clinicalValidation").asBoolean(true));
        writeSmoke(smoke);
    }

    @Test
    @EnabledIfSystemProperty(named = K1B_RUN_PROPERTY, matches = "true")
    void publicBrafComparatorProducesPm5ModerateThroughTheRuntimeV2Chain()
            throws Exception {
        VariantAnalysisProperties properties = localProperties();
        enableOnlyClinVarAndVep(properties);

        JsonNode resourceManifest = mapper.readTree(requiredResourceManifest().toFile());
        RuntimeEvidenceV2ProviderWiring wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding clinVarBinding =
                wiring.bind(resourceManifest, CLINVAR_RESOURCE_ID);
        Path sidecarPath = configuredFile("varvis.e3k1a.sidecar");
        JsonNode sidecarManifest = mapper.readTree(
                configuredFile("varvis.e3k1a.sidecar-manifest").toFile());
        verifySidecarIdentity(sidecarPath, sidecarManifest, clinVarBinding);
        ClinVarComparatorSidecar sidecar = new ClinVarComparatorSidecar(
                mapper,
                sidecarPath,
                requiredText(sidecarManifest, "resourceId"),
                requiredText(sidecarManifest, "resourceVersion"),
                requiredText(sidecarManifest, "identityFingerprintSha256"));

        NormalizedVariantKey targetKey = RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "7", 140753336, "A", "T");
        ObjectNode context = brafK1bContext(targetKey, properties.getVep().getVersion());
        VariantInterpretation target = RuntimeEvidenceV2ProviderWiringTest.variant(targetKey);
        TranscriptConsequence selectedTarget = selectedBrafV600e(properties.getVep().getVersion());
        CountingVepProvider vep = new CountingVepProvider(properties);
        ClinVarConditionAwareComparatorInput input = new ClinVarConditionAwareComparatorInput(
                new ClinVarTabixProvider(properties), vep, sidecar,
                new VepProteinRelationClassifier());
        ClinVarConditionAwareComparatorInput.Result comparatorInput = input.build(
                context, target, selectedTarget);

        assertEquals("READY", comparatorInput.getStatus(), comparatorInput.getReasonCode());
        assertEquals(1, vep.batchCalls);
        ClinVarConditionAwareComparatorInput.ComparatorRecord comparator = comparatorInput
                .getComparators().stream()
                .filter(record -> "40389".equals(record.getVariationId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing public BRAF V600G comparator"));
        assertEquals("SAME_RESIDUE_DIFFERENT_AMINO_ACID", comparator.getProteinRelation());

        ObjectNode profile = ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper);
        List<ObjectNode> atomics = wiring.projectClinVarComparatorEvidence(
                context, comparatorInput, profile);
        ObjectNode expertPanelAtomic = atomics.stream()
                .filter(value -> "SCV001424737.1".equals(
                        measurement(value, "clinvar.assertion.scv").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing SCV001424737.1 comparator atomic"));
        assertEquals("CLINICAL_DATABASE", expertPanelAtomic.path("evidenceType").asText());
        assertEquals("HIT", expertPanelAtomic.path("queryOutcome").path("status").asText());
        assertEquals("40389", measurement(expertPanelAtomic,
                "clinvar.comparator.variationId").asText());

        List<ObjectNode> evaluations = new ClinVarPs1Pm5CriterionEvaluator(mapper).evaluate(
                context, atomics, K1B_SELECTION_ID);
        ObjectNode ps1 = criterion(evaluations, "ACMG_AMP_2015:PS1");
        ObjectNode pm5 = criterion(evaluations, "ACMG_AMP_2015:PM5");
        assertEquals("NOT_MET", ps1.path("evaluationResult").path("status").asText());
        assertEquals("NONE", ps1.path("appliedStrength").asText());
        assertEquals("MET", pm5.path("evaluationResult").path("status").asText());
        assertEquals("MODERATE", pm5.path("baseStrength").asText());
        assertEquals("MODERATE", pm5.path("appliedStrength").asText());

        ObjectNode smoke = k1bSmoke(
                context, profile, comparatorInput, comparator, expertPanelAtomic, ps1, pm5,
                vep.batchCalls);
        String serialized = mapper.writeValueAsString(smoke);
        assertFalse(serialized.contains(sidecarPath.toAbsolutePath().normalize().toString()));
        assertFalse(serialized.contains("F:\\"));
        assertFalse(serialized.contains("D:\\"));
        assertFalse(serialized.contains("file:/"));
        assertFalse(smoke.path("clinicalValidation").asBoolean(true));
        assertFalse(smoke.path("patientFlowEnabled").asBoolean(true));
        assertFalse(smoke.path("classificationEnabled").asBoolean(true));
        writeK1bSmoke(smoke);
    }

    private ObjectNode smoke(RuntimeEvidenceV2ProviderWiring.ResourceBinding clinVarBinding,
                             RuntimeEvidenceV2ProviderWiring.ResourceBinding vepBinding,
                             JsonNode sidecarManifest,
                             NormalizedVariantKey target,
                             EvidenceItem targetClinVar,
                             ObjectNode targetClinVarAtomic,
                             ClinVarConditionAwareComparatorInput.Result result,
                             ClinVarConditionAwareComparatorInput.ComparatorRecord comparator,
                             JsonNode assertion) {
        ObjectNode smoke = mapper.createObjectNode();
        smoke.put("schemaVersion", "e3-k1a-clinvar-condition-aware-comparator-smoke-v1");
        smoke.put("status", "READY_FOR_E3_K1B");
        smoke.put("resourceDataClass", "PUBLIC");
        smoke.put("contextDataClass", "SYNTHETIC");
        smoke.put("clinicalValidation", false);
        smoke.put("patientFlowEnabled", false);
        smoke.put("criterionEvaluationProduced", false);
        smoke.put("ps1Implemented", false);
        smoke.put("pm5Implemented", false);

        ObjectNode resources = smoke.putObject("resources");
        resource(resources.putObject("clinVarVcf"), clinVarBinding);
        resource(resources.putObject("vep"), vepBinding);
        ObjectNode sidecar = resources.putObject("conditionAwareSidecar");
        sidecar.put("resourceId", requiredText(sidecarManifest, "resourceId"));
        sidecar.put("resourceVersion", requiredText(sidecarManifest, "resourceVersion"));
        sidecar.put("identityFingerprintSha256",
                requiredText(sidecarManifest, "identityFingerprintSha256"));
        sidecar.put("logicalName", requiredText(sidecarManifest.path("output"), "logicalName"));
        sidecar.put("sourceRelease", requiredText(sidecarManifest.path("identity"), "sourceRelease"));

        ObjectNode fixedPoint = smoke.putObject("fixedPoint");
        allele(fixedPoint.putObject("targetAllele"), target);
        fixedPoint.put("selectedTranscript", VERSIONED_TRANSCRIPT);
        fixedPoint.put("targetProteinChange", "V600E");
        fixedPoint.put("boundedClinVarRegion", result.getDiscovery().getQueriedRegion());
        fixedPoint.put("vepBatchInvocationCount", 1);
        ObjectNode targetRecord = fixedPoint.putObject("targetClinVarExactRecord");
        targetRecord.put("queryOutcome",
                targetClinVarAtomic.path("queryOutcome").path("status").asText());
        targetRecord.put("variationId", String.valueOf(
                targetClinVar.getDetails().get("variationId")));
        targetRecord.put("conditionGroupCount",
                ((List<?>) targetClinVar.getDetails().get("conditions")).size());
        targetRecord.put("conflictState", String.valueOf(
                targetClinVar.getDetails().get("conflictState")));
        ObjectNode record = fixedPoint.putObject("comparator");
        record.put("targetContextId", comparator.getTargetContextId());
        allele(record.putObject("exactAllele"), comparator.getExactAllele());
        record.put("variationId", comparator.getVariationId());
        record.put("proteinRelation", comparator.getProteinRelation());
        record.put("selectedTranscript", comparator.getComparatorProteinChange().getTranscriptId());
        record.put("proteinPosition", comparator.getComparatorProteinChange().getProteinPosition());
        record.put("referenceAminoAcid", comparator.getComparatorProteinChange().getReferenceAminoAcid());
        record.put("alternateAminoAcid", comparator.getComparatorProteinChange().getAlternateAminoAcid());
        record.put("conflictState", comparator.getConflictState());
        ArrayNode contributors = record.putArray("contributingSubmissionIdentifiers");
        comparator.getContributingSubmissionIdentifiers().forEach(contributors::add);
        ObjectNode submittedAssertion = record.putObject("submittedAssertion");
        submittedAssertion.put("scv", assertion.path("scv").asText());
        submittedAssertion.put("classification", assertion.path("classification").asText());
        submittedAssertion.put("reviewStatus", assertion.path("reviewStatus").asText());
        submittedAssertion.put("dateLastEvaluated", assertion.path("dateLastEvaluated").asText());
        submittedAssertion.set("condition", assertion.path("condition").deepCopy());
        submittedAssertion.set("sourceLocator", assertion.path("sourceLocator").deepCopy());
        return smoke;
    }

    private ObjectNode k1bSmoke(
            ObjectNode context,
            ObjectNode profile,
            ClinVarConditionAwareComparatorInput.Result input,
            ClinVarConditionAwareComparatorInput.ComparatorRecord comparator,
            ObjectNode atomic,
            ObjectNode ps1,
            ObjectNode pm5,
            int vepBatchCalls) {
        ObjectNode smoke = mapper.createObjectNode();
        smoke.put("schemaVersion", "e3-k1b-clinvar-ps1-pm5-smoke-v1");
        smoke.put("status", "E3_K1B_COMPLETED_PAUSED");
        smoke.put("resourceDataClass", "PUBLIC");
        smoke.put("contextDataClass", "SYNTHETIC");
        smoke.put("clinicalValidation", false);
        smoke.put("patientFlowEnabled", false);
        smoke.put("classificationEnabled", false);
        smoke.put("criterionEvaluationProduced", true);
        ArrayNode runtimeChain = smoke.putArray("runtimeChain");
        runtimeChain.add("ClinVarTabixProvider");
        runtimeChain.add("ClinVarConditionAwareComparatorInput");
        runtimeChain.add("VepTranscriptAnnotationProvider");
        runtimeChain.add("RuntimeEvidenceV2ProviderWiring");
        runtimeChain.add("RuntimeEvidenceV2Adapter");
        runtimeChain.add("ClinVarPs1Pm5CriterionEvaluator");

        ObjectNode profileBinding = smoke.putObject("profile");
        profileBinding.put("profileId", profile.path("profileId").asText());
        profileBinding.put("profileVersion", profile.path("profileVersion").asText());
        profileBinding.put("profileSha256", adapter.canonicalSha256(profile));
        profileBinding.set("resources", profile.path("resources").deepCopy());

        ObjectNode fixedPoint = smoke.putObject("fixedPoint");
        fixedPoint.put("contextId", context.path("contextId").asText());
        fixedPoint.put("mondoId", context.path("disease").path("mondoId").asText());
        fixedPoint.set("targetQueryAllele",
                context.path("variant").path("evidenceQueryAllele").deepCopy());
        fixedPoint.put("boundedClinVarRegion", input.getDiscovery().getQueriedRegion());
        fixedPoint.put("vepBatchInvocationCount", vepBatchCalls);
        ObjectNode comparatorValue = fixedPoint.putObject("comparator");
        comparatorValue.put("variationId", comparator.getVariationId());
        allele(comparatorValue.putObject("exactAllele"), comparator.getExactAllele());
        comparatorValue.put("proteinRelation", comparator.getProteinRelation());
        comparatorValue.put("selectedTranscript",
                comparator.getComparatorProteinChange().getTranscriptId());
        comparatorValue.put("proteinPosition",
                comparator.getComparatorProteinChange().getProteinPosition());
        comparatorValue.put("referenceAminoAcid",
                comparator.getComparatorProteinChange().getReferenceAminoAcid());
        comparatorValue.put("alternateAminoAcid",
                comparator.getComparatorProteinChange().getAlternateAminoAcid());

        ObjectNode atomicValue = fixedPoint.putObject("conditionLinkedAtomicEvidence");
        atomicValue.put("evidenceId", atomic.path("evidenceId").asText());
        atomicValue.put("evidenceType", atomic.path("evidenceType").asText());
        atomicValue.put("queryOutcome", atomic.path("queryOutcome").path("status").asText());
        atomicValue.put("sourceId", atomic.path("source").path("sourceId").asText());
        atomicValue.put("scv", measurement(atomic, "clinvar.assertion.scv").asText());
        atomicValue.put("classification",
                measurement(atomic, "clinvar.assertion.classification").asText());
        atomicValue.put("reviewStatus",
                measurement(atomic, "clinvar.assertion.reviewStatus").asText());
        atomicValue.put("dateLastEvaluated",
                measurement(atomic, "clinvar.assertion.dateLastEvaluated").asText());
        atomicValue.put("conditionSubmittedRaw",
                measurement(atomic, "clinvar.assertion.condition.submittedRaw").asText());
        atomicValue.set("sourceRecords", atomic.path("source").path("sourceRecords").deepCopy());
        atomicValue.set("resourceLocators",
                atomic.path("source").path("sourceLocators").deepCopy());

        ArrayNode criteria = fixedPoint.putArray("criterionEvaluations");
        criterionSummary(criteria.addObject(), ps1);
        criterionSummary(criteria.addObject(), pm5);
        smoke.set("context", context.deepCopy());
        smoke.set("atomicEvidence", atomic.deepCopy());
        ArrayNode contractEvaluations = smoke.putArray("criterionEvaluations");
        contractEvaluations.add(ps1.deepCopy());
        contractEvaluations.add(pm5.deepCopy());
        ObjectNode boundary = smoke.putObject("boundary");
        boundary.put("ps1RealComparatorSearched", false);
        boundary.put("sidecarRebuilt", false);
        boundary.put("wholeClinVarScanned", false);
        boundary.put("patientDataUsed", false);
        boundary.put("classificationPerformed", false);
        return smoke;
    }

    private void criterionSummary(ObjectNode target, ObjectNode evaluation) {
        target.put("criterionId", evaluation.path("criterionId").asText());
        target.put("evaluationId", evaluation.path("evaluationId").asText());
        target.put("status", evaluation.path("evaluationResult").path("status").asText());
        target.put("baseStrength", evaluation.path("baseStrength").asText());
        target.put("appliedStrength", evaluation.path("appliedStrength").asText());
        target.set("evidenceIds", evaluation.path("evidenceIds").deepCopy());
    }

    private ObjectNode criterion(List<ObjectNode> evaluations, String criterionId) {
        return evaluations.stream()
                .filter(value -> criterionId.equals(value.path("criterionId").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing criterion " + criterionId));
    }

    private JsonNode measurement(ObjectNode evidence, String metricId) {
        for (JsonNode value : evidence.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) {
                return value.path("rawValue");
            }
        }
        return mapper.missingNode();
    }

    private void verifySidecarIdentity(
            Path sidecar,
            JsonNode manifest,
            RuntimeEvidenceV2ProviderWiring.ResourceBinding clinVarBinding) throws Exception {
        assertEquals("CLINVAR_CONDITION_AWARE_COMPARATOR_SIDECAR_V1",
                manifest.path("identity").path("format").asText());
        assertEquals("GRCh38", manifest.path("identity").path("assembly").asText());
        assertEquals(clinVarBinding.getResourceId(),
                manifest.path("baseClinvarResource").path("resourceId").asText());
        assertEquals(clinVarBinding.getResourceVersion(),
                manifest.path("baseClinvarResource").path("resourceVersion").asText());
        assertEquals(clinVarBinding.getIdentityFingerprintSha256(),
                manifest.path("baseClinvarResource").path("identityFingerprintSha256").asText());
        assertEquals(sidecar.getFileName().toString(),
                manifest.path("output").path("logicalName").asText());
        assertEquals(Files.size(sidecar), manifest.path("output").path("byteLength").asLong(-1));
        assertEquals(sha256(sidecar), manifest.path("output").path("sha256").asText());
        Path index = sidecar.resolveSibling(sidecar.getFileName() + ".tbi");
        assertTrue(Files.isRegularFile(index));
        assertEquals(index.getFileName().toString(),
                manifest.path("output").path("indexLogicalName").asText());
        assertEquals(Files.size(index), manifest.path("output").path("indexByteLength").asLong(-1));
        assertEquals(sha256(index), manifest.path("output").path("indexSha256").asText());
        String serialized = mapper.writeValueAsString(manifest);
        assertFalse(serialized.contains(sidecar.toAbsolutePath().normalize().toString()));
        assertFalse(serialized.contains("F:\\"));
        assertFalse(serialized.contains("D:\\"));
        assertFalse(serialized.contains("file:/"));
    }

    private JsonNode completeAssertion(ArrayNode submissions) {
        for (JsonNode assertion : submissions) {
            if (hasText(assertion.path("scv").asText(null))
                    && hasText(assertion.path("classification").asText(null))
                    && hasText(assertion.path("reviewStatus").asText(null))
                    && hasText(assertion.path("dateLastEvaluated").asText(null))
                    && hasCondition(assertion.path("condition"))) {
                return assertion;
            }
        }
        return null;
    }

    private boolean hasCondition(JsonNode condition) {
        return condition != null && (hasText(condition.path("submittedRaw").asText(null))
                || hasText(condition.path("reportedRaw").asText(null)));
    }

    private TranscriptConsequence selectedBrafV600e(String vepVersion) {
        return TranscriptConsequence.ann(
                "BRAF", "ENSG00000157764", VERSIONED_TRANSCRIPT, "Ensembl",
                "missense_variant", "MODERATE",
                VERSIONED_TRANSCRIPT + ":c.1799T>A",
                "ENSP00000493543.1:p.Val600Glu", "15/18", null,
                "600", "V/E", "gTg/gAg", Instant.EPOCH)
                .withAnnotationMetadata("VEP_CACHED", "COMPLETED", vepVersion, Instant.EPOCH);
    }

    private ObjectNode brafContext(NormalizedVariantKey key, String vepVersion) throws IOException {
        ObjectNode context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
                mapper, adapter, key, TRANSCRIPT);
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:1097").put("symbol", "BRAF");
        ((ObjectNode) context.path("transcript"))
                .put("version", 2)
                .put("selectionPolicy", "E3_K1A_SYNTHETIC_PINNED")
                .put("annotationSourceVersion", vepVersion);
        updateContextId(context);
        return context;
    }

    private ObjectNode brafK1bContext(NormalizedVariantKey key, String vepVersion)
            throws IOException {
        ObjectNode context = brafContext(key, vepVersion);
        ((ObjectNode) context.path("disease"))
                .put("mondoId", "MONDO:0021060")
                .put("label", "Synthetic RASopathy context");
        ((ObjectNode) context.path("transcript"))
                .put("selectionPolicy", "E3_K1B_SYNTHETIC_PINNED");
        updateContextId(context);
        return context;
    }

    private void updateContextId(ObjectNode context) {
        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", context.path("scope").deepCopy());
        projection.put("alleleId", context.path("variant").path("alleleId").asText());
        projection.put("hgncId", context.path("gene").path("hgncId").asText());
        projection.put("mondoId", context.path("disease").path("mondoId").asText());
        projection.put("moi", context.path("moi").asText());
        ObjectNode transcript = projection.putObject("transcript");
        transcript.put("accession", context.path("transcript").path("accession").asText());
        transcript.set("version", context.path("transcript").path("version").deepCopy());
        projection.put("patientContextId",
                context.path("patientContext").path("patientContextId").asText());
        projection.set("familyContextId",
                context.path("patientContext").path("familyContextId").deepCopy());
        context.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
    }

    private VariantAnalysisProperties localProperties() throws IOException {
        Path localConfig = requiredPath(
                Paths.get("config", "variant-analysis-local.properties"),
                Paths.get("varvis-server", "config", "variant-analysis-local.properties"));
        Properties local = new Properties();
        try (Reader reader = Files.newBufferedReader(localConfig, StandardCharsets.UTF_8)) {
            local.load(reader);
        }
        String resourceRoot = firstText(
                System.getProperty("varvis.e3k1a.resource-root"),
                System.getProperty("varvis.e3k0.resource-root"),
                System.getenv("VARVIS_RESOURCE_ROOT"));
        assertNotNull(resourceRoot,
                "Set varvis.e3k1a.resource-root or VARVIS_RESOURCE_ROOT for the opt-in smoke");
        String projectRoot = firstText(
                System.getProperty("varvis.e3k1a.project-root"),
                System.getProperty("varvis.e3k0.project-root"),
                System.getenv("VARVIS_PROJECT_ROOT"), resourceRoot);
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new PropertiesPropertySource("e3k1a-ignored-local-config", local));
        Map<String, Object> roots = new LinkedHashMap<String, Object>();
        roots.put("VARVIS_RESOURCE_ROOT", resourceRoot);
        roots.put("VARVIS_PROJECT_ROOT", projectRoot);
        environment.getPropertySources().addFirst(new MapPropertySource("e3k1a-local-roots", roots));
        return Binder.get(environment)
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class))
                .orElseThrow(() -> new IllegalStateException("variant-analysis local config did not bind"));
    }

    private void enableOnlyClinVarAndVep(VariantAnalysisProperties properties) {
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setAllowVcfInfoFallback(false);
        properties.getClinVar().getCompactIndex().setEnabled(false);
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        properties.getVep().setLofteeEnabled(false);
        properties.getVep().setTempDir(tempDirectory.resolve("vep").toString());
        properties.getVep().setCleanupTemp(true);
        VariantAnalysisProperties.Vep.ResourceProfile grch38 = properties.getVep()
                .getResources().values().stream()
                .filter(profile -> "GRCh38".equals(profile.normalizedAssembly()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing local GRCh38 VEP profile"));
        grch38.setEnabled(true);
    }

    private Path requiredResourceManifest() {
        String configured = firstText(System.getProperty("varvis.e3k1a.resource-manifest"));
        if (configured != null) return requiredPath(Paths.get(configured));
        return requiredPath(
                Paths.get("..", "docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json"),
                Paths.get("docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json"));
    }

    private Path configuredFile(String property) {
        String configured = firstText(System.getProperty(property));
        assertNotNull(configured, "Set -D" + property + " for the opt-in smoke");
        return requiredPath(Paths.get(configured));
    }

    private void writeSmoke(ObjectNode smoke) throws IOException {
        writeSmoke(smoke, "varvis.e3k1a.smoke-output");
    }

    private void writeK1bSmoke(ObjectNode smoke) throws IOException {
        writeSmoke(smoke, "varvis.e3k1b.smoke-output");
    }

    private void writeSmoke(ObjectNode smoke, String property) throws IOException {
        String configured = firstText(System.getProperty(property));
        assertNotNull(configured,
                "Set " + property + " to a file below varvis-server/target");
        Path output = Paths.get(configured);
        if (!output.isAbsolute()) output = Paths.get("").toAbsolutePath().resolve(output);
        output = output.normalize();
        Path target = Files.isDirectory(Paths.get("varvis-server"))
                ? Paths.get("varvis-server", "target").toAbsolutePath().normalize()
                : Paths.get("target").toAbsolutePath().normalize();
        assertTrue(output.startsWith(target),
                property + " must remain below varvis-server/target");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), smoke);
    }

    private void resource(ObjectNode target, RuntimeEvidenceV2ProviderWiring.ResourceBinding binding) {
        target.put("resourceId", binding.getResourceId());
        target.put("resourceVersion", binding.getResourceVersion());
        target.put("assembly", binding.getAssembly());
        target.put("identityFingerprintSha256", binding.getIdentityFingerprintSha256());
    }

    private void allele(ObjectNode target, NormalizedVariantKey allele) {
        target.put("assembly", allele.getGenomeAssembly());
        target.put("contig", allele.getChromosome());
        target.put("position", allele.getPosition());
        target.put("reference", allele.getReference());
        target.put("alternate", allele.getAlternate());
    }

    private String requiredText(JsonNode node, String field) {
        String value = node == null ? null : node.path(field).asText(null);
        assertTrue(hasText(value), "Missing required manifest field " + field);
        return value;
    }

    private Path requiredPath(Path... candidates) {
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(absolute)) return absolute;
        }
        throw new IllegalStateException("Required local E3-K1A input is missing");
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (java.io.InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte current : digest.digest()) value.append(String.format("%02x", current));
        return value.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) return value.trim();
        }
        return null;
    }

    private static final class CountingVepProvider extends VepTranscriptAnnotationProvider {
        private int batchCalls;

        private CountingVepProvider(VariantAnalysisProperties properties) {
            super(properties);
        }

        @Override
        public Map<NormalizedVariantKey, List<TranscriptConsequence>> annotateBatch(
                List<VariantInterpretation> variants,
                AnalysisContext context) {
            batchCalls++;
            return super.annotateBatch(variants, context);
        }
    }
}
