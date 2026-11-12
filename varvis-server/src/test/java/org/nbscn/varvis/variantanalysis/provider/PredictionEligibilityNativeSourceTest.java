package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** TEST_ONLY native-shaped memory snapshots; no DB, production supplier or scientific acquisition. */
class PredictionEligibilityNativeSourceTest {
    @Test void nativeSourceReachesMissingContextWithoutChangingOriginalsOrRanking() throws Exception {
        var f = nativeFixture();
        byte[] original = f.mapper.writeValueAsBytes(f.docs.get("publication"));
        JsonNode annotation = annotation(f, 0).deepCopy();
        var input = f.input(); assertEquals("", input.issue());
        var receipts = new PredictionApplicationEligibility(f.mapper).assess(input);
        assertEquals(4, receipts.size());
        for (var receipt : receipts) {
            JsonNode out = receipt.json();
            assertEquals("PASS", out.at("/stages/0/status").asText());
            assertEquals("EXPLICIT_CONTEXT_MISSING", out.path("reasonCode").asText());
            assertEquals("BLOCKED", out.at("/stages/1/status").asText());
            for (int stage = 2; stage <= 6; stage++) assertEquals("NOT_CHECKED", out.at("/stages/" + stage + "/status").asText());
            assertEquals("COMPLETE", out.at("/stages/7/status").asText());
            assertEquals("NONE", out.path("appliedStrength").asText());
            for (String field : List.of("applicationReady", "formalEnabled", "criterionEvaluationCreated")) assertFalse(out.path(field).asBoolean());
            assertEquals(SavedPredictionApplicationInputs.NATIVE_PUBLICATION, out.at("/publicationProjection/adapterVersion").asText());
            assertEquals("SAVED_RUN_CANDIDATE_ORDER", out.at("/publicationProjection/rankSource").asText());
            assertEquals(PredictionCalibration.sha256(original), out.at("/publicationProjection/publicationByteSha256").asText());
        }
        ObjectNode view = SavedPredictionApplicationInputs.publicationCandidate(input, "TEST_ONLY_1");
        assertEquals(2, view.path("rank").asInt()); // Both engine declaredRanks are 1.
        assertFalse(view.has("hgncId"));
        assertEquals("/candidates/1/published", view.at("/sourceProjection/originalPointer").asText());
        view.withArray("annotations").removeAll(); view.put("rank", 99);
        ((ObjectNode) input.document("publication")).remove("candidates");
        assertArrayEquals(original, f.mapper.writeValueAsBytes(input.document("publication")));
        assertArrayEquals(original, f.mapper.writeValueAsBytes(f.docs.get("publication")));
        assertEquals(annotation, annotation(f, 0));
    }

