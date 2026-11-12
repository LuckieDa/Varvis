package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.BoundedSequenceDeletion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Minimal E2 carrier-to-atomic-evidence-v2 boundary; it never applies an ACMG criterion. */
public final class RuntimeEvidenceV2Adapter {

    public static final String PRODUCER_ID = "varvis-runtime-v2-adapter";
    public static final String PRODUCER_VERSION = "2.0.0-e2";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$");
    private static final Pattern SOURCE_ID = Pattern.compile("^[A-Z][A-Z0-9._-]{1,63}$");
    private static final Pattern CONTIG = Pattern.compile("^(?:[1-9]|1[0-9]|2[0-2]|X|Y)$");
    private static final Pattern BASES = Pattern.compile("^[ACGT]+$");
    private static final Pattern HGNC_ID = Pattern.compile("^HGNC:[1-9][0-9]*$");
    private static final Pattern GENE_SYMBOL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");
    private static final Pattern MONDO_ID = Pattern.compile("^MONDO:[0-9]{7}$");
    private static final Pattern TRANSCRIPT = Pattern.compile("^(?:(?:NM|NR)_[A-Za-z0-9]+|ENST[0-9]+)$");
    private static final Pattern PATIENT_ID = Pattern.compile("^patient-context-[a-z0-9][a-z0-9._-]{7,95}$");
    private static final Pattern FAMILY_ID = Pattern.compile("^family-context-[a-z0-9][a-z0-9._-]{7,95}$");
    private static final Pattern METRIC_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{0,95}$");
    private static final Set<String> RUNTIME_TYPES = Set.of(
            "POPULATION_FREQUENCY", "CLINICAL_DATABASE", "VARIANT_CONSEQUENCE",
            "COMPUTATIONAL_PREDICTION", "FUNCTIONAL_STUDY"
    );
    private static final Set<String> DATA_CLASSES = Set.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME");
    private static final Set<String> ASSEMBLIES = Set.of("GRCh37", "GRCh38");
    private static final Set<String> VARIANT_TYPES = Set.of(
            "SNV", "MNV", "SMALL_INSERTION", "SMALL_DELETION", "SMALL_DELINS"
    );
    private static final Set<String> MOI = Set.of(
            "AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED_DOMINANT",
            "X_LINKED_RECESSIVE", "Y_LINKED"
    );
    private static final Set<String> EXECUTION = Set.of("NOT_RUN", "SUCCEEDED", "FAILED", "TIMEOUT", "DISABLED");
    private static final Set<String> AVAILABILITY = Set.of("AVAILABLE", "UNAVAILABLE", "UNKNOWN");
    private static final Set<String> QUERY = Set.of("HIT", "NO_RECORD", "NOT_QUERIED");
    private static final Set<String> APPLICABILITY = Set.of("APPLICABLE", "NOT_APPLICABLE", "UNKNOWN");
    private static final Set<String> LOCATOR_TYPES = Set.of(
            "DOCUMENT_SECTION", "DOCUMENT_PAGE", "TABLE", "FIGURE", "PARAGRAPH",
            "WEB_FRAGMENT", "API_RECORD", "DATABASE_RECORD", "REPOSITORY_ARTIFACT", "GENOMIC_REGION"
    );
    private static final List<String> CARRIER_FIELDS = List.of(
            "source", "resourceExecution", "resourceAvailability", "queryOutcome",
            "resourceApplicability", "measurements"
    );

    private final ObjectMapper mapper;

