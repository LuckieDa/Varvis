package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.springframework.lang.Nullable;

/** Consumes the A10 receipt from a service-owned assessor; it never classifies coordinates. */
final class DeNovoA10RegionSupply {
    private static final String COORDINATES = "ONE_BASED_CLOSED_REFERENCE_BASES_WITH_INSERTION_FLANKS";
    private static final String BOUNDARY = "TRIM_SHARED_PADDING_THEN_CLASSIFY_ENTIRE_EDIT_V1";
    private DeNovoA10RegionSupply() { }

    /** The chief wires SexChromosomeRegionResolver.assess; request/case region fields are not an assessor. */
    static DeNovoSpecialEvent.RegionSupply fromAssessor(ObjectMapper mapper, String expectedCatalogSha,
                                                        Function<JsonNode, JsonNode> assessor) {
        Objects.requireNonNull(mapper); Objects.requireNonNull(assessor);
        if (expectedCatalogSha == null || !expectedCatalogSha.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("DENOVO_A10_CATALOG_PIN_INVALID");
        var identity = new RuntimeEvidenceV2Adapter(mapper);
        return input -> {
            String assembly = input == null ? "" : input.path("assembly").asText();
            String querySha;
            try { querySha = identity.canonicalSha256(input); }
            catch (IllegalArgumentException invalid) {
                return fact(assembly, "", "UNKNOWN", expectedCatalogSha, "DENOVO_A10_QUERY_BINDING_INVALID");
            }
            if (input == null || !input.isObject())
                return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, "DENOVO_A10_QUERY_BINDING_INVALID");
            JsonNode original = input.deepCopy();
            JsonNode receipt;
            try { receipt = assessor.apply(original.deepCopy()); }
            catch (RuntimeException failed) {
                return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, "DENOVO_A10_ASSESSOR_FAILED");
            }
            if (receipt == null || !receipt.isObject()
                    || !"sex-chromosome-region-v1".equals(receipt.path("schemaVersion").asText())
                    || !receipt.path("methodVersion").asText().matches("1\\.[0-9]+\\.[0-9]+")
                    || !expectedCatalogSha.equals(receipt.path("sourceCatalogSha256").asText())
                    || !COORDINATES.equals(receipt.path("coordinateSystem").asText())
                    || !BOUNDARY.equals(receipt.path("boundaryConvention").asText())
                    || !explicitFalse(receipt, "clinicalQualificationGranted") || !explicitFalse(receipt, "patientPloidyInferred"))
                return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, "DENOVO_A10_RECEIPT_BINDING_INVALID");
            try {
                if (!querySha.equals(identity.canonicalSha256(receipt.path("queryAllele"))))
                    return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, "DENOVO_A10_QUERY_BINDING_INVALID");
            } catch (IllegalArgumentException invalid) {
                return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, "DENOVO_A10_QUERY_BINDING_INVALID");
            }
            String status = receipt.path("status").asText(), region = receipt.path("region").asText();
            String reason = receipt.path("reasonCode").asText();
            if (!reason.isBlank() && !reason.matches("[A-Z][A-Z0-9_]{0,160}"))
                return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, "DENOVO_A10_RECEIPT_BINDING_INVALID");
            boolean unresolved = "UNRESOLVED".equals(status) && "UNKNOWN".equals(region);
            // Early S0 failures can precede the query hash and reference binding; they cannot grant a region.
            if (unresolved && reason.matches("[A-Z][A-Z0-9_]{0,160}"))
                return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, reason);
            if (!querySha.equals(receipt.path("queryAlleleSha256").asText()))
                return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, "DENOVO_A10_QUERY_BINDING_INVALID");
            if ("NOT_APPLICABLE".equals(status) && "AUTOSOMAL".equals(region) && "SEX_REGION_AUTOSOMAL_QUERY".equals(reason))
                return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, reason);
            if (!("RESOLVED".equals(status) && Set.of("PAR", "NON_PAR").contains(region))
                    && !("UNRESOLVED".equals(status) && "CROSSES_BOUNDARY".equals(region)
                        && "SEX_REGION_EVENT_CROSSES_PAR_BOUNDARY".equals(reason)))
                return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, "DENOVO_A10_REGION_STATUS_INVALID");
            if (!completeSources(receipt, assembly))
                return fact(assembly, querySha, "UNKNOWN", expectedCatalogSha, "DENOVO_A10_SOURCE_BINDING_INVALID");
            return fact(assembly, querySha, "CROSSES_BOUNDARY".equals(region) ? "CROSS_BOUNDARY" : region,
                    expectedCatalogSha, reason.isBlank() ? null : reason);
        };
    }

    private static boolean explicitFalse(JsonNode receipt, String key) {
        return receipt.path(key).isBoolean() && !receipt.path(key).booleanValue();
    }

    private static boolean completeSources(JsonNode receipt, String assembly) {
        String version = receipt.path("sourceVersion").asText(), accession = receipt.path("assemblyAccession").asText();
        String originalContig = receipt.at("/queryAllele/contig").asText();
        String resolvedContig = originalContig.startsWith("chr") ? originalContig.substring(3) : originalContig;
        boolean aliasUsed = !originalContig.equals(resolvedContig);
        if (!Set.of("GRCh37", "GRCh38").contains(assembly) || !version.matches(assembly + "\\.p[1-9][0-9]*")
                || !Set.of("X", "Y").contains(resolvedContig) || !resolvedContig.equals(receipt.path("resolvedContig").asText())
                || !receipt.path("contigAliasUsed").isBoolean() || receipt.path("contigAliasUsed").booleanValue() != aliasUsed
                || !accession.matches("GCF_[0-9]{9}\\.[1-9][0-9]*")
                || !receipt.at("/referenceSequence/accession").asText().matches("NC_[0-9]{6}\\.[1-9][0-9]*")
                || !receipt.at("/referenceSequence/length").isIntegralNumber() || receipt.at("/referenceSequence/length").asLong() <= 0
                || !receipt.path("sourceBindings").isArray() || receipt.path("sourceBindings").size() != 2
                || !receipt.path("event").isObject() || !receipt.path("classificationSpan").isObject()) return false;
        Set<String> required = Set.of(accession + "_" + version + "_assembly_regions.txt", accession + "_" + version + "_assembly_report.txt");
        Set<String> found = new HashSet<>();
        for (var source : receipt.path("sourceBindings")) {
            String id = source.path("sourceId").asText();
            if (!required.contains(id) || !found.add(id) || !source.path("sha256").asText().matches("[0-9a-f]{64}")
                    || !source.path("bytes").isIntegralNumber() || source.path("bytes").asLong() <= 0) return false;
            try {
                URI url = URI.create(source.path("url").asText());
                String path = url.getPath();
                if (!"https".equals(url.getScheme()) || !"ftp.ncbi.nlm.nih.gov".equals(url.getHost())
                        || url.getPort() != -1 || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                        || path == null || !path.startsWith("/genomes/all/") || !path.endsWith("/" + id)) return false;
            } catch (IllegalArgumentException invalid) { return false; }
        }
        return found.equals(required);
    }

    private static DeNovoSpecialEvent.RegionFact fact(String assembly, String querySha, String region, String catalogSha, @Nullable String reason) {
        return new DeNovoSpecialEvent.RegionFact(assembly, querySha, region, catalogSha, reason);
    }
}
