package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

/** Real opaque supply issuance over an explicitly SYNTHETIC small virtual genome.
 * This is not actual VEP execution, a human reference receipt, RNA observation, or scientific approval.
 * Default oracle: CC + ATG AAA CCC GGG TTT GAA TAA + GGG TGA (29 RNA bases).
 * CDS occupies RNA 3..23, and full-reference exon ends are 8/17/25/29.
 * All RNA coordinates accepted here are one-based and inclusive.
 */
public final class CodingTranscriptRnaFixture {
    public static final String DEFAULT_RNA = "CCATGAAACCCGGGTTTGAATAAGGGTGA";
    public final ObjectMapper mapper;
    public final RuntimeEvidenceV2Adapter adapter;
    public final Path root;
    public ObjectNode context, model;
    public VariantInterpretation candidate;
    public CodingTranscriptMaterialSupply supply;
    public CodingTranscriptMaterialSupply.VerifiedRna token;
    public int referenceCalls, referenceBasesRead;
    private final Map<Integer, Character> genome = new HashMap<>();
    private final ObjectNode annotationTemplate;
    private String referenceRnaSequence, referenceSha, referenceContig;

    public CodingTranscriptRnaFixture(Path root, int strand) throws Exception {
        this(new ObjectMapper().findAndRegisterModules(), root, strand);
    }
    public CodingTranscriptRnaFixture(ObjectMapper mapper, Path root, int strand) throws Exception {
        if (strand != 1 && strand != -1) throw new IllegalArgumentException("Synthetic strand must be 1 or -1");
        this.mapper = mapper; this.adapter = new RuntimeEvidenceV2Adapter(mapper); this.root = root;
        Files.createDirectories(root);
        var legacy = new CodingTranscriptMaterialSupplyTest(); legacy.root = root; legacy.setup();
        context = legacy.input.fixture().context().deepCopy(); model = legacy.model.deepCopy(); model.put("strand", strand);
        annotationTemplate = mapper.valueToTree(legacy.input.fixture().candidate().getTranscriptAnnotations().get(0));
        configure(DEFAULT_RNA, 3, 23, 8, 17, 25, 29);
    }