    public RuntimeEvidenceV2Adapter(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Validates one complete context without requiring a provider carrier. */
    public void validateCompleteContext(JsonNode context) {
        requireCompleteContext(context);
    }

    /** Explicit successor dispatch. Legacy callers still require the unchanged v2 contract. */
    public void validateVersionedContext(JsonNode context) {
        if (context != null && "evidence-context-v3".equals(context.path("schemaVersion").asText()))
            requireContext(context, true);
        else requireCompleteContext(context);
    }

    public String canonicalVersionedContextId(JsonNode context) {
        if (!"evidence-context-v3".equals(context.path("schemaVersion").asText())) return canonicalContextId(context);
        ObjectNode projection = mapper.createObjectNode().put("domain", "varvis-evidence-context-v3")
                .put("schemaVersion", "evidence-context-v3").put("dataClass", context.path("dataClass").asText());
        projection.set("scope", context.path("scope"));
        projection.put("alleleId", context.at("/variant/alleleId").asText());
        projection.put("hgncId", context.at("/gene/hgncId").asText());
        projection.put("mondoId", context.at("/disease/mondoId").asText());
        projection.put("moi", context.path("moi").asText());
        if (context.has("transcript")) projection.putObject("transcript")
                .put("accession", context.at("/transcript/accession").asText()).set("version", context.at("/transcript/version"));
        projection.set("patientContext", context.path("patientContext"));
        return "context-sha256-" + canonicalSha256(projection);
    }

    public ObjectNode adaptVersioned(JsonNode context, ObjectNode carrier) {
        validateVersionedContext(context);
        return adaptValidated(context, carrier, "evidence-context-v3".equals(context.path("schemaVersion").asText()));
    }

    /** Returns the frozen E1 allele identity for a normalized allele and normalizer. */
    public String canonicalAlleleId(JsonNode normalizedAllele, JsonNode normalization) {
        ObjectNode projection = mapper.createObjectNode();
        projection.set("normalizedAllele", normalizedAllele.deepCopy());
        ObjectNode normalizer = mapper.createObjectNode();
        for (String field : List.of("method", "methodVersion", "referenceSequenceSha256", "refValidation")) {
            normalizer.put(field, text(normalization, field));
        }
        projection.set("normalizer", normalizer);
        return "allele-sha256-" + canonicalSha256(projection);
    }

    /** Returns the frozen E1 context identity from a complete context-shaped value. */
    public String canonicalContextId(JsonNode context) {
        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", context.path("scope").deepCopy());
        projection.put("alleleId", context.path("variant").path("alleleId").asText());
        projection.put("hgncId", context.path("gene").path("hgncId").asText());
        projection.put("mondoId", context.path("disease").path("mondoId").asText());
        projection.put("moi", context.path("moi").asText());
        ObjectNode transcript = mapper.createObjectNode();
        transcript.put("accession", context.path("transcript").path("accession").asText());
        transcript.set("version", context.path("transcript").path("version").deepCopy());
        projection.set("transcript", transcript);
        projection.put("patientContextId", context.path("patientContext").path("patientContextId").asText());
        JsonNode family = context.path("patientContext").get("familyContextId");
        if (family == null) projection.putNull("familyContextId");
        else projection.set("familyContextId", family.deepCopy());
        return "context-sha256-" + canonicalSha256(projection);
    }

    /** Adapts one explicit, schema-shaped provider carrier against a complete supplied context. */
    public ObjectNode adapt(JsonNode context, ObjectNode carrier) {
        requireCompleteContext(context);
        return adaptValidated(context, carrier);
    }

    private ObjectNode adaptValidated(JsonNode context, ObjectNode carrier) {
        return adaptValidated(context, carrier, false);
    }

    private ObjectNode adaptValidated(JsonNode context, ObjectNode carrier, boolean versioned) {
        requireCarrier(carrier, context.path("variant").path("evidenceQueryAllele"), versioned);

        ObjectNode evidence = mapper.createObjectNode();
        evidence.put("schemaVersion", "atomic-evidence-v2");
        evidence.put("contextId", context.path("contextId").asText());
        evidence.put("canonicalizationProfile", "varvis-json-c14n-v1");
        evidence.put("evidenceType", carrier.path("evidenceType").asText());
        evidence.put("direction", "UNDETERMINED");
        CARRIER_FIELDS.forEach(field -> evidence.set(field, carrier.path(field).deepCopy()));
        evidence.set("derivedFromEvidenceIds", mapper.createArrayNode());
        evidence.set("conflict", noConflict());
        evidence.set("review", noReview());
        if ("FUNCTIONAL_STUDY".equals(carrier.path("evidenceType").asText())) {
            requirePattern(carrier, "correlationGroupId", Pattern.compile("^[A-Za-z][A-Za-z0-9._:-]{2,127}$"));
            evidence.set("correlationGroupId", carrier.path("correlationGroupId"));
            ((ObjectNode) evidence.path("review")).put("status", "REQUIRED");
        }

        ObjectNode provenance = mapper.createObjectNode();
        provenance.put("producerId", PRODUCER_ID);
        provenance.put("producerVersion", PRODUCER_VERSION);
        provenance.put("producedAt", text(carrier, "producedAt"));
        provenance.put("sourcePayloadSha256", text(carrier, "sourcePayloadSha256"));
        evidence.set("provenance", provenance);

        evidence.put("deduplicationKey", "dedup-sha256-" + canonicalSha256(dedupProjection(evidence)));
        String identitySha256 = canonicalSha256(identityProjection(evidence));
        evidence.put("evidenceId", "evidence-sha256-" + identitySha256);
        provenance.put("identityProjectionSha256", identitySha256);
        return evidence;
    }

    void validatePopulationCarrier(ObjectNode carrier, JsonNode allele) {
        if (!"POPULATION_FREQUENCY".equals(carrier.path("evidenceType").asText())) throw invalid("population carrier required");
        requireCarrier(carrier, allele);
    }

    private void requireCompleteContext(JsonNode context) {
        requireContext(context, false);
    }

    private void requireContext(JsonNode context, boolean successor) {
        if (context == null || !context.isObject()
                || !(successor ? "evidence-context-v3" : "evidence-context-v2").equals(context.path("schemaVersion").asText())
                || !"varvis-json-c14n-v1".equals(context.path("canonicalizationProfile").asText())) {
            throw invalid("evidence-context-v2 is required");
        }
        requireOnlyFields(context, Set.of(
                "schemaVersion", "contextId", "canonicalizationProfile", "dataClass", "scope",
                "variant", "gene", "disease", "moi", "transcript", "patientContext",
                "contextCompleteness"
        ), "context");
        requireAllowed(text(context, "dataClass"), DATA_CLASSES, "dataClass");
        JsonNode completeness = context.path("contextCompleteness");
        if (!"COMPLETE".equals(completeness.path("status").asText())
                || !completeness.path("missingFields").isArray()
                || !completeness.path("missingFields").isEmpty()) {
            throw invalid("context must be explicitly COMPLETE");
        }
        requireOnlyFields(completeness, Set.of("status", "missingFields"), "contextCompleteness");

        JsonNode scope = requiredObject(context, "scope");
        requireOnlyFields(scope, Set.of("analysisDomain", "diseaseModel", "variantScope"), "scope");
        if (!"GERMLINE".equals(scope.path("analysisDomain").asText())
                || !"MENDELIAN".equals(scope.path("diseaseModel").asText())
                || !"SMALL_SEQUENCE_VARIANT".equals(scope.path("variantScope").asText())) {
            throw invalid("context is outside the E2 scope");
        }

        JsonNode variant = context.path("variant");
        requireOnlyFields(variant, Set.of(
                "alleleId", "legacyCanonicalVariantId", "variantType", "inputAllele",
                "normalizedAllele", "evidenceQueryAllele", "normalization", "assemblyLineage"
        ), "variant");
        requireAllowed(text(variant, "variantType"), VARIANT_TYPES, "variantType");
        if (variant.has("legacyCanonicalVariantId")) {
            requireTextLength(variant, "legacyCanonicalVariantId", 512);
        }
        JsonNode inputAllele = requireAllele(variant, "inputAllele");
        JsonNode normalizedAllele = requireAllele(variant, "normalizedAllele");
        JsonNode queryAllele = requireAllele(variant, "evidenceQueryAllele");
        if (!inputAllele.path("assembly").equals(normalizedAllele.path("assembly"))) {
            throw invalid("normalization cannot change assembly");
        }

        JsonNode normalization = requiredObject(variant, "normalization");
        requireOnlyFields(normalization, Set.of(
                "status", "method", "methodVersion", "referenceSequenceSha256", "refValidation"
        ), "normalization");
        requireAllowed(text(normalization, "status"), Set.of("UNCHANGED", "NORMALIZED"), "normalization status");
        requireTextLength(normalization, "method", 128);
        requireTextLength(normalization, "methodVersion", 64);
        requireSha256(normalization, "referenceSequenceSha256");
        if (!"MATCH".equals(text(normalization, "refValidation"))) throw invalid("REF validation is not MATCH");
        if ("UNCHANGED".equals(normalization.path("status").asText()) && !inputAllele.equals(normalizedAllele)) {
            throw invalid("UNCHANGED normalization must preserve the input allele");
        }
        requireAssemblyLineage(variant, normalizedAllele, queryAllele);

        JsonNode gene = requiredObject(context, "gene");
        requireOnlyFields(gene, Set.of("hgncId", "symbol"), "gene");
        requirePattern(gene, "hgncId", HGNC_ID);
        if (requirePattern(gene, "symbol", GENE_SYMBOL).length() > 64) throw invalid("gene symbol is too long");
        JsonNode disease = requiredObject(context, "disease");
        requireOnlyFields(disease, Set.of("mondoId", "label"), "disease");
        requirePattern(disease, "mondoId", MONDO_ID);
        requireTextLength(disease, "label", 256);
        requireAllowed(text(context, "moi"), MOI, "moi");

        if (!successor || context.has("transcript")) {
            JsonNode transcriptContext = requiredObject(context, "transcript");
            requireOnlyFields(transcriptContext, Set.of(
                    "accession", "version", "selectionPolicy", "annotationSourceVersion"
            ), "transcript");
            requirePattern(transcriptContext, "accession", TRANSCRIPT);
            requirePositiveInteger(transcriptContext, "version");
            requireTextLength(transcriptContext, "selectionPolicy", 128);
            requireTextLength(transcriptContext, "annotationSourceVersion", 128);
        }

        JsonNode patient = requiredObject(context, "patientContext");
        requireOnlyFields(patient, Set.of(
                "patientContextId", "familyContextId", "role", "affectedStatus", "sex"
        ), "patientContext");
        requirePattern(patient, "patientContextId", PATIENT_ID);
        if (patient.has("familyContextId")) requirePattern(patient, "familyContextId", FAMILY_ID);
        requireAllowed(text(patient, "role"), Set.of("PROBAND", "FAMILY_MEMBER"), "patient role");
        requireAllowed(text(patient, "affectedStatus"), Set.of("AFFECTED", "UNAFFECTED", "UNKNOWN"),
                "affected status");
        requireAllowed(text(patient, "sex"), Set.of("FEMALE", "MALE", "UNKNOWN"), "sex");

        String alleleId = canonicalAlleleId(normalizedAllele, normalization);
        if (!alleleId.equals(variant.path("alleleId").asText())) throw invalid("alleleId is not canonical");
        String contextId = successor ? canonicalVersionedContextId(context) : canonicalContextId(context);
        if (!contextId.equals(context.path("contextId").asText())) throw invalid("contextId is not canonical");
    }

    private JsonNode requireAllele(JsonNode variant, String field) {
        JsonNode allele = requiredObject(variant, field);
        requireOnlyFields(allele, Set.of("assembly", "contig", "position", "reference", "alternate"), field);
        requireAllowed(text(allele, "assembly"), ASSEMBLIES, field + " assembly");
        requirePattern(allele, "contig", CONTIG);
        requirePositiveInteger(allele, "position");
        String reference = requirePattern(allele, "reference", BASES);
        String alternate = requirePattern(allele, "alternate", BASES);
        if (reference.length() > 10_000 || alternate.length() > 10_000) throw invalid(field + " allele is too long");
        boolean extendedDeletion = BoundedSequenceDeletion.NORMALIZATION_METHOD.equals(variant.at("/normalization/method").asText())
                && "GRCh38".equals(allele.path("assembly").asText()) && "SMALL_DELETION".equals(variant.path("variantType").asText())
                && "NATIVE".equals(variant.at("/assemblyLineage/mappingStatus").asText())
                && BoundedSequenceDeletion.extended(reference, alternate);
        if ("normalizedAllele".equals(field)
                && (Math.max(reference.length(), alternate.length()) > 50 && !extendedDeletion || reference.equals(alternate))) {
            throw invalid("normalized allele is outside the E1 small-variant boundary");
        }
        return allele;
    }

    private void requireAssemblyLineage(JsonNode variant, JsonNode normalizedAllele, JsonNode queryAllele) {
        JsonNode lineage = requiredObject(variant, "assemblyLineage");
        requireOnlyFields(lineage, Set.of("mappingStatus", "coordinateMapping", "reasonCode"), "assemblyLineage");
        String status = text(lineage, "mappingStatus");
        if (lineage.has("reasonCode")) requireReasonCode(lineage);
        if ("NATIVE".equals(status)) {
            if (lineage.has("coordinateMapping") || !normalizedAllele.equals(queryAllele)) {
                throw invalid("NATIVE query allele must equal the normalized allele");
            }
            return;
        }
        if (!"MAPPED".equals(status)) throw invalid("unmapped context cannot produce runtime evidence");
        JsonNode mapping = requiredObject(lineage, "coordinateMapping");
        requireOnlyFields(mapping, Set.of(
                "sourceAllele", "targetAllele", "method", "methodVersion", "refAltValidation"
        ), "coordinateMapping");
        JsonNode sourceAllele = requireAllele(mapping, "sourceAllele");
        JsonNode targetAllele = requireAllele(mapping, "targetAllele");
        requireTextLength(mapping, "method", 128);
        requireTextLength(mapping, "methodVersion", 64);
        if (!"MATCH".equals(text(mapping, "refAltValidation"))
                || !sourceAllele.equals(normalizedAllele) || !targetAllele.equals(queryAllele)
                || sourceAllele.path("assembly").equals(targetAllele.path("assembly"))) {
            throw invalid("coordinate mapping does not preserve the normalized/query lineage");
        }
    }

    private void requireCarrier(ObjectNode carrier, JsonNode queryAllele) {
        requireCarrier(carrier, queryAllele, false);
    }

    private void requireCarrier(ObjectNode carrier, JsonNode queryAllele, boolean versioned) {
        if (carrier == null || !queryAllele.equals(carrier.path("queryAllele"))) {
            throw invalid("carrier queryAllele must equal context.variant.evidenceQueryAllele");
        }
        if (!RUNTIME_TYPES.contains(text(carrier, "evidenceType"))
                && !(versioned && "CASE_OBSERVATION".equals(text(carrier, "evidenceType")))) {
            throw invalid("evidenceType is outside the runtime v2 boundary");
        }
        CARRIER_FIELDS.forEach(field -> {
            if (!carrier.has(field)) throw invalid("carrier is missing " + field);
        });
        requireDateTime(carrier, "producedAt");
        requireSha256(carrier, "sourcePayloadSha256");
        JsonNode source = requiredObject(carrier, "source");
        requireOnlyFields(source, Set.of(
                "sourceId", "resourceVersion", "resourceSha256", "retrievedAt", "sourceRecords", "sourceLocators"
        ), "source");
        requirePattern(source, "sourceId", SOURCE_ID);
        requireTextLength(source, "resourceVersion", 128);
        requireSha256(source, "resourceSha256");
        requireDateTime(source, "retrievedAt");
        if (!source.path("sourceRecords").isArray()
                || !source.path("sourceLocators").isArray()
                || source.path("sourceLocators").isEmpty()) {
            throw invalid("source identity, records and locators are required");
        }
        for (JsonNode locator : source.path("sourceLocators")) requireLocator(locator);
        for (JsonNode record : source.path("sourceRecords")) {
            requireOnlyFields(record, Set.of("recordId", "locator"), "sourceRecord");
            requireTextLength(record, "recordId", 256);
            requireLocator(requiredObject(record, "locator"));
        }
        requireUnique(source.path("sourceLocators"), "source locators");
        requireUnique(source.path("sourceRecords"), "source records");
        requireMeasurements(carrier.path("measurements"));
        requireFourAxes(carrier, source);
    }

    private void requireFourAxes(ObjectNode carrier, JsonNode source) {
        JsonNode executionAxis = requiredObject(carrier, "resourceExecution");
        JsonNode availabilityAxis = requiredObject(carrier, "resourceAvailability");
        JsonNode queryAxis = requiredObject(carrier, "queryOutcome");
        JsonNode applicabilityAxis = requiredObject(carrier, "resourceApplicability");
        requireOnlyFields(executionAxis, Set.of("status", "reasonCode", "attemptedAt", "completedAt"),
                "resourceExecution");
        requireOnlyFields(availabilityAxis, Set.of("status", "reasonCode"), "resourceAvailability");
        requireOnlyFields(queryAxis, Set.of("status", "recordCount", "queryFingerprint"), "queryOutcome");
        requireOnlyFields(applicabilityAxis, Set.of("status", "reasonCode"), "resourceApplicability");

        String execution = requireAllowed(text(executionAxis, "status"), EXECUTION, "resource execution");
        String availability = requireAllowed(text(availabilityAxis, "status"), AVAILABILITY,
                "resource availability");
        String query = requireAllowed(text(queryAxis, "status"), QUERY, "query outcome");
        String applicability = requireAllowed(text(applicabilityAxis, "status"), APPLICABILITY,
                "resource applicability");
        for (JsonNode axis : List.of(executionAxis, availabilityAxis, applicabilityAxis)) {
            if (axis.has("reasonCode")) requireReasonCode(axis);
        }
        if (executionAxis.has("attemptedAt")) requireDateTime(executionAxis, "attemptedAt");
        if (executionAxis.has("completedAt")) requireDateTime(executionAxis, "completedAt");
        if ("SUCCEEDED".equals(execution)) {
            requireDateTime(executionAxis, "attemptedAt");
            requireDateTime(executionAxis, "completedAt");
        } else {
            requireReasonCode(executionAxis);
        }
        if (!"AVAILABLE".equals(availability)) requireReasonCode(availabilityAxis);
        if (!"APPLICABLE".equals(applicability)) requireReasonCode(applicabilityAxis);

        JsonNode records = source.path("sourceRecords");
        JsonNode measurements = carrier.path("measurements");
        JsonNode recordCountNode = queryAxis.path("recordCount");
        if (!recordCountNode.isIntegralNumber() || !recordCountNode.canConvertToInt()) {
            throw invalid("recordCount is invalid");
        }
        int recordCount = recordCountNode.asInt(-1);
        if (!measurements.isArray() || recordCount != records.size()) throw invalid("recordCount is invalid");
        if ("HIT".equals(query) || "NO_RECORD".equals(query)) requireSha256(queryAxis, "queryFingerprint");
        if ("NOT_QUERIED".equals(query) && queryAxis.has("queryFingerprint")) {
            throw invalid("NOT_QUERIED cannot contain a query fingerprint");
        }
        if ("SUCCEEDED".equals(execution) && !Set.of("HIT", "NO_RECORD").contains(query)) {
            throw invalid("SUCCEEDED requires HIT or NO_RECORD");
        }
        if ("HIT".equals(query) && (!"SUCCEEDED".equals(execution)
                || !"AVAILABLE".equals(availability) || recordCount < 1)) {
            throw invalid("HIT requires SUCCEEDED and AVAILABLE");
        }
        if ("NO_RECORD".equals(query) && (!"SUCCEEDED".equals(execution)
                || !"AVAILABLE".equals(availability) || recordCount != 0 || !measurements.isEmpty())) {
            throw invalid("NO_RECORD requires a successful empty query");
        }
        if (!"SUCCEEDED".equals(execution) && (!"NOT_QUERIED".equals(query)
                || recordCount != 0 || !measurements.isEmpty())) {
            throw invalid("provider failure cannot become NO_RECORD or negative evidence");
        }
        if ("NOT_QUERIED".equals(query) && (!records.isEmpty() || !measurements.isEmpty())) {
            throw invalid("NOT_QUERIED cannot contain records or measurements");
        }
        if (("FAILED".equals(execution) || "TIMEOUT".equals(execution) || "DISABLED".equals(execution))
                && "APPLICABLE".equals(applicability)) {
            throw invalid("failed or disabled provider cannot assert applicability");
        }
        if ("UNAVAILABLE".equals(availability) && !"NOT_QUERIED".equals(query)) {
            throw invalid("UNAVAILABLE cannot become a query result");
        }
    }

    private ObjectNode dedupProjection(ObjectNode evidence) {
        ObjectNode result = mapper.createObjectNode();
        result.put("contextId", evidence.path("contextId").asText());
        result.put("evidenceType", evidence.path("evidenceType").asText());
        result.put("sourceId", evidence.path("source").path("sourceId").asText());
        List<String> ids = new ArrayList<String>();
        evidence.path("source").path("sourceRecords").forEach(record -> ids.add(record.path("recordId").asText()));
        Collections.sort(ids);
        ArrayNode records = mapper.createArrayNode();
        ids.forEach(records::add);
        result.set("sourceRecordIds", records);
        JsonNode fingerprint = evidence.path("queryOutcome").get("queryFingerprint");
        if (fingerprint == null) result.putNull("queryFingerprint");
        else result.set("queryFingerprint", fingerprint.deepCopy());
        return result;
    }

    /** Verify an already saved atomic identity without producing or adopting new evidence. */
    public boolean savedAtomicIdentityMatches(ObjectNode evidence) {
        String sha = canonicalSha256(identityProjection(evidence));
        return ("evidence-sha256-" + sha).equals(evidence.path("evidenceId").asText())
                && sha.equals(evidence.at("/provenance/identityProjectionSha256").asText());
    }

    private ObjectNode identityProjection(ObjectNode evidence) {
        ObjectNode result = evidence.deepCopy();
        result.remove(List.of("evidenceId", "deduplicationKey", "provenance", "conflict", "review"));
        ObjectNode source = ((ObjectNode) evidence.path("source")).deepCopy();
        source.remove("retrievedAt");
        source.set("sourceLocators", withoutRetrievedAt(evidence.path("source").path("sourceLocators")));
        ArrayNode records = mapper.createArrayNode();
        for (JsonNode record : evidence.path("source").path("sourceRecords")) {
            ObjectNode item = mapper.createObjectNode();
            item.put("recordId", record.path("recordId").asText());
            ObjectNode locator = ((ObjectNode) record.path("locator")).deepCopy();
            locator.remove("retrievedAt");
            item.set("locator", locator);
            records.add(item);
        }
        source.set("sourceRecords", sorted(records));
        result.set("source", source);
        ObjectNode execution = ((ObjectNode) evidence.path("resourceExecution")).deepCopy();
        execution.remove(List.of("attemptedAt", "completedAt"));
        result.set("resourceExecution", execution);
        result.set("measurements", sorted(evidence.path("measurements")));
        result.set("derivedFromEvidenceIds", sorted(evidence.path("derivedFromEvidenceIds")));
        ObjectNode provenance = mapper.createObjectNode();
        provenance.put("producerId", PRODUCER_ID);
        provenance.put("producerVersion", PRODUCER_VERSION);
        provenance.put("sourcePayloadSha256", evidence.path("provenance").path("sourcePayloadSha256").asText());
        result.set("provenance", provenance);
        return result;
    }

    private ArrayNode withoutRetrievedAt(JsonNode values) {
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode value : values) {
            ObjectNode copy = ((ObjectNode) value).deepCopy();
            copy.remove("retrievedAt");
            result.add(copy);
        }
        return sorted(result);
    }

