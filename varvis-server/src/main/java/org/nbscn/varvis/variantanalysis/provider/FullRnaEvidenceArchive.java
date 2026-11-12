package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;

/** Private, self-contained originals and exact mechanical facts. Reading never creates live source tokens. */
public final class FullRnaEvidenceArchive {
    public static final String VERSION = "1.0.0";
    private FullRnaEvidenceArchive() { }

    static ObjectNode create(ObjectMapper mapper, Collection<ReviewedRuleBundle.FullRnaPrepared> prepared, ObjectNode batch) {
        var hashes = new RuntimeEvidenceV2Adapter(mapper);
        var out = mapper.createObjectNode().put("schemaVersion", "full-rna-evidence-archive-v1").put("methodVersion", VERSION)
                .put("restoresLiveQualification", false).put("executionSourceSha256", batch.path("sourceSnapshotSha256").asText());
        var originals = out.putObject("originalsByRawSha256"); var materials = out.putObject("materialsBySha256");
        if (batch.path("fullRnaObservedSourceIdentity").isObject()) out.set("sourceObservationIdentity", batch.path("fullRnaObservedSourceIdentity").deepCopy());
        var sorted = new TreeMap<String, ObjectNode>();
        for (var value : prepared) {
            var material = value.privateSnapshot(mapper); String sha = material.path("originalSha256").asText();
            if (sha.matches("[0-9a-f]{64}")) {
                var original = mapper.createObjectNode().put("rawSha256", sha).put("encoding", "UTF-8")
                        .put("originalUtf8", material.path("originalUtf8").asText());
                if (!sha.equals(CodingTranscriptMaterialSupply.sha(original.path("originalUtf8").asText().getBytes(StandardCharsets.UTF_8))))
                    throw new IllegalArgumentException("FULL_RNA_ARCHIVE_ORIGINAL_BYTES_MISMATCH");
                var existing = originals.get(sha);
                if (existing != null && !existing.equals(original)) throw new IllegalArgumentException("FULL_RNA_ARCHIVE_ORIGINAL_COLLISION");
                originals.set(sha, original);
                material.remove(List.of("originalUtf8", "sourceOriginal")); material.put("originalReference", sha);
            }
            sorted.put(hashes.canonicalSha256(material), material);
        }
        sorted.forEach(materials::set);
        var judgments = out.putArray("judgments");
        for (String module : List.of("pvs1", "pm4", "bp3")) {
            int index = 0;
            for (var raw : batch.at("/modules/" + module + "/results")) {
                if (raw.path("fullRnaCurrentUse").isObject()) {
                    var item = judgments.addObject().put("modulePointer", "/modules/" + module + "/results/" + index)
                            .put("moduleSha256", hashes.canonicalSha256(raw));
                    item.set("evaluation", raw.path("evaluation").deepCopy());
                    item.set("application", raw.path("fullRnaCurrentUse").deepCopy());
                }
                index++;
            }
        }
        out.put("archiveSha256", hashes.canonicalSha256(out)); return out;
    }

    /** New runtime identity requires an archive even when all source preparation attempts failed. */
    public static boolean required(JsonNode fullBatch) {
        if (!fullBatch.at("/identity/fullRnaExecution/capabilityConfigured").asBoolean()) return false;
        var observed = fullBatch.path("fullRnaObservedSourceIdentity");
        if (observed.isObject() && !observed.path("archiveRequired").asBoolean(true)) return false;
        for (var code : fullBatch.path("executionCriteria"))
            if (List.of("ACMG_AMP_2015:PVS1", "ACMG_AMP_2015:PM4", "ACMG_AMP_2015:BP3").contains(code.asText())) return true;
        return false;
    }

