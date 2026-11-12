package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Project use of unchanged source-qualified conditions, not a new expert review. */
final class Pvs1SourceQualification {
    static final String SCHEMA = "pvs1-source-qualified-knowledge-v1";
    static final String VERSION = "1.0.0";
    private Pvs1SourceQualification() { }

    static final String FULL_RNA_ORIGINAL_SCHEMA = "pvs1-full-rna-effect-review-original-v1";
    static final String FULL_RNA_STAGE = "PINNED_STRUCTURED_RESEARCH_ORIGINAL_REPLAYED_NOT_CRITERION_OR_PHASE_ADOPTION";
    private static final int FULL_RNA_ORIGINAL_MAX_BYTES = 4_000_000;

    /** Opaque, neutral replay of a bounded research original. No criterion, clinical,
     * functional or phase adoption is issued by this token. The original's assay
     * limitations remain visible for the eventual independent criterion consumer. */
    static final class SourceBoundFullRna {
        private final CodingTranscriptEdits.RnaResult result;
        private final CodingConsequenceFacts.RnaFacts facts;
        private final Pvs1SpliceTopology.FullRnaProjection topology;
        private final ObjectNode original, provenance;
        private final String originalSha256;
        private final byte[] originalBytes;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private SourceBoundFullRna(CodingTranscriptEdits.RnaResult result, CodingConsequenceFacts.RnaFacts facts,
                Pvs1SpliceTopology.FullRnaProjection topology, ObjectNode original, ObjectNode provenance, String originalSha256, byte[] originalBytes) {
            this.result = result;this.facts = facts;this.topology = topology;
            this.original = original.deepCopy();this.provenance = provenance.deepCopy();this.originalSha256 = originalSha256;
            this.originalBytes = originalBytes.clone();
        }
        CodingTranscriptEdits.RnaResult result() { return result; }
        CodingConsequenceFacts.RnaFacts facts() { return facts; }
        Pvs1SpliceTopology.FullRnaProjection topology() { return topology; }
        ObjectNode original() { return original.deepCopy(); }
        ObjectNode provenance() { return provenance.deepCopy(); }
        String originalSha256() { return originalSha256; }
        byte[] originalBytes() { return originalBytes.clone(); }
        String originalUtf8() { return new String(originalBytes, StandardCharsets.UTF_8); }
        String eligibilityStage() { return FULL_RNA_STAGE; }
    }

    static final class FullRnaResolution {
        private final SourceBoundFullRna qualified;
        private final ObjectNode snapshot;
        private FullRnaResolution(SourceBoundFullRna qualified, ObjectNode snapshot) {
            this.qualified = qualified;this.snapshot = snapshot.deepCopy();
        }
        boolean ready() { return qualified != null; }
        @org.springframework.lang.Nullable SourceBoundFullRna qualified() { return qualified; }
        ObjectNode snapshot() { return snapshot.deepCopy(); }
    }

