package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic CDS edits, not scientific adoption. Callers still verify primary
 * reference bytes, event/phase sources and the applicable evidence rule. */
public final class CodingTranscriptEdits {
    public static final String VERSION = "1.0.0";
    public static final String RNA_VERSION = "1.0.0";
    public static final String RNA_COORDINATES = "REFERENCE_SPLICED_RNA_0_BASED_HALF_OPEN";
    private static final int MAX_CDS = 1_000_000, MAX_ALTERED = 2_000_000, MAX_EDITS = 1000;
    private CodingTranscriptEdits() { }
    public enum Origin { CODING_ALLELE, SPLICE_PROJECTION }
    public enum Bias { BEFORE, AFTER }

    public static final class Rejected extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String reasonCode;
        private final List<Edit> inputBindings;
        private final String bindingStatus;
        private Rejected(String code) { this(code, List.of(), "NOT_ASSESSED"); }
        private Rejected(String code, List<Edit> inputs, String bindingStatus) {
            super("CODING_EDIT_" + code); reasonCode = getMessage();
            inputBindings = List.copyOf(inputs); this.bindingStatus = bindingStatus;
        }
        public String reasonCode() { return reasonCode; }
        /** Original input bindings, never a deduplicated or partially applied result. */
        public List<Edit> inputBindings() { return inputBindings; }
        public String bindingStatus() { return bindingStatus; }
        public ObjectNode snapshot(ObjectMapper mapper) {
            var out = mapper.createObjectNode().put("schemaVersion", "coding-transcript-edit-rejection-v1")
                    .put("methodVersion", VERSION).put("reasonCode", reasonCode).put("bindingStatus", bindingStatus)
                    .put("partialResultAvailable", false).put("scientificQualificationGranted", false);
            out.set("inputBindings", mapper.valueToTree(inputBindings)); return out;
        }
    }

    public record Edit(String id, Origin origin, int referenceEditStart0, int referenceEditEnd0,
            String insertedSequence, String expectedReference, String evidenceBindingSha256,
            String alleleGroupSha256, int insertionExonOrdinal) {
        public Edit {
            require(id != null && !id.isBlank() && id.length() <= 300 && origin != null, "IDENTITY_INVALID");
            require(referenceEditStart0 >= 0 && referenceEditEnd0 >= referenceEditStart0, "RANGE_INVALID");
            if (insertedSequence == null || expectedReference == null) throw new Rejected("BASES_INVALID");
            require(insertedSequence.matches("[ACGT]*") && insertedSequence.length() <= MAX_CDS
                    && expectedReference.matches("[ACGT]*"), "BASES_INVALID");
            require(hash(evidenceBindingSha256) && alleleGroupSha256 != null
                    && (alleleGroupSha256.isEmpty() || hash(alleleGroupSha256)) && insertionExonOrdinal >= 0, "INPUT_BINDING_INVALID");
            require(referenceEditEnd0 - referenceEditStart0 == expectedReference.length(), "REFERENCE_LENGTH_MISMATCH");
            require(referenceEditStart0 != referenceEditEnd0 || !insertedSequence.isEmpty(), "EMPTY_EDIT");
        }
        private int delta() { return insertedSequence.length() - expectedReference.length(); }
    }

    /** This is a declared mechanical grouping, not evidence that variants are in cis. */
    public enum RnaGrouping { DECLARED_SINGLE_PHYSICAL_ALLELE, UNKNOWN, TRANS }

    /** All operations use reference RNA coordinates. A complete product is a witness only, never edited again. */
    public record RnaEditSet(String coordinateSystem, String referenceRnaSha256, RnaGrouping grouping,
                             List<Edit> edits, String completeAllelicRnaWitness) {
        public RnaEditSet {
            require(RNA_COORDINATES.equals(coordinateSystem) && hash(referenceRnaSha256), "RNA_COORDINATE_SCOPE_REQUIRED");
            if (grouping == null || edits == null || edits.size() > MAX_EDITS) throw new Rejected("RNA_EVENT_SET_INVALID");
            require(edits.stream().allMatch(Objects::nonNull), "NULL_EDIT");
            edits = List.copyOf(edits);
            require(completeAllelicRnaWitness != null && completeAllelicRnaWitness.matches("[ACGT]*")
                    && completeAllelicRnaWitness.length() <= MAX_ALTERED, "RNA_PRODUCT_WITNESS_INVALID");
        }
        public RnaEditSet(String coordinates, String hash, RnaGrouping grouping, List<Edit> edits) {
            this(coordinates, hash, grouping, edits, "");
        }
    }

    public static final class RnaReference {
        private final CodingTranscriptMaterialSupply.VerifiedRna material;
        private final ObjectNode context;
        private final int[] owners;
        private RnaReference(CodingTranscriptMaterialSupply.VerifiedRna material, ObjectNode context) {
            this.material = material; this.context = context.deepCopy();
            owners = new int[material.referenceRnaSequence().length()];
            for (var exon : material.exons()) Arrays.fill(owners, exon.startRna1() - 1, exon.endRna1(), exon.referenceOrdinal());
            for (int ordinal : owners) require(ordinal > 0, "RNA_REFERENCE_EXON_COVERAGE_INVALID");
        }
        public CodingTranscriptMaterialSupply.VerifiedRna material() { return material; }
        public String referenceRna() { return material.referenceRnaSequence(); }
        public int cdsStartRna0() { return material.cdsStartRna1() - 1; }
        public int cdsEndRna0() { return material.cdsEndRna1(); }
        public ObjectNode snapshot() { return material.snapshot(); }
    }

    public static RnaReference bindRna(ObjectMapper mapper, JsonNode context, CodingTranscriptMaterialSupply.VerifiedRna material) {
        if (mapper == null || !(context instanceof ObjectNode) || material == null) throw new Rejected("RNA_VERIFIED_MATERIAL_REQUIRED");
        require(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(context).equals(material.contextSha256()), "RNA_CONTEXT_BINDING_MISMATCH");
        require(material.referenceRnaSequence().length() <= MAX_ALTERED
                && sha(material.referenceRnaSequence()).equals(material.referenceRnaSha256()), "RNA_REFERENCE_BINDING_MISMATCH");
        return new RnaReference(material, (ObjectNode) context);
    }

    public static RnaResult applyRna(RnaReference reference, RnaEditSet set) {
        if (reference == null || set == null) throw new Rejected("RNA_VERIFIED_MATERIAL_REQUIRED");
        require(reference.material.referenceRnaSha256().equals(set.referenceRnaSha256()), "RNA_REFERENCE_BINDING_MISMATCH");
        require(set.grouping() == RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE, "RNA_PHASE_SCOPE_UNRESOLVED_OR_TRANS");
        var mapper = new ObjectMapper();
        var allele = reference.context.at("/variant/evidenceQueryAllele");
        require(allele.isObject(), "RNA_SINGLE_ALLELE_CONTEXT_REQUIRED");
        String group = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(allele);
        for (var edit : set.edits()) if (!group.equals(edit.alleleGroupSha256()))
            throw new Rejected("RNA_ALLELE_GROUP_BINDING_MISMATCH", set.edits(), "GROUP_CONFLICT_WITH_CURRENT_ALLELE");
        var edited = applySequence(reference.referenceRna(), reference.owners, set.edits(), true);
        require(set.completeAllelicRnaWitness().isEmpty() || edited.sequence().equals(set.completeAllelicRnaWitness()), "RNA_COMPLETE_PRODUCT_WITNESS_MISMATCH");
        return new RnaResult(reference, edited, !set.completeAllelicRnaWitness().isEmpty());
    }

    /** Complete RNA sequence/mapping only. Segment boundaries and owner changes are never EJC proof. */
    public static final class RnaResult {
        private final RnaReference reference;
        private final AppliedSequence edited;
        private final int[] referenceToAltered;
        private final boolean completeProductWitness;
        private RnaResult(RnaReference reference, AppliedSequence edited, boolean completeProductWitness) {
            this.reference = reference; this.edited = edited; this.completeProductWitness = completeProductWitness;
            referenceToAltered = new int[reference.referenceRna().length() + 1];
            for (int p = 0; p < edited.referenceBases().length; p++) {
                int original = edited.referenceBases()[p];
                if (original > 0) { require(referenceToAltered[original] == 0, "RNA_REFERENCE_BASE_MAPPING_NOT_UNIQUE"); referenceToAltered[original] = p + 1; }
            }
        }
        public RnaReference reference() { return reference; }
        public String alteredRna() { return edited.sequence(); }
        public String alteredRnaSha256() { return sha(alteredRna()); }
        public int delta() { return alteredRna().length() - reference.referenceRna().length(); }
        public List<Edit> appliedEdits() { return List.copyOf(edited.applied()); }
        public int alteredToReferenceRnaBase(int position1) {
            return position1 < 1 || position1 > edited.referenceBases().length ? 0 : edited.referenceBases()[position1 - 1];
        }
        public int referenceRnaToAlteredBase(int position1) {
            if (position1 < 1 || position1 > reference.referenceRna().length()) return 0;
            return referenceToAltered[position1];
        }
        public int alteredReferenceExonOrdinal(int position1) {
            return position1 < 1 || position1 > edited.owners().length ? 0 : edited.owners()[position1 - 1];
        }
        public int mapReferenceRnaBoundary(int boundary0, Bias bias) {
            require(boundary0 >= 0 && boundary0 <= reference.referenceRna().length(), "RNA_BOUNDARY_OUTSIDE_VERIFIED_REFERENCE");
            return mappedBoundary(edited.applied(), boundary0, bias);
        }
        public ObjectNode snapshot(ObjectMapper mapper) {
            var out = mapper.createObjectNode().put("schemaVersion", "full-reference-rna-edit-facts-v1").put("methodVersion", RNA_VERSION)
                    .put("coordinateSystem", RNA_COORDINATES).put("sequenceScope", "FULL_SPLICED_REFERENCE_RNA")
                    .put("alteredRnaSha256", alteredRnaSha256()).put("alteredRnaLength", alteredRna().length())
                    .put("netRnaLengthChange", delta()).put("completeAllelicRnaWitnessVerified", completeProductWitness)
                    .put("inputSequenceSemantics", "REFERENCE_RNA_EDITS_WITH_OPTIONAL_COMPLETE_PRODUCT_WITNESS")
                    .put("scientificQualificationGranted", false).put("phaseQualificationGranted", false)
                    .put("topologyStatus", "NOT_DERIVED_FROM_SEQUENCE_EDITS").putNull("lastJunctionRnaPosition");
            out.set("reference", reference.snapshot());
            var spans = out.putArray("originSpans");
            int i = 0;
            while (i < edited.referenceBases().length) {
                int first = edited.referenceBases()[i], owner = edited.owners()[i], end = i + 1;
                while (end < edited.referenceBases().length && edited.owners()[end] == owner
                        && (first == 0 ? edited.referenceBases()[end] == 0 : edited.referenceBases()[end] == first + end - i)) end++;
                var row = spans.addObject().put("alteredStart0", i).put("alteredEnd0", end);
                if (first == 0) row.putNull("referenceStart0"); else row.put("referenceStart0", first - 1);
                if (owner == 0) row.putNull("referenceExonOrdinal"); else row.put("referenceExonOrdinal", owner);
                i = end;
            }
            var rows = out.putArray("edits");
            edited.outcomes().stream().sorted(Comparator.comparing(o -> o.edit().id())).forEach(o -> {
                var row = rows.addObject(); row.set("input", mapper.valueToTree(o.edit())); row.put("status", o.status());
            });
            return out;
        }
    }

    /** A pinned model/context snapshot. Pin consistency does not grant clinical or RNA qualification. */
    public static final class Reference {
        private final ObjectNode model, context;
        private final String modelSha, cdsSha, contextSha, cds;
        private final int[] owner;
        private Reference(ObjectNode model, ObjectNode context, String modelSha, String cdsSha, String contextSha) {
            this.model = model.deepCopy(); this.context = context.deepCopy();
            this.modelSha = modelSha; this.cdsSha = cdsSha; this.contextSha = contextSha; cds = model.path("cdsSequence").asText();
            owner = new int[cds.length()];
            for (var exon : model.path("exons")) {
                int lo = Math.max(0, exon.path("cdsStart").asInt() - 1), hi = Math.min(cds.length(), exon.path("cdsEnd").asInt());
                if (lo < hi) Arrays.fill(owner, lo, hi, exon.path("ordinal").asInt());
            }
            for (int ordinal : owner) require(ordinal > 0, "REFERENCE_EXON_COVERAGE_INVALID");
        }
        public ObjectNode model() { return model.deepCopy(); }
        public String cds() { return cds; }
        public String modelSha256() { return modelSha; }
        public String cdsSha256() { return cdsSha; }
        public String contextSha256() { return contextSha; }
        private ObjectNode snapshot(ObjectMapper mapper) {
            return mapper.createObjectNode().put("assembly", model.path("assembly").asText()).put("contig", model.path("contig").asText())
                    .put("transcript", model.path("transcript").asText()).put("modelSha256", modelSha)
                    .put("referenceCdsSha256", cdsSha).put("contextSha256", contextSha);
        }
    }

    public static Reference bind(ObjectMapper mapper, JsonNode context, JsonNode model,
            String expectedModelSha256, String expectedCdsSha256) {
        require(context instanceof ObjectNode && model instanceof ObjectNode, "REFERENCE_IDENTITY_INVALID");
        String issue = CodingTranscriptMapping.modelIssue(model, context);
        require(issue == null, issue == null ? "MODEL_INVALID" : issue);
        String cds = model.path("cdsSequence").asText();
        require(cds.length() <= MAX_CDS, "REFERENCE_LIMIT");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        require(hash(expectedModelSha256) && expectedModelSha256.equals(adapter.canonicalSha256(model)), "MODEL_BINDING_MISMATCH");
        require(hash(expectedCdsSha256) && expectedCdsSha256.equals(sha(cds)), "CDS_BINDING_MISMATCH");
        return new Reference((ObjectNode) model, (ObjectNode) context, expectedModelSha256, expectedCdsSha256,
                adapter.canonicalSha256(context));
    }

    public record GenomicBlock(int genomicStart, int genomicEnd, int referenceCdsStart0,
                               int referenceCdsEnd0, int referenceExonOrdinal) { }
    public record IntervalProjection(List<GenomicBlock> blocks, int unmappedBases, boolean crossesExons) {
        public IntervalProjection { blocks = List.copyOf(blocks); }
    }

    /** Mapping facts only; an intronic/untranslated gap is explicitly counted. */
    public static IntervalProjection genomicInterval(Reference reference, int start, int end) {
        require(start > 0 && end >= start && (long) end - start + 1 <= MAX_CDS, "GENOMIC_RANGE_INVALID");
        var blocks = new ArrayList<GenomicBlock>(); int mapped = 0;
        for (var exon : reference.model.path("exons")) {
            int lo = Math.max(start, exon.path("genomicStart").asInt()), hi = Math.min(end, exon.path("genomicEnd").asInt());
            if (lo > hi) continue;
            // Clip untranslated exon flanks using the model's CDS-relative offsets.
            int strand = reference.model.path("strand").asInt();
            int codingLo = strand == 1 ? exon.path("genomicStart").asInt() + Math.max(0, 1 - exon.path("cdsStart").asInt())
                    : exon.path("genomicStart").asInt() + Math.max(0, exon.path("cdsEnd").asInt() - reference.cds.length());
            int codingHi = strand == 1 ? exon.path("genomicEnd").asInt() - Math.max(0, exon.path("cdsEnd").asInt() - reference.cds.length())
                    : exon.path("genomicEnd").asInt() - Math.max(0, 1 - exon.path("cdsStart").asInt());
            lo = Math.max(lo, codingLo); hi = Math.min(hi, codingHi); if (lo > hi) continue;
            int a = CodingTranscriptMapping.cdsPosition(reference.model, lo), b = CodingTranscriptMapping.cdsPosition(reference.model, hi);
            require(a > 0 && b > 0, "GENOMIC_MAPPING_INVALID");
            mapped += hi - lo + 1;
            blocks.add(new GenomicBlock(lo, hi, Math.min(a, b) - 1, Math.max(a, b), exon.path("ordinal").asInt()));
        }
        blocks.sort(Comparator.comparingInt((GenomicBlock b) -> Objects.requireNonNull(b).referenceCdsStart0()));
        return new IntervalProjection(blocks, end - start + 1 - mapped, blocks.size() > 1);
    }

    /** One physical short VCF allele. Unphased SNV lists are deliberately not an input form. */
    public static Edit projectAllele(Reference reference, JsonNode allele, String inputSha256) {
        if (allele == null || !allele.isObject() || !hash(inputSha256)) throw new Rejected("ALLELE_INPUT_INVALID");
        require(reference.model.path("assembly").equals(allele.path("assembly"))
                && reference.model.path("contig").equals(allele.path("contig")), "ALLELE_IDENTITY_MISMATCH");
        if (reference.context.at("/variant/evidenceQueryAllele").isObject())
            require(reference.context.at("/variant/evidenceQueryAllele").equals(allele), "ALLELE_CONTEXT_MISMATCH");
        require(allele.path("position").isIntegralNumber() && allele.path("position").canConvertToInt(), "ALLELE_POSITION_INVALID");
        int position = allele.path("position").asInt(); String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        require(position > 0 && ref.matches("[ACGT]+") && alt.matches("[ACGT]+") && !ref.equals(alt)
                && ref.length() <= 50 && alt.length() <= 50 && (long) position + ref.length() - 1 <= Integer.MAX_VALUE, "SHORT_ALLELE_REQUIRED");
        var projection = genomicInterval(reference, position, position + ref.length() - 1);
        if (projection.unmappedBases() > 0 && CodingTranscriptMapping.exonForGenomicInterval(
                reference.model, position, position + ref.length() - 1) != null)
            throw new Rejected("ALLELE_EXTENDS_OUTSIDE_CDS_UNSUPPORTED");
        require(projection.unmappedBases() == 0 && !projection.crossesExons() && projection.blocks().size() == 1,
                "GENOMIC_BOUNDARY_REQUIRES_SPLICE_RESULT");
        var block = projection.blocks().get(0);
        require(reference.cds.substring(block.referenceCdsStart0(), block.referenceCdsEnd0())
                .equals(CodingTranscriptMapping.orientAllele(reference.model, ref)), "ALLELE_REFERENCE_MISMATCH");
        var change = alleleDelta(ref, alt);
        int prefix = change.prefix(), suffix = change.suffix();
        String deleted = change.deleted(), inserted = change.inserted();
        int start, end; int owner = block.referenceExonOrdinal();
        if (deleted.isEmpty()) {
            require((long) position + prefix > 1 && (long) position + prefix <= Integer.MAX_VALUE, "INSERTION_FLANKS_REQUIRE_SOURCE");
            int left = CodingTranscriptMapping.cdsPosition(reference.model, position + prefix - 1);
            int right = CodingTranscriptMapping.cdsPosition(reference.model, position + prefix);
            require(left > 0 && right > 0 && Math.abs(left - right) == 1
                    && reference.owner[left - 1] == reference.owner[right - 1], "INSERTION_FLANKS_REQUIRE_SOURCE");
            start = Math.min(left, right); end = start;
        } else {
            int a = CodingTranscriptMapping.cdsPosition(reference.model, position + prefix);
            int b = CodingTranscriptMapping.cdsPosition(reference.model, position + ref.length() - suffix - 1);
            require(a > 0 && b > 0, "ALLELE_CDS_MAPPING_INVALID"); start = Math.min(a, b) - 1; end = Math.max(a, b);
        }
        return new Edit("allele:" + inputSha256, Origin.CODING_ALLELE, start, end,
                inserted.isEmpty() ? "" : CodingTranscriptMapping.orientAllele(reference.model, inserted),
                deleted.isEmpty() ? "" : CodingTranscriptMapping.orientAllele(reference.model, deleted), inputSha256, inputSha256, owner);
    }

    /** Projects one physical genomic allele onto the verified complete RNA, including UTR exons. */
    public static Edit projectRnaAllele(RnaReference reference, JsonNode allele, String inputSha256) {
        if (reference == null || allele == null || !allele.isObject() || !hash(inputSha256)) throw new Rejected("RNA_ALLELE_INPUT_INVALID");
        var model = reference.material.model();
        require(model.path("assembly").equals(allele.path("assembly")) && model.path("contig").equals(allele.path("contig"))
                && reference.context.at("/variant/evidenceQueryAllele").equals(allele), "RNA_ALLELE_IDENTITY_MISMATCH");
        require(new RuntimeEvidenceV2Adapter(new ObjectMapper()).canonicalSha256(allele).equals(inputSha256), "RNA_ALLELE_GROUP_BINDING_MISMATCH");
        require(allele.path("position").isIntegralNumber() && allele.path("position").canConvertToInt(), "ALLELE_POSITION_INVALID");
        int position = allele.path("position").asInt();
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        require(position > 0 && ref.matches("[ACGT]{1,50}") && alt.matches("[ACGT]{1,50}") && !ref.equals(alt)
                && (long) position + ref.length() - 1 <= Integer.MAX_VALUE, "SHORT_ALLELE_REQUIRED");
        int first = referenceRnaPositionForGenomicBase(reference, position);
        int last = referenceRnaPositionForGenomicBase(reference, position + ref.length() - 1);
        require(first > 0 && last > 0 && Math.abs(first - last) + 1 == ref.length()
                && reference.owners[first - 1] == reference.owners[last - 1], "RNA_GENOMIC_BOUNDARY_REQUIRES_SOURCE_OUTCOME");
        require(reference.referenceRna().substring(Math.min(first, last) - 1, Math.max(first, last))
                .equals(CodingTranscriptMapping.orientAllele(model, ref)), "ALLELE_REFERENCE_MISMATCH");
        var change = alleleDelta(ref, alt);int lo, hi;
        if (change.deleted().isEmpty()) {
            require((long) position + change.prefix() <= Integer.MAX_VALUE, "INSERTION_FLANKS_REQUIRE_SOURCE");
            int a = referenceRnaPositionForGenomicBase(reference, position + change.prefix() - 1);
            int b = referenceRnaPositionForGenomicBase(reference, position + change.prefix());
            require(a > 0 && b > 0 && Math.abs(a - b) == 1 && reference.owners[a - 1] == reference.owners[b - 1], "INSERTION_FLANKS_REQUIRE_SOURCE");
            lo = Math.min(a, b);hi = lo;
        } else {
            int a = referenceRnaPositionForGenomicBase(reference, position + change.prefix());
            int b = referenceRnaPositionForGenomicBase(reference, position + ref.length() - change.suffix() - 1);
            require(a > 0 && b > 0, "RNA_ALLELE_MAPPING_UNRESOLVED");lo = Math.min(a, b) - 1;hi = Math.max(a, b);
        }
        return new Edit("rna-allele:" + inputSha256, Origin.CODING_ALLELE, lo, hi,
                change.inserted().isEmpty() ? "" : CodingTranscriptMapping.orientAllele(model, change.inserted()),
                change.deleted().isEmpty() ? "" : CodingTranscriptMapping.orientAllele(model, change.deleted()),
                inputSha256, inputSha256, reference.owners[first - 1]);
    }

    public static int referenceRnaPositionForGenomicBase(RnaReference reference, int position1) {
        if (reference == null || position1 <= 0) throw new Rejected("RNA_GENOMIC_POSITION_INVALID");
        int strand = reference.material.model().path("strand").asInt();
        for (var exon : reference.material.exons()) if (position1 >= exon.genomicStart() && position1 <= exon.genomicEnd())
            return exon.startRna1() + (strand == 1 ? position1 - exon.genomicStart() : exon.genomicEnd() - position1);
        return 0;
    }

    private record AlleleDelta(int prefix, int suffix, String deleted, String inserted) { }
    private static AlleleDelta alleleDelta(String ref, String alt) {
        int prefix = 0, suffix = 0;
        while (prefix < ref.length() && prefix < alt.length() && ref.charAt(prefix) == alt.charAt(prefix)) prefix++;
        while (suffix < ref.length() - prefix && suffix < alt.length() - prefix
                && ref.charAt(ref.length() - 1 - suffix) == alt.charAt(alt.length() - 1 - suffix)) suffix++;
        return new AlleleDelta(prefix, suffix, ref.substring(prefix, ref.length() - suffix), alt.substring(prefix, alt.length() - suffix));
    }

    public static Edit codingHgvsEdit(Reference reference, String hgvs, String inputSha256) {
        if (hgvs == null || hgvs.length() >= 10_000 || !hash(inputSha256)) throw new Rejected("HGVS_INPUT_INVALID");
        int colon = hgvs.indexOf(":c."); require(colon > 0, "HGVS_SCOPE_UNSUPPORTED");
        String transcript = hgvs.substring(0, colon), expression = hgvs.substring(colon + 3);
        require(reference.model.path("transcript").asText().equals(transcript)
                || PublishedCriterionKnowledge.exactBridge(transcript, reference.model), "HGVS_TRANSCRIPT_MISMATCH");
        var snv = Pattern.compile("([1-9][0-9]*)([ACGT])>([ACGT])").matcher(expression);
        if (snv.matches()) {
            int start = number(snv.group(1), reference.cds.length()) - 1;
            return hgvs(reference, inputSha256, start, start + 1, snv.group(3), snv.group(2));
        }
        var change = Pattern.compile("([1-9][0-9]*)(?:_([1-9][0-9]*))?(delins|del|dup|ins)([ACGT]*)").matcher(expression);
        require(change.matches(), "HGVS_SCOPE_UNSUPPORTED");
        int first = number(change.group(1), reference.cds.length()), last = change.group(2) == null ? first : number(change.group(2), reference.cds.length());
        require(last >= first, "HGVS_RANGE_INVALID"); String op = change.group(3), bases = change.group(4), deleted = reference.cds.substring(first - 1, last);
        if (op.equals("ins")) {
            require(last == first + 1 && !bases.isEmpty(), "HGVS_INSERTION_INVALID");
            return hgvs(reference, inputSha256, first, first, bases, "");
        }
        if (op.equals("delins")) { require(!bases.isEmpty(), "HGVS_DELINS_INVALID"); return hgvs(reference, inputSha256, first - 1, last, bases, deleted); }
        require(bases.isEmpty() || bases.equals(deleted), "HGVS_REFERENCE_MISMATCH");
        return op.equals("dup") ? hgvs(reference, inputSha256, last, last, deleted, "")
                : hgvs(reference, inputSha256, first - 1, last, "", deleted);
    }

    private static Edit hgvs(Reference reference, String binding, int start, int end, String insert, String expected) {
        require(start >= 0 && end <= reference.cds.length() && reference.cds.substring(start, end).equals(expected), "HGVS_REFERENCE_MISMATCH");
        require(!expected.equals(insert), "HGVS_NO_CHANGE");
        int owner = owner(reference, start, end, insert, 0);
        return new Edit("hgvs:" + binding, Origin.CODING_ALLELE, start, end, insert, expected, binding, binding, owner);
    }
    public static boolean sequenceEquivalent(Reference reference, Edit first, Edit second) {
        return apply(reference, List.of(first)).alteredCds().equals(apply(reference, List.of(second)).alteredCds());
    }

    public record ExonSpan(int ordinal, int referenceOrdinal, int referenceCdsStart0, int referenceCdsEnd0,
                           int alteredCdsStart0, int alteredCdsEnd0) { }
    private record Key(Origin origin, int start, int end, String inserted, int owner) { }
    private record Outcome(Edit edit, String status) { }

    public static Result apply(Reference reference, List<Edit> supplied) {
        if (reference == null) throw new Rejected("EDIT_LIMIT_OR_REFERENCE_INVALID");
        var edited = applySequence(reference.cds, reference.owner, supplied, false);
        return new Result(reference, edited.sequence(), edited.referenceBases(), edited.owners(), edited.applied(), edited.outcomes());
    }

    private record AppliedSequence(String sequence, int[] referenceBases, int[] owners, List<Edit> applied, List<Outcome> outcomes) { }

    /** One shared edit kernel. Full RNA is an explicit scope, never a model with RNA substituted for CDS. */
    private static AppliedSequence applySequence(String original, int[] referenceOwners, List<Edit> supplied, boolean fullRna) {
        if (supplied == null || supplied.size() > MAX_EDITS) throw new Rejected("EDIT_LIMIT_OR_REFERENCE_INVALID");
        var unique = new LinkedHashMap<Key, Edit>(); var identities = new HashSet<String>(); var outcomes = new ArrayList<Outcome>();
        var sorted = new ArrayList<>(supplied); require(sorted.stream().allMatch(Objects::nonNull), "NULL_EDIT");
        sorted.sort(Comparator.comparingInt((Edit e) -> Objects.requireNonNull(e).referenceEditStart0()).thenComparingInt(e -> e.referenceEditEnd0())
                .thenComparing(e -> e.origin().name()).thenComparing(e -> e.insertedSequence()).thenComparing(e -> e.id()));
        for (var edit : sorted) {
            require(identities.add(edit.id()), "DUPLICATE_EDIT_ID");
            require(edit.referenceEditEnd0() <= original.length()
                    && original.substring(edit.referenceEditStart0(), edit.referenceEditEnd0()).equals(edit.expectedReference()), "EDIT_REFERENCE_MISMATCH");
            require(fullRna || edit.origin() != Origin.SPLICE_PROJECTION || edit.insertedSequence().isEmpty(), "SPLICE_INSERTION_MAPPING_UNSUPPORTED");
            require(!fullRna || edit.origin() != Origin.SPLICE_PROJECTION || edit.insertionExonOrdinal() == 0, "RNA_INSERTION_OWNER_REQUIRES_TOPOLOGY_SOURCE");
        }
        // All original aliases must agree before any sequence-level reduction.
        // Otherwise lexicographic alias selection can hide a different/missing group.
        if (sorted.size() > 1) {
            Set<String> groups = new HashSet<>(); boolean missing = false;
            for (var edit : sorted) {
                if (edit.alleleGroupSha256().isEmpty()) missing = true;
                else groups.add(edit.alleleGroupSha256());
            }
            if (missing || groups.size() != 1) throw new Rejected("SAME_ALLELE_BINDING_REQUIRED", supplied,
                    missing ? groups.size() > 1 ? "GROUP_CONFLICT_AND_MISSING" : "GROUP_MISSING" : "GROUP_CONFLICT");
        }
        for (var edit : sorted) {
            var key = new Key(edit.origin(), edit.referenceEditStart0(), edit.referenceEditEnd0(), edit.insertedSequence(), edit.insertionExonOrdinal());
            if (unique.putIfAbsent(key, edit) != null) outcomes.add(new Outcome(edit, "DUPLICATE_SEQUENCE_EDIT"));
        }
        var edits = new ArrayList<>(unique.values());
        var splices = edits.stream().filter(e -> e.origin() == Origin.SPLICE_PROJECTION).toList();
        var applied = new ArrayList<Edit>();
        for (var edit : edits) {
            boolean removed = false;
            if (edit.origin() == Origin.CODING_ALLELE) for (var splice : splices) {
                int a = edit.referenceEditStart0(), b = edit.referenceEditEnd0(), lo = splice.referenceEditStart0(), hi = splice.referenceEditEnd0();
                if (a == b && (a == lo || a == hi)) throw new Rejected("COMPOSITE_INSERTION_BOUNDARY_AMBIGUOUS");
                if (a == b ? a > lo && a < hi : a >= lo && b <= hi) removed = true;
                else if (Math.max(a, lo) < Math.min(b, hi)) throw new Rejected("COMPOSITE_EDIT_PARTIAL_OVERLAP");
            }
            outcomes.add(new Outcome(edit, removed ? "REMOVED_BY_SPLICE" : "APPLIED")); if (!removed) applied.add(edit);
        }
        for (int i = 0; i < applied.size(); i++) for (int j = i + 1; j < applied.size(); j++) {
            var a = applied.get(i); var b = applied.get(j);
            require(!overlap(a, b), "OVERLAPPING_EDITS");
        }
        long size = original.length(); for (var edit : applied) size += edit.delta();
        require(size >= 0 && size <= MAX_ALTERED, "ALTERED_SEQUENCE_LIMIT");
        var sequence = new StringBuilder((int) size); int[] referenceBases = new int[(int) size], owners = new int[(int) size];
        int cursor = 0;
        for (var edit : applied) {
            for (int p = cursor; p < edit.referenceEditStart0(); p++) { int index = sequence.length(); sequence.append(original.charAt(p)); referenceBases[index] = p + 1; owners[index] = referenceOwners[p]; }
            int ordinal = fullRna && edit.origin() == Origin.SPLICE_PROJECTION ? 0
                    : owner(original, referenceOwners, edit.referenceEditStart0(), edit.referenceEditEnd0(), edit.insertedSequence(), edit.insertionExonOrdinal());
            for (int p = 0; p < edit.insertedSequence().length(); p++) { int index = sequence.length(); sequence.append(edit.insertedSequence().charAt(p)); owners[index] = ordinal; }
            cursor = edit.referenceEditEnd0();
        }
        for (int p = cursor; p < original.length(); p++) { int index = sequence.length(); sequence.append(original.charAt(p)); referenceBases[index] = p + 1; owners[index] = referenceOwners[p]; }
        return new AppliedSequence(sequence.toString(), referenceBases, owners, applied, outcomes);
    }

    private static boolean overlap(Edit a, Edit b) {
        int lo = a.referenceEditStart0(), hi = a.referenceEditEnd0(), x = b.referenceEditStart0(), y = b.referenceEditEnd0();
        if (lo == hi) return x <= lo && lo <= y;
        if (x == y) return lo <= x && x <= hi;
        return Math.max(lo, x) < Math.min(hi, y);
    }
    private static int owner(Reference reference, int start, int end, String insert, int declared) {
        return owner(reference.cds, reference.owner, start, end, insert, declared);
    }
    private static int owner(String original, int[] referenceOwners, int start, int end, String insert, int declared) {
        if (insert.isEmpty()) return 0;
        require(start >= 0 && end <= original.length(), "EDIT_RANGE_INVALID");
        Set<Integer> candidates = new HashSet<>();
        if (start == end) { if (start > 0) candidates.add(referenceOwners[start - 1]); if (start < referenceOwners.length) candidates.add(referenceOwners[start]); }
        else for (int p = start; p < end; p++) candidates.add(referenceOwners[p]);
        if (declared > 0) { require(candidates.contains(declared), "INSERTION_EXON_ASSIGNMENT_INVALID"); return declared; }
        require(candidates.size() == 1, "INSERTION_EXON_ASSIGNMENT_REQUIRED"); return candidates.iterator().next();
    }

    public static final class Result {
        private final Reference reference;
        private final String altered;
        private final int[] referenceBases;
        private final List<Edit> applied;
        private final List<Outcome> outcomes;
        private final List<ExonSpan> exons;
        private final List<Integer> junctions;
        private Result(Reference reference, String altered, int[] referenceBases, int[] owners,
                       List<Edit> applied, List<Outcome> outcomes) {
            this.reference = reference; this.altered = altered; this.referenceBases = referenceBases;
            this.applied = List.copyOf(applied); this.outcomes = List.copyOf(outcomes);
            Set<Integer> cutPoints = new HashSet<>();
            for (var edit : applied) if (edit.origin() == Origin.SPLICE_PROJECTION) {
                int cut = mapReferenceBoundary(edit.referenceEditStart0(), Bias.BEFORE);
                if (cut > 0 && cut < altered.length()) cutPoints.add(cut);
            }
            var spans = new ArrayList<ExonSpan>(); var joins = new ArrayList<Integer>(); int start = 0;
            for (int i = 1; i <= owners.length; i++) if (i == owners.length || owners[i] != owners[start] || cutPoints.contains(i)) {
                if (i > start) {
                    int lo = Integer.MAX_VALUE, hi = -1;
                    for (int p = start; p < i; p++) if (referenceBases[p] > 0) { lo = Math.min(lo, referenceBases[p] - 1); hi = Math.max(hi, referenceBases[p]); }
                    spans.add(new ExonSpan(spans.size() + 1, owners[start], hi < 0 ? -1 : lo, hi, start, i));
                    if (i < owners.length) joins.add(i);
                }
                start = i;
            }
            exons = List.copyOf(spans); junctions = List.copyOf(joins);
        }
        public String alteredCds() { return altered; }
        public String alteredCdsSha256() { return sha(altered); }
        public int delta() { return altered.length() - reference.cds.length(); }
        public List<ExonSpan> exons() { return exons; }
        public List<Integer> junctions() { return junctions; }
        public int alteredToReferenceBase(int position1) { return position1 < 1 || position1 > referenceBases.length ? 0 : referenceBases[position1 - 1]; }
        public int mapReferenceBoundary(int boundary0, Bias bias) {
            return mappedBoundary(applied, boundary0, bias);
        }
        public ObjectNode snapshot(ObjectMapper mapper) {
            var out = mapper.createObjectNode().put("schemaVersion", "coding-transcript-edit-facts-v1").put("methodVersion", VERSION)
                    .put("scope", "CDS_SEQUENCE_FACTS_ONLY").put("scientificQualificationGranted", false)
                    .put("topologyScope", "CODING_EXONS_ONLY").put("alteredCdsSha256", alteredCdsSha256())
                    .put("referenceCdsLength", reference.cds.length()).put("alteredCdsLength", altered.length()).put("netCdsLengthChange", delta());
            out.set("binding", reference.snapshot(mapper));
            var maps = out.putArray("alteredToReference"); int start = 0;
            while (start < referenceBases.length) {
                int first = referenceBases[start], end = start + 1;
                while (end < referenceBases.length && (first == 0 ? referenceBases[end] == 0 : referenceBases[end] == first + end - start)) end++;
                var row = maps.addObject().put("alteredStart0", start).put("alteredEnd0", end);
                if (first == 0) row.putNull("referenceStart0"); else row.put("referenceStart0", first - 1);
                start = end;
            }
            var exonRows = out.putArray("codingExons"); for (var exon : exons) exonRows.add(mapper.valueToTree(exon));
            var joins = out.putArray("codingJunctions0"); junctions.forEach(joins::add);
            var rows = out.putArray("edits");
            outcomes.stream().sorted(Comparator.comparing(o -> o.edit().id())).forEach(o -> {
                var row = rows.addObject().put("id", o.edit().id()).put("origin", o.edit().origin().name()).put("status", o.status())
                        .put("referenceEditStart0", o.edit().referenceEditStart0()).put("referenceEditEnd0", o.edit().referenceEditEnd0())
                        .put("insertedSequence", o.edit().insertedSequence()).put("expectedReference", o.edit().expectedReference())
                        .put("evidenceBindingSha256", o.edit().evidenceBindingSha256()).put("alleleGroupSha256", o.edit().alleleGroupSha256());
                row.put("insertionExonOrdinal", o.edit().insertionExonOrdinal());
            });
            return out;
        }
    }

    private static int mappedBoundary(List<Edit> applied, int boundary0, Bias bias) {
        require(boundary0 >= 0 && bias != null, "BOUNDARY_INVALID"); int shift = 0;
        for (var edit : applied) {
            int start = edit.referenceEditStart0(), end = edit.referenceEditEnd0();
            if (boundary0 < start) return boundary((long) boundary0 + shift);
            if (start == end && boundary0 == start) return boundary((long) start + shift + (bias == Bias.AFTER ? edit.insertedSequence().length() : 0));
            if (boundary0 == start) return boundary((long) start + shift);
            if (boundary0 < end) return edit.insertedSequence().isEmpty() ? boundary((long) start + shift) : -1;
            shift += edit.delta();
        }
        return boundary((long) boundary0 + shift);
    }
    private static int boundary(long coordinate) {
        require(coordinate >= 0 && coordinate <= Integer.MAX_VALUE, "BOUNDARY_OVERFLOW"); return (int) coordinate;
    }

    private static int number(String value, int maximum) {
        try { long n = Long.parseLong(value); require(n > 0 && n <= maximum, "HGVS_RANGE_INVALID"); return (int) n; }
        catch (NumberFormatException invalid) { throw new Rejected("HGVS_COORDINATE_OVERFLOW"); }
    }
    private static void require(boolean condition, String code) { if (!condition) throw new Rejected(code); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
