package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Fixed release semantics plus checked actual resource headers. No gene-specific logic. */
final class Bs1JointFilterSource {
    static final String VERSION = "bs1-joint-filter-adapter-v1";
    private Bs1JointFilterSource() { }
    static JsonNode contract(ObjectMapper mapper) {
        try (var in = Bs1JointFilterSource.class.getResourceAsStream("/acmg/bs1-joint-filter-source-v1.json")) {
            if (in == null) throw new java.io.IOException("missing joint filter source");
            return mapper.readTree(in);
        } catch (java.io.IOException invalid) { throw new IllegalStateException("BS1_JOINT_FILTER_SOURCE_UNAVAILABLE", invalid); }
    }
    private static final java.util.Set<String> SEX_CONTIGS = java.util.Set.of("X", "Y");
    static JsonNode sexContract(ObjectMapper mapper) {
        try (var in = Bs1JointFilterSource.class.getResourceAsStream("/acmg/bs1-joint-sex-filter-source-v1.json")) {
            if (in == null) throw new java.io.IOException("missing sex joint filter source");
            return mapper.readTree(in);
        } catch (java.io.IOException invalid) { throw new IllegalStateException("BS1_JOINT_FILTER_SOURCE_UNAVAILABLE", invalid); }
    }
    static JsonNode forObservation(ObjectMapper mapper, JsonNode observation) {
        return SEX_CONTIGS.contains(observation.at("/queryAllele/contig").asText()) ? sexContract(mapper) : contract(mapper);
    }
    static boolean bound(ObjectMapper mapper, JsonNode observation, JsonNode release) {
        var source = forObservation(mapper, observation);
        var transport = observation.path("transport");
        var header = source.path("headers").path(observation.at("/queryAllele/contig").asText());
        var resource = observation.path("resourceContract");
        return source.path("definitionId").equals(release.path("jointFilterDefinition"))
                && source.path("release").equals(resource.at("/identity/release"))
                && source.path("assembly").equals(observation.at("/queryAllele/assembly"))
                && source.path("resourceFingerprintSha256").equals(resource.path("identityFingerprintSha256"))
                && source.path("resourceFingerprintSha256").equals(observation.at("/carrier/source/resourceSha256"))
                && header.isObject() && header.path("fieldDefinitionsSha256").equals(source.path("fieldDefinitionsSha256"))
                && header.path("url").equals(transport.path("resourceUri"))
                && (header.path("url").asText() + ".tbi").equals(transport.path("resourceIndexUri").asText())
                && "remote-joint-vcf-population-input".equals(transport.path("querySource").asText())
                && "joint".equals(transport.path("sourceDataset").asText())
                && resource.path("resourceVersion").equals(transport.path("gnomadVersion"));
    }
}
