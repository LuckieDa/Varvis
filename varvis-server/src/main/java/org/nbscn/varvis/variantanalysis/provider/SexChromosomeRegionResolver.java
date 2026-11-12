package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Versioned primary-reference geometry only. This does not infer a person's
 * sex/ploidy, combine X/Y alleles, or authorize an evidence criterion. */
public final class SexChromosomeRegionResolver {
    public static final String VERSION = "1.0.0";
    public static final String CATALOG_SHA256 = "41b99cb587f84e0f9823a7994ea29573a3f6aad619ebe02d011e02b964b820aa";
    private static final String RESOURCE = "/acmg/sex-chromosome-regions-v1.json";
    private static final int MAX_ALLELE_LENGTH = 50;
    private static final Set<String> FIELDS = Set.of("assembly", "contig", "position", "reference", "alternate");
    private SexChromosomeRegionResolver() { }

    /** Input is the original normalized query allele. Coordinate aliases are
     * resolved only for this calculation; the original allele remains bound. */
    public static ObjectNode assess(ObjectMapper mapper, JsonNode allele) {
        var out = resolve(mapper, allele);
        try {
            // A JSON receipt must survive storage: Jackson distinguishes an
            // in-memory LongNode from the same persisted small integer.
            var stable = mapper.readTree(mapper.writeValueAsBytes(out));
            if (stable instanceof ObjectNode object) return object;
        } catch (java.io.IOException invalid) {
            return out.put("status", "UNRESOLVED").put("region", "UNKNOWN")
                    .put("reasonCode", "SEX_REGION_RESULT_SERIALIZATION_FAILED");
        }
        return out.put("status", "UNRESOLVED").put("region", "UNKNOWN")
                .put("reasonCode", "SEX_REGION_RESULT_SERIALIZATION_FAILED");
    }

