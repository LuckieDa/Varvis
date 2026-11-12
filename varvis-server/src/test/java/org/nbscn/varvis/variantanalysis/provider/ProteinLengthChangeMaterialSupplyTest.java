package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

/** Technical material only: invented CDS and hand-calculated altered sequences, never clinical approval. */
class ProteinLengthChangeMaterialSupplyTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final AtomicInteger bases = new AtomicInteger();
    Pm4TestFixtures data;
    String reference, referenceVersion = "TEST_ONLY_REFERENCE_1";

    void setup(int strand, int position, String ref, String alt, String coding, String protein) throws Exception {
        data = new Pm4TestFixtures(mapper, strand);
        data.variant(position, ref, alt, coding, protein);
        reference = strand == 1 ? "ATGGAACCTGGCTTTAAATAA" : "TTATTTAAAGCCAGGTTCCAT";
        data.model.putObject("sequenceVerification")
                .put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(Pm4TestFixtures.CDS))
                .put("cdsSha256", CodingTranscriptMaterialSupply.sha(Pm4TestFixtures.CDS));
    }

    CodingTranscriptMaterialSupply.ReferenceSpan read(String contig, int start, int length) {
        assertEquals("11", contig);
        bases.addAndGet(length);
        return new CodingTranscriptMaterialSupply.ReferenceSpan(reference.substring(start - 100, start - 100 + length),
                mapper.createObjectNode().put("version", referenceVersion)
                        .put("expectedSha256", data.context.at("/variant/normalization/referenceSequenceSha256").asText()));
    }

    CodingTranscriptMaterialSupply prebuilt() throws Exception {
        return prebuilt(false);
    }

    CodingTranscriptMaterialSupply prebuilt(boolean complexAlleles) throws Exception {
        byte[] raw = mapper.writeValueAsBytes(data.model);
        Files.write(root.resolve("ENST000001.1.model.json"), raw);
        var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        manifest.putArray("entries").addObject().put("transcript", "ENST000001.1")
                .put("file", "ENST000001.1.model.json").put("fileBytes", raw.length)
                .put("fileSha256", CodingTranscriptMaterialSupply.sha(raw))
                .put("canonicalSha256", data.canonicalSha256(data.model))
                .put("modelId", data.model.path("modelId").asText()).put("modelVersion", data.model.path("modelVersion").asText());
        byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
        Files.write(root.resolve("manifest.json"), manifestBytes);
        return new CodingTranscriptMaterialSupply(mapper, root, CodingTranscriptMaterialSupply.sha(manifestBytes), this::read, null, complexAlleles);
    }

    CodingTranscriptMaterialSupply derived() throws Exception {
        var row = data.model.deepCopy();
        row.remove(List.of("sourceRecords", "cdsSequence", "schemaVersion", "modelId", "modelVersion", "sequenceVerification"));
        row.put("transcriptLength", 21).put("rawGtfSha256", "b".repeat(64));
        byte[] annotations = (row + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] index = ("ENST000001.1\t0\t" + annotations.length + "\t" + CodingTranscriptMaterialSupply.sha(annotations) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        Files.write(root.resolve("annotations.jsonl"), annotations); Files.write(root.resolve("index.tsv"), index);
        var manifest = mapper.createObjectNode().put("schemaVersion", "gencode-transcript-index-v1").put("methodVersion", "1.0.0")
                .put("assembly", "GRCh38").put("release", 38).put("activation", "TECHNICAL_MATERIAL_ONLY").put("independentRnaVerification", false);
        manifest.putObject("source").put("sha256", "a".repeat(64))
                .put("url", "https://ftp.ebi.ac.uk/pub/databases/gencode/Gencode_human/release_38/gencode.v38.annotation.gtf.gz");
        manifest.putObject("counts").put("indexed", 1);
        manifest.putObject("data").put("file", "annotations.jsonl").put("bytes", annotations.length).put("sha256", CodingTranscriptMaterialSupply.sha(annotations));
        manifest.putObject("index").put("file", "index.tsv").put("bytes", index.length).put("sha256", CodingTranscriptMaterialSupply.sha(index));
        byte[] raw = mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), raw);
        return new CodingTranscriptMaterialSupply(mapper, null, "", this::read,
                new GencodeTranscriptIndex(mapper, root, CodingTranscriptMaterialSupply.sha(raw)));
    }

    @ParameterizedTest @CsvSource({
        "1,105,A,AGCT,6_7insGCT,Glu2_Pro3insAla,ATGGAAGCTCCTGGCTTTAAATAA,MEAPGFK*,1",
        "-1,114,G,GAGC,6_7insGCT,Glu2_Pro3insAla,ATGGAAGCTCCTGGCTTTAAATAA,MEAPGFK*,1",
        "1,105,ACCT,A,7_9del,Pro3del,ATGGAAGGCTTTAAATAA,MEGFK*,-1",
        "-1,111,CAGG,C,7_9del,Pro3del,ATGGAAGGCTTTAAATAA,MEGFK*,-1",
        "1,103,G,GGGC,4_5insGGC,Glu2delinsGlyGln,ATGGGGCAACCTGGCTTTAAATAA,MGQPGFK*,1",
        "1,104,A,ACCT,5_6insCCT,Glu2delinsAspLeu,ATGGACCTACCTGGCTTTAAATAA,MDLPGFK*,1",
        "1,103,GAAC,G,5_7del,Glu2_Pro3delinsAla,ATGGCTGGCTTTAAATAA,MAGFK*,-1",
        "1,104,AACC,A,6_8del,Glu2_Pro3delinsAsp,ATGGATGGCTTTAAATAA,MDGFK*,-1",
        "1,108,T,TCCT,7_9dup,Pro3dup,ATGGAACCTCCTGGCTTTAAATAA,MEPPGFK*,1"
    })
    void suppliesLengthFactsAcrossStrandsAndPhases(int strand, int position, String ref, String alt,
            String coding, String protein, String expectedCds, String expectedProtein, int delta) throws Exception {
        setup(strand, position, ref, alt, coding, protein);
        var contextBefore = data.context.deepCopy(); var candidateBefore = mapper.valueToTree(data.candidate);
        var result = prebuilt().resolveProteinLengthChange(data.context, data.candidate);
        assertTrue(result.ready(), result.snapshot().toString());
        var snapshot = result.snapshot(); var facts = snapshot.path("alleleAssociation");
        assertEquals("SIMPLE_INFRAME_LENGTH_CHANGE", snapshot.path("associationKind").asText());
        assertEquals("1.0.0", snapshot.path("associationVersion").asText());
        assertEquals(data.context.path("contextId"), snapshot.path("contextId"));
        assertEquals(data.context.at("/variant/evidenceQueryAllele"), snapshot.path("queryAllele"));
        assertEquals(delta, facts.path("netAminoAcidChange").asInt());
        assertEquals(CodingTranscriptMaterialSupply.sha(expectedCds), facts.path("alteredCdsSha256").asText());
        assertEquals(CodingTranscriptMaterialSupply.sha(expectedProtein), facts.path("alteredProteinSha256").asText());
        assertEquals("READY", facts.path("equivalentEditEnvelopeStatus").asText());
        assertFalse(snapshot.path("clinicalAcceptance").asBoolean(true));
        assertFalse(snapshot.path("scientificEvaluationPerformed").asBoolean(true));
        assertFalse(snapshot.toString().contains(expectedCds)); assertFalse(snapshot.toString().contains(expectedProtein));
        assertFalse(facts.has("referenceCds")); assertFalse(facts.has("alteredCds"));
        assertEquals(contextBefore, data.context); assertEquals(candidateBefore, mapper.valueToTree(data.candidate));
        assertEquals(23, bases.get());
    }

    @ParameterizedTest @ValueSource(ints={1,-1})
    void derivesExactModelFromGencodeWithoutHandmadeMaterial(int strand) throws Exception {
        setup(strand, strand == 1 ? 105 : 114, strand == 1 ? "A" : "G", strand == 1 ? "AGCT" : "GAGC",
                "6_7insGCT", "Glu2_Pro3insAla");
        var supply = derived(); var first = supply.resolveProteinLengthChange(data.context, data.candidate);
        assertTrue(first.ready(), first.snapshot().toString());
        assertEquals("GENCODE_GTF_PLUS_REFERENCE", first.snapshot().path("modelOrigin").asText());
        assertEquals(Pm4TestFixtures.CDS, requireNonNull(first.model()).path("cdsSequence").asText());
        assertFalse(requireNonNull(first.model()).at("/sequenceVerification/independentRnaVerification").asBoolean(true));
        int before = bases.get();
        var repeat = supply.resolveProteinLengthChange(data.context, data.candidate);
        assertTrue(repeat.ready()); assertEquals(2, bases.get() - before); assertEquals(first.model(), repeat.model());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void structureAndLengthModesShareOnlySequenceProofAndNeverEffectApproval(boolean structureFirst) throws Exception {
        setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var supply = prebuilt();
        var first = structureFirst ? supply.resolveStructure(data.context, data.candidate)
                : supply.resolveProteinLengthChange(data.context, data.candidate);
        assertTrue(first.ready(), first.snapshot().toString());
        var structure = supply.resolveStructure(data.context, data.candidate);
        var length = supply.resolveProteinLengthChange(data.context, data.candidate);
        assertTrue(structure.ready()); assertTrue(length.ready());
        assertEquals("EXACT_TRANSCRIPT_LOCAL_STRUCTURE_SUPPLY", structure.snapshot().path("method").asText());
        assertFalse(structure.snapshot().path("alleleEffectVerified").asBoolean(true));
        assertFalse(structure.snapshot().has("alleleAssociation"));
        assertEquals(1, length.snapshot().at("/alleleAssociation/netAminoAcidChange").asInt());
        assertFalse(supply.resolve(data.context, data.candidate).ready(), "In-frame material cannot authorize missense");
        assertEquals(structure.snapshot().path("verification"), length.snapshot().path("verification"));
        data.variant(105,"A","AGCT","5_6insGCT",null);
        assertTrue(supply.resolveStructure(data.context, data.candidate).ready(), "Structure does not claim an effect");
        var invalid = supply.resolveProteinLengthChange(data.context, data.candidate);
        assertFalse(invalid.ready());
        assertEquals("PM4_HGVSC_SEQUENCE_MISMATCH", invalid.snapshot().path("reasonCode").asText());
    }

    @Test void cachedStructureStillChecksTheVariantReferenceAgainstTheCurrentFasta() throws Exception {
        setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var supply = prebuilt();
        assertTrue(supply.resolveStructure(data.context, data.candidate).ready());
        data.variant(105,"C","CGCT","6_7insGCT","Glu2_Pro3insAla");
        var invalid = supply.resolveStructure(data.context, data.candidate);
        assertFalse(invalid.ready());
        assertEquals("TRANSCRIPT_MATERIAL_ALLELE_REFERENCE_MISMATCH", invalid.snapshot().path("reasonCode").asText());
    }

    @Test void cachedModelDoesNotReuseAnAlleleJudgmentOrChangeOldMissenseContract() throws Exception {
        setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var supply = prebuilt(); var identityBefore = supply.identity();
        assertFalse(identityBefore.has("associationKind"));
        assertEquals("SIMPLE_INFRAME_LENGTH_CHANGE", supply.proteinLengthChangeIdentity().path("associationKind").asText());
        assertTrue(supply.resolveProteinLengthChange(data.context, data.candidate).ready());
        var first = supply.resolveProteinLengthChange(data.context, data.candidate);
        assertTrue(first.snapshot().path("modelCacheHit").asBoolean()); assertEquals(24, bases.get());
        requireNonNull(first.model()).removeAll(); first.snapshot().removeAll();
        assertFalse(supply.resolve(data.context, data.candidate).ready());
        assertEquals(identityBefore, supply.identity()); assertEquals("1.1.0", supply.identity().path("version").asText());
        data.variant(105,"ACCT","A","7_9del","Pro3del");
        var second = supply.resolveProteinLengthChange(data.context, data.candidate);
        assertTrue(second.ready(), second.snapshot().toString());
        assertEquals(-1, second.snapshot().at("/alleleAssociation/netAminoAcidChange").asInt());
        assertEquals(25, bases.get());
        data.variant(105,"A","AGCT","5_6insGCT",null);
        var invalid = supply.resolveProteinLengthChange(data.context, data.candidate);
        assertFalse(invalid.ready()); assertEquals("PM4_HGVSC_SEQUENCE_MISMATCH", invalid.snapshot().path("reasonCode").asText());
        assertEquals(25, bases.get());
    }

    @ParameterizedTest @CsvSource({
        "105,A,AG,6_7insG,PM4_FRAMESHIFT_UNSUPPORTED",
        "105,A,ATAA,6_7insTAA,PM4_PREMATURE_STOP_UNSUPPORTED",
        "100,ATGG,A,2_4del,PM4_START_AFFECTED_UNSUPPORTED",
        "116,AATA,A,18_20del,PM4_STOP_LOSS_UNSUPPORTED",
        "105,AC,AGCTT,6_7delinsGCTT,PM4_COMPLEX_ALLELE_UNSUPPORTED",
        "105,C,CGCT,6_7insGCT,PM4_REFERENCE_MISMATCH",
        "105,A,AGCT,5_6insGCT,PM4_HGVSC_SEQUENCE_MISMATCH"
    })
    void unsafeEditsStayExplicitlyUnavailable(int pos,String ref,String alt,String coding,String reason) throws Exception {
        setup(1,pos,ref,alt,coding,null);
        var result = prebuilt().resolveProteinLengthChange(data.context,data.candidate);
        assertFalse(result.ready()); assertNull(result.model()); assertEquals(reason,result.snapshot().path("reasonCode").asText());
        assertFalse(result.snapshot().has("alleleAssociation")); assertEquals(0,bases.get());
    }

    @ParameterizedTest @ValueSource(strings={"allele","gene","version","annotation","duplicate","timestamp","vepVersion","normalization","lineage"})
    void mismatchedOrUnqualifiedIdentitiesCannotProduceMaterial(String mode) throws Exception {
        setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla"); var supply = prebuilt();
        switch(mode) {
            case "allele" -> data.context.withObject("/variant/evidenceQueryAllele").put("position",106);
            case "gene" -> data.context.withObject("/gene").put("hgncId","HGNC:999");
            case "version" -> data.context.withObject("/transcript").put("version",2);
            case "annotation" -> data.candidate.applyTranscriptAnnotations(List.of());
            case "duplicate" -> data.candidate.applyTranscriptAnnotations(List.of(data.candidate.getTranscriptAnnotations().get(0), data.candidate.getTranscriptAnnotations().get(0)));
            case "normalization" -> data.context.withObject("/variant/normalizedAllele").put("alternate","A");
            case "lineage" -> data.context.withObject("/variant/assemblyLineage").put("mappingStatus","CONVERTED");
            default -> {
                var annotation = data.annotation("6_7insGCT","Glu2_Pro3insAla","inframe_insertion");
                if (mode.equals("timestamp")) annotation.remove("timestamp");
                else { annotation.put("version","115.0"); data.context.withObject("/transcript").put("annotationSourceVersion","115.0"); }
                data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation,TranscriptConsequence.class)));
            }
        }
        var result = supply.resolveProteinLengthChange(data.context,data.candidate);
        assertFalse(result.ready(),result.snapshot().toString()); assertNull(result.model()); assertEquals(0,bases.get());
    }

    @Test void referenceAndModelMustRemainQualifiedEvenAfterCachedSuccess() throws Exception {
        setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla"); var supply = prebuilt();
        assertTrue(supply.resolveProteinLengthChange(data.context,data.candidate).ready());
        referenceVersion = "TEST_ONLY_REFERENCE_2"; reference = "ATGGAACCTGGCTTTAAACAA";
        var changed = supply.resolveProteinLengthChange(data.context,data.candidate);
        assertFalse(changed.ready()); assertEquals("TRANSCRIPT_MATERIAL_REFERENCE_SEQUENCE_MISMATCH",changed.snapshot().path("reasonCode").asText());
        reference = Pm4TestFixtures.CDS;
        assertTrue(supply.resolveProteinLengthChange(data.context,data.candidate).ready());
        Files.writeString(root.resolve("ENST000001.1.model.json"),"{}");
        assertFalse(supply.resolveProteinLengthChange(data.context,data.candidate).ready());
    }

    @Test void cancellationPreservesFlagAndDoesNotReadReference() throws Exception {
        setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla"); var supply = prebuilt();
        Thread.currentThread().interrupt();
        try { var result = supply.resolveProteinLengthChange(data.context,data.candidate);
            assertFalse(result.ready()); assertEquals("TRANSCRIPT_MATERIAL_INTERRUPTED",result.snapshot().path("reasonCode").asText());
            assertEquals(0,bases.get()); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }

    @Test void bp3EnvelopeBoundaryDoesNotDiscardValidLengthMaterial() throws Exception {
        setup(1,105,"G","GATG","4_6dup","Met2dup");
        reference = "ATGATGGAATAA";
        data.model.put("cdsSequence",reference).put("cdsLength",12).put("proteinLength",3);
        data.model.withObject("/exons/0").put("genomicEnd",111).put("cdsEnd",12);
        data.model.withObject("/sequenceVerification").put("fullSplicedTranscriptSha256",CodingTranscriptMaterialSupply.sha(reference))
                .put("cdsSha256",CodingTranscriptMaterialSupply.sha(reference));
        var result = prebuilt().resolveProteinLengthChange(data.context,data.candidate);
        assertTrue(result.ready(),result.snapshot().toString());
        assertEquals("UNAVAILABLE",result.snapshot().at("/alleleAssociation/equivalentEditEnvelopeStatus").asText());
        assertEquals("BP3_EQUIVALENT_ENVELOPE_ENGINEERING_BOUNDARY",
                result.snapshot().at("/alleleAssociation/equivalentEditEnvelopeReasonCode").asText());
        assertEquals(0,result.snapshot().at("/alleleAssociation/equivalentEditEnvelope/firstStart0").asInt());
        assertEquals(1,result.snapshot().at("/alleleAssociation/netAminoAcidChange").asInt());
    }

    @ParameterizedTest @ValueSource(strings={"missingContextId","staleContextId","alias","coordinateOverflow"})
    void invalidIdentityAndCoordinatesReturnStructuredFailure(String mode) throws Exception {
        setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        String expected = "LENGTH_CHANGE_CONTEXT_INVALID";
        switch (mode) {
            case "missingContextId" -> data.context.remove("contextId");
            case "staleContextId" -> data.context.put("contextId","context-sha256-" + "0".repeat(64));
            case "alias" -> {
                var annotation = data.annotation("6_7insGCT","Glu2_Pro3insAla","inframe_insertion").put("transcriptId","ENST999999.1");
                data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation,TranscriptConsequence.class)));
                expected = "LENGTH_CHANGE_ANNOTATION_IDENTITY_MISMATCH";
            }
            default -> { data.variant(Integer.MAX_VALUE,"A","AGCT","6_7insGCT",null); expected = "LENGTH_CHANGE_COORDINATE_OUT_OF_RANGE"; }
        }
        var supply = prebuilt();
        var result = supply.resolveProteinLengthChange(data.context,data.candidate);
        assertFalse(result.ready()); assertEquals(expected,result.snapshot().path("reasonCode").asText()); assertEquals(0,bases.get());
    }

    @Test void derivedModelFeedsExistingPm4WhileMissingRegionsRemainMissing() throws Exception {
        setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var material = derived().resolveProteinLengthChange(data.context,data.candidate);
        assertTrue(material.ready());
        var model = material.model(); assertNotNull(model);
        data.model.removeAll(); data.model.setAll(model);
        model.path("sourceRecords").forEach(source -> data.policy.withArray("scientificSources").add(source.deepCopy()));
        data.bind();
        var result = data.evaluate();
        assertEquals("MET",result.at("/evaluation/evaluationResult/status").asText(),result.toString());
        assertEquals(material.snapshot().at("/alleleAssociation/alteredProteinSha256"),result.at("/resolvedInput/alteredProteinSha256"));
        data.regions.putArray("intervals"); data.bind();
        var missing = data.evaluate();
        assertEquals("NOT_EVALUATED",missing.at("/evaluation/evaluationResult/status").asText(),missing.toString());
        assertEquals("PM4_REGION_COVERAGE_MISSING",missing.at("/evaluation/evaluationResult/reasonCode").asText());
    }

    @Test void derivedModelFeedsExistingBp3WithoutGrantingFunctionalReview() throws Exception {
        setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var material = derived().resolveProteinLengthChange(data.context,data.candidate);
        assertTrue(material.ready());
        var model = material.model(); assertNotNull(model);
        var bp3 = new Bp3TestFixtures(mapper,1);
        bp3.model.removeAll(); bp3.model.setAll(model); bp3.context = data.context.deepCopy(); bp3.candidate = data.candidate;
        bp3.policy.withObject("scope").put("hgncId",data.context.at("/gene/hgncId").asText())
                .put("mondoId",data.context.at("/disease/mondoId").asText()).put("moi",data.context.path("moi").asText());
        model.path("sourceRecords").forEach(source -> bp3.policy.withArray("scientificSources").add(source.deepCopy()));
        // This is a synthetic test-only review of an invented region, not generated scientific approval.
        bp3.bind(); bp3.review(); bp3.bind();
        var result = bp3.evaluate();
        assertEquals("MET",result.at("/evaluation/evaluationResult/status").asText(),result.toString());
        assertEquals(material.snapshot().at("/alleleAssociation/equivalentEditEnvelope"),result.at("/resolvedInput/equivalentEditEnvelope"));
        ((ObjectNode) bp3.regions.at("/intervals/0")).remove("functionalReview"); bp3.bind();
        var missing = bp3.evaluate();
        assertEquals("NOT_EVALUATED",missing.at("/evaluation/evaluationResult/status").asText(),missing.toString());
    }
}
