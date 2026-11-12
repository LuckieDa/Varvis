package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/** Pinned published clauses and explicit project interpretations; never creates a review receipt.
 * Offline preparation verifies original RAW bytes; this loader verifies that accepted compiled artifact.
 * The criterion consumer must validate and execute its own interpretation and all conditions.
 * A source match alone is not MET, and missing coverage is never a negative assertion.
 */
public final class PublishedCriterionKnowledge {
    public static final String VERSION = "1.0.0";
    public static final String SCHEMA = "published-criterion-knowledge-v1";
    public static final String PP2_HCM_SOURCE_METHOD_ADAPTATION_VERSION = "legacy-pp2-hcm-source-method-v1";
    public static final String BP1_TRUNCATING_SOURCE_ADAPTATION_VERSION = "legacy-bp1-truncating-source-v1";
    private static final int MAX_BYTES = 32_000_000;
    private static final Set<String> CRITERIA = Set.of("ACMG_AMP_2015:PM1", "ACMG_AMP_2015:PP2", "ACMG_AMP_2015:BP1", "ACMG_AMP_2015:BP3");
    private static final Set<String> RECORD_FIELDS = Set.of("recordId", "criterionId", "specificationKey", "status",
            "reasonCode", "interpretation", "clausePointer", "clauseSha256", "scopePointer", "scope");
    private final ObjectMapper mapper;
    private final ObjectNode identity;
    private final Map<String, ObjectNode> specifications = new HashMap<>();
    private final List<ObjectNode> records = new ArrayList<>();
    @org.springframework.lang.Nullable private final Bp1PublishedFunctionScreen bp1FunctionalScreen;
    private final Bp1NoEstablishedLofSupply bp1NoLofSupply;
    private final Pp2PublishedSpectrum pp2PublishedSpectrum;
    private final java.time.Clock bp1QualificationClock;

    public static PublishedCriterionKnowledge load(ObjectMapper mapper, Path file, String acceptedRawSha256) throws IOException {
        require(acceptedRawSha256.matches("[0-9a-f]{64}") && Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES,
                "PUBLISHED_KNOWLEDGE_FILE_INVALID");
        byte[] raw;
        try (var in = Files.newInputStream(file)) { raw = in.readNBytes(MAX_BYTES + 1); }
        require(raw.length <= MAX_BYTES && acceptedRawSha256.equals(CodingTranscriptMaterialSupply.sha(raw)),
                "PUBLISHED_KNOWLEDGE_PIN_MISMATCH");
        var strict = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return new PublishedCriterionKnowledge(mapper, strict.readTree(raw), acceptedRawSha256);
    }

    PublishedCriterionKnowledge(ObjectMapper mapper, JsonNode doc, String sha) {
        this(mapper, doc, sha, java.time.Clock.systemUTC());
    }

    PublishedCriterionKnowledge(ObjectMapper mapper, JsonNode doc, String sha, java.time.Clock bp1Clock) {
        this.mapper = mapper;
        this.bp1QualificationClock = java.util.Objects.requireNonNull(bp1Clock);
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        if (!(doc != null && SCHEMA.equals(doc.path("schemaVersion").asText()) && VERSION.equals(doc.path("methodVersion").asText())
                && "PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS".equals(doc.path("adoptionMode").asText())
                && isFalse(doc.path("clinicalValidationCompleted")) && text(doc, "policyId") && text(doc, "policyVersion")
                && doc.path("specifications").isArray() && doc.path("specifications").size() <= 2_000
                && doc.path("records").isArray() && doc.path("records").size() <= 20_000)) {
            throw new IllegalArgumentException("PUBLISHED_KNOWLEDGE_DOCUMENT_INVALID");
        }
        for (var spec : doc.path("specifications")) {
            var original = spec.path("original");
            String key = original.path("entId").asText() + "@" + original.at("/entContent/version").asText();
            var states = new ArrayList<String>();
            for (var state : original.at("/entContent/states")) if (state.path("current").asBoolean()) states.add(state.path("name").asText());
            require(spec instanceof ObjectNode && original.path("entId").asText().matches("GN[0-9]+")
                    && original.at("/entContent/version").asText().matches("[0-9]+(?:\\.[0-9]+){1,2}")
                    && key.equals(spec.path("specificationKey").asText()) && states.equals(List.of("Released"))
                    && adapter.canonicalSha256(original).equals(spec.path("entitySha256").asText())
                    && spec.at("/source/rawSha256").asText().matches("[0-9a-f]{64}")
                    && spec.at("/source/jsonPointer").asText().matches("/data(?:/[0-9]+)?")
                    && spec.at("/source/sourceUrl").asText().matches("https://[^\\s]+")
                    && !specifications.containsKey(key), "PUBLISHED_KNOWLEDGE_SPECIFICATION_INVALID");
            specifications.put(key, ((ObjectNode) spec).deepCopy());
        }
        var ids = new HashSet<String>();
        for (var row : doc.path("records")) {
            require(row instanceof ObjectNode && text(row, "recordId") && ids.add(row.path("recordId").asText())
                    && CRITERIA.contains(row.path("criterionId").asText()) && row.path("interpretation").isObject()
                    && text(row.path("interpretation"), "type") && text(row.path("interpretation"), "version")
                    && Set.of("EXECUTABLE", "NOT_APPLICABLE", "UNSUPPORTED_CONDITIONS", "SOURCE_CONFLICT").contains(row.path("status").asText())
                    && text(row, "reasonCode") && row.path("reasonCode").asText().matches("[A-Z][A-Z0-9_]+"),
                    "PUBLISHED_KNOWLEDGE_RECORD_INVALID");
            var spec = specifications.get(row.path("specificationKey").asText());
            require(spec != null, "PUBLISHED_KNOWLEDGE_SPECIFICATION_MISSING");
            var verifiedSpec = java.util.Objects.requireNonNull(spec);
            verifyScope(row, verifiedSpec.path("original"), adapter);
            if ("ACMG_AMP_2015:BP3".equals(row.path("criterionId").asText()))
                PublishedBp3RegionAssessment.verifyRecord(row, verifiedSpec.path("original"));
            var retained = adaptLegacyHcmSourceMethod(doc, (ObjectNode) row, verifiedSpec, adapter);
            records.add(adaptLegacyBp1TruncatingSource(doc, retained, verifiedSpec, adapter));
        }
        bp1FunctionalScreen = doc.has("bp1FunctionalScreen") ? new Bp1PublishedFunctionScreen(mapper, doc.path("bp1FunctionalScreen")) : null;
        pp2PublishedSpectrum = new Pp2PublishedSpectrum(doc.path("pp2AuthoritySpectrum"), doc.path("pp2AcgsConstraint"));
        identity = mapper.createObjectNode().put("schemaVersion", "published-criterion-knowledge-binding-v1")
                .put("version", VERSION).put("rawSha256", sha).put("policyId", doc.path("policyId").asText())
                .put("policyVersion", doc.path("policyVersion").asText()).put("adoptionMode", doc.path("adoptionMode").asText())
                .put("clinicalValidationCompleted", false).put("createsReviewReceipt", false)
                .put("sourceScope", "PINNED_HISTORICAL_PUBLISHED_SPECIFICATIONS")
                .put("transcriptBridgeVersion", "1.1.0")
                .put("selectionDiagnosticsVersion", "1.0.0")
                .put("pp2HcmSourceMethodAdaptationVersion", PP2_HCM_SOURCE_METHOD_ADAPTATION_VERSION)
                .put("specifications", specifications.size()).put("records", records.size());
        if ("PROJECT_PUBLISHED_BP1".equals(doc.path("policyId").asText()))
            identity.put("bp1TruncatingSourceAdaptationVersion", BP1_TRUNCATING_SOURCE_ADAPTATION_VERSION);
        bp1NoLofSupply = Bp1NoEstablishedLofSupply.fromCatalog(mapper, doc, specifications, sha);
        if (doc.has("bp1LofAssociationRawSources"))
            identity.set("bp1NoEstablishedLofSource", bp1NoLofSupply.identity());
    }

