package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Exact versioned transcript material acquisition, independent of disease/prediction policy.
 * A configured manifest pins technical material, not clinical relevance or scientific adoption.
 */
public final class CodingTranscriptMaterialSupply {
    public static final String VERSION = "1.1.0";
    public static final String STRUCTURE_VERSION = "1.0.0";
    public static final String RNA_VERSION = "1.0.0";
    private enum Purpose { MISSENSE, STRUCTURE, PROTEIN_LENGTH_CHANGE, STOP_LOSS, FULL_RNA }
    private static final int MAX_BYTES = 4_000_000, MAX_BASES = 1_000_000;
    @FunctionalInterface public interface ReferenceReader {
        /** Must validate the current reference against a caller-trusted receipt on EVERY call. */
        ReferenceSpan read(String contig, int start, int length) throws IOException;
    }
    public record ReferenceSpan(String bases, ObjectNode identity) { }
    /** Full reference-transcript coordinates are one-based and inclusive, in transcript order. */
    public record RnaExonSpan(int referenceOrdinal, int startRna1, int endRna1, int genomicStart, int genomicEnd) { }

    /** Opaque technical sequence proof. JSON and caller-declared hashes cannot issue this material. */
    public static final class VerifiedRna {
        private final ObjectNode model, referenceIdentity, snapshot;
        private final String sequence, sequenceSha, modelSha, contextSha;
        private final int cdsStart, cdsEnd;
        private final java.util.List<RnaExonSpan> exons;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private VerifiedRna(ObjectMapper mapper, RuntimeEvidenceV2Adapter adapter, ObjectNode model, ObjectNode context,
                            String sequence, ObjectNode referenceIdentity) {
            this.model = model.deepCopy(); this.referenceIdentity = referenceIdentity.deepCopy(); this.sequence = sequence;
            sequenceSha = sha(sequence); modelSha = adapter.canonicalSha256(model); contextSha = adapter.canonicalSha256(context);
            cdsStart = Math.toIntExact(2L - model.path("exons").get(0).path("cdsStart").asInt());
            cdsEnd = Math.addExact(cdsStart, model.path("cdsLength").asInt() - 1);
            require(cdsStart > 0 && cdsEnd >= cdsStart && cdsEnd <= sequence.length()
                    && sequence.substring(cdsStart - 1, cdsEnd).equals(model.path("cdsSequence").asText()),
                    "TRANSCRIPT_RNA_CDS_BOUNDS_INVALID");
            var spans = new java.util.ArrayList<RnaExonSpan>(); int start = 1;
            for (var exon : model.path("exons")) {
                int end = Math.addExact(start, exon.path("genomicEnd").asInt() - exon.path("genomicStart").asInt());
                spans.add(new RnaExonSpan(exon.path("ordinal").asInt(), start, end,
                        exon.path("genomicStart").asInt(), exon.path("genomicEnd").asInt()));
                start = Math.addExact(end, 1);
            }
            require(start - 1 == sequence.length(), "TRANSCRIPT_RNA_EXON_COVERAGE_INVALID");
            exons = java.util.List.copyOf(spans);
            snapshot = mapper.createObjectNode().put("schemaVersion", "verified-full-reference-rna-v1").put("methodVersion", RNA_VERSION)
                    .put("sequenceScope", "FULL_SPLICED_REFERENCE_RNA").put("coordinateSystem", "TRANSCRIPT_RNA_1_BASED_INCLUSIVE")
                    .put("referenceRnaSha256", sequenceSha).put("referenceRnaLength", sequence.length())
                    .put("cdsStartRna1", cdsStart).put("cdsEndRna1", cdsEnd)
                    .put("scientificQualificationGranted", false).put("alleleEffectVerified", false).put("phaseQualificationGranted", false);
            snapshot.putObject("binding").put("modelSha256", modelSha).put("contextSha256", contextSha)
                    .put("referenceCdsSha256", sha(model.path("cdsSequence").asText()))
                    .put("referenceIdentitySha256", adapter.canonicalSha256(referenceIdentity))
                    .put("modelSourcesSha256", adapter.canonicalSha256(model.path("sourceRecords")));
            snapshot.set("referenceIdentity", referenceIdentity.deepCopy()); snapshot.set("sourceRecords", model.path("sourceRecords").deepCopy());
            snapshot.set("exons", mapper.valueToTree(exons));
        }
        public ObjectNode model() { return model.deepCopy(); }
        public String referenceRnaSequence() { return sequence; }
        public String referenceRnaSha256() { return sequenceSha; }
        public int cdsStartRna1() { return cdsStart; }
        public int cdsEndRna1() { return cdsEnd; }
        public java.util.List<RnaExonSpan> exons() { return exons; }
        public String modelSha256() { return modelSha; }
        public String contextSha256() { return contextSha; }
        public ObjectNode referenceIdentity() { return referenceIdentity.deepCopy(); }
        public ObjectNode snapshot() { return snapshot.deepCopy(); }
    }

