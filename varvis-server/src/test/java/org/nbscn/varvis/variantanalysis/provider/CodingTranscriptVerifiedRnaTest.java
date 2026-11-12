package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic trusted-reader controls, not current human reference or scientific approval.
 * Independent oracle: RNA CCC ATG AAA TAA GGG, CDS RNA 4..12 = ATG AAA TAA.
 * Transcript exons span RNA 1..9 and 10..15, on either genomic strand.
 * A cache hit reads the fresh qualification witness and the allele, not all 15 exon bases.
 */
class CodingTranscriptVerifiedRnaTest {
    private static final String RNA = "CCCATGAAATAAGGG", REFERENCE_SHA = "f".repeat(64);
    @TempDir Path directory;
    private final CodingTranscriptMaterialSupplyTest legacy = new CodingTranscriptMaterialSupplyTest();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private ObjectNode context, model;
    private VariantInterpretation candidate;
    private String referenceVersion = "SYNTHETIC_REFERENCE_1";
    private boolean withdrawn, corrupt, coverageMissing;
    private int calls, bases;

    private CodingTranscriptMaterialSupply setup(int strand) throws Exception {
        legacy.root = directory; legacy.setup();
        context = legacy.input.fixture().context(); model = legacy.model;
        model.put("strand", strand);
        var exons = model.putArray("exons");
        int first = strand == 1 ? 93 : 200, second = strand == 1 ? 200 : 93;
        exons.addObject().put("ordinal", 1).put("genomicStart", first).put("genomicEnd", first + 8).put("cdsStart", -2).put("cdsEnd", 6);
        exons.addObject().put("ordinal", 2).put("genomicStart", second).put("genomicEnd", second + 5).put("cdsStart", 7).put("cdsEnd", 12);
        model.withObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(RNA));
        int position = strand == 1 ? 100 : 201;
        String reference = strand == 1 ? "A" : "T", alternate = strand == 1 ? "G" : "C";
        context.withObject("variant").withObject("evidenceQueryAllele").put("position", position).put("reference", reference).put("alternate", alternate);
        context.withObject("variant").withObject("normalization").put("referenceSequenceSha256", REFERENCE_SHA);
        context.put("contextId", adapter.canonicalContextId(context));
        var key = new NormalizedVariantKey("GRCh38", "1", position, reference, alternate);
        var observation = new VariantQcObservation(key, "SYNTHETIC_RNA_CONTROL", "0/1", null, null, null, null, "PASS", "SNV", null);
        candidate = new VariantInterpretation("SYNTHETIC_RNA_CONTROL", observation, List.of(), List.of());
        candidate.applyTranscriptAnnotations(legacy.input.fixture().candidate().getTranscriptAnnotations());
        return load();
    }
    private CodingTranscriptMaterialSupply load() throws Exception {
        String tx = model.path("transcript").asText();
        byte[] raw = mapper.writeValueAsBytes(model); Files.write(directory.resolve(tx + ".model.json"), raw);
        var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        manifest.putArray("entries").addObject().put("transcript", tx).put("file", tx + ".model.json")
                .put("fileBytes", raw.length).put("fileSha256", CodingTranscriptMaterialSupply.sha(raw))
                .put("canonicalSha256", adapter.canonicalSha256(model)).put("modelId", model.path("modelId").asText())
                .put("modelVersion", model.path("modelVersion").asText());
        byte[] bytes = mapper.writeValueAsBytes(manifest); Files.write(directory.resolve("manifest.json"), bytes);
        return new CodingTranscriptMaterialSupply(mapper, directory, CodingTranscriptMaterialSupply.sha(bytes), this::readReference);
    }
    private CodingTranscriptMaterialSupply.ReferenceSpan readReference(String contig, int start, int length) throws IOException {
        calls++;
        if (withdrawn) throw new IllegalArgumentException("SYNTHETIC_REFERENCE_QUALIFICATION_WITHDRAWN");
        var result = new StringBuilder();
        for (int g = start; g < start + length; g++) {
            int offset = 0; boolean found = false;
            for (var exon : model.path("exons")) {
                int lo = exon.path("genomicStart").asInt(), hi = exon.path("genomicEnd").asInt();
                if (g >= lo && g <= hi) {
                    if (coverageMissing && exon.path("ordinal").asInt() == 2) throw new IOException("Synthetic missing full exon coverage");
                    int index = offset + (model.path("strand").asInt() == 1 ? g - lo : hi - g);
                    String base = RNA.substring(index, index + 1);
                    if (corrupt && index == 0) base = "A";
                    result.append(CodingTranscriptMapping.orientAllele(model, base)); found = true; break;
                }
                offset += hi - lo + 1;
            }
            if (!found) throw new IOException("Outside synthetic reference exons");
        }
        bases += length;
        return new CodingTranscriptMaterialSupply.ReferenceSpan(result.toString(), mapper.createObjectNode()
                .put("version", referenceVersion).put("expectedSha256", REFERENCE_SHA));
    }
    private CodingTranscriptMaterialSupply.VerifiedRna token(CodingTranscriptMaterialSupply supply) {
        var material = supply.resolveRna(context, candidate);
        assertTrue(material.ready(), material.snapshot()::toPrettyString);
        assertNotNull(material.verifiedRna()); return material.verifiedRna();
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void suppliesExactFullRnaCdsBoundariesAndReferenceExonsOnBothStrands(int strand) throws Exception {
        var supply = setup(strand); var token = token(supply);
        assertEquals(RNA, token.referenceRnaSequence()); assertEquals(CodingTranscriptMaterialSupply.sha(RNA), token.referenceRnaSha256());
        assertEquals(4, token.cdsStartRna1()); assertEquals(12, token.cdsEndRna1());
        assertEquals("ATGAAATAA", RNA.substring(token.cdsStartRna1() - 1, token.cdsEndRna1()));
        assertEquals(1, token.exons().get(0).startRna1()); assertEquals(9, token.exons().get(0).endRna1());
        assertEquals(10, token.exons().get(1).startRna1()); assertEquals(15, token.exons().get(1).endRna1());
        assertEquals(1, token.exons().get(0).referenceOrdinal()); assertEquals(2, token.exons().get(1).referenceOrdinal());
        assertEquals(adapter.canonicalSha256(model), token.modelSha256()); assertEquals(adapter.canonicalSha256(context), token.contextSha256());
        assertEquals(REFERENCE_SHA, token.referenceIdentity().path("expectedSha256").asText());
        assertEquals(adapter.canonicalSha256(model.path("sourceRecords")), token.snapshot().at("/binding/modelSourcesSha256").asText());
        assertFalse(token.snapshot().path("scientificQualificationGranted").asBoolean(true));
        assertFalse(token.snapshot().path("phaseQualificationGranted").asBoolean(true));
    }
    @Test void cacheRechecksQualificationAndAlleleBeforeIssuingAnotherToken() throws Exception {
        var supply = setup(1); token(supply); int first = bases, before = calls;
        var hit = supply.resolveRna(context, candidate);
        assertTrue(hit.ready()); assertTrue(hit.snapshot().path("modelCacheHit").asBoolean());
        assertEquals(first + 2, bases); assertEquals(before + 2, calls);
        withdrawn = true;
        var blocked = supply.resolveRna(context, candidate);
        assertFalse(blocked.ready()); assertNull(blocked.verifiedRna());
        assertEquals("SYNTHETIC_REFERENCE_QUALIFICATION_WITHDRAWN", blocked.snapshot().path("reasonCode").asText());
    }
    @Test void changedReferenceIdentityRequiresFullExonVerificationAgain() throws Exception {
        var supply = setup(1); token(supply); int first = bases;
        referenceVersion = "SYNTHETIC_REFERENCE_2";
        var next = supply.resolveRna(context, candidate);
        assertTrue(next.ready()); assertFalse(next.snapshot().path("modelCacheHit").asBoolean());
        assertEquals(first + RNA.length() + 3, bases);
    }
    @ParameterizedTest @ValueSource(strings = {"reference", "gene", "transcript", "candidate", "coverage", "sequence", "cdsBounds"})
    void wrongReferenceContextCoverageAndSequenceCannotIssueTokens(String fault) throws Exception {
        var supply = setup(1);
        switch (fault) {
            case "reference" -> context.withObject("variant").withObject("normalization").put("referenceSequenceSha256", "e".repeat(64));
            case "gene" -> context.withObject("gene").put("hgncId", "HGNC:999");
            case "transcript" -> context.withObject("transcript").put("version", 999);
            case "candidate" -> context.withObject("variant").withObject("evidenceQueryAllele").put("alternate", "T");
            case "coverage" -> coverageMissing = true;
            case "sequence" -> corrupt = true;
            case "cdsBounds" -> { ((ObjectNode) model.at("/exons/0")).put("cdsStart", -1); supply = load(); }
            default -> throw new AssertionError(fault);
        }
        var result = supply.resolveRna(context, candidate);
        assertFalse(result.ready(), result.snapshot()::toPrettyString); assertNull(result.verifiedRna());
    }
    @Test void contextPinsAreFreshEvenWhenTranscriptSequenceIsCached() throws Exception {
        var supply = setup(1); var first = token(supply);
        context.withObject("disease").put("mondoId", "MONDO:9999999");
        context.put("contextId", adapter.canonicalContextId(context));
        var second = token(supply);
        assertNotEquals(first.contextSha256(), second.contextSha256()); assertEquals(first.referenceRnaSha256(), second.referenceRnaSha256());
    }
    @Test void snapshotsAreImmutableAndJsonCannotSelfIssueAnOpaqueToken() throws Exception {
        var supply = setup(1); var token = token(supply); var before = token.snapshot();
        token.model().removeAll(); token.referenceIdentity().removeAll(); token.snapshot().removeAll();
        assertEquals(before, token.snapshot()); assertEquals(model, token.model());
        assertThrows(UnsupportedOperationException.class, () -> token.exons().clear());
        assertEquals(0, CodingTranscriptMaterialSupply.VerifiedRna.class.getConstructors().length);
        for (var constructor : CodingTranscriptMaterialSupply.VerifiedRna.class.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> mapper.treeToValue(before, CodingTranscriptMaterialSupply.VerifiedRna.class));
        assertEquals(before, mapper.readTree(mapper.writeValueAsBytes(before)));
    }
    @Test void pvs1DelegatesTheSameTechnicalTokenAndLegacyStructureOutputStaysUnextended() throws Exception {
        var supply = setup(1); var pvs1 = new Pvs1TranscriptMaterialSupply(mapper, supply);
        var material = pvs1.resolveRna(context, candidate);
        assertTrue(material.ready()); assertEquals(RNA, java.util.Objects.requireNonNull(material.verifiedRna()).referenceRnaSequence());
        var old = supply.resolveStructure(context, candidate);
        assertTrue(old.ready()); assertNull(old.verifiedRna()); assertFalse(old.snapshot().has("rnaMaterial"));
        assertEquals(model, old.model()); assertEquals("1.0.0", pvs1.identity().path("version").asText());
    }
}
