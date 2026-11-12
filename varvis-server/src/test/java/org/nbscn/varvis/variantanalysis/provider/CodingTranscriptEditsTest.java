package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CodingTranscriptEditsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private static final String CDS = "ATGAAACCCGGGTTTTAA", BINDING = "b".repeat(64), GROUP = "c".repeat(64);

    ObjectNode model(int strand) {
        var model = mapper.createObjectNode().put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", "synthetic-edit-reference").put("modelVersion", "1").put("assembly", "GRCh38").put("contig", "1")
                .put("hgncId", "HGNC:1").put("geneSymbol", "SYNTHETIC").put("transcript", "NM_000001.1")
                .put("protein", "NP_000001.1").put("strand", strand).put("cdsSequence", CDS).put("cdsLength", CDS.length())
                .put("proteinLength", CDS.length() / 3 - 1);
        var exons = model.putArray("exons");
        exons.addObject().put("ordinal", 1).put("genomicStart", strand == 1 ? 100 : 200).put("genomicEnd", strand == 1 ? 108 : 208)
                .put("cdsStart", 1).put("cdsEnd", 9);
        exons.addObject().put("ordinal", 2).put("genomicStart", strand == 1 ? 200 : 100).put("genomicEnd", strand == 1 ? 208 : 108)
                .put("cdsStart", 10).put("cdsEnd", 18);
        model.putArray("sourceRecords").addObject().put("sourceId", "SYNTHETIC").put("version", "1")
                .put("url", "https://example.invalid/synthetic-only").put("sha256", "a".repeat(64));
        return model;
    }
    ObjectNode context() {
        var context = mapper.createObjectNode();
        context.putObject("gene").put("hgncId", "HGNC:1").put("symbol", "SYNTHETIC");
        context.putObject("transcript").put("accession", "NM_000001").put("version", 1);
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1");
        return context;
    }
    private CodingTranscriptEdits.Reference reference(int strand) {
        var model = model(strand); var context = context(); context.withObject("variant").remove("evidenceQueryAllele");
        context.withObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1");
        // This fixture deliberately binds only model identity. A complete allele is bound in projection tests below.
        return bind(model, context);
    }
    private CodingTranscriptEdits.Reference bind(ObjectNode model, ObjectNode context) {
        return CodingTranscriptEdits.bind(mapper, context, model, adapter.canonicalSha256(model),
                org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(model.path("cdsSequence").asText()));
    }
    private CodingTranscriptEdits.Edit edit(String id, CodingTranscriptEdits.Origin origin, int start, int end, String insert, int exon) {
        return new CodingTranscriptEdits.Edit(id, origin, start, end, insert, CDS.substring(start, end), BINDING, GROUP, exon);
    }
    private CodingTranscriptEdits.Edit coding(String id, int start, int end, String insert, int exon) {
        return edit(id, CodingTranscriptEdits.Origin.CODING_ALLELE, start, end, insert, exon);
    }
    private CodingTranscriptEdits.Edit splice(String id, int start, int end) {
        return edit(id, CodingTranscriptEdits.Origin.SPLICE_PROJECTION, start, end, "", 0);
    }
    private void rejection(String reason, Runnable operation) {
        assertEquals("CODING_EDIT_" + reason, assertThrows(CodingTranscriptEdits.Rejected.class, operation::run).reasonCode());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void handCalculatedReferenceVectorsAndInputPermutationsAreReplayed(int strand) throws Exception {
        JsonNode expectations;
        try (var stream = getClass().getResourceAsStream("/acmg/a5-edit-expectations-v1.json")) {
            assertNotNull(stream); expectations = mapper.readTree(stream);
        }
        assertEquals(CDS, expectations.path("referenceCds").asText());
        var reference = reference(strand);
        for (var vector : expectations.path("cases")) {
            var edits = new ArrayList<CodingTranscriptEdits.Edit>(); int id = 0;
            for (var e : vector.path("edits")) edits.add(edit(vector.path("id").asText() + ":" + id++,
                    CodingTranscriptEdits.Origin.valueOf(e.path(0).asText()), e.path(1).asInt(), e.path(2).asInt(), e.path(3).asText(), e.path(4).asInt()));
            var result = CodingTranscriptEdits.apply(reference, edits);
            assertEquals(vector.path("alteredCds").asText(), result.alteredCds(), vector::toPrettyString);
            assertEquals(vector.path("delta").asInt(), result.delta());
            assertEquals(vector.path("junctions"), mapper.valueToTree(result.junctions()));
            var snapshot = result.snapshot(mapper); assertFalse(snapshot.path("scientificQualificationGranted").asBoolean());
            if (vector.has("removedCodingEvents")) {
                int removed = 0; for (var row : snapshot.path("edits")) if ("REMOVED_BY_SPLICE".equals(row.path("status").asText())) removed++;
                assertEquals(vector.path("removedCodingEvents").asInt(), removed);
            }
            Collections.reverse(edits); assertEquals(snapshot, CodingTranscriptEdits.apply(reference, edits).snapshot(mapper));
        }
    }

    @Test void replacementAndInsertionBasesNeverAcquireReferenceIdentity() {
        var replacement = CodingTranscriptEdits.apply(reference(1), List.of(coding("replace", 3, 6, "CG", 1)));
        assertEquals(3, replacement.alteredToReferenceBase(3));
        assertEquals(0, replacement.alteredToReferenceBase(4)); assertEquals(0, replacement.alteredToReferenceBase(5));
        assertEquals(7, replacement.alteredToReferenceBase(6)); assertEquals(-1, replacement.mapReferenceBoundary(4, CodingTranscriptEdits.Bias.BEFORE));
        assertEquals(5, replacement.mapReferenceBoundary(6, CodingTranscriptEdits.Bias.BEFORE));
        var insertion = CodingTranscriptEdits.apply(reference(1), List.of(coding("insert", 6, 6, "GCT", 1)));
        assertEquals(6, insertion.mapReferenceBoundary(6, CodingTranscriptEdits.Bias.BEFORE));
        assertEquals(9, insertion.mapReferenceBoundary(6, CodingTranscriptEdits.Bias.AFTER));
        assertEquals(0, insertion.alteredToReferenceBase(8)); assertEquals(7, insertion.alteredToReferenceBase(10));
        assertEquals(24, insertion.mapReferenceBoundary(21, CodingTranscriptEdits.Bias.AFTER));
        rejection("BOUNDARY_OVERFLOW", () -> insertion.mapReferenceBoundary(Integer.MAX_VALUE, CodingTranscriptEdits.Bias.AFTER));
        assertEquals(0, insertion.alteredToReferenceBase(0)); assertEquals(0, insertion.alteredToReferenceBase(99));
        var deletion = CodingTranscriptEdits.apply(reference(1), List.of(coding("delete", 3, 6, "", 0)));
        assertEquals(3, deletion.mapReferenceBoundary(5, CodingTranscriptEdits.Bias.BEFORE));
    }

    @Test void fullCodingDeletionNoEditAndDuplicateAliasesHaveExplicitFacts() {
        var reference = reference(1);
        var unchanged = CodingTranscriptEdits.apply(reference, List.of());
        assertEquals(CDS, unchanged.alteredCds()); assertEquals(List.of(9), unchanged.junctions());
        var empty = CodingTranscriptEdits.apply(reference, List.of(coding("delete", 0, CDS.length(), "", 0)));
        assertEquals("", empty.alteredCds()); assertTrue(empty.exons().isEmpty()); assertTrue(empty.junctions().isEmpty());
        var first = coding("alias-a", 3, 6, "CG", 1); var second = coding("alias-b", 3, 6, "CG", 1);
        var duplicate = CodingTranscriptEdits.apply(reference, List.of(second, first));
        assertEquals("ATGCGCCCGGGTTTTAA", duplicate.alteredCds());
        assertEquals(2, duplicate.snapshot(mapper).path("edits").size());
        assertEquals(duplicate.snapshot(mapper), CodingTranscriptEdits.apply(reference, List.of(first, second)).snapshot(mapper));
        rejection("DUPLICATE_EDIT_ID", () -> CodingTranscriptEdits.apply(reference, List.of(first, first)));
    }

    @Test void unphasedEventsAndOverlappingOrPartiallyRemovedEditsAreRejected() {
        var reference = reference(1);
        var unbound = new CodingTranscriptEdits.Edit("unbound", CodingTranscriptEdits.Origin.CODING_ALLELE, 12, 13, "C", "T", BINDING, "", 2);
        rejection("SAME_ALLELE_BINDING_REQUIRED", () -> CodingTranscriptEdits.apply(reference, List.of(coding("one", 3, 4, "C", 1), unbound)));
        rejection("SAME_ALLELE_BINDING_REQUIRED", () -> CodingTranscriptEdits.apply(reference, List.of(splice("splice", 6, 12), unbound)));
        var unrelatedSplice = new CodingTranscriptEdits.Edit("other-rna", CodingTranscriptEdits.Origin.SPLICE_PROJECTION,
                3, 6, "", "AAA", BINDING, "d".repeat(64), 0);
        rejection("SAME_ALLELE_BINDING_REQUIRED", () -> CodingTranscriptEdits.apply(reference, List.of(splice("splice", 6, 12), unrelatedSplice)));
        rejection("OVERLAPPING_EDITS", () -> CodingTranscriptEdits.apply(reference, List.of(coding("a", 3, 6, "CG", 1), coding("b", 4, 7, "C", 1))));
        rejection("OVERLAPPING_EDITS", () -> CodingTranscriptEdits.apply(reference, List.of(splice("a", 3, 9), splice("b", 6, 12))));
        rejection("COMPOSITE_EDIT_PARTIAL_OVERLAP", () -> CodingTranscriptEdits.apply(reference, List.of(splice("a", 6, 12), coding("b", 9, 14, "G", 2))));
        rejection("COMPOSITE_INSERTION_BOUNDARY_AMBIGUOUS", () -> CodingTranscriptEdits.apply(reference, List.of(splice("a", 6, 12), coding("b", 6, 6, "G", 1))));
        rejection("SPLICE_INSERTION_MAPPING_UNSUPPORTED", () -> CodingTranscriptEdits.apply(reference,
                List.of(edit("source", CodingTranscriptEdits.Origin.SPLICE_PROJECTION, 6, 12, "G", 1))));
    }

    @Test void crossExonSequenceEditsNeedExplicitAlignmentAssignment() {
        var reference = reference(1);
        rejection("INSERTION_EXON_ASSIGNMENT_REQUIRED", () -> CodingTranscriptEdits.apply(reference, List.of(coding("replace", 6, 12, "GCT", 0))));
        var witnessed = CodingTranscriptEdits.apply(reference, List.of(coding("replace", 6, 12, "GCT", 1)));
        assertEquals("ATGAAAGCTTTTTAA", witnessed.alteredCds());
        assertEquals(List.of(9), witnessed.junctions());
        assertFalse(witnessed.snapshot(mapper).path("scientificQualificationGranted").asBoolean());
        rejection("INSERTION_EXON_ASSIGNMENT_INVALID", () -> CodingTranscriptEdits.apply(reference, List.of(coding("bad", 3, 6, "GCT", 2))));
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void physicalGenomicDelinsAndExactHgvsGiveTheSameFullCds(int strand) {
        var allele = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "1").put("position", strand == 1 ? 103 : 203)
                .put("reference", strand == 1 ? "AAA" : "TTT").put("alternate", strand == 1 ? "CG" : "CG");
        var context = context(); context.withObject("variant").set("evidenceQueryAllele", allele.deepCopy());
        var reference = bind(model(strand), context);
        var edit = CodingTranscriptEdits.projectAllele(reference, allele, BINDING);
        assertEquals(3, edit.referenceEditStart0()); assertEquals(6, edit.referenceEditEnd0()); assertEquals("CG", edit.insertedSequence());
        var hgvs = CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000001.1:c.4_6delinsCG", GROUP);
        assertTrue(CodingTranscriptEdits.sequenceEquivalent(reference, edit, hgvs));
        assertEquals("ATGCGCCCGGGTTTTAA", CodingTranscriptEdits.apply(reference, List.of(edit)).alteredCds());
        allele.put("alternate", "C");
        rejection("ALLELE_CONTEXT_MISMATCH", () -> CodingTranscriptEdits.projectAllele(reference, allele, BINDING));
    }

    @Test void anchoredIndelsTrimPrefixSuffixAndReverseStrandConsistently() {
        for (int strand : List.of(1, -1)) {
            var allele = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "1").put("position", strand == 1 ? 104 : 203)
                    .put("reference", strand == 1 ? "AA" : "TT").put("alternate", strand == 1 ? "ACCCA" : "TGGGT");
            var context = context(); context.withObject("variant").set("evidenceQueryAllele", allele);
            var reference = bind(model(strand), context);
            var edit = CodingTranscriptEdits.projectAllele(reference, allele, BINDING);
            assertEquals(5, edit.referenceEditStart0()); assertEquals(5, edit.referenceEditEnd0()); assertEquals("CCC", edit.insertedSequence());
        }
    }

    @Test void hgvsLimitsIdentityAndReferenceBytesFailWithoutNumericOverflow() {
        var reference = reference(1);
        var deletion = CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000001.1:c.4_6delAAA", BINDING);
        assertEquals("ATGCCCGGGTTTTAA", CodingTranscriptEdits.apply(reference, List.of(deletion)).alteredCds());
        assertEquals("AAA", CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000001.1:c.4_6dup", BINDING).insertedSequence());
        assertEquals(6, CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000001.1:c.6_7insGCT", BINDING).referenceEditStart0());
        rejection("HGVS_TRANSCRIPT_MISMATCH", () -> CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000002.1:c.4A>C", BINDING));
        rejection("HGVS_COORDINATE_OVERFLOW", () -> CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000001.1:c.999999999999999999999999del", BINDING));
        rejection("HGVS_RANGE_INVALID", () -> CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000001.1:c.19del", BINDING));
        rejection("HGVS_INSERTION_INVALID", () -> CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000001.1:c.6_8insG", BINDING));
        rejection("HGVS_REFERENCE_MISMATCH", () -> CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000001.1:c.4_6delCCC", BINDING));
        rejection("HGVS_SCOPE_UNSUPPORTED", () -> CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000001.1:c.[4A>C;7C>G]", BINDING));
        rejection("HGVS_SCOPE_UNSUPPORTED", () -> CodingTranscriptEdits.codingHgvsEdit(reference, "NM_000001.1:c.6+1G>A", BINDING));
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void intervalFactsExposeIntronsButNeverApplyAnUnobservedSplice(int strand) {
        var reference = reference(strand);
        var blocks = CodingTranscriptEdits.genomicInterval(reference, 106, 202);
        assertEquals(91, blocks.unmappedBases()); assertTrue(blocks.crossesExons()); assertEquals(2, blocks.blocks().size());
        assertEquals(1, blocks.blocks().get(0).referenceExonOrdinal());
        var allele = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "1").put("position", 106)
                .put("reference", "A".repeat(50)).put("alternate", "G");
        var context = context(); context.withObject("variant").set("evidenceQueryAllele", allele);
        var bound = bind(model(strand), context);
        rejection("GENOMIC_BOUNDARY_REQUIRES_SPLICE_RESULT", () -> CodingTranscriptEdits.projectAllele(bound, allele, BINDING));
    }

    @Test void referenceAndCallerSnapshotsAreIsolatedAndPinsCannotBeInvented() {
        var model = model(1); var context = context(); var reference = bind(model, context);
        model.put("cdsSequence", "ATGTAA"); context.withObject("gene").put("symbol", "OTHER");
        assertEquals(CDS, reference.cds()); reference.model().put("cdsSequence", "ATGTAA"); assertEquals(CDS, reference.model().path("cdsSequence").asText());
        var original = model(1); var originalContext = context();
        rejection("MODEL_BINDING_MISMATCH", () -> CodingTranscriptEdits.bind(mapper, originalContext, original, BINDING,
                org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(CDS)));
        rejection("CDS_BINDING_MISMATCH", () -> CodingTranscriptEdits.bind(mapper, originalContext, original, adapter.canonicalSha256(original), BINDING));
        var result = CodingTranscriptEdits.apply(reference, List.of(coding("one", 3, 6, "CG", 1)));
        var snapshot = result.snapshot(mapper); snapshot.remove("binding"); assertTrue(result.snapshot(mapper).has("binding"));
        assertThrows(UnsupportedOperationException.class, () -> result.junctions().add(1));
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void cdsToUtrEditingIsAnExplicitSoftwareBoundaryNotAMissingSpliceObservation(int strand) {
        var model = model(strand);
        var last = (ObjectNode) model.path("exons").path(1);
        last.put("cdsEnd", 21);
        if (strand == 1) last.put("genomicEnd", 211); else last.put("genomicStart", 97);
        var allele = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "1")
                .put("position", strand == 1 ? 207 : 98).put("reference", "AAAA").put("alternate", "C");
        var context = context(); context.withObject("variant").set("evidenceQueryAllele", allele);
        var reference = bind(model, context);
        rejection("ALLELE_EXTENDS_OUTSIDE_CDS_UNSUPPORTED", () -> CodingTranscriptEdits.projectAllele(reference, allele, BINDING));
    }
}
