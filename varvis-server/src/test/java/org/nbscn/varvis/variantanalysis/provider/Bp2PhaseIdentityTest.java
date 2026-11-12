package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Bp2TestMaterials.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class Bp2PhaseIdentityTest {
    @TestFactory Stream<DynamicTest> strictBytesIndependentOfCaller() {
        return Stream.of("duplicate", "NFC-keys", "trailing-root", "comment", "trailing-comma", "nonfinite", "finite-float", "unknown", "depth", "size", "long-overflow")
                .map(name -> DynamicTest.dynamicTest(name, () -> {
                    String input = M.writeValueAsString(base("alpha"));
                    String bytes = switch (name) {
                        case "duplicate" -> input.replaceFirst("\\{", "{\"schemaVersion\":\"other\",");
                        case "NFC-keys" -> input.replaceFirst("\\{", "{\"é\":1,\"e\\u0301\":2,");
                        case "trailing-root" -> input + " {}";
                        case "comment" -> "/* not JSON */" + input;
                        case "trailing-comma" -> input.substring(0, input.length() - 1) + ",}";
                        case "nonfinite" -> "{\"x\":NaN}";
                        case "finite-float" -> input.replace("\"targetAltIndex\":1", "\"targetAltIndex\":0.25");
                        case "unknown" -> input.replaceFirst("\\{", "{\"unknown\":true,");
                        case "depth" -> "{\"x\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}";
                        case "size" -> "{\"x\":\"" + "a".repeat(1048576) + "\"}";
                        case "long-overflow" -> input.replace("\"targetAltIndex\":1", "\"targetAltIndex\":18446744073709551617");
                        default -> throw new IllegalStateException(name);
                    };
                    ObjectMapper permissive = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
                            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS)
                            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_TRAILING_COMMA).build())
                            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
                    byte[] rawBytes = bytes.getBytes(StandardCharsets.UTF_8);
                    String pin;
                    try { pin = hash(M.readTree(rawBytes)); } catch (Exception expectedInvalid) { pin = "0".repeat(64); }
                    String canonicalPin = pin;
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Bp2PhaseBundle.load(permissive, rawBytes, raw(rawBytes), canonicalPin));
                    assertTrue(failure.getMessage().startsWith("BP2_"), failure::getMessage);
                    String output = System.getProperty("varvis.ps2.artifacts");
                    if (output != null) { var dir = java.nio.file.Path.of(output, "bp2-strict", name); java.nio.file.Files.createDirectories(dir); java.nio.file.Files.write(dir.resolve("input.bytes"), rawBytes); java.nio.file.Files.writeString(dir.resolve("rejection.txt"), failure.toString()); }
                }));
    }
    @Test void rawAndCanonicalPinsAreDistinct() throws Exception {
        ObjectNode root = base("alpha"); byte[] bytes = M.writeValueAsBytes(root);
        assertThrows(IllegalArgumentException.class, () -> Bp2PhaseBundle.load(M, bytes, "0".repeat(64), hash(root)));
        assertThrows(IllegalArgumentException.class, () -> Bp2PhaseBundle.load(M, bytes, raw(bytes), "0".repeat(64)));
        byte[] pretty = M.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        assertNotEquals(raw(bytes), raw(pretty));
        assertEquals(load(root).snapshot(), Bp2PhaseBundle.load(M, pretty, raw(pretty), hash(root)).snapshot());
    }
    @Test void registryOnlyPm3AndLineageChange() throws Exception {
        try (var prior = getClass().getResourceAsStream(Pm3InTransCriterionEvaluator.REGISTRY);
                var next = getClass().getResourceAsStream(Bp2PhaseCriterionEvaluator.REGISTRY)) {
            assertNotNull(prior); assertNotNull(next);
            byte[] oldBytes = prior.readAllBytes(); ObjectNode old = (ObjectNode) M.readTree(oldBytes), current = (ObjectNode) M.readTree(next.readAllBytes());
            assertEquals(raw(oldBytes), current.at("/lineage/baseRegistrySha256").asText());
            assertEquals(old.path("registryId"), current.at("/lineage/baseRegistryId"));
            int unchanged = 0;
            for (int i = 0; i < old.path("criteria").size(); i++) {
                ObjectNode before = object(old, "/criteria/" + i), after = object(current, "/criteria/" + i);
                if (!"ACMG_AMP_2015:BP2".equals(before.path("criterionId").asText())) { assertEquals(before, after); unchanged++; }
                else { before.remove(List.of("implementationStatus", "notes")); after.remove(List.of("implementationStatus", "notes")); assertEquals(before, after); }
            }
            assertEquals(27, unchanged); old.remove(List.of("registryId", "lineage", "asOf")); current.remove(List.of("registryId", "lineage", "asOf")); assertEquals(old, current);
        }
    }
    @Test void oldSelectorsAndLoadersDoNotAcceptPm3() throws Exception {
        ObjectNode root = base("alpha");
        var catalog = object(root, "/catalog"); var policies = M.createArrayNode().add(root.path("policy"));
        for (AcmgSpecificationSelector selector : List.of(new AcmgSpecificationSelector(M, catalog, hash(catalog), policies),
                AcmgSpecificationSelector.forPs2Versioned(M, catalog, hash(catalog), policies), AcmgSpecificationSelector.forPm6Versioned(M, catalog, hash(catalog), policies), AcmgSpecificationSelector.forPm3Versioned(M, catalog, hash(catalog), policies)))
            assertNull(selector.select(root.path("context"), Bp2PhaseCriterionEvaluator.CRITERION).selectedPolicy());
        byte[] bytes = M.writeValueAsBytes(root);
        assertThrows(IllegalArgumentException.class, () -> Ps2DeNovoBundle.load(M, bytes, raw(bytes), hash(root)));
        assertThrows(IllegalArgumentException.class, () -> Pm6DeNovoBundle.load(M, bytes, raw(bytes), hash(root)));
        assertThrows(IllegalArgumentException.class, () -> new FamilyDeNovoJson("TEST_").facts(facts(root)));
    }
    @Test void symmetricPairDoesNotPermitRoleReuse() throws Exception {
        ObjectNode root = base("alpha"), before = evaluate(root); assertTrue(before.has("evaluation"));
        ObjectNode binding = object(facts(root), "/binding"); String pair = binding.path("pairId").asText();
        String target = binding.path("targetAlleleId").asText(), partner = binding.path("partnerAlleleId").asText();
        binding.put("targetAlleleId", partner).put("partnerAlleleId", target);
        assertEquals(pair, new Bp2PhasePolicy().pairId(binding));
        reissue(root, "facts"); ObjectNode result = evaluate(root); assertEquals("BP2_PART_BINDING_MISMATCH", result.at("/evaluationResult/reasonCode").asText());
        artifact("role-swap-with-old-classification-and-policy", root, result);
    }
    @Test void explicitScopeAndContextBindingsFailClosed() throws Exception {
        for (String field : List.of("contextId", "hgncId", "mondoId", "moi", "patientContextId", "familyContextId", "occurrenceId", "caseId")) {
            ObjectNode root = base("alpha");
            for (String part : PARTS) object(observation(root), "/" + part + "/binding").put(field, "different");
            reissue(root, PARTS.toArray(String[]::new)); ObjectNode result = evaluate(root);
            assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
            assertTrue(List.of("BP2_CONTEXT_BINDING_MISMATCH", "BP2_FACT_IDENTITY_MISMATCH").contains(result.at("/evaluationResult/reasonCode").asText()));
            artifact("binding-" + field, root, result);
        }
    }
    @Test void missingStructuredFieldsAreNotDefaults() throws Exception {
        for (String pointer : List.of("/observations/0/diseaseApplicability", "/observations/0/diseaseApplicability/mechanism", "/observations/0/partnerClassification/independence", "/observations/0/facts/parentage")) {
            ObjectNode root = base("alpha"); int slash = pointer.lastIndexOf('/'); object(root, pointer.substring(0, slash)).remove(pointer.substring(slash + 1));
            assertThrows(IllegalArgumentException.class, () -> load(root));
        }
    }
    @Test void validPSMayBePreservedButIsNotPhaseEvidence() throws Exception {
        ObjectNode root = base("alpha");
        for (int i = 0; i < 6; i++) {
            ObjectNode r = object(facts(root), "/records/" + i); call(r, r.at("/observation/genotype").asText().replace('/', '|'));
            r.put("rawSampleValue", r.path("rawSampleValue").asText() + ":999"); object(facts(root), "/detections/" + i).put("sampleFormat", "GT:DP:GQ:AD:FT:PS");
        }
        reissue(root, "facts"); ObjectNode result = evaluate(root); assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText()); artifact("phased-with-PS", root, result);
        // Preserve proband pipes/PS but destroy segregation. PS cannot rescue missing phase evidence.
        call(object(facts(root), "/records/4"), "0|1"); object(facts(root), "/records/4").put("rawSampleValue", facts(root).at("/records/4/rawSampleValue").asText() + ":999");
        reissue(root, "facts"); result = evaluate(root); assertEquals("BP2_PHASE_UNRESOLVED", result.at("/evaluationResult/reasonCode").asText()); artifact("PS-cannot-rescue-unresolved", root, result);
    }
    @TestFactory Stream<DynamicTest> fullyReissuedOutOfScopeAllelesAndContext() {
        return Stream.of("same-allele", "same-site", "other-contig", "GRCh37", "X", "indel", "unsupported-MOI", "unknown-status").map(name -> DynamicTest.dynamicTest(name, () -> {
            ObjectNode root = base("alpha"), f = facts(root);
            String reason = "BP2_NATIVE_AUTOSOMAL_BIALLELIC_SNV_REQUIRED";
            if (name.equals("same-allele")) { object(f, "/alleles").set("partner", f.at("/alleles/target").deepCopy()); reason = "BP2_DISTINCT_LOCI_REQUIRED"; }
            else if (name.equals("unsupported-MOI") || name.equals("unknown-status")) {
                if (name.equals("unsupported-MOI")) object(root, "/context").put("moi", "X_LINKED_DOMINANT");
                else object(root, "/context/patientContext").put("affectedStatus", "UNKNOWN");
                reason = "BP2_CONTEXT_OUTSIDE_IMPLEMENTED_SCOPE";
            } else {
                for (String a : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) {
                    ObjectNode allele = object(f, "/alleles/partner/" + a);
                    switch (name) {
                        case "same-site" -> { allele.set("position", f.at("/alleles/target/normalizedAllele/position")); reason = "BP2_DISTINCT_LOCI_REQUIRED"; }
                        case "other-contig" -> { allele.put("contig", "8"); reason = "BP2_DISTINCT_LOCI_REQUIRED"; }
                        case "GRCh37" -> allele.put("assembly", "GRCh37");
                        case "X" -> allele.put("contig", "X");
                        case "indel" -> allele.put("alternate", "AA");
                        default -> throw new IllegalArgumentException(name);
                    }
                }
                if (name.equals("indel")) object(f, "/alleles/partner").put("variantType", "SMALL_INSERTION");
            }
            rebindFamily(root); ObjectNode result = evaluate(root); artifact("scope-" + name, root, result);
            assertEquals(reason, result.at("/evaluationResult/reasonCode").asText()); assertFalse(result.has("evaluation")); assertFalse(result.has("bp2Points"));
        }));
    }
    @Test void nfcEquivalentSampleAndSourceReferencesPreserveExactInput() throws Exception {
        ObjectNode root = base("alpha"), f = facts(root);
        String old = f.at("/members/1/sampleId").asText();
        String before = M.writeValueAsString(root).replace(old, "TEST_ONLY-é"); root = (ObjectNode) M.readTree(before); f = facts(root);
        object(f, "/records/2").put("sampleId", "TEST_ONLY-e\u0301"); object(f, "/records/3").put("sampleId", "TEST_ONLY-e\u0301");
        reissue(root, "facts", "phase"); ObjectNode immutable = root.deepCopy(), result = evaluate(root);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText()); assertEquals(immutable, root); artifact("NFC-equivalent-sample", root, result);
    }
}