    /** Reparse one known compiler-only disposition, never a variant judgment.
     * Validation above has already checked release, source hashes and exact scope.
     * Unknown formats, explicit policy decisions and future source versions stay
     * unchanged; only the currently implemented full source grammar may advance.
     */
    private ObjectNode adaptLegacyHcmSourceMethod(JsonNode document, ObjectNode originalRow, ObjectNode specification,
            RuntimeEvidenceV2Adapter adapter) {
        var retained = originalRow.deepCopy(); var preparation = document.path("preparation");
        if (!"ACMG_AMP_2015:PP2".equals(originalRow.path("criterionId").asText())
                || !"UNSUPPORTED_CONDITIONS".equals(originalRow.path("status").asText())
                || !"PP2_ADDITIONAL_CONDITIONS_UNSUPPORTED".equals(originalRow.path("reasonCode").asText())
                || !"PP2_SOURCE_RESTRICTION".equals(originalRow.at("/interpretation/type").asText())
                || !"1.0.0".equals(originalRow.at("/interpretation/version").asText())
                || !"1.0".equals(specification.at("/original/entContent/version").asText())
                || !"PROJECT_LOCAL_PP2_PUBLISHED_ASSERTIONS".equals(document.path("policyId").asText())
                || !"1.0.0".equals(document.path("policyVersion").asText())
                || !"1.0.0".equals(preparation.path("methodVersion").asText())
                || !preparation.path("originalRawVerified").isBoolean() || !preparation.path("originalRawVerified").asBoolean()
                || !isFalse(preparation.path("priorBa1ApprovalInherited"))
                || !"PROJECT_RESEARCH_NOT_NEW_EXPERT_REVIEW".equals(preparation.path("interpretationAttribution").asText())
                || !preparation.path("catalogRawSha256").asText().matches("[0-9a-f]{64}")
                || !preparation.path("planCanonicalSha256").asText().matches("[0-9a-f]{64}")
                || !exactFields(document, Set.of("schemaVersion", "methodVersion", "adoptionMode", "clinicalValidationCompleted",
                    "policyId", "policyVersion", "preparation", "specifications", "records"))
                || !exactFields(preparation, Set.of("methodVersion", "catalogRawSha256", "planCanonicalSha256",
                    "originalRawVerified", "priorBa1ApprovalInherited", "interpretationAttribution"))
                || !exactFields(originalRow, RECORD_FIELDS)
                || !exactFields(originalRow.path("interpretation"), Set.of("type", "version", "assessment"))) return retained;
        var selection = mapper.createObjectNode(); selection.set("record", originalRow.deepCopy());
        selection.set("specification", specification.deepCopy());
        final ObjectNode derived;
        try { derived = Pp2HcmPublishedSource.derive(mapper, selection); }
        catch (IllegalArgumentException unsupported) { return retained; }
        var scope = derived.path("sourceScope");
        String priorAssessment = scope.path("geneSymbol").asText() + "/HCM empirical enrichment assertion; exact "
                + scope.path("preferredTranscript").asText() + " required, active text mentions unversioned "
                + scope.path("unversionedSourceEnsembl").asText()
                + ". Requires checking evidence supporting non-HCM phenotype; absent evidence collection is not negative proof.";
        if (!priorAssessment.equals(originalRow.at("/interpretation/assessment").asText())) return retained;
        retained.put("status", "EXECUTABLE").put("reasonCode", "PP2_HCM_PUBLISHED_METHOD_BOUND");
        retained.set("interpretation", derived);
        var trace = retained.putObject("sourceMethodAdaptation").put("schemaVersion", "published-source-method-adaptation-v1")
                .put("methodVersion", PP2_HCM_SOURCE_METHOD_ADAPTATION_VERSION)
                .put("originalStatus", originalRow.path("status").asText())
                .put("originalReasonCode", originalRow.path("reasonCode").asText())
                .put("originalInterpretationType", originalRow.at("/interpretation/type").asText())
                .put("originalRecordSha256", adapter.canonicalSha256(originalRow))
                .put("derivedInterpretationSha256", adapter.canonicalSha256(derived))
                .put("sourceEntitySha256", specification.path("entitySha256").asText())
                .put("sourceClauseSha256", originalRow.path("clauseSha256").asText())
                .put("sourceRawSha256", specification.at("/source/rawSha256").asText())
                .put("variantEvaluationCreated", false).put("phenotypeReviewGranted", false);
        trace.set("originalInterpretation", originalRow.path("interpretation").deepCopy());
        return retained;
    }

