package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** Shared native coding-model checks and transcript-oriented coordinate mechanics.
 * Call modelIssue and independently bind the model/allele identities before mapping.
 * No LoF mechanism, NMD threshold, clinical relevance, or evidence strength is inferred.
 */
final class CodingTranscriptMapping {
    private static final String CACHE_SOURCE = "VEP116_OFFLINE_CACHE_TRANSCRIPT_OBJECT_NOT_REST";
    private static final Set<String> CACHE_FIELDS = Set.of("sourceId", "version", "sourceKind", "sha256",
            "objectSha256", "requestSha256", "exportSha256", "exportManifestSha256", "exporterSha256",
            "cacheInfoSha256", "apiSourceSha256");
    private static final Set<String> CACHE_MODULES = Set.of("Bio/EnsEMBL/ApiVersion.pm", "Bio/EnsEMBL/Biotype.pm",
            "Bio/EnsEMBL/CoordSystem.pm", "Bio/EnsEMBL/Exon.pm", "Bio/EnsEMBL/Feature.pm", "Bio/EnsEMBL/Slice.pm",
            "Bio/EnsEMBL/Transcript.pm", "Bio/EnsEMBL/Translation.pm", "Bio/EnsEMBL/Variation/Utils/BaseVepPlugin.pm");
    private CodingTranscriptMapping() { }

    static String modelIssue(JsonNode model, JsonNode context) {
        if (model == null || !model.isObject() || context == null || !context.isObject())
            return "CODING_TRANSCRIPT_MODEL_IDENTITY_INVALID";
        if (!Set.of("pvs1-transcript-model-v1", "native-coding-transcript-model-v1").contains(model.path("schemaVersion").asText())
                || !text(model.path("modelId")) || !text(model.path("modelVersion")))
            return "CODING_TRANSCRIPT_MODEL_IDENTITY_INVALID";
        String transcript = context.at("/transcript/accession").asText() + "."
                + context.at("/transcript/version").asInt();
        if (!transcript.equals(model.path("transcript").asText())
                || !context.at("/gene/hgncId").equals(model.path("hgncId"))
                || !context.at("/gene/symbol").equals(model.path("geneSymbol"))
                || !context.at("/variant/evidenceQueryAllele/assembly").equals(model.path("assembly"))
                || !context.at("/variant/evidenceQueryAllele/contig").equals(model.path("contig"))
                || !model.path("protein").asText().matches("(?:ENSP[0-9]+|NP_[0-9]+)\\.[1-9][0-9]*"))
            return "CODING_TRANSCRIPT_MODEL_CONTEXT_MISMATCH";
        int strand = model.path("strand").asInt();
        String cds = model.path("cdsSequence").asText();
        if (!model.path("strand").isIntegralNumber() || !model.path("strand").canConvertToInt() || (strand != 1 && strand != -1)
                || !cds.matches("[ACGT]+") || cds.length() < 6 || cds.length() % 3 != 0
                || !cds.startsWith("ATG") || !model.path("cdsLength").isIntegralNumber() || !model.path("cdsLength").canConvertToInt()
                || !model.path("proteinLength").isIntegralNumber() || !model.path("proteinLength").canConvertToInt()
                || model.path("cdsLength").asInt() != cds.length()
                || model.path("proteinLength").asInt() != cds.length() / 3 - 1)
            return "CODING_TRANSCRIPT_MODEL_SEQUENCE_INVALID";
        for (int i = 0; i < cds.length(); i += 3) {
            boolean stop = TruncatingSequence.translateCodon(cds.substring(i, i + 3)) == '*';
            if (stop != (i == cds.length() - 3)) return "CODING_TRANSCRIPT_MODEL_SEQUENCE_INVALID";
        }
        JsonNode exons = model.path("exons");
        if (!exons.isArray() || exons.isEmpty() || exons.size() > 1000)
            return "CODING_TRANSCRIPT_MODEL_EXON_INVALID";
        long expectedCdsStart = 0;
        int previousGenomicStart = 0;
        int previousGenomicEnd = 0;
        for (int i = 0; i < exons.size(); i++) {
            JsonNode exon = exons.path(i);
            for (String field : Set.of("ordinal", "genomicStart", "genomicEnd", "cdsStart", "cdsEnd"))
                if (!exon.path(field).isIntegralNumber() || !exon.path(field).canConvertToInt())
                    return "CODING_TRANSCRIPT_MODEL_EXON_INVALID";
            int start = exon.path("genomicStart").asInt();
            int end = exon.path("genomicEnd").asInt();
            int lo = exon.path("cdsStart").asInt();
            int hi = exon.path("cdsEnd").asInt();
            if (exon.path("ordinal").asInt() != i + 1 || start < 1 || end < start || hi < lo
                    || (long) end - start != (long) hi - lo
                    // A transcript may start with one or more wholly untranslated exons.
                    // These are continuous CDS-relative offsets, not HGVS c. coordinates;
                    // zero is the base immediately before CDS1. Keep their real ordinals.
                    || (i == 0 ? lo > 1 : lo != expectedCdsStart)
                    || (i > 0 && (strand == 1 ? start <= previousGenomicEnd : end >= previousGenomicStart)))
                return "CODING_TRANSCRIPT_MODEL_EXON_INVALID";
            expectedCdsStart = (long) hi + 1;
            previousGenomicStart = start;
            previousGenomicEnd = end;
        }
        if (expectedCdsStart <= cds.length()) return "CODING_TRANSCRIPT_MODEL_EXON_INVALID";
        if (!modelSourcesValid(model)) return "CODING_TRANSCRIPT_MODEL_SOURCE_INVALID";
        return null;
    }


