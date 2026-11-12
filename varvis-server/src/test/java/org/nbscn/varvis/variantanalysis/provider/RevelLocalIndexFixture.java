package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.Feature;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.tribble.index.tabix.TabixIndexCreator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Portable toy index. Integrity flags in the template are synthetic test inputs, never a real review. */
final class RevelLocalIndexFixture {
    static Path create(Path root) throws Exception {
        var mapper = new ObjectMapper();
        Path tool = root.resolve("synthetic-tool-never-executed.txt"); Files.writeString(tool, "SYNTHETIC_ONLY");
        Path data = root.resolve(RevelPublisherIndexManifest.DATA);
        var creator = new TabixIndexCreator(new TabixFormat(0, 1, 2, 2, '#', 0));
        try (var out = new BlockCompressedOutputStream(data.toFile())) {
            int ordinal = 0;
            for (String raw : List.of("1,90,100,A,G,K,R,0.7,ENST000001", "1,90,100,A,G,K,R,0.7,ENST000001",
                    "1,90,100,A,T,K,N,0.1,ENST000001", "1,90,100,A,G,K,R,0.99,ENST999999")) {
                creator.addFeature(new Feature() {
                    @Override public String getContig() { return "1"; }
                    @Override public int getStart() { return 100; }
                    @Override public int getEnd() { return 100; }
                }, out.getFilePointer());
                out.write(("1\t100\t" + ++ordinal + "\t" + raw + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            out.flush(); creator.finalizeIndex(out.getFilePointer()).writeBasedOnFeatureFile(data.toFile());
        }
        try (var out = new BlockCompressedOutputStream(root.resolve(RevelPublisherIndexManifest.EXCLUDED).toFile())) {
            out.write("#SYNTHETIC_ONLY\n".getBytes(StandardCharsets.US_ASCII));
        }
        ObjectNode manifest;
        try (var in = RevelLocalIndexFixture.class.getResourceAsStream("/acmg/synthetic-revel-index-manifest.json")) {
            manifest = (ObjectNode) mapper.readTree(in);
        }
        ((ObjectNode) manifest.at("/tools/tabix")).put("sha256", sha(tool));
        for (String name : List.of(RevelPublisherIndexManifest.DATA, RevelPublisherIndexManifest.INDEX, RevelPublisherIndexManifest.EXCLUDED))
            manifest.withArray("/artifacts").addObject().put("file", name).put("bytes", Files.size(root.resolve(name))).put("sha256", sha(root.resolve(name)));
        Files.write(root.resolve("manifest.json"), mapper.writeValueAsBytes(manifest));
        var review = mapper.createObjectNode().put("schemaVersion", "reviewed-revel-publisher-index-v1")
                .put("adapterId", RevelPublisherIndexObservationProvider.ID).put("adapterVersion", RevelPublisherIndexObservationProvider.VERSION)
                .put("resourceId", "REVEL_SYNTHETIC_PUBLISHER").put("resourceVersion", "1.3-synthetic").put("clinicalAcceptance", false).put("defaultEnabled", false);
        review.putObject("review").put("status", "REVIEWED_INDEX_INTEGRITY").put("reviewId", "SYNTHETIC_ONLY_NOT_A_REAL_APPROVAL");
        review.putObject("producer").put("version", "1.0.1").put("sha256", manifest.path("producerSha256").asText());
        for (var artifact : manifest.path("artifacts")) {
            ObjectNode stamp = artifact.deepCopy(); stamp.put("mtimeNs", modified(root.resolve(artifact.path("file").asText())));
            review.withArray("/artifacts").add(stamp);
        }
        review.putObject("manifest").put("file", "manifest.json").put("bytes", Files.size(root.resolve("manifest.json")))
                .put("sha256", sha(root.resolve("manifest.json"))).put("mtimeNs", modified(root.resolve("manifest.json")));
        review.putObject("tool").put("bytes", Files.size(tool)).put("mtimeNs", modified(tool)).put("sha256", sha(tool)).put("version", "SYNTHETIC");
        Files.write(root.resolve("review.json"), mapper.writeValueAsBytes(review));
        return tool;
    }
    static String sha(Path path) throws Exception { return RevelPublisherIndexManifest.digest(Files.readAllBytes(path)); }
    static long modified(Path path) throws Exception { return Files.getLastModifiedTime(path).to(TimeUnit.NANOSECONDS); }
}
