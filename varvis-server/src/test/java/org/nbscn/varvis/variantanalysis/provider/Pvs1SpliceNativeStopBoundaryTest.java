package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed before production changes in PVS1_SPLICE_BOUNDARY_REVIEW_2026-11-04.md.
 * All reviews, variants and models are synthetic engineering fixtures, not actual
 * RNA observations or new scientific approvals. Reference CDS length is 600 and
 * native STOP is c598..600. Deleting exon2 c13..25 preserves native STOP at
 * altered c585..587, while the actual PTC is c49..51 (reference c62..64).
 * Only the last two reference exon boundaries vary; all decision rules stay fixed.
 */
class Pvs1SpliceNativeStopBoundaryTest {
    private final Pvs1SpliceConsequenceTest base = new Pvs1SpliceConsequenceTest();

    private Pvs1SpliceConsequenceTest.Data data(int strand, int junction) throws Exception {
        var data = base.data(strand, false, false);
        var model = data.input().model;
        var exons = model.putArray("exons");int start = 1;int[] ends = {12, 25, junction, 600};
        for (int i = 0; i < ends.length; i++) {
            int genomicStart = strand == 1 ? 2000 + i * 1000 : 20000 - i * 1000;
            exons.addObject().put("ordinal", i + 1).put("cdsStart", start).put("cdsEnd", ends[i])
                    .put("genomicStart", genomicStart).put("genomicEnd", genomicStart + ends[i] - start);
            start = ends[i] + 1;
        }
        model.put("lastJunctionCdsPosition", junction);
        data.material().put("transcriptModelSha256", base.adapter.canonicalSha256(model));
        return data;
    }

    private void bind(Pvs1SpliceConsequenceTest.Data data) {
        var d = data.input();
        d.context.put("contextId", base.adapter.canonicalContextId(d.context));
        d.policy.putObject("transcriptModelBinding").put("modelId", d.model.path("modelId").asText())
                .put("modelVersion", d.model.path("modelVersion").asText()).put("canonicalSha256", base.adapter.canonicalSha256(d.model));
        d.selection.put("contextId", d.context.path("contextId").asText()).put("policyId", d.policy.path("policyId").asText())
                .put("policyVersion", d.policy.path("policyVersion").asText()).put("policySha256", base.adapter.canonicalSha256(d.policy));
        d.selection.remove("selectionId");
        d.selection.put("selectionId", "cspec-selection-sha256-" + base.adapter.canonicalSha256(d.selection));
    }

    private String module(Pvs1SpliceConsequenceTest.Data data, ObjectNode input) {
        bind(data);var d = data.input();
        return Pvs1SpliceConsequence.resolveForIntegration(d.context, d.candidate.getTranscriptAnnotations().get(0),
                d.policy, d.model, data.material(), input, d.selection);
    }