    @ParameterizedTest @ValueSource(strings = {"version", "adapter", "flatConflict", "conversion", "conversionType", "assembly",
            "array", "element", "duplicate", "publishedId", "identityId", "engineId", "sourceBlocker", "annotations", "annotationElement",
            "rank", "rankType", "order", "settingsRank", "sourcePin"})
    void nativeShapeAndFormalOrderCannotBeSubstituted(String kind) throws Exception {
        var f = nativeFixture(); ObjectNode pub = f.docs.get("publication"), row = nativeRow(f, 0);
        switch (kind) {
            case "version" -> pub.put("version", "acmg-execution-source-v2");
            case "adapter" -> f.settings.put("publicationAdapterVersion", "unknown");
            case "flatConflict" -> f.settings.put("publicationAdapterVersion", SavedPredictionApplicationInputs.FLAT_PUBLICATION);
            case "conversion" -> pub.put("conversionApplied", true);
            case "conversionType" -> pub.put("conversionApplied", "false");
            case "assembly" -> pub.put("inputAssembly", "GRCh37");
            case "array" -> pub.putObject("candidates");
            case "element" -> pub.withArray("candidates").set(0, f.mapper.nullNode());
            case "duplicate" -> pub.withArray("candidates").add(row.deepCopy());
            case "publishedId" -> row.withObject("/published").put("candidateId", "different");
            case "identityId" -> row.withObject("/published/candidateIdentity").remove("candidateId");
            case "engineId" -> row.withObject("/engineCandidate").put("candidateId", "different");
            case "sourceBlocker" -> row.putNull("sourceBlocker");
            case "annotations" -> row.withObject("/published").putObject("transcriptAnnotations");
            case "annotationElement" -> row.withObject("/published").withArray("transcriptAnnotations").set(0, f.mapper.nullNode());
            case "rank" -> ((ObjectNode) f.docs.get("run").path("candidates").get(0)).put("rank", 2);
            case "rankType" -> ((ObjectNode) f.docs.get("run").path("candidates").get(0)).put("rank", 1.0);
            case "order" -> f.docs.get("run").withArray("candidateIds").set(0, f.mapper.getNodeFactory().textNode("TEST_ONLY_1"));
            case "settingsRank" -> f.candidate(0).put("rank", "1");
            case "sourcePin" -> { }
            default -> fail();
        }
        resealNative(f);
        if (kind.equals("sourcePin")) { bindSource(f, "0".repeat(64)); f.seal(); }
        JsonNode out = assertDoesNotThrow(f::assess).get(0).json(); rejectedAtS0(out);
        if (List.of("version", "adapter", "flatConflict", "conversion", "conversionType", "assembly").contains(kind))
            assertEquals("UNSUPPORTED", out.path("status").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"positionString", "positionFraction", "positionOverflow", "ordinalZero", "altZero", "altRange",
            "alternatesObject", "alternateNumber", "alternateEmpty", "alternateDelimiter", "missingRecord", "callOrdinal", "publishedAlt",
            "callAssembly", "callMissing", "alleleMismatch"})
    void nativeInputRequiresExactTypedRecordWitness(String kind) throws Exception {
        var f = nativeFixture(); ObjectNode row = nativeRow(f, 0), record = row.withObject("/matchedRecord");
        switch (kind) {
            case "positionString" -> record.put("position", "100000");
            case "positionFraction" -> record.put("position", 100000.0);
            case "positionOverflow" -> record.put("position", Long.MAX_VALUE);
            case "ordinalZero" -> record.put("recordOrdinal", 0);
            case "altZero" -> record.put("selectedAltIndex", 0);
            case "altRange" -> record.put("selectedAltIndex", 2);
            case "alternatesObject" -> record.putObject("alternates");
            case "alternateNumber" -> record.withArray("alternates").set(0, f.mapper.getNodeFactory().numberNode(1));
            case "alternateEmpty" -> record.putArray("alternates").add("");
            case "alternateDelimiter" -> record.putArray("alternates").add("G,T");
            case "missingRecord" -> row.remove("matchedRecord");
            case "callOrdinal" -> row.withObject("/engineCandidate/analysisVcfCall").put("matchedRecordOrdinal", 2);
            case "publishedAlt" -> row.withObject("/published/analysisVcfCall").put("targetAlleleIndex", 2);
            case "callAssembly" -> row.withObject("/published/analysisVcfCall").put("assembly", "GRCh37");
            case "callMissing" -> row.withObject("/engineCandidate").remove("analysisVcfCall");
            case "alleleMismatch" -> record.put("reference", "C");
            default -> fail();
        }
        resealNative(f); rejectedAtS0(f.assess().get(0).json());
        assertEquals("EXPLICIT_CONTEXT_MISSING", f.result(1, "PP3").path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"full", "hgvs", "aliasOnly"})
    void sameVersionResolverWorksAtSourceAndContextStages(String form) throws Exception {
        var f = new PredictionEligibilityFixtures();
        ObjectNode annotation = (ObjectNode) f.docs.get("publication").at("/candidates/0/annotations/0");
        String tx = annotation.path("transcriptId").asText(), bare = tx.substring(0, tx.indexOf('.'));
        if (!form.equals("full")) {
            annotation.put("transcriptId", bare).put("transcriptAccession", bare).put("hgvsc", tx + ":c.1A>G");
            if (form.equals("hgvs")) annotation.put("hgvsCoding", tx + ":c.1A>G");
        }
        bindAnnotation(f, 0, annotation); f.seal();
        assertEquals("PRECONDITIONS_PASS", f.result(0, "BP4").path("status").asText());
        assertEquals("NO_SUPPORT", f.result(0, "PP3").path("status").asText());
        assertEquals(tx, SavedPredictionApplicationInputs.versionedAnnotationIdentity(annotation));
    }

