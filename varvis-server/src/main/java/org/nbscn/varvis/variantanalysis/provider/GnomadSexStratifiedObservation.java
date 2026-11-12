package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Additional source facts derived from a verified v1 observation, without changing its meaning or identity. */
public final class GnomadSexStratifiedObservation {
    public static final String VERSION = "gnomad-sex-stratified-observation-v1";
    public static final String HEADER_SHA256 = "66875ac50f53b8f491c263394fc85e17308c639bae4b7d5daa43fea6f773ca83";
    public static final String JOINT_VERSION = "gnomad-joint-sex-stratified-observation-v1";
    public static final String JOINT_HEADER_SHA256 = "2875792c14a97bc0d69d96267f3532594d0cef1c1c9578cd7f0c43159aa0cc00";
    private static final String HEADER_SOURCE = "https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/vcf/exomes/gnomad.exomes.v4.1.sites.chr{chromosome}.vcf.bgz";
    private static final String HEADER_RESOURCE = "/acmg/gnomad-exomes-4.1-sex-observation-header.vcf.txt";
    private static final String JOINT_HEADER_SOURCE = "https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/vcf/joint/gnomad.joint.v4.1.sites.chr{chromosome}.vcf.bgz";
    private static final String JOINT_HEADER_RESOURCE = "/acmg/gnomad-joint-4.1-sex-observation-header.vcf.txt";
    private static final Pattern INFO = Pattern.compile("^##INFO=<ID=([^,]+),Number=([^,]+),Type=([^,]+),Description=\"(.*)\">$");
    private static final List<String> ANCESTRIES = List.of("ALL", "afr", "ami", "amr", "asj", "eas", "fin", "mid", "nfe", "sas");
    private static final List<String> JOINT_ANCESTRIES = List.of("ALL", "afr", "ami", "amr", "asj", "eas", "fin", "mid", "nfe", "remaining", "sas");
    private static final List<String> STATISTICS = List.of("AC", "AN", "AF", "faf95");
    private GnomadSexStratifiedObservation() { }

    public static byte[] loadPinnedHeader() {
        try (var input = GnomadSexStratifiedObservation.class.getResourceAsStream(HEADER_RESOURCE)) {
            if (input == null) throw new IllegalStateException("GNOMAD_SEX_HEADER_RESOURCE_MISSING");
            byte[] bytes = input.readNBytes(1_048_577);
            if (!HEADER_SHA256.equals(sha(bytes))) throw new IllegalStateException("GNOMAD_SEX_HEADER_PIN_MISMATCH");
            return bytes;
        } catch (java.io.IOException invalid) { throw new IllegalStateException("GNOMAD_SEX_HEADER_RESOURCE_UNAVAILABLE", invalid); }
    }

    public static ObjectNode derive(ObjectMapper mapper, JsonNode parent, JsonNode context, String sourceSnapshot) {
        return derive(mapper, parent, context, sourceSnapshot, loadPinnedHeader());
    }

