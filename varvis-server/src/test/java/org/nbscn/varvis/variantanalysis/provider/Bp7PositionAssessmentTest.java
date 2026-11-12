package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed genomic geometry is the oracle; preparation metadata is explicitly synthetic. */
class Bp7PositionAssessmentTest {
    @TempDir Path directory;
    final NativeSpliceAiRuntimeTest fixture = new NativeSpliceAiRuntimeTest();
    final ObjectMapper mapper = fixture.f.mapper;
    final RuntimeEvidenceV2Adapter adapter = fixture.f.adapter;
    final Bp7PositionAssessment assessment = new Bp7PositionAssessment(mapper);
    record Input(ObjectNode plan, VariantInterpretation candidate) { }
    ObjectNode gencodeIdentity() {
        return mapper.createObjectNode().put("method", "EXACT_GENCODE_LOCAL_ANNOTATION").put("version", "1.0.0")
                .put("manifestSha256", "a".repeat(64)).put("sourceGtfSha256", "b".repeat(64)).put("release", 38);
    }
    void coding(ObjectNode model) {
        int cursor = 1;
        var exons = new java.util.ArrayList<ObjectNode>(); model.path("exons").forEach(e -> exons.add((ObjectNode) e));
        exons.sort(java.util.Comparator.comparingInt(e -> e.path("genomicStart").asInt()));
        if (model.path("strand").asInt() == -1) java.util.Collections.reverse(exons);
        for (var exon : exons) {
            int length = exon.path("genomicEnd").asInt() - exon.path("genomicStart").asInt() + 1;
            exon.put("cdsStart", cursor).put("cdsEnd", cursor + length - 1); cursor += length;
        }
        model.put("cdsLength", cursor - 1);
    }
    Input input(int strand, String consequence, int position, int[][] spans) throws Exception {
        var source = fixture.input(); var context = source.context().deepCopy();
        var allele = (ObjectNode) context.at("/variant/evidenceQueryAllele"); allele.put("position", position);
        for (String field : List.of("inputAllele", "normalizedAllele")) ((ObjectNode) context.path("variant")).set(field, allele.deepCopy());
        ((ObjectNode) context.path("variant")).put("alleleId", adapter.canonicalAlleleId(allele, context.at("/variant/normalization")));
        context.put("contextId", adapter.canonicalContextId(context));
        var key = new NormalizedVariantKey("GRCh38", allele.path("contig").asText(), position, allele.path("reference").asText(), allele.path("alternate").asText());
        var qc = (ObjectNode) mapper.valueToTree(source.candidate().getObservation()); qc.set("key", mapper.valueToTree(key));
        var candidate = new VariantInterpretation("bp7-geometry", mapper.treeToValue(qc, VariantQcObservation.class), List.of(), List.of());
        var tx = (ObjectNode) mapper.valueToTree(source.candidate().getTranscriptAnnotations().get(0));
        tx.put("consequence", consequence).put("transcriptAccession", context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asInt()).putNull("hgvsCoding");
        candidate.applyTranscriptAnnotations(List.of(mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(tx, TranscriptConsequence.class)));
        var model = source.model().deepCopy().put("strand", strand).put("assembly", "GRCh38")
                .put("hgncId", context.at("/gene/hgncId").asText()).put("rawGtfSha256", "c".repeat(64));
        var exons = model.putArray("exons");
        for (int[] span : spans) exons.addObject().put("genomicStart", span[0]).put("genomicEnd", span[1]);
        coding(model);
        var plan = mapper.createObjectNode().put("schemaVersion", "selected-prediction-plan-v1").put("status", "PREPARED").put("candidateId", candidate.getCandidateId());
        plan.set("context", context); plan.set("queryAllele", allele.deepCopy()); plan.putObject("sourceProjection").put("fixtureAttribution", "SYNTHETIC_PREPARED_SOURCE");
        var nativePlan = plan.putObject("nativeSplicePreparation").put("status", "PREPARED"); nativePlan.set("annotation", model);
        nativePlan.putObject("identity").put("method", "NATIVE_RAW_SELECTED_GENCODE_V1").put("version", NativeSpliceAiRuntime.VERSION).set("gencode", gencodeIdentity());
        nativePlan.putObject("referenceWindow").putObject("qualifiedReference").set("expectedSha256", context.at("/variant/normalization/referenceSequenceSha256"));
        bind(plan); return new Input(plan, candidate);
    }
    void bind(ObjectNode plan) {
        var nativePlan = (ObjectNode) plan.path("nativeSplicePreparation");
        var selected = NativeSpliceAiRuntime.selectedAnnotation(mapper, nativePlan.path("annotation")); nativePlan.set("selectedAnnotation", selected);
        var query = mapper.createObjectNode(); query.set("allele", plan.path("queryAllele")); query.set("annotation", selected);
        nativePlan.put("rawQueryId", "selected-" + adapter.canonicalSha256(query));
    }
    ObjectNode result(Input i) { return assessment.assess(i.plan(), i.candidate()); }
    void hgvs(Input i, String suffix) throws Exception {
        var old = i.candidate().getTranscriptAnnotations().get(0);
        var tx = (ObjectNode) mapper.valueToTree(old); tx.put("hgvsCoding", old.getVersionedTranscriptId() + ":c." + suffix);
        i.candidate().applyTranscriptAnnotations(List.of(mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(tx, TranscriptConsequence.class)));
    }
    void proof(Input i, long start, long end, int strand) {
        var old = i.candidate().getTranscriptAnnotations().get(0); var key = i.candidate().getObservation().getKey();
        long left = key.getPosition() - start, right = end - key.getPosition(); String nearest = left == right ? "start_end" : left < right ? "start" : "end";
        var proof = new SynonymousExonPosition("GRCh38", key.getChromosome(), key.getPosition(), key.getReference(), key.getAlternate(), old.getVersionedTranscriptId(),
                "ENSE000001", strand, start, end, "ENSE000001|" + Math.min(left, right) + "|" + nearest + "|" + (end - start + 1), old.getVersion(), SynonymousExonPosition.PLUGIN_SHA256);
        i.candidate().applyTranscriptAnnotations(List.of(old.withSynonymousExonPosition(proof)));
    }
    @ParameterizedTest @CsvSource({
            "1,20000,EXCLUDED,0,9", "1,20001,ELIGIBLE,1,8", "1,20006,ELIGIBLE,6,3",
            "1,20007,EXCLUDED,7,2", "1,20008,EXCLUDED,8,1", "1,20009,EXCLUDED,9,0",
            "-1,20009,EXCLUDED,0,9", "-1,20008,ELIGIBLE,1,8", "-1,20003,ELIGIBLE,6,3",
            "-1,20002,EXCLUDED,7,2", "-1,20001,EXCLUDED,8,1", "-1,20000,EXCLUDED,9,0"})
    void synonymousBoundariesFollowTranscriptDirection(int strand, int pos, String status, long fromStart, long fromEnd) throws Exception {
        var i = input(strand, "synonymous_variant", pos, new int[][]{{20000,20009}}); var out = result(i);
        assertEquals(status, out.path("status").asText(), out.toPrettyString());
        assertEquals(fromStart, out.at("/geometry/zeroBasedDistanceFromExonStart").asLong());
        assertEquals(fromEnd, out.at("/geometry/zeroBasedDistanceFromExonEnd").asLong());
        assertFalse(out.path("finalEvaluationProduced").asBoolean()); assertEquals(0, out.path("providerCalls").asInt());
    }
    @ParameterizedTest @CsvSource({
            "1,20006,EXCLUDED,6,94", "1,20007,ELIGIBLE,7,93", "1,20079,ELIGIBLE,79,21", "1,20080,EXCLUDED,80,20",
            "-1,20094,EXCLUDED,6,94", "-1,20093,ELIGIBLE,7,93", "-1,20021,ELIGIBLE,79,21", "-1,20020,EXCLUDED,80,20"})
    void intronicBoundariesCheckBothEnds(int strand, int pos, String status, long donor, long acceptor) throws Exception {
        var i = input(strand, "intron_variant", pos, new int[][]{{19900,20000},{20100,20200}}); var out = result(i);
        assertEquals(status, out.path("status").asText(), out.toPrettyString());
        assertEquals(donor, out.at("/geometry/donorDistance").asLong()); assertEquals(acceptor, out.at("/geometry/acceptorDistance").asLong());
        assertEquals(20001, out.at("/geometry/intronStart").asInt()); assertEquals(20099, out.at("/geometry/intronEnd").asInt());
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void shortIntronsNeverUseOnePassingSideToHideTheOther(int strand) throws Exception {
        for (int pos = 20001; pos < 20020; pos++) {
            var out = result(input(strand, "intron_variant", pos, new int[][]{{19900,20000},{20020,20100}}));
            assertEquals("EXCLUDED", out.path("status").asText(), "position=" + pos + out);
        }
        for (int length = 1; length <= 4; length++) for (int pos = 20000; pos < 20000 + length; pos++) {
            assertEquals("EXCLUDED", result(input(strand, "synonymous_variant", pos, new int[][]{{20000,19999+length}})).path("status").asText());
        }
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void exonOrderDoesNotChangeGeometryAndOrdinalsAreNeverCoordinates(int strand) throws Exception {
        var first = input(strand, "synonymous_variant", 20005, new int[][]{{19900,19920},{20000,20020},{20100,20120}});
        var reversed = input(strand, "synonymous_variant", 20005, new int[][]{{20100,20120},{20000,20020},{19900,19920}});
        assertEquals(result(first).path("geometry"), result(reversed).path("geometry"));
        assertEquals(2, result(first).at("/geometry/transcriptExonOrdinal").asInt());
        var out = result(input(strand, "intron_variant", 20050, new int[][]{{19900,19920},{20000,20020},{20100,20120}}));
        assertEquals(strand == 1 ? 2 : 1, out.at("/geometry/transcriptIntronOrdinal").asInt());
    }
    @ParameterizedTest @CsvSource({"synonymous_variant,20050,BP7_CONSEQUENCE_STRUCTURE_CONFLICT", "intron_variant,20005,BP7_CONSEQUENCE_STRUCTURE_CONFLICT",
            "synonymous_variant,19899,BP7_POSITION_OUTSIDE_TRANSCRIPT", "intron_variant,20201,BP7_POSITION_OUTSIDE_TRANSCRIPT"})
    void geometryDisagreesWithAnnotationsExplicitly(String term, int pos, String reason) throws Exception {
        var out = result(input(1, term, pos, new int[][]{{19900,20020},{20100,20200}}));
        assertEquals("NOT_EVALUATED", out.path("status").asText()); assertEquals(reason, out.path("reasonCode").asText());
    }
    @ParameterizedTest @CsvSource({"1,100+25A>G,MATCHED", "1,101-75A>G,MATCHED", "-1,100+75A>G,MATCHED", "-1,101-25A>G,MATCHED"})
    void hgvsSignedOffsetIsCrossCheckedAgainstTheCorrectStrand(int strand, String hgvs, String expected) throws Exception {
        var i = input(strand, "intron_variant", 20025, new int[][]{{19900,20000},{20100,20200}}); hgvs(i, hgvs);
        assertEquals(expected, result(i).path("hgvsOffsetCrossCheck").asText(), result(i).toPrettyString());
        hgvs(i, "100+26A>G"); assertEquals("BP7_HGVS_POSITION_CONFLICT", result(i).path("reasonCode").asText());
        hgvs(i, "100+25_100+26del"); assertEquals("BP7_HGVS_POSITION_CONFLICT", result(i).path("reasonCode").asText());
    }
    @Test void vepPositionProofIsOptionalButCannotContradictTheExactStructure() throws Exception {
        var i = input(1, "synonymous_variant", 20005, new int[][]{{20000,20009}});
        proof(i,20000,20009,1); assertEquals("MATCHED", result(i).path("vepPositionCrossCheck").asText());
        proof(i,19999,20009,1); assertEquals("BP7_VEP_POSITION_CONFLICT", result(i).path("reasonCode").asText());
        proof(i,20000,20009,-1); assertEquals("BP7_VEP_POSITION_CONFLICT", result(i).path("reasonCode").asText());
        hgvs(i,"100+25A>G");
        proof(i,20000,20009,1); assertEquals("BP7_HGVS_POSITION_CONFLICT", result(i).path("reasonCode").asText());
    }
    @ParameterizedTest @CsvSource({"PIN,BP7_POSITION_SOURCE_IDENTITY_REQUIRED", "RAW_GTF,BP7_POSITION_SOURCE_IDENTITY_REQUIRED",
            "ASSEMBLY,BP7_POSITION_SOURCE_CONTEXT_MISMATCH", "GENE,BP7_POSITION_SOURCE_CONTEXT_MISMATCH",
            "TX,BP7_SELECTED_ANNOTATION_MISMATCH", "REFERENCE,BP7_REFERENCE_BINDING_INVALID", "QUERY,BP7_NATIVE_QUERY_IDENTITY_INVALID",
            "EXONS,NATIVE_SELECTED_EXONS_INVALID", "FLOAT,NATIVE_SELECTED_EXONS_INVALID", "OVERLAP,NATIVE_SELECTED_EXONS_INVALID",
            "CODING,BP7_CODING_POSITION_UNAVAILABLE", "UTR,BP7_CONSEQUENCE_CODING_CONFLICT"})
    void badOrIncompatibleSupplyIsNotEvaluated(String change, String expected) throws Exception {
        var i = input(1,"synonymous_variant",20005,new int[][]{{20000,20009}});
        var nativePlan = (ObjectNode) i.plan().path("nativeSplicePreparation"); var model = (ObjectNode) nativePlan.path("annotation");
        switch(change) {
            case "PIN" -> ((ObjectNode) nativePlan.at("/identity/gencode")).remove("manifestSha256");
            case "RAW_GTF" -> model.remove("rawGtfSha256");
            case "ASSEMBLY" -> model.put("assembly","GRCh37");
            case "GENE" -> model.put("hgncId","HGNC:99999");
            case "TX" -> { model.put("transcript","ENST00000000001.99"); bind(i.plan()); }
            case "REFERENCE" -> ((ObjectNode) nativePlan.at("/referenceWindow/qualifiedReference")).put("expectedSha256","0".repeat(64));
            case "QUERY" -> nativePlan.put("rawQueryId","other");
            case "EXONS" -> model.putArray("exons");
            case "FLOAT" -> ((ObjectNode) model.at("/exons/0")).put("genomicStart",20000.5);
            case "OVERLAP" -> model.withArray("exons").addObject().put("genomicStart",20005).put("genomicEnd",20015);
            case "CODING" -> model.remove("cdsLength");
            case "UTR" -> model.put("cdsLength",3);
        }
        var out = result(i); assertEquals("NOT_EVALUATED",out.path("status").asText());
        // Floating identity values are rejected by the existing canonical identity boundary before geometry.
        assertEquals("FLOAT".equals(change) ? "BP7_POSITION_INPUT_INVALID" : expected,out.path("reasonCode").asText(),out.toPrettyString());
    }
    @Test void completeGeometryRoundTripsWithoutMutatingAnyInput() throws Exception {
        var i = input(-1,"intron_variant",20050,new int[][]{{19900,20000},{20100,20200}});
        byte[] plan=mapper.writeValueAsBytes(i.plan()), candidate=mapper.writeValueAsBytes(i.candidate());
        var out=result(i); assertEquals(out,result(i));
        assertArrayEquals(mapper.writeValueAsBytes(out),mapper.writeValueAsBytes(assessment.assess((ObjectNode)mapper.readTree(plan),i.candidate())));
        assertArrayEquals(plan,mapper.writeValueAsBytes(i.plan())); assertArrayEquals(candidate,mapper.writeValueAsBytes(i.candidate()));
        var missing = i.plan().deepCopy(); missing.remove("nativeSplicePreparation");
        assertEquals("BP7_NATIVE_PREPARATION_UNAVAILABLE",assessment.assess(missing,i.candidate()).path("reasonCode").asText());
    }
    @Test void existingPinnedGencodeReaderSuppliesGeometryWithoutReadingReferenceSequence() throws Exception {
        var f = new GencodeTranscriptIndexTest(); f.root = directory; f.setup();
        f.row.put("geneId", "ENSG000001"); var index = f.write();
        var source = f.input.fixture(); var model = index.annotation(source.context());
        var candidate = source.candidate();
        var tx = (ObjectNode) mapper.valueToTree(candidate.getTranscriptAnnotations().get(0));
        tx.put("consequence", "synonymous_variant").put("geneId", "ENSG000001");
        candidate.applyTranscriptAnnotations(List.of(mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(tx, TranscriptConsequence.class)));
        var prepared = input(1, "synonymous_variant", candidate.getObservation().getKey().getPosition(), new int[][]{{96,104}}).plan();
        prepared.put("candidateId", candidate.getCandidateId()); prepared.set("context", source.context().deepCopy());
        prepared.set("queryAllele", source.context().at("/variant/evidenceQueryAllele").deepCopy());
        var nativePlan = (ObjectNode) prepared.path("nativeSplicePreparation"); nativePlan.set("annotation", model);
        ((ObjectNode) nativePlan.path("identity")).set("gencode", index.identity());
        ((ObjectNode) nativePlan.at("/referenceWindow/qualifiedReference")).set("expectedSha256", source.context().at("/variant/normalization/referenceSequenceSha256"));
        bind(prepared);
        var out = assessment.assess(prepared, candidate);
        assertEquals("ELIGIBLE", out.path("status").asText(), out.toPrettyString());
        assertEquals(4, out.at("/geometry/zeroBasedDistanceFromExonStart").asInt());
        assertEquals(4, out.at("/geometry/zeroBasedDistanceFromExonEnd").asInt());
        assertEquals(index.identity().path("manifestSha256"), out.at("/source/gencode/manifestSha256"));
        assertEquals(0, f.bases.get());
    }
    @ParameterizedTest @CsvSource({"0.0,READY_FOR_EXPERIMENTAL_CONFLICT_CHECK", "0.15,DEPENDENCY_NOT_SATISFIED", "0.2,DEPENDENCY_NOT_SATISFIED"})
    void joinsActualSavedPredictionWithoutAdditionalModelCallsOrFinalAdoption(String score, String status) throws Exception {
        var f=new Bp7PredictionDependencyTest(); f.directory=directory;
        var saved=f.saved(score,"synonymous_variant",false); var nativePlan=(ObjectNode)saved.plan().path("nativeSplicePreparation");
        ((ObjectNode)nativePlan.path("identity")).set("gencode",gencodeIdentity());
        var model=(ObjectNode)nativePlan.path("annotation"); model.put("assembly","GRCh38").put("hgncId",saved.plan().at("/context/gene/hgncId").asText()).put("rawGtfSha256","c".repeat(64)); coding(model);
        saved.row().set("plan",saved.plan().deepCopy()); ((ObjectNode)saved.row().path("nativeSpliceResult")).set("plan",nativePlan.deepCopy()); f.seal(saved.row());
        var out=assessment.inspect(saved.plan(),saved.candidate(),saved.row()); assertEquals(status,out.path("status").asText(),out.toPrettyString());
        assertEquals("ELIGIBLE",out.at("/position/status").asText()); assertFalse(out.path("finalEvaluationProduced").asBoolean());
        assertEquals("NONE",out.path("appliedStrength").asText()); assertEquals("PENDING",out.path("experimentalConflictCheck").asText());
        assertEquals(out,assessment.inspect(saved.plan(),saved.candidate(),saved.row())); assertEquals(1,saved.calls().get());
        if ("0.0".equals(score)) {
            var i = new Input(saved.plan(), saved.candidate()); proof(i, 19001, 21000, 1);
            var conflict = assessment.inspect(saved.plan(), saved.candidate(), saved.row());
            assertEquals("READY_FOR_POSITION_AND_CONFLICT_CHECKS", conflict.at("/predictionDependency/status").asText());
            assertEquals("NOT_EVALUATED", conflict.path("status").asText());
            assertEquals("BP7_VEP_POSITION_CONFLICT", conflict.path("reasonCode").asText());
            assertEquals(1, saved.calls().get());
        }
        String destination=System.getProperty("bp7.position.evidence.dir");
        if(destination!=null) java.nio.file.Files.write(Path.of(destination).resolve("computational-"+score+".json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(out));
    }
}
