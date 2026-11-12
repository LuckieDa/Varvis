package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Existing native expert originals/adoption replay. HNF1A is retained; the generic dependency test grants no TPM1/HCM PP2. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Pp2OtherPhenotypeSourceTest {
    @TempDir static Path work;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private Path sourceRoot, project, python;
    private Pp2OtherPhenotypeSource source;
    private Pp2OtherPhenotypeSource.Observed observed;
    private Pp2OtherPhenotypeSource.ExpertInventory inventory;
    private ObjectNode actualFacts;
    private static final Path MANIFEST = Path.of("local/development/handoffs/PS1-PM5-expert-evidence-batch-20261013/dev-01/input-manifest-final.json");
    private static final Path BUNDLE = Path.of("local/runtime/bp7-evidence-workbench/materials/ps1-research-v2-20261016/bundle.json");

    @BeforeAll void existingOriginalsOnly() throws Exception {
        var root = System.getProperty("varvis.pp2.sourceRoot");
        assumeTrue(root != null, "Actual existing source root must be supplied; a skip is not source replay acceptance");
        sourceRoot = Path.of(root); project = Path.of(System.getProperty("varvis.pp2.projectRoot", ".." )).toAbsolutePath().normalize();
        python = Path.of(System.getProperty("varvis.pp2.python", sourceRoot.resolve("local/tools/alphagenome/python/python.exe").toString()));
        byte[] bundle = Files.readAllBytes(sourceRoot.resolve(BUNDLE));
        inventory = new Pp2OtherPhenotypeSource.ExpertInventory("existing_native_expert", sourceRoot, MANIFEST,
                "37df23f04ce27e32d1c3ddb32a7d6d16bd4399a1e9cd77da8266ef56030c5b8d",
                BUNDLE, CodingTranscriptMaterialSupply.sha(bundle), adapter.canonicalSha256(mapper.readTree(bundle)));
        source = new Pp2OtherPhenotypeSource(mapper, python, project, work, List.of(inventory), List.of());
        observed = source.observe();
        for (var row : observed.privateArchive().at("/readerFacts/expertScopes/0/references"))
            if ("1033090".equals(row.at("/facts/variationId").asText())) actualFacts = (ObjectNode) row.path("facts").deepCopy();
        assertNotNull(actualFacts, "Existing native record unavailable: " + observed.snapshot().path("readerStatus").asText()
                + "/" + observed.privateArchive().at("/readerFacts/reasonCode").asText());
    }
    private ObjectNode context() {
        var out = mapper.createObjectNode().put("contextId", "ENGINEERING_DEPENDENCY_ONLY_HNF1A_RETAINED");
        out.putObject("gene").put("symbol", "HNF1A"); out.putObject("disease").put("mondoId", "MONDO:0005045");
        out.putObject("variant").set("evidenceQueryAllele", actualFacts.path("allele").deepCopy());
        return out;
    }
    private ObjectNode policy() {
        return mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PP2").put("specificationId", "GN100")
                .put("version", "1.0").put("originalRawSha256", "4dc9861b487b1ff09e348aa80979b67bd410184ded2471597c0ae30fbee3937d")
                .put("fixtureMeaning", "GENERIC_DEPENDENCY_BINDING_NOT_HCM_GENE_SCOPE_APPROVAL");
    }
    private Pp2OtherPhenotypeSource.Review review() { return source.capture(observed, context(), "one", policy()).review(); }

    @Test void actualNativeExpertBytesIssueAQualifiedOtherPhenotypeWithoutMovingTheOriginalGene() {
        var value = review(); assertEquals(Pp2OtherPhenotypeSource.Outcome.SUPPORTS_OTHER_PHENOTYPE, value.outcome());
        var facts = value.snapshot().at("/qualifiedEvidence/0");
        assertEquals("SCV002032337.1", facts.path("scv").asText());
        assertEquals("MONDO:0015967", facts.at("/disease/id").asText());
        assertEquals("monogenic diabetes", facts.at("/disease/label").asText());
        assertEquals("DISTINCT", facts.path("relation").asText());
        assertEquals("HNF1A", actualFacts.path("geneSymbols").get(0).asText());
        assertEquals(120978826, value.snapshot().at("/binding/allele/position").asInt());
        assertFalse(value.snapshot().toString().contains("base64"));
        assertFalse(value.snapshot().toString().contains("originalAssertion"));
        assertTrue(value.matches(context(), "one", policy()));
    }
    @Test void unrelatedPendingDoesNotEraseAnEstablishedExclusionAndDoesNotBecomeClear() {
        var value = review().snapshot(); assertEquals("SUPPORTS_OTHER_PHENOTYPE", value.path("outcome").asText());
        assertTrue(value.path("pending").toString().contains("CASE_RELATIONSHIP_NOT_IMPLEMENTED"));
        assertEquals("SOFTWARE_UNSUPPORTED", value.at("/requiredScope/1/status").asText());
    }
    @Test void differentTargetCannotBorrowAnExpertRecordAndEmptyPortfolioCannotClear() {
        var context = context(); ((ObjectNode) context.at("/variant/evidenceQueryAllele")).put("position", 120978827);
        var value = source.capture(observed, context, "another", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, value.outcome());
        assertTrue(value.snapshot().path("qualifiedEvidence").isEmpty());
        assertTrue(value.snapshot().path("pending").toString().contains("TARGET_NATIVE_EXPERT_SCOPE_NOT_ESTABLISHED"));
    }
    @Test void sameExpertDiseaseIsNotOtherAndUnsupportedLiteratureStillBlocksClear() {
        var context = context(); ((ObjectNode) context.path("disease")).put("mondoId", actualFacts.at("/disease/id").asText());
        var value = source.capture(observed, context, "one", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, value.outcome());
        assertTrue(value.snapshot().path("qualifiedEvidence").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"familial hypertrophic cardiomyopathy", "hypertrophic cardiomyopathy 2"})
    void actualDiseaseSemanticMethodKeepsHcmFamilyDespiteDifferentIdentifiers(String label) {
        var facts = semanticFacts(label, "MONDO:1234567");
        assertEquals(Pp2OtherPhenotypeSource.Relation.SAME_HCM_FAMILY,
                new Pp2OtherPhenotypeDiseaseSemantics(mapper).resolve(facts, context(), policy()));
    }
    @ParameterizedTest @ValueSource(strings = {"cardiomyopathy", "dilated cardiomyopathy", "end-stage hypertrophic cardiomyopathy with ventricular dilation", "diabetes and cardiomyopathy", "some unrelated disease"})
    void differentIdentifiersAndAmbiguousClinicalWordsDoNotProveDistinctDisease(String label) {
        var facts = semanticFacts(label, "MONDO:1234567");
        assertEquals(Pp2OtherPhenotypeSource.Relation.UNRESOLVED,
                new Pp2OtherPhenotypeDiseaseSemantics(mapper).resolve(facts, context(), policy()));
    }
    @Test void mixedTraitSetCannotBorrowTheFavorableExpertSummary() {
        var facts = actualFacts.deepCopy(); var condition = (ArrayNode) facts.at("/originalAssertion/condition");
        condition.add(condition.get(0).deepCopy());
        assertEquals(Pp2OtherPhenotypeSource.Relation.UNRESOLVED,
                new Pp2OtherPhenotypeDiseaseSemantics(mapper).resolve(facts, context(), policy()));
    }
    @Test void aNativeMondoXrefWithBareSevenDigitsMeansTheSameDiseaseWithoutChangingTheOriginal() {
        var facts = actualFacts.deepCopy(); changeMondo(facts.at("/originalAssertion/condition"), "0015967");
        assertEquals(Pp2OtherPhenotypeSource.Relation.DISTINCT,
                new Pp2OtherPhenotypeDiseaseSemantics(mapper).resolve(facts, context(), policy()));
        assertEquals("MONDO:0015967", actualFacts.at("/disease/id").asText());
    }
    private ObjectNode semanticFacts(String label, String id) {
        // Pure predicate counterexample only: not written as an original/adoption, not passed to capture, never a scientific grant.
        var facts = actualFacts.deepCopy(); ((ObjectNode) facts.path("disease")).put("id", id).put("label", label);
        ((ObjectNode) facts.path("originalExpertSummary")).put("condition", label);
        changeMondo(facts.at("/originalAssertion/condition"), id); return facts;
    }
    private void changeMondo(JsonNode node, String id) {
        if (node.isArray()) { for (var value : node) changeMondo(value, id); return; }
        if (!node.isObject()) return;
        if ("XRef".equals(node.path("tag").asText()) && "MONDO".equals(node.at("/attributes/DB").asText()))
            ((ObjectNode) node.path("attributes")).put("ID", id);
        for (var child : node.path("children")) changeMondo(child, id);
    }
    @Test void copiedReviewCannotRestoreAuthorityAndReturnedSnapshotsAreDefensive() throws Exception {
        var value = review(); var snapshot = value.snapshot(); snapshot.put("outcome", "NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE");
        assertEquals(Pp2OtherPhenotypeSource.Outcome.SUPPORTS_OTHER_PHENOTYPE, value.outcome());
        assertThrows(IOException.class, () -> mapper.readValue(mapper.writeValueAsBytes(snapshot), Pp2OtherPhenotypeSource.Review.class));
        assertThrows(IOException.class, () -> mapper.readValue(mapper.writeValueAsBytes(snapshot), Pp2OtherPhenotypeSource.Captured.class));
        assertThrows(IOException.class, () -> mapper.readValue(mapper.writeValueAsBytes(observed.snapshot()), Pp2OtherPhenotypeSource.Observed.class));
        var otherContext = context(); otherContext.put("contextId", "foreign");
        assertFalse(value.matches(otherContext, "one", policy())); assertFalse(value.matches(context(), "foreign", policy()));
        var otherPolicy = policy(); otherPolicy.put("version", "foreign"); assertFalse(value.matches(context(), "one", otherPolicy));
    }
    @Test void anotherOwnerCannotUseAnObservedHandleFromThisOwner() {
        var other = new Pp2OtherPhenotypeSource(mapper, python, project, work, List.of(inventory), List.of());
        assertThrows(IllegalArgumentException.class, () -> other.capture(observed, context(), "one", policy()));
    }
    @Test void privateArchiveStoresOriginalBytesOnceAndObservationsDoNotDependOnWorkDirectoryNames() {
        var archive = observed.privateArchive(); var keys = archive.path("originals").fieldNames();
        while (keys.hasNext()) { String key = keys.next(); var original = archive.path("originals").path(key);
            assertEquals(key, CodingTranscriptMaterialSupply.sha(java.util.Base64.getDecoder().decode(original.path("base64").asText()))); }
        assertFalse(observed.identity().toString().contains("base64"));
        assertFalse(observed.identity().toString().contains(work.toString()));
        assertTrue(observed.snapshot().path("readDispositions").size() > 0);
    }
    private Pp2OtherPhenotypeSource transport(ObjectNode returnedFacts) {
        // Invalid reader-output transport control, not a source/adoption generator. All originals and adoption remain unchanged.
        return new Pp2OtherPhenotypeSource(mapper, python, project, work, List.of(inventory), List.of(),
                new Pp2OtherPhenotypeDiseaseSemantics(mapper), (command, directory, timeout) -> {
                    Path input = Path.of(command.get(command.indexOf("--input") + 1));
                    Path output = Path.of(command.get(command.indexOf("--output") + 1));
                    var copy = returnedFacts.deepCopy(); copy.put("inputRawSha256", CodingTranscriptMaterialSupply.sha(Files.readAllBytes(input)));
                    Files.write(output, mapper.writeValueAsBytes(copy)); return new ProcessResult(0, "ENGINEERING_TRANSPORT_ONLY", false);
                });
    }
    @Test void nativeSourcePinsMustMatchTheActualExistingAdoptionSourceBytes() {
        var facts = (ObjectNode) observed.privateArchive().path("readerFacts").deepCopy();
        ((ObjectNode) facts.at("/expertScopes/0/sourcePins")).put("clinvar_vcv-1d4df594b2a13d7b", "0".repeat(64));
        var owner = transport(facts); var value = owner.capture(owner.observe(), context(), "one", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, value.outcome());
        assertTrue(value.snapshot().path("pending").toString().contains("PP2_NATIVE_EXPERT_SOURCE_PIN_MISMATCH"));
    }
    @Test void readerCannotInventAChangedNativeFactInsideAnOtherwiseMatchingSource() {
        var facts = (ObjectNode) observed.privateArchive().path("readerFacts").deepCopy();
        for (var row : facts.at("/expertScopes/0/references")) if ("1033090".equals(row.at("/facts/variationId").asText()))
            ((ObjectNode) row.at("/facts/originalAssertion")).put("classification", "Benign");
        var owner = transport(facts); var value = owner.capture(owner.observe(), context(), "one", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, value.outcome());
        assertTrue(value.snapshot().path("pending").toString().contains("PP2_NATIVE_EXPERT_ORIGINAL_CLASS_NOT_PATHOGENIC_OR_LIKELY_PATHOGENIC"));
    }
    @Test void aSameDiseaseCurrentExpertBenignConflictWithBareMondoIdCannotRemainAnExclusion() {
        // Contradictory reader-output transport only, never written as a native original or new clinical assertion.
        var facts = (ObjectNode) observed.privateArchive().path("readerFacts").deepCopy();
        for (var row : facts.at("/expertScopes/0/references")) if ("1033090".equals(row.at("/facts/variationId").asText())) {
            var adverse = ((ObjectNode) row.at("/facts/originalAssertion")).deepCopy();
            adverse.put("scv", "SCV_ENGINEERING_CONTRADICTION").put("classification", "Benign");
            changeMondo(adverse.path("condition"), "0015967"); ((ArrayNode) row.at("/facts/otherAssertions")).add(adverse);
        }
        var owner = transport(facts); var value = owner.capture(owner.observe(), context(), "one", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.CONFLICT, value.outcome());
        assertTrue(value.snapshot().path("qualifiedEvidence").isEmpty());
        assertTrue(value.snapshot().path("conflicts").toString().contains("PP2_NATIVE_EXPERT_ASSERTION_SOURCE_CONFLICT"));
        var delta = value.snapshot().at("/conflicts/0/differences/0");
        assertEquals("classification", delta.path("field").asText());
        assertEquals("Pathogenic", delta.at("/left/value").asText()); assertEquals("Benign", delta.at("/right/value").asText());
        assertFalse(delta.at("/left/sourceRecordId").asText().isBlank()); assertFalse(delta.at("/right/sourceRecordId").asText().isBlank());
    }
    @Test void invalidReaderEnvelopeCannotRetainPreviouslyPresentPositiveFacts() {
        var facts = (ObjectNode) observed.privateArchive().path("readerFacts").deepCopy(); facts.put("scientificAdoption", true);
        var owner = transport(facts); var read = owner.observe(); var value = owner.capture(read, context(), "one", policy()).review();
        assertEquals("UNAVAILABLE", read.identity().path("readerStatus").asText());
        assertEquals(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, value.outcome());
        assertTrue(value.snapshot().path("qualifiedEvidence").isEmpty());
        assertTrue(value.snapshot().path("pending").toString().contains("PP2_NATIVE_READER_RESULT_INVALID"));
    }
    @Test void badObservedManifestPinCannotReuseAnOldCaptureOrCreateAnEmptyScopeClear() {
        var wrong = new Pp2OtherPhenotypeSource.ExpertInventory(inventory.scopeId(), sourceRoot, MANIFEST, "0".repeat(64),
                BUNDLE, inventory.adoptedBundleRawSha256(), inventory.adoptedBundleCanonicalSha256());
        var owner = new Pp2OtherPhenotypeSource(mapper, python, project, work, List.of(wrong), List.of());
        var read = owner.observe(); var value = owner.capture(read, context(), "one", policy()).review();
        assertNotEquals(observed.identity(), read.identity());
        assertFalse(review().matches(read, context(), "one", policy()));
        assertEquals(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, value.outcome());
        assertTrue(read.snapshot().path("readDispositions").toString().contains("PP2_SOURCE_RAW_PIN_MISMATCH"));
        boolean retainedMismatch = false;
        for (var row : read.snapshot().path("readDispositions")) if ("PP2_SOURCE_RAW_PIN_MISMATCH".equals(row.path("reasonCode").asText())) {
            retainedMismatch = true;
            assertEquals(row.path("observedRawSha256"), row.path("archivedRawSha256"));
            assertTrue(read.privateArchive().path("originals").has(row.path("archivedRawSha256").asText()));
        }
        assertTrue(retainedMismatch);
    }
    @Test void anOptionalComparisonAuditFailureDoesNotEraseAnIndependentNativeExpertAssertion() {
        var wrong = new Pp2OtherPhenotypeSource.ExpertInventory(inventory.scopeId(), sourceRoot, MANIFEST, inventory.manifestRawSha256(),
                BUNDLE, "0".repeat(64), inventory.adoptedBundleCanonicalSha256());
        var owner = new Pp2OtherPhenotypeSource(mapper, python, project, work, List.of(wrong), List.of());
        var read = owner.observe(); var value = owner.capture(read, context(), "one", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.SUPPORTS_OTHER_PHENOTYPE, value.outcome());
        assertTrue(read.snapshot().path("readDispositions").toString().contains("PP2_SOURCE_RAW_PIN_MISMATCH"));
    }
    @Test void currentLiteralPaperOriginalsAreReallyReadButNeverUpgradedToRelationshipQualification() throws Exception {
        Path index = Path.of("local/knowledge/literature/public-papers/evidence/PS2/index.json");
        String sha = CodingTranscriptMaterialSupply.sha(Files.readAllBytes(sourceRoot.resolve(index)));
        var papers = new Pp2OtherPhenotypeSource.LiteratureInventory("existing_papers", sourceRoot, index, sha);
        var owner = new Pp2OtherPhenotypeSource(mapper, python, project, work, List.of(inventory), List.of(papers));
        var read = owner.observe(); boolean factsRead = false;
        for (var paper : read.privateArchive().at("/readerFacts/literature")) if ("SOURCE_FACTS_READ".equals(paper.path("status").asText())) {
            factsRead = true; assertEquals("SOFTWARE_UNSUPPORTED", paper.path("relationQualification").asText());
            assertTrue(paper.path("literalFacts").isObject());
        }
        assertTrue(factsRead, "At least one actually pinned existing JATS original must pass its existing literal reader");
        var value = owner.capture(read, context(), "one", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.SUPPORTS_OTHER_PHENOTYPE, value.outcome());
        assertEquals("SOFTWARE_UNSUPPORTED", value.snapshot().at("/requiredScope/1/status").asText());
    }
    @Test void aRealPublishedExpertAssertionUsingPs1AndPm5IsIndependentOfTheComparisonQualificationRoute() {
        var nativeOnly = new Pp2OtherPhenotypeSource.ExpertInventory(inventory.scopeId(), sourceRoot, MANIFEST, inventory.manifestRawSha256());
        var owner = new Pp2OtherPhenotypeSource(mapper, python, project, work, List.of(nativeOnly), List.of());
        var read = owner.observe(); JsonNode target = mapper.missingNode();
        for (var row : read.privateArchive().at("/readerFacts/expertScopes/0/references"))
            if ("1327599".equals(row.at("/facts/variationId").asText())) target = row.path("facts");
        assertEquals("SCV002032336.2", target.at("/originalAssertion/scv").asText());
        assertEquals("Pathogenic", target.at("/originalAssertion/classification").asText());
        assertEquals(java.util.Set.of("PS1", "PM5"), mapper.convertValue(target.path("appliedComparisonCriteria"),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Set<String>>() { }));
        var context = context(); ((ObjectNode) context.path("variant")).set("evidenceQueryAllele", target.path("allele").deepCopy());
        var value = owner.capture(read, context, "existing_1327599", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.SUPPORTS_OTHER_PHENOTYPE, value.outcome());
        assertEquals("SCV002032336.2", value.snapshot().at("/qualifiedEvidence/0/scv").asText());
        assertEquals("NATIVE_PUBLISHED_EXPERT_ASSERTION_REPLAYED", value.snapshot().at("/qualifiedEvidence/0/sourceQualification").asText());
        assertEquals("INDEPENDENT_NATIVE_EXPERT_ASSERTION_ROUTE", owner.identity().path("sourceQualificationRoute").asText());
        assertFalse(read.snapshot().path("readDispositions").toString().contains("optionalComparisonAudit"));
        assertTrue(value.matches(read, context, "existing_1327599", policy()));
    }
    @ParameterizedTest @ValueSource(strings = {"grade", "withdrawn", "retracted", "summary-class", "summary-disease", "summary-version"})
    void originalNativeExpertIdentityGradeWithdrawalAndConflictsRemainRequired(String mutation) {
        var returned = (ObjectNode) observed.privateArchive().path("readerFacts").deepCopy();
        for (var row : returned.at("/expertScopes/0/references")) if ("1033090".equals(row.at("/facts/variationId").asText())) {
            var facts = (ObjectNode) row.path("facts");
            switch (mutation) {
                case "grade" -> ((ObjectNode) facts.path("originalAssertion")).put("reviewStatus", "criteria provided, single submitter");
                case "withdrawn" -> ((ObjectNode) facts.path("originalAssertion")).put("recordStatus", "removed");
                case "retracted" -> ((ObjectNode) facts.path("originalExpertSummary")).put("retracted", true);
                case "summary-class" -> ((ObjectNode) facts.path("originalExpertSummary")).put("classification", "Benign");
                case "summary-disease" -> ((ObjectNode) facts.path("originalExpertSummary")).put("mondoId", "MONDO:0005045")
                        .put("cvId", Long.parseLong(facts.path("variationId").asText()));
                case "summary-version" -> ((ObjectNode) facts.path("originalExpertSummary")).put("docVersion", "999.0.0");
                default -> throw new AssertionError(mutation);
            }
        }
        var owner = transport(returned); var value = owner.capture(owner.observe(), context(), "one", policy()).review();
        assertTrue(java.util.Set.of(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, Pp2OtherPhenotypeSource.Outcome.CONFLICT).contains(value.outcome()));
        assertTrue(value.snapshot().path("qualifiedEvidence").isEmpty());
        assertFalse(value.snapshot().path("pending").isEmpty() && value.snapshot().path("conflicts").isEmpty());
        if (mutation.startsWith("summary-")) {
            var differences = value.snapshot().at("/conflicts/0/differences"); assertTrue(differences.isArray() && !differences.isEmpty());
            String field = switch (mutation) { case "summary-class" -> "classification"; case "summary-disease" -> "diseaseId"; default -> "curationVersion"; };
            boolean present = false;
            for (var difference : differences) assertNotEquals("variationId", difference.path("field").asText(),
                    "A numeric/string form of the same actual ClinVar ID is not a disagreement");
            for (var difference : differences) if (field.equals(difference.path("field").asText())) {
                present = true; assertNotEquals(difference.at("/left/value"), difference.at("/right/value"));
                assertFalse(difference.at("/left/sourceRecordId").asText().isBlank());
            }
            assertTrue(present, mutation); assertFalse(value.snapshot().toString().contains("rawJson"));
        }
    }
    @Test void projectionIdentityChangesTheActualSourceCapabilityWithoutChangingOldSavedObjects() {
        var current = source.identity(); var old = current.deepCopy(); old.remove("sourceProjectionVersion");
        assertEquals(Pp2OtherPhenotypeSource.SOURCE_PROJECTION_VERSION, current.path("sourceProjectionVersion").asText());
        assertNotEquals(adapter.canonicalSha256(old), adapter.canonicalSha256(current)); assertFalse(old.has("sourceProjectionVersion"));
    }
    @Test void discoveryFactoryIdentityIsLazyAndDoesNotTurnARankedDeclarationIntoAnEvidenceContext() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var owner = Pp2OtherPhenotypeSource.forWorkflow(mapper, work.resolve("not-present-workflow.json"), "a".repeat(64), work,
                () -> { calls.incrementAndGet(); throw new AssertionError("Static identity must not acquire sources"); });
        assertTrue(owner.identity().has("automaticWorkflow")); assertEquals(0, calls.get());
        assertEquals(Pp2OtherPhenotypeSource.SOURCE_DISPOSITION_VERSION, owner.identity().path("sourceDispositionVersion").asText());
        var oldCapability = owner.identity().deepCopy(); oldCapability.remove("sourceDispositionVersion");
        var oldCapabilityBytes = mapper.valueToTree(oldCapability);
        assertNotEquals(adapter.canonicalSha256(oldCapability), adapter.canonicalSha256(owner.identity()));
        assertEquals(oldCapabilityBytes, oldCapability, "The new source capability does not mutate or relabel a saved older object");
        var notSelected = owner.observe(List.of());
        assertEquals("NOT_SELECTED", notSelected.identity().path("readerStatus").asText());
        assertEquals("NOT_OBSERVED", notSelected.snapshot().at("/configurationObservation/status").asText());
        assertTrue(notSelected.snapshot().path("readDispositions").isEmpty());
        var prepared = context(); ((ObjectNode) prepared.path("gene")).put("hgncId", "HGNC:11621"); prepared.put("moi", "AUTOSOMAL_DOMINANT");
        var target = new Pp2OtherPhenotypeSource.DiscoveryTarget("one", "hypothesis", (ObjectNode) prepared.at("/variant/evidenceQueryAllele"),
                "HGNC:11621", "HNF1A", "MONDO:0005045", "AUTOSOMAL_DOMINANT", mapper.createObjectNode().put("fixture", "RANKED_PUBLIC_DISCOVERY_ONLY"));
        var read = owner.observe(List.of(target));
        assertEquals("DISCOVERY_ONLY_NOT_SCIENTIFIC_CONTEXT", read.snapshot().path("targetRole").asText());
        assertFalse(read.snapshot().at("/discoveryTargets/0").has("formalCandidate"));
        var value = owner.capture(read, prepared, "one", "hypothesis", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, value.outcome());
        assertEquals("UNAVAILABLE", value.snapshot().at("/requiredScope/1/status").asText());
        assertEquals(read.privateArchive().at("/readerFacts/reasonCode"), value.snapshot().at("/requiredScope/1/reasonCode"));
        assertFalse(value.snapshot().path("pending").toString().contains("CASE_RELATIONSHIP_NOT_IMPLEMENTED"),
                "An unread workflow configuration is a source failure, not an absent algorithm");
        assertTrue(value.matches(read, prepared, "one", "hypothesis", policy()));
        assertFalse(value.matches(read, prepared, "one", "foreign-hypothesis", policy()));
        var wrong = prepared.deepCopy(); ((ObjectNode) wrong.path("gene")).put("hgncId", "HGNC:99999");
        var invalid = owner.capture(read, wrong, "one", "hypothesis", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.INVALID, invalid.outcome());
        for (String field : List.of("requiredScope", "qualifiedEvidence", "pending", "conflicts", "sourceReadDispositionIds"))
            assertTrue(invalid.snapshot().path(field).isArray() && invalid.snapshot().path(field).isEmpty());
        assertEquals(0, calls.get());
    }
    @Test void multipleDeclaredBackgroundsRequireAnExplicitHypothesisAndDefensivePublicTargetData() {
        var owner = Pp2OtherPhenotypeSource.forWorkflow(mapper, work.resolve("absent.json"), "a".repeat(64), work,
                () -> { throw new AssertionError("Declaration cannot grant acquisition or evidence authority"); });
        var prepared = context(); ((ObjectNode) prepared.path("gene")).put("hgncId", "HGNC:11621"); prepared.put("moi", "AUTOSOMAL_DOMINANT");
        var allele = (ObjectNode) prepared.at("/variant/evidenceQueryAllele"); var candidate = mapper.createObjectNode().put("fixture", "DISCOVERY_ONLY");
        var first = new Pp2OtherPhenotypeSource.DiscoveryTarget("one", "h1", allele, "HGNC:11621", "HNF1A", "MONDO:0005045", "AUTOSOMAL_DOMINANT", candidate);
        var second = new Pp2OtherPhenotypeSource.DiscoveryTarget("one", "h2", allele, "HGNC:11621", "HNF1A", "MONDO:0005045", "AUTOSOMAL_DOMINANT", candidate);
        first.genomicAllele().put("position", 1); first.formalCandidate().put("fixture", "modified");
        assertEquals(120978826, first.genomicAllele().path("position").asInt()); assertEquals("DISCOVERY_ONLY", first.formalCandidate().path("fixture").asText());
        var read = owner.observe(List.of(first, second));
        assertEquals(Pp2OtherPhenotypeSource.Outcome.INVALID, owner.capture(read, prepared, "one", policy()).review().outcome());
        var explicit = owner.capture(read, prepared, "one", "h2", policy()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, explicit.outcome());
        assertEquals("h2", explicit.snapshot().at("/binding/hypothesisId").asText());
    }
}