    private ArrayNode sorted(JsonNode values) {
        List<JsonNode> items = new ArrayList<JsonNode>();
        values.forEach(value -> items.add(value.deepCopy()));
        items.sort(Comparator.comparing(this::canonicalString));
        ArrayNode result = mapper.createArrayNode();
        items.forEach(result::add);
        return result;
    }

    private ObjectNode noConflict() {
        ObjectNode value = mapper.createObjectNode();
        value.put("status", "NONE");
        value.set("conflictIds", mapper.createArrayNode());
        return value;
    }

    private ObjectNode noReview() {
        ObjectNode value = mapper.createObjectNode();
        value.put("status", "NOT_REQUIRED");
        value.put("decision", "NONE");
        value.set("reviewRecordIds", mapper.createArrayNode());
        return value;
    }

    private void requireLocator(JsonNode locator) {
        requireOnlyFields(locator, Set.of(
                "locatorType", "locatorValue", "sourceRecordId", "artifactSha256", "retrievedAt"
        ), "source locator");
        requireAllowed(text(locator, "locatorType"), LOCATOR_TYPES, "locator type");
        String value = text(locator, "locatorValue");
        if (value.length() > 512 || value.matches("^[A-Za-z]:[\\\\/].*")
                || value.startsWith("/") || value.startsWith("\\\\")
                || value.regionMatches(true, 0, "file:", 0, 5)) {
            throw invalid("absolute filesystem locators are forbidden");
        }
        if (locator.has("sourceRecordId")) requireTextLength(locator, "sourceRecordId", 256);
        if (locator.has("artifactSha256")) requireSha256(locator, "artifactSha256");
        requireDateTime(locator, "retrievedAt");
    }

