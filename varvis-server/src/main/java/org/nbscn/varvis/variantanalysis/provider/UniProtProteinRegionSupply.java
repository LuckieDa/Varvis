package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline, pinned annotation facts. Neither Swiss-Prot review nor a feature-free interval is ACMG approval. */
public final class UniProtProteinRegionSupply {
    public static final String VERSION = "1.0.1";
    static final int MAX_RECORD_BYTES = 2_000_000, MAX_MANIFEST_BYTES = 16_000_000;
    static final int MAX_ENTRIES = 25_000;
    private static final Set<String> REGION_TYPES = Set.of("Repeat", "Domain", "Motif", "Region", "Coiled coil",
            "Compositional bias", "Active site", "Binding site", "Site", "DNA binding", "Zinc finger",
            "Transmembrane", "Intramembrane", "Topological domain", "Signal", "Transit peptide", "Propeptide",
            "Chain", "Peptide", "Disulfide bond", "Cross-link", "Modified residue", "Glycosylation", "Lipidation");
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final Path root;
    private final String manifestSha;
    private final Map<String, List<ObjectNode>> byGene = new LinkedHashMap<>();

    public UniProtProteinRegionSupply(ObjectMapper mapper, Path root, String manifestSha) throws IOException {
        this.mapper = strictMapper(mapper); this.adapter = new RuntimeEvidenceV2Adapter(this.mapper);
        this.root = root.toRealPath(); this.manifestSha = manifestSha;
        var manifest = readPinned("manifest.json", manifestSha, MAX_MANIFEST_BYTES);
        require("uniprot-protein-region-index-v1".equals(manifest.path("schemaVersion").asText())
                // 1.0.0 indexes contain the same pinned native bytes; the corrected reader has its own identity.
                && Set.of("1.0.0", VERSION).contains(manifest.path("methodVersion").asText())
                && "UNREVIEWED_ANNOTATION_FACTS".equals(manifest.path("usageScope").asText())
                && manifest.path("entries").isArray() && manifest.path("entries").size() <= MAX_ENTRIES,
                "PROTEIN_REGION_MANIFEST_INVALID");
        var accessions = new HashSet<String>();
        for (var value : manifest.path("entries")) {
            require(value instanceof ObjectNode, "PROTEIN_REGION_MANIFEST_ENTRY_INVALID");
            var entry = (ObjectNode) value;
            String accession = entry.path("accession").asText();
            require(accession.matches("[A-Z0-9]{6,10}") && accessions.add(accession)
                    && (accession + ".json").equals(entry.path("file").asText())
                    && entry.path("sha256").asText().matches("[0-9a-f]{64}")
                    && positive(entry.path("bytes")) && entry.path("bytes").asInt() <= MAX_RECORD_BYTES
                    && entry.path("release").asText().matches("[0-9]{4}_[0-9]{2}")
                    && entry.path("hgncIds").isArray() && !entry.path("hgncIds").isEmpty()
                    && entry.path("hgncIds").size() <= 20, "PROTEIN_REGION_MANIFEST_ENTRY_INVALID");
            var genes = new HashSet<String>();
            for (var gene : entry.path("hgncIds")) {
                require(gene.isTextual() && gene.asText().matches("HGNC:[1-9][0-9]*") && genes.add(gene.asText()),
                        "PROTEIN_REGION_MANIFEST_GENE_INVALID");
                var candidates = byGene.computeIfAbsent(gene.asText(), ignored -> new ArrayList<>());
                require(candidates.size() < 100, "PROTEIN_REGION_GENE_RECORD_LIMIT");
                candidates.add(entry.deepCopy());
            }
        }
    }

    public ObjectNode identity() {
        return mapper.createObjectNode().put("method", "PINNED_UNIPROT_EXACT_PROTEIN_REGIONS").put("version", VERSION)
                .put("manifestSha256", manifestSha).put("networkAllowed", false)
                .put("scientificEvaluationPerformed", false).put("scientificAcceptance", "UNREVIEWED")
                .put("negativeCoverageEstablished", false);
    }

