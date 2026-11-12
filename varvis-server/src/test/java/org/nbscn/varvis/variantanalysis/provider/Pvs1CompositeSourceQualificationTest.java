package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned toy originals, not public clinical or expert adoption.
 * Independent sequence: RNA c13_24del removes four CCA codons; retained
 * c28C>A changes the next original P10 to T. The complete altered CDS is
 * ATG + CCA*4 + ACA + CCA*10 + CTAACC + CCA*177 + TAA (588 nt).
 * Source original is checked separately from the actual-context A5 replay.
 */
class Pvs1CompositeSourceQualificationTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final Pvs1SpliceConsequenceTest base = new Pvs1SpliceConsequenceTest();
    static final String ORIGINAL_ID = "TEST_ONLY_COMPOSITE_ORIGINAL";
    static final String ALTERED = "ATG" + "CCA".repeat(4) + "ACA" + "CCA".repeat(10) + "CTAACC" + "CCA".repeat(177) + "TAA";
    record Data(Pvs1SpliceConsequenceTest.Data data, Map<String, byte[]> originals, ObjectNode original) { }

    Data data(int strand) throws Exception {
        var d = base.data(strand, false, false);var input = d.input();var outcome = d.material();
        var exon = input.model.path("exons").path(2);int offset = 28 - exon.path("cdsStart").asInt();
        int genomic = strand == 1 ? exon.path("genomicStart").asInt() + offset : exon.path("genomicEnd").asInt() - offset;
        base.moveAllele(d, genomic, strand == 1 ? "C" : "G", strand == 1 ? "A" : "T", "splice_region_variant", ":c.28C>A", genomic, strand == 1 ? "C" : "G");
        outcome.withObject("event").put("type", "EXON_SHORTENING").put("deleteEndCds", 24);
        input.policy.withObject("spliceScope").putArray("eventTypes").add("EXON_SKIPPING").add("EXON_SHORTENING");
        new Pvs1SpliceCompositionModuleTest().composition(d);
        outcome.withObject("codingEditComposition").put("sequenceScope", "COMPLETE_ALLELIC_CDS").put("alteredCdsSequence", ALTERED);
        outcome.putObject("inFrameReview").put("status", "REVIEWED_FOR_PVS1_SPLICE_INFRAME").put("reviewId", "TEST_ONLY_FULL_EFFECT")
                .put("basis", "Synthetic complete combined effect and function review").putArray("sourceIds").add("synthetic-source");
        var frameScope = input.policy.withObject("spliceScope").putObject("inFrameScope").put("status", "REVIEWED_FOR_PVS1_SPLICE_INFRAME")
                .put("reviewId", "TEST_ONLY_COMPOSITE_SCOPE").put("basis", "Synthetic explicit function and applicable rule");
        frameScope.putArray("sourceIds").add("synthetic-source");var exonFacts=frameScope.putArray("exonAssessments");
        for (var row:input.model.path("exons")) exonFacts.addObject().put("ordinal",row.path("ordinal").asInt())
                .put("biologicalRelevance","PRESENT").put("populationLof","NOT_FREQUENT").put("basis","Synthetic exon facts")
                .putArray("sourceIds").add("synthetic-source");
        frameScope.putArray("rules").addObject().put("ruleId","four-deleted-codons").put("predicate","CRITICAL_REGION")
                .put("start",5).put("end",8).put("minimumAffectedResidues",1).put("strength","STRONG")
                .put("basis","Synthetic complete codon deletion rule").putArray("sourceIds").add("synthetic-source");
        Map<String, byte[]> originals = new LinkedHashMap<>(Pvs1SpliceSourceQualificationTest.configureOriginals(mapper, input.policy, input.model, List.of(outcome)));
        input.context.put("contextId", adapter.canonicalContextId(input.context));
        String actualEffect = derive(d).mechanicalEffectSha256();
        outcome.withObject("inFrameReview").putObject("compositeEffectAssessment")
                .put("schemaVersion", "pvs1-composite-inframe-effect-review-v1").put("status", "REVIEWED_FOR_ACTUAL_COMPOSITE_EFFECT")
                .put("mechanicalEffectSha256", actualEffect).put("reviewId", "TEST_ONLY_WHOLE_REVIEW")
                .put("basis", "Synthetic original explicitly reviews the combined effect").putArray("sourceIds").add(ORIGINAL_ID);
        outcome.withArray("sourceRecords").addObject().put("sourceId", ORIGINAL_ID).put("version", "TEST_ONLY")
                .put("url", "https://example.test/composite-original").put("sha256", "0".repeat(64));
        var original = mapper.createObjectNode().put("schemaVersion", "pvs1-composite-effect-review-original-v1").put("reviewId", "TEST_ONLY_WHOLE_REVIEW");
        var scope = original.putObject("scope");
        for (String key : List.of("hgncId", "mondoId", "moi", "transcript", "transcriptModelSha256", "allele")) scope.set(key, outcome.path(key).deepCopy());
        original.putObject("sequence").put("referenceCdsSha256", CodingTranscriptMaterialSupply.sha(input.model.path("cdsSequence").asText()))
                .put("alteredCdsSequence", ALTERED).put("alteredCdsSha256", CodingTranscriptMaterialSupply.sha(ALTERED));
        var events = original.putObject("events");events.set("rnaEvent", outcome.path("event").deepCopy());
        events.set("codingAllele", outcome.path("allele").deepCopy());events.set("codingEditComposition", outcome.path("codingEditComposition").deepCopy());
        var conditions = original.putObject("conditions");conditions.set("parentReview", outcome.path("review").deepCopy());
        conditions.set("assayAssessment", outcome.path("assessment").deepCopy());
        var frame = outcome.withObject("inFrameReview").deepCopy();frame.remove("compositeEffectAssessment");conditions.set("inFrameReview", frame);
        original.putObject("use").put("purpose", "PROJECT_RESEARCH").put("clinicalAcceptance", false).put("createsExpertReview", false)
                .putArray("allowedDataClasses").add("SYNTHETIC").add("PRIVACY_BOUNDED_RUNTIME");
        var result = new Data(d, originals, original);seal(result, mapper.writeValueAsString(original));
        assertEquals(actualEffect, derive(d).mechanicalEffectSha256(), "Original evidence bytes must not create a circular effect hash");
        return result;
    }
    CodingConsequenceFacts.Facts derive(Pvs1SpliceConsequenceTest.Data data) {
        var input = data.input();var ref = CodingTranscriptEdits.bind(mapper, input.context, input.model,
                adapter.canonicalSha256(input.model), CodingTranscriptMaterialSupply.sha(input.model.path("cdsSequence").asText()));
        var result = Pvs1SpliceComposition.compose(mapper, input.context, input.model, data.material(), 12, 24).result();
        assertEquals(ALTERED, result.alteredCds(), "Independent complete CDS witness");
        return CodingConsequenceFacts.derive(mapper, ref, result);
    }
    void seal(Data d, String raw) {
        var effect = d.data().material().withObject("inFrameReview").withObject("compositeEffectAssessment");
        effect.putObject("originalReview").put("sourceId", ORIGINAL_ID).put("structuredOriginalUtf8", raw);
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);d.originals().put(ORIGINAL_ID, bytes);
        for (var source : d.data().material().path("sourceRecords")) if (ORIGINAL_ID.equals(source.path("sourceId").asText()))
            ((ObjectNode) source).put("sha256", CodingTranscriptMaterialSupply.sha(bytes));
    }
    ObjectNode qualification(Data d) throws Exception {
        return Pvs1SpliceSourceQualificationTest.qualification(mapper, temporary, d.data().input().policy,
                d.data().input().model, List.of(d.data().material()), d.originals());
    }
    ObjectNode entry(Data d, ObjectNode q) { return new Pvs1SpliceSourceQualificationTest().entry(d.data().input().policy, q); }
    String issue(Data d, ObjectNode q) { return Pvs1SourceQualification.issue(entry(d, q), d.data().input().policy, adapter); }
    String actual(Data d, ObjectNode q) {
        var assessment = mapper.createObjectNode().put("status", "READY");assessment.putObject("adoption").set("qualification", q);
        return Pvs1SourceQualification.spliceMaterialIssue(assessment, d.data().input().context,
                d.data().input().policy, d.data().input().model, d.data().material(), adapter);
    }

    void sample(Data d, ObjectNode q, String scenario) throws Exception {
        String output=System.getProperty("varvis.t07.composite.source.artifacts");if (output == null) return;
        Path directory=Path.of(output).resolve(scenario);java.nio.file.Files.createDirectories(directory);
        String sourceIssue=issue(d,q), actualIssue=actual(d,q);
        var sample=mapper.createObjectNode().put("schemaVersion","t07-composite-source-gate-sample-v1")
                .put("dataClass","SYNTHETIC").put("purpose","PROJECT_RESEARCH").put("clinicalAcceptance",false)
                .put("normalSelectedBusinessVerified",false).put("scientificAdoptionGranted",false)
                .put("layer","ACTUAL_PINNED_SYNTHETIC_ORIGINAL_AND_DIRECT_NORMAL_SOURCE_GATE");
        sample.set("context",d.data().input().context.deepCopy());sample.set("model",d.data().input().model.deepCopy());
        sample.set("policy",d.data().input().policy.deepCopy());sample.set("sourceOutcome",d.data().material().deepCopy());
        sample.set("qualification",q.deepCopy());
        var result=sample.putObject("actualGateResults");
        if (sourceIssue == null) result.putNull("sourceQualificationIssue");else result.put("sourceQualificationIssue",sourceIssue);
        if (actualIssue == null) result.putNull("actualMaterialIssue");else result.put("actualMaterialIssue",actualIssue);
        Path originalPath=temporary.resolve("source-"+CodingTranscriptMaterialSupply.sha(ORIGINAL_ID)+".txt");
        byte[] original=java.nio.file.Files.readAllBytes(originalPath);
        sample.put("actualReadOriginalSha256",CodingTranscriptMaterialSupply.sha(original)).put("actualReadOriginalBytes",original.length);
        java.nio.file.Files.write(directory.resolve("original-review.json"),original,java.nio.file.StandardOpenOption.CREATE_NEW);
        java.nio.file.Files.write(directory.resolve("source-gate-sample.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sample),
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    @ParameterizedTest @ValueSource(ints={1,-1})
    void originalUseAndConditionsAndActualCompleteEffectAreSeparatelyVerified(int strand) throws Exception {
        var d=data(strand);var q=qualification(d);
        assertNull(issue(d,q));assertNull(actual(d,q));
        assertFalse(q.path("createsExpertReview").asBoolean());assertFalse(q.path("clinicalAcceptance").asBoolean());
        assertEquals(4, derive(d.data()).inFrameNetDeletedProteinResidues());
        sample(d,q,strand == 1 ? "plus" : "minus");
    }
    @ParameterizedTest @ValueSource(strings={"missingOriginal","unknownSource","unreferencedSource","changedUtf8","badProofBytes"})
    void statusAndEffectHashCannotSubstituteForActualOriginalBytes(String mutation) throws Exception {
        var d=data(1);var effect=d.data().material().withObject("inFrameReview").withObject("compositeEffectAssessment");
        if (mutation.equals("missingOriginal")) effect.remove("originalReview");
        if (mutation.equals("unknownSource")) effect.withObject("originalReview").put("sourceId","not-in-the-source-list");
        if (mutation.equals("unreferencedSource")) effect.putArray("sourceIds").add("synthetic-source");
        if (mutation.equals("changedUtf8")) effect.withObject("originalReview").put("structuredOriginalUtf8", "changed original");
        var q=qualification(d);
        if (mutation.equals("badProofBytes")) {
            for (var proof:q.at("/spliceVerification/outcomes/0/sourceVerification")) if (ORIGINAL_ID.equals(proof.at("/source/sourceId").asText()))
                ((ObjectNode)proof).put("bytes",proof.path("bytes").asInt()+1);
        }
        assertNotNull(issue(d,q));
    }
    @ParameterizedTest @ValueSource(strings={"scope","model","sequence","rna","coding","parent","assay","function","purpose","classes","clinical","expert","reviewId","extraField","invalidJson","duplicateJson","trailingJson","trailingScalar","tooLarge"})
    void rehashedOriginalStillCannotChangeScopeConditionsOrUse(String mutation) throws Exception {
        var d=data(1);var original=d.original();
        switch (mutation) {
            case "scope" -> original.withObject("scope").put("mondoId","MONDO:wrong");
            case "model" -> original.withObject("scope").put("transcriptModelSha256","0".repeat(64));
            case "sequence" -> original.withObject("sequence").put("alteredCdsSequence","ATGTAA");
            case "rna" -> original.withObject("events").withObject("rnaEvent").put("deleteEndCds",25);
            case "coding" -> original.withObject("events").withObject("codingAllele").put("alternate","T");
            case "parent" -> original.withObject("conditions").withObject("parentReview").put("maximumStrength","VERY_STRONG_DIFFERENT");
            case "assay" -> original.withObject("conditions").withObject("assayAssessment").put("tissueRelevance","UNKNOWN");
            case "function" -> original.withObject("conditions").withObject("inFrameReview").put("reviewId","single-deletion-only");
            case "purpose" -> original.withObject("use").put("purpose","CLINICAL");
            case "classes" -> original.withObject("use").putArray("allowedDataClasses").add("PUBLIC_REFERENCE");
            case "clinical" -> original.withObject("use").put("clinicalAcceptance",true);
            case "expert" -> original.withObject("use").put("createsExpertReview",true);
            case "reviewId" -> original.put("reviewId","not-the-original-review");
            case "extraField" -> original.put("statusOnlyAuthority",true);
            case "invalidJson", "duplicateJson", "trailingJson", "trailingScalar", "tooLarge" -> { }
            default -> throw new AssertionError(mutation);
        }
        String raw=mapper.writeValueAsString(original);
        if (mutation.equals("invalidJson")) raw="not json";
        if (mutation.equals("duplicateJson")) raw=raw.substring(0,raw.length()-1)+",\"reviewId\":\"TEST_ONLY_WHOLE_REVIEW\"}";
        if (mutation.equals("trailingJson")) raw += "{}";
        if (mutation.equals("trailingScalar")) raw += " true";
        if (mutation.equals("tooLarge")) raw=" ".repeat(65537);
        seal(d,raw);var q=qualification(d);assertNotNull(issue(d,q));
        if (mutation.equals("purpose")) sample(d,q,"wrong-use");
    }
    @Test void fullEffectReviewCannotCreateAnApplicablePolicyScope() throws Exception {
        var d=data(1);d.data().input().policy.withObject("spliceScope").remove("inFrameScope");
        assertEquals("PVS1_SOURCE_QUALIFICATION_COMPOSITE_POLICY_SCOPE_REQUIRED",issue(d,qualification(d)));
    }
    @Test void actualContextEffectHashIsVerifiedAfterTheWholeMaterialIsBound() throws Exception {
        var d=data(1);d.data().material().withObject("inFrameReview").withObject("compositeEffectAssessment").put("mechanicalEffectSha256","0".repeat(64));
        var q=qualification(d);assertNull(issue(d,q),"Source original does not pretend to predict a patient context hash");
        assertEquals("PVS1_SOURCE_QUALIFICATION_COMPOSITE_EFFECT_BINDING_MISMATCH",actual(d,q));
        sample(d,q,"wrong-context-effect-binding");
    }
    @Test void consistentButWrongCompleteCdsWitnessStillFailsTheExistingA5Replay() throws Exception {
        var d=data(1);String wrong="ATGTAA";
        d.data().material().withObject("codingEditComposition").put("alteredCdsSequence",wrong);
        d.original().withObject("events").set("codingEditComposition",d.data().material().path("codingEditComposition").deepCopy());
        d.original().withObject("sequence").put("alteredCdsSequence",wrong).put("alteredCdsSha256",CodingTranscriptMaterialSupply.sha(wrong));
        seal(d,mapper.writeValueAsString(d.original()));var q=qualification(d);assertNull(issue(d,q));
        assertEquals("PVS1_SOURCE_QUALIFICATION_COMPOSITE_EFFECT_REPLAY_FAILED",actual(d,q));
        sample(d,q,"wrong-actual-cds");
    }
    @Test void oldQualifiedInventoryCannotBeBorrowedForChangedReviewOriginal() throws Exception {
        var d=data(1);var q=qualification(d);assertNull(actual(d,q));
        d.data().material().withObject("inFrameReview").withObject("compositeEffectAssessment").withObject("originalReview").put("structuredOriginalUtf8","replacement");
        assertEquals("PVS1_SOURCE_QUALIFICATION_SPLICE_MATERIAL_UNBOUND",actual(d,q));
    }
}
