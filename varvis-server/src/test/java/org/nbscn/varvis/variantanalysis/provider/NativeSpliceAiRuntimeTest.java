package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpliceAiRuntimeTest {
    @TempDir Path temporary;
    final PredictionClauseRuntimeTest f = new PredictionClauseRuntimeTest();

    record Fixture(ObjectNode context, VariantInterpretation candidate, ObjectNode model) { }
    Fixture input() throws Exception {
        var original = f.alpha("0.1"); var c = original.context().deepCopy();
        var allele = (ObjectNode) c.at("/variant/evidenceQueryAllele"); allele.put("position", 20000);
        for (String name : List.of("inputAllele", "normalizedAllele")) ((ObjectNode) c.path("variant")).set(name, allele.deepCopy());
        ((ObjectNode) c.path("variant")).put("alleleId", f.adapter.canonicalAlleleId(allele, c.at("/variant/normalization")));
        c.put("contextId", f.adapter.canonicalContextId(c));
        var key = new NormalizedVariantKey("GRCh38", allele.path("contig").asText(), 20000, allele.path("reference").asText(), allele.path("alternate").asText());
        var qc = (ObjectNode) f.mapper.valueToTree(original.candidate().getObservation()); qc.set("key", f.mapper.valueToTree(key));
        var candidate = new VariantInterpretation("native-fixture", f.mapper.treeToValue(qc, VariantQcObservation.class), List.of(), List.of());
        candidate.applyTranscriptAnnotations(original.candidate().getTranscriptAnnotations());
        var tx = candidate.getTranscriptAnnotations().get(0);
        var model = f.mapper.createObjectNode().put("transcript", tx.getVersionedTranscriptId()).put("geneSymbol", tx.getGeneSymbol())
                .put("geneId", tx.getGeneId()).put("contig", key.getChromosome()).put("strand", 1);
        model.putArray("exons").addObject().put("genomicStart", 19000).put("genomicEnd", 21000);
        return new Fixture(c, candidate, model);
    }
    PredictionSpecializationGuard guard(String gene) throws Exception {
        var data = f.mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA);
        data.putObject("metadata").put("status", "COMPLETE").put("entryCount", 1);
        data.putArray("entries").addObject().putObject("scope").putArray("hgncIds").add(gene);
        Path path = temporary.resolve("catalog.json"); Files.write(path, f.mapper.writeValueAsBytes(data));
        return PredictionSpecializationGuard.load(f.mapper, path, CodingTranscriptMaterialSupply.sha(Files.readAllBytes(path)));
    }
    ObjectNode fact(Fixture i) {
        var row = f.mapper.createObjectNode().put("status", "HIT").put("exactAlleleMatch", true);
        row.set("selectedAnnotation", NativeSpliceAiRuntime.selectedAnnotation(f.mapper, i.model));
        row.put("annotationFileSha256", "a".repeat(64));
        row.set("allele", i.context.at("/variant/evidenceQueryAllele"));
        row.putObject("referenceWindow").put("start", 10001).put("length", 19999)
                .put("sha256", CodingTranscriptMaterialSupply.sha(i.candidate.getObservation().getKey().getReference().repeat(19999)));
        var ann = row.putArray("annotationRows").addObject().put("geneSymbol", i.model.path("geneSymbol").asText())
                .put("contig", i.model.path("contig").asText()).put("strand", "+").put("start", 19000).put("end", 21000);
        ann.putArray("exons").addArray().add(19000).add(21000);
        var score = row.putArray("annotations").addObject().put("geneSymbol", i.model.path("geneSymbol").asText())
                .put("allele", i.candidate.getObservation().getKey().getAlternate());
        var ds = score.putObject("deltaScores"); var dp = score.putObject("deltaPositions");
        for (String kind : List.of("AG", "AL", "DG", "DL")) { ds.put("DS_" + kind, "0.00"); dp.put("DP_" + kind, -10); }
        return row;
    }
    NativeSpliceAiRuntime runtime(Fixture i, ObjectNode fact, AtomicInteger calls, String spec) throws Exception {
        return new NativeSpliceAiRuntime(f.mapper, c -> i.model.deepCopy(), (c,s,n) -> new CodingTranscriptMaterialSupply.ReferenceSpan(
                i.candidate.getObservation().getKey().getReference().repeat(n), f.mapper.createObjectNode().put("expectedSha256", i.context.at("/variant/normalization/referenceSequenceSha256").asText())),
                guard(spec), f.mapper.createObjectNode(), request -> {
                    calls.incrementAndGet(); assertEquals(1, request.path("variants").size());
                    var output = f.mapper.createObjectNode().put("status", "SUCCEEDED").put("annotationFileSha256", "a".repeat(64));
                    assertEquals("spliceai-native-selected-request-v1", request.path("requestSchemaVersion").asText());
                    assertEquals(NativeSpliceAiRuntime.selectedAnnotation(f.mapper, i.model), request.at("/variants/0/selectedAnnotation"));
                    var value = fact.deepCopy(); value.set("queryId", request.at("/variants/0/queryId"));
                    output.putArray("results").add(value); return output;
                });
    }
    @Test void exactTranscriptAndQualifiedWindowCalibrateOnceAndDoNotMutateCandidate() throws Exception {
        var i = input(); var calls = new AtomicInteger(); var runtime = runtime(i, fact(i), calls, "HGNC:99999");
        var before = f.mapper.valueToTree(i.candidate); var one = runtime.prepare(i.context, i.candidate, false);
        var two = runtime.prepare(i.context, i.candidate, false);
        assertEquals("PREPARED", one.snapshot().path("status").asText(), one.snapshot().toString()); assertEquals(0, calls.get());
        runtime.compute(List.of(one, two)); var result = runtime.evaluate(one);
        assertEquals("CALIBRATED", result.at("/mechanism/status").asText(), result.toPrettyString());
        assertEquals("BENIGN", result.at("/mechanism/direction").asText());
        assertEquals(result, runtime.evaluate(two)); runtime.compute(List.of(one)); assertEquals(1, calls.get());
        assertEquals(before, f.mapper.valueToTree(i.candidate));
        var mechanisms = f.mapper.createObjectNode(); mechanisms.set("SPLICING", result.path("mechanism"));
        mechanisms.putObject("PROTEIN_MISSENSE").put("status", "CALIBRATED").put("direction", "BENIGN").put("appliedStrength", "SUPPORTING");
        var appliedPlan = PredictionDevelopmentRuntime.load(f.mapper, "prediction-core-policy-v1.json"); appliedPlan.set("context", i.context);
        appliedPlan.set("effectRestrictions", PredictionCoreTest.restrictions(f.mapper, appliedPlan));
        assertEquals("MET", PredictionMechanismApplication.apply(f.mapper, appliedPlan, mechanisms, null).at("/BP4/status").asText());
        f.adapter.canonicalSha256(runtime.audit());
    }
    @ParameterizedTest @ValueSource(strings={"EXON", "STRAND", "GENE", "DUPLICATE", "REF", "DP", "SCORE", "ALLELE", "NO_RECORD", "TX_RECEIPT", "ANN_SHA"})
    void mismatchesDoNotBecomeBenign(String mutation) throws Exception {
        var i=input(); var row=fact(i);
        switch(mutation) {
            case "TX_RECEIPT" -> ((ObjectNode)row.path("selectedAnnotation")).put("transcriptId", "ENST00000000001.99");
            case "ANN_SHA" -> row.put("annotationFileSha256", "b".repeat(64));
            case "EXON" -> ((com.fasterxml.jackson.databind.node.ArrayNode)row.at("/annotationRows/0/exons/0")).set(0, f.mapper.getNodeFactory().numberNode(19001));
            case "STRAND" -> ((ObjectNode)row.at("/annotationRows/0")).put("strand", "-");
            case "GENE" -> ((ObjectNode)row.at("/annotationRows/0")).put("geneSymbol", "OTHER");
            case "DUPLICATE" -> row.withArray("annotations").add(row.at("/annotations/0").deepCopy());
            case "REF" -> ((ObjectNode)row.path("referenceWindow")).put("sha256", "0".repeat(64));
            case "DP" -> ((ObjectNode)row.at("/annotations/0/deltaPositions")).put("DP_AG", 5000);
            case "SCORE" -> ((ObjectNode)row.at("/annotations/0/deltaScores")).put("DS_AG", "1.1");
            case "ALLELE" -> ((ObjectNode)row.at("/annotations/0")).put("allele", "WRONG");
            case "NO_RECORD" -> row.put("status", "NO_RECORD").put("reasonCode", "NO_MATCHING_GENE_ANNOTATION");
        }
        var runtime=runtime(i,row,new AtomicInteger(),"HGNC:99999"); var plan=runtime.prepare(i.context,i.candidate,false);
        runtime.compute(List.of(plan)); assertEquals("NOT_EVALUATED", runtime.evaluate(plan).at("/mechanism/status").asText());
    }
    @Test void specializedOrWrongTranscriptPlansDoNotLaunch() throws Exception {
        var i=input(); var calls=new AtomicInteger(); var runtime=runtime(i,fact(i),calls,i.context.at("/gene/hgncId").asText());
        var plan=runtime.prepare(i.context,i.candidate,false); runtime.compute(List.of(plan));
        assertEquals("PREDICTION_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION", runtime.evaluate(plan).at("/mechanism/reasonCode").asText()); assertEquals(0,calls.get());
        var other=runtime(i,fact(i),calls,"HGNC:99999"); i.model.put("transcript","ENST000000.999");
        var bad=other.prepare(i.context,i.candidate,false); other.compute(List.of(bad)); assertEquals(0,calls.get());
        assertThrows(IllegalArgumentException.class,()->other.evaluate(plan));
    }

    @Test void sameAlleleDifferentVersionHasDifferentUnitAndOwnResult() throws Exception {
        var first = input(); var otherContext = first.context.deepCopy();
        otherContext.withObject("/transcript").put("version", 99);
        otherContext.put("contextId", f.adapter.canonicalContextId(otherContext));
        String txId = otherContext.at("/transcript/accession").asText() + ".99";
        var txJson = (ObjectNode) f.mapper.valueToTree(first.candidate.getTranscriptAnnotations().get(0));
        txJson.put("transcriptId", txId).put("transcriptAccession", txId);
        var copyMapper = f.mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var otherCandidate = new VariantInterpretation("other-tx", first.candidate.getObservation(), List.of(), List.of());
        otherCandidate.applyTranscriptAnnotations(List.of(copyMapper.treeToValue(txJson, TranscriptConsequence.class)));
        var otherModel = first.model.deepCopy(); otherModel.put("transcript", txId);
        var second = new Fixture(otherContext, otherCandidate, otherModel);
        var calls = new AtomicInteger();
        var runtime = new NativeSpliceAiRuntime(f.mapper, c -> c.at("/transcript/version").asInt() == 99 ? otherModel : first.model,
                (c,s,n) -> new CodingTranscriptMaterialSupply.ReferenceSpan(first.candidate.getObservation().getKey().getReference().repeat(n),
                        f.mapper.createObjectNode().put("expectedSha256", first.context.at("/variant/normalization/referenceSequenceSha256").asText())),
                guard("HGNC:99999"), f.mapper.createObjectNode(), request -> {
                    calls.incrementAndGet(); assertEquals(2, request.path("variants").size());
                    var result = f.mapper.createObjectNode().put("status", "SUCCEEDED").put("annotationFileSha256", "a".repeat(64));
                    var rows = result.putArray("results");
                    for (var input : request.path("variants")) {
                        var row = fact(txId.equals(input.path("selectedTranscriptId").asText()) ? second : first);
                        row.set("queryId", input.path("queryId"));
                        if (txId.equals(input.path("selectedTranscriptId").asText())) ((ObjectNode) row.at("/annotations/0/deltaScores")).put("DS_AG", "0.50");
                        rows.add(row);
                    }
                    return result;
                });
        var a = runtime.prepare(first.context, first.candidate, false); var b = runtime.prepare(second.context, second.candidate, false);
        assertEquals("PREPARED", b.snapshot().path("status").asText(), b.snapshot().toPrettyString());
        assertNotEquals(a.snapshot().path("rawQueryId"), b.snapshot().path("rawQueryId"));
        runtime.compute(List.of(a,b,a)); assertEquals(1, calls.get());
        assertEquals("BENIGN", runtime.evaluate(a).at("/mechanism/direction").asText());
        assertEquals("HARMFUL", runtime.evaluate(b).at("/mechanism/direction").asText());
    }

    @ParameterizedTest @ValueSource(strings={"ZERO", "OVERLAP", "FLOAT", "REVERSED", "OUTSIDE", "CLASSICAL", "STRAND"})
    void badStructuresAndCanonicalSpliceDoNotStartModel(String mutation) throws Exception {
        var i=input();
        switch(mutation) {
            case "ZERO" -> ((ObjectNode)i.model.at("/exons/0")).put("genomicStart",0);
            case "OVERLAP" -> i.model.withArray("exons").addObject().put("genomicStart",20000).put("genomicEnd",22000);
            case "FLOAT" -> ((ObjectNode)i.model.at("/exons/0")).put("genomicStart",19000.1);
            case "REVERSED" -> ((ObjectNode)i.model.at("/exons/0")).put("genomicStart",22000);
            case "OUTSIDE" -> ((ObjectNode)i.model.at("/exons/0")).put("genomicEnd",19999);
            case "STRAND" -> i.model.put("strand",0);
            case "CLASSICAL" -> {
                var tx=(ObjectNode)f.mapper.valueToTree(i.candidate.getTranscriptAnnotations().get(0)); tx.put("consequence","splice_donor_variant");
                i.candidate.applyTranscriptAnnotations(List.of(f.mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(tx,TranscriptConsequence.class)));
            }
        }
        var calls=new AtomicInteger();
        // Keep the transport unused, including when an invalid model cannot be rendered as a fact.
        var runtime=new NativeSpliceAiRuntime(f.mapper,c->i.model,(c,s,n)->null,guard("HGNC:99999"),f.mapper.createObjectNode(),r->{calls.incrementAndGet();return null;});
        var plan=runtime.prepare(i.context,i.candidate,false);runtime.compute(List.of(plan));
        assertEquals("BLOCKED",plan.snapshot().path("status").asText());assertEquals(0,calls.get());
    }

    @Test void negativeStrandGenomicExonOrderIsCanonicalAndNeverReverseComplementedTwice() throws Exception {
        var i=input();i.model.put("strand",-1);var exons=i.model.putArray("exons");
        exons.addObject().put("genomicStart",21000).put("genomicEnd",22000);exons.addObject().put("genomicStart",18000).put("genomicEnd",20500);
        var selected=NativeSpliceAiRuntime.selectedAnnotation(f.mapper,i.model);
        assertEquals("-",selected.path("strand").asText());assertEquals(18000,selected.path("start").asInt());
        assertEquals(f.mapper.readTree("[[18000,20500],[21000,22000]]"),selected.path("exons"));
    }

    @Test void moreThanFiftyDistinctUnitsRefuseWholeSelectionBeforeExecution() throws Exception {
        var i=input();var calls=new AtomicInteger();var runtime=runtime(i,fact(i),calls,"HGNC:99999");
        var plans=new java.util.ArrayList<NativeSpliceAiRuntime.Plan>();
        for(int position=20000;position<20051;position++) {
            var c=i.context.deepCopy();
            for(String field:List.of("inputAllele","normalizedAllele","evidenceQueryAllele"))c.withObject("/variant/"+field).put("position",position);
            c.withObject("/variant").put("alleleId",f.adapter.canonicalAlleleId(c.at("/variant/evidenceQueryAllele"),c.at("/variant/normalization")));
            c.put("contextId",f.adapter.canonicalContextId(c));
            var observation=(ObjectNode)f.mapper.valueToTree(i.candidate.getObservation());observation.withObject("/key").put("position",position);
            var candidate=new VariantInterpretation("limit-"+position,f.mapper.treeToValue(observation,VariantQcObservation.class),List.of(),List.of());
            candidate.applyTranscriptAnnotations(i.candidate.getTranscriptAnnotations());
            var plan=runtime.prepare(c,candidate,false);assertEquals("PREPARED",plan.snapshot().path("status").asText(),plan.snapshot().toString());plans.add(plan);
        }
        runtime.compute(plans);assertEquals(0,calls.get());
        for(var plan:plans)assertEquals("NATIVE_TOTAL_UNIT_LIMIT_EXCEEDED",runtime.evaluate(plan).at("/mechanism/reasonCode").asText());
    }
}