    private static ObjectNode resolve(ObjectMapper mapper, JsonNode allele) {
        var out = mapper.createObjectNode().put("schemaVersion", "sex-chromosome-region-v1")
                .put("methodVersion", VERSION).put("sourceCatalogSha256", CATALOG_SHA256)
                .put("coordinateSystem", "ONE_BASED_CLOSED_REFERENCE_BASES_WITH_INSERTION_FLANKS")
                .put("boundaryConvention", "TRIM_SHARED_PADDING_THEN_CLASSIFY_ENTIRE_EDIT_V1")
                .put("clinicalQualificationGranted", false).put("patientPloidyInferred", false)
                .put("status", "UNRESOLVED").put("region", "UNKNOWN");
        if (allele == null || !allele.isObject()) return fail(out, "SEX_REGION_ALLELE_INVALID");
        out.set("queryAllele", allele.deepCopy());
        var names = new java.util.HashSet<String>(); allele.fieldNames().forEachRemaining(names::add);
        if (!FIELDS.equals(names) || !allele.path("position").isIntegralNumber()
                || !allele.path("position").canConvertToInt() || allele.path("position").asInt() < 1
                || !allele.path("assembly").isTextual() || !allele.path("contig").isTextual()
                || !allele.path("reference").isTextual() || !allele.path("alternate").isTextual())
            return fail(out, "SEX_REGION_ALLELE_INVALID");
        out.put("queryAlleleSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(allele));
        String assembly = allele.path("assembly").asText();
        if (!Set.of("GRCh37", "GRCh38").contains(assembly)) return fail(out, "SEX_REGION_ASSEMBLY_UNSUPPORTED");
        String originalContig = allele.path("contig").asText();
        String contig = originalContig.startsWith("chr") ? originalContig.substring(3) : originalContig;
        out.put("resolvedContig", contig).put("contigAliasUsed", !contig.equals(originalContig));
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        if (!ref.matches("[ACGT]+") || !alt.matches("[ACGT]+") || ref.equals(alt)
                || ref.length() > MAX_ALLELE_LENGTH || alt.length() > MAX_ALLELE_LENGTH)
            return fail(out, "SEX_REGION_SMALL_ALLELE_UNSUPPORTED");
        if (contig.matches("[1-9]|1[0-9]|2[0-2]"))
            return out.put("status", "NOT_APPLICABLE").put("region", "AUTOSOMAL")
                    .put("reasonCode", "SEX_REGION_AUTOSOMAL_QUERY");
        if (!Set.of("X", "Y").contains(contig)) return fail(out, "SEX_REGION_CONTIG_UNSUPPORTED");
        try {
            JsonNode catalog = catalog(); JsonNode chromosome = null;
            for (var row : catalog.path("chromosomes"))
                if (assembly.equals(row.path("assembly").asText()) && contig.equals(row.path("contig").asText())) {
                    if (chromosome != null) return fail(out, "SEX_REGION_CATALOG_AMBIGUOUS");
                    chromosome = row;
                }
            if (chromosome == null) return fail(out, "SEX_REGION_REFERENCE_UNAVAILABLE");
            long position = allele.path("position").asLong(), length = chromosome.path("length").asLong();
            if (position + ref.length() - 1 > length) return fail(out, "SEX_REGION_REFERENCE_SPAN_OUT_OF_RANGE");
            out.put("sourceVersion", chromosome.path("patchRelease").asText())
                    .put("assemblyAccession", chromosome.path("assemblyAccession").asText());
            out.putObject("referenceSequence").put("accession", chromosome.path("referenceAccession").asText()).put("length", length);
            var bindings = out.putArray("sourceBindings");
            for (var source : catalog.path("sources"))
                if (source.path("sourceId").equals(chromosome.path("regionsSourceId"))
                        || source.path("sourceId").equals(chromosome.path("referenceSourceId"))) bindings.add(source.deepCopy());
            out.set("referenceRegions", chromosome.path("pseudoautosomalRegions").deepCopy());
            int prefix = 0, suffix = 0;
            while (prefix < Math.min(ref.length(), alt.length()) && ref.charAt(prefix) == alt.charAt(prefix)) prefix++;
            while (suffix < Math.min(ref.length(), alt.length()) - prefix
                    && ref.charAt(ref.length() - suffix - 1) == alt.charAt(alt.length() - suffix - 1)) suffix++;
            int consumed = ref.length() - prefix - suffix, inserted = alt.length() - prefix - suffix;
            long start = position + prefix, end = start + consumed - 1;
            var edit = out.putObject("event").put("trimmedPrefixBases", prefix).put("trimmedSuffixBases", suffix)
                    .put("referenceBasesConsumed", consumed).put("alternateBasesInserted", inserted)
                    .put("type", consumed == 0 ? "INSERTION" : inserted == 0 ? "DELETION" : consumed == inserted ? "SUBSTITUTION" : "REPLACEMENT");
            String role = "AFFECTED_REFERENCE_BASES";
            if (consumed == 0) {
                edit.putObject("insertionBreakpoint").put("leftFlank", start - 1).put("rightFlank", start);
                start--; end = start + 1; role = "INSERTION_FLANKS";
            } else edit.putObject("affectedReferenceInterval").put("start", start).put("end", end);
            if (start < 1 || end > length) return fail(out, "SEX_REGION_EVENT_FLANK_OUT_OF_RANGE");
            out.putObject("classificationSpan").put("start", start).put("end", end).put("role", role);
            List<String> intersected = new ArrayList<>(); String containing = null;
            for (var par : chromosome.path("pseudoautosomalRegions")) {
                long lo = par.path("start").asLong(), hi = par.path("end").asLong();
                if (start <= hi && end >= lo) intersected.add(par.path("regionId").asText());
                if (start >= lo && end <= hi) containing = par.path("regionId").asText();
            }
            var ids = out.putArray("intersectedParRegions"); intersected.forEach(ids::add);
            if (containing != null) return out.put("status", "RESOLVED").put("region", "PAR").put("regionId", containing);
            if (intersected.isEmpty()) return out.put("status", "RESOLVED").put("region", "NON_PAR").put("regionId", "NON_PAR");
            return out.put("region", "CROSSES_BOUNDARY").put("reasonCode", "SEX_REGION_EVENT_CROSSES_PAR_BOUNDARY");
        } catch (java.io.IOException | IllegalStateException invalid) {
            return fail(out, "SEX_REGION_REFERENCE_UNAVAILABLE");
        }
    }

    private static ObjectNode fail(ObjectNode out, String reason) { return out.put("reasonCode", reason); }

    private static JsonNode catalog() throws java.io.IOException {
        try (var in = SexChromosomeRegionResolver.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new java.io.IOException("missing catalog");
            byte[] raw = in.readNBytes(65_537);
            try {
                if (raw.length > 65_536 || !CATALOG_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))))
                    throw new java.io.IOException("catalog bytes differ");
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            var strict = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var value = strict.readTree(raw);
            if (value == null || !"sex-chromosome-region-catalog-v1".equals(value.path("schemaVersion").asText())
                    || !VERSION.equals(value.path("catalogVersion").asText()) || value.path("chromosomes").size() != 4)
                throw new java.io.IOException("catalog schema differs");
            return value;
        }
    }
}