    public static final class Material {
        private final ObjectNode audit;
        @Nullable private final ObjectNode model;
        @Nullable private final VerifiedRna verifiedRna;
        private Material(ObjectNode audit, @Nullable ObjectNode model) { this(audit, model, null); }
        private Material(ObjectNode audit, @Nullable ObjectNode model, @Nullable VerifiedRna verifiedRna) {
            this.audit = audit.deepCopy(); this.model = model == null ? null : model.deepCopy(); this.verifiedRna = verifiedRna;
        }
        public ObjectNode snapshot() { return audit.deepCopy(); }
        @Nullable public ObjectNode model() { var snapshot = model;return snapshot == null ? null : snapshot.deepCopy(); }
        public boolean ready() { return model != null; }
        @Nullable public VerifiedRna verifiedRna() { return verifiedRna; }
    }
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    @Nullable private final Path root;
    @Nullable private final GencodeTranscriptIndex gencode;
    private final String manifestSha;
    private final Map<String, ObjectNode> entries = new LinkedHashMap<>();
    private record VerifiedTranscript(ObjectNode audit, String threePrimeWindow, int threePrimeBases, String referenceRnaSequence) { }
    private final Map<String, VerifiedTranscript> verified = new LinkedHashMap<>();
    private final ReferenceReader reference;
    private final boolean complexAlleles;
    @Nullable private final ProteinLengthMaterialFactory.Capability sequenceCapability;

    public CodingTranscriptMaterialSupply(ObjectMapper mapper, Path root, String manifestSha, ReferenceReader reference) throws IOException {
        this(mapper, root, manifestSha, reference, null);
    }

    /** GENCODE is used only when the exact prebuilt model is absent, never to replace a corrupt existing model. */
    public CodingTranscriptMaterialSupply(ObjectMapper mapper, @Nullable Path root, String manifestSha,
            ReferenceReader reference, @Nullable GencodeTranscriptIndex gencode) throws IOException {
        this(mapper, root, manifestSha, reference, gencode, false);
    }

    /** Explicit sequence-only extension. Existing constructors retain their bounded simple-allele scope. */
    public CodingTranscriptMaterialSupply(ObjectMapper mapper, @Nullable Path root, String manifestSha,
            ReferenceReader reference, @Nullable GencodeTranscriptIndex gencode, boolean complexAlleles) throws IOException {
        this(mapper, root, manifestSha, reference, gencode, complexAlleles, null);
    }

    CodingTranscriptMaterialSupply(ObjectMapper mapper, @Nullable Path root, String manifestSha,
            ReferenceReader reference, @Nullable GencodeTranscriptIndex gencode, boolean complexAlleles,
            @Nullable ProteinLengthMaterialFactory.Capability sequenceCapability) throws IOException {
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        adapter = new RuntimeEvidenceV2Adapter(mapper); this.root = root == null ? null : root.toRealPath(); this.manifestSha = manifestSha;
        this.gencode = gencode;
        this.reference = java.util.Objects.requireNonNull(reference);
        this.complexAlleles = complexAlleles;
        this.sequenceCapability = sequenceCapability;
        var currentRoot = this.root;
        if (currentRoot == null) { require(gencode != null, "TRANSCRIPT_MATERIAL_SOURCE_REQUIRED"); return; }
        var manifest = read(currentRoot.resolve("manifest.json"), manifestSha);
        require("portable-native-transcript-model-derivation-v1".equals(manifest.path("schemaVersion").asText())
                && manifest.path("entries").isArray() && manifest.path("entries").size() <= 10_000, "TRANSCRIPT_MATERIAL_MANIFEST_INVALID");
        for (var entry : manifest.path("entries")) {
            String tx = entry.path("transcript").asText();
            require(entry instanceof ObjectNode && tx.matches("(?:ENST[0-9]+|NM_[0-9]+)\\.[1-9][0-9]*")
                    && (tx + ".model.json").equals(entry.path("file").asText())
                    && entry.path("fileSha256").asText().matches("[0-9a-f]{64}")
                    && entry.path("canonicalSha256").asText().matches("[0-9a-f]{64}")
                    && entry.path("fileBytes").isIntegralNumber() && entry.path("fileBytes").canConvertToInt()
                    && entry.path("fileBytes").asInt() > 0 && entry.path("fileBytes").asInt() <= MAX_BYTES,
                    "TRANSCRIPT_MATERIAL_MANIFEST_ENTRY_INVALID");
            require(entries.putIfAbsent(tx, (ObjectNode) entry.deepCopy()) == null, "TRANSCRIPT_MATERIAL_AMBIGUOUS");
        }
    }