    private void savePositive(int strand, int junction, ObjectNode input, ObjectNode result) throws IOException {
        String configured = System.getProperty("varvis.a2.boundary.topology.output");
        if (configured == null || configured.isBlank()) return;
        Path output = Path.of(configured).toAbsolutePath().normalize();Files.createDirectories(output);
        String prefix = "strand-" + strand + "-junction-" + junction;
        Files.write(output.resolve(prefix + "-module-input.json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(input),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.write(output.resolve(prefix + "-runtime-result.json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    private int savedReferenceBase(ObjectNode input, int alteredPosition1) {
        int base0 = alteredPosition1 - 1;
        for (var span : input.at("/codingEditComposition/alteredToReference"))
            if (base0 >= span.path("alteredStart0").asInt() && base0 < span.path("alteredEnd0").asInt())
                return span.path("referenceStart0").isNull() ? 0
                        : span.path("referenceStart0").asInt() + base0 - span.path("alteredStart0").asInt() + 1;
        throw new AssertionError("Saved A5 mapping does not cover altered base " + alteredPosition1);
    }

    private void mechanics(ObjectNode input, int referenceJunction, int alteredJunction, int distance) {
        assertEquals(587, input.path("alteredCdsLength").asInt());
        assertEquals("ATG", input.path("alteredCdsSequence").asText().substring(0, 3));
        assertEquals("TAA", input.path("alteredCdsSequence").asText().substring(584, 587));
        assertEquals(598, savedReferenceBase(input, 585));
        assertEquals(599, savedReferenceBase(input, 586));
        assertEquals(600, savedReferenceBase(input, 587));
        assertEquals(49, input.path("ptcStart").asInt());assertEquals(51, input.path("ptcEnd").asInt());
        assertEquals(62, input.path("ptcReferenceStart").asInt());
        assertEquals(63, input.path("ptcReferenceMiddle").asInt());
        assertEquals(64, input.path("ptcReferenceEnd").asInt());
        assertEquals(referenceJunction, input.path("referenceLastJunctionCdsPosition").asInt());
        assertEquals(alteredJunction, input.path("lastJunctionCdsPosition").asInt());
        assertEquals(distance, input.path("distanceToLastJunction").asInt());
        assertEquals(4, input.at("/spliceAssessment/referenceTopology/exonCount").asInt());
        assertEquals(3, input.at("/spliceAssessment/alteredTopology/exonCount").asInt());
        assertEquals("PREDICTED_NMD", input.path("nmdPrediction").asText());
    }

    @ParameterizedTest @CsvSource({"1,597,584,533", "-1,597,584,533", "1,598,585,534", "-1,598,585,534", "1,599,586,535", "-1,599,586,535"})
    void nativeStopBeforeOrAcrossTheFinalJunctionKeepsTheSupportedNmdPath(int strand, int junction, int alteredJunction, int distance) throws Exception {
        var data = data(strand, junction);var d = data.input();
        ObjectNode originalModel = d.model.deepCopy();
        assertNull(CodingTranscriptMapping.modelIssue(d.model, d.context), "The shared model grammar already permits this complete CDS geometry");
        var input = base.mapper.createObjectNode();
        assertNull(module(data, input), input::toPrettyString);
        mechanics(input, junction, alteredJunction, distance);
        assertEquals("MET", input.at("/spliceAssessment/status").asText());
        assertEquals("VERY_STRONG", input.at("/spliceAssessment/adoptedStrength").asText());
        var result = base.run(data);
        assertEquals("MET", base.status(result).path("status").asText(), result::toPrettyString);
        assertEquals("VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
        JsonNode resolved = result.path("resolvedInput");
        if (!(resolved instanceof ObjectNode actualInput)) throw new AssertionError("Actual Runtime did not retain its resolved input");
        mechanics(actualInput, junction, alteredJunction, distance);
        assertEquals(originalModel, d.model, "Projection must preserve the complete reference model");
        savePositive(strand, junction, input, result);
    }

    @ParameterizedTest @ValueSource(strings = {"sourceIdentity", "sourceMissing", "modelPin", "junctionMismatch", "nativeStop", "startRemoved", "stopRemoved"})
    void boundarySupportKeepsExistingSourceModelAndNativeCodonGuards(String fault) throws Exception {
        // J596 is already supported, so these controls isolate the original gates
        // independently of the three positive cases currently rejected by the model.
        var data = data(1, 596);var d = data.input();String expected;
        switch (fault) {
            case "sourceIdentity" -> {
                if (!(data.material().at("/sourceRecords/0") instanceof ObjectNode source)) throw new AssertionError("Source missing");
                source.put("sha256", "f".repeat(64));expected = "PVS1_SPLICE_SOURCE_IDENTITY_CONFLICT";
            }
            case "sourceMissing" -> { data.material().withObject("review").putArray("sourceIds").add("unbound-source");expected = "PVS1_SPLICE_OUTCOME_NOT_REVIEWED"; }
            case "modelPin" -> { data.material().put("transcriptModelSha256", "0".repeat(64));expected = "PVS1_SPLICE_MODEL_IDENTITY_MISMATCH"; }
            case "junctionMismatch" -> {
                d.model.put("lastJunctionCdsPosition", 595);data.material().put("transcriptModelSha256", base.adapter.canonicalSha256(d.model));
                expected = "PVS1_TRANSCRIPT_MODEL_JUNCTION_INVALID";
            }
            case "nativeStop" -> {
                String cds = d.model.path("cdsSequence").asText();d.model.put("cdsSequence", cds.substring(0, 597) + "CAA");
                data.material().put("transcriptModelSha256", base.adapter.canonicalSha256(d.model));expected = "PVS1_TRANSCRIPT_MODEL_SEQUENCE_INVALID";
            }
            case "startRemoved" -> { data.material().withObject("event").put("exonOrdinal", 1).put("deleteStartCds", 1).put("deleteEndCds", 12);expected = "PVS1_SPLICE_START_REMOVAL_BRANCH_NOT_IMPLEMENTED"; }
            case "stopRemoved" -> { data.material().withObject("event").put("exonOrdinal", 4).put("deleteStartCds", 597).put("deleteEndCds", 600);expected = "PVS1_SPLICE_TERMINAL_EXTENSION_BRANCH_NOT_IMPLEMENTED"; }
            default -> throw new AssertionError(fault);
        }
        var input = base.mapper.createObjectNode();assertEquals(expected, module(data, input), input::toPrettyString);
        assertNotEquals("MET", input.at("/spliceAssessment/status").asText());
        var result = base.run(data);assertEquals("NOT_EVALUATED", base.status(result).path("status").asText(), result::toPrettyString);
        assertEquals(expected, base.status(result).path("reasonCode").asText());
    }

    private Pvs1SpliceConsequenceTest.Data utrData(int strand) throws Exception {
        var data = data(strand, 599);var model = data.input().model;
        if (!(model.at("/exons/2") instanceof ObjectNode penultimate) || !(model.at("/exons/3") instanceof ObjectNode terminal))
            throw new AssertionError("Expected reference exon geometry missing");
        penultimate.put("cdsEnd", 600).put("genomicEnd", penultimate.path("genomicStart").asInt() + 574);
        terminal.put("cdsStart", 601).put("cdsEnd", 620).put("genomicEnd", terminal.path("genomicStart").asInt() + 19);
        model.put("lastJunctionCdsPosition", 600);
        String cds = model.path("cdsSequence").asText();
        model.putObject("sequenceVerification").put("cdsSha256", CodingTranscriptMaterialSupply.sha(cds))
                .put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(cds + "A".repeat(20)));
        data.material().put("transcriptModelSha256", base.adapter.canonicalSha256(model));
        return data;
    }

    private void utrScope(Pvs1SpliceConsequenceTest.Data data) {
        data.input().policy.withObject("nmdScope").putObject("utrJunctionApplicability").put("status", "REVIEWED_FOR_3_PRIME_UTR_JUNCTION")
                .put("basis", "TEST_ONLY separately reviewed UTR applicability; no scientific approval")
                .putArray("sourceIds").add("synthetic-source");
    }

    @ParameterizedTest @ValueSource(strings = {"missingProof", "wrongCdsProof", "missingApplicability", "unboundApplicability"})
    void junctionAtCdsEndStillNeedsCompleteRnaProofAndSeparateUtrApplicability(String fault) throws Exception {
        var data = utrData(1);String expected;
        switch (fault) {
            case "missingProof" -> { data.input().model.remove("sequenceVerification");expected = "PVS1_SPECIAL_TOPOLOGY_SEQUENCE_PROOF_MISSING"; }
            case "wrongCdsProof" -> { data.input().model.withObject("sequenceVerification").put("cdsSha256", "0".repeat(64));expected = "PVS1_SPECIAL_TOPOLOGY_SEQUENCE_PROOF_MISSING"; }
            case "missingApplicability" -> expected = "PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED";
            case "unboundApplicability" -> {
                utrScope(data);data.input().policy.withObject("nmdScope").withObject("utrJunctionApplicability").putArray("sourceIds").add("unbound-source");
                expected = "PVS1_UTR_JUNCTION_APPLICABILITY_INVALID";
            }
            default -> throw new AssertionError(fault);
        }
        data.material().put("transcriptModelSha256", base.adapter.canonicalSha256(data.input().model));
        var input = base.mapper.createObjectNode();assertEquals(expected, module(data, input), input::toPrettyString);
        assertFalse(input.has("nmdPrediction"));
        var result = base.run(data);assertEquals("NOT_EVALUATED", base.status(result).path("status").asText(), result::toPrettyString);
        assertEquals(expected, base.status(result).path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void properlyBoundUtrJunctionRetainsItsExistingQualifiedPath(int strand) throws Exception {
        var data = utrData(strand);utrScope(data);var input = base.mapper.createObjectNode();
        assertNull(module(data, input), input::toPrettyString);mechanics(input, 600, 587, 536);
        assertEquals("LAST_JUNCTION_IN_3_PRIME_UTR", input.at("/spliceAssessment/alteredTopology/kind").asText());
        var result = base.run(data);assertEquals("MET", base.status(result).path("status").asText(), result::toPrettyString);
        assertEquals("VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
        savePositive(strand, 600, input, result);
    }
}