    /** Returns the unique exon containing the complete inclusive genomic interval, or null.
     * Invalid/reversed positions and ambiguous overlapping exon models throw. Contig/assembly
     * identity must already have been checked; a coordinate alone cannot establish identity.
     */
    static JsonNode exonForGenomicInterval(JsonNode model, int start, int end) {
        if (start < 1 || end < start || !model.path("exons").isArray())
            throw new IllegalArgumentException("Invalid genomic interval/model");
        JsonNode found = null;
        for (JsonNode exon : model.path("exons")) {
            if (start >= exon.path("genomicStart").asInt() && end <= exon.path("genomicEnd").asInt()) {
                if (found != null) throw new IllegalArgumentException("Ambiguous coding exon interval");
                found = exon;
            }
        }
        return found;
    }

    /** One-based genomic base to one-based CDS base; 0 means outside the model's CDS.
     * Exon intervals are inclusive genomic coordinates ordered by transcript orientation.
     */
    static int cdsPosition(JsonNode model, int genomicPosition) {
        JsonNode exon = exonForGenomicInterval(model, genomicPosition, genomicPosition);
        int strand = strand(model);
        if (exon == null) return 0;
        long position = (long) exon.path("cdsStart").asInt() + (strand == 1
                ? (long) genomicPosition - exon.path("genomicStart").asInt()
                : (long) exon.path("genomicEnd").asInt() - genomicPosition);
        return position < 1 || position > model.path("cdsSequence").asText().length() ? 0 : (int) position;
    }

    /** Transforms an A/C/G/T forward-reference allele to transcript orientation. */
    static String orientAllele(JsonNode model, String genomicBases) {
        if (genomicBases == null || !genomicBases.matches("[ACGT]+"))
            throw new IllegalArgumentException("An A/C/G/T genomic allele is required");
        return strand(model) == 1 ? genomicBases : TruncatingSequence.reverseComplement(genomicBases);
    }

    private static int strand(JsonNode model) {
        if (!model.path("strand").isIntegralNumber() || !model.path("strand").canConvertToInt()
                || (model.path("strand").asInt() != 1 && model.path("strand").asInt() != -1))
            throw new IllegalArgumentException("A validated transcript strand is required");
        return model.path("strand").asInt();
    }

    static boolean sourcesValid(JsonNode sources) {
        if (!sources.isArray() || sources.isEmpty()) return false;
        Set<String> ids = new HashSet<>();
        for (JsonNode source : sources) {
            if (!text(source.path("sourceId")) || !ids.add(source.path("sourceId").asText())
                    || !text(source.path("version")) || !source.path("url").asText().startsWith("https://")
                    || !source.path("sha256").asText().matches("[0-9a-f]{64}")) return false;
        }
        return true;
    }

    /** The caller first binds the complete model hash to its reviewed policy.
     * Independent model sources remain exact policy sources. A fully verified local cache
     * record is technical provenance inside that pinned model, never policy authority.
     */
    static boolean modelSourcesBound(JsonNode model, JsonNode policySources) {
        if (model == null || !model.isObject() || !modelSourcesValid(model) || !sourcesValid(policySources)) return false;
        for (JsonNode source : policySources) if (hasCacheLabel(source)) return false;
        for (JsonNode source : model.path("sourceRecords")) {
            if (hasCacheIdentity(source)) continue;
            boolean bound = false;
            for (JsonNode policySource : policySources) if (source.equals(policySource)) bound = true;
            if (!bound) return false;
        }
        return true;
    }