    /** The allele range comes only from the verified complete equivalent-edit envelope, never HGVS text. */
    public ObjectNode resolve(CodingTranscriptMaterialSupply.Material material) {
        var sequence = material.snapshot();
        var result = identity().put("status", "UNAVAILABLE");
        result.put("sequenceMaterialSha256", adapter.canonicalSha256(sequence));
        if (!material.ready() || !java.util.Set.of("SIMPLE_INFRAME_LENGTH_CHANGE", "VERIFIED_INFRAME_LENGTH_CHANGE")
                .contains(sequence.path("associationKind").asText()))
            return result.put("reasonCode", "PROTEIN_REGION_SEQUENCE_MATERIAL_UNAVAILABLE");
        var facts = sequence.path("alleleAssociation");
        if (!"READY".equals(facts.path("equivalentEditEnvelopeStatus").asText()))
            return result.put("reasonCode", "PROTEIN_REGION_EQUIVALENT_ENVELOPE_UNAVAILABLE")
                    .set("upstreamReason", facts.path("equivalentEditEnvelopeReasonCode").deepCopy());
        var envelope = facts.path("equivalentEditEnvelope");
        if (!"REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE".equals(envelope.path("proteinCoordinateSystem").asText())
                || !positive(envelope.path("proteinStart")) || !positive(envelope.path("proteinEnd")))
            return result.put("reasonCode", "PROTEIN_REGION_RANGE_INVALID");
        var model = java.util.Objects.requireNonNull(material.model());
        var mapped = mapModel(model, envelope.path("proteinStart").asInt(), envelope.path("proteinEnd").asInt());
        mapped.put("sequenceMaterialSha256", adapter.canonicalSha256(sequence));
        mapped.set("contextId", sequence.path("contextId").deepCopy());
        mapped.set("queryAllele", sequence.path("queryAllele").deepCopy());
        mapped.set("equivalentEditEnvelope", envelope.deepCopy());
        return mapped;
    }