    /** Admit the existing truncation evaluator from a verified, fully recognized legacy clause.
     * Keep the source conditional. Neither this adapter nor a missing LoF record grants BP1.
     */
    private ObjectNode adaptLegacyBp1TruncatingSource(JsonNode document, ObjectNode row, ObjectNode specification,
            RuntimeEvidenceV2Adapter adapter) {
        var preparation = document.path("preparation");
        if (!GeneralBp1Runtime.CRITERION.equals(row.path("criterionId").asText())
                || !"UNSUPPORTED_CONDITIONS".equals(row.path("status").asText())
                || !"BP1_PUBLISHED_SOURCE_CONDITIONS_UNSUPPORTED".equals(row.path("reasonCode").asText())
                || !exactFields(row, RECORD_FIELDS)
                || !exactFields(row.path("interpretation"), Set.of("type", "version"))
                || !"PUBLISHED_SOURCE_DISPOSITION_ONLY_V1".equals(row.at("/interpretation/type").asText())
                || !"1.0.0".equals(row.at("/interpretation/version").asText())
                || !"PROJECT_PUBLISHED_BP1".equals(document.path("policyId").asText())
                || !Set.of("1.1.0", "1.2.0").contains(document.path("policyVersion").asText())
                || !"1.0.0".equals(preparation.path("methodVersion").asText())
                || !preparation.path("originalRawVerified").isBoolean() || !preparation.path("originalRawVerified").asBoolean()
                || !isFalse(preparation.path("priorBa1ApprovalInherited"))
                || !"PROJECT_RESEARCH_NOT_NEW_EXPERT_REVIEW".equals(preparation.path("interpretationAttribution").asText())
                || !preparation.path("catalogRawSha256").asText().matches("[0-9a-f]{64}")
                || !preparation.path("planCanonicalSha256").asText().matches("[0-9a-f]{64}")) return row;

        var retained = row.deepCopy().put("reasonCode", "BP1_PUBLISHED_TRUNCATING_LOF_CONDITIONS_REQUIRED");
        var interpretation = retained.putObject("interpretation").put("type", Bp1PublishedPolicy.TRUNCATING_TYPE)
                .put("version", "1.0.0").put("appliedStrength", "SUPPORTING").put("germlineOnly", true)
                .put("requiresNoEstablishedLofDiseaseCorrelation", true);
        var selection = mapper.createObjectNode();
        selection.set("record", retained);
        selection.set("specification", specification);
        try {
            Bp1PublishedPolicy.validateTruncatingSource(selection);
        } catch (IllegalArgumentException unsupported) {
            return row;
        }
        retained.putObject("sourceMethodAdaptation").put("schemaVersion", "published-source-method-adaptation-v1")
                .put("methodVersion", BP1_TRUNCATING_SOURCE_ADAPTATION_VERSION)
                .put("originalStatus", row.path("status").asText())
                .put("originalReasonCode", row.path("reasonCode").asText())
                .put("originalRecordSha256", adapter.canonicalSha256(row))
                .put("derivedInterpretationSha256", adapter.canonicalSha256(interpretation))
                .put("sourceEntitySha256", specification.path("entitySha256").asText())
                .put("sourceClauseSha256", row.path("clauseSha256").asText())
                .put("sourceRawSha256", specification.at("/source/rawSha256").asText())
                .put("variantEvaluationCreated", false);
        return retained;
    }

    private static boolean exactFields(JsonNode object, Set<String> fields) {
        return object.isObject() && object.size() == fields.size() && fields.stream().allMatch(object::has);
    }