    public ObjectNode identity() {
        var identity = mapper.createObjectNode().put("method", "EXACT_TRANSCRIPT_LOCAL_MATERIAL_SUPPLY").put("version", VERSION)
                .put("manifestSha256", manifestSha).put("clinicalAcceptance", false).put("networkAllowed", false);
        var gencodeSource = gencode;
        if (gencodeSource != null) identity.set("gencode", gencodeSource.identity());
        return identity;
    }

    /** Bind this identity when opting into the length-change path; legacy prediction identity is separate. */
    public ObjectNode proteinLengthChangeIdentity() {
        var out = identity().put("associationKind", complexAlleles ? "VERIFIED_INFRAME_LENGTH_CHANGE" : "SIMPLE_INFRAME_LENGTH_CHANGE")
                .put("associationVersion", complexAlleles ? "2.0.0" : "1.0.0")
                .put("scientificEvaluationPerformed", false).put("materialPurpose", "PROTEIN_LENGTH_SEQUENCE_CHECK_ONLY");
        var capability = sequenceCapability;
        if (capability != null) out.set("sequenceCapability", capability.snapshot(mapper));
        return out;
    }

    /** Supplies the existing bounded simple-inframe sequence facts, not PM4/BP3 region or policy approval.
     * This additive path has its own association identity; the missense path and its identity stay unchanged.
     */
    public synchronized Material resolveProteinLengthChange(ObjectNode context, VariantInterpretation candidate) {
        return resolve(context, candidate, Purpose.PROTEIN_LENGTH_CHANGE);
    }

    public ObjectNode stopLossIdentity() {
        var out = identity().put("associationKind", complexAlleles ? "VERIFIED_STOP_LOSS_SMALL_ALLELE" : "STOP_LOSS_SNV")
                .put("associationVersion", complexAlleles ? "2.0.0" : ProteinStopLossObservation.VERSION)
                .put("maximumThreePrimeWindowBases", ProteinStopLossObservation.MAXIMUM_TAIL_BASES)
                .put("scientificEvaluationPerformed", false).put("materialPurpose", "STOP_LOSS_SEQUENCE_CHECK_ONLY");
        var capability = sequenceCapability;
        if (capability != null) out.set("sequenceCapability", capability.snapshot(mapper));
        return out;
    }

    public boolean complexAllelesEnabled() { return complexAlleles; }

    /** Reuses the existing full-spliced reference proof; never guesses sequence beyond the annotated transcript. */
    public synchronized Material resolveStopLoss(ObjectNode context, VariantInterpretation candidate) {
        return resolve(context, candidate, Purpose.STOP_LOSS);
    }