    private void requireMeasurements(JsonNode measurements) {
        if (!measurements.isArray()) throw invalid("measurements must be an array");
        for (JsonNode measurement : measurements) {
            requireOnlyFields(measurement, Set.of(
                    "metricId", "rawValue", "normalizedValue", "unit", "calibrationProfileId"
            ), "measurement");
            requirePattern(measurement, "metricId", METRIC_ID);
            requireScalar(measurement, "rawValue");
            if (measurement.has("normalizedValue")) requireScalar(measurement, "normalizedValue");
            if (measurement.has("unit")) requireTextLength(measurement, "unit", 64);
            if (measurement.has("calibrationProfileId")) {
                requireTextLength(measurement, "calibrationProfileId", 128);
            }
        }
        requireUnique(measurements, "measurements");
    }

    private void requireScalar(JsonNode parent, String field) {
        if (!parent.has(field)) throw invalid("missing " + field);
        JsonNode value = parent.get(field);
        if (!(value.isTextual() || value.isIntegralNumber() || value.isBoolean() || value.isNull())) {
            throw invalid(field + " must be a canonical JSON scalar");
        }
    }

    private void requireUnique(JsonNode values, String label) {
        Set<String> seen = new HashSet<String>();
        for (JsonNode value : values) {
            if (!seen.add(canonicalString(value))) throw invalid(label + " must be unique");
        }
    }