    private static void verifyScope(JsonNode row, JsonNode original, RuntimeEvidenceV2Adapter adapter) {
        String cp = row.path("clausePointer").asText(), sp = row.path("scopePointer").asText();
        require(cp.matches("/ld/CriteriaCode/[0-9]+")
                && sp.matches("/ld/RuleSet/[0-9]+/entContent/genes/[0-9]+/diseases/[0-9]+"), "PUBLISHED_KNOWLEDGE_POINTER_INVALID");
        var clause = original.at(cp); var disease = original.at(sp);
        var gene = original.at(sp.substring(0, sp.lastIndexOf("/diseases/")));
        String symbol = gene.path("gene").asText(), hgnc = "";
        for (var g : original.at("/ld/Gene")) if (symbol.equals(g.at("/entContent/HGNC/symbol").asText())) {
            String found = g.at("/entContent/HGNC/hgnc_id").asText();
            require(hgnc.isEmpty() || hgnc.equals(found), "PUBLISHED_KNOWLEDGE_GENE_AMBIGUOUS"); hgnc = found;
        }
        String moi = sourceMoi(disease.path("preferredModeOfInheritance").asText(), row.path("criterionId").asText());
        require(!disease.has("modesOfInheritance") || disease.path("modesOfInheritance").isArray(),
                "PUBLISHED_KNOWLEDGE_INHERITANCE_SOURCE_CONFLICT");
        for (var mode : disease.path("modesOfInheritance")) {
            String value = sourceMoi(mode.path("modeOfInheritance").asText(), row.path("criterionId").asText());
            require(mode.isObject() && mode.path("modeOfInheritance").isTextual() && !value.isEmpty() && value.equals(moi),
                    "PUBLISHED_KNOWLEDGE_INHERITANCE_SOURCE_CONFLICT");
        }
        var scope = row.path("scope"); var cd = clause.at("/entContent/disease");
        require(hgnc.matches("HGNC:[1-9][0-9]*") && !moi.isEmpty() && hgnc.equals(scope.path("hgncId").asText())
                && disease.path("preferredMondoId").asText().matches("MONDO:[0-9]{7}")
                && disease.path("preferredMondoId").equals(scope.path("mondoId")) && moi.equals(scope.path("moi").asText())
                && "GRCh38".equals(scope.path("assembly").asText())
                && row.path("criterionId").asText().equals("ACMG_AMP_2015:" + clause.at("/entContent/label").asText())
                && adapter.canonicalSha256(clause).equals(row.path("clauseSha256").asText())
                && contains(clause.at("/entContent/gene"), symbol)
                && (cd.isMissingNode() || cd.isArray() && (cd.isEmpty() || contains(cd, scope.path("mondoId").asText()))),
                "PUBLISHED_KNOWLEDGE_SOURCE_SCOPE_MISMATCH");
        String transcript = scope.path("transcript").asText();
        require(transcript.matches("(?:NM_[0-9]+|ENST[0-9]+)\\.[1-9][0-9]*")
                && (transcript.equals(gene.path("preferredTranscript").asText()) || contains(gene.path("transcripts"), transcript)),
                "PUBLISHED_KNOWLEDGE_EXPLICIT_TRANSCRIPT_REQUIRED");
        require(!"EXECUTABLE".equals(row.path("status").asText())
                || "Applicable".equals(clause.at("/entContent/applicability").asText()), "PUBLISHED_KNOWLEDGE_CLAUSE_NOT_APPLICABLE");
        if ("NOT_APPLICABLE".equals(row.path("status").asText())) {
            require(Set.of("not applicable", "not applicable for this vcep")
                    .contains(sourceLabel(clause.at("/entContent/applicability").asText())),
                    "PUBLISHED_KNOWLEDGE_SOURCE_NONAPPLICABILITY_REQUIRED");
            var descriptors = clause.at("/entContent/strengthDescriptor");
            require(descriptors.isMissingNode() || descriptors.isArray(), "PUBLISHED_KNOWLEDGE_SOURCE_STRENGTH_DESCRIPTORS_INVALID");
            for (var descriptor : descriptors) {
                require(descriptor.isObject() && descriptor.path("applicability").isTextual()
                        && !descriptor.path("applicability").asText().isBlank(), "PUBLISHED_KNOWLEDGE_SOURCE_STRENGTH_DESCRIPTORS_INVALID");
                require(!"applicable".equals(sourceLabel(descriptor.path("applicability").asText())),
                        "PUBLISHED_KNOWLEDGE_SOURCE_APPLICABILITY_CONFLICT");
            }
        }
    }

    private static String sourceMoi(String term, String criterion) {
        return switch (term.strip()) {
            case "Autosomal dominant inheritance", "Autosomal Dominant" -> "AUTOSOMAL_DOMINANT";
            case "Autosomal recessive inheritance", "Autosomal Recessive" -> "AUTOSOMAL_RECESSIVE";
            case "X-linked inheritance" -> "ACMG_AMP_2015:BP3".equals(criterion) ? "X_LINKED" : "";
            default -> "";
        };
    }

