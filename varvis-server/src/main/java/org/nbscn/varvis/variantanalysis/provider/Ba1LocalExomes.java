package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/** Local-only transport and explicit data-scope adaptation; never manufactures joint facts. */
public final class Ba1LocalExomes {
    public static final String VERSION = "ba1-local-exomes-v1";
    private Ba1LocalExomes() { }

    static GnomadTabixProvider provider(VariantAnalysisProperties original) {
        var settings = new VariantAnalysisProperties();
        var target = settings.getGnomad(); var source = original.getGnomad();
        for (Path path : source.getGrch38Files()) requireLocal(path);
        target.setEnabled(true); target.setPopulationInputEnabled(true);
        target.setVersion("gnomAD exomes v4.1 GRCh38"); target.setGrch38Files(source.getGrch38Files().stream()
                .map(mappedItem -> mappedItem.toString()).collect(Collectors.joining(",")));
        target.setTabixExecutable(source.getTabixExecutable()); target.setTimeout(source.getTimeout());
        target.setLocalPopulationInProcessEnabled(source.isLocalPopulationInProcessEnabled());
        target.setParallelism(1); target.setAllowFullVcfFallback(false); target.setAllowVcfInfoFallback(false);
        // false selects local indexed files; no API client, Range transport, or subtype supplement is constructed.
        return new GnomadTabixProvider(settings, GnomadPopulationInputProfile.loadLocalBa1(), false, true);
    }

    public static ObjectNode identity(ObjectMapper mapper, VariantAnalysisProperties settings) {
        var result = mapper.createObjectNode().put("version", VERSION).put("remoteQueriesEnabled", false);
        if (settings.getGnomad().isLocalPopulationInProcessEnabled())
            result.set("localPopulationReader", mapper.valueToTree(GnomadTabixProvider.localPopulationTransportIdentity()));
        var files = result.putArray("resources");
        for (Path path : settings.getGnomad().getGrch38Files()) {
            requireLocal(path);
            var item = files.addObject().put("fileName", path.getFileName().toString())
                    .put("locationSha256", sha256(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)));
            try {
                item.put("bytes", Files.size(path)).put("mtime", Files.getLastModifiedTime(path).toString());
                Path index = Path.of(path + ".tbi");
                if (Files.isRegularFile(index) && Files.size(index) <= 4 * 1024 * 1024)
                    item.put("indexSha256", sha256(Files.readAllBytes(index)));
                else item.put("indexStatus", "MISSING_OR_UNSUPPORTED");
            } catch (IOException unavailable) { item.put("status", "UNAVAILABLE"); }
        }
        result.put("tabixCommandSha256", sha256(settings.getGnomad().getTabixExecutable().getBytes(StandardCharsets.UTF_8)));
        return result;
    }

    private static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void requireLocal(Path path) {
        String value = path.toString();
        if (value.startsWith("\\\\") || value.startsWith("//") || value.contains("://"))
            throw new IllegalArgumentException("BA1_LOCAL_RESOURCE_REQUIRED");
    }

    static ObjectNode selection(ObjectMapper mapper, ObjectNode original) {
        ObjectNode selection = original.deepCopy();
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        selection.put("sourceSelectionId", original.path("selectionId").asText());
        if (original.path("policy").isObject()) selection.put("sourcePolicySha256", adapter.canonicalSha256(original.path("policy")));
        if ("SELECTED".equals(original.path("status").asText())
                && Ba1SpecificationCatalog.JOINT_DATASET.equals(original.at("/policy/clause/requiredDataset").asText())) {
            String reason = "BA1_JOINT_POPULATION_INPUT_REQUIRED";
            selection.put("status", "BLOCKED").put("reasonCode", reason);
            selection.withArray("requiredInputs").addObject().put("field", "jointPopulationObservation").put("reason", reason);
        } else {
            replaceMetricNamespace(selection.path("policy"));
        }
        selection.set("dataScope", GnomadPopulationInputProfile.loadLocalBa1().getDocument().path("localDataPolicy"));
        selection.remove("selectionId");
        selection.put("selectionId", "ba1-local-selection-sha256-" + adapter.canonicalSha256(selection));
        return selection;
    }

    private static void replaceMetricNamespace(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = (ObjectNode) value;
            var names = new java.util.ArrayList<String>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.path(name);
                if (child.isTextual() && child.asText().startsWith("gnomad.joint."))
                    object.put(name, "gnomad.exomes." + child.asText().substring("gnomad.joint.".length()));
                else replaceMetricNamespace(child);
            }
        } else if (value.isArray()) for (JsonNode child : value) replaceMetricNamespace(child);
    }
}