    /** Derives a reference model and genome, then obtains a token through the ordinary supply API. */
    public CodingTranscriptRnaFixture configure(String rna, int cdsStartRna1, int cdsEndRna1, int... exonEndsRna1) throws Exception {
        if (rna == null || !rna.matches("[ACGT]+") || cdsStartRna1 < 1 || cdsEndRna1 < cdsStartRna1
                || cdsEndRna1 > rna.length() || exonEndsRna1.length == 0 || exonEndsRna1[exonEndsRna1.length - 1] != rna.length())
            throw new IllegalArgumentException("Invalid synthetic RNA/CDS/exon bounds");
        String cds = rna.substring(cdsStartRna1 - 1, cdsEndRna1);
        model.put("cdsSequence", cds).put("cdsLength", cds.length()).put("proteinLength", cds.length() / 3 - 1);
        var exons = model.putArray("exons"); int start = 1;
        for (int i = 0; i < exonEndsRna1.length; i++) {
            int end = exonEndsRna1[i];
            if (end < start) throw new IllegalArgumentException("Synthetic exon ends must increase");
            int genomicStart = model.path("strand").asInt() == 1 ? (i + 1) * 1000 : (exonEndsRna1.length - i) * 1000;
            exons.addObject().put("ordinal", i + 1).put("genomicStart", genomicStart).put("genomicEnd", genomicStart + end - start)
                    .put("cdsStart", start - cdsStartRna1 + 1).put("cdsEnd", end - cdsStartRna1 + 1);
            start = end + 1;
        }
        if (exonEndsRna1.length == 1) model.putNull("lastJunctionCdsPosition");
        else model.put("lastJunctionCdsPosition", exonEndsRna1[exonEndsRna1.length - 2] - cdsStartRna1 + 1);
        model.withObject("sequenceVerification").put("cdsSha256", CodingTranscriptMaterialSupply.sha(cds))
                .put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(rna));
        return configure(model, rna);
    }

    /** Custom reference model overload. It keeps the caller's declared sequence proofs unchanged. */
    public CodingTranscriptRnaFixture configure(ObjectNode referenceModel, String rna) throws Exception {
        if (rna == null || !rna.matches("[ACGT]+")) throw new IllegalArgumentException("Invalid synthetic RNA bases");
        model = referenceModel.deepCopy(); referenceRnaSequence = rna; genome.clear();
        int offset = 0;
        for (var exon : model.path("exons")) {
            int lo = exon.path("genomicStart").asInt(), hi = exon.path("genomicEnd").asInt();
            if (lo < 1 || hi < lo || (long) offset + hi - lo + 1 > rna.length()) throw new IllegalArgumentException("Invalid synthetic genomic exon coverage");
            for (int g = lo; g <= hi; g++) {
                int index = offset + (model.path("strand").asInt() == 1 ? g - lo : hi - g);
                char base = CodingTranscriptMapping.orientAllele(model, rna.substring(index, index + 1)).charAt(0);
                if (genome.put(g, base) != null) throw new IllegalArgumentException("Overlapping synthetic genomic exons");
            }
            offset += hi - lo + 1;
        }
        if (offset != rna.length()) throw new IllegalArgumentException("Incomplete synthetic RNA exon coverage");
        referenceContig = model.path("contig").asText();
        refreshReferenceIdentity();
        context.withObject("gene").put("hgncId", model.path("hgncId").asText()).put("symbol", model.path("geneSymbol").asText());
        String tx = model.path("transcript").asText(); int dot = tx.lastIndexOf('.');
        if (dot < 1) throw new IllegalArgumentException("Synthetic model requires exact transcript version");
        context.withObject("transcript").put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)));
        context.withObject("variant").withObject("normalization").put("referenceSequenceSha256", referenceSha);
        int cdsStart = 2 - model.path("exons").get(0).path("cdsStart").asInt();
        int position = Math.min(rna.length(), cdsStart + 4);
        String ref = rna.substring(position - 1, position);
        return alleleAtRna(position, ref, "A".equals(ref) ? "G" : "A");
    }

    /** Replaces a transcript-oriented reference interval by an alternate string (including VCF anchor). */
    public CodingTranscriptRnaFixture alleleAtRna(int positionRna1, String reference, String alternate) throws Exception {
        if (positionRna1 < 1 || reference == null || alternate == null || !reference.matches("[ACGT]+")
                || !alternate.matches("[ACGT]+") || reference.equals(alternate)
                || (long) positionRna1 - 1 + reference.length() > referenceRnaSequence.length()
                || !referenceRnaSequence.substring(positionRna1 - 1, positionRna1 - 1 + reference.length()).equals(reference))
            throw new IllegalArgumentException("Invalid synthetic transcript-oriented allele");
        int first = genomicPosition(positionRna1), last = genomicPosition(positionRna1 + reference.length() - 1);
        if (Math.abs(first - last) + 1 != reference.length()) throw new IllegalArgumentException("Use an intronic genomic allele explicitly for a junction-spanning RNA event");
        return alleleAtGenomic(Math.min(first, last), CodingTranscriptMapping.orientAllele(model, reference), CodingTranscriptMapping.orientAllele(model, alternate));
    }

    /** Genomic allele overload; all queried reference bases still must exist in this virtual genome. */
    public CodingTranscriptRnaFixture alleleAtGenomic(int position, String reference, String alternate) throws Exception {
        if (position < 1 || reference == null || alternate == null || !reference.matches("[ACGT]+")
                || !alternate.matches("[ACGT]+") || reference.equals(alternate)) throw new IllegalArgumentException("Invalid synthetic genomic allele");
        String assembly = model.path("assembly").asText(), contig = model.path("contig").asText();
        String variantType = reference.length() > alternate.length() ? "SMALL_DELETION" : reference.length() < alternate.length() ? "SMALL_INSERTION"
                : reference.length() == 1 ? "SNV" : "MNV";
        context.withObject("variant").put("variantType", variantType);
        context.withObject("variant").withObject("evidenceQueryAllele").put("assembly", assembly).put("contig", contig)
                .put("position", position).put("reference", reference).put("alternate", alternate);
        context.put("contextId", adapter.canonicalContextId(context));
        var key = new NormalizedVariantKey(assembly, contig, position, reference, alternate);
        var nativeIdentity = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key);
        context.set("variant", nativeIdentity.path("variant"));
        context.withObject("variant").put("variantType", variantType);
        context.withObject("variant").withObject("normalization").put("referenceSequenceSha256", referenceSha);
        context.put("contextId", adapter.canonicalContextId(context));
        var observation = new VariantQcObservation(key, "SYNTHETIC_RNA_FIXTURE", "0/1", null, null, null, null, "PASS", variantType, null);
        candidate = new VariantInterpretation("SYNTHETIC_RNA_FIXTURE", observation, List.of(), List.of());
        var annotation = annotationTemplate.deepCopy().put("transcriptAccession", model.path("transcript").asText())
                .put("transcriptId", model.path("transcript").asText()).put("source", "VEP_SYNTHETIC_MODEL_DERIVED_RNA_CONTROL");
        candidate.applyTranscriptAnnotations(List.of(mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .treeToValue(annotation, TranscriptConsequence.class)));
        return reload();
    }
    private int genomicPosition(int positionRna1) {
        int offset = 0;
        for (var exon : model.path("exons")) {
            int lo = exon.path("genomicStart").asInt(), hi = exon.path("genomicEnd").asInt(), length = hi - lo + 1;
            if (positionRna1 > offset && positionRna1 <= offset + length)
                return model.path("strand").asInt() == 1 ? lo + positionRna1 - offset - 1 : hi - positionRna1 + offset + 1;
            offset += length;
        }
        throw new IllegalArgumentException("Synthetic RNA coordinate is outside exons");
    }

    /** Explicit synthetic genomic coverage, for example intronic bases used by an observed allele. */
    public CodingTranscriptRnaFixture withGenomicReference(int start, String bases) throws Exception {
        if (start < 1 || bases == null || !bases.matches("[ACGT]+") || bases.length() > 1000
                || (long) start + bases.length() - 1 > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid synthetic genomic coverage");
        for (int i = 0; i < bases.length(); i++) {
            Character old = genome.get(start + i);
            if (old != null && old.charValue() != bases.charAt(i)) throw new IllegalArgumentException("Synthetic reference coverage conflicts");
        }
        for (int i = 0; i < bases.length(); i++) genome.put(start + i, bases.charAt(i));
        refreshReferenceIdentity();
        context.withObject("variant").withObject("normalization").put("referenceSequenceSha256", referenceSha);
        context.put("contextId", adapter.canonicalContextId(context));
        return reload();
    }
    private void refreshReferenceIdentity() {
        var identity = mapper.createObjectNode().put("source", "SYNTHETIC_VIRTUAL_GENOME_ONLY").put("contig", referenceContig);
        identity.set("genomicBases", mapper.valueToTree(new java.util.TreeMap<>(genome)));
        referenceSha = adapter.canonicalSha256(identity);
    }

    /** Re-pins only the current model/manifest, retaining the actual configured virtual genome. */
    public CodingTranscriptRnaFixture reload() throws Exception {
        var variant = context.withObject("variant");
        variant.put("alleleId", adapter.canonicalAlleleId(variant.path("normalizedAllele"), variant.path("normalization")));
        context.put("contextId", adapter.canonicalContextId(context));
        String tx = model.path("transcript").asText(); byte[] raw = mapper.writeValueAsBytes(model);
        Files.write(root.resolve(tx + ".model.json"), raw);
        var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        manifest.putArray("entries").addObject().put("transcript", tx).put("file", tx + ".model.json").put("fileBytes", raw.length)
                .put("fileSha256", CodingTranscriptMaterialSupply.sha(raw)).put("canonicalSha256", adapter.canonicalSha256(model))
                .put("modelId", model.path("modelId").asText()).put("modelVersion", model.path("modelVersion").asText());
        byte[] bytes = mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), bytes);
        supply = new CodingTranscriptMaterialSupply(mapper, root, CodingTranscriptMaterialSupply.sha(bytes), this::readReference);
        var material = supply.resolveRna(context, candidate);
        if (!material.ready() || material.verifiedRna() == null) throw new AssertionError(material.snapshot().toPrettyString());
        token = material.verifiedRna(); return this;
    }
    private CodingTranscriptMaterialSupply.ReferenceSpan readReference(String contig, int start, int length) throws IOException {
        referenceCalls++;
        if (!referenceContig.equals(contig)) throw new IOException("Wrong synthetic contig");
        var bases = new StringBuilder();
        for (int i = 0; i < length; i++) {
            Character base = genome.get(start + i);
            if (base == null) throw new IOException("Synthetic virtual reference coverage missing");
            bases.append(base.charValue());
        }
        referenceBasesRead += length;
        return new CodingTranscriptMaterialSupply.ReferenceSpan(bases.toString(), mapper.createObjectNode()
                .put("source", "SYNTHETIC_TRUSTED_REFERENCE_READER_NOT_HUMAN_REFERENCE")
                .put("expectedSha256", referenceSha).put("clinicalAcceptance", false));
    }
}