    private static String sourceLabel(String term) {
        return term.replaceAll("(?U)\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** Code-owned execution identity; no files, sources, scientific approval or resources are opened. */
    public static ObjectNode executionIdentity(ObjectMapper mapper) {
        return mapper.createObjectNode().put("schemaVersion", "finite-published-condition-execution-v1")
                .put("sourceScopeValidation", "FINITE_MOI_AND_NATIVE_NONAPPLICABILITY_V1")
                .put("sourceDisposition", "SELECTED_SOURCE_DISPOSITION_V1")
                .put("pm1PeerConditionsVersion", PublishedPm1CrossCriterionConditions.VERSION)
                .put("pm1Pm5ExclusionVersion", PublishedPm1Pm5Exclusion.VERSION)
                .put("pm5CountedDecision", "BOUND_TWO_DISTINCT_ADOPTED_PATHOGENIC_SUBSTITUTIONS_V1");
    }

    public ObjectNode identity() {
        // Source publication/curation updates, not the wall-clock day, identify knowledge.
        // No source-native expiry policy has been adopted for this BP1 material.
        return identity.deepCopy();
    }

    /** This BP1-only producer reads admitted complete originals, never caller-supplied claim JSON. */
    Bp1NoEstablishedLofSupply.Qualification assessBp1NoEstablishedLof(ObjectNode context,
            CodingTranscriptMaterialSupply.Material structure, Bp1TruncatingConsequence.Observation observation) {
        return bp1NoLofSupply.assess(context, structure, observation, bp1QualificationClock);
    }

    ObjectNode assessBp1FunctionScreen(JsonNode model, JsonNode association) {
        return bp1FunctionalScreen == null ? mapper.createObjectNode().put("status", "UNAVAILABLE")
                .put("reasonCode", "BP1_FUNCTION_SCREEN_NOT_CONFIGURED") : bp1FunctionalScreen.assess(model, association);
    }

    /** Requires a model already verified by CodingTranscriptMaterialSupply. READY is not automatic evidence. */
    public ObjectNode select(String criterionId, ObjectNode context, ObjectNode model) {
        return select(criterionId, context, model, false);
    }

    /** The opaque material must come from resolve(), not resolveStructure()/length/splicing supply.
     * Only this entry may transfer an exact RefSeq coding sequence across versions.
     */
    public ObjectNode selectForVerifiedMissense(String criterionId, ObjectNode context, CodingTranscriptMaterialSupply.Material material) {
        var model = material.model(); var audit = material.snapshot(); var association = audit.path("alleleAssociation");
        if (!material.ready() || model == null || !"EXACT_MODEL_REFERENCE_AND_VEP_AGREE".equals(audit.path("reasonCode").asText())
                || !"EXACT_TRANSCRIPT_LOCAL_MATERIAL_SUPPLY".equals(audit.path("method").asText())
                || !new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(model).equals(association.path("nativeModelSha256").asText())
                || !context.at("/variant/evidenceQueryAllele").equals(association.path("allele"))
                || !"SNV".equals(context.at("/variant/variantType").asText())
                || !"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !model.path("geneSymbol").equals(context.at("/gene/symbol"))
                || !audit.at("/verification/referenceIdentity/expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256"))
                || !association.path("refAminoAcid").asText().matches("[ACDEFGHIKLMNPQRSTVWY]")
                || !association.path("altAminoAcid").asText().matches("[ACDEFGHIKLMNPQRSTVWY]")
                || association.path("refAminoAcid").equals(association.path("altAminoAcid"))) {
            var out = mapper.createObjectNode().put("schemaVersion", "published-criterion-selection-v1")
                    .put("status", "UNAVAILABLE").put("criterionId", criterionId).put("clinicalValidationCompleted", false)
                    .put("reasonCode", "PUBLISHED_KNOWLEDGE_VERIFIED_PURE_MISSENSE_REQUIRED");
            out.set("binding", identity()); return out;
        }
        return select(criterionId, context, model, true);
    }

    /** Exact published scope, before sequence or scoring. Includes blocked clauses. */
    public List<String> declaredTranscripts(String criterionId, ObjectNode context) {
        return records.stream().filter(row -> criterionId.equals(row.path("criterionId").asText())
                && row.at("/scope/hgncId").equals(context.at("/gene/hgncId"))
                && row.at("/scope/mondoId").equals(context.at("/disease/mondoId"))
                && row.at("/scope/assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"))
                && matchesInheritance(criterionId, row.path("scope"), context))
                .map(row -> row.at("/scope/transcript").asText()).distinct().sorted().toList();
    }