    @ParameterizedTest @ValueSource(strings = {"codingAlias", "proteinAlias", "base", "version", "accessionBase", "accessionVersion",
            "missingVersion", "wrongType", "missingId", "rawDigest"})
    void versionAndAliasConflictsRemainSourceFailures(String kind) throws Exception {
        var f = nativeFixture(); ObjectNode a = annotation(f, 0); String full = f.prediction(0).at("/selection/versionedTranscriptId").asText();
        switch (kind) {
            case "codingAlias" -> a.put("hgvsc", full + ":c.2A>G");
            case "proteinAlias" -> a.put("hgvsp", "TEST_ONLY.1:p.A1G").put("hgvsProtein", "TEST_ONLY.1:p.A2G");
            case "base" -> a.put("transcriptId", "NM_12345");
            case "version" -> a.put("transcriptId", full + "9");
            case "accessionBase" -> a.put("transcriptAccession", "NM_12345.1");
            case "accessionVersion" -> a.put("transcriptAccession", full + "9");
            case "missingVersion" -> a.remove(List.of("hgvsc", "hgvsCoding"));
            case "wrongType" -> a.putObject("transcriptId");
            case "missingId" -> a.remove(List.of("transcriptId", "transcriptAccession", "hgvsc", "hgvsCoding"));
            case "rawDigest" -> a.put("additionalSavedField", "digest must change");
            default -> fail();
        }
        if (!kind.equals("rawDigest")) bindAnnotation(f, 0, a);
        resealNative(f); JsonNode out = f.assess().get(0).json(); rejectedAtS0(out);
        assertTrue(out.path("reasonCode").asText().startsWith("ANNOTATION_"), out.toString());
    }

