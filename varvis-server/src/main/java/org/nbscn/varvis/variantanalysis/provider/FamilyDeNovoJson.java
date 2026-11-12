package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.trio.CaseFamilyObservation;

/** Narrow shared JSON/facts boundary; rule-specific wrappers retain their schemas and error prefixes. */
final class FamilyDeNovoJson {
    private final String prefix;
    FamilyDeNovoJson(String prefix) { this.prefix = prefix; }
    ObjectNode parse(ObjectMapper mapper, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > Ps2DeNovoBundle.MAX_BYTES) throw invalid("SIZE_LIMIT");
        // Do not inherit a caller's permissive comment/trailing-comma/non-JSON parser flags.
        var factory = new com.fasterxml.jackson.core.JsonFactory();
        factory.setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(Ps2DeNovoBundle.MAX_BYTES).build());
        try (JsonParser parser = factory.createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = mapper.readTree(parser);
            if (!(node instanceof ObjectNode object) || parser.nextToken() != null) throw invalid("SINGLE_OBJECT_REQUIRED");
            depth(object, 0); return object;
        } catch (IOException error) { throw invalid("JSON_INVALID"); }
    }
    void depth(JsonNode node, int depth) {
        if (depth > 32) throw invalid("DEPTH_LIMIT");
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) throw invalid("NONFINITE");
        if (node.isObject()) {
            Set<String> canonicalKeys = new HashSet<>();
            node.fieldNames().forEachRemaining(key -> {
                if (!canonicalKeys.add(java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFC)))
                    throw invalid("CANONICAL_KEY_COLLISION");
            });
        }
        for (JsonNode child : node) depth(child, depth + 1);
    }
    void facts(JsonNode n) {
        shape(n, "schemaVersion:s observationId:s observationVersion:s occurrenceId:s caseId:s familyId:s binding:o members:a records:a parentage:a clinical:o sources:a");
        equal(n, "schemaVersion", CaseFamilyObservation.SCHEMA); binding(n.path("binding"));
        for (JsonNode member : n.path("members")) {
            shape(member, "memberId:s sampleId:s role:s affectedStatus:s detection:o");
            enumeration(member, "role", "PROBAND FATHER MOTHER");
            enumeration(member, "affectedStatus", "AFFECTED UNAFFECTED UNKNOWN");
            JsonNode d = member.path("detection");
            shape(d, "binding:o memberId:s targetAltIndex:i ploidy:i sampleFormat:s method:s coverage:s capability:s quality:s basis:s limitations:s sourceIds:a");
            binding(d.path("binding")); strings(d.path("sourceIds"));
            enumeration(d, "coverage", "COVERED UNKNOWN NOT_COVERED");
            enumeration(d, "capability", "TARGET_DETECTION UNKNOWN INADEQUATE");
            enumeration(d, "quality", "VALID UNKNOWN LOW_QUALITY CONFLICT");
        }
        for (JsonNode r : n.path("records")) {
            shape(r, "variantKey:o role:s sampleId:s geneSymbol:?s recordFilter:?s sampleFilter:?s rawSampleValue:?s observation:?o");
            enumeration(r, "role", "PROBAND FATHER MOTHER");
            shape(r.path("variantKey"), "assembly:s chromosome:s position:i reference:s alternate:s");
            JsonNode g = r.path("observation"); if (g.isNull()) continue;
            shape(g, "role:s genotype:?s call:s depth:?i genotypeQuality:?i alleleDepth:?s phased:b");
            enumeration(g, "role", "PROBAND FATHER MOTHER");
            enumeration(g, "call", "HET_ALT HOM_ALT HEMI_ALT HOM_REF UNCONFIRMED_HOM_REF UNOBSERVED NO_CALL OTHER");
        }
        for (JsonNode p : n.path("parentage")) {
            shape(p, "binding:o parentRole:s parentMemberId:s probandMemberId:s status:s method:s sourceIds:a");
            binding(p.path("binding")); strings(p.path("sourceIds"));
            enumeration(p, "parentRole", "FATHER MOTHER"); enumeration(p, "status", "CONFIRMED UNCONFIRMED UNKNOWN CONFLICT");
        }
        JsonNode c = n.path("clinical");
        shape(c, "binding:o probandMemberId:s observedAt:s age:s phenotypeObservations:a exclusions:a phenotypeCategory:s heterogeneity:s familyHistory:s familyHistoryBasis:s mosaicism:s scopeBasis:s siblingRecurrence:s sourceIds:a applicability:!o");
        // Legacy facts remain readable, but absence is never an applicable clinical conclusion.
        if (c.has("applicability")) {
            JsonNode conclusion = c.path("applicability");
            shape(conclusion, "schemaVersion:s relatedFamilyHistory:s singleTrioScope:s sourceIds:a reviewer:s date:s basis:s");
            equal(conclusion, "schemaVersion", "case-family-clinical-applicability-v1");
            enumeration(conclusion, "relatedFamilyHistory", "ABSENT PRESENT UNKNOWN CONFLICT UNRESOLVED");
            enumeration(conclusion, "singleTrioScope", "APPLICABLE OUT_OF_SCOPE UNKNOWN CONFLICT UNRESOLVED");
            strings(conclusion.path("sourceIds"));
        }
        binding(c.path("binding"));
        for (String field : List.of("phenotypeObservations", "exclusions", "sourceIds")) strings(c.path(field));
        enumeration(c, "phenotypeCategory", "HIGHLY_SPECIFIC CONSISTENT NOT_CONSISTENT UNKNOWN");
        enumeration(c, "heterogeneity", "HIGH NOT_HIGH UNKNOWN");
        enumeration(c, "familyHistory", "REVIEWED UNKNOWN CONFLICT");
        enumeration(c, "mosaicism", "REVIEWED_NONMOSAIC KNOWN UNKNOWN");
        enumeration(c, "siblingRecurrence", "REVIEWED_ABSENT PRESENT UNKNOWN");
        for (JsonNode s : n.path("sources"))
            shape(s, "sourceId:s recordId:s version:s locator:s acquiredAt:s rawUtf8:s rawSha256:s canonicalSha256:s");
    }
    void binding(JsonNode n) {
        shape(n, "observationId:s contextId:s alleleId:s allele:o hgncId:s mondoId:s moi:s patientContextId:s familyContextId:s");
        shape(n.path("allele"), "assembly:s contig:s position:i reference:s alternate:s");
    }
    void scope(JsonNode n) {
        shape(n, "hgncId:s mondoId:s moi:s assembly:s transcriptMode:s variantType:s transcripts:!a");
        enumeration(n, "transcriptMode", "TRANSCRIPT_NOT_REQUIRED EXACT_ACCESSIONS");
        enumeration(n, "variantType", "NATIVE_NONPROJECTED_SNV NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT");
        enumeration(n, "assembly", "GRCh38 GRCh37");
        enumeration(n, "moi", "AUTOSOMAL_DOMINANT AUTOSOMAL_RECESSIVE X_LINKED_DOMINANT X_LINKED_RECESSIVE Y_LINKED");
        if (n.has("transcripts")) for (JsonNode t : n.path("transcripts")) shape(t, "accession:s version:i");
    }
    void catalog(JsonNode n) {
        shape(n, "schemaVersion:s catalogId:s catalogVersion:s searchScope:s searchedAt:s review:o sources:a entries:a");
        equal(n, "schemaVersion", "acmg-specification-catalog-v1");
        shape(n.path("review"), "status:s reviewId:s");
        enumeration(n.path("review"), "status", "REVIEWED UNREVIEWED UNKNOWN CONFLICT");
        for (JsonNode s : n.path("sources")) shape(s, "sourceId:s url:s version:s sha256:s");
        for (JsonNode e : n.path("entries")) {
            shape(e, "entryId:s criterionId:s scope:o searchStatus:s review:o sourceIds:a disposition:s baseRuleRelationship:s generalPrerequisites:o policyBinding:o");
            scope(e.path("scope")); shape(e.path("review"), "status:s reviewId:s"); strings(e.path("sourceIds"));
            enumeration(e, "searchStatus", "COMPLETE INCOMPLETE FAILED");
            enumeration(e, "disposition", "SPECIALIZED_UNIMPLEMENTED SPECIALIZED_IMPLEMENTED SPECIALIZED_VERSION_CONFLICT SPECIALIZED_UNRESOLVED NO_APPLICABLE_SPECIALIZATION GENERAL_CLAUSE_ALLOWED");
            enumeration(e.path("review"), "status", "REVIEWED UNREVIEWED UNKNOWN CONFLICT");
            shape(e.path("generalPrerequisites"), "status:s sourceIds:a"); strings(e.at("/generalPrerequisites/sourceIds"));
            enumeration(e.path("generalPrerequisites"), "status", "COMPLETE INCOMPLETE UNKNOWN");
            shape(e.path("policyBinding"), "policyId:s policyVersion:s policySha256:s");
        }
    }
    /** ! permits absence only; ? permits explicit unknown null, never silently supplies a value. */
    void shape(JsonNode node, String specification) {
        if (!node.isObject()) throw invalid("OBJECT_REQUIRED");
        Set<String> allowed = new HashSet<>();
        for (String field : specification.split(" ")) {
            String[] pair = field.split(":"); String key = pair[0], type = pair[1]; allowed.add(key);
            JsonNode value = node.path(key);
            if (type.startsWith("!") && value.isMissingNode()) continue;
            if (type.startsWith("?") && value.isNull()) continue;
            type = type.replace("!", "").replace("?", "");
            boolean valid = switch (type) {
                case "s" -> value.isTextual() && !value.asText().isBlank();
                case "o" -> value.isObject(); case "a" -> value.isArray(); case "b" -> value.isBoolean();
                case "i" -> value.isIntegralNumber() && value.canConvertToLong(); default -> false;
            };
            if (!valid) throw invalid("FIELD_INVALID_" + key);
        }
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid("UNKNOWN_FIELD_" + key); });
    }
    void strings(JsonNode n) {
        if (!n.isArray()) throw invalid("ARRAY_REQUIRED");
        for (JsonNode item : n) if (!item.isTextual() || item.asText().isBlank()) throw invalid("STRING_REQUIRED");
    }
    void enumeration(JsonNode n, String field, String values) {
        if (!Set.of(values.split(" ")).contains(n.path(field).asText())) throw invalid("ENUM_INVALID_" + field);
    }
    void equal(JsonNode n, String field, String value) {
        if (!value.equals(n.path(field).asText())) throw invalid("SCHEMA_INVALID");
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(prefix + reason); }
}