    /** Configuration metadata only; no sequence, source observation or scientific adoption is performed. */
    public boolean hasInterpretationForBackground(String criterionId, ObjectNode context, String type) {
        return records.stream().anyMatch(row -> criterionId.equals(row.path("criterionId").asText())
                && "EXECUTABLE".equals(row.path("status").asText()) && type.equals(row.at("/interpretation/type").asText())
                && row.at("/scope/hgncId").equals(context.at("/gene/hgncId"))
                && row.at("/scope/mondoId").equals(context.at("/disease/mondoId"))
                && row.at("/scope/assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"))
                && matchesInheritance(criterionId, row.path("scope"), context));
    }

    private ObjectNode select(String criterionId, ObjectNode context, ObjectNode model, boolean verifiedMissense) {
        var out = mapper.createObjectNode().put("schemaVersion", "published-criterion-selection-v1")
                .put("status", "UNAVAILABLE").put("criterionId", criterionId).put("clinicalValidationCompleted", false);
        out.set("binding", identity());
        if (!CRITERIA.contains(criterionId)) return out.put("reasonCode", "PUBLISHED_KNOWLEDGE_CRITERION_UNSUPPORTED");
        String tx = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        if (!tx.equals(model.path("transcript").asText()) || !model.path("hgncId").equals(context.at("/gene/hgncId"))
                || !model.path("assembly").equals(context.at("/variant/evidenceQueryAllele/assembly")))
            return out.put("reasonCode", "PUBLISHED_KNOWLEDGE_MODEL_CONTEXT_MISMATCH");
        ObjectNode selected = null, selectedSourceScope = null; boolean background = false, transcript = false;
        out.put("executionTranscript",tx);
        var scopedTranscripts=out.putArray("backgroundTranscriptSources");
        for (var row : records) {
            var s = row.path("scope");
            if (!criterionId.equals(row.path("criterionId").asText()) || !s.path("hgncId").equals(context.at("/gene/hgncId"))
                    || !s.path("mondoId").equals(context.at("/disease/mondoId")) || !matchesInheritance(criterionId, s, context)) continue;
            background = true;
            var source=scopedTranscripts.addObject().put("declaredTranscript",s.path("transcript").asText())
                    .put("assembly",s.path("assembly").asText()).put("recordId",row.path("recordId").asText())
                    .put("specificationKey",row.path("specificationKey").asText())
                    .put("scopePointer",row.path("scopePointer").asText());
            source.set("source",specifications.get(row.path("specificationKey").asText()).path("source").deepCopy());
            ObjectNode sourceScope = null;
            if (!tx.equals(s.path("transcript").asText()) && !exactBridge(s.path("transcript").asText(), model)
                    && !(verifiedMissense && codingTransfer(s.path("transcript").asText(), model).isObject())) {
                var specification = specifications.get(row.path("specificationKey").asText());
                if (verifiedMissense && "ACMG_AMP_2015:PP2".equals(criterionId) && specification != null)
                    sourceScope = Pp2HcmPublishedSource.transcriptScope(mapper, row, specification, context, model);
                if (sourceScope == null) continue;
            }
            transcript = true;
            if (!s.path("assembly").equals(model.path("assembly"))) continue;
            if (selected != null) return out.put("reasonCode", "PUBLISHED_KNOWLEDGE_MULTIPLE_POLICIES_REQUIRE_SELECTION");
            selected = row; selectedSourceScope = sourceScope;
        }
        if (!background && verifiedMissense && GeneralPp2Runtime.CRITERION.equals(criterionId)
                && pp2PublishedSpectrum.select(out, context, tx, declared -> exactBridge(declared, model))) return out;
        if (selected == null) return out.put("reasonCode", !background ? "PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED"
                : !transcript ? "PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED" : "PUBLISHED_KNOWLEDGE_ASSEMBLY_NOT_COVERED");
        out.set("record", selected.deepCopy());
        out.set("specification", specifications.get(selected.path("specificationKey").asText()).deepCopy());
        out.put("sourceDisposition", selected.path("status").asText())
                .put("sourceReasonCode", selected.path("reasonCode").asText())
                .put("sourceClausePointer", selected.path("clausePointer").asText());
        out.put("declaredTranscript", selected.at("/scope/transcript").asText()).put("executionTranscript", tx);
        if (!tx.equals(selected.at("/scope/transcript").asText())) {
            if (selectedSourceScope != null) out.set("sourceTranscriptScope", selectedSourceScope.deepCopy());
            else out.set("transcriptBridge", model.path("transcriptBridge").deepCopy());
            if (selectedSourceScope == null && !exactBridge(selected.at("/scope/transcript").asText(), model))
                out.set("codingTranscriptTransfer", codingTransfer(selected.at("/scope/transcript").asText(), model).deepCopy());
            out.put("verifiedModelSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(model));
        }
        return out.put("status", "EXECUTABLE".equals(selected.path("status").asText()) ? "READY" : "BLOCKED")
                .put("reasonCode", selected.path("reasonCode").asText());
    }

    private static boolean matchesInheritance(String criterionId, JsonNode scope, JsonNode context) {
        if (scope.path("moi").equals(context.path("moi"))) return true;
        // Source taxonomy may be broader than the already selected patient model; never infer that model.
        return "ACMG_AMP_2015:BP3".equals(criterionId) && "X_LINKED".equals(scope.path("moi").asText())
                && Set.of("X_LINKED_DOMINANT", "X_LINKED_RECESSIVE").contains(context.path("moi").asText());
    }

    /** Reuse only the existing full RNA/exon/CDS proof. MANE names alone and versionless aliases fail. */
    static boolean exactBridge(String sourceTranscript, JsonNode model) {
        var b = model.path("transcriptBridge"); var proof = model.path("sequenceVerification");
        if (!"FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE".equals(b.path("status").asText())
                || !sourceTranscript.equals(b.path("refseqTranscript").asText())
                || !model.path("transcript").equals(b.path("ensemblTranscript"))
                || !model.path("protein").equals(b.path("ensemblProtein"))
                || !b.path("refseqTranscript").asText().matches("NM_[0-9]+\\.[1-9][0-9]*")
                || !b.path("refseqProtein").asText().matches("NP_[0-9]+\\.[1-9][0-9]*")
                || !b.path("ensemblTranscript").asText().matches("ENST[0-9]+\\.[1-9][0-9]*")
                || !b.path("ensemblProtein").asText().matches("ENSP[0-9]+\\.[1-9][0-9]*")
                || !model.path("cdsSequence").asText().matches("[ACGT]+")
                || !CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()).equals(proof.path("cdsSha256").asText())
                || !proof.path("fullSplicedTranscriptSha256").asText().matches("[0-9a-f]{64}")
                || !proof.path("fastaIndexSha256").asText().matches("[0-9a-f]{64}")
                || !proof.path("queriedBases").isIntegralNumber() || !proof.path("queriedBases").canConvertToInt()
                || !model.path("exons").isArray() || model.path("exons").isEmpty()) return false;
        long length = 0;
        for (var e : model.path("exons")) length += (long)e.path("genomicEnd").asInt() - e.path("genomicStart").asInt() + 1;
        if (length <= 0 || length > 1_000_000 || length != proof.path("queriedBases").asInt()) return false;
        boolean rna = false, independentMapping = false;
        if (!model.path("sourceRecords").isArray()) return false;
        for (var s : model.path("sourceRecords")) {
            if (!s.path("sha256").asText().matches("[0-9a-f]{64}") || !text(s, "version")) return false;
            if (("RefSeq:" + sourceTranscript).equals(s.path("sourceId").asText())
                    && "NCBI_VERSIONED_REFSEQ_GENBANK".equals(s.path("sourceKind").asText())
                    && s.path("url").asText().startsWith("https://")) rna = true;
            if (("VEP116_CACHE:" + b.path("ensemblTranscript").asText()).equals(s.path("sourceId").asText())
                    || ("Ensembl:" + b.path("ensemblTranscript").asText()).equals(s.path("sourceId").asText())
                    && s.path("url").asText().startsWith("https://")) independentMapping = true;
            if (("GENCODE:" + b.path("ensemblTranscript").asText()).equals(s.path("sourceId").asText())
                    && "GENCODE_GTF_LOCAL_SNAPSHOT".equals(s.path("sourceKind").asText())
                    && hash(s.path("annotationRowSha256")) && hash(s.path("rawGtfSha256")) && hash(s.path("indexManifestSha256"))
                    && s.path("url").asText().startsWith("https://") && gencodeRnaProof(model)) independentMapping = true;
        }
        return rna && independentMapping;
    }

    private static boolean gencodeRnaProof(JsonNode model) {
        var p = model.path("sequenceVerification"); var eq = p.path("rnaEquivalence"); var b = model.path("transcriptBridge");
        if (!"gencode-refseq-full-equivalence-v1".equals(eq.path("schemaVersion").asText())
                || !p.path("independentRnaVerification").asBoolean(false)
                || !"PINNED_GENCODE_REFERENCE_AND_REFSEQ_FULL_RNA_V1".equals(p.path("independentRnaMethod").asText())
                || !isFalse(eq.path("scientificPolicyAdoption")) || !hash(eq.path("sourceModelRawSha256"))
                || !eq.path("hgncId").equals(model.path("hgncId"))
                || !eq.path("refseqTranscript").equals(b.path("refseqTranscript"))
                || !eq.path("refseqProtein").equals(b.path("refseqProtein"))
                || !eq.path("cdsSha256").equals(p.path("cdsSha256"))
                || !eq.path("fullSplicedTranscriptSha256").equals(p.path("fullSplicedTranscriptSha256"))
                || !sourceBound(model, b.path("refseqTranscript").asText(), eq.path("refseqRawSha256").asText())
                || !eq.path("fullExonLengths").isArray() || eq.path("fullExonLengths").size() != model.path("exons").size()
                || !codingIntervals(model).equals(eq.path("codingExonIntervals"))) return false;
        for (int i = 0; i < model.path("exons").size(); i++) {
            var e = model.path("exons").path(i); var n = eq.path("fullExonLengths").path(i);
            if (!n.isIntegralNumber() || n.asLong() != (long)e.path("genomicEnd").asInt() - e.path("genomicStart").asInt() + 1) return false;
        }
        var row = eq.path("maneRow");
        if (!row.path("HGNC_ID").equals(model.path("hgncId")) || !row.path("symbol").equals(model.path("geneSymbol"))
                || !row.path("RefSeq_nuc").equals(b.path("refseqTranscript")) || !row.path("RefSeq_prot").equals(b.path("refseqProtein"))
                || !row.path("Ensembl_nuc").equals(b.path("ensemblTranscript")) || !row.path("Ensembl_prot").equals(b.path("ensemblProtein"))
                || !maneGenomicIdentityMatches(model, row)) return false;
        String[] fields = {"#NCBI_GeneID", "Ensembl_Gene", "HGNC_ID", "symbol", "name", "RefSeq_nuc", "RefSeq_prot", "Ensembl_nuc",
                "Ensembl_prot", "MANE_status", "GRCh38_chr", "chr_start", "chr_end", "chr_strand"};
        for (var s : model.path("sourceRecords")) {
            if (!"NCBI_MANE_SUMMARY_ROW".equals(s.path("sourceKind").asText()) || !s.path("sha256").equals(eq.path("maneRawSha256"))
                    || !s.path("rowRawSha256").equals(eq.path("maneRowRawSha256"))) continue;
            String raw = s.path("rowRawUtf8").asText();
            if (!CodingTranscriptMaterialSupply.sha(raw.getBytes(StandardCharsets.UTF_8)).equals(s.path("rowRawSha256").asText())) return false;
            String[] values = raw.replaceFirst("[\\r\\n]+$", "").split("\\t", -1);
            if (values.length != fields.length) return false;
            for (int i = 0; i < fields.length; i++) if (!values[i].equals(row.path(fields[i]).asText())) return false;
            return true;
        }
        return false;
    }

    /** Exact primary reference accessions; no accession-prefix or cross-build aliases. */
    static String grch38PrimaryContig(String accession) {
        return switch (accession) {
            case "NC_000001.11" -> "1"; case "NC_000002.12" -> "2"; case "NC_000003.12" -> "3";
            case "NC_000004.12" -> "4"; case "NC_000005.10" -> "5"; case "NC_000006.12" -> "6";
            case "NC_000007.14" -> "7"; case "NC_000008.11" -> "8"; case "NC_000009.12" -> "9";
            case "NC_000010.11" -> "10"; case "NC_000011.10" -> "11"; case "NC_000012.12" -> "12";
            case "NC_000013.11" -> "13"; case "NC_000014.9" -> "14"; case "NC_000015.10" -> "15";
            case "NC_000016.10" -> "16"; case "NC_000017.11" -> "17"; case "NC_000018.10" -> "18";
            case "NC_000019.10" -> "19"; case "NC_000020.11" -> "20"; case "NC_000021.9" -> "21";
            case "NC_000022.11" -> "22"; case "NC_000023.11" -> "X"; case "NC_000024.10" -> "Y";
            default -> "";
        };
    }

    private static boolean maneGenomicIdentityMatches(JsonNode model, JsonNode row) {
        String contig = grch38PrimaryContig(row.path("GRCh38_chr").asText());
        var strand = model.path("strand");
        if (contig.isEmpty() || !contig.equals(model.path("contig").asText()) || !"GRCh38".equals(model.path("assembly").asText())
                || !strand.isIntegralNumber() || !strand.canConvertToInt() || Math.abs(strand.asInt()) != 1
                || !(strand.asInt() == 1 ? "+" : "-").equals(row.path("chr_strand").asText())) return false;
        long start = Long.MAX_VALUE, end = 0;
        for (var exon : model.path("exons")) {
            var lo = exon.path("genomicStart"); var hi = exon.path("genomicEnd");
            if (!lo.isIntegralNumber() || !hi.isIntegralNumber() || !lo.canConvertToInt() || !hi.canConvertToInt()
                    || lo.asInt() < 1 || hi.asInt() < lo.asInt()) return false;
            start = Math.min(start, lo.asLong()); end = Math.max(end, hi.asLong());
        }
        return end > 0 && Long.toString(start).equals(row.path("chr_start").asText())
                && Long.toString(end).equals(row.path("chr_end").asText());
    }

    private static JsonNode codingTransfer(String sourceTranscript, JsonNode model) {
        var missing = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        String current = model.at("/transcriptBridge/refseqTranscript").asText();
        if (!sourceTranscript.matches("NM_[0-9]+\\.[1-9][0-9]*") || sourceTranscript.equals(current)
                || !sourceTranscript.split("\\.")[0].equals(current.split("\\.")[0]) || !exactBridge(current, model)
                || !gencodeRnaProof(model) || !model.path("codingTranscriptTransfers").isArray()
                || model.path("codingTranscriptTransfers").size() > 32) return missing;
        JsonNode found = missing;
        for (var t : model.path("codingTranscriptTransfers")) {
            if (!sourceTranscript.equals(t.path("sourceTranscript").asText())) continue;
            if (found.isObject() || !"refseq-coding-missense-transfer-v1".equals(t.path("schemaVersion").asText())
                    || !"CDS_PROTEIN_AND_CODING_EXONS_IDENTICAL".equals(t.path("status").asText())
                    || !"PURE_CODING_MISSENSE_SNV".equals(t.path("allowedPurpose").asText())
                    || !current.equals(t.path("targetTranscript").asText())
                    || !model.at("/transcriptBridge/refseqProtein").equals(t.path("refseqProtein"))
                    || !model.path("transcript").equals(t.path("ensemblTranscript")) || !model.path("protein").equals(t.path("ensemblProtein"))
                    || !model.path("hgncId").equals(t.path("hgncId")) || !model.path("geneSymbol").equals(t.path("geneSymbol"))
                    || !model.at("/sequenceVerification/cdsSha256").equals(t.path("cdsSha256"))
                    || !codingIntervals(model).equals(t.path("codingExonIntervals"))
                    || !sourceBound(model, sourceTranscript, t.path("sourceRawSha256").asText())
                    || !sourceBound(model, current, t.path("targetRawSha256").asText())
                    || !model.at("/sequenceVerification/rnaEquivalence/refseqRawSha256").equals(t.path("targetRawSha256"))
                    || !model.at("/sequenceVerification/fullSplicedTranscriptSha256").equals(t.path("targetRnaSha256"))
                    || !hash(t.path("sourceRnaSha256")) || !t.path("sourceFullExonCoverage").isBoolean()
                    || !t.path("fullRnaEquivalent").isBoolean()
                    || t.path("fullRnaEquivalent").asBoolean() != t.path("sourceRnaSha256").equals(t.path("targetRnaSha256"))
                    || !isFalse(t.path("fullTranscriptEquivalenceClaimed")) || !isFalse(t.path("scientificPolicyAdoption"))) return missing;
            found = t;
        }
        return found;
    }

    private static JsonNode codingIntervals(JsonNode model) {
        var out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(); int length = model.path("cdsLength").asInt();
        for (var e : model.path("exons")) {
            int lo = Math.max(1, e.path("cdsStart").asInt()), hi = Math.min(length, e.path("cdsEnd").asInt());
            if (lo <= hi) out.addArray().add(lo).add(hi);
        }
        return out;
    }
    private static boolean sourceBound(JsonNode model, String transcript, String sha) {
        if (!sha.matches("[0-9a-f]{64}")) return false;
        for (var s : model.path("sourceRecords")) if (("RefSeq:" + transcript).equals(s.path("sourceId").asText())
                && "NCBI_VERSIONED_REFSEQ_GENBANK".equals(s.path("sourceKind").asText()) && transcript.equals(s.path("version").asText())
                && s.path("url").asText().startsWith("https://") && sha.equals(s.path("sha256").asText())) return true;
        return false;
    }
    private static boolean hash(JsonNode n) { return n.isTextual() && n.asText().matches("[0-9a-f]{64}"); }

    private static boolean contains(JsonNode array, String value) {
        if (array.isArray()) for (var n : array) if (n.isTextual() && value.equals(n.asText())) return true;
        return false;
    }
    private static boolean text(JsonNode n, String f) { return n.path(f).isTextual() && !n.path(f).asText().isBlank(); }
    private static boolean isFalse(JsonNode n) { return n.isBoolean() && !n.asBoolean(); }
    private static void require(boolean ok, String reason) { if (!ok) throw new IllegalArgumentException(reason); }
}