    /** Package-private component boundary, also used to validate real reference models without fabricating variants. */
    ObjectNode mapModel(ObjectNode model, int start, int end) {
        var result = identity().put("status", "UNAVAILABLE").put("modelCanonicalSha256", adapter.canonicalSha256(model));
        result.put("transcript", model.path("transcript").asText()).put("protein", model.path("protein").asText());
        result.putObject("queryRange").put("start", start).put("end", end)
                .put("coordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE");
        try {
            require(!Thread.currentThread().isInterrupted(), "PROTEIN_REGION_INTERRUPTED");
            // Recheck the small manifest on every read; no stale cached success after source replacement.
            readPinned("manifest.json", manifestSha, MAX_MANIFEST_BYTES);
            String cds = model.path("cdsSequence").asText();
            require(cds.length() <= 1_000_000 && cds.matches("[ACGT]+") && cds.length() % 3 == 0
                    && cds.startsWith("ATG") && model.path("hgncId").asText().matches("HGNC:[1-9][0-9]*")
                    && model.path("transcript").asText().matches("(?:ENST[0-9]+|NM_[0-9]+)\\.[1-9][0-9]*")
                    && model.path("protein").asText().matches("(?:ENSP[0-9]+|NP_[0-9]+)\\.[1-9][0-9]*"),
                    "PROTEIN_REGION_MODEL_IDENTITY_INVALID");
            String translated = ProteinLengthChangeObservation.translate(cds);
            require(translated.endsWith("*") && translated.indexOf('*') == translated.length() - 1
                    && translated.length() - 1 == model.path("proteinLength").asInt(), "PROTEIN_REGION_MODEL_SEQUENCE_INVALID");
            // UniProt has no terminal stop. Retain both digests instead of confusing the two conventions.
            String protein = translated.substring(0, translated.length() - 1);
            result.put("proteinSequenceSha256WithoutStop", CodingTranscriptMaterialSupply.sha(protein))
                    .put("translatedSequenceSha256WithStop", CodingTranscriptMaterialSupply.sha(translated))
                    .put("proteinLength", protein.length());
            require(start >= 1 && end >= start && end <= protein.length(), "PROTEIN_REGION_RANGE_INVALID");
            var entries = byGene.get(model.path("hgncId").asText());
            require(entries != null, "PROTEIN_REGION_GENE_NOT_IN_LOCAL_SNAPSHOT");
            var attempts = result.putArray("mappingAttempts");
            var matches = new ArrayList<ObjectNode>();
            for (var entry : java.util.Objects.requireNonNull(entries)) {
                require(!Thread.currentThread().isInterrupted(), "PROTEIN_REGION_INTERRUPTED");
                var raw = readPinned(entry.path("file").asText(), entry.path("sha256").asText(), MAX_RECORD_BYTES, entry.path("bytes").asInt());
                validateRecord(raw);
                require(raw.path("primaryAccession").equals(entry.path("accession"))
                        && genes(raw).equals(entry.path("hgncIds")), "PROTEIN_REGION_INDEX_SOURCE_MISMATCH");
                var attempt = attempts.addObject().put("accession", entry.path("accession").asText());
                String issue = mappingIssue(raw, model, protein);
                attempt.put("reasonCode", issue == null ? "EXACT_VERSIONED_PAIR_AND_FULL_SEQUENCE" : issue);
                if (issue == null) {
                    var match = project(raw, entry, start, end);
                    matches.add(match);
                }
            }
            require(!matches.isEmpty(), "PROTEIN_REGION_EXACT_MAPPING_UNAVAILABLE");
            // Multiple entries can carry different annotations even for identical sequences. Do not pick the favorable one.
            require(matches.size() == 1, "PROTEIN_REGION_MULTIPLE_SOURCE_RECORDS");
            result.set("source", matches.get(0).path("source"));
            result.set("features", matches.get(0).path("features"));
            result.set("unmappedFeatures", matches.get(0).path("unmappedFeatures"));
            result.set("featureSummary", matches.get(0).path("featureSummary"));
            result.put("status", "READY").put("reasonCode", "EXACT_PROTEIN_ANNOTATION_FACTS_AVAILABLE")
                    .put("mappingMethod", "EXACT_VERSIONED_PAIR_AND_FULL_SEQUENCE")
                    .put("repeatConclusion", "NOT_EVALUATED").put("functionalConclusion", "NOT_EVALUATED");
        } catch (IllegalArgumentException unavailable) {
            result.put("reasonCode", unavailable.getMessage());
        } catch (IOException unavailable) { result.put("reasonCode", "PROTEIN_REGION_SOURCE_IO_UNAVAILABLE"); }
        return result;
    }

    @org.springframework.lang.Nullable static String mappingIssue(ObjectNode raw, ObjectNode model, String protein) {
        if (!raw.at("/sequence/value").asText().equals(protein)) return "PROTEIN_REGION_FULL_SEQUENCE_MISMATCH";
        String tx = model.path("transcript").asText(), peptide = model.path("protein").asText();
        boolean pair = false;
        for (var xref : raw.path("uniProtKBCrossReferences")) {
            boolean matches = ("Ensembl".equals(xref.path("database").asText()) && tx.equals(xref.path("id").asText())
                    && peptide.equals(property(xref, "ProteinId")))
                    || ("RefSeq".equals(xref.path("database").asText()) && peptide.equals(xref.path("id").asText())
                    && tx.equals(property(xref, "NucleotideSequenceId")));
            if (matches) {
                if (xref.has("isoformId") && !displayedIsoform(raw, xref.path("isoformId").asText()))
                    return "PROTEIN_REGION_NONCANONICAL_ISOFORM_XREF";
                pair = true;
            }
        }
        return pair ? null : "PROTEIN_REGION_VERSIONED_TRANSCRIPT_PROTEIN_PAIR_MISSING";
    }