    /** Exact saved data verification only: no provider, evaluator, session or scientific token is reconstructed. */
    public static ObjectNode verifyAndExport(ObjectMapper mapper, JsonNode archive, JsonNode fullBatch) {
        if (!(archive instanceof ObjectNode object) || !"full-rna-evidence-archive-v1".equals(archive.path("schemaVersion").asText()))
            throw new IllegalArgumentException("FULL_RNA_ARCHIVE_REQUIRED");
        var hashes = new RuntimeEvidenceV2Adapter(mapper); var body = object.deepCopy(); body.remove("archiveSha256");
        require(hashes.canonicalSha256(body).equals(archive.path("archiveSha256").asText()), "HASH_INVALID");
        require(archive.path("archiveSha256").equals(fullBatch.path("fullRnaArchiveSha256")), "REFERENCE_INVALID");
        require(archive.path("executionSourceSha256").equals(fullBatch.path("sourceSnapshotSha256")), "SOURCE_INVALID");
        require(archive.path("sourceObservationIdentity").equals(fullBatch.path("fullRnaObservedSourceIdentity")), "OBSERVED_SOURCE_IDENTITY_INVALID");
        var originals = archive.path("originalsByRawSha256"); require(originals.isObject(), "ORIGINAL_INDEX_INVALID");
        var fields = originals.properties().iterator();
        while (fields.hasNext()) {
            var field = fields.next(); var raw = field.getValue(); String utf8 = raw.path("originalUtf8").asText();
            require(field.getKey().matches("[0-9a-f]{64}") && field.getKey().equals(raw.path("rawSha256").asText())
                    && field.getKey().equals(CodingTranscriptMaterialSupply.sha(utf8.getBytes(StandardCharsets.UTF_8))), "ORIGINAL_BYTES_INVALID");
        }
        var materials = archive.path("materialsBySha256"); require(materials.isObject(), "MATERIAL_INDEX_INVALID");
        var materialFields = materials.properties().iterator();
        while (materialFields.hasNext()) {
            var field = materialFields.next(); var material = field.getValue();
            require(field.getKey().equals(hashes.canonicalSha256(material)), "MATERIAL_HASH_INVALID");
            if (material.has("originalReference")) {
                require(originals.has(material.path("originalReference").asText()), "MATERIAL_ORIGINAL_MISSING");
                require(material.path("originalReference").equals(material.path("originalSha256")), "MATERIAL_ORIGINAL_INVALID");
                require(material.at("/preparation/sourceReplay/factsSha256").asText().equals(hashes.canonicalSha256(material.path("consequenceFacts"))), "FACTS_INVALID");
                require(material.at("/preparation/sourceReplay/resultSha256").asText().equals(hashes.canonicalSha256(material.path("rnaEditFacts"))), "EDIT_FACTS_INVALID");
                require(material.at("/preparation/sourceReplay/topologySha256").asText().equals(hashes.canonicalSha256(material.path("topology"))), "TOPOLOGY_INVALID");
            }
        }
        int actual = 0;
        for (String module : List.of("pvs1", "pm4", "bp3")) for (var raw : fullBatch.at("/modules/" + module + "/results"))
            if (raw.path("fullRnaCurrentUse").isObject()) actual++;
        require(archive.path("judgments").isArray() && archive.path("judgments").size() == actual, "JUDGMENT_COUNT_INVALID");
        var seen = new java.util.HashSet<String>();
        for (var judgment : archive.path("judgments")) {
            String pointer = judgment.path("modulePointer").asText();
            require(pointer.matches("/modules/(pvs1|pm4|bp3)/results/(0|[1-9][0-9]{0,5})") && seen.add(pointer), "JUDGMENT_POINTER_INVALID");
            var raw = fullBatch.at(pointer);
            require(judgment.path("moduleSha256").asText().equals(hashes.canonicalSha256(raw))
                    && judgment.path("evaluation").equals(raw.path("evaluation"))
                    && judgment.path("application").equals(raw.path("fullRnaCurrentUse")), "JUDGMENT_BINDING_INVALID");
            FullRnaCurrentUse.publicProof(mapper, raw);
            var proof = raw.path("fullRnaCurrentUse");
            if (proof.has("sourceOriginalSha256")) {
                require(originals.has(proof.path("sourceOriginalSha256").asText()), "JUDGMENT_ORIGINAL_MISSING");
                int bound = 0;
                for (var material : materials) if (material.path("originalReference").equals(proof.path("sourceOriginalSha256"))
                        && material.at("/preparation/selectedProjectionSha256").equals(proof.at("/preparation/selectedProjectionSha256"))
                        && material.at("/preparation/executionSourceSha256").equals(proof.path("executionSourceSha256"))
                        && material.at("/preparation/sourceReplay/factsSha256").equals(proof.path("factsSha256"))
                        && material.at("/preparation/sourceReplay/mechanicalEffectSha256").equals(proof.path("mechanicalEffectSha256"))) bound++;
                require(bound == 1, "JUDGMENT_MATERIAL_AMBIGUOUS_OR_MISSING");
            }
        }
        return object.deepCopy();
    }
    private static void require(boolean pass, String why) { if (!pass) throw new IllegalArgumentException("FULL_RNA_ARCHIVE_" + why); }
}
