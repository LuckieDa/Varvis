package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.springframework.lang.Nullable;

/** Exact transcript pairs from the pinned official MANE GRCh38 summary. */
public final class Ba1ManeTranscriptPairs {
    private static final String ARCHIVE = "MANE.GRCh38.v1.5.summary.txt.gz";
    private static final int ARCHIVE_BYTES = 1_115_288;
    private static final String SHA256 = "d10ace2720681a3b2e0eefd9da4f551274a6b4141ac9bfd6a2565dfb6e9ad55c";
    private static final String SOURCE = "https://ftp.ncbi.nlm.nih.gov/refseq/MANE/MANE_human/release_1.5/" + ARCHIVE;
    private static final String HEADER = "#NCBI_GeneID\tEnsembl_Gene\tHGNC_ID\tsymbol\tname\tRefSeq_nuc\tRefSeq_prot"
            + "\tEnsembl_nuc\tEnsembl_prot\tMANE_status\tGRCh38_chr\tchr_start\tchr_end\tchr_strand";
    private static final Pattern PRIMARY_CHROMOSOME = Pattern.compile("NC_0000(0[1-9]|1[0-9]|2[0-4])\\.[1-9][0-9]*");

    private final Map<String, Pair> pairs;
    private final String archiveSha256;

    private Ba1ManeTranscriptPairs(Map<String, Pair> pairs, String archiveSha256) {
        this.pairs = Map.copyOf(pairs);
        this.archiveSha256 = archiveSha256;
    }

    public static Ba1ManeTranscriptPairs load(Path bindingDirectory) throws IOException {
        try (var input = Files.newInputStream(bindingDirectory.resolve(ARCHIVE))) {
            return read(input.readNBytes(ARCHIVE_BYTES + 1), SHA256);
        }
    }

    static Ba1ManeTranscriptPairs read(byte[] bytes, String expectedSha256) throws IOException {
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(expectedSha256)) throw new IOException("MANE_ARCHIVE_DIGEST_MISMATCH");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        Map<String, Pair> pairs = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(bytes)), StandardCharsets.UTF_8))) {
            if (!HEADER.equals(reader.readLine())) throw new IOException("MANE_HEADER_INVALID");
            int row = 1;
            for (String line; (line = reader.readLine()) != null;) {
                row++;
                String[] fields = line.split("\t", -1);
                if (fields.length != 14) throw new IOException("MANE_ROW_INVALID");
                String gene = fields[2], refseq = fields[5], ensembl = fields[7], status = fields[9], reference = fields[10];
                var primary = PRIMARY_CHROMOSOME.matcher(reference);
                if (!gene.matches("HGNC:[1-9][0-9]*") || !primary.matches()) continue;
                if (!ensembl.matches("ENST[0-9]+\\.[1-9][0-9]*") || !refseq.matches("N[MR]_[0-9]+\\.[1-9][0-9]*")
                        || !("MANE Select".equals(status) || "MANE Plus Clinical".equals(status))) {
                    throw new IOException("MANE_TRANSCRIPT_PAIR_INVALID");
                }
                int chromosome = Integer.parseInt(primary.group(1));
                String contig = switch (chromosome) {
                    case 23 -> "X";
                    case 24 -> "Y";
                    default -> Integer.toString(chromosome);
                };
                if (pairs.putIfAbsent(ensembl, new Pair(gene, refseq, status, contig, reference, row)) != null)
                    throw new IOException("MANE_TRANSCRIPT_PAIR_AMBIGUOUS");
            }
        }
        if (pairs.isEmpty()) throw new IOException("MANE_TRANSCRIPT_PAIRS_MISSING");
        return new Ba1ManeTranscriptPairs(pairs, expectedSha256);
    }

    ObjectNode identity(ObjectMapper mapper) {
        return mapper.createObjectNode().put("method", "MANE_GRCH38_EXACT_PAIR_V1").put("version", "1.5")
                .put("assembly", "GRCh38").put("url", SOURCE).put("sha256", archiveSha256);
    }

    @Nullable Pair find(String hgncId, String ensembl, String contig) {
        var pair = pairs.get(ensembl);
        return pair != null && pair.hgncId().equals(hgncId) && pair.contig().equals(contig) ? pair : null;
    }

    record Pair(String hgncId, String refseqTranscript, String status, String contig, String referenceAccession, int row) { }
}
