package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Additional native fields of an already source-verified JOINT4.1 RAW record.
 * This class cannot issue input authority: only the opaque native supply calls it.
 * A raw allele upper bound is never an exact person count or positive evidence.
 */
final class Ba1NativeCountBounds {
    private Ba1NativeCountBounds() { }

    record Bounds(@Nullable BigInteger hemizygoteLowerBound, @Nullable BigInteger personUpperBound,
                  ObjectNode details) { }
    private record Value(@Nullable BigDecimal number, ObjectNode detail) { }

    static Bounds read(ObjectMapper mapper, String raw, JsonNode allele, JsonNode region,
                       Map<String, String[]> definitions, @Nullable BigInteger hom,
                       BigInteger adjAc, BigInteger adjAn) {
        String[] columns = raw.split("\t", -1), alts = columns[4].split(",", -1);
        int selected = -1;
        for (int i = 0; i < alts.length; i++) if (alts[i].equals(allele.path("alternate").asText())) {
            require(selected == -1, "ALT_AMBIGUOUS"); selected = i;
        }
        require(selected >= 0, "ALT_MISMATCH");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String item : columns[7].split(";", -1)) {
            String[] pair = item.split("=", 2);
            require(!pair[0].isBlank() && fields.put(pair[0], pair.length == 2 ? pair[1] : "true") == null,
                    "DUPLICATE_INFO");
        }
        var details = mapper.createObjectNode();
        var readings = details.putArray("readings");
        Value rawAc = value(mapper, fields, definitions, "AC_joint_raw", selected, alts.length);
        Value rawAn = value(mapper, fields, definitions, "AN_joint_raw", selected, alts.length);
        Value rawAf = value(mapper, fields, definitions, "AF_joint_raw", selected, alts.length);
        readings.add(rawAc.detail()); readings.add(rawAn.detail()); readings.add(rawAf.detail());
        BigInteger upper = null;
        var upperDetail = details.putObject("personUpperBound").put("status", "UNAVAILABLE")
                .put("reasonCode", "BA1_NATIVE_COUNT_ALL_RAW_STATISTICS_MISSING")
                .put("scope", "ALL_JOINT_RAW_TARGET_ALT_CALLS_INCLUDING_PRE_ADJ")
                .put("countUnit", "individuals").put("sourceMetric", "AC_joint_raw")
                .put("sourceUnit", "alleles").put("exactPersonCount", false);
        var rawCount = rawAc.number(); var rawDenominator = rawAn.number(); var rawFrequency = rawAf.number();
        if (rawCount != null && rawDenominator != null && rawFrequency != null) {
            validTriple(rawAc, rawAn, rawAf, "RAW");
            upper = rawCount.toBigIntegerExact();
            require(upper.compareTo(adjAc) >= 0 && rawDenominator.toBigIntegerExact().compareTo(adjAn) >= 0,
                    "RAW_ADJ_SCOPE_CONFLICT");
            upperDetail.put("status", "QUALIFIED_UPPER_BOUND").put("value", upper.toString())
                    .put("reasonCode", "BA1_NATIVE_COUNT_ALL_RAW_ALT_BOUNDS_ALL_COUNTED_PEOPLE")
                    .put("proof", "Each homo/hemi individual counted in this release contributes at least one target ALT in the same ALL raw callset; filtering or clinical subsets cannot increase this set.");
        }
        BigInteger hemi = null;
        var hemiDetail = details.putObject("hemizygoteLowerBound").put("status", "UNAVAILABLE")
                .put("reasonCode", "BA1_NATIVE_COUNT_NATIVE_NONPAR_SCOPE_REQUIRED")
                .put("scope", "XY_JOINT_ADJ_NON_PAR_TARGET_ALT_CALLS")
                .put("unit", "individuals").put("completeHemizygoteClass", false);
        // The source indexes genotype ploidy by its VCF locus. S0 classifies the
        // complete edit; both must be NON_PAR, including an indel's anchor.
        if ("RESOLVED".equals(region.path("status").asText()) && "NON_PAR".equals(region.path("region").asText())
                && List.of("X", "Y").contains(allele.path("contig").asText()) && nativeLocusNonPar(allele, region)) {
            Value xyAc = value(mapper, fields, definitions, "AC_joint_XY", selected, alts.length);
            Value xyAn = value(mapper, fields, definitions, "AN_joint_XY", selected, alts.length);
            Value xyAf = value(mapper, fields, definitions, "AF_joint_XY", selected, alts.length);
            Value xyHom = value(mapper, fields, definitions, "nhomalt_joint_XY", selected, alts.length);
            readings.add(xyAc.detail()); readings.add(xyAn.detail()); readings.add(xyAf.detail()); readings.add(xyHom.detail());
            hemiDetail.put("reasonCode", "BA1_NATIVE_COUNT_NATIVE_XY_STATISTICS_MISSING");
            var xyCount = xyAc.number(); var xyDenominator = xyAn.number(); var xyFrequency = xyAf.number(); var xyHomozygotes = xyHom.number();
            if (xyCount != null && xyDenominator != null && xyFrequency != null && xyHomozygotes != null) {
                if (xyCount.signum() == 0 && xyDenominator.signum() == 0 && xyFrequency.signum() == 0 && xyHomozygotes.signum() == 0) {
                    hemiDetail.put("reasonCode", "BA1_NATIVE_COUNT_XY_NO_CALLED_ALLELES");
                    return new Bounds(null, upper, details); // Empty coverage is not an observed zero people count.
                }
                validTriple(xyAc, xyAn, xyAf, "XY");
                require(xyHomozygotes.signum() == 0, "XY_NONPAR_PLOIDY_CONFLICT");
                hemi = xyCount.toBigIntegerExact();
                require(hemi.compareTo(adjAc) <= 0 && xyDenominator.toBigIntegerExact().compareTo(adjAn) <= 0,
                        "XY_ALL_SCOPE_CONFLICT");
                if (hom != null) require(hom.shiftLeft(1).add(hemi).compareTo(adjAc) <= 0,
                        "HOMO_HEMI_ALT_CONFLICT");
                hemiDetail.put("status", "QUALIFIED_LOWER_BOUND").put("value", hemi.toString())
                        .put("reasonCode", "BA1_NATIVE_COUNT_SOURCE_NONPAR_XY_HAPLOID_ALT_LOWER_BOUND")
                        .put("qualificationBasis", "Fixed native source explicitly interprets same-stratum XY-adj AC as hemizygotes only on NON_PAR; actual source ploidy pipeline, unique release people, zero XY diploid homo and coherent original fields are required.");
            }
        }
        return new Bounds(hemi, upper, details);
    }

    private static boolean nativeLocusNonPar(JsonNode allele, JsonNode region) {
        long position = allele.path("position").asLong();
        if (position < 1 || !region.path("referenceRegions").isArray() || region.path("referenceRegions").isEmpty()) return false;
        for (var par : region.path("referenceRegions"))
            if (position >= par.path("start").asLong() && position <= par.path("end").asLong()) return false;
        return true;
    }

    private static Value value(ObjectMapper mapper, Map<String, String> fields, Map<String, String[]> definitions,
                               String id, int selected, int altCount) {
        boolean fraction = id.startsWith("AF_");
        String number = id.startsWith("AN_") ? "1" : "A", type = fraction ? "Float" : "Integer";
        String[] definition = definitions.get(id);
        if (definition == null) throw new IllegalArgumentException("BA1_NATIVE_COUNT_HEADER_UNSUPPORTED");
        require(number.equals(definition[0]) && type.equals(definition[1]), "HEADER_UNSUPPORTED");
        ObjectNode detail = mapper.createObjectNode().put("infoId", id).put("unit", fraction ? "fraction" : id.startsWith("nhomalt") ? "individuals" : "alleles")
                .put("headerDefinition", definition[2]);
        String text = fields.get(id);
        if (text == null || ".".equals(text)) return new Value(null, detail.put("status", "MISSING"));
        String[] items = text.split(",", -1);
        require(items.length == ("A".equals(number) ? altCount : 1), "ALT_CARDINALITY_INVALID");
        String raw = items["A".equals(number) ? selected : 0];
        if (".".equals(raw)) return new Value(null, detail.put("status", "MISSING"));
        require(raw.length() <= 80 && (fraction || raw.matches("[0-9]{1,64}")), "NUMBER_INVALID");
        BigDecimal parsed;
        try { parsed = new BigDecimal(raw); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("BA1_NATIVE_COUNT_NUMBER_INVALID", invalid); }
        require(parsed.signum() >= 0 && parsed.precision() <= 50 && parsed.scale() >= -100 && parsed.scale() <= 100
                && (!fraction || parsed.compareTo(BigDecimal.ONE) <= 0), "NUMBER_INVALID");
        return new Value(parsed, detail.put("status", "PRESENT").put("rawValue", raw));
    }

    private static void validTriple(Value ac, Value an, Value af, String name) {
        BigDecimal count = ac.number(), denominator = an.number(), frequency = af.number();
        require(count != null && denominator != null && frequency != null, name + "STATISTICS_MISSING");
        if (count == null || denominator == null || frequency == null) return;
        // AN is allele opportunity, including reference calls; never AN/2 people.
        require(denominator.signum() > 0 && count.compareTo(denominator) <= 0
                && Bs1Quality.countsAgree(count, denominator, frequency), name + "_COUNT_AF_CONFLICT");
    }

    private static void require(boolean valid, String suffix) {
        if (!valid) throw new IllegalArgumentException("BA1_NATIVE_COUNT_" + suffix);
    }
}