    private static final class FullRnaInvalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private FullRnaInvalid(String reason) { super("FULL_RNA_SOURCE_" + reason); }
    }
    private static void rnaRequire(boolean condition, String reason) { if (!condition) throw new FullRnaInvalid(reason); }

    /** Only a controlled local configuration may call this package-private producer.
     * A caller JSON status/hash is never a substitute for reading the pinned original.
     * LoF mechanism and disease-specific policy are intentionally not prerequisites. */
    static FullRnaResolution resolveFullRna(ObjectMapper mapper, Path configuredRoot, JsonNode configuredBinding,
            JsonNode context, CodingTranscriptMaterialSupply.VerifiedRna material) {
        var audit = (mapper == null ? new ObjectMapper() : mapper).createObjectNode().put("schemaVersion", "source-bound-full-rna-replay-v1")
                .put("status", "UNAVAILABLE").put("eligibilityStage", "NOT_READY")
                .put("clinicalAcceptance", false).put("createsExpertReview", false)
                .put("scientificAdoptionGranted", false).put("phaseQualificationGranted", false)
                .put("functionalQualificationGranted", false).put("criterionQualificationGranted", false);
        try {
            if (mapper == null) throw new FullRnaInvalid("MAPPER_REQUIRED");
            if (!(context instanceof ObjectNode) || material == null) throw new FullRnaInvalid("VERIFIED_REFERENCE_REQUIRED");
            var hashes = new RuntimeEvidenceV2Adapter(mapper);
            rnaRequire(hashes.canonicalSha256(context).equals(material.contextSha256()), "CONTEXT_BINDING_MISMATCH");
            if (configuredBinding == null || !only(configuredBinding, Set.of("relativePath", "sourceRecord"))
                    || !boundedText(configuredBinding.path("relativePath"), 1024)) throw new FullRnaInvalid("CONFIGURATION_INVALID");
            var source = configuredBinding.path("sourceRecord");
            var sources = mapper.createArrayNode().add(source);
            rnaRequire(only(source, Set.of("sourceId", "version", "url", "sha256"))
                    && CodingTranscriptMapping.sourcesValid(sources), "SOURCE_RECORD_INVALID");
            byte[] raw = readFullRnaOriginal(configuredRoot, configuredBinding.path("relativePath").asText());
            String rawSha = CodingTranscriptMaterialSupply.sha(raw);
            rnaRequire(rawSha.equals(source.path("sha256").asText()), "ORIGINAL_SHA_MISMATCH");
            ObjectNode original;
            try { original = parseFullRnaOriginal(raw); }
            catch (IOException invalidJson) { throw new FullRnaInvalid("ORIGINAL_JSON_INVALID"); }
            rnaRequire(only(original, Set.of("schemaVersion", "reviewId", "basis", "scope", "use", "assay", "physicalAllele", "events", "sequence", "topology", "pvs1Assessment"))
                    && FULL_RNA_ORIGINAL_SCHEMA.equals(original.path("schemaVersion").asText())
                    && boundedText(original.path("reviewId"), 256) && boundedText(original.path("basis"), 4000), "ORIGINAL_SCHEMA_INVALID");
            rnaRequire(!original.has("pvs1Assessment") || original.path("pvs1Assessment").isObject(), "PVS1_ASSESSMENT_INVALID");
            var use = original.path("use");
            rnaRequire(only(use, Set.of("purpose", "dataClass", "clinicalAcceptance", "createsExpertReview"))
                    && "PROJECT_RESEARCH".equals(use.path("purpose").asText())
                    && Set.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(use.path("dataClass").asText())
                    && use.path("dataClass").equals(context.path("dataClass"))
                    && isFalse(use.path("clinicalAcceptance")) && isFalse(use.path("createsExpertReview")), "ORIGINAL_USE_RESTRICTED");
            var scope = original.path("scope");var model = material.model();var allele = context.at("/variant/evidenceQueryAllele");
            rnaRequire(only(scope, Set.of("assembly", "contig", "hgncId", "geneSymbol", "transcript", "modelSha256", "referenceRnaSha256", "allele"))
                    && scope.path("assembly").equals(model.path("assembly")) && scope.path("contig").equals(model.path("contig"))
                    && scope.path("hgncId").equals(model.path("hgncId")) && scope.path("geneSymbol").equals(model.path("geneSymbol"))
                    && scope.path("transcript").equals(model.path("transcript")) && scope.path("allele").equals(allele)
                    && material.modelSha256().equals(scope.path("modelSha256").asText())
                    && material.referenceRnaSha256().equals(scope.path("referenceRnaSha256").asText()), "ORIGINAL_SCOPE_MISMATCH");
            var assay = original.path("assay");
            String multiplicity = assay.path("outcomeMultiplicity").asText();
            rnaRequire(only(assay, Set.of("evidenceKind", "alleleAttribution", "assayQuality", "tissueRelevance", "residualNormalTranscript", "outcomeMultiplicity", "basis"))
                    && "OBSERVED_RNA".equals(assay.path("evidenceKind").asText())
                    && "ASSESSED".equals(assay.path("alleleAttribution").asText())
                    && Set.of("ASSESSED", "UNKNOWN").contains(assay.path("assayQuality").asText())
                    && Set.of("ASSESSED", "UNKNOWN").contains(assay.path("tissueRelevance").asText())
                    && Set.of("NOT_DETECTED_IN_ASSESSED_SCOPE", "DETECTED_IN_ASSESSED_SCOPE", "UNKNOWN").contains(assay.path("residualNormalTranscript").asText())
                    && Set.of("SINGLE_SUPPORTED_OUTCOME", "MULTIPLE_OUTCOMES").contains(multiplicity)
                    && boundedText(assay.path("basis"), 4000), "OBSERVED_ASSAY_SCOPE_REQUIRED");
            String alleleSha = hashes.canonicalSha256(allele);var physical = original.path("physicalAllele");
            rnaRequire(only(physical, Set.of("status", "alleleSha256", "basis"))
                    && "SINGLE_PHYSICAL_ALLELE".equals(physical.path("status").asText())
                    && alleleSha.equals(physical.path("alleleSha256").asText())
                    && boundedText(physical.path("basis"), 4000), "PHYSICAL_ALLELE_SCOPE_REQUIRED");
            if ("MULTIPLE_OUTCOMES".equals(multiplicity)) {
                // Preserve the allele-bound observation without selecting a product for replay.
                audit.put("originalSha256", rawSha);
                audit.set("sourceRecord", source.deepCopy());
                audit.set("assayConditions", assay.deepCopy());
                throw new FullRnaInvalid("MULTIPLE_OUTCOMES_UNRESOLVED");
            }
            var events = original.path("events");
            rnaRequire(only(events, Set.of("coordinateSystem", "sequenceSemantics", "rnaEdits"))
                    && CodingTranscriptEdits.RNA_COORDINATES.equals(events.path("coordinateSystem").asText())
                    && "REFERENCE_RNA_EDIT_DELTAS".equals(events.path("sequenceSemantics").asText())
                    && events.path("rnaEdits").isArray() && events.path("rnaEdits").size() <= 999, "RNA_EVENT_DECLARATION_INVALID");
            var reference = CodingTranscriptEdits.bindRna(mapper, context, material);
            var edits = new ArrayList<CodingTranscriptEdits.Edit>();var ids = new HashSet<String>();
            for (var event : events.path("rnaEdits")) {
                rnaRequire(only(event, Set.of("eventId", "start0", "end0", "expectedReference", "insertedSequence"))
                        && boundedText(event.path("eventId"), 128) && ids.add(event.path("eventId").asText())
                        && nonnegativeInt(event.path("start0")) && nonnegativeInt(event.path("end0"))
                        && event.path("expectedReference").isTextual() && event.path("insertedSequence").isTextual(), "RNA_EVENT_DECLARATION_INVALID");
                String deleted = event.path("expectedReference").asText(), inserted = event.path("insertedSequence").asText();
                rnaRequire(deleted.isEmpty() || inserted.isEmpty() || deleted.charAt(0) != inserted.charAt(0)
                        && deleted.charAt(deleted.length() - 1) != inserted.charAt(inserted.length() - 1), "RNA_EVENT_NOT_MINIMAL_DELTA");
                edits.add(new CodingTranscriptEdits.Edit("source-rna:" + rawSha + ":" + event.path("eventId").asText(),
                        CodingTranscriptEdits.Origin.SPLICE_PROJECTION, event.path("start0").asInt(), event.path("end0").asInt(),
                        event.path("insertedSequence").asText(), event.path("expectedReference").asText(), rawSha, alleleSha, 0));
            }
            boolean intronic = addActualRnaAllele(reference, allele, alleleSha, edits);
            var sequence = original.path("sequence");String witness = sequence.path("completeAlteredRnaWitness").asText();
            rnaRequire(only(sequence, Set.of("completeAlteredRnaWitness", "alteredRnaSha256"))
                    && sequence.path("completeAlteredRnaWitness").isTextual() && witness.length() <= 2_000_000 && witness.matches("[ACGT]+")
                    && CodingTranscriptMaterialSupply.sha(witness).equals(sequence.path("alteredRnaSha256").asText()), "PRODUCT_WITNESS_INVALID");
            // A5 applies reference-coordinate deltas exactly once. The complete product is a comparison only.
            var result = CodingTranscriptEdits.applyRna(reference, new CodingTranscriptEdits.RnaEditSet(CodingTranscriptEdits.RNA_COORDINATES,
                    material.referenceRnaSha256(), CodingTranscriptEdits.RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE, edits, witness));
            var topology = original.path("topology");
            rnaRequire(only(topology, Set.of("completeness", "junctions0", "basis"))
                    && Set.of("COMPLETE", "UNKNOWN").contains(topology.path("completeness").asText())
                    && topology.path("junctions0").isArray() && topology.path("junctions0").size() <= 1000
                    && boundedText(topology.path("basis"), 4000), "TOPOLOGY_DECLARATION_INVALID");
            var junctions = new ArrayList<Integer>();int previous = 0;
            for (var boundary : topology.path("junctions0")) {
                rnaRequire(positive(boundary) && boundary.asInt() > previous && boundary.asInt() < result.alteredRna().length(), "TOPOLOGY_DECLARATION_INVALID");
                previous = boundary.asInt();junctions.add(previous);
            }
            rnaRequire(!"UNKNOWN".equals(topology.path("completeness").asText()) || junctions.isEmpty(), "UNKNOWN_TOPOLOGY_HAS_DECLARED_JUNCTIONS");
            var projected = Pvs1SpliceTopology.projectFullRna(result, new Pvs1SpliceTopology.RnaTopologyDeclaration(material.modelSha256(),
                    material.referenceRnaSha256(), result.alteredRnaSha256(), hashes.canonicalSha256(result.snapshot(mapper)),
                    Pvs1SpliceTopology.RnaTopologyCompleteness.valueOf(topology.path("completeness").asText()), junctions));
            var facts = CodingConsequenceFacts.deriveRna(mapper, context, result);
            var provenance = audit.deepCopy().put("status", "READY").put("reasonCode", "FULL_RNA_SOURCE_ORIGINAL_AND_ACTUAL_PRODUCT_BOUND")
                    .put("eligibilityStage", FULL_RNA_STAGE).put("originalVerificationStatus", "RAW_SHA_MATCH")
                    .put("originalBytesRead", raw.length).put("originalSha256", rawSha)
                    .put("contextSha256", material.contextSha256()).put("modelSha256", material.modelSha256())
                    .put("referenceRnaSha256", material.referenceRnaSha256()).put("alteredRnaSha256", result.alteredRnaSha256())
                    .put("mechanicalEffectSha256", facts.mechanicalEffectSha256()).put("factsSha256", facts.canonicalSha256())
                    .put("resultSha256", hashes.canonicalSha256(result.snapshot(mapper))).put("topologySha256", hashes.canonicalSha256(projected.snapshot()))
                    .put("dnaProjection", intronic ? "WHOLLY_WITHIN_SINGLE_REFERENCE_INTRON_NO_RNA_DNA_COMPONENT" : "ACTUAL_CONTEXT_ALLELE_PROJECTED_ONCE");
            provenance.set("sourceRecord", source.deepCopy());provenance.set("assayConditions", assay.deepCopy());
            provenance.set("physicalAlleleDeclaration", physical.deepCopy());provenance.set("topologyDeclaration", topology.deepCopy());
            return new FullRnaResolution(new SourceBoundFullRna(result, facts, projected, original, provenance, rawSha, raw), provenance);
        } catch (FullRnaInvalid invalid) { audit.put("reasonCode", invalid.getMessage()); }
        catch (IOException unavailable) { audit.put("reasonCode", "FULL_RNA_SOURCE_ORIGINAL_IO_UNAVAILABLE"); }
        catch (IllegalArgumentException invalidReplay) { audit.put("reasonCode", "FULL_RNA_SOURCE_ACTUAL_REPLAY_REJECTED"); }
        return new FullRnaResolution(null, audit);
    }

    /** Bounded observed-product conditions shared by length consumers, not a region grant. */
    static String fullRnaAssayIssue(@org.springframework.lang.Nullable SourceBoundFullRna source) {
        if(source==null)return "FULL_RNA_SOURCE_REQUIRED";
        var assay=source.original().path("assay");
        for(String field:List.of("alleleAttribution","assayQuality","tissueRelevance"))
            if(!"ASSESSED".equals(assay.path(field).asText()))return "FULL_RNA_"+field.toUpperCase(java.util.Locale.ROOT)+"_UNRESOLVED";
        if(!"SINGLE_SUPPORTED_OUTCOME".equals(assay.path("outcomeMultiplicity").asText()))return "FULL_RNA_MULTIPLE_OUTCOMES_UNRESOLVED";
        if(!"NOT_DETECTED_IN_ASSESSED_SCOPE".equals(assay.path("residualNormalTranscript").asText()))return "FULL_RNA_RESIDUAL_TRANSCRIPT_UNRESOLVED";
        return null;
    }

    static byte[] readFullRnaOriginal(Path configuredRoot, String relative) throws IOException {
        if (configuredRoot == null) throw new FullRnaInvalid("CONTROLLED_ROOT_REQUIRED");
        Path child;
        try { child = Path.of(relative); }
        catch (java.nio.file.InvalidPathException invalidPath) { throw new FullRnaInvalid("ORIGINAL_PATH_INVALID"); }
        rnaRequire(!child.isAbsolute() && child.getNameCount() > 0, "ORIGINAL_PATH_OUTSIDE_ROOT");
        for (var part : child) rnaRequire(!"..".equals(part.toString()) && !".".equals(part.toString()), "ORIGINAL_PATH_OUTSIDE_ROOT");
        Path root = configuredRoot.toRealPath(), candidate = root.resolve(child).normalize();
        rnaRequire(Files.isDirectory(root) && candidate.startsWith(root), "ORIGINAL_PATH_OUTSIDE_ROOT");
        Path real = candidate.toRealPath();
        rnaRequire(real.startsWith(root) && !real.equals(root), "ORIGINAL_PATH_OUTSIDE_ROOT");
        var before = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        rnaRequire(before.isRegularFile() && before.size() > 0 && before.size() <= FULL_RNA_ORIGINAL_MAX_BYTES, "ORIGINAL_BYTES_INVALID");
        byte[] raw;
        try (var input = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) { raw = input.readNBytes(FULL_RNA_ORIGINAL_MAX_BYTES + 1); }
        var after = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        rnaRequire(candidate.toRealPath().equals(real) && after.isRegularFile() && raw.length == before.size()
                && after.size() == before.size() && after.lastModifiedTime().equals(before.lastModifiedTime())
                && java.util.Objects.equals(after.fileKey(), before.fileKey()), "ORIGINAL_CHANGED_DURING_READ");
        return raw;
    }

    static ObjectNode parseRnaInitiationScientificJson(byte[] raw) throws IOException { return parseFullRnaOriginal(raw); }

    private static ObjectNode parseFullRnaOriginal(byte[] raw) throws IOException {
        // Reject malformed UTF-8 rather than silently replacing bytes before interpreting the pinned original.
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        var strict = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        int depth = 0, tokens = 0;
        try (var parser = strict.createParser(text)) {
            com.fasterxml.jackson.core.JsonToken token;
            while ((token = parser.nextToken()) != null) {
                rnaRequire(++tokens <= 200_000, "ORIGINAL_JSON_LIMIT_EXCEEDED");
                if (token.isStructStart()) rnaRequire(++depth <= 32, "ORIGINAL_JSON_LIMIT_EXCEEDED");
                else if (token.isStructEnd()) depth--;
            }
        }
        var parsed = strict.readTree(text);
        rnaRequire(parsed instanceof ObjectNode, "ORIGINAL_SCHEMA_INVALID");
        return (ObjectNode) parsed;
    }

    /** No second allele editor: A5 performs the projection. A rejected mapping may
     * be omitted only when its entire reference footprint is inside ONE true intron.
     * UTRs, flanking regions, crossed boundaries and full-intron footprints never vanish. */
    private static boolean addActualRnaAllele(CodingTranscriptEdits.RnaReference reference, JsonNode allele, String alleleSha,
            List<CodingTranscriptEdits.Edit> edits) {
        try { edits.add(CodingTranscriptEdits.projectRnaAllele(reference, allele, alleleSha));return false; }
        catch (CodingTranscriptEdits.Rejected rejected) {
            rnaRequire(Set.of("CODING_EDIT_RNA_GENOMIC_BOUNDARY_REQUIRES_SOURCE_OUTCOME", "CODING_EDIT_INSERTION_FLANKS_REQUIRE_SOURCE")
                    .contains(rejected.reasonCode()), "DNA_PROJECTION_REJECTED");
        }
        rnaRequire(positive(allele.path("position")) && allele.path("reference").isTextual() && allele.path("alternate").isTextual()
                && allele.path("reference").asText().matches("[ACGT]{1,50}") && allele.path("alternate").asText().matches("[ACGT]{1,50}"), "SHORT_ALLELE_REQUIRED");
        long start = allele.path("position").asInt(), end = start + allele.path("reference").asText().length() - 1;
        rnaRequire(end <= Integer.MAX_VALUE, "SHORT_ALLELE_REQUIRED");
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        int prefix = 0, suffix = 0;
        while (prefix < ref.length() && prefix < alt.length() && ref.charAt(prefix) == alt.charAt(prefix)) prefix++;
        while (suffix < ref.length() - prefix && suffix < alt.length() - prefix
                && ref.charAt(ref.length() - suffix - 1) == alt.charAt(alt.length() - suffix - 1)) suffix++;
        boolean insertion = ref.length() == prefix + suffix;
        long insertionLeft = start + prefix - 1, insertionRight = start + prefix;
        var exons = reference.material().exons();boolean withinOneIntron = false;
        for (int i = 1; i < exons.size(); i++) {
            var a = exons.get(i - 1);var b = exons.get(i);
            long lo = Math.min(a.genomicEnd(), b.genomicEnd()) + 1L;
            long hi = Math.max(a.genomicStart(), b.genomicStart()) - 1L;
            if (hi >= lo && start >= lo && end <= hi
                    && (!insertion || insertionLeft >= lo && insertionRight <= hi)) {
                rnaRequire(!(start <= lo && end >= hi), "FULL_INTRON_FOOTPRINT_REQUIRES_JUNCTION_REVIEW");
                withinOneIntron = true;break;
            }
        }
        rnaRequire(withinOneIntron, "DNA_FOOTPRINT_NOT_WHOLLY_INTRONIC");
        return true;
    }

    private static boolean boundedText(JsonNode value, int maximum) { return text(value) && value.asText().length() <= maximum; }
    private static boolean nonnegativeInt(JsonNode value) { return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0; }
    static boolean policyValid(JsonNode p) {
        return only(p, Set.of("policyId", "version", "purpose", "allowedDataClasses", "clinicalAcceptance", "createsExpertReview"))
                && text(p.path("policyId")) && VERSION.equals(p.path("version").asText())
                && "PROJECT_RESEARCH".equals(p.path("purpose").asText())
                && p.path("allowedDataClasses").isArray() && p.path("allowedDataClasses").size() == 2
                && "SYNTHETIC".equals(p.path("allowedDataClasses").get(0).asText())
                && "PRIVACY_BOUNDED_RUNTIME".equals(p.path("allowedDataClasses").get(1).asText())
                && isFalse(p.path("clinicalAcceptance")) && isFalse(p.path("createsExpertReview"));
    }

    static boolean entryValid(JsonNode q) {
        return only(q, Set.of("method", "originalPolicyReview", "sourceVerification", "reviewWitness",
                "originalCandidateRawSha256", "knowledge", "createsExpertReview", "clinicalAcceptance", "spliceVerification"))
                && "UNCHANGED_SOURCE_POLICY_CONDITIONS".equals(q.path("method").asText())
                && q.path("originalPolicyReview").isObject() && q.path("knowledge").isObject()
                && q.path("sourceVerification").isArray() && !q.path("sourceVerification").isEmpty()
                && q.path("sourceVerification").size() <= 256
                && Set.of("EXTERNAL_PINNED_REVIEW", "INLINE_REVIEW_IN_PINNED_ORIGINAL").contains(q.path("reviewWitness").asText())
                && q.path("originalCandidateRawSha256").asText().matches("[0-9a-f]{64}")
                && isFalse(q.path("createsExpertReview")) && isFalse(q.path("clinicalAcceptance"));
    }

    @org.springframework.lang.Nullable
    static String issue(JsonNode entry, JsonNode policy, RuntimeEvidenceV2Adapter adapter) {
        if (!Set.of("pvs1-truncating-nmd-policy-v1", "pvs1-truncating-nmd-policy-v2", "pvs1-truncating-nmd-policy-v3", Pvs1SpliceConsequence.SCHEMA)
                .contains(policy.path("schemaVersion").asText())) return "PVS1_SOURCE_QUALIFICATION_BRANCH_UNSUPPORTED";
        if (policy.at("/review/kind").asText().contains("SYNTHETIC_RESEARCH_ONLY"))
            return "PVS1_SOURCE_QUALIFICATION_ORIGINAL_USE_RESTRICTED";
        JsonNode q = entry.path("qualification");
        if (!entryValid(q) || !q.path("originalPolicyReview").equals(policy.path("review")))
            return "PVS1_SOURCE_QUALIFICATION_ORIGINAL_REVIEW_MISMATCH";
        var knowledge = q.path("knowledge");
        if (!only(knowledge, Set.of("mechanism", "nmdScope", "maximumStrength", "escapeScope", "spliceScope", "codingEditScope"))
                || !knowledge.path("mechanism").equals(policy.path("mechanism"))
                || !knowledge.path("nmdScope").equals(policy.path("nmdScope"))
                || !knowledge.path("maximumStrength").equals(policy.path("appliedStrength"))
                || !knowledge.path("escapeScope").equals(policy.path("escapeScope"))
                || !knowledge.path("spliceScope").equals(policy.path("spliceScope")))
            return "PVS1_SOURCE_QUALIFICATION_CONDITIONS_MISMATCH";
        if (!knowledge.path("codingEditScope").equals(policy.path("codingEditScope"))) return "PVS1_SOURCE_QUALIFICATION_CONDITIONS_MISMATCH";
        var sources = policy.path("scientificSources");
        if (!sources.isArray() || sources.size() != q.path("sourceVerification").size())
            return "PVS1_SOURCE_QUALIFICATION_BYTES_REQUIRED";
        var seen = new HashSet<String>();
        for (int i = 0; i < sources.size(); i++) {
            var s = sources.get(i); var proof = q.path("sourceVerification").get(i);
            if (!seen.add(s.path("sourceId").asText())
                    || !only(proof, Set.of("source", "status", "bytes"))
                    || !"RAW_SHA_MATCH".equals(proof.path("status").asText())
                    || !proof.path("bytes").isIntegralNumber() || !proof.path("bytes").canConvertToLong()
                    || proof.path("bytes").asLong() < 1 || proof.path("bytes").asLong() > 4_000_000
                    || !proof.path("source").equals(s)
                    || !s.path("sha256").asText().matches("[0-9a-f]{64}"))
                return "PVS1_SOURCE_QUALIFICATION_BYTES_REQUIRED";
        }
        if (!adapter.canonicalSha256(policy).equals(entry.path("policySha256").asText())
                || !policy.at("/transcriptModelBinding/canonicalSha256").equals(entry.path("modelSha256"))
                || !adapter.canonicalSha256(policy.path("nmdScope")).equals(entry.path("exonNmdScopeSha256").asText()))
            return "PVS1_KNOWLEDGE_POLICY_OR_EXON_BINDING_MISMATCH";
        if (Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText())) return spliceIssue(q, policy, adapter);
        return q.has("spliceVerification") ? "PVS1_SOURCE_QUALIFICATION_CONDITIONS_MISMATCH" : null;
    }

    /** Rebind the material actually consumed by the Bundle, rather than a policy-level permission. */
    static String spliceMaterialIssue(JsonNode assessment, JsonNode context, JsonNode policy, JsonNode model, JsonNode outcome,
                                      RuntimeEvidenceV2Adapter adapter) {
        var verification = assessment.at("/adoption/qualification/spliceVerification");
        if (!"READY".equals(assessment.path("status").asText()) || !verification.isObject() || !verification.path("model").isObject())
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_MATERIAL_REQUIRED";
        if (!adapter.canonicalSha256(model).equals(adapter.canonicalSha256(verification.path("model"))))
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_MODEL_MISMATCH";
        if (!outcome.isObject()) return "PVS1_SOURCE_QUALIFICATION_SPLICE_MATERIAL_REQUIRED";
        if (!outcome.path("allele").equals(context.at("/variant/evidenceQueryAllele"))
                || !outcome.path("hgncId").equals(context.at("/gene/hgncId"))
                || !outcome.path("mondoId").equals(context.at("/disease/mondoId"))
                || !outcome.path("moi").equals(context.path("moi")) || !outcome.path("transcript").equals(model.path("transcript")))
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_CONTEXT_MISMATCH";
        String pin = adapter.canonicalSha256(outcome);int matches = 0;JsonNode matched = null;
        for (var proof : verification.path("outcomes")) if (pin.equals(proof.path("canonicalSha256").asText()) && outcome.equals(proof.path("outcome"))) {
            matches++;matched = proof;
        }
        if (matches != 1 || matched == null) return "PVS1_SOURCE_QUALIFICATION_SPLICE_MATERIAL_UNBOUND";
        var sources = matched.path("sourceVerification");
        String issue = outcomeIssue(policy, model, outcome, sources, adapter);
        if (issue != null) return issue;
        return outcome.at("/inFrameReview/compositeEffectAssessment").isMissingNode() ? null
                : actualCompositeIssue(context, model, outcome, sources, adapter);
    }

    private static String spliceIssue(JsonNode q, JsonNode policy, RuntimeEvidenceV2Adapter adapter) {
        var v = q.path("spliceVerification");var model = v.path("model");
        if (!only(v, Set.of("model", "modelRawSha256", "outcomes")) || !model.isObject() || !hash(v.path("modelRawSha256"))
                || !adapter.canonicalSha256(model).equals(policy.at("/transcriptModelBinding/canonicalSha256").asText()))
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_MODEL_MISMATCH";
        if (!CodingTranscriptMapping.sourcesValid(policy.path("scientificSources"))) return "PVS1_SOURCE_QUALIFICATION_BYTES_REQUIRED";
        String scopeIssue = Pvs1SpliceConsequence.policyIssue(policy);
        if (scopeIssue == null) scopeIssue = Pvs1SpliceInFrame.policyIssue(policy, model);
        if (scopeIssue == null) scopeIssue = Pvs1NmdEscape.policyIssue(policy, model);
        if (scopeIssue != null) return scopeIssue;
        if (policy.has("codingEditScope") && !TruncatingNmdPolicy.complexAllelesEnabled(policy)) return "PVS1_SOURCE_QUALIFICATION_CONDITIONS_MISMATCH";
        if (!v.path("outcomes").isArray() || v.path("outcomes").size() > 1024)
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_MATERIAL_INVALID";
        var seen = new HashSet<String>();
        for (var proof : v.path("outcomes")) {
            var outcome = proof.path("outcome");
            if (!only(proof, Set.of("outcome", "rawSha256", "canonicalSha256", "bytes", "status", "sourceVerification"))
                    || !"RAW_SHA_MATCH".equals(proof.path("status").asText()) || !hash(proof.path("rawSha256"))
                    || !bytes(proof.path("bytes"), 2_000_000) || !outcome.isObject()
                    || !adapter.canonicalSha256(outcome).equals(proof.path("canonicalSha256").asText())
                    || !seen.add(proof.path("canonicalSha256").asText())
                    || !sourceProofs(outcome.path("sourceRecords"), proof.path("sourceVerification")))
                return "PVS1_SOURCE_QUALIFICATION_SPLICE_BYTES_REQUIRED";
            String issue = outcomeIssue(policy, model, outcome, proof.path("sourceVerification"), adapter);
            // An intact observation can establish that its own outcome is unresolved.
            // Apply that finding to the selected allele instead of disabling the policy.
            if (issue != null && !unresolvedSplice(issue)) return issue;
        }
        return null;
    }

    static boolean unresolvedSplice(String issue) {
        return switch (issue) {
            case "PVS1_SPLICE_OUTCOME_UNRESOLVED", "PVS1_SPLICE_FUNCTIONAL_RESCUE_UNRESOLVED",
                    "PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED" -> true;
            default -> false;
        };
    }

    private static String outcomeIssue(JsonNode policy, JsonNode model, JsonNode o, JsonNode verifiedSources, RuntimeEvidenceV2Adapter adapter) {
        if (!only(o, Set.of("schemaVersion", "outcomeId", "evidenceKind", "allele", "hgncId", "mondoId", "moi", "transcript",
                "transcriptModelSha256", "review", "event", "referenceWindow", "assessment", "sourceRecords", "inFrameReview", "codingEditComposition"))
                || !"pvs1-splice-outcome-v1".equals(o.path("schemaVersion").asText()) || !text(o.path("outcomeId")))
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_MATERIAL_INVALID";
        var scope = policy.path("scope");var allele = o.path("allele");
        boolean transcript = false;
        for (var tx : scope.path("transcripts")) if ((tx.path("accession").asText() + "." + tx.path("version").asText()).equals(o.path("transcript").asText())) transcript = true;
        if (!o.path("hgncId").equals(scope.path("hgncId")) || !o.path("mondoId").equals(scope.path("mondoId"))
                || !o.path("moi").equals(scope.path("moi")) || !allele.path("assembly").equals(scope.path("assembly"))
                || !transcript || !o.path("transcript").equals(model.path("transcript"))
                || !o.path("transcriptModelSha256").equals(policy.at("/transcriptModelBinding/canonicalSha256"))
                || !allele.path("contig").equals(model.path("contig")) || !positive(allele.path("position"))
                || !only(allele, Set.of("assembly", "contig", "position", "reference", "alternate"))
                || !allele.path("reference").asText().matches("[ACGT]{1,50}") || !allele.path("alternate").asText().matches("[ACGT]{1,50}")
                || allele.path("reference").equals(allele.path("alternate")))
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_CONTEXT_MISMATCH";
        var sources = o.path("sourceRecords");
        for (var s : sources) for (var declared : policy.path("scientificSources"))
            if (s.path("sourceId").equals(declared.path("sourceId")) && !s.equals(declared)) return "PVS1_SPLICE_SOURCE_IDENTITY_CONFLICT";
        var review = o.path("review");boolean predicted = "PREDICTED_CANONICAL".equals(o.path("evidenceKind").asText());
        if (!contains(policy.at("/spliceScope/evidenceKinds"), o.path("evidenceKind"))
                || !only(review, Set.of("status", "reviewId", "basis", "sourceIds", "maximumStrength", "functionalRescue", "outcomeMultiplicity", "residualNormalTranscript"))
                || !"REVIEWED_FOR_PVS1_SPLICE".equals(review.path("status").asText()) || !text(review.path("reviewId")) || !sourced(review, sources)
                || !Set.of("VERY_STRONG", "STRONG", "MODERATE", "SUPPORTING").contains(review.path("maximumStrength").asText()))
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_REVIEW_REQUIRED";
        var assay = o.path("assessment");
        if (!only(assay, Set.of("status", "basis", "sourceIds", "tissueRelevance", "alleleAttribution", "assayQuality", "nearbyRescueAssessment", "alternativeOutcomesAssessment"))
                || !sourced(assay, sources) || !(predicted ? "QUALIFIED_CANONICAL_PREDICTION" : "QUALIFIED_RNA_ASSAY").equals(assay.path("status").asText()))
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_ASSAY_REQUIRED";
        for (String field : predicted ? Set.of("nearbyRescueAssessment", "alternativeOutcomesAssessment") : Set.of("tissueRelevance", "alleleAttribution", "assayQuality", "alternativeOutcomesAssessment"))
            if (!"ASSESSED".equals(assay.path(field).asText())) return "PVS1_SOURCE_QUALIFICATION_SPLICE_ASSAY_REQUIRED";
        var event = o.path("event");
        if (!only(event, Set.of("type", "exonOrdinal", "deleteStartCds", "deleteEndCds"))
                || !contains(policy.at("/spliceScope/eventTypes"), event.path("type")) || !positive(event.path("exonOrdinal"))
                || event.path("exonOrdinal").asInt() > model.path("exons").size() || !positive(event.path("deleteStartCds"))
                || !positive(event.path("deleteEndCds")) || event.path("deleteStartCds").asInt() > event.path("deleteEndCds").asInt()
                || event.path("deleteEndCds").asInt() > model.path("cdsSequence").asText().length())
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_EVENT_INVALID";
        var exon = model.path("exons").path(event.path("exonOrdinal").asInt() - 1);
        int lo = event.path("deleteStartCds").asInt(), hi = event.path("deleteEndCds").asInt();
        int exonStart = Math.max(1, exon.path("cdsStart").asInt()), exonEnd = Math.min(model.path("cdsSequence").asText().length(), exon.path("cdsEnd").asInt());
        boolean whole = lo == exonStart && hi == exonEnd;
        if (lo < exonStart || hi > exonEnd || "EXON_SKIPPING".equals(event.path("type").asText()) != whole
                || !whole && lo != exon.path("cdsStart").asInt() && hi != exon.path("cdsEnd").asInt())
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_EVENT_INVALID";
        var window = o.path("referenceWindow");long offset = allele.path("position").asLong() - window.path("start").asLong();
        String sequence = window.path("sequence").asText(), ref = allele.path("reference").asText();
        if (!only(window, Set.of("start", "sequence")) || !positive(window.path("start")) || !sequence.matches("[ACGT]{1,100000}")
                || offset < 0 || offset + ref.length() > sequence.length() || !sequence.substring((int) offset, (int) offset + ref.length()).equals(ref))
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_REFERENCE_MISMATCH";
        if (o.has("inFrameReview")) {
            var frame = o.path("inFrameReview");
            if (!only(frame, Set.of("status", "reviewId", "basis", "sourceIds", "criticalRegionAssessment", "compositeEffectAssessment"))
                    || !"REVIEWED_FOR_PVS1_SPLICE_INFRAME".equals(frame.path("status").asText())
                    || !text(frame.path("reviewId")) || !sourced(frame, sources)) return "PVS1_SPLICE_INFRAME_OUTCOME_NOT_REVIEWED";
            if (frame.has("criticalRegionAssessment")) {
                var region = frame.path("criticalRegionAssessment");
                if (!only(region, Set.of("status", "coverage", "finding", "start", "end", "basis", "sourceIds")) || !sourced(region, sources)
                        || !Set.of("REVIEWED_IN_DECLARED_SCOPE", "UNREVIEWED").contains(region.path("status").asText())
                        || !Set.of("COMPLETE_FOR_AFFECTED_PROTEIN_REGION", "INCOMPLETE", "UNKNOWN").contains(region.path("coverage").asText())
                        || !Set.of("NO_KNOWN_CRITICAL_REGION_AFFECTED", "KNOWN_CRITICAL_REGION_AFFECTED", "UNKNOWN").contains(region.path("finding").asText())
                        || !positive(region.path("start")) || !positive(region.path("end")) || region.path("start").asInt() > region.path("end").asInt()
                        || region.path("end").asInt() > model.path("proteinLength").asInt()) return "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_INVALID";
            }
        }
        if (!o.at("/inFrameReview/compositeEffectAssessment").isMissingNode() && policy.at("/spliceScope/inFrameScope").isMissingNode())
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_POLICY_SCOPE_REQUIRED";
        String compositionIssue = o.has("codingEditComposition") ? compositionIssue(o, model, adapter) : null;
        if (compositionIssue != null) return compositionIssue;
        String originalIssue = compositeOriginalIssue(model, o, verifiedSources);
        if (originalIssue != null) return originalIssue;
        if (!"SINGLE_SUPPORTED_OUTCOME".equals(review.path("outcomeMultiplicity").asText()))
            return "PVS1_SPLICE_OUTCOME_UNRESOLVED";
        if (!"EXCLUDED_IN_REVIEWED_SCOPE".equals(review.path("functionalRescue").asText()))
            return "PVS1_SPLICE_FUNCTIONAL_RESCUE_UNRESOLVED";
        return Pvs1SpliceConsequence.residualTranscriptCompatible(review, predicted)
                ? null : "PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED";
    }

    /** A pinned structured review original is evidence of the declared use and
     * complete effect, not a new expert review. Its bytes are independently read
     * by the existing source transport; the body never contains its own hash.
     */
    private static String compositeOriginalIssue(JsonNode model, JsonNode outcome, JsonNode verifiedSources) {
        var effect = outcome.at("/inFrameReview/compositeEffectAssessment");
        if (effect.isMissingNode()) return null;
        var composition = outcome.path("codingEditComposition");
        if (!only(effect, Set.of("schemaVersion", "status", "mechanicalEffectSha256", "reviewId", "basis", "sourceIds", "originalReview"))
                || !"pvs1-composite-inframe-effect-review-v1".equals(effect.path("schemaVersion").asText())
                || !"REVIEWED_FOR_ACTUAL_COMPOSITE_EFFECT".equals(effect.path("status").asText())
                || !hash(effect.path("mechanicalEffectSha256")) || !text(effect.path("reviewId"))
                || !sourced(effect, outcome.path("sourceRecords"))
                || !"COMPLETE_ALLELIC_CDS".equals(composition.path("sequenceScope").asText()))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_REVIEW_REQUIRED";
        var witness = effect.path("originalReview");
        if (!only(witness, Set.of("sourceId", "structuredOriginalUtf8")) || !text(witness.path("sourceId"))
                || !witness.path("structuredOriginalUtf8").isTextual() || !contains(effect.path("sourceIds"), witness.path("sourceId")))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_REQUIRED";
        String raw = witness.path("structuredOriginalUtf8").asText();byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 65_536 || !raw.equals(new String(bytes, StandardCharsets.UTF_8)))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_BYTES_INVALID";
        JsonNode source = null;int matches = 0;
        for (var value : outcome.path("sourceRecords")) if (witness.path("sourceId").equals(value.path("sourceId"))) { source = value;matches++; }
        if (matches != 1 || source == null || !CodingTranscriptMaterialSupply.sha(bytes).equals(source.path("sha256").asText()))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_BYTES_INVALID";
        int proofMatches = 0;
        for (var proof : verifiedSources) if (source.equals(proof.path("source")) && "RAW_SHA_MATCH".equals(proof.path("status").asText())
                && proof.path("bytes").isIntegralNumber() && proof.path("bytes").canConvertToInt() && proof.path("bytes").asInt() == bytes.length) proofMatches++;
        if (proofMatches != 1) return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_BYTES_INVALID";
        JsonNode original;
        try { original = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes); }
        catch (java.io.IOException invalid) { return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_INVALID"; }
        if (original == null || !only(original, Set.of("schemaVersion", "reviewId", "scope", "sequence", "events", "conditions", "use"))
                || !"pvs1-composite-effect-review-original-v1".equals(original.path("schemaVersion").asText())
                || !original.path("reviewId").equals(effect.path("reviewId")))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_INVALID";
        var scope = original.path("scope");
        if (!only(scope, Set.of("hgncId", "mondoId", "moi", "transcript", "transcriptModelSha256", "allele")))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_SCOPE_MISMATCH";
        for (String key : List.of("hgncId", "mondoId", "moi", "transcript", "transcriptModelSha256", "allele"))
            if (!scope.path(key).equals(outcome.path(key))) return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_SCOPE_MISMATCH";
        var sequence = original.path("sequence");
        if (!only(sequence, Set.of("referenceCdsSha256", "alteredCdsSequence", "alteredCdsSha256"))
                || !CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()).equals(sequence.path("referenceCdsSha256").asText())
                || !sequence.path("alteredCdsSequence").equals(composition.path("alteredCdsSequence"))
                || !sequence.path("alteredCdsSequence").asText().matches("[ACGT]{1,2000000}")
                || !CodingTranscriptMaterialSupply.sha(sequence.path("alteredCdsSequence").asText()).equals(sequence.path("alteredCdsSha256").asText()))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_SEQUENCE_MISMATCH";
        var events = original.path("events");
        if (!only(events, Set.of("rnaEvent", "codingAllele", "codingEditComposition"))
                || !events.path("rnaEvent").equals(outcome.path("event")) || !events.path("codingAllele").equals(outcome.path("allele"))
                || !events.path("codingEditComposition").equals(composition))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_EVENTS_MISMATCH";
        var conditions = original.path("conditions");
        var inFrame = outcome.path("inFrameReview").deepCopy();
        if (!(inFrame instanceof com.fasterxml.jackson.databind.node.ObjectNode frame))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_CONDITIONS_MISMATCH";
        frame.remove("compositeEffectAssessment");
        if (!only(conditions, Set.of("parentReview", "assayAssessment", "inFrameReview"))
                || !conditions.path("parentReview").equals(outcome.path("review"))
                || !conditions.path("assayAssessment").equals(outcome.path("assessment")) || !conditions.path("inFrameReview").equals(frame))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_CONDITIONS_MISMATCH";
        var use = original.path("use");
        if (!only(use, Set.of("purpose", "allowedDataClasses", "clinicalAcceptance", "createsExpertReview"))
                || !"PROJECT_RESEARCH".equals(use.path("purpose").asText())
                || !use.path("allowedDataClasses").isArray() || use.path("allowedDataClasses").size() != 2
                || !"SYNTHETIC".equals(use.path("allowedDataClasses").path(0).asText())
                || !"PRIVACY_BOUNDED_RUNTIME".equals(use.path("allowedDataClasses").path(1).asText())
                || !isFalse(use.path("clinicalAcceptance")) || !isFalse(use.path("createsExpertReview")))
            return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_ORIGINAL_USE_RESTRICTED";
        return null;
    }

    /** The normal Bundle binding has the actual context. Replay existing A5
     * mechanics here; Python validates the original bytes but is not a second editor.
     */
    private static String actualCompositeIssue(JsonNode context, JsonNode model, JsonNode outcome, JsonNode proofs,
                                                RuntimeEvidenceV2Adapter adapter) {
        String originalIssue = compositeOriginalIssue(model, outcome, proofs);
        if (originalIssue != null) return originalIssue;
        try {
            var mapper = new ObjectMapper();String cds = model.path("cdsSequence").asText();
            var reference = CodingTranscriptEdits.bind(mapper, context, model, adapter.canonicalSha256(model), CodingTranscriptMaterialSupply.sha(cds));
            var composed = Pvs1SpliceComposition.compose(mapper, context, model, outcome,
                    outcome.at("/event/deleteStartCds").asInt() - 1, outcome.at("/event/deleteEndCds").asInt());
            if (!composed.retainedCodingEdit()) return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_RETAINED_EDIT_REQUIRED";
            var actual = CodingConsequenceFacts.derive(mapper, reference, composed.result());
            if (!actual.nativeStartRetained() || !"NATIVE_STOP".equals(actual.firstStopKind()) || actual.inFrameNetDeletedProteinResidues() == null)
                return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_INFRAME_EFFECT_REQUIRED";
            if (!actual.mechanicalEffectSha256().equals(outcome.at("/inFrameReview/compositeEffectAssessment/mechanicalEffectSha256").asText()))
                return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_EFFECT_BINDING_MISMATCH";
            return null;
        } catch (IllegalArgumentException invalid) { return "PVS1_SOURCE_QUALIFICATION_COMPOSITE_EFFECT_REPLAY_FAILED"; }
    }

    private static String compositionIssue(JsonNode outcome, JsonNode model, RuntimeEvidenceV2Adapter adapter) {
        var c = outcome.path("codingEditComposition");
        if (!only(c, Set.of("schemaVersion", "status", "reviewId", "basis", "sourceIds", "alleleGroupSha256", "referenceCdsSha256", "spliceEventSha256", "sequenceScope", "alteredCdsSequence", "codingHgvsc"))
                || !"pvs1-splice-coding-composition-v1".equals(c.path("schemaVersion").asText())
                || !"REVIEWED_FOR_SAME_ALLELE_COMPOSITION".equals(c.path("status").asText()) || !text(c.path("reviewId")) || !sourced(c, outcome.path("sourceRecords"))
                || !adapter.canonicalSha256(outcome.path("allele")).equals(c.path("alleleGroupSha256").asText())
                || !CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()).equals(c.path("referenceCdsSha256").asText())
                || !adapter.canonicalSha256(outcome.path("event")).equals(c.path("spliceEventSha256").asText())
                || !Set.of("REFERENCE_CDS_DELETION", "COMPLETE_ALLELIC_CDS").contains(c.path("sequenceScope").asText())
                || "COMPLETE_ALLELIC_CDS".equals(c.path("sequenceScope").asText()) && !c.path("alteredCdsSequence").asText().matches("[ACGT]{1,2000000}")
                || "REFERENCE_CDS_DELETION".equals(c.path("sequenceScope").asText()) && c.has("alteredCdsSequence")
                || c.has("codingHgvsc") && !text(c.path("codingHgvsc")))
            return "PVS1_SOURCE_QUALIFICATION_SPLICE_COMPOSITION_INVALID";
        return null;
    }

    private static boolean sourceProofs(JsonNode sources, JsonNode proofs) {
        if (!CodingTranscriptMapping.sourcesValid(sources) || sources.size() > 256 || !proofs.isArray() || sources.size() != proofs.size()) return false;
        for (int i = 0; i < sources.size(); i++) if (!only(proofs.get(i), Set.of("source", "status", "bytes"))
                || !sources.get(i).equals(proofs.get(i).path("source")) || !"RAW_SHA_MATCH".equals(proofs.get(i).path("status").asText())
                || !bytes(proofs.get(i).path("bytes"), 4_000_000)) return false;
        return true;
    }
    private static boolean sourced(JsonNode value, JsonNode sources) {
        if (!text(value.path("basis")) || !value.path("sourceIds").isArray() || value.path("sourceIds").isEmpty()) return false;
        var seen = new HashSet<String>();for (var id : value.path("sourceIds")) {
            if (!text(id) || !seen.add(id.asText())) return false;
            boolean found = false;for (var source : sources) if (id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }return true;
    }
    private static boolean contains(JsonNode values, JsonNode target) { for (var value : values) if (value.equals(target)) return true;return false; }
    private static boolean hash(JsonNode value) { return value.isTextual() && value.asText().matches("[0-9a-f]{64}"); }
    private static boolean positive(JsonNode value) { return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0; }
    private static boolean bytes(JsonNode value, long maximum) { return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0 && value.asLong() <= maximum; }

    private static boolean only(JsonNode n, Set<String> keys) {
        if (!n.isObject()) return false;
        var names = n.fieldNames(); while (names.hasNext()) if (!keys.contains(names.next())) return false;
        return true;
    }
    private static boolean text(JsonNode n) { return n.isTextual() && !n.asText().isBlank(); }
    private static boolean isFalse(JsonNode n) { return n.isBoolean() && !n.asBoolean(); }
}