    @Test void contextCannotSupplyItsOwnMissingIndependentGeneIdentity() throws Exception {
        var f = nativeFixture();
        var ordinary = new PredictionEligibilityFixtures();
        f.candidate(0).set("hypotheses", ordinary.candidate(0).path("hypotheses").deepCopy());
        resealNative(f);
        assertEquals("PASS", f.result(0, "BP4").at("/stages/0/status").asText());
        assertEquals("CONTEXT_GENE_IDENTITY_UNVERIFIED", f.result(0, "BP4").path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"formatted", "outerPin"})
    void originalPublicationBytePinCannotBeReplaced(String kind) throws Exception {
        var f = nativeFixture(); ObjectNode pub = f.docs.get("publication");
        if (kind.equals("formatted")) {
            byte[] formatted = f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pub);
            f.artifacts.put("publication", new SavedPredictionApplicationInputs.Artifact(formatted, pub));
        } else {
            Map<String, String> pins = new LinkedHashMap<>();
            for (var entry : f.docs.entrySet()) pins.put(entry.getKey(), PredictionCalibration.sha256(f.mapper.writeValueAsBytes(entry.getValue())));
            ObjectNode wrapper = f.mapper.createObjectNode().put("snapshot_json", f.mapper.writeValueAsString(pub));
            pins.put("publication", PredictionCalibration.sha256(f.mapper.writeValueAsBytes(wrapper)));
            f.roots = new SavedPredictionApplicationInputs.Supply("TEST_ONLY", pins, f.hash.canonicalSha256(f.settings));
        }
        assertEquals("SOURCE_BYTES_DIGEST_MISMATCH", f.assess().get(0).json().path("reasonCode").asText());
    }

    static PredictionEligibilityFixtures nativeFixture() throws Exception {
        var f = new PredictionEligibilityFixtures();
        f.settings.put("publicationAdapterVersion", SavedPredictionApplicationInputs.NATIVE_PUBLICATION);
        ObjectNode pub = f.docs.get("publication"); ArrayNode flat = pub.withArray("candidates").deepCopy();
        pub.remove("executionSourceSha256"); pub.put("version", "acmg-execution-source-v1")
                .put("inputAssembly", "GRCh38").put("analysisAssembly", "GRCh38").put("conversionApplied", false);
        ArrayNode rows = pub.putArray("candidates");
        for (int i = 0; i < flat.size(); i++) {
            JsonNode old = flat.get(i); String id = old.path("candidateId").asText();
            ObjectNode row = rows.addObject().put("candidateId", id);
            ObjectNode published = row.putObject("published").put("candidateId", id).put("rankingScore", 1.25).put("totalScore", 0.75);
            published.putObject("candidateIdentity").put("candidateId", id).put("declaredRank", 1);
            published.putObject("observation").set("key", old.path("queryAllele").deepCopy());
            published.set("transcriptAnnotations", old.path("annotations").deepCopy());
            ObjectNode call = published.putObject("analysisVcfCall").put("schemaVersion", "analysis-vcf-call-v1")
                    .put("matchStatus", "MATCHED").put("assembly", "GRCh38").put("matchedRecordOrdinal", i + 1).put("targetAlleleIndex", 1)
                    .put("artifactRole", "EXOMISER_ANALYSIS_VCF").put("provenance", "ANALYSIS_VCF_EXACT_ALLELE");
            row.putObject("engineCandidate").put("candidateId", id).set("analysisVcfCall", call.deepCopy());
            row.putObject("matchedRecord").put("contig", "chr1").put("position", 100000 + i).put("reference", "A")
                    .put("selectedAltIndex", 1).put("recordOrdinal", i + 1).putArray("alternates").add("G");
            ObjectNode annotation = annotation(f, i);
            String full = annotation.path("transcriptId").asText(), bare = full.substring(0, full.indexOf('.'));
            annotation.put("transcriptId", bare).put("transcriptAccession", bare).put("hgvsCoding", full + ":c.1A>G").put("hgvsc", full + ":c.1A>G");
            bindAnnotation(f, i, annotation); f.candidate(i).putArray("hypotheses");
        }
        resealNative(f); return f;
    }

    private static ObjectNode nativeRow(PredictionEligibilityFixtures f, int index) { return (ObjectNode) f.docs.get("publication").path("candidates").get(index); }
    private static ObjectNode annotation(PredictionEligibilityFixtures f, int index) { return (ObjectNode) nativeRow(f, index).at("/published/transcriptAnnotations/0"); }
    private static void bindAnnotation(PredictionEligibilityFixtures f, int index, JsonNode annotation) {
        String digest = f.hash.canonicalSha256(annotation);
        f.prediction(index).withObject("/selection").put("annotationSourceSha256", digest);
        f.prediction(index).withObject("/associationInput").put("annotationSourceSha256", digest);
    }
    private static void bindSource(PredictionEligibilityFixtures f, String digest) {
        for (String role : List.of("run", "parent")) f.docs.get(role).put("executionSourceSha256", digest);
        f.docs.get("request").withObject("/declaration/calibration").put("executionSourceSha256", digest);
        for (int i = 0; i < 2; i++) f.prediction(i).withObject("/associationInput").put("executionSourceSha256", digest);
    }
    static void resealNative(PredictionEligibilityFixtures f) throws Exception {
        bindSource(f, PredictionCalibration.sha256(f.mapper.writeValueAsBytes(f.docs.get("publication")))); f.seal();
    }
    private static void rejectedAtS0(JsonNode out) {
        assertNotEquals("PASS", out.at("/stages/0/status").asText());
        assertEquals("NOT_CHECKED", out.at("/stages/1/status").asText());
        assertFalse(out.path("criterionEvaluationCreated").asBoolean());
        assertEquals("NONE", out.path("appliedStrength").asText());
    }
}