    /** Header bytes are the original, pinned full header; a caller-selected header digest is never accepted. */
    public static ObjectNode derive(ObjectMapper mapper, JsonNode parent, JsonNode context,
            String sourceSnapshot, byte[] rawHeaderBytes) {
        var profile = GnomadPopulationInputProfile.loadLocalBa1();
        PopulationObservation.verify(mapper, parent, context, sourceSnapshot, profile);
        if (!profile.getDocument().path("resources").path(0).path("assembly").equals(parent.at("/queryAllele/assembly")))
            throw new IllegalArgumentException("GNOMAD_SEX_RESOURCE_ASSEMBLY_MISMATCH");
        if (rawHeaderBytes == null || rawHeaderBytes.length > 1_048_576 || !HEADER_SHA256.equals(sha(rawHeaderBytes)))
            throw new IllegalArgumentException("GNOMAD_SEX_HEADER_PIN_MISMATCH");
        String header = new String(rawHeaderBytes, StandardCharsets.UTF_8);
        var definitions = headerDefinitions(header);
        var result = mapper.createObjectNode().put("schemaVersion", VERSION).put("methodVersion", "1.0.0")
                .put("parentObservationId", parent.path("observationId").asText())
                .put("sourceSnapshot", sourceSnapshot).put("profileSha256", profile.getProfileSha256())
                .put("rawRecordSha256", parent.path("rawRecordSha256").asText())
                .put("headerSha256", HEADER_SHA256).put("dataset", "EXOMES").put("release", "4.1")
                .put("stratumMeaning", "SOURCE_INFERRED_GENETIC_KARYOTYPE_NOT_PATIENT_SEX")
                .put("statisticalQualification", "NOT_EVALUATED").put("criterionEvaluationEnabled", false);
        result.set("resourceContract", parent.path("resourceContract").deepCopy());
        result.set("queryAllele", parent.path("queryAllele").deepCopy());
        result.putObject("headerSource").put("urlTemplate", HEADER_SOURCE).put("sha256", HEADER_SHA256)
                .put("bindingBasis", "FIXED_EXOMES_4_1_HEADER_AND_VERIFIED_PARENT_RESOURCE");
        var region = SexChromosomeRegionResolver.assess(mapper, parent.path("queryAllele"));
        result.set("region", region);
        String outcome = parent.at("/carrier/queryOutcome/status").asText();
        if (!"SUCCEEDED".equals(parent.at("/carrier/resourceExecution/status").asText())
                || !"AVAILABLE".equals(parent.at("/carrier/resourceAvailability/status").asText())
                || !"VERIFIED".equals(parent.path("outputCompleteness").asText())) {
            result.put("status", "UNAVAILABLE").put("reasonCode", "GNOMAD_SEX_PARENT_EXECUTION_UNAVAILABLE");
            result.putArray("strata");
        } else if (!"HIT".equals(outcome)) {
            result.put("status", "NO_RECORD".equals(outcome) ? "NO_RECORD" : "UNAVAILABLE")
                    .put("reasonCode", "NO_RECORD".equals(outcome) ? "GNOMAD_SEX_NO_RECORD" : "GNOMAD_SEX_PARENT_OUTCOME_UNAVAILABLE");
            result.putArray("strata");
        } else if (!List.of("X", "Y").contains(parent.at("/queryAllele/contig").asText())) {
            result.put("status", "NOT_APPLICABLE").put("reasonCode", "GNOMAD_SEX_AUTOSOMAL_QUERY");
            result.putArray("strata");
        } else {
            var parsed = parseRecord(mapper, parent.path("rawRecord").asText(), parent.at("/queryAllele/alternate").asText(), definitions,
                    region.path("region").asText());
            parsed.properties().iterator().forEachRemaining(e -> result.set(e.getKey(), e.getValue()));
        }
        // Region geometry uses long arithmetic. Normalize the JSON tree before sealing so
        // persistence cannot change a LongNode into an IntNode with the same JSON value.
        // Replay still requires complete structural equality; no field or binding is omitted.
        ObjectNode stable = stableJson(mapper, result);
        stable.put("observationId", "gnomad-sex-observation-sha256-" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(stable));
        return stable;
    }

    /** Re-derivation also checks bindings, region provenance, field meanings and numerical source facts. */
    public static void verify(ObjectMapper mapper, JsonNode derived, JsonNode parent, JsonNode context,
            String sourceSnapshot, byte[] rawHeaderBytes) {
        if (!derive(mapper, parent, context, sourceSnapshot, rawHeaderBytes).equals(derived))
            throw new IllegalArgumentException("GNOMAD_SEX_OBSERVATION_INTEGRITY_INVALID");
    }

    /** Full original JOINT header captured independently from the official X and Y resources. */
    public static byte[] loadPinnedJointHeader() {
        try (var input = GnomadSexStratifiedObservation.class.getResourceAsStream(JOINT_HEADER_RESOURCE)) {
            if (input == null) throw new IllegalStateException("GNOMAD_JOINT_SEX_HEADER_RESOURCE_MISSING");
            byte[] bytes = input.readNBytes(1_048_577);
            if (!JOINT_HEADER_SHA256.equals(sha(bytes))) throw new IllegalStateException("GNOMAD_JOINT_SEX_HEADER_PIN_MISMATCH");
            return bytes;
        } catch (java.io.IOException invalid) { throw new IllegalStateException("GNOMAD_JOINT_SEX_HEADER_RESOURCE_UNAVAILABLE", invalid); }
    }

    public static ObjectNode deriveJoint(ObjectMapper mapper, JsonNode parent, JsonNode context, String sourceSnapshot) {
        return deriveJoint(mapper, parent, context, sourceSnapshot, loadPinnedJointHeader());
    }