    /** Planning hint only. Full reference, VEP and allele checks still run in resolve.
     * Only exact identifiers or an existing independently verified full transcript bridge qualify.
     * A corrupt present model is not replaced by GENCODE or another unpinned source.
     */
    public boolean matchesDeclaredTranscript(ObjectNode context, String execution, java.util.List<String> declared) {
        if (declared.contains(execution)) return true;
        var entry = entries.get(execution);var materialRoot = root;
        if (entry == null || materialRoot == null) return false;
        try {
            Path file = materialRoot.resolve(entry.path("file").asText()).toRealPath();
            require(file.getParent().equals(materialRoot) && Files.size(file) == entry.path("fileBytes").asLong(),
                    "TRANSCRIPT_MATERIAL_BYTES_MISMATCH");
            var model = read(file, entry.path("fileSha256").asText());
            require(entry.path("canonicalSha256").asText().equals(adapter.canonicalSha256(model)),
                    "TRANSCRIPT_MATERIAL_MODEL_IDENTITY_MISMATCH");
            return model.path("hgncId").equals(context.at("/gene/hgncId"))
                    && model.path("assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"))
                    && declared.stream().anyMatch(tx -> PublishedCriterionKnowledge.exactBridge(tx, model));
        } catch (IOException invalid) { throw new IllegalArgumentException("TRANSCRIPT_MATERIAL_READ_FAILED", invalid); }
    }

    /** Model/reference work is cached; the allele and stored VEP are checked anew for each candidate. */
    public synchronized Material resolve(ObjectNode context, VariantInterpretation candidate) {
        return resolve(context, candidate, Purpose.MISSENSE);
    }

    /** Technical sequence/identity only. The consumer must independently validate the allele's effect and its policy. */
    public synchronized Material resolveStructure(ObjectNode context, VariantInterpretation candidate) {
        return resolve(context, candidate, Purpose.STRUCTURE);
    }

    /** Issues full reference RNA only after the current reference and complete exon sequence agree. */
    public synchronized Material resolveRna(ObjectNode context, VariantInterpretation candidate) {
        return resolve(context, candidate, Purpose.FULL_RNA);
    }

    /** Rechecks the current reference before exposing a source-bound RNA length observation.
     * This is sequence/source material only; it never issues region or criterion eligibility. */
    synchronized Material resolveFullRnaLength(ObjectNode context, VariantInterpretation candidate,
            @Nullable Pvs1SourceQualification.SourceBoundFullRna source) {
        var current=resolveRna(context,candidate);var audit=current.snapshot();
        audit.put("associationKind","SOURCE_BOUND_FULL_RNA_LENGTH_CHANGE").put("contextId",context.path("contextId").asText());
        audit.set("queryAllele",context.at("/variant/evidenceQueryAllele").deepCopy());
        var verifiedRna=current.verifiedRna();
        if(!current.ready() || verifiedRna==null)return new Material(audit,null);
        if(source==null) {audit.put("status","UNAVAILABLE").put("reasonCode","FULL_RNA_LENGTH_SOURCE_REQUIRED");return new Material(audit,null);}
        var originalReference=source.result().reference().material();
        if(!verifiedRna.contextSha256().equals(originalReference.contextSha256())
                || !verifiedRna.modelSha256().equals(originalReference.modelSha256())
                || !verifiedRna.referenceRnaSha256().equals(originalReference.referenceRnaSha256())
                || !verifiedRna.referenceIdentity().equals(originalReference.referenceIdentity())) {
            audit.put("status","UNAVAILABLE").put("reasonCode","FULL_RNA_LENGTH_CURRENT_REFERENCE_BINDING_MISMATCH");return new Material(audit,null);
        }
        var observation=mapper.createObjectNode();String issue=ProteinLengthChangeObservation.resolveFullRna(mapper,context,source,observation);
        audit.set("alleleAssociation",observation);
        if(issue!=null){audit.put("status","UNAVAILABLE").put("reasonCode",issue);return new Material(audit,null);}
        audit.put("status","READY").put("reasonCode","FULL_RNA_SOURCE_BOUND_LENGTH_FACTS_READY");
        return new Material(audit,verifiedRna.model(),verifiedRna);
    }

    public ObjectNode rnaIdentity() {
        return structureIdentity().put("method", "FULL_REFERENCE_RNA_MATERIAL_SUPPLY").put("version", RNA_VERSION);
    }

    public ObjectNode structureIdentity() {
        return identity().put("method", "EXACT_TRANSCRIPT_LOCAL_STRUCTURE_SUPPLY").put("version", STRUCTURE_VERSION)
                .put("alleleEffectVerified", false);
    }

    /** Technical inventory only: neither a MANE label nor coding annotation proves function. */
    public ObjectNode startLossInventory(ObjectNode context) throws IOException {
        var source = gencode;
        if (source == null) throw new IllegalArgumentException("GENCODE_INVENTORY_UNAVAILABLE");
        return source.inventory(context);
    }

    ReferenceSpan startLossReference(JsonNode context, String contig, int start, int length) throws IOException {
        require("GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                && contig.equals(context.at("/variant/evidenceQueryAllele/contig").asText())
                && context.at("/variant/normalization/referenceSequenceSha256").asText().matches("[a-f0-9]{64}")
                && start > 0 && length > 0 && length <= MAX_BASES && (long) start + length - 1 <= Integer.MAX_VALUE,
                "REFSEQ_REFERENCE_SCOPE_INVALID");
        var span = readReference(contig, start, length, mapper.createObjectNode());
        require(span.identity().path("expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")),
                "REFSEQ_REFERENCE_IDENTITY_MISMATCH");
        return span;
    }

    /** A splice witness can include intronic bases outside the verified transcript exons. */
    String spliceReferenceIssue(JsonNode context, JsonNode window, ObjectNode audit) {
        int start = window.path("start").asInt();
        String sequence = window.path("sequence").asText();
        if (!window.path("start").isIntegralNumber() || !window.path("start").canConvertToInt()
                || start < 1 || sequence.isEmpty() || sequence.length() > 100_000
                || (long) start + sequence.length() - 1 > Integer.MAX_VALUE)
            return "PVS1_SPLICE_REFERENCE_WITNESS_INVALID";
        var proof = audit.putObject("spliceReferenceWindow").put("status", "UNAVAILABLE")
                .put("start", start).put("length", sequence.length());
        try {
            String contig = context.at("/variant/evidenceQueryAllele/contig").asText();
            var span = readReference(contig, start, sequence.length(), proof);
            if (!span.identity().equals(audit.at("/structure/verification/referenceIdentity")))
                return "PVS1_SPLICE_CURRENT_REFERENCE_IDENTITY_MISMATCH";
            if (!sequence.equals(span.bases())) return "PVS1_SPLICE_CURRENT_REFERENCE_WINDOW_MISMATCH";
            proof.put("status", "READY").put("sequenceSha256", sha(sequence));
            return null;
        } catch (IOException | IllegalArgumentException unavailable) {
            return "PVS1_SPLICE_CURRENT_REFERENCE_WINDOW_UNAVAILABLE";
        }
    }

    private Material resolve(ObjectNode context, VariantInterpretation candidate, Purpose purpose) {
        boolean lengthChange = purpose == Purpose.PROTEIN_LENGTH_CHANGE;
        boolean stopLoss = purpose == Purpose.STOP_LOSS;
        boolean rna = purpose == Purpose.FULL_RNA, structure = purpose == Purpose.STRUCTURE || rna;
        var audit = (rna ? rnaIdentity() : stopLoss ? stopLossIdentity() : lengthChange ? proteinLengthChangeIdentity() : purpose == Purpose.STRUCTURE ? structureIdentity() : identity())
                .put("status", "UNAVAILABLE").put("referenceBasesRead", 0).put("modelCacheHit", false);
        String tx = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        audit.put("transcript", tx);
        try {
            require(!Thread.currentThread().isInterrupted(), "TRANSCRIPT_MATERIAL_INTERRUPTED");
            if (lengthChange || stopLoss) {
                try { adapter.validateCompleteContext(context); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("LENGTH_CHANGE_CONTEXT_INVALID"); }
                audit.put("contextId", context.path("contextId").asText());
                audit.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
            }
            require("GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                    && "NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()), "TRANSCRIPT_MATERIAL_NATIVE_GRCH38_REQUIRED");
            var entry = entries.get(tx);
            ObjectNode model;
            if (entry == null) {
                var source = gencode;
                require(source != null, "TRANSCRIPT_MATERIAL_EXACT_VERSION_NOT_AVAILABLE");
                audit.put("modelOrigin", "GENCODE_GTF_PLUS_REFERENCE").put("independentRnaVerification", false);
                model = java.util.Objects.requireNonNull(source).model(context, (c, s, n) -> readReference(c, s, n, audit));
            } else {
                audit.put("modelOrigin", "PREBUILT_LOCAL");
                Path file = java.util.Objects.requireNonNull(root).resolve(entry.path("file").asText()).toRealPath();
                require(file.getParent().equals(root), "TRANSCRIPT_MATERIAL_PATH_OUTSIDE_ROOT");
                require(Files.size(file) == entry.path("fileBytes").asLong(), "TRANSCRIPT_MATERIAL_BYTES_MISMATCH");
                model = read(file, entry.path("fileSha256").asText());
                require(entry.path("canonicalSha256").asText().equals(adapter.canonicalSha256(model))
                        && entry.path("modelId").equals(model.path("modelId")) && entry.path("modelVersion").equals(model.path("modelVersion")),
                        "TRANSCRIPT_MATERIAL_MODEL_IDENTITY_MISMATCH");
            }
            if (lengthChange && entry == null) {
                // BP3 requires a precise source locator. Derive it from the already pinned GENCODE row;
                // never rewrite the source files, upgrade their review, or change the legacy model path.
                audit.put("sourceModelCanonicalSha256", adapter.canonicalSha256(model));
                for (var source : model.path("sourceRecords")) {
                    require(source instanceof ObjectNode && "GENCODE_GTF_LOCAL_SNAPSHOT".equals(source.path("sourceKind").asText())
                            && ("GENCODE:" + tx).equals(source.path("sourceId").asText())
                            && source.path("annotationRowSha256").asText().matches("[0-9a-f]{64}")
                            && source.path("rawGtfSha256").asText().matches("[0-9a-f]{64}")
                            && source.path("indexManifestSha256").asText().matches("[0-9a-f]{64}"), "LENGTH_CHANGE_SOURCE_LOCATOR_UNAVAILABLE");
                    ((ObjectNode) source).put("locator", "transcript=" + tx
                            + ";annotationRowSha256=" + source.path("annotationRowSha256").asText()
                            + ";rawGtfSha256=" + source.path("rawGtfSha256").asText()
                            + ";indexManifestSha256=" + source.path("indexManifestSha256").asText());
                }
                audit.put("sourceLocatorMethod", "EXACT_GENCODE_ROW_LOCATOR_V1");
            }
            String modelSha = adapter.canonicalSha256(model);
            String issue = CodingTranscriptMapping.modelIssue(model, context);
            require(issue == null, issue);
            require(model.at("/sequenceVerification/fullSplicedTranscriptSha256").asText().matches("[0-9a-f]{64}")
                    && sha(model.path("cdsSequence").asText()).equals(model.at("/sequenceVerification/cdsSha256").asText()),
                    "TRANSCRIPT_MATERIAL_SEQUENCE_PROOF_MISSING");
            var vep = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            if (vep == null) throw new IllegalArgumentException(lengthChange || stopLoss
                    ? "LENGTH_CHANGE_SELECTED_TRANSCRIPT_NOT_UNIQUE" : "PREDICTION_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            var allele = context.at("/variant/evidenceQueryAllele");
            var candidateKey = candidate.getObservation() == null ? null : candidate.getObservation().getKey();
            require(candidateKey != null && candidateKey.getGenomeAssembly().equals(allele.path("assembly").asText())
                    && candidateKey.getChromosome().equals(allele.path("contig").asText())
                    && candidateKey.getPosition() == allele.path("position").asInt()
                    && candidateKey.getReference().equals(allele.path("reference").asText())
                    && candidateKey.getAlternate().equals(allele.path("alternate").asText()), "TRANSCRIPT_MATERIAL_CANDIDATE_ALLELE_MISMATCH");
            var association = mapper.createObjectNode();
            if (lengthChange || stopLoss) {
                require("116.0".equals(vep.getVersion()) && vep.getTimestamp() != null, "LENGTH_CHANGE_ANNOTATION_NOT_QUALIFIED");
                for (String alias : new String[]{vep.getTranscriptId(), vep.getTranscriptAccession()})
                    require(alias == null || alias.isBlank() || alias.equals(tx) || alias.equals(context.at("/transcript/accession").asText()),
                            "LENGTH_CHANGE_ANNOTATION_IDENTITY_MISMATCH");
                try {
                    if (stopLoss) issue = complexAlleles
                            ? ProteinStopLossObservation.prepareComplex(context, model, vep, association)
                            : ProteinStopLossObservation.prepare(context, model, vep, association);
                    else {
                        // Keep the established simple derivation/envelope for old supported alleles.
                        // A wider technical capability must not change the scope of an existing expert rule.
                        issue = ProteinLengthChangeObservation.resolve(context, model, vep, association);
                        if (complexAlleles && ("PM4_COMPLEX_ALLELE_UNSUPPORTED".equals(issue)
                                || "PM4_HGVSC_UNSUPPORTED_OR_INVALID".equals(issue))) {
                            association.removeAll();
                            issue = ProteinLengthChangeObservation.resolveComplex(context, model, vep, association);
                        }
                    }
                }
                catch (ArithmeticException overflow) { throw new IllegalArgumentException("LENGTH_CHANGE_COORDINATE_OUT_OF_RANGE"); }
            } else if (purpose == Purpose.MISSENSE) issue = CodingMissenseResolver.resolve(context, vep, model, association, adapter);
            require(issue == null, issue);
            if (lengthChange) {
                // A BP3-specific envelope boundary must not hide independently valid PM4 sequence facts.
                String envelopeIssue = "CODING_TRANSCRIPT_EDITS_S0".equals(association.path("editDerivation").asText())
                        ? Bp3EquivalentEditEnvelope.resolveComplex(model, association)
                        : Bp3EquivalentEditEnvelope.resolve(model, association);
                association.put("equivalentEditEnvelopeStatus", envelopeIssue == null ? "READY" : "UNAVAILABLE");
                if (envelopeIssue != null) association.put("equivalentEditEnvelopeReasonCode", envelopeIssue);
                ObjectNode storedAnnotation = mapper.valueToTree(vep);
                storedAnnotation.put("timestamp", java.util.Objects.toString(vep.getTimestamp(), null));
                association.put("storedAnnotationSha256", adapter.canonicalSha256(storedAnnotation));
                // The bounded model accessor is private technical material; the audit exposes hashes only.
                association.remove(java.util.List.of("referenceCds", "alteredCds", "referenceProtein", "alteredProtein"));
            }
            if (stopLoss && sequenceCapability != null) {
                try {
                    ObjectNode storedAnnotation = mapper.valueToTree(vep);
                    storedAnnotation.put("timestamp", java.util.Objects.toString(vep.getTimestamp(), null));
                    association.put("storedAnnotationSha256", adapter.canonicalSha256(storedAnnotation));
                } catch (RuntimeException publicAnnotationUnavailable) {
                    audit.put("publicAnnotationBindingReason", "PUBLIC_ANNOTATION_BINDING_UNAVAILABLE");
                }
            }
            long total = 0;
            for (var exon : model.path("exons")) total += (long) exon.path("genomicEnd").asInt() - exon.path("genomicStart").asInt() + 1;
            require(total > 0 && total <= MAX_BASES, "TRANSCRIPT_MATERIAL_BASE_LIMIT");
            // A fresh qualified read checks receipt applicability, current index/alias and file stamps even on cache hits.
            String contig = model.path("contig").asText();
            var witness = readReference(contig, model.path("exons").get(0).path("genomicStart").asInt(), 1, audit);
            require(witness.identity().path("expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")),
                    "TRANSCRIPT_MATERIAL_CONTEXT_REFERENCE_MISMATCH");
            if (structure || stopLoss) {
                String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
                require(ref.matches("[ACGT]{1,4096}") && alt.matches("[ACGT]{1,4096}") && !ref.equals(alt)
                        && allele.path("position").isIntegralNumber() && allele.path("position").canConvertToInt()
                        && allele.path("position").asInt() > 0
                        && (long) allele.path("position").asInt() + ref.length() - 1 <= Integer.MAX_VALUE,
                        "TRANSCRIPT_MATERIAL_ALLELE_OUTSIDE_SCOPE");
                var actual = readReference(contig, allele.path("position").asInt(), ref.length(), audit);
                require(witness.identity().equals(actual.identity()), "TRANSCRIPT_REFERENCE_IDENTITY_CHANGED");
                require(ref.equals(actual.bases()), "TRANSCRIPT_MATERIAL_ALLELE_REFERENCE_MISMATCH");
            }
            String key = modelSha + ":" + adapter.canonicalSha256(witness.identity());
            VerifiedTranscript verifiedTranscript = verified.get(key);
            if (verifiedTranscript == null) {
                var spliced = new StringBuilder();
                for (var exon : model.path("exons")) {
                    var genomic = new StringBuilder();
                    int end = exon.path("genomicEnd").asInt();
                    for (long start = exon.path("genomicStart").asInt(); start <= end;) {
                        int length = (int) Math.min(100_000L, end - start + 1);
                        var span = readReference(contig, (int) start, length, audit);
                        require(witness.identity().equals(span.identity()), "TRANSCRIPT_REFERENCE_IDENTITY_CHANGED");
                        genomic.append(span.bases()); start += length;
                    }
                    spliced.append(CodingTranscriptMapping.orientAllele(model, genomic.toString()));
                }
                long offset = 1L - model.path("exons").get(0).path("cdsStart").asInt();
                require(offset >= 0 && offset + model.path("cdsLength").asInt() <= spliced.length(), "TRANSCRIPT_MATERIAL_CDS_BOUNDS_INVALID");
                int cdsOffset = (int) offset;
                require(sha(spliced.toString()).equals(model.at("/sequenceVerification/fullSplicedTranscriptSha256").asText())
                        && spliced.substring(cdsOffset, cdsOffset + model.path("cdsLength").asInt()).equals(model.path("cdsSequence").asText()),
                        "TRANSCRIPT_MATERIAL_REFERENCE_SEQUENCE_MISMATCH");
                // Detect a reference change after the final exon, including on multi-exon transcripts.
                var after = readReference(contig, model.path("exons").get(0).path("genomicStart").asInt(), 1, audit);
                require(witness.identity().equals(after.identity()), "TRANSCRIPT_REFERENCE_IDENTITY_CHANGED");
                var proof = mapper.createObjectNode().put("fullSplicedTranscriptSha256", sha(spliced.toString()))
                        .put("cdsSha256", sha(model.path("cdsSequence").asText())).put("verifiedExonBases", total);
                proof.set("referenceIdentity", witness.identity().deepCopy());
                int tailStart = cdsOffset + model.path("cdsLength").asInt();
                int tailEnd = Math.min(spliced.length(), tailStart + ProteinStopLossObservation.MAXIMUM_TAIL_BASES);
                verifiedTranscript = new VerifiedTranscript(proof.deepCopy(), spliced.substring(tailStart, tailEnd), spliced.length() - tailStart, spliced.toString());
                if (verified.size() >= 128) verified.remove(verified.keySet().iterator().next());
                verified.put(key, verifiedTranscript);
            } else audit.put("modelCacheHit", true);
            if (stopLoss) {
                audit.put("modelCanonicalSha256", modelSha).set("verification", verifiedTranscript.audit().deepCopy());
                issue = ProteinStopLossObservation.finish(model, vep, verifiedTranscript.threePrimeWindow(), verifiedTranscript.threePrimeBases(), association);
                audit.set("alleleAssociation", association.deepCopy());
                require(issue == null, issue);
            }
            audit.put("status", "READY").put("reasonCode", lengthChange
                    ? "LENGTH_CHANGE_MODEL_REFERENCE_AND_VEP_AGREE" : stopLoss ? "STOP_LOSS_MODEL_REFERENCE_AND_VEP_AGREE" : purpose == Purpose.STRUCTURE
                    ? "EXACT_MODEL_REFERENCE_AND_SELECTED_ANNOTATION_BOUND" : "EXACT_MODEL_REFERENCE_AND_VEP_AGREE")
                    .put("modelCanonicalSha256", modelSha);
            audit.set("verification", verifiedTranscript.audit().deepCopy());
            if (!structure) audit.set("alleleAssociation", association);
            if ((lengthChange || stopLoss) && sequenceCapability != null) {
                try { audit.set("publicMaterialBinding", ProteinLengthPublicProof.materialBinding(mapper, model, context)); }
                catch (RuntimeException projectionUnavailable) {
                    // A public explanation gap must never change a verified scientific material/result.
                    audit.putObject("publicMaterialBinding").put("status", "UNAVAILABLE").put("reasonCode", "PUBLIC_MODEL_SOURCE_BINDING_UNAVAILABLE");
                }
            }
            if (rna) {
                var token = new VerifiedRna(mapper, adapter, model, context, verifiedTranscript.referenceRnaSequence(), witness.identity());
                audit.put("reasonCode", "FULL_REFERENCE_RNA_SEQUENCE_AND_EXONS_VERIFIED").set("rnaMaterial", token.snapshot());
                return new Material(audit, model, token);
            }
            return new Material(audit, model);
        } catch (IllegalArgumentException blocked) {
            audit.put("reasonCode", blocked.getMessage() == null ? "TRANSCRIPT_MATERIAL_INVALID" : blocked.getMessage());
        } catch (IOException unavailable) { audit.put("reasonCode", "TRANSCRIPT_MATERIAL_IO_UNAVAILABLE"); }
        return new Material(audit, null);
    }

    private ReferenceSpan readReference(String contig, int start, int length, ObjectNode audit) throws IOException {
        require(!Thread.currentThread().isInterrupted(), "TRANSCRIPT_MATERIAL_INTERRUPTED");
        var span = reference.read(contig, start, length);
        require(span != null && span.identity() != null && !span.identity().isEmpty() && span.bases() != null
                && span.bases().length() == length && span.bases().matches("[ACGT]+"), "TRANSCRIPT_REFERENCE_SPAN_INVALID");
        audit.put("referenceBasesRead", audit.path("referenceBasesRead").asInt() + length);
        return span;
    }
    private ObjectNode read(Path path, String pin) throws IOException {
        require(Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES, "TRANSCRIPT_MATERIAL_FILE_INVALID");
        byte[] bytes;
        try (var in = Files.newInputStream(path)) { bytes = in.readNBytes(MAX_BYTES + 1); }
        require(bytes.length <= MAX_BYTES && sha(bytes).equals(pin), "TRANSCRIPT_MATERIAL_FILE_IDENTITY_MISMATCH");
        JsonNode value = mapper.readTree(bytes);
        require(value instanceof ObjectNode, "TRANSCRIPT_MATERIAL_JSON_INVALID");
        return (ObjectNode) value;
    }
    private static void require(boolean allowed, @Nullable String reason) { if (!allowed) throw new IllegalArgumentException(reason); }
    static String sha(String value) { return sha(value.getBytes(StandardCharsets.US_ASCII)); }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