    private void requireOnlyFields(JsonNode value, Set<String> allowed, String label) {
        if (value == null || !value.isObject()) throw invalid(label + " must be an object");
        value.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw invalid(label + " contains unsupported field " + field);
        });
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw invalid("missing " + field);
        return value;
    }

    private String requireAllowed(String value, Set<String> allowed, String label) {
        if (!allowed.contains(value)) throw invalid(label + " is invalid");
        return value;
    }

    private String requirePattern(JsonNode parent, String field, Pattern pattern) {
        String value = text(parent, field);
        if (!pattern.matcher(value).matches()) throw invalid(field + " is invalid");
        return value;
    }

    private void requireSha256(JsonNode parent, String field) {
        if (!SHA256.matcher(text(parent, field)).matches()) throw invalid(field + " is invalid");
    }

    private void requireReasonCode(JsonNode parent) {
        String value = text(parent, "reasonCode");
        if (value.length() < 3 || value.length() > 96 || !REASON_CODE.matcher(value).matches()) {
            throw invalid("reasonCode is invalid");
        }
    }

    private void requireDateTime(JsonNode parent, String field) {
        try {
            OffsetDateTime.parse(text(parent, field));
        } catch (DateTimeParseException ex) {
            throw invalid(field + " is not an RFC 3339 date-time");
        }
    }

    private int requirePositiveInteger(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
            throw invalid(field + " must be a positive integer");
        }
        return value.asInt();
    }

    private String requireTextLength(JsonNode parent, String field, int maximum) {
        String value = text(parent, field);
        if (value.length() > maximum) throw invalid(field + " is too long");
        return value;
    }

    private String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().trim().isEmpty()) throw invalid("missing " + field);
        return value.asText();
    }

    /** The same canonical identity routine for explicit cross-package source projections. */
    public String canonicalSha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String canonicalString(JsonNode value) {
        try {
            // HTTP pretty printing must never change the frozen compact identity bytes.
            return mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(canonicalNode(value));
        } catch (JsonProcessingException ex) {
            throw invalid("carrier cannot be canonicalized");
        }
    }

    private JsonNode canonicalNode(JsonNode value) {
        if (value == null || value.isNull() || value.isBoolean() || value.isIntegralNumber()) return value;
        if (value.isFloatingPointNumber()) throw invalid("binary floating-point identity values are forbidden");
        if (value.isTextual()) {
            return mapper.getNodeFactory().textNode(Normalizer.normalize(value.asText(), Normalizer.Form.NFC));
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonicalNode(item)));
            return result;
        }
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            TreeSet<String> names = new TreeSet<String>();
            Iterator<String> iterator = value.fieldNames();
            iterator.forEachRemaining(names::add);
            names.forEach(name -> result.set(Normalizer.normalize(name, Normalizer.Form.NFC),
                    canonicalNode(value.path(name))));
            return result;
        }
        throw invalid("unsupported canonical JSON value");
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid runtime evidence v2 carrier: " + message);
    }
}
