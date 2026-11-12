package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.service.CurrentAcmgEvaluationSession;
import org.nbscn.varvis.variantanalysis.service.CurrentAdoptionInventory;
import org.nbscn.varvis.variantanalysis.service.T04CurrentSessionFixture;

/** Independent SYNTHETIC current-session/source controls, never real clinical qualification. */
class T04CurrentContributionTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path temporary;

    private ObjectNode context(T04CurrentSessionFixture fixture) {
        return T04NativeOriginalFixture.context(fixture);
    }

    private static ObjectNode readyBinding(CurrentAcmgEvaluationSession session) {
        assertEquals("READY", session.attempts().get(0).path("bindingStatus").asText());
        return (ObjectNode) session.attempts().get(0).path("binding");
    }

    private static void unknown(ObjectNode applied, String reason) {
        assertEquals("UNKNOWN", applied.at("/ledger/status").asText());
        assertTrue(applied.at("/ledger/pp4Points").isNull());
        assertEquals(reason, applied.at("/ledger/reasonCode").asText());
        assertTrue(applied.at("/ledger/eventIds").isEmpty());
        assertTrue(applied.at("/ledger/exclusiveFactIds").isEmpty());
    }

    @Test void aRealClaimAndUnselectedPp4CannotReplaceAMissingProductionSource() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("missing"), "t04-analysis", "t04-missing-run",
                List.of("ACMG_AMP_2015:PP1"))) {
            var session = fixture.issue(context(fixture)); var binding = readyBinding(session); var before = binding.deepCopy();
            var missing = Pp1ContributionSnapshot.capture(json, null, json.createObjectNode(), "t04-analysis", json.createObjectNode());
            unknown(missing.apply(binding, "t04-analysis", session), "PP1_CURRENT_CONTRIBUTION_SOURCE_UNAVAILABLE");
            assertEquals(before, binding);
            assertEquals("t04-missing-run", session.identity().path("runId").asText());
        }
    }

    @Test void nativeSessionIssuanceAndSerializationDoNotBecomeJsonAuthority() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("session"), "t04-analysis", "t04-session-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var session = fixture.issue(context(fixture)); var original = session.snapshot();
            var next = fixture.reissueSameClaim();
            assertNotEquals(session.identity(), next.identity(), "actual reissuance produces another native evaluation capability");
            assertEquals(session.identity().path("runId"), next.identity().path("runId"));
            var exported = session.snapshot(); exported.put("runId", "forged");
            exported.withArray("attempts").removeAll();
            assertEquals(original, session.snapshot());
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> json.treeToValue(exported, CurrentAcmgEvaluationSession.class));
        }
    }

    @Test void resealedAuditJsonCannotReconstructNativeContributionAuthority() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("forged-audit"), "t04-analysis", "t04-forged-run",
                List.of("ACMG_AMP_2015:PP1"))) {
            var session = fixture.issue(context(fixture)); var binding = readyBinding(session);
            ObjectNode sourceIdentity = json.createObjectNode().put("scope", "CURRENT_EVALUATION_ONLY").put("origin", "SERVICE_OWNED")
                    .put("sourceId", "SYNTHETIC_ADVERSARIAL_SERIALIZED_AUDIT");
            ObjectNode audit = json.createObjectNode().put("schemaVersion", Pp1ContributionSnapshot.SCHEMA).put("analysisId", "t04-analysis")
                    .put("sourceIdentitySha256", Pp1ContributionSnapshot.digest(json, sourceIdentity));
            var entry = audit.putArray("entries").addObject()
                    .put("bindingIdentitySha256", Pp1ContributionSnapshot.digest(json, binding.path("identity")));
            entry.set("scope", binding.at("/ledger/scope").deepCopy()); entry.set("allele", binding.at("/ledger/allele").deepCopy());
            entry.putObject("coverage").put("status", "COMPLETE").put("provenanceSha256", "1".repeat(64))
                    .putArray("checkedResultSha256s").add("2".repeat(64)); entry.putArray("contributions");
            audit.put("snapshotSha256", Pp1ContributionSnapshot.digest(json, audit));
            Pp1ContributionSnapshot.Source auditOnly = adversarialSource(sourceIdentity, audit);
            var captured = Pp1ContributionSnapshot.capture(json, auditOnly, sourceIdentity, "t04-analysis", json.createObjectNode());
            unknown(captured.apply(binding, "t04-analysis", session), "PP1_CURRENT_CONTRIBUTION_NATIVE_SOURCE_REQUIRED");
            var withForgedNativeHeader = audit.deepCopy(); withForgedNativeHeader.remove("snapshotSha256");
            withForgedNativeHeader.set("currentSessionIdentity", session.identity());
            withForgedNativeHeader.put("currentSessionIdentitySha256", Pp1ContributionSnapshot.digest(json, session.identity()));
            withForgedNativeHeader.put("snapshotSha256", Pp1ContributionSnapshot.digest(json, withForgedNativeHeader));
            var forged = Pp1ContributionSnapshot.capture(json, adversarialSource(sourceIdentity, withForgedNativeHeader),
                    sourceIdentity, "t04-analysis", json.createObjectNode());
            assertEquals("UNKNOWN", forged.summary().path("status").asText());
            assertEquals("PP1_CURRENT_CONTRIBUTION_NATIVE_SOURCE_REQUIRED", forged.summary().path("reasonCode").asText());
            unknown(forged.apply(binding, "t04-analysis", session), "PP1_CURRENT_CONTRIBUTION_NATIVE_SOURCE_REQUIRED");
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> json.treeToValue(withForgedNativeHeader, CurrentAdoptionInventory.class));
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> json.treeToValue(withForgedNativeHeader, Pp1CurrentContributionSource.class));
        }
    }

    /** Deliberately forged input for rejection only; never a positive producer or material fixture. */
    private static Pp1ContributionSnapshot.Source adversarialSource(ObjectNode identity, ObjectNode audit) {
        return new Pp1ContributionSnapshot.Source() {
            @Override public ObjectNode identity() { return identity.deepCopy(); }
            @Override public ObjectNode capture(String analysisId, ObjectNode recovered) { return audit.deepCopy(); }
        };
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void completeNativeOriginalsReplayFiveOfTwelveButDoNotGrantAdoption() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-complete"), "t04-analysis", "t04-native-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture); var nativeSources = new T04NativeOriginalFixture(fixture, ctx);
            var originals = nativeSources.originals(); var originalBytes = originals.get(0).raw();
            byte[] callerBytes = originals.get(0).raw(); callerBytes[0] = '!';
            assertArrayEquals(originalBytes, originals.get(0).raw(), "registration original owns its bytes");
            assertThrows(UnsupportedOperationException.class, () -> originals.get(0).tableIds().put("fake", "fake"));
            var registered = registration(nativeSources);
            var verified = Pp4QualifiedMaterial.verify(registered, ctx);
            assertTrue(verified.isComplete(), () -> verified.snapshot().toPrettyString());
            var material = verified.material().orElseThrow(); var before = material.snapshot();
            assertEquals("NATIVE_FACTS_REPLAYED_AND_SCOPE_CHECKED", before.path("status").asText());
            assertEquals(5, before.path("numerator").asInt()); assertEquals(12, before.path("denominator").asInt());
            assertEquals(6, before.path("sourcePins").size());
            assertFalse(before.path("adoptionGranted").asBoolean());
            // Primary Table 2: 41.5% => 2.5; next band is 50.6% => 3.
            // Compare integer cross-products, independently of the runtime lookup implementation.
            assertTrue(5L * 1000 >= 12L * 415); assertTrue(5L * 1000 < 12L * 506);
            var guidanceTable = Pp4PrimaryTableReplay.replay(guidance(),
                    "187c67f0de556fbc9e1bbc0ea1d09f80ecbc53b1e1f07f806b04966dbdf236a1", "PMC10806742", "tbl2");
            assertTrue(guidanceTable.bodyRows().stream().anyMatch(row -> row.cells().get(0).text().equals("41.5%") && row.cells().get(1).text().equals("2.5")));
            assertTrue(guidanceTable.bodyRows().stream().anyMatch(row -> row.cells().get(0).text().equals("50.6%") && row.cells().get(1).text().equals("3")));
            var boundedYield = Pp4DiagnosticYield.calculate(5, 12);
            assertEquals(415, boundedYield.thresholdPerThousand()); assertEquals("2.5", boundedYield.rawPoints());
            assertEquals("2.5", boundedYield.appliedPoints()); assertEquals("MODERATE", boundedYield.strength());
            assertEquals(new java.math.BigDecimal("2.5"), new java.math.BigDecimal("5").subtract(new java.math.BigDecimal(boundedYield.appliedPoints())));
            assertFalse(before.path("clinicalValidationCompleted").asBoolean());
            assertEquals("ABSENT", material.nativeFacts().at("/observations/1/presence").asText());
            assertEquals(12, material.nativeFacts().path("enrollment").size(), "all seven gene-negative rows remain in denominator");
            assertEquals(2, material.observedFactIds().size()); assertEquals(1, material.proposedEventIds().size());
            var fullOriginals = material.originals(); var ownedOriginals = fullOriginals.deepCopy();
            assertEquals(Pp4TestMaterials.hash(fullOriginals), before.path("originalsSha256").asText());
            assertEquals(13, fullOriginals.at("/nativeFacts/originalTableReplays").size());
            assertEquals(6, before.path("resolvedDiagnosticReports").size());
            com.fasterxml.jackson.databind.JsonNode cohortReportLink = null;
            for (var link : before.path("resolvedDiagnosticReports")) if (link.path("declaredRecordId").asText().equals("diagnosis-original-cohort-case-01")) cohortReportLink = link;
            assertNotNull(cohortReportLink);
            assertEquals("MOLECULAR_DIAGNOSIS", cohortReportLink.path("externalDiagnosticConclusion").asText());
            assertEquals("PATHOGENIC", cohortReportLink.at("/variants/0/originalClassification").asText());
            assertEquals(3, cohortReportLink.at("/report/sourceRowNumber").asInt());
            assertEquals(2, cohortReportLink.at("/report/bodyRowNumber").asInt());
            assertEquals("/article[1]/body[1]/table-wrap[1]/table[1]/tbody[1]/tr[2]/td[1]", cohortReportLink.at("/report/recordCellLocator").asText());
            assertEquals("/article[1]/body[1]/table-wrap[1]/table[1]/tbody[1]/tr[2]/td[14]", cohortReportLink.at("/report/conclusionCellLocator").asText());
            assertEquals("/article[1]/body[1]/table-wrap[2]/table[1]/tbody[1]/tr[2]/td[12]", cohortReportLink.at("/variants/0/conclusionCellLocator").asText());
            fullOriginals.removeAll();
            assertEquals(ownedOriginals, material.originals(), "full originals also return owned copies");
            var exported = material.snapshot(); exported.put("numerator", 12).put("adoptionGranted", true);
            assertEquals(before, material.snapshot());
            var exportedRegistration = registered.identity(); exportedRegistration.removeAll();
            assertEquals(6, registered.identity().at("/registeredInputs/originals").size());
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> json.treeToValue(exported, Pp4QualifiedMaterial.class));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void explicitFailedPhenotypeCanBeCompleteButMissingOrUnknownExaminationsCannot() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-phenotype"), "t04-analysis", "t04-phenotype-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture);
            var failed = new T04NativeOriginalFixture(fixture, ctx); failed.set("CLINICAL", "observations", 0, "presence", "ABSENT");
            var completeFailure = Pp4QualifiedMaterial.verify(registration(failed), ctx);
            assertTrue(completeFailure.isComplete(), "completeness is not phenotype satisfaction: " + completeFailure.snapshot());
            assertEquals("ABSENT", completeFailure.material().orElseThrow().nativeFacts().at("/observations/0/presence").asText());
            var missing = new T04NativeOriginalFixture(fixture, ctx); missing.remove("CLINICAL", "observations", 0);
            partial(Pp4QualifiedMaterial.verify(registration(missing), ctx), "PP4_NATIVE_EXAMINATION_ROSTER_INCOMPLETE");
            var unknown = new T04NativeOriginalFixture(fixture, ctx); unknown.set("CLINICAL", "observations", 0, "presence", "UNKNOWN");
            partial(Pp4QualifiedMaterial.verify(registration(unknown), ctx), "PP4_NATIVE_EXAMINATION_UNKNOWN");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void sourceRehashDoesNotRepairAnIncompleteCohortOrCircularDiagnosis() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-cohort"), "t04-analysis", "t04-cohort-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture);
            var closureGap = new T04NativeOriginalFixture(fixture, ctx); closureGap.remove("COHORT", "enrollment", 11);
            partial(Pp4QualifiedMaterial.verify(registration(closureGap), ctx), "PP4_NATIVE_COHORT_ROSTER_INCOMPLETE_OR_EXTRA");
            var circular = new T04NativeOriginalFixture(fixture, ctx); circular.set("COHORT", "enrollment", 0, "diagnosis_basis_criteria", "ACMG_AMP_2015:PP4");
            partial(Pp4QualifiedMaterial.verify(registration(circular), ctx), "PP4_NATIVE_CIRCULAR_DIAGNOSIS_BASIS");
            var target = new T04NativeOriginalFixture(fixture, ctx); target.set("COHORT", "enrollment", 0, "diagnosis_variant_keys", "GRCh38:11:2:T:A");
            partial(Pp4QualifiedMaterial.verify(registration(target), ctx), "PP4_NATIVE_CIRCULAR_DIAGNOSIS_BASIS");
            var related = new T04NativeOriginalFixture(fixture, ctx); related.set("COHORT", "enrollment", 0, "family_id", ctx.at("/patientContext/familyContextId").asText());
            partial(Pp4QualifiedMaterial.verify(registration(related), ctx), "PP4_NATIVE_CURRENT_CASE_FAMILY_OR_DUPLICATE_IN_COHORT");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void originalCurrentVisitAndIndependentGeneDiseaseBasisCannotBeBorrowedOrRelabeled() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-basis"), "t04-analysis", "t04-basis-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture);
            var olderVisit = new T04NativeOriginalFixture(fixture, ctx);
            olderVisit.set("CLINICAL", "observations", 0, "observed_at", "2020-01-01T00:00:00Z");
            partial(Pp4QualifiedMaterial.verify(registration(olderVisit), ctx), "PP4_NATIVE_OBSERVATION_DATE_MISMATCH");
            var olderCohortVisit = new T04NativeOriginalFixture(fixture, ctx);
            olderCohortVisit.set("COHORT", "observations", 0, "observed_at", "2010-01-01T00:00:00Z");
            partial(Pp4QualifiedMaterial.verify(registration(olderCohortVisit), ctx), "PP4_NATIVE_OBSERVATION_DATE_MISMATCH");
            var unsupportedAssertion = new T04NativeOriginalFixture(fixture, ctx);
            unsupportedAssertion.set("GENE_DISEASE_BASIS", "assertion", 0, "validity_assertion", "REVIEWED");
            partial(Pp4QualifiedMaterial.verify(registration(unsupportedAssertion), ctx), "PP4_NATIVE_GENE_DISEASE_ASSERTION_UNESTABLISHED");
            var circularBasis = new T04NativeOriginalFixture(fixture, ctx);
            circularBasis.set("GENE_DISEASE_BASIS", "evidence", 0, "diagnosis_basis_criteria", "ACMG_AMP_2015:PP1");
            partial(Pp4QualifiedMaterial.verify(registration(circularBasis), ctx), "PP4_NATIVE_GENE_DISEASE_CIRCULAR_BASIS");
            var missingSupport = new T04NativeOriginalFixture(fixture, ctx); missingSupport.remove("GENE_DISEASE_BASIS", "evidence", 0);
            partial(Pp4QualifiedMaterial.verify(registration(missingSupport), ctx), "PP4_NATIVE_GENE_DISEASE_SUPPORT_REQUIRED");
            var newScope = ctx.deepCopy(); newScope.withObject("/gene").put("hgncId", "HGNC:2");
            newScope.put("contextId", new RuntimeEvidenceV2Adapter(json).canonicalVersionedContextId(newScope));
            partial(Pp4QualifiedMaterial.verify(registration(new T04NativeOriginalFixture(fixture, ctx)), newScope), "PP4_NATIVE_CASE_CONTEXT_MISMATCH");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void generatedSyntheticRuleOriginalPreservesCatalogAndRejectsCrossScopeAndByteTampering() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-rules"), "t04-analysis", "t04-rules-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture); var template = syntheticRuleOriginal(ctx);
            byte[] raw = json.writeValueAsBytes(template); String rawPin = Pp4TestMaterials.raw(raw), canonicalPin = Pp4TestMaterials.hash(template);
            var rules = Pp4RegisteredRuleInputs.load(raw, rawPin, canonicalPin); var audit = rules.identity();
            assertTrue(rules.matchesContext(ctx));
            assertEquals(template.path("catalogSha256"), audit.path("originalCatalogSha256"));
            assertEquals(template.at("/catalog/sources"), audit.path("catalogSources"));
            assertEquals(template.at("/policy/scientificSources"), audit.path("scientificSources"));
            assertEquals("NO_APPLICABLE_SPECIALIZATION", template.at("/catalog/entries/0/disposition").asText());
            var elsewhere = ctx.deepCopy(); elsewhere.withObject("/disease").put("mondoId", "MONDO:0000002");
            elsewhere.put("contextId", new RuntimeEvidenceV2Adapter(json).canonicalVersionedContextId(elsewhere));
            assertFalse(rules.matchesContext(elsewhere));
            var otherClass = ctx.deepCopy().put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
            otherClass.put("contextId", new RuntimeEvidenceV2Adapter(json).canonicalVersionedContextId(otherClass));
            assertFalse(rules.matchesContext(otherClass));
            raw[raw.length - 2] ^= 1;
            assertThrows(IOException.class, () -> Pp4RegisteredRuleInputs.load(raw, rawPin, canonicalPin));
            var exported = rules.identity(); exported.put("originalCatalogSha256", "0".repeat(64));
            assertEquals(audit, rules.identity());
        }
    }

    private ObjectNode syntheticRuleOriginal(ObjectNode context) throws Exception {
        return T04NativeOriginalFixture.ruleOriginal(context);
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void realNativeProducerReportsAllocationDependencyAndNeverInfersUnselectedZero() throws Exception {
        for (var criteria : List.of(List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"), List.of("ACMG_AMP_2015:PP1"))) {
            try (var fixture = new T04CurrentSessionFixture(temporary.resolve("allocation-" + criteria.size()), "t04-analysis", "t04-allocation-" + criteria.size(), criteria)) {
                var session = fixture.issue(context(fixture)); var pipeline = pipeline(fixture, session);
                var outcome = pipeline.producer().evaluate(session, session.attempts().get(0)); var receipt = outcome.snapshot();
                assertTrue(outcome.matches(session, session.attempts().get(0))); assertFalse(outcome.isComplete());
                assertEquals("UNAVAILABLE", receipt.path("kind").asText(), receipt.toPrettyString());
                assertEquals("PP1_CURRENT_CLINICAL_ALLOCATION_UNAVAILABLE", receipt.path("reasonCode").asText(), receipt.toPrettyString());
                assertEquals("PP4_CURRENT_CLINICAL_ALLOCATION_UNAVAILABLE", receipt.at("/materialDiagnostic/reasonCode").asText());
                assertEquals("NATIVE_FACTS_REPLAYED_AND_SCOPE_CHECKED", receipt.at("/qualification/status").asText());
                assertFalse(receipt.has("rawCoreResult"), "the unchanged core must not run before current clinical allocation exists");
                assertEquals("NOT_RUN_CURRENT_ALLOCATION_UNAVAILABLE", receipt.path("coreExecutionStatus").asText());
                assertTrue(receipt.path("derivedCoreInputSha256").asText().matches("[a-f0-9]{64}"));
                assertTrue(receipt.path("sourceResultSha256").isNull()); assertTrue(receipt.path("evaluationSha256").isNull());
                assertTrue(receipt.path("finalPoints").isNull()); assertTrue(receipt.path("eventIds").isEmpty());
                assertFalse(pipeline.inventory().isComplete());
                var qualified = outcome.qualifiedMaterial().orElseThrow(); var actualOriginals = qualified.originals();
                String originalsPin = Pp4TestMaterials.hash(actualOriginals);
                assertEquals(originalsPin, receipt.path("materialOriginalsSha256").asText());
                assertEquals(originalsPin, qualified.snapshot().path("originalsSha256").asText());
                var privateOriginals = pipeline.inventory().materialOriginals();
                assertEquals(1, privateOriginals.size()); assertTrue(privateOriginals.containsKey(originalsPin));
                assertEquals(actualOriginals, privateOriginals.get(originalsPin));
                assertEquals(originalsPin, Pp4TestMaterials.hash(privateOriginals.get(originalsPin)));
                assertEquals(originalsPin, pipeline.inventory().snapshot().at("/attempts/0/materialOriginalsSha256").asText());
                assertEquals(originalsPin, pipeline.inventory().snapshot().at("/attempts/0/terminalReceipt/materialOriginalsSha256").asText());
                assertEquals(originalsPin, pipeline.inventory().snapshot().at("/materialOriginalSha256s/0").asText());
                assertThrows(UnsupportedOperationException.class, () -> privateOriginals.put("forged", json.createObjectNode()));
                privateOriginals.get(originalsPin).removeAll();
                assertEquals(actualOriginals, pipeline.inventory().materialOriginals().get(originalsPin));
                var referencedSource = pipeline.source().capture("t04-analysis", json.createObjectNode());
                assertNull(referencedSource.findValue("originalTableReplays"), "full thirteen-table originals stay once in the private archive map");
                assertEquals(originalsPin, referencedSource.at("/currentInventory/attempts/0/terminalReceipt/materialOriginalsSha256").asText());
                unknown(pipeline.snapshot().apply(pipeline.binding(), "t04-analysis", session), "PP1_CURRENT_CLINICAL_ALLOCATION_UNAVAILABLE");
                assertFalse(receipt.toString().contains("NOT_SELECTED"), "omitting PP4 does not prove a complete zero without allocation capability");
                var altered = outcome.snapshot(); altered.put("complete", true).put("kind", "ADOPTED").put("finalPoints", "5");
                assertEquals(receipt, outcome.snapshot());
                assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                        () -> json.treeToValue(altered, Pp4CurrentAdoptionProducer.Outcome.class));
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void nativeSnapshotRequiresExactCurrentSessionAndRejectsEveryImmutableLedgerDrift() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-target"), "t04-analysis", "t04-target-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var session = fixture.issue(context(fixture)); var pipeline = pipeline(fixture, session); var binding = pipeline.binding();
            unknown(pipeline.snapshot().apply(binding, "t04-analysis"), "PP1_CURRENT_CONTRIBUTION_TARGET_SESSION_REQUIRED");
            unknown(pipeline.snapshot().apply(binding, "t04-analysis", null), "PP1_CURRENT_CONTRIBUTION_TARGET_SESSION_REQUIRED");
            unknown(pipeline.snapshot().apply(binding, "other-analysis", session), "PP1_CONTRIBUTION_ANALYSIS_MISMATCH");
            var reissued = fixture.reissueSameClaim();
            unknown(pipeline.snapshot().apply(binding, "t04-analysis", reissued), "PP1_CURRENT_CONTRIBUTION_SESSION_MISMATCH");
            for (String field : List.of("selectionKey", "provenanceSha256", "scopeStatement", "schemaVersion")) {
                var drift = binding.deepCopy(); drift.withObject("/ledger").put(field, "SYNTHETIC_WRONG_" + field);
                unknown(pipeline.snapshot().apply(drift, "t04-analysis", session), "PP1_CURRENT_CONTRIBUTION_SESSION_SCOPE_MISMATCH");
            }
            for (String field : List.of("candidateId", "hypothesisId", "sourceSnapshotSha256")) {
                var drift = binding.deepCopy(); drift.withObject("/identity").put(field, "SYNTHETIC_WRONG_" + field);
                unknown(pipeline.snapshot().apply(drift, "t04-analysis", session), "PP1_CURRENT_CONTRIBUTION_SESSION_SCOPE_MISMATCH");
            }
            var differentAllele = binding.deepCopy(); differentAllele.withObject("/ledger/allele").put("alternate", "G");
            unknown(pipeline.snapshot().apply(differentAllele, "t04-analysis", session), "PP1_CURRENT_CONTRIBUTION_SESSION_SCOPE_MISMATCH");
            var differentContext = binding.deepCopy(); differentContext.withObject("/identity/preparedContext/disease").put("mondoId", "MONDO:0000002");
            unknown(pipeline.snapshot().apply(differentContext, "t04-analysis", session), "PP1_CURRENT_CONTRIBUTION_SESSION_SCOPE_MISMATCH");
            var occupied = binding.deepCopy(); occupied.withObject("/ledger").put("status", "KNOWN").put("pp4Points", "1.25")
                    .withArray("eventIds").add("already-owned-event");
            occupied.withObject("/ledger").withArray("exclusiveFactIds").add("already-owned-fact");
            var withheld = pipeline.snapshot().apply(occupied, "t04-analysis", session);
            assertEquals("UNKNOWN", withheld.at("/ledger/status").asText()); assertTrue(withheld.at("/ledger/pp4Points").isNull());
            assertEquals("PP1_CURRENT_CONTRIBUTION_EXISTING_OCCUPANCY_UNRESOLVED", withheld.at("/ledger/reasonCode").asText());
            assertEquals(occupied.path("ledger"), withheld.at("/ledger/unmergedCurrentOccupancy"));
            assertEquals("already-owned-event", withheld.at("/ledger/eventIds/0").asText());
            assertEquals("already-owned-fact", withheld.at("/ledger/exclusiveFactIds/0").asText());
            assertEquals(withheld, pipeline.snapshot().apply(withheld, "t04-analysis", session), "blocked occupancy replay must not recursively nest audit");
            var noTarget = pipeline.snapshot().apply(occupied, "t04-analysis", null);
            assertEquals("PP1_CURRENT_CONTRIBUTION_TARGET_SESSION_REQUIRED", noTarget.at("/ledger/reasonCode").asText());
            assertEquals(occupied.path("ledger"), noTarget.at("/ledger/unmergedCurrentOccupancy"));
            assertEquals(noTarget, pipeline.snapshot().apply(noTarget, "t04-analysis", null));
            var pointsOnly = binding.deepCopy(); pointsOnly.withObject("/ledger").put("status", "KNOWN").put("pp4Points", "2.5");
            assertTrue(pointsOnly.at("/ledger/eventIds").isEmpty()); assertTrue(pointsOnly.at("/ledger/exclusiveFactIds").isEmpty());
            var pointsWithheld = pipeline.snapshot().apply(pointsOnly, "t04-analysis", session);
            assertEquals("UNKNOWN", pointsWithheld.at("/ledger/status").asText()); assertTrue(pointsWithheld.at("/ledger/pp4Points").isNull());
            assertEquals("PP1_CURRENT_CONTRIBUTION_EXISTING_OCCUPANCY_UNRESOLVED", pointsWithheld.at("/ledger/reasonCode").asText());
            assertEquals("2.5", pointsWithheld.at("/ledger/unmergedCurrentOccupancy/pp4Points").asText());
            assertEquals(pointsOnly.path("ledger"), pointsWithheld.at("/ledger/unmergedCurrentOccupancy"));
            assertEquals(pointsWithheld, pipeline.snapshot().apply(pointsWithheld, "t04-analysis", session), "points-only unresolved allocation remains blocked on replay");
            assertEquals(binding, pipeline.binding(), "caller mutations never rewrite native session binding");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void actualSourceAuditCannotBeReimportedAndInventoryCannotMoveBetweenProducerInstances() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-authority"), "t04-analysis", "t04-authority-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var session = fixture.issue(context(fixture)); var pipeline = pipeline(fixture, session);
            var actualAudit = pipeline.source().capture("t04-analysis", json.createObjectNode());
            var copiedAuditSource = adversarialSource(pipeline.source().identity(), actualAudit);
            var rejected = Pp1ContributionSnapshot.capture(json, copiedAuditSource, copiedAuditSource.identity(), "t04-analysis", json.createObjectNode());
            assertEquals("PP1_CURRENT_CONTRIBUTION_NATIVE_SOURCE_REQUIRED", rejected.summary().path("reasonCode").asText());
            unknown(rejected.apply(pipeline.binding(), "t04-analysis", session), "PP1_CURRENT_CONTRIBUTION_NATIVE_SOURCE_REQUIRED");
            var incompleteSources = new T04NativeOriginalFixture(fixture, context(fixture)); incompleteSources.remove("CLINICAL", "observations", 0);
            var incompleteProducer = new Pp4CurrentAdoptionProducer(registration(incompleteSources), pipeline.rules());
            var unqualified = incompleteProducer.evaluate(session, session.attempts().get(0));
            assertTrue(unqualified.qualifiedMaterial().isEmpty());
            assertFalse(unqualified.snapshot().has("materialOriginalsSha256"));
            assertTrue(CurrentAdoptionInventory.capture(session, incompleteProducer).materialOriginals().isEmpty(), "unqualified attempts cannot manufacture an archive entry");
            var sameDependenciesOtherProducer = new Pp4CurrentAdoptionProducer(pipeline.registration(), pipeline.rules());
            assertEquals(pipeline.producer().identity(), sameDependenciesOtherProducer.identity());
            assertFalse(pipeline.inventory().matches(session, sameDependenciesOtherProducer));
            var otherFactory = new Pp1CurrentContributionSource.Factory(sameDependenciesOtherProducer);
            var wrongProducer = assertThrows(IllegalArgumentException.class, () -> otherFactory.forCurrentSession(session, pipeline.inventory()));
            assertEquals("PP1_CURRENT_CONTRIBUTION_NATIVE_INVENTORY_REQUIRED", wrongProducer.getMessage());
            var otherSession = fixture.reissueSameClaim();
            assertFalse(pipeline.inventory().matches(otherSession));
            var wrongSession = assertThrows(IllegalArgumentException.class,
                    () -> new Pp1CurrentContributionSource.Factory(pipeline.producer()).forCurrentSession(otherSession, pipeline.inventory()));
            assertEquals("PP1_CURRENT_CONTRIBUTION_NATIVE_INVENTORY_REQUIRED", wrongSession.getMessage());
            var inventoryBefore = pipeline.inventory().snapshot(); var mutateInventory = pipeline.inventory().snapshot();
            mutateInventory.put("complete", true); mutateInventory.withArray("attempts").removeAll();
            assertEquals(inventoryBefore, pipeline.inventory().snapshot());
            actualAudit.withArray("entries").removeAll(); actualAudit.put("snapshotSha256", "0".repeat(64));
            assertFalse(pipeline.source().capture("t04-analysis", json.createObjectNode()).path("entries").isEmpty());
            var recovered = json.createObjectNode().put("analysisId", "t04-analysis").put("complete", true).put("pp4Points", "0");
            assertEquals(pipeline.source().capture("t04-analysis", json.createObjectNode()), pipeline.source().capture("t04-analysis", recovered));
            var wrongRecovered = assertThrows(IOException.class,
                    () -> pipeline.source().capture("t04-analysis", json.createObjectNode().put("analysisId", "another-run")));
            assertEquals("PP1_CURRENT_CONTRIBUTION_ANALYSIS_MISMATCH", wrongRecovered.getMessage());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void immutableCurrentUnavailableInventoryCanBeReadConcurrentlyWithoutCreatingZero() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-concurrent"), "t04-analysis", "t04-concurrent-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var session = fixture.issue(context(fixture)); var pipeline = pipeline(fixture, session);
            var auditBefore = pipeline.source().capture("t04-analysis", json.createObjectNode());
            var expected = pipeline.snapshot().apply(pipeline.binding(), "t04-analysis", session);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                var tasks = new ArrayList<java.util.concurrent.Callable<ObjectNode>>();
                for (int i = 0; i < 24; i++) tasks.add(() -> {
                    var copy = pipeline.source().capture("t04-analysis", json.createObjectNode()); copy.removeAll();
                    return pipeline.snapshot().apply(pipeline.binding(), "t04-analysis", session);
                });
                for (var future : executor.invokeAll(tasks)) assertEquals(expected, future.get());
            } finally {
                executor.shutdown();
                if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS), "synthetic reader executor must terminate");
                }
            }
            unknown(expected, "PP1_CURRENT_CLINICAL_ALLOCATION_UNAVAILABLE");
            assertEquals(auditBefore, pipeline.source().capture("t04-analysis", json.createObjectNode()));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    @EnabledIfSystemProperty(named = "varvis.t04.primary-source-xml", matches = ".+")
    void actualLiteratureTableIsReplayedButCannotMasqueradeAsCompleteNativeClinicalMaterial() throws Exception {
        byte[] actual = Files.readAllBytes(Path.of(System.getProperty("varvis.t04.primary-source-xml")));
        assertEquals("0a542ec70647ba134445511e7c80ae55da005827dfb014aff5d38d7ecf8d13ed", Pp4TestMaterials.raw(actual));
        var original = new Pp4ProductionMethodRegistration.PrimaryArtifact("CLINICAL", "PMC12048594_ORIGINAL_TAB1", "PMC12048594",
                Map.of("case", "Tab1", "observations", "Tab1", "candidates", "Tab1"), actual,
                "0a542ec70647ba134445511e7c80ae55da005827dfb014aff5d38d7ecf8d13ed",
                "https://pmc.ncbi.nlm.nih.gov/articles/PMC12048594/", "2026-11-04T00:00:00Z");
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("actual-partial"), "t04-analysis", "t04-partial-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture); var artifacts = new ArrayList<>(new T04NativeOriginalFixture(fixture, ctx).originals());
            artifacts.removeIf(item -> item.role().equals("CLINICAL")); artifacts.add(original);
            var inputs = Pp4ProductionMethodRegistration.RegisteredInputConfiguration.load(guidance(), artifacts, "SYNTHETIC");
            var registration = Pp4ProductionMethodRegistration.register(inputs);
            partial(Pp4QualifiedMaterial.verify(registration, ctx), "PP4_NATIVE_PROFILE_HEADERS_UNSUPPORTED");
            assertArrayEquals(actual, original.raw(), "actual primary bytes are neither rewritten nor completed with generated clinical facts");
        }
    }

    private record Pipeline(Pp4ProductionMethodRegistration registration, Pp4RegisteredRuleInputs rules,
            Pp4CurrentAdoptionProducer producer, CurrentAdoptionInventory inventory, Pp1CurrentContributionSource source,
            Pp1ContributionSnapshot snapshot, ObjectNode binding) { }

    private Pipeline pipeline(T04CurrentSessionFixture fixture, CurrentAcmgEvaluationSession session) throws Exception {
        var binding = readyBinding(session); var context = (ObjectNode) binding.at("/identity/preparedContext");
        var registration = registration(new T04NativeOriginalFixture(fixture, context)); var template = syntheticRuleOriginal(context);
        byte[] bytes = json.writeValueAsBytes(template);
        var rules = Pp4RegisteredRuleInputs.load(bytes, Pp4TestMaterials.raw(bytes), Pp4TestMaterials.hash(template));
        var producer = new Pp4CurrentAdoptionProducer(registration, rules);
        var inventory = CurrentAdoptionInventory.capture(session, producer);
        var source = new Pp1CurrentContributionSource.Factory(producer).forCurrentSession(session, inventory);
        var snapshot = Pp1ContributionSnapshot.capture(json, source, source.identity(), "t04-analysis", json.createObjectNode());
        assertEquals("CAPTURED", snapshot.summary().path("status").asText(), snapshot.summary().toPrettyString());
        return new Pipeline(registration, rules, producer, inventory, source, snapshot, binding);
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void unknownSexDoesNotBecomeAKnownConflictOrAnInventedSexValue() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-sex"), "t04-analysis", "t04-sex-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture);
            var sourceUnknown = new T04NativeOriginalFixture(fixture, ctx); sourceUnknown.set("CLINICAL", "case", 0, "sex", "UNKNOWN");
            var unknownVerified = Pp4QualifiedMaterial.verify(registration(sourceUnknown), ctx);
            assertTrue(unknownVerified.isComplete(), unknownVerified.snapshot().toPrettyString());
            assertEquals("UNKNOWN", unknownVerified.material().orElseThrow().nativeFacts().at("/case/sex").asText());
            var contextUnknown = ctx.deepCopy(); contextUnknown.withObject("/patientContext").put("sex", "UNKNOWN");
            contextUnknown.put("contextId", new RuntimeEvidenceV2Adapter(json).canonicalVersionedContextId(contextUnknown));
            var knownSource = new T04NativeOriginalFixture(fixture, ctx);
            var unknownContextVerified = Pp4QualifiedMaterial.verify(registration(knownSource), contextUnknown);
            assertTrue(unknownContextVerified.isComplete(), unknownContextVerified.snapshot().toPrettyString());
            assertEquals("UNKNOWN", unknownContextVerified.material().orElseThrow().snapshot().at("/context/patientContext/sex").asText());
            var conflicting = new T04NativeOriginalFixture(fixture, ctx); conflicting.set("CLINICAL", "case", 0, "sex", "MALE");
            partial(Pp4QualifiedMaterial.verify(registration(conflicting), ctx), "PP4_NATIVE_SEX_CONTEXT_CONFLICT");
            var invalid = new T04NativeOriginalFixture(fixture, ctx); invalid.set("CLINICAL", "case", 0, "sex", "UNSPECIFIED_FREE_TEXT");
            partial(Pp4QualifiedMaterial.verify(registration(invalid), ctx), "PP4_NATIVE_SEX_VALUE_INVALID");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void testedUndiagnosedAndOtherGeneCasesRemainInDenominatorWithoutInventedPositiveDiagnosis() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-tested-negative"), "t04-analysis", "t04-tested-negative-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture); var originals = new T04NativeOriginalFixture(fixture, ctx);
            for (int index = 5; index < 12; index++) for (String field : List.of("diagnosis_at", "diagnostic_hgnc_id", "diagnostic_mondo_id",
                    "diagnosis_basis_method", "diagnosis_basis_record_id", "diagnosis_basis_criteria", "diagnosis_variant_keys"))
                originals.set("COHORT", "enrollment", index, field, "");
            // One tested case has a dated independent diagnosis in a different gene: it is still target-gene negative.
            originals.set("COHORT", "enrollment", 6, "diagnosis_at", "2020-04-01T00:00:00Z");
            originals.set("COHORT", "enrollment", 6, "diagnostic_hgnc_id", "HGNC:2");
            originals.set("COHORT", "enrollment", 6, "diagnostic_mondo_id", "MONDO:0000002");
            originals.set("COHORT", "enrollment", 6, "diagnosis_basis_method", "Independent molecular diagnosis of a different gene");
            originals.set("COHORT", "enrollment", 6, "diagnosis_basis_record_id", "other-gene-original-report");
            originals.set("COHORT", "enrollment", 6, "diagnosis_basis_criteria", "ACMG_AMP_2015:PVS1|ACMG_AMP_2015:PM2");
            originals.set("COHORT", "enrollment", 6, "diagnosis_variant_keys", "GRCh38:12:3:C:G");
            var verified = Pp4QualifiedMaterial.verify(registration(originals), ctx);
            assertTrue(verified.isComplete(), "tested target-gene-negative rows must not require invented positive diagnosis fields: " + verified.snapshot());
            var material = verified.material().orElseThrow();
            assertEquals(5, material.snapshot().path("numerator").asInt()); assertEquals(12, material.snapshot().path("denominator").asInt());
            assertEquals("", material.nativeFacts().at("/enrollment/5/diagnosis_at").asText());
            assertEquals("", material.nativeFacts().at("/enrollment/5/diagnostic_hgnc_id").asText());
            assertEquals("HGNC:2", material.nativeFacts().at("/enrollment/6/diagnostic_hgnc_id").asText());
            assertEquals("FALSE", material.nativeFacts().at("/enrollment/6/diagnosed_in_gene").asText());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void positiveMolecularDiagnosesRequireOriginalReportIdentityScopeAndEveryReferencedVariant() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-diagnostic-reports"), "t04-analysis", "t04-reports-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture);
            var missing = new T04NativeOriginalFixture(fixture, ctx); missing.remove("DIAGNOSTIC_REPORTS", "reports", 1);
            partial(Pp4QualifiedMaterial.verify(registration(missing), ctx), "PP4_NATIVE_DIAGNOSIS_REPORT_RECORD_UNRESOLVED");
            var wrongCase = new T04NativeOriginalFixture(fixture, ctx); wrongCase.set("DIAGNOSTIC_REPORTS", "reports", 1, "case_id", "unrelated-original-case");
            partial(Pp4QualifiedMaterial.verify(registration(wrongCase), ctx), "PP4_NATIVE_DIAGNOSIS_REPORT_CASE_MISMATCH");
            var wrongScope = new T04NativeOriginalFixture(fixture, ctx); wrongScope.set("DIAGNOSTIC_REPORTS", "reports", 1, "hgnc_id", "HGNC:2");
            partial(Pp4QualifiedMaterial.verify(registration(wrongScope), ctx), "PP4_NATIVE_DIAGNOSIS_REPORT_SCOPE_MISMATCH");
            var wrongSample = new T04NativeOriginalFixture(fixture, ctx); wrongSample.set("DIAGNOSTIC_REPORTS", "variants", 1, "sample_id", "another-source-sample");
            partial(Pp4QualifiedMaterial.verify(registration(wrongSample), ctx), "PP4_NATIVE_DIAGNOSIS_REPORT_SAMPLE_OR_GENOTYPE_UNSUPPORTED");
            var nonHeterozygous = new T04NativeOriginalFixture(fixture, ctx); nonHeterozygous.set("DIAGNOSTIC_REPORTS", "variants", 1, "genotype", "1/1");
            partial(Pp4QualifiedMaterial.verify(registration(nonHeterozygous), ctx), "PP4_NATIVE_DIAGNOSIS_REPORT_SAMPLE_OR_GENOTYPE_UNSUPPORTED");
            var missingVariant = new T04NativeOriginalFixture(fixture, ctx); missingVariant.remove("DIAGNOSTIC_REPORTS", "variants", 1);
            partial(Pp4QualifiedMaterial.verify(registration(missingVariant), ctx), "PP4_NATIVE_DIAGNOSIS_REPORT_VARIANTS_INCOMPLETE");
            var partialRoster = new T04NativeOriginalFixture(fixture, ctx);
            partialRoster.set("DIAGNOSTIC_REPORTS", "reports", 1, "native_variant_keys", "GRCh38:11:3:C:G|GRCh38:11:5:A:C");
            partial(Pp4QualifiedMaterial.verify(registration(partialRoster), ctx), "PP4_NATIVE_DIAGNOSIS_REPORT_VARIANTS_INCOMPLETE");
            var completeCombination = new T04NativeOriginalFixture(fixture, ctx);
            completeCombination.set("DIAGNOSTIC_REPORTS", "reports", 1, "native_variant_keys", "GRCh38:11:3:C:G|GRCh38:11:5:A:C");
            completeCombination.set("DIAGNOSTIC_REPORTS", "reports", 1, "variant_record_ids", "variant-diagnosis-original-cohort-case-01|second-original-variant");
            completeCombination.set("COHORT", "enrollment", 0, "diagnosis_variant_keys", "GRCh38:11:3:C:G|GRCh38:11:5:A:C");
            completeCombination.add("DIAGNOSTIC_REPORTS", "variants", T04NativeOriginalFixture.row("second-original-variant", "report-cohort-case-01", "GRCh38", "11", "5", "A", "C",
                    "sample-cohort-case-01", "0/1", "EXOME_AND_CODING_SPLICE_VALIDATION", "assay-v1", "PATHOGENIC", "2020-04-01T00:00:00Z", "ACMG_AMP_2015:PVS1|ACMG_AMP_2015:PM2"));
            partial(Pp4QualifiedMaterial.verify(registration(completeCombination), ctx), "PP4_NATIVE_DIAGNOSIS_REPORT_COMBINATION_UNSUPPORTED");
            var unsupportedResult = new T04NativeOriginalFixture(fixture, ctx); unsupportedResult.set("DIAGNOSTIC_REPORTS", "variants", 1, "classification", "UNCERTAIN_SIGNIFICANCE");
            partial(Pp4QualifiedMaterial.verify(registration(unsupportedResult), ctx), "PP4_NATIVE_DIAGNOSIS_REPORT_CONCLUSION_UNSUPPORTED");
            var wrongBasisReport = new T04NativeOriginalFixture(fixture, ctx);
            wrongBasisReport.set("GENE_DISEASE_BASIS", "evidence", 0, "diagnosis_basis_record_id", "missing-original-basis-report");
            partial(Pp4QualifiedMaterial.verify(registration(wrongBasisReport), ctx), "PP4_NATIVE_DIAGNOSIS_REPORT_RECORD_UNRESOLVED");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void originalDiagnosticCriteriaMustBeRecognizedIndependentAndFreeOfUnresolvedDirectionConflict() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-diagnostic-criteria"), "t04-analysis", "t04-report-criteria-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture);
            var unknown = new T04NativeOriginalFixture(fixture, ctx);
            unknown.set("COHORT", "enrollment", 0, "diagnosis_basis_criteria", "ACMG_AMP_2015:ZZ9");
            unknown.set("DIAGNOSTIC_REPORTS", "variants", 1, "criteria", "ACMG_AMP_2015:ZZ9");
            partial(Pp4QualifiedMaterial.verify(registration(unknown), ctx), "PP4_NATIVE_DIAGNOSIS_CRITERION_UNKNOWN");
            var circularOriginal = new T04NativeOriginalFixture(fixture, ctx);
            circularOriginal.set("DIAGNOSTIC_REPORTS", "variants", 1, "criteria", "ACMG_AMP_2015:PP4");
            partial(Pp4QualifiedMaterial.verify(registration(circularOriginal), ctx), "PP4_NATIVE_DIAGNOSIS_CIRCULAR_BASIS");
            var opposite = new T04NativeOriginalFixture(fixture, ctx);
            opposite.set("COHORT", "enrollment", 0, "diagnosis_basis_criteria", "ACMG_AMP_2015:PS3|ACMG_AMP_2015:BP4");
            opposite.set("DIAGNOSTIC_REPORTS", "variants", 1, "criteria", "ACMG_AMP_2015:PS3|ACMG_AMP_2015:BP4");
            partial(Pp4QualifiedMaterial.verify(registration(opposite), ctx), "PP4_NATIVE_DIAGNOSIS_MIXED_BASIS_UNSUPPORTED");
            var allBenign = new T04NativeOriginalFixture(fixture, ctx);
            allBenign.set("COHORT", "enrollment", 0, "diagnosis_basis_criteria", "ACMG_AMP_2015:BS1");
            allBenign.set("DIAGNOSTIC_REPORTS", "variants", 1, "criteria", "ACMG_AMP_2015:BS1");
            partial(Pp4QualifiedMaterial.verify(registration(allBenign), ctx), "PP4_NATIVE_DIAGNOSIS_BASIS_REQUIRES_CONFLICT_REVIEW");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void unknownOrUnperformedTestingCannotBeCountedAsGeneNegativeAndDefinitionMustPredateEligibility() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-cohort-time"), "t04-analysis", "t04-cohort-time-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture);
            var unknown = new T04NativeOriginalFixture(fixture, ctx); unknown.set("COHORT", "enrollment", 5, "diagnosed_in_gene", "UNKNOWN");
            partial(Pp4QualifiedMaterial.verify(registration(unknown), ctx), "PP4_NATIVE_DIAGNOSIS_OUTCOME_UNKNOWN");
            for (String unestablished : List.of("", "UNKNOWN", "PENDING", "NOT_PERFORMED")) {
                var untested = new T04NativeOriginalFixture(fixture, ctx); untested.set("COHORT", "enrollment", 5, "tested_at", unestablished);
                partial(Pp4QualifiedMaterial.verify(registration(untested), ctx), "PP4_NATIVE_TESTED_RECORD_UNESTABLISHED");
            }
            var lateDefinition = new T04NativeOriginalFixture(fixture, ctx); lateDefinition.set("DEFINITION", "definition", 0, "specified_at", "2020-03-01T00:00:00Z");
            partial(Pp4QualifiedMaterial.verify(registration(lateDefinition), ctx), "PP4_NATIVE_COHORT_DEFINITION_NOT_PRESPECIFIED");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void noPrimaryRoleCanContainFactsDatedAfterItsOriginalWasAcquired() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("native-acquisition"), "t04-analysis", "t04-acquisition-run",
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"))) {
            var ctx = context(fixture);
            for (var roleAndCutoff : Map.of("CLINICAL", "2023-12-31T00:00:00Z", "CLINICIAN", "2024-02-01T00:00:00Z",
                    "DEFINITION", "2018-12-31T00:00:00Z", "COHORT", "2020-04-15T00:00:00Z",
                    "GENE_DISEASE_BASIS", "2018-12-31T00:00:00Z", "DIAGNOSTIC_REPORTS", "2017-12-31T00:00:00Z").entrySet()) {
                var future = new T04NativeOriginalFixture(fixture, ctx); future.acquiredAt(roleAndCutoff.getKey(), roleAndCutoff.getValue());
                partial(Pp4QualifiedMaterial.verify(registration(future), ctx), "PP4_NATIVE_SOURCE_ACQUISITION_CUTOFF_MISMATCH");
            }
        }
    }

    private static void partial(Pp4QualifiedMaterial.Verification verification, String reason) {
        assertFalse(verification.isComplete()); assertTrue(verification.material().isEmpty());
        String expectedStatus = switch (reason) {
            case "PP4_NATIVE_PROFILE_HEADERS_UNSUPPORTED", "PP4_NATIVE_DIAGNOSIS_REPORT_SAMPLE_OR_GENOTYPE_UNSUPPORTED",
                    "PP4_NATIVE_DIAGNOSIS_REPORT_COMBINATION_UNSUPPORTED", "PP4_NATIVE_DIAGNOSIS_REPORT_CONCLUSION_UNSUPPORTED",
                    "PP4_NATIVE_DIAGNOSIS_MIXED_BASIS_UNSUPPORTED" -> "NATIVE_PROFILE_UNSUPPORTED";
            default -> "PARTIAL_OR_CONFLICT";
        };
        assertEquals(expectedStatus, verification.snapshot().path("status").asText());
        assertEquals(reason, verification.snapshot().path("reasonCode").asText());
        assertFalse(verification.snapshot().path("adoptionGranted").asBoolean());
    }

    private static byte[] guidance() throws IOException {
        return Files.readAllBytes(Path.of(System.getProperty("varvis.t04.guidance-xml")));
    }

    private Pp4ProductionMethodRegistration registration(T04NativeOriginalFixture sources) throws IOException {
        return Pp4ProductionMethodRegistration.register(Pp4ProductionMethodRegistration.RegisteredInputConfiguration.load(
                guidance(), sources.originals(), "SYNTHETIC"));
    }
}