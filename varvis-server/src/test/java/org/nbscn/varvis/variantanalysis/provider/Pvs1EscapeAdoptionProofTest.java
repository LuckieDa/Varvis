package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual runtime toy escape results, not scientific source qualification. Source-qualified
 * Bundle/SelectedBatch coverage belongs to the separate business tests; no qualification stub here.
 * Fixed oracle: deletion c.13..25 -> stop c.49..51; 199 reference residues, first altered 5,
 * 16 altered residues, 195 affected reference residues, shortening 183, critical overlap 186.
 */
class Pvs1EscapeAdoptionProofTest {
    final Pvs1SpliceConsequenceTest base = new Pvs1SpliceConsequenceTest();
    static final String SCHEMA = "pvs1-escape-adoption-proof-v1";
    static final String PRIVATE_TEXT = "TEST_PRIVATE_ASSAY_FULL_TEXT_NEVER_PUBLIC_7b2d9";

    Pvs1SpliceConsequenceTest.Data data(int strand) throws Exception {
        return base.data(strand, true, false);
    }
    ObjectNode proof(ObjectNode result) { return Pvs1EscapeAdoptionProof.project(base.mapper, result); }

    void available(ObjectNode result, ObjectNode proof, String parent, String scope, String outcome, String local, String adopted) {
        assertEquals("MET", base.status(result).path("status").asText(), result::toPrettyString);
        assertEquals("NMD_NOT_PREDICTED", result.at("/resolvedInput/nmdPrediction").asText());
        assertEquals(SCHEMA, proof.path("schemaVersion").asText());
        assertEquals("1.0.0", proof.path("methodVersion").asText());
        assertEquals("AVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertEquals("PVS1_ESCAPE_PUBLIC_PROOF_VERIFIED", proof.path("reasonCode").asText());
        assertTrue(proof.path("scientificApprovalCreated").isBoolean());assertFalse(proof.path("scientificApprovalCreated").asBoolean());
        assertTrue(proof.path("rescoringPerformed").isBoolean());assertFalse(proof.path("rescoringPerformed").asBoolean());
        assertEquals("critical-5", proof.at("/selectedRule/ruleId").asText());
        assertEquals("CRITICAL_REGION", proof.at("/selectedRule/predicate").asText());
        assertEquals(5, proof.at("/selectedRule/start").asInt());
        assertEquals(190, proof.at("/selectedRule/end").asInt());
        assertEquals(1, proof.at("/selectedRule/minimumAffectedResidues").asInt());
        assertEquals("STRONG", proof.at("/selectedRule/strength").asText());
        assertEquals("MATCH", proof.at("/ruleCheck/status").asText());
        assertEquals(186, proof.at("/ruleCheck/affectedResiduesInRegion").asInt());
        assertEquals(5, proof.at("/proteinEffect/firstAlteredResidue").asInt());
        assertEquals(199, proof.at("/proteinEffect/referenceProteinLength").asInt());
        assertEquals(16, proof.at("/proteinEffect/alteredProteinLength").asInt());
        assertEquals(195, proof.at("/proteinEffect/referenceResiduesAffected").asInt());
        assertEquals(183, proof.at("/proteinEffect/netProteinShortening").asInt());
        assertEquals("STRONG", proof.at("/strengthDerivation/sourceRuleStrength").asText());
        assertEquals(parent, proof.at("/strengthDerivation/parentPolicyCap").asText());
        assertEquals(scope, proof.at("/strengthDerivation/spliceScopeCap").asText());
        assertEquals(outcome, proof.at("/strengthDerivation/outcomeReviewCap").asText());
        assertEquals(local, proof.at("/strengthDerivation/localMaximumStrength").asText());
        assertEquals(adopted, proof.at("/strengthDerivation/adoptedStrength").asText());
        assertEquals(adopted, result.at("/evaluation/appliedStrength").asText());
        assertEquals(result.at("/policy/policyId"), proof.at("/policy/policyId"));
        assertEquals(result.at("/policy/policyVersion"), proof.at("/policy/policyVersion"));
        assertEquals("REVIEWED_FOR_PVS1_ESCAPE", proof.at("/review/status").asText());
        assertEquals("SYNTHETIC_ESCAPE_ENGINEERING_ONLY", proof.at("/review/reviewId").asText());
        assertEquals(result.at("/policy/escapeScope/sourceIds"), proof.at("/review/sourceIds"));
        assertEquals(result.at("/policy/escapeScope/rules/0/sourceIds"), proof.at("/selectedRule/sourceIds"));
        assertEquals(result.at("/policySelection/outcome"), proof.at("/scope/selectionOutcome"));
        assertEquals(result.at("/spliceConsequenceMaterial/transcript"), proof.at("/scope/transcript"));
        for (String field : List.of("hgncId", "mondoId", "moi", "assembly", "variantType"))
            assertEquals(result.at("/policy/scope").path(field), proof.path("scope").path(field));
        assertFalse(proof.path("sources").isEmpty());
        var source = proof.path("sources").get(0);
        assertEquals("synthetic-source", source.path("sourceId").asText());
        for (String field : List.of("version", "url", "sha256"))
            assertEquals(result.at("/policy/scientificSources/0").path(field), source.path(field));
        assertFalse(proof.path("exonChecks").isEmpty());
        assertEquals(List.of(2, 3, 4), base.mapper.convertValue(proof.path("affectedReferenceExonOrdinals"),
                new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() { }));
        for (var exon : proof.path("exonChecks")) {
            assertEquals("PRESENT", exon.path("biologicalRelevance").asText());
            assertEquals("NOT_FREQUENT", exon.path("populationLof").asText());
            assertEquals("synthetic-source", exon.path("sourceIds").path(0).asText());
        }
        var bindings = proof.path("bindings");
        assertEquals(base.adapter.canonicalSha256(result.path("policy")), bindings.path("policySha256").asText());
        assertEquals(base.adapter.canonicalSha256(result.at("/policy/escapeScope")), bindings.path("escapeScopeSha256").asText());
        assertEquals(base.adapter.canonicalSha256(result.at("/resolvedInput/escapeAssessment/selectedRule")), bindings.path("selectedRuleSha256").asText());
        assertEquals(result.at("/transcriptModelBinding/canonicalSha256").asText(), bindings.path("transcriptModelSha256").asText());
        assertEquals(base.adapter.canonicalSha256(result.path("resolvedInput")), bindings.path("resolvedInputSha256").asText());
        assertEquals(base.adapter.canonicalSha256(result.path("policySelection")), bindings.path("selectionSha256").asText());
        assertEquals(base.adapter.canonicalSha256(result.path("evaluation")), bindings.path("evaluationSha256").asText());
        assertEquals(result.at("/evaluation/evaluationId"), bindings.path("evaluationId"));
        assertEquals(base.adapter.canonicalSha256(result.path("spliceConsequenceMaterial")), bindings.path("spliceMaterialSha256").asText());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void reviewedStrongEscapeProjectsActualSourceRuleAndIndependentProteinEffectOnBothStrands(int strand) throws Exception {
        var result = base.run(data(strand));
        assertFalse(result.has("knowledgeAssessment"), "Mechanical projection must not fabricate source qualification");
        available(result, proof(result), "VERY_STRONG", "VERY_STRONG", "VERY_STRONG", "VERY_STRONG", "STRONG");
    }

    @Test void strongRuleIsCappedByParentModerate() throws Exception {
        var data = data(1);data.input().policy.put("appliedStrength", "MODERATE");
        var result = base.run(data);
        available(result, proof(result), "MODERATE", "VERY_STRONG", "VERY_STRONG", "VERY_STRONG", "MODERATE");
    }

    @Test void localSupportingCapRemainsDistinctFromParentModerateAndSourceStrong() throws Exception {
        var data = data(1);data.input().policy.put("appliedStrength", "MODERATE");
        data.input().policy.withObject("spliceScope").put("maximumStrength", "STRONG");
        data.material().withObject("review").put("maximumStrength", "SUPPORTING");
        var result = base.run(data);
        available(result, proof(result), "MODERATE", "STRONG", "SUPPORTING", "SUPPORTING", "SUPPORTING");
    }

    @Test void exactRationalFractionRuleProjectsItsMatchedProductsWithoutRounding() throws Exception {
        var data = data(1);var scope = data.input().policy.withObject("escapeScope");
        scope.putArray("rules");Pvs1NmdEscapeTest.length(scope, "NET_PROTEIN_SHORTENING", "GT", 1, 10, "STRONG");
        var result = base.run(data);var proof = proof(result);
        assertEquals("AVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertEquals("PROTEIN_LOSS_FRACTION", proof.at("/selectedRule/predicate").asText());
        assertEquals("NET_PROTEIN_SHORTENING", proof.at("/selectedRule/metric").asText());
        assertEquals(183, proof.at("/ruleCheck/actualNumerator").asInt());
        assertEquals(199, proof.at("/ruleCheck/actualDenominator").asInt());
        assertEquals(1830, proof.at("/ruleCheck/leftProduct").asInt());
        assertEquals(199, proof.at("/ruleCheck/rightProduct").asInt());
    }

    @ParameterizedTest @ValueSource(strings = {"ABSENT", "FREQUENT"})
    void actualNegativeHasNoPositiveAdoptionProof(String negative) throws Exception {
        var data = data(1);
        for (var row : data.input().policy.at("/escapeScope/exonAssessments"))
            ((ObjectNode) row).put("ABSENT".equals(negative) ? "biologicalRelevance" : "populationLof", negative);
        var result = base.run(data);
        assertEquals("NOT_MET", base.status(result).path("status").asText());
        var proof = proof(result);
        assertEquals("NOT_ADOPTED", proof.path("status").asText(), proof::toPrettyString);
        assertFalse(proof.has("selectedRule"));
        assertFalse(proof.has("strengthDerivation"));
    }

    @ParameterizedTest @ValueSource(strings = {"residual", "assay", "escapeScope"})
    void insufficientMaterialIsNeverConvertedIntoAdoption(String missing) throws Exception {
        var data = data(1);
        switch (missing) {
            case "residual" -> data.material().withObject("review").put("residualNormalTranscript", "UNKNOWN");
            case "assay" -> data.material().withObject("assessment").put("assayQuality", "UNKNOWN");
            case "escapeScope" -> data.input().policy.remove("escapeScope");
            default -> throw new AssertionError();
        }
        var result = base.run(data);
        assertEquals("NOT_EVALUATED", base.status(result).path("status").asText());
        var proof = proof(result);
        assertEquals("NOT_ADOPTED", proof.path("status").asText(), proof::toPrettyString);
        assertFalse(proof.has("selectedRule"));
    }

    @ParameterizedTest @ValueSource(strings = {"rule", "ruleOperand", "ruleCheck", "source", "model", "parentCap", "localCap",
            "material", "input", "trace", "traceHash", "evaluationId", "selection", "exonCheck"})
    void replacedPrivateFactsCannotReuseTheOriginalAdoptionAudit(String mutation) throws Exception {
        var genuine = base.run(data(1));
        assertEquals("MET", base.status(genuine).path("status").asText());
        var result = genuine.deepCopy();
        switch (mutation) {
            case "rule" -> result.withObject("resolvedInput").withObject("escapeAssessment").withObject("selectedRule").put("ruleId", "UNREVIEWED_REPLACEMENT");
            case "ruleOperand" -> result.withObject("resolvedInput").withObject("escapeAssessment").withObject("selectedRule").put("minimumAffectedResidues", 187);
            case "ruleCheck" -> ((ObjectNode) result.at("/resolvedInput/escapeAssessment/ruleChecks/0")).put("affectedResiduesInRegion", 185);
            case "source" -> ((ObjectNode) result.at("/policy/scientificSources/0")).put("sha256", "f".repeat(64));
            case "model" -> result.withObject("transcriptModelBinding").put("canonicalSha256", "f".repeat(64));
            case "parentCap" -> result.withObject("policy").put("appliedStrength", "SUPPORTING");
            case "localCap" -> result.withObject("resolvedInput").withObject("spliceAssessment").put("maximumStrength", "SUPPORTING");
            case "material" -> result.withObject("spliceConsequenceMaterial").withObject("event").put("deleteEndCds", 24);
            case "input" -> result.withObject("resolvedInput").put("ptcEnd", 54);
            case "trace" -> {
                for (var row : result.at("/evaluation/deterministicTrace/parameters"))
                    if ("resolvedInputSha256".equals(row.path("name").asText())) ((ObjectNode) row).put("value", "f".repeat(64));
            }
            case "traceHash" -> result.withObject("evaluation").withObject("deterministicTrace").put("traceSha256", "f".repeat(64));
            case "evaluationId" -> result.withObject("evaluation").put("evaluationId", "evaluation-sha256-" + "f".repeat(64));
            case "selection" -> result.withObject("policySelection").put("policyId", "UNSELECTED_POLICY");
            case "exonCheck" -> ((ObjectNode) result.at("/resolvedInput/escapeAssessment/exonChecks/0")).put("biologicalRelevance", "ABSENT");
            default -> throw new AssertionError();
        }
        var proof = proof(result);
        assertEquals("UNAVAILABLE", proof.path("status").asText(), mutation + ": " + proof.toPrettyString());
        assertTrue(proof.path("reasonCode").asText().startsWith("PVS1_ESCAPE_PUBLIC_PROOF_"));
        assertFalse(proof.has("selectedRule"));
        assertFalse(proof.has("strengthDerivation"));
        assertEquals("MET", base.status(genuine).path("status").asText());
    }

    @Test void nmdAndEscapePositionConditionsKeepTheirDeclaredMethodScopes() throws Exception {
        var data = data(1);
        var condition = data.input().policy.withObject("nmdScope").putArray("positionConditions").addObject().put("conditionId", "NMD_ONLY_STOP_RANGE")
                .put("coordinate", "REFERENCE_PTC_SPAN").put("maximumExclusive", 62)
                .put("basis", "Synthetic NMD-only source range: full reference stop 62..64 does not fit");
        condition.putArray("sourceIds").add("synthetic-source");
        var result = base.run(data);var proof = proof(result);
        assertEquals("MET", base.status(result).path("status").asText(), result::toPrettyString);
        assertEquals("AVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertEquals("NOT_USED_BY_ESCAPE_METHOD", proof.at("/positionConditions/nmd/status").asText());
        assertEquals("NOT_DECLARED", proof.at("/positionConditions/escape/status").asText());
        assertFalse(proof.at("/positionConditions/nmd/conditions/0").has("checkedPositions"));
        data.input().policy.withObject("escapeScope").putArray("positionConditions").add(condition.deepCopy());
        result = base.run(data);
        assertEquals("NOT_EVALUATED", base.status(result).path("status").asText(), result::toPrettyString);
        assertEquals("PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE", base.status(result).path("reasonCode").asText());
        assertEquals("NOT_ADOPTED", proof(result).path("status").asText());
        ((ObjectNode) data.input().policy.at("/escapeScope/positionConditions/0")).put("maximumExclusive", 65);
        result = base.run(data);proof = proof(result);
        assertEquals("AVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertEquals("CHECKED", proof.at("/positionConditions/escape/status").asText());
        assertEquals("PASS", proof.at("/positionConditions/escape/conditions/0/status").asText());
        assertEquals(List.of(62, 63, 64), base.mapper.convertValue(proof.at("/positionConditions/escape/conditions/0/checkedPositions"),
                new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() { }));
        assertEquals("NOT_USED_BY_ESCAPE_METHOD", proof.at("/positionConditions/nmd/status").asText());
    }

    /** Recompute only the mechanical audit hashes in an adversarial result copy.
     * This does not create source qualification; the mismatching facts must still
     * fail comparisons with the unchanged source rule and original material.
     */
    void resealAudit(ObjectNode result) {
        var input = result.path("resolvedInput");var evaluation = result.withObject("evaluation");
        var trace = evaluation.withObject("deterministicTrace");
        for (var row : trace.path("parameters")) {
            String name = row.path("name").asText();
            if ("resolvedInputSha256".equals(name)) ((ObjectNode) row).put("value", base.adapter.canonicalSha256(input));
            else if (input.has(name)) ((ObjectNode) row).put("value", input.path(name).isContainerNode()
                    ? input.path(name).toString() : input.path(name).asText());
        }
        trace.remove("traceSha256");trace.put("traceSha256", base.adapter.canonicalSha256(trace));
        var identity = evaluation.deepCopy();identity.remove(List.of("evaluationId", "conflict", "review"));
        evaluation.put("evaluationId", "evaluation-sha256-" + base.adapter.canonicalSha256(identity));
    }

    @ParameterizedTest @ValueSource(strings = {"selectedRule", "arithmetic", "twoMatches", "localCap", "exonFact", "escapeCheck"})
    void resealedAuditCannotInventSourceRuleMatchOrExecutionFacts(String mutation) throws Exception {
        var data = data(1);
        if ("twoMatches".equals(mutation)) Pvs1NmdEscapeTest.critical(data.input().policy.withObject("escapeScope"), 1, 2, 1, "MODERATE");
        if ("escapeCheck".equals(mutation)) {
            var c = data.input().policy.withObject("escapeScope").putArray("positionConditions").addObject().put("conditionId", "TAIL")
                    .put("coordinate", "REFERENCE_PTC_SPAN").put("maximumExclusive", 65).put("basis", "Synthetic checked bound");
            c.putArray("sourceIds").add("synthetic-source");
        }
        var result = base.run(data);
        assertEquals("AVAILABLE", proof(result).path("status").asText());
        switch (mutation) {
            case "selectedRule" -> result.withObject("resolvedInput").withObject("escapeAssessment").withObject("selectedRule").put("ruleId", "INVENTED_RULE");
            case "arithmetic" -> ((ObjectNode) result.at("/resolvedInput/escapeAssessment/ruleChecks/0")).put("affectedResiduesInRegion", 185);
            case "twoMatches" -> ((ObjectNode) result.at("/resolvedInput/escapeAssessment/ruleChecks/1")).put("status", "MATCH");
            case "localCap" -> result.withObject("resolvedInput").withObject("spliceAssessment").put("maximumStrength", "SUPPORTING");
            case "exonFact" -> ((ObjectNode) result.at("/resolvedInput/escapeAssessment/exonChecks/0")).put("biologicalRelevance", "ABSENT");
            case "escapeCheck" -> ((ObjectNode) result.at("/resolvedInput/escapePositionConditionChecks/0")).putArray("checkedPositions").add(62).add(63).add(65);
            default -> throw new AssertionError();
        }
        resealAudit(result);
        var proof = proof(result);
        assertEquals("UNAVAILABLE", proof.path("status").asText(), mutation + ": " + proof.toPrettyString());
        assertFalse(proof.has("selectedRule"));assertFalse(proof.has("strengthDerivation"));
    }

    @Test void repeatedProjectionIsStableLeavesPrivateAuditUntouchedAndContainsNoSequencesOrRawProse() throws Exception {
        var data = data(1);
        ((ObjectNode) data.input().policy.at("/escapeScope/rules/0")).put("basis", PRIVATE_TEXT);
        data.material().withObject("review").put("basis", PRIVATE_TEXT);
        var result = base.run(data);var before = result.deepCopy();
        var first = proof(result);var second = proof(result);
        assertEquals("AVAILABLE", first.path("status").asText(), first::toPrettyString);
        assertEquals(first, second);assertEquals(before, result);
        assertEquals(first, Pvs1SelectedBatch.publicSummary(base.mapper, result).path("escapeAdoptionProof"));
        String json = Pvs1SelectedBatch.publicSummary(base.mapper, result).toString();
        assertFalse(json.contains(PRIVATE_TEXT));
        assertFalse(json.contains(data.input().model.path("cdsSequence").asText()));
        for (String forbidden : List.of("alteredCdsSequence", "cdsSequence", "referenceWindow", "sourceQuote", "sourceDeclaration"))
            assertFalse(first.toString().contains('"' + forbidden + '"'), forbidden);
        assertEquals(first, proof(base.run(data)));
    }

    @Test void ordinaryNmdIsNotRelabeledAsEscapeAndLegacyInputDoesNotGetAnInventedProof() throws Exception {
        var result = base.run(base.data(1, false, false));
        assertEquals("MET", base.status(result).path("status").asText());
        assertEquals("PREDICTED_NMD", result.at("/resolvedInput/nmdPrediction").asText());
        assertEquals("NOT_ADOPTED", proof(result).path("status").asText());
        var legacy = base.mapper.createObjectNode();
        assertNotEquals("AVAILABLE", proof(legacy).path("status").asText());
        assertFalse(legacy.has("escapeAdoptionProof"));
    }

    @Test void sourceUrlWithCredentialsOrQueryIsOmittedWithoutChangingThePrivateSourceBinding() throws Exception {
        for (String url : List.of("https://example.org/source?private=" + PRIVATE_TEXT,
                "https://TEST_ONLY:password@example.org/source")) {
            var data = data(1);
            ((ObjectNode) data.input().policy.at("/scientificSources/0")).put("url", url);
            ((ObjectNode) data.material().at("/sourceRecords/0")).put("url", url);
            var result = base.run(data);var proof = proof(result);
            assertEquals("AVAILABLE", proof.path("status").asText(), proof::toPrettyString);
            assertEquals(url, result.at("/policy/scientificSources/0/url").asText());
            assertFalse(proof.at("/sources/0").has("url"));
            assertTrue(proof.at("/sources/0/urlOmitted").asBoolean());
            assertFalse(proof.toString().contains(PRIVATE_TEXT));
            assertFalse(proof.toString().contains("password"));
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void ordinaryNonspliceEscapeRetainsLegacyRuleAndParentCapWithoutInventingLocalCaps(int strand) throws Exception {
        var fixture = new TruncatingNmdPvs1RuntimeTest();
        var data = fixture.nonsense(strand == 1 ? 1 : 2, strand, 499);
        var scope = Pvs1NmdEscapeTest.scope(data.policy, 4);
        Pvs1NmdEscapeTest.critical(scope, 160, 190, 10, "STRONG");
        data.policy.put("appliedStrength", "MODERATE");fixture.bind(data);
        var result = fixture.evaluate(data);var proof = proof(result);
        assertEquals("MET", base.status(result).path("status").asText(), result::toPrettyString);
        assertFalse(result.path("resolvedInput").has("spliceAssessment"));
        assertFalse(result.path("resolvedInput").has("codingEditDerivation"));
        assertEquals("AVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertEquals("critical-160", proof.at("/selectedRule/ruleId").asText());
        assertEquals(24, proof.at("/ruleCheck/affectedResiduesInRegion").asInt());
        assertEquals(167, proof.at("/proteinEffect/firstAlteredResidue").asInt());
        assertEquals(33, proof.at("/proteinEffect/referenceResiduesAffected").asInt());
        assertEquals(33, proof.at("/proteinEffect/netProteinShortening").asInt());
        noLocalCaps(proof);
        assertEquals(proof, Pvs1SelectedBatch.publicSummary(base.mapper, result).path("escapeAdoptionProof"));
    }

    void noLocalCaps(ObjectNode proof) {
        assertEquals("STRONG", proof.at("/strengthDerivation/sourceRuleStrength").asText());
        assertEquals("MODERATE", proof.at("/strengthDerivation/parentPolicyCap").asText());
        assertEquals("MODERATE", proof.at("/strengthDerivation/adoptedStrength").asText());
        for (String local : List.of("spliceScopeCap", "outcomeReviewCap", "localMaximumStrength"))
            assertFalse(proof.path("strengthDerivation").has(local), local);
        assertFalse(proof.has("localReviews"));
        assertFalse(proof.path("bindings").has("spliceMaterialSha256"));
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void explicitA5ComplexCodingEscapeUsesActualReferenceMappingWithoutSpliceLocalCaps(int strand) throws Exception {
        var fixture = new TruncatingNmdPvs1RuntimeTest();
        // Exact A5 vector c.31_32delinsG gives stop61..63; a reference junction
        // at100 becomes99, independently fixing distance36 in the escape range.
        var data = fixture.frameshift(false, strand, 100);
        var annotation = fixture.annotation(data, "frameshift_variant", "31_32delinsG", "Pro11AspfsTer11",
                "3/10", "11", "P/X", "CCA/GA");
        fixture.candidate(data, fixture.genomic(data.model, strand == 1 ? 31 : 32),
                strand == 1 ? "CC" : "GG", strand == 1 ? "G" : "C", annotation);
        TruncatingNmdPolicyComplexScopeTest.enable(data.policy, 50);
        var scope = Pvs1NmdEscapeTest.scope(data.policy, 10);
        Pvs1NmdEscapeTest.critical(scope, 160, 190, 10, "STRONG");
        data.policy.put("appliedStrength", "MODERATE");fixture.bind(data);
        var result = new TruncatingNmdPvs1Runtime(base.mapper, fixture.resourceProfile(), data.model, true)
                .evaluate(data.context, data.candidate, data.policy, data.selection);
        var proof = proof(result);
        assertEquals("MET", base.status(result).path("status").asText(), result::toPrettyString);
        assertTrue(result.at("/resolvedInput/codingEditDerivation").isObject());
        assertFalse(result.path("resolvedInput").has("spliceAssessment"));
        assertEquals(61, result.at("/resolvedInput/ptcStart").asInt());
        assertEquals(63, result.at("/resolvedInput/ptcEnd").asInt());
        assertEquals(99, result.at("/resolvedInput/lastJunctionCdsPosition").asInt());
        assertEquals(36, result.at("/resolvedInput/distanceToLastJunction").asInt());
        assertEquals(62, result.at("/resolvedInput/ptcReferenceStart").asInt());
        assertEquals(64, result.at("/resolvedInput/ptcReferenceEnd").asInt());
        assertEquals("AVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertEquals("critical-160", proof.at("/selectedRule/ruleId").asText());
        assertEquals(11, proof.at("/proteinEffect/firstAlteredResidue").asInt());
        assertEquals(20, proof.at("/proteinEffect/alteredProteinLength").asInt());
        assertEquals(189, proof.at("/proteinEffect/referenceResiduesAffected").asInt());
        assertEquals(179, proof.at("/proteinEffect/netProteinShortening").asInt());
        noLocalCaps(proof);
        assertEquals(proof, Pvs1SelectedBatch.publicSummary(base.mapper, result).path("escapeAdoptionProof"));
    }

    @Test void criticalRegionEscapeDoesNotInventAPopulationQualificationRequirement() throws Exception {
        var data = data(1);
        for (var row : data.input().policy.at("/escapeScope/exonAssessments")) ((ObjectNode) row).put("populationLof", "UNKNOWN");
        var result = base.run(data);var proof = proof(result);
        assertEquals("MET", base.status(result).path("status").asText(), result::toPrettyString);
        assertEquals("AVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertEquals("STRONG", proof.at("/strengthDerivation/adoptedStrength").asText());
        for (var row : proof.path("exonChecks")) assertEquals("UNKNOWN", row.path("populationLof").asText());
    }

    @ParameterizedTest @CsvSource({"REFERENCE_TAIL_ALTERED_OR_REMOVED,189,1890", "NET_PROTEIN_SHORTENING,178,1780"})
    void bothSourceDeclaredFractionMetricsKeepTheirDistinctActualNumerators(String metric, int numerator, int product) throws Exception {
        var helper = new Pvs1NmdEscapeTest();var data = helper.fixture.frameshift(true, 1, 112);
        var scope = Pvs1NmdEscapeTest.scope(data.policy, 10);
        Pvs1NmdEscapeTest.length(scope, metric, "GT", 1, 10, "STRONG");
        var result = helper.run(data);var proof = proof(result);
        assertEquals("AVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertEquals(metric, proof.at("/selectedRule/metric").asText());
        assertEquals(numerator, proof.at("/ruleCheck/actualNumerator").asInt());
        assertEquals(199, proof.at("/ruleCheck/actualDenominator").asInt());
        assertEquals(product, proof.at("/ruleCheck/leftProduct").asInt());
        assertEquals(199, proof.at("/ruleCheck/rightProduct").asInt());
        assertEquals("STRONG", proof.at("/strengthDerivation/sourceRuleStrength").asText());
    }

    @ParameterizedTest @CsvSource({"REFERENCE_TAIL_ALTERED_OR_REMOVED,189", "NET_PROTEIN_SHORTENING,178"})
    void equalityForEitherFractionMetricDoesNotAcquireAReviewedStrength(String metric, int numerator) throws Exception {
        var helper = new Pvs1NmdEscapeTest();var data = helper.fixture.frameshift(true, 1, 112);
        var scope = Pvs1NmdEscapeTest.scope(data.policy, 10);
        Pvs1NmdEscapeTest.length(scope, metric, "GT", numerator, 199, "STRONG");
        Pvs1NmdEscapeTest.length(scope, metric, "LT", numerator, 199, "MODERATE");
        var result = helper.run(data);
        assertEquals("NOT_EVALUATED", base.status(result).path("status").asText());
        assertEquals("PVS1_ESCAPE_FRACTION_BOUNDARY_UNSPECIFIED", base.status(result).path("reasonCode").asText());
        assertEquals("NOT_ADOPTED", proof(result).path("status").asText());
    }

    @Test void generalSelectionCannotUseRawVeryStrongEscapeEvenWhenParentWouldCapItToSupporting() throws Exception {
        var data = data(1);
        ((ObjectNode) data.input().policy.at("/escapeScope/rules/0")).put("strength", "VERY_STRONG");
        data.input().policy.put("appliedStrength", "SUPPORTING");
        data.input().selection.put("outcome", "GENERAL_POLICY_SELECTED");
        var result = base.run(data);
        assertEquals("NOT_EVALUATED", base.status(result).path("status").asText());
        assertEquals("PVS1_ESCAPE_SPECIALIZED_STRENGTH_REQUIRED", base.status(result).path("reasonCode").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals("NOT_ADOPTED", proof(result).path("status").asText());
    }

    @Test void moreThanThirtyTwoActuallyReferencedSourcesAreUnavailableInsteadOfSilentlyTruncated() throws Exception {
        var data = data(1);var sources = data.input().policy.withArray("scientificSources");
        var ids = data.input().policy.withObject("escapeScope").withArray("sourceIds");
        for (int i = 1; i <= 32; i++) {
            String id = "SYNTHETIC_EXTRA_" + i;
            sources.addObject().put("sourceId", id).put("version", "1").put("url", "https://example.invalid/toy-" + i)
                    .put("sha256", "b".repeat(64));
            ids.add(id);
        }
        data.material().set("sourceRecords", sources.deepCopy());
        var result = base.run(data);
        assertEquals("MET", base.status(result).path("status").asText(), result::toPrettyString);
        var proof = proof(result);
        assertEquals("UNAVAILABLE", proof.path("status").asText());
        assertFalse(proof.has("sources"));assertFalse(proof.has("selectedRule"));
    }

    @ParameterizedTest @ValueSource(strings = {"ruleChecksMissing", "ruleChecks33", "exonChecksEmpty", "exonChecksOneMissing", "exonChecks33",
            "exonWitnessMissing", "sourceIdMissing", "traceParameterMissing"})
    void missingOrOversizedExecutionChecksNeverProducePartialPublicProof(String mutation) throws Exception {
        var result = base.run(data(1));
        var audit = result.withObject("resolvedInput").withObject("escapeAssessment");
        switch (mutation) {
            case "ruleChecksMissing" -> audit.remove("ruleChecks");
            case "ruleChecks33" -> {
                var original = audit.at("/ruleChecks/0").deepCopy();var rows = audit.putArray("ruleChecks");
                for (int i = 0; i < 33; i++) rows.add(original.deepCopy());
            }
            case "exonChecksEmpty" -> audit.putArray("exonChecks");
            case "exonChecksOneMissing" -> audit.withArray("exonChecks").remove(0);
            case "exonWitnessMissing" -> audit.remove("affectedReferenceExonOrdinals");
            case "exonChecks33" -> {
                var original = audit.at("/exonChecks/0").deepCopy();var rows = audit.putArray("exonChecks");
                for (int i = 0; i < 33; i++) rows.add(original.deepCopy());
            }
            case "sourceIdMissing" -> ((ObjectNode) audit.at("/selectedRule")).remove("sourceIds");
            case "traceParameterMissing" -> {
                var old = result.at("/evaluation/deterministicTrace/parameters").deepCopy();
                var rows = result.withObject("evaluation").withObject("deterministicTrace").putArray("parameters");
                for (var row : old) if (!"resolvedInputSha256".equals(row.path("name").asText())) rows.add(row);
            }
            default -> throw new AssertionError();
        }
        resealAudit(result);
        var proof = proof(result);
        assertEquals("UNAVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertFalse(proof.has("selectedRule"));assertFalse(proof.has("sources"));
    }

    @ParameterizedTest @ValueSource(strings = {"gene", "allele", "transcript", "selfRehashedContext"})
    void contextReplacementCannotReuseTheSelectedPolicyAndEvaluationIdentity(String mutation) throws Exception {
        var result = base.run(data(1));var context = result.withObject("context");
        switch (mutation) {
            case "gene" -> context.withObject("gene").put("hgncId", "HGNC:999");
            case "allele" -> context.withObject("variant").withObject("evidenceQueryAllele").put("alternate", "T");
            case "transcript" -> context.withObject("transcript").put("version", 2);
            case "selfRehashedContext" -> {
                context.withObject("disease").put("mondoId", "MONDO:0000999");
                context.put("contextId", base.adapter.canonicalContextId(context));
            }
            default -> throw new AssertionError();
        }
        var proof = proof(result);
        assertEquals("UNAVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertFalse(proof.has("scope"));assertFalse(proof.has("selectedRule"));
    }

    @ParameterizedTest @ValueSource(strings = {"firstAltered", "firstChangedInput", "proteinPositionInput", "auditLength",
            "modelFactsMissing", "modelFactHash", "modelFactProteinLength", "modelFactCdsLength", "modelFactsAndAudit"})
    void internallyConsistentEscapeAuditCannotReplaceActualResolvedProteinAndModelFacts(String mutation) throws Exception {
        var result = base.run(data(1));
        assertEquals("AVAILABLE", proof(result).path("status").asText());
        var input = result.withObject("resolvedInput");var audit = input.withObject("escapeAssessment");
        // These source primitives are fixed before tampering: first5, model199,
        // alternate16, affected195, shortening183, critical overlap186.
        assertEquals(5, input.path("firstChangedProteinPosition").asInt());
        assertEquals(5, input.path("proteinPosition").asInt());
        switch (mutation) {
            case "firstAltered" -> {
                audit.put("firstAlteredResidue", 6).put("referenceResiduesAffected", 194);
                ((ObjectNode) audit.at("/ruleChecks/0")).put("affectedResiduesInRegion", 185);
            }
            case "firstChangedInput" -> input.put("firstChangedProteinPosition", 6);
            case "proteinPositionInput" -> input.put("proteinPosition", 6);
            case "auditLength" -> audit.put("referenceProteinLength", 200).put("referenceResiduesAffected", 196)
                    .put("netProteinShortening", 184);
            case "modelFactsMissing" -> result.remove("transcriptModelFacts");
            case "modelFactHash" -> result.withObject("transcriptModelFacts").put("modelSha256", "f".repeat(64));
            case "modelFactProteinLength" -> result.withObject("transcriptModelFacts").put("proteinLength", 200);
            case "modelFactCdsLength" -> result.withObject("transcriptModelFacts").put("cdsLength", 603);
            case "modelFactsAndAudit" -> {
                result.withObject("transcriptModelFacts").put("proteinLength", 200).put("cdsLength", 603);
                audit.put("referenceProteinLength", 200).put("referenceResiduesAffected", 196).put("netProteinShortening", 184);
            }
            default -> throw new AssertionError();
        }
        // First/length modifications deliberately retain internally valid tail
        // and rule arithmetic. Rehashing the audit cannot establish model facts.
        resealAudit(result);
        var proof = proof(result);
        assertEquals("UNAVAILABLE", proof.path("status").asText(), mutation + ": " + proof.toPrettyString());
        assertFalse(proof.has("proteinEffect"));assertFalse(proof.has("selectedRule"));
    }

    @ParameterizedTest @ValueSource(strings = {"oneBase", "missingLast", "otherInRangeBase", "duplicate", "reordered"})
    void referencePtcConditionProofRequiresTheExactThreeExecutedPositionsInOrder(String mutation) throws Exception {
        var data = data(1);
        var condition = data.input().policy.withObject("escapeScope").putArray("positionConditions").addObject()
                .put("conditionId", "COMPLETE_STOP_CODON").put("coordinate", "REFERENCE_PTC_SPAN")
                .put("minimumInclusive", 1).put("maximumExclusive", 100)
                .put("basis", "Synthetic broad bound still requires all three actual reference PTC bases");
        condition.putArray("sourceIds").add("synthetic-source");
        var result = base.run(data);
        assertEquals("AVAILABLE", proof(result).path("status").asText());
        assertEquals(List.of(62, 63, 64), base.mapper.convertValue(result.at("/resolvedInput/escapePositionConditionChecks/0/checkedPositions"),
                new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() { }));
        List<Integer> replaced = switch (mutation) {
            case "oneBase" -> List.of(62);
            case "missingLast" -> List.of(62, 63);
            case "otherInRangeBase" -> List.of(61, 63, 64);
            case "duplicate" -> List.of(62, 63, 63);
            case "reordered" -> List.of(64, 63, 62);
            default -> throw new AssertionError();
        };
        var positions = ((ObjectNode) result.at("/resolvedInput/escapePositionConditionChecks/0")).putArray("checkedPositions");
        replaced.forEach(positions::add);
        resealAudit(result);
        var proof = proof(result);
        assertEquals("UNAVAILABLE", proof.path("status").asText(), mutation + ": " + proof.toPrettyString());
        assertFalse(proof.has("positionConditions"));assertFalse(proof.has("selectedRule"));
    }
}
