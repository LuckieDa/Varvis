package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;

/** Fabricated provenance for engineering controls only; no actual cache, RNA or clinical review. */
final class OfflineCacheModelTestSupport {
    private OfflineCacheModelTestSupport() { }

    static ObjectNode add(ObjectNode model, ObjectNode policy) {
        boolean ensembl = model.path("transcript").asText().startsWith("ENST");
        String enst = ensembl ? model.path("transcript").asText() : "ENST000999.1";
        String ensp = ensembl ? model.path("protein").asText() : "ENSP000999.1";
        String nm = ensembl ? "NM_000999.1" : model.path("transcript").asText();
        String np = ensembl ? "NP_000999.1" : model.path("protein").asText();
        model.putObject("transcriptBridge").put("ensemblTranscript", enst).put("ensemblProtein", ensp)
                .put("refseqTranscript", nm).put("refseqProtein", np).put("status", "FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE");
        int bases = 0;
        for (JsonNode exon : model.path("exons")) bases += exon.path("genomicEnd").asInt() - exon.path("genomicStart").asInt() + 1;
        model.putObject("sequenceVerification").put("fastaIndexSha256", "a".repeat(64))
                .put("fullSplicedTranscriptSha256", "b".repeat(64)).put("queriedBases", bases)
                .put("cdsSha256", AcmgBusinessStore.sha(model.path("cdsSequence").asText()));
        ObjectNode rna = policy.withArray("sources").addObject().put("sourceId", "RefSeq:" + nm)
                .put("version", "SYNTHETIC_ENGINEERING_ONLY").put("sourceKind", "NCBI_VERSIONED_REFSEQ_GENBANK")
                .put("url", "https://example.invalid/synthetic-rna").put("sha256", "c".repeat(64));
        model.withArray("sourceRecords").add(rna.deepCopy());
        ObjectNode cache = model.withArray("sourceRecords").addObject()
                .put("sourceKind", "VEP116_OFFLINE_CACHE_TRANSCRIPT_OBJECT_NOT_REST")
                .put("sourceId", "VEP116_CACHE:" + enst).put("version", "116.GRCh38:exporter-1.0.1");
        for (String field : new String[] {"sha256", "objectSha256", "requestSha256", "exportSha256",
                "exportManifestSha256", "exporterSha256", "cacheInfoSha256"}) cache.put(field, "d".repeat(64));
        ObjectNode api = cache.putObject("apiSourceSha256");
        for (String field : new String[] {"ApiVersion", "Biotype", "CoordSystem", "Exon", "Feature", "Slice", "Transcript", "Translation"})
            api.put("Bio/EnsEMBL/" + field + ".pm", "e".repeat(64));
        api.put("Bio/EnsEMBL/Variation/Utils/BaseVepPlugin.pm", "e".repeat(64));
        return cache;
    }
}