    private ObjectNode project(ObjectNode raw, ObjectNode entry, int start, int end) throws IOException {
        var result = mapper.createObjectNode();
        result.putObject("source").put("accession", entry.path("accession").asText())
                .put("url", "https://rest.uniprot.org/uniprotkb/" + entry.path("accession").asText() + ".json")
                .put("release", entry.path("release").asText()).put("rawSha256", entry.path("sha256").asText())
                .put("entryVersion", raw.at("/entryAudit/entryVersion").asInt())
                .put("sequenceVersion", raw.at("/entryAudit/sequenceVersion").asInt())
                .put("sourceReview", raw.path("entryType").asText()).set("entryAudit", raw.path("entryAudit").deepCopy());
        var features = result.putArray("features"); var unmapped = result.putArray("unmappedFeatures");
        int index = 0, ignored = 0, repeatHits = 0, otherHits = 0;
        for (var feature : raw.path("features")) {
            int ordinal = index++;
            String type = feature.path("type").asText();
            if (!REGION_TYPES.contains(type)) { ignored++; continue; }
            // Keep invalid/uncertain native coordinates as text, not as numbers in downstream canonical contracts.
            // The complete original bytes remain pinned by source.rawSha256 and the exact JSON pointer.
            String featureJson = mapper.writeValueAsString(feature);
            var output = mapper.createObjectNode().put("sourceLocator", "/features/" + ordinal)
                    .put("featureJsonSha256", CodingTranscriptMaterialSupply.sha(featureJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .put("featureJsonEncoding", "JACKSON_JSON_UTF8").put("rawFeatureJson", featureJson);
            var location = feature.path("location");
            String coordinateIssue = null;
            if (location.has("sequence") && !displayedIsoform(raw, location.path("sequence").asText())
                    && !raw.path("primaryAccession").asText().equals(location.path("sequence").asText()))
                coordinateIssue = "FEATURE_OTHER_ISOFORM";
            else if (!"EXACT".equals(location.at("/start/modifier").asText())
                    || !"EXACT".equals(location.at("/end/modifier").asText())) coordinateIssue = "FEATURE_UNCERTAIN_COORDINATES";
            else if (!positive(location.at("/start/value")) || !positive(location.at("/end/value"))
                    || location.at("/start/value").asInt() > location.at("/end/value").asInt()
                    || location.at("/end/value").asInt() > raw.at("/sequence/length").asInt())
                coordinateIssue = "FEATURE_COORDINATES_OUT_OF_RANGE";
            if (coordinateIssue != null) { unmapped.add(output.put("reasonCode", coordinateIssue)); continue; }
            int lo = location.at("/start/value").asInt(), hi = location.at("/end/value").asInt();
            if (hi < start || lo > end) continue;
            boolean repeat = "Repeat".equals(type);
            if (repeat) repeatHits++; else otherHits++;
            features.add(output.put("start", lo).put("end", hi).put("type", type)
                    .put("category", repeat ? "REPEAT_ANNOTATION" : "OTHER_REGION_ANNOTATION")
                    .put("coversEntireQueryRange", lo <= start && hi >= end));
        }
        result.putObject("featureSummary").put("sourceFeatureCount", raw.path("features").size())
                .put("ignoredFeatureCount", ignored).put("repeatOverlaps", repeatHits).put("otherRegionOverlaps", otherHits)
                .put("unmappedRegionFeatures", unmapped.size())
                .put("regionCoordinateStatus", unmapped.isEmpty() ? "AVAILABLE_REGION_COORDINATES_EXACT" : "UNRESOLVED_REGION_FEATURES_PRESENT")
                .put("annotationCoverage", "NOT_ASSERTED_COMPLETE")
                .put("noHitMeaning", "NO_MATCH_IN_AVAILABLE_ANNOTATIONS_NOT_ABSENCE_OF_REPEAT_OR_FUNCTION");
        return result;
    }

    private static boolean displayedIsoform(ObjectNode raw, String id) {
        if (id.isBlank()) return false;
        for (var comment : raw.path("comments")) if ("ALTERNATIVE PRODUCTS".equals(comment.path("commentType").asText()))
            for (var isoform : comment.path("isoforms")) if ("Displayed".equals(isoform.path("isoformSequenceStatus").asText()))
                for (var value : isoform.path("isoformIds")) if (id.equals(value.asText())) return true;
        return false;
    }

    static void validateRecord(ObjectNode raw) {
        require("UniProtKB reviewed (Swiss-Prot)".equals(raw.path("entryType").asText())
                && positive(raw.at("/organism/taxonId")) && raw.at("/organism/taxonId").asInt() == 9606
                && raw.path("primaryAccession").asText().matches("[A-Z0-9]{6,10}")
                && positive(raw.at("/entryAudit/entryVersion")) && positive(raw.at("/entryAudit/sequenceVersion"))
                && raw.at("/sequence/value").asText().matches("[ACDEFGHIKLMNPQRSTVWY]+")
                && positive(raw.at("/sequence/length")) && raw.at("/sequence/length").asInt() <= 100_000
                && raw.at("/sequence/length").asInt() == raw.at("/sequence/value").asText().length()
                && raw.path("uniProtKBCrossReferences").isArray() && raw.path("uniProtKBCrossReferences").size() <= 10_000,
                "PROTEIN_REGION_RECORD_IDENTITY_INVALID");
        require(raw.path("features").isArray() && raw.path("features").size() <= 10_000,
                "PROTEIN_REGION_FEATURES_FIELD_MISSING_OR_INVALID");
        for (var feature : raw.path("features")) require(feature.isObject() && feature.path("type").isTextual(),
                "PROTEIN_REGION_FEATURE_INVALID");
        require(!genes(raw).isEmpty(), "PROTEIN_REGION_HGNC_ID_MISSING");
    }

    static com.fasterxml.jackson.databind.node.ArrayNode genes(ObjectNode raw) {
        var ids = new java.util.TreeSet<String>();
        for (var xref : raw.path("uniProtKBCrossReferences")) if ("HGNC".equals(xref.path("database").asText())) {
            require(xref.path("id").asText().matches("HGNC:[1-9][0-9]*"), "PROTEIN_REGION_HGNC_ID_INVALID");
            ids.add(xref.path("id").asText());
        }
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        ids.forEach(result::add); return result;
    }

    private static String property(JsonNode node, String key) {
        String result = ""; boolean found = false;
        for (var property : node.path("properties")) if (key.equals(property.path("key").asText())) {
            if (found) return ""; // Duplicate identity properties are not a unique binding, even if the first is empty.
            found = true;
            result = property.path("value").asText();
        }
        return result;
    }

    private ObjectNode readPinned(String file, String pin, int limit) throws IOException {
        return readPinned(file, pin, limit, -1);
    }
    private ObjectNode readPinned(String file, String pin, int limit, int expectedBytes) throws IOException {
        Path path = root.resolve(file).toRealPath();
        require(path.getParent().equals(root), "PROTEIN_REGION_PATH_OUTSIDE_ROOT");
        byte[] bytes = readBytes(path, limit);
        require(expectedBytes < 0 || bytes.length == expectedBytes, "PROTEIN_REGION_SOURCE_SIZE_MISMATCH");
        require(CodingTranscriptMaterialSupply.sha(bytes).equals(pin), "PROTEIN_REGION_SOURCE_CHECKSUM_MISMATCH");
        var node = mapper.readTree(bytes);
        require(node instanceof ObjectNode, "PROTEIN_REGION_JSON_OBJECT_REQUIRED");
        return (ObjectNode) node;
    }

    static ObjectMapper strictMapper(ObjectMapper mapper) {
        return mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    static byte[] readBytes(Path path, int limit) throws IOException {
        require(Files.isRegularFile(path) && Files.size(path) <= limit, "PROTEIN_REGION_FILE_SIZE_LIMIT");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            require(bytes.length <= limit, "PROTEIN_REGION_FILE_SIZE_LIMIT"); return bytes;
        }
    }
    static boolean positive(JsonNode n) { return n.isIntegralNumber() && n.canConvertToInt() && n.asInt() > 0; }
    static void require(boolean ok, String code) { if (!ok) throw new IllegalArgumentException(code); }
}