    /** Uses source-provided JOINT fields only; exome/genome component counts are never summed. */
    public static ObjectNode deriveJoint(ObjectMapper mapper, JsonNode parent, JsonNode context,
            String sourceSnapshot, byte[] rawHeaderBytes) {
        var profile = GnomadPopulationInputProfile.loadExecutableV2();
        PopulationObservation.verify(mapper, parent, context, sourceSnapshot, profile);
        if (!profile.getDocument().path("resources").path(0).path("assembly").equals(parent.at("/queryAllele/assembly")))
            throw new IllegalArgumentException("GNOMAD_JOINT_SEX_RESOURCE_ASSEMBLY_MISMATCH");
        if (rawHeaderBytes == null || rawHeaderBytes.length > 1_048_576 || !JOINT_HEADER_SHA256.equals(sha(rawHeaderBytes)))
            throw new IllegalArgumentException("GNOMAD_JOINT_SEX_HEADER_PIN_MISMATCH");
        var definitions = headerDefinitions(new String(rawHeaderBytes, StandardCharsets.UTF_8));
        var result = mapper.createObjectNode().put("schemaVersion", JOINT_VERSION).put("methodVersion", "1.0.0")
                .put("parentObservationId", parent.path("observationId").asText())
                .put("sourceSnapshot", sourceSnapshot).put("profileSha256", profile.getProfileSha256())
                .put("rawRecordSha256", parent.path("rawRecordSha256").asText())
                .put("headerSha256", JOINT_HEADER_SHA256).put("dataset", "JOINT").put("release", "4.1")
                .put("stratumMeaning", "SOURCE_INFERRED_GENETIC_KARYOTYPE_NOT_PATIENT_SEX")
                .put("statisticOrigin", "SOURCE_PROVIDED_JOINT_FIELDS_NO_LOCAL_COMPONENT_SUM")
                .put("statisticalQualification", "NOT_EVALUATED").put("criterionEvaluationEnabled", false);
        result.set("resourceContract", parent.path("resourceContract").deepCopy());
        result.set("queryAllele", parent.path("queryAllele").deepCopy());
        result.putObject("headerSource").put("urlTemplate", JOINT_HEADER_SOURCE).put("sha256", JOINT_HEADER_SHA256)
                .put("bindingBasis", "FIXED_JOINT_4_1_HEADER_AND_VERIFIED_PARENT_RESOURCE");
        var region = SexChromosomeRegionResolver.assess(mapper, parent.path("queryAllele"));
        result.set("region", region);
        String outcome = parent.at("/carrier/queryOutcome/status").asText();
        if (!"SUCCEEDED".equals(parent.at("/carrier/resourceExecution/status").asText())
                || !"AVAILABLE".equals(parent.at("/carrier/resourceAvailability/status").asText())
                || !"VERIFIED".equals(parent.path("outputCompleteness").asText())) {
            result.put("status", "UNAVAILABLE").put("reasonCode", "GNOMAD_JOINT_SEX_PARENT_EXECUTION_UNAVAILABLE");
            result.putArray("strata");
        } else if (!"HIT".equals(outcome)) {
            result.put("status", "NO_RECORD".equals(outcome) ? "NO_RECORD" : "UNAVAILABLE")
                    .put("reasonCode", "NO_RECORD".equals(outcome) ? "GNOMAD_JOINT_SEX_NO_RECORD" : "GNOMAD_JOINT_SEX_PARENT_OUTCOME_UNAVAILABLE");
            result.putArray("strata");
        } else if (!List.of("X", "Y").contains(parent.at("/queryAllele/contig").asText())) {
            result.put("status", "NOT_APPLICABLE").put("reasonCode", "GNOMAD_JOINT_SEX_AUTOSOMAL_QUERY");
            result.putArray("strata");
        } else {
            var parsed = parseJointRecord(mapper, parent.path("rawRecord").asText(), parent.at("/queryAllele/alternate").asText(),
                    definitions, region.path("region").asText());
            parsed.properties().iterator().forEachRemaining(e -> result.set(e.getKey(), e.getValue()));
        }
        ObjectNode stable = stableJson(mapper, result);
        stable.put("observationId", "gnomad-joint-sex-observation-sha256-" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(stable));
        return stable;
    }

    /** Replays the fixed JOINT profile/header, parent source, raw record and S0 region bindings. */
    public static void verifyJoint(ObjectMapper mapper, JsonNode derived, JsonNode parent, JsonNode context,
            String sourceSnapshot, byte[] rawHeaderBytes) {
        if (!deriveJoint(mapper, parent, context, sourceSnapshot, rawHeaderBytes).equals(derived))
            throw new IllegalArgumentException("GNOMAD_JOINT_SEX_OBSERVATION_INTEGRITY_INVALID");
    }