    /** Model provenance only. Scientific authority/calibration sources still require the old HTTPS contract.
     * A pinned cache export is a derived local artifact, not an Ensembl REST response or an independent RNA.
     * The caller must still bind the complete reviewed model hash before using any coordinates or sequence.
     */
    private static boolean modelSourcesValid(JsonNode model) {
        JsonNode sources = model.path("sourceRecords");
        if (!sources.isArray() || sources.isEmpty()) return false;
        Set<String> ids = new HashSet<>();
        boolean cachePresent = false, independentRnaPresent = false;
        JsonNode bridge = model.path("transcriptBridge");
        for (JsonNode source : sources) {
            if (!text(source.path("sourceId")) || !ids.add(source.path("sourceId").asText())
                    || !text(source.path("version")) || !hash(source.path("sha256"))) return false;
            if (!hasCacheIdentity(source)) {
                if (!source.path("url").asText().startsWith("https://")) return false;
                if ("NCBI_VERSIONED_REFSEQ_GENBANK".equals(source.path("sourceKind").asText())
                        && source.path("sourceId").asText().equals("RefSeq:" + bridge.path("refseqTranscript").asText()))
                    independentRnaPresent = true;
                continue;
            }
            if (cachePresent || !CACHE_SOURCE.equals(source.path("sourceKind").asText())
                    || !exactFields(source, CACHE_FIELDS)
                    || !"116.GRCh38:exporter-1.0.1".equals(source.path("version").asText())
                    || !source.path("sourceId").asText().equals("VEP116_CACHE:" + bridge.path("ensemblTranscript").asText())
                    || !exactFields(source.path("apiSourceSha256"), CACHE_MODULES)) return false;
            for (String field : Set.of("objectSha256", "requestSha256", "exportSha256", "exportManifestSha256",
                    "exporterSha256", "cacheInfoSha256")) if (!hash(source.path(field))) return false;
            for (JsonNode value : source.path("apiSourceSha256")) if (!hash(value)) return false;
            cachePresent = true;
        }
        if (!cachePresent) return true;
        if (!independentRnaPresent || !"GRCh38".equals(model.path("assembly").asText())
                || !"FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE".equals(bridge.path("status").asText())
                || !bridge.path("ensemblTranscript").asText().matches("ENST[0-9]+\\.[1-9][0-9]*")
                || !bridge.path("ensemblProtein").asText().matches("ENSP[0-9]+\\.[1-9][0-9]*")
                || !bridge.path("refseqTranscript").asText().matches("NM_[0-9]+\\.[1-9][0-9]*")
                || !bridge.path("refseqProtein").asText().matches("NP_[0-9]+\\.[1-9][0-9]*")) return false;
        boolean ensembl = model.path("transcript").equals(bridge.path("ensemblTranscript"))
                && model.path("protein").equals(bridge.path("ensemblProtein"));
        boolean refseq = model.path("transcript").equals(bridge.path("refseqTranscript"))
                && model.path("protein").equals(bridge.path("refseqProtein"));
        JsonNode verification = model.path("sequenceVerification");
        long fullTranscriptBases = 0;
        for (JsonNode exon : model.path("exons"))
            fullTranscriptBases += (long) exon.path("genomicEnd").asInt() - exon.path("genomicStart").asInt() + 1;
        return (ensembl || refseq) && hash(verification.path("fastaIndexSha256"))
                && hash(verification.path("fullSplicedTranscriptSha256"))
                && sequenceSha(model.path("cdsSequence").asText()).equals(verification.path("cdsSha256").asText())
                && verification.path("queriedBases").isIntegralNumber() && verification.path("queriedBases").canConvertToInt()
                && verification.path("queriedBases").asInt() == fullTranscriptBases
                && verification.path("queriedBases").asInt() <= 1_000_000;
    }

    private static boolean hasCacheIdentity(JsonNode source) {
        if (hasCacheLabel(source)) return true;
        for (String field : CACHE_FIELDS)
            if (!Set.of("sourceId", "version", "sourceKind", "sha256").contains(field) && source.has(field)) return true;
        return false;
    }

    private static boolean hasCacheLabel(JsonNode source) {
        return CACHE_SOURCE.equals(source.path("sourceKind").asText())
                || source.path("sourceId").asText().startsWith("VEP116_CACHE:")
                || source.path("version").asText().startsWith("116.GRCh38:exporter-");
    }

    private static boolean exactFields(JsonNode node, Set<String> expected) {
        if (!node.isObject() || node.size() != expected.size()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!expected.contains(names.next())) return false;
        return true;
    }

    private static boolean hash(JsonNode value) { return value.isTextual() && value.asText().matches("[0-9a-f]{64}"); }

    private static String sequenceSha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static boolean text(JsonNode node) { return node.isTextual() && !node.asText().isBlank(); }
}
