package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Source replay and use binding only. No project-authored prose or date window
 * qualifies a scientific negative. Native review interpretation remains explicit
 * software work until its source-specific coverage is implemented and verified. */
final class Bp1NoEstablishedLofSupply {
    static final String VERSION = "1.1.0";
    private static final String PREFIX = "BP1_NO_LOF_";
    private static final int MAX_SOURCE_BYTES = 4_000_000;
    private final ObjectMapper mapper;
    private final String catalogSha;
    private final String sourceStatus;
    private final String reason;
    private final List<ObjectNode> claims;

    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    private Bp1NoEstablishedLofSupply(ObjectMapper mapper, String catalogSha, String sourceStatus,
            String reason, List<ObjectNode> claims) {
        this.mapper = mapper; this.catalogSha = catalogSha; this.sourceStatus = sourceStatus; this.reason = reason;
        var copied = new ArrayList<ObjectNode>();
        for (var claim : claims) copied.add(java.util.Objects.requireNonNull(claim.deepCopy()));
        this.claims = List.copyOf(copied);
    }

    /** Only the catalog loader calls this with its already admitted complete specifications. */
    static Bp1NoEstablishedLofSupply fromCatalog(ObjectMapper mapper, JsonNode compiledDoc,
            Map<String, ObjectNode> admittedSpecifications, String acceptedCompiledSha256) {
        var claims = new ArrayList<ObjectNode>();
        if (!compiledDoc.has("bp1LofAssociationRawSources"))
            return new Bp1NoEstablishedLofSupply(mapper, acceptedCompiledSha256, "UNAVAILABLE", PREFIX + "SOURCE_NOT_CONFIGURED", claims);
        try {
            require(acceptedCompiledSha256.matches("[a-f0-9]{64}"), "CATALOG_PIN_INVALID");
            var rawSources = compiledDoc.path("bp1LofAssociationRawSources");
            require(rawSources.isArray() && rawSources.size() > 0 && rawSources.size() <= 8, "RAW_SOURCES_INVALID");
            var rawBySha = new HashMap<String, JsonNode>();
            var strict = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            int total = 0;
            for (var row : rawSources) {
                require(row.isObject() && row.size() == 2 && row.path("rawSha256").isTextual()
                        && row.path("rawJsonUtf8").isTextual(), "RAW_SOURCE_SHAPE_INVALID");
                String sha = row.path("rawSha256").asText(), raw = row.path("rawJsonUtf8").asText();
                require(sha.matches("[a-f0-9]{64}") && raw.length() <= MAX_SOURCE_BYTES, "RAW_SOURCE_SIZE_OR_PIN_INVALID");
                ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(raw));
                byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); total += bytes.length;
                require(bytes.length <= MAX_SOURCE_BYTES && total <= 32_000_000, "RAW_SOURCE_TOO_LARGE");
                require(sha.equals(CodingTranscriptMaterialSupply.sha(bytes)), "RAW_PIN_MISMATCH");
                var parsed = strict.readTree(bytes);
                require(parsed != null && parsed.isObject(), "RAW_JSON_INVALID");
                require(rawBySha.putIfAbsent(sha, parsed) == null, "RAW_SOURCE_DUPLICATE");
            }
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            for (var entry : admittedSpecifications.entrySet()) {
                var spec = entry.getValue(); var original = spec.path("original");
                var source = spec.path("source");
                JsonNode raw = rawBySha.get(source.path("rawSha256").asText());
                if (raw == null) continue; // Only sources actually retained for this purpose.
                String gn = original.path("entId").asText();
                require(gn.matches("GN[0-9]+") && (gn + "@" + original.at("/entContent/version").asText()).equals(entry.getKey())
                        && entry.getKey().equals(spec.path("specificationKey").asText()), "SPECIFICATION_IDENTITY_INVALID");
                require(admittedSourceUrl(source.path("sourceUrl").asText(), gn), "SOURCE_ORIGIN_UNSUPPORTED");
                String ptr = source.path("jsonPointer").asText();
                require(ptr.matches("/data(?:/[0-9]+)?"), "SOURCE_POINTER_INVALID");
                require(original.isObject() && original.equals(raw.at(ptr))
                        && adapter.canonicalSha256(original).equals(spec.path("entitySha256").asText()), "ORIGINAL_REPLAY_MISMATCH");
                var states = new ArrayList<String>(); String releasedAt = "";
                for (var state : original.at("/entContent/states")) if (state.path("current").asBoolean()) {
                    states.add(state.path("name").asText()); releasedAt = state.at("/event/timeStamp").asText();
                }
                require(states.equals(List.of("Released")), "SOURCE_STATE_INVALID");
                var content = original.path("entContent");
                for (String flag : List.of("legacyReplaced", "legacyFullySuperseded"))
                    require(!content.has(flag) || content.path(flag).isBoolean(), "LEGACY_STATE_INVALID");
                require(!content.path("legacyReplaced").asBoolean(), "LEGACY_REPLACED");
                require(!content.has("legacyFullySuperseded") || content.path("legacyFullySuperseded").asBoolean(),
                        "LEGACY_NOT_YET_SUPERSEDED");
                for (var subject : original.at("/ld/Gene")) {
                    String symbol = subject.at("/entContent/HGNC/symbol").asText();
                    String hgnc = subject.at("/entContent/HGNC/hgnc_id").asText();
                    require(symbol.matches("[A-Za-z0-9_.-]{1,100}") && hgnc.matches("HGNC:[1-9][0-9]*"), "NATIVE_SUBJECT_INVALID");
                    var c = mapper.createObjectNode();
                    String notes = original.at("/entContent/notes").asText();
                    boolean attachment = original.at("/ld/File").isContainerNode() && !original.at("/ld/File").isEmpty();
                    c.putObject("claim").put("hgncId", hgnc).put("geneSymbol", symbol)
                            .put("diseaseScope", "SOURCE_NATIVE_SCOPE_NOT_YET_INTERPRETED")
                            .put("inheritanceScope", "NOT_ASSUMED").put("molecularMechanism", "NOT_ASSUMED");
                    c.put("softwareInterpretationStatus", "UNSUPPORTED")
                            .put("reasonCode", PREFIX + (attachment ? "ATTACHMENT_INTERPRETATION_UNSUPPORTED"
                                    : notes.contains("across all human diseases. Expert review date:")
                                            ? "LEGACY_PROJECT_TEMPLATE_NOT_SCIENTIFIC_REVIEW" : "NATIVE_REVIEW_SCOPE_NOT_INTERPRETED"));
                    c.putObject("sourceBinding").put("catalogRawSha256", acceptedCompiledSha256)
                            .put("specificationKey", entry.getKey()).put("entitySha256", spec.path("entitySha256").asText())
                            .put("sourceRawSha256", source.path("rawSha256").asText()).put("sourceUrl", source.path("sourceUrl").asText())
                            .put("sourceJsonPointer", ptr).put("sourceReleasedAt", releasedAt).put("statementPointer", "/entContent/notes")
                            .put("statementTextSha256", CodingTranscriptMaterialSupply.sha(notes.getBytes(StandardCharsets.UTF_8)));
                    claims.add(c);
                }
            }
            return new Bp1NoEstablishedLofSupply(mapper, acceptedCompiledSha256, "CHECKED",
                    PREFIX + (claims.isEmpty() ? "RETAINED_GENE_SOURCE_REQUIRED" : "NATIVE_REVIEW_SCOPE_NOT_INTERPRETED"), claims);
        } catch (Exception invalid) {
            String message = invalid.getMessage();
            return new Bp1NoEstablishedLofSupply(mapper, acceptedCompiledSha256, "INVALID",
                    message != null && message.matches("BP1_NO_LOF_[A-Z0-9_]+") ? message : PREFIX + "RAW_OR_SOURCE_INVALID", List.of());
        }
    }

    ObjectNode identity() {
        return mapper.createObjectNode().put("schemaVersion", "bp1-no-established-lof-supply-v1").put("methodVersion", VERSION)
                .put("status", sourceStatus).put("reasonCode", reason).put("catalogRawSha256", catalogSha)
                .put("retainedSourceReviews", claims.size()).put("absenceQualificationSupported", false).put("performsIo", false).put("scoringPermissionGranted", false);
    }

    static final class Qualification {
        private final ObjectNode value;
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        private Qualification(ObjectNode value) { this.value = value.deepCopy(); }
        boolean qualified() { return value.path("absenceQualificationGranted").asBoolean(false)
                && "QUALIFIED".equals(value.path("status").asText()); }
        ObjectNode snapshot() { return value.deepCopy(); }
    }

    Qualification assess(ObjectNode context, @Nullable CodingTranscriptMaterialSupply.Material actualStructure,
            @Nullable Bp1TruncatingConsequence.Observation actualObservation, Clock clock) {
        var out = mapper.createObjectNode().put("schemaVersion", "bp1-no-established-lof-qualification-v1")
                .put("methodVersion", VERSION).put("status", "UNKNOWN").put("reasonCode", reason)
                .put("sourceCheckStatus", sourceStatus).put("absenceQualificationGranted", false)
                .put("lossOfFunctionEstablishedForQueryAllele", false).put("scoringPermissionGranted", false)
                .put("clinicalValidationCompleted", false);
        try {
            var evaluatedAt = clock.instant();
            out.put("evaluatedDate", LocalDate.ofInstant(evaluatedAt, ZoneOffset.UTC).toString());
            if (!"CHECKED".equals(sourceStatus)) return new Qualification(out);
            if (actualStructure == null) throw new IllegalArgumentException(PREFIX + "STRUCTURE_REQUIRED");
            if (actualObservation == null) throw new IllegalArgumentException(PREFIX + "SEQUENCE_OBSERVATION_REQUIRED");
            require(actualStructure.ready() && actualObservation.ready() && actualObservation.sequenceConfirmed(), "ACTUAL_SEQUENCE_REQUIRED");
            var adapter = new RuntimeEvidenceV2Adapter(mapper); adapter.validateCompleteContext(context);
            require(adapter.canonicalContextId(context).equals(context.path("contextId").asText()), "CONTEXT_ID_MISMATCH");
            var audit = actualStructure.snapshot(); var model = actualStructure.model();
            if (model == null) throw new IllegalArgumentException(PREFIX + "STRUCTURE_REQUIRED");
            require("READY".equals(audit.path("status").asText())
                    && "EXACT_TRANSCRIPT_LOCAL_STRUCTURE_SUPPLY".equals(audit.path("method").asText())
                    && CodingTranscriptMaterialSupply.STRUCTURE_VERSION.equals(audit.path("version").asText())
                    && "EXACT_MODEL_REFERENCE_AND_SELECTED_ANNOTATION_BOUND".equals(audit.path("reasonCode").asText())
                    && audit.path("alleleEffectVerified").isBoolean() && !audit.path("alleleEffectVerified").asBoolean(), "STRUCTURE_PURPOSE_MISMATCH");
            require(CodingTranscriptMapping.modelIssue(model, context) == null, "MODEL_CONTEXT_MISMATCH");
            String modelSha = adapter.canonicalSha256(model), structureSha = adapter.canonicalSha256(audit), contextSha = adapter.canonicalSha256(context);
            var proof = audit.path("verification"); var allele = context.at("/variant/evidenceQueryAllele");
            require(modelSha.equals(audit.path("modelCanonicalSha256").asText())
                    && CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()).equals(proof.path("cdsSha256").asText())
                    && proof.path("fullSplicedTranscriptSha256").asText().matches("[a-f0-9]{64}")
                    && proof.path("fullSplicedTranscriptSha256").equals(model.at("/sequenceVerification/fullSplicedTranscriptSha256"))
                    && proof.at("/referenceIdentity/expectedSha256").asText().matches("[a-f0-9]{64}")
                    && proof.at("/referenceIdentity/expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")), "REFERENCE_BINDING_MISMATCH");
            var observation = actualObservation.snapshot(); var b = observation.path("binding");
            require("TRUNCATION_CANDIDATE".equals(observation.path("status").asText())
                    && context.path("contextId").equals(observation.path("contextId"))
                    && contextSha.equals(observation.path("contextCanonicalSha256").asText())
                    && allele.equals(observation.path("queryAllele"))
                    && modelSha.equals(observation.path("nativeModelSha256").asText())
                    && structureSha.equals(observation.path("structureMaterialSha256").asText())
                    && context.path("contextId").equals(b.path("contextId")) && contextSha.equals(b.path("contextSha256").asText())
                    && adapter.canonicalSha256(allele).equals(b.path("alleleSha256").asText())
                    && modelSha.equals(b.path("modelCanonicalSha256").asText()) && structureSha.equals(b.path("structureMaterialSha256").asText())
                    && model.path("transcript").equals(b.path("transcript")) && model.path("protein").equals(b.path("protein"))
                    && proof.path("cdsSha256").equals(b.path("referenceCdsSha256"))
                    && proof.path("fullSplicedTranscriptSha256").equals(b.path("fullSplicedReferenceTranscriptSha256"))
                    && context.at("/variant/normalization/referenceSequenceSha256").equals(b.path("referenceSequenceSha256"))
                    && !observation.path("candidateId").asText().isBlank()
                    && observation.path("candidateId").equals(b.path("candidateId")), "SEQUENCE_BINDING_MISMATCH");
            out.putObject("useBinding").put("contextId", context.path("contextId").asText()).put("contextSha256", contextSha)
                    .put("alleleSha256", adapter.canonicalSha256(allele)).put("modelCanonicalSha256", modelSha)
                    .put("structureMaterialSha256", structureSha).put("sequenceObservationSha256", adapter.canonicalSha256(observation));
            var reviews = out.putArray("sourceReviews");
            for (var claim : claims) if (context.at("/gene/hgncId").equals(claim.at("/claim/hgncId"))
                    && context.at("/gene/symbol").equals(claim.at("/claim/geneSymbol"))) reviews.add(claim.deepCopy());
            JsonNode firstReview = reviews.isEmpty() ? null : reviews.get(0);
            if (firstReview == null) return new Qualification(out.put("reasonCode", PREFIX + "NAMED_GENE_SCOPE_MISMATCH"));
            // Successful transport is not a reviewed gene-wide negative proposition.
            // This stage deliberately grants no absence qualification from unparsed source text.
            out.set("claim", firstReview.path("claim").deepCopy());
            out.set("sourceBinding", firstReview.path("sourceBinding").deepCopy());
            out.putObject("reviewCoverage").put("status", "SOFTWARE_UNSUPPORTED")
                    .put("sameGeneOtherDiseasesChecked", false).put("absenceFromCatalogMeansNoLof", false);
            out.putObject("validity").put("status", "NO_SOURCE_EXPIRY_POLICY_ADOPTED")
                    .put("expiryRequiredByScientificRule", false);
            return new Qualification(out.put("reasonCode", firstReview.path("reasonCode").asText()));
        } catch (RuntimeException invalid) {
            String message = invalid.getMessage();
            return new Qualification(out.put("sourceCheckStatus", "INVALID").put("reasonCode",
                    message != null && message.matches("BP1_NO_LOF_[A-Z0-9_]+") ? message : PREFIX + "USE_BINDING_INVALID"));
        }
    }

    private static boolean admittedSourceUrl(String value, String gn) {
        try {
            var uri = URI.create(value); String base = "/cspec/SequenceVariantInterpretation/id";
            if (!"https".equals(uri.getScheme()) || !"cspec.genome.network".equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getUserInfo() != null || uri.getFragment() != null) return false;
            if ((base + "/" + gn).equals(uri.getRawPath())) return uri.getRawQuery() == null;
            if (!base.equals(uri.getRawPath()) || uri.getRawQuery() == null) return false;
            var params = new HashMap<String, String>();
            for (String p : uri.getRawQuery().split("&", -1)) {
                int eq = p.indexOf('='); if (eq < 1) return false;
                String k = URLDecoder.decode(p.substring(0, eq), StandardCharsets.UTF_8), v = URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8);
                if (!Set.of("ids", "detail").contains(k) || params.putIfAbsent(k, v) != null) return false;
            }
            String ids = params.get("ids");
            if (ids == null || !ids.matches("GN[0-9]+(?:,GN[0-9]+)*") || params.containsKey("detail") && !"high".equals(params.get("detail"))) return false;
            var unique = new HashSet<String>(); boolean contains = false;
            for (String id : ids.split(",")) { if (!unique.add(id)) return false; contains |= gn.equals(id); }
            return contains;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(PREFIX + reason);
    }
}