    static Map<String, String[]> headerDefinitions(String header) {
        var definitions = new LinkedHashMap<String, String[]>();
        for (String line : header.split("\\r?\\n")) {
            var match = INFO.matcher(line);
            if (!match.matches()) continue;
            if (definitions.put(match.group(1), new String[]{match.group(2), match.group(3), match.group(4), line}) != null)
                throw new IllegalArgumentException("GNOMAD_SEX_HEADER_DUPLICATE_INFO");
        }
        return definitions;
    }

    // Package-private lexical core permits synthetic counterexamples without a configurable production trust pin.
    static ObjectNode parseRecord(ObjectMapper mapper, String raw, String targetAlt, Map<String, String[]> definitions, String region) {
        return parseRecord(mapper, raw, targetAlt, definitions, region, ANCESTRIES, "", "gnomad.exomes");
    }

    // Independent lexical controls cannot replace the fixed production JOINT trust pin.
    static ObjectNode parseJointRecord(ObjectMapper mapper, String raw, String targetAlt, Map<String, String[]> definitions, String region) {
        return parseRecord(mapper, raw, targetAlt, definitions, region, JOINT_ANCESTRIES, "_joint", "gnomad.joint");
    }

    private static ObjectNode parseRecord(ObjectMapper mapper, String raw, String targetAlt, Map<String, String[]> definitions,
            String region, List<String> ancestries, String infoNamespace, String metricPrefix) {
        var result = mapper.createObjectNode().put("status", "OBSERVED");
        var rows = result.putArray("strata");
        String[] columns = raw.split("\t", -1);
        if (columns.length < 8 || raw.contains("\n") || raw.contains("\r"))
            throw new IllegalArgumentException("GNOMAD_SEX_RAW_RECORD_INVALID");
        String[] alts = columns[4].split(",", -1); int selected = -1;
        for (int i = 0; i < alts.length; i++) if (targetAlt.equals(alts[i])) {
            if (selected >= 0) throw new IllegalArgumentException("GNOMAD_SEX_ALT_AMBIGUOUS");
            selected = i;
        }
        if (selected < 0) throw new IllegalArgumentException("GNOMAD_SEX_ALT_MISMATCH");
        result.put("selectedAltIndex", selected).put("recordAltCount", alts.length).put("recordFilter", columns[6]);
        var info = new LinkedHashMap<String, String>();
        for (String item : columns[7].split(";", -1)) {
            String[] pair = item.split("=", 2);
            if (pair[0].isBlank() || info.put(pair[0], pair.length == 2 ? pair[1] : "true") != null)
                throw new IllegalArgumentException("GNOMAD_SEX_RAW_INFO_AMBIGUOUS");
        }
        boolean incomplete = false;
        for (String ancestry : ancestries) for (String stratum : List.of("ALL", "XX", "XY")) {
            var row = rows.addObject().put("ancestry", ancestry).put("karyotype", stratum)
                    .put("denominatorUnit", "ALLELES").put("sampleCount", "NOT_SUPPLIED")
                    .put("individualPloidy", "NOT_ESTABLISHED");
            var measures = row.putObject("measurements"); var issues = row.putArray("issues");
            for (String statistic : STATISTICS) {
                String id = statistic + infoNamespace + ("ALL".equals(ancestry) ? "" : "_" + ancestry) + ("ALL".equals(stratum) ? "" : "_" + stratum);
                var value = measures.putObject(statistic).put("infoId", id)
                        .put("metricId", metricPrefix + ".karyotype." + stratum.toLowerCase(java.util.Locale.ROOT) + ".ancestry." + ancestry.toLowerCase(java.util.Locale.ROOT) + "." + statistic.toLowerCase(java.util.Locale.ROOT))
                        .put("unit", List.of("AC", "AN").contains(statistic) ? "alleles" : "fraction");
                var definition = definitions.get(id);
                if (definition == null) {
                    value.put("state", "NOT_DECLARED_BY_HEADER");
                    if (!"faf95".equals(statistic)) add(issues, "GNOMAD_SEX_HEADER_FIELD_NOT_DECLARED", id);
                    continue;
                }
                String number = "AN".equals(statistic) ? "1" : "A";
                String type = List.of("AC", "AN").contains(statistic) ? "Integer" : "Float";
                if (!number.equals(definition[0]) || !type.equals(definition[1]))
                    throw new IllegalArgumentException("GNOMAD_SEX_HEADER_FIELD_CONTRACT_MISMATCH");
                value.put("number", number).put("type", type).put("sourceHeaderLine", definition[3]);
                if ("faf95".equals(statistic)) value.put("confidence", "95_PERCENT_LOWER_BOUND")
                        .put("scope", "ALL".equals(stratum) ? "SOURCE_ALL_KARYOTYPES" : "SEX_CHROMOSOME_NON_PAR_ONLY")
                        .put("applicability", "ALL".equals(stratum) || "NON_PAR".equals(region) ? "APPLICABLE" : "PAR".equals(region) || "AUTOSOMAL".equals(region) ? "NOT_APPLICABLE" : "UNRESOLVED");
                String lexeme = info.get(id);
                String state = lexeme == null ? "MISSING_KEY" : lexeme.isEmpty() ? "EMPTY" : ".".equals(lexeme) ? "DOT" : "PRESENT";
                if (lexeme != null) value.put("rawFieldValue", lexeme);
                if (lexeme != null && "PRESENT".equals(state)) {
                    String[] values = lexeme.split(",", -1);
                    if (values.length != ("A".equals(number) ? alts.length : 1)) state = "CARDINALITY_MISMATCH";
                    else {
                        lexeme = values["A".equals(number) ? selected : 0]; value.put("rawValue", lexeme);
                        if (lexeme.isEmpty() || ".".equals(lexeme)) state = lexeme.isEmpty() ? "EMPTY" : "DOT";
                        else {
                            BigDecimal numeric = number(lexeme);
                            if (numeric == null || numeric.signum() < 0 || "Integer".equals(type) && !lexeme.matches("[0-9]+")
                                    || "Float".equals(type) && numeric.compareTo(BigDecimal.ONE) > 0) state = "INVALID";
                            else value.put("normalizedValue", numeric.signum() == 0 ? "0" : numeric.stripTrailingZeros().toPlainString());
                        }
                    }
                }
                value.put("state", state);
                if (!"PRESENT".equals(state)) add(issues, "GNOMAD_SEX_FIELD_" + state, id);
            }
            validateCounts(measures, issues);
            row.put("status", issues.isEmpty() ? "OBSERVED" : "INCOMPLETE"); incomplete |= !issues.isEmpty();
        }
        return result.put("status", incomplete ? "INCOMPLETE" : "OBSERVED");
    }

