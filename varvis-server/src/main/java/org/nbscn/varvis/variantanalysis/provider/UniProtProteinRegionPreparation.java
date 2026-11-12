package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.regex.Pattern;
import static org.nbscn.varvis.variantanalysis.provider.UniProtProteinRegionSupply.*;

/** Offline index preparation. Input: native JSON files and their bounded HTTP acquisition receipts; no network or approval. */
public final class UniProtProteinRegionPreparation {
    private UniProtProteinRegionPreparation() { }

    public static ObjectNode prepare(ObjectMapper inputMapper, Path directory) throws IOException {
        var mapper = strictMapper(inputMapper); Path root = directory.toRealPath();
        Path receipt = root.resolve("acquisition.json").toRealPath();
        require(root.equals(receipt.getParent()), "PROTEIN_REGION_PATH_OUTSIDE_ROOT");
        byte[] receipts = readBytes(receipt, MAX_MANIFEST_BYTES);
        var acquired = mapper.readTree(receipts);
        require(acquired.isArray() && acquired.size() <= MAX_ENTRIES, "PROTEIN_REGION_ACQUISITION_INVALID");
        var manifest = mapper.createObjectNode().put("schemaVersion", "uniprot-protein-region-index-v1")
                .put("methodVersion", VERSION).put("usageScope", "UNREVIEWED_ANNOTATION_FACTS")
                .put("acquisitionSha256", CodingTranscriptMaterialSupply.sha(receipts));
        var entries = manifest.putArray("entries"); var ids = new HashSet<String>();
        String release = null; int failed = 0;
        for (var item : acquired) {
            require(item.path("success").isBoolean(), "PROTEIN_REGION_ACQUISITION_INVALID");
            if (!item.path("success").asBoolean()) { failed++; continue; }
            String id = item.path("accession").asText();
            require(id.matches("[A-Z0-9]{6,10}") && ids.add(id)
                    && ("https://rest.uniprot.org/uniprotkb/" + id + ".json").equals(item.path("url").asText())
                    && positive(item.path("status")) && item.path("status").asInt() == 200,
                    "PROTEIN_REGION_ACQUISITION_IDENTITY_INVALID");
            Instant started = Instant.parse(item.path("startedAt").asText()), finished = Instant.parse(item.path("finishedAt").asText());
            require(!finished.isBefore(started), "PROTEIN_REGION_ACQUISITION_TIME_INVALID");
            var header = Pattern.compile("(?im)^X-UniProt-Release: ([0-9]{4}_[0-9]{2})\\s*$").matcher(item.path("headers").asText());
            require(header.find(), "PROTEIN_REGION_RELEASE_HEADER_MISSING"); String currentRelease = header.group(1);
            require(!header.find() && (release == null || release.equals(currentRelease)), "PROTEIN_REGION_MIXED_RELEASES");
            release = currentRelease;
            Path file = root.resolve(id + ".json").toRealPath();
            require(root.equals(file.getParent()), "PROTEIN_REGION_PATH_OUTSIDE_ROOT");
            byte[] bytes = readBytes(file, MAX_RECORD_BYTES);
            require(positive(item.path("bytes")) && bytes.length == item.path("bytes").asInt()
                    && CodingTranscriptMaterialSupply.sha(bytes).equals(item.path("sha256").asText()), "PROTEIN_REGION_ACQUISITION_BYTES_MISMATCH");
            var node = mapper.readTree(bytes);
            require(node instanceof ObjectNode, "PROTEIN_REGION_JSON_OBJECT_REQUIRED"); var raw = (ObjectNode) node;
            validateRecord(raw);
            require(id.equals(raw.path("primaryAccession").asText()), "PROTEIN_REGION_ACCESSION_MISMATCH");
            entries.addObject().put("accession", id).put("file", id + ".json").put("bytes", bytes.length)
                    .put("sha256", CodingTranscriptMaterialSupply.sha(bytes)).put("release", currentRelease)
                    .put("acquiredAt", finished.toString()).set("hgncIds", genes(raw));
        }
        require(!entries.isEmpty(), "PROTEIN_REGION_NO_USABLE_RECORDS");
        return manifest.put("successfulRecords", entries.size()).put("failedAcquisitions", failed);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: UniProtProteinRegionPreparation <local-snapshot-directory>");
        Path root = Path.of(args[0]); var mapper = new ObjectMapper();
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(prepare(mapper, root));
        // Existing snapshots are immutable: prepare a new directory for an update.
        Files.write(root.resolve("manifest.json"), bytes, StandardOpenOption.CREATE_NEW);
        System.out.println("manifestSha256=" + CodingTranscriptMaterialSupply.sha(bytes));
    }
}