    private static void validateCounts(ObjectNode values, ArrayNode issues) {
        BigDecimal ac = parsed(values.path("AC")), an = parsed(values.path("AN")), af = parsed(values.path("AF")), faf = parsed(values.path("faf95"));
        if (ac != null && an != null && ac.compareTo(an) > 0) add(issues, "GNOMAD_SEX_AC_EXCEEDS_AN", "AC/AN");
        if (an != null && an.signum() == 0 && (ac != null && ac.signum() > 0 || af != null && af.signum() > 0))
            add(issues, "GNOMAD_SEX_ZERO_AN_CONFLICT", "AC/AN/AF");
        if (ac != null && an != null && an.signum() > 0 && af != null && !Bs1Quality.countsAgree(ac, an, af))
            add(issues, "GNOMAD_SEX_COUNT_AF_CONFLICT", "AC/AN/AF");
        if (af != null && faf != null && faf.compareTo(af) > 0) add(issues, "GNOMAD_SEX_FAF_EXCEEDS_AF", "AF/faf95");
    }
    private static BigDecimal parsed(JsonNode field) { return "PRESENT".equals(field.path("state").asText()) ? number(field.path("rawValue").asText()) : null; }
    private static BigDecimal number(String raw) {
        if (raw.length() > 80) return null;
        try { var value = new BigDecimal(raw); return value.precision() <= 50 && value.scale() >= -100 && value.scale() <= 100 ? value : null; }
        catch (NumberFormatException invalid) { return null; }
    }
    private static void add(ArrayNode issues, String code, String field) { issues.addObject().put("reasonCode", code).put("field", field); }
    private static ObjectNode stableJson(ObjectMapper mapper, ObjectNode value) {
        try {
            JsonNode readback = mapper.readTree(mapper.writeValueAsBytes(value));
            if (!(readback instanceof ObjectNode object)) throw new IllegalStateException("GNOMAD_SEX_CANONICAL_COPY_INVALID");
            return object;
        } catch (java.io.IOException invalid) { throw new IllegalStateException("GNOMAD_SEX_CANONICAL_COPY_FAILED", invalid); }
    }
    private static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
