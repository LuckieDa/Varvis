package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real bounded original-byte reads and ordinary opaque reference issuance over
 * a SYNTHETIC virtual genome only. No clinical RNA, phase or criterion adoption.
 * Independent oracle: R=CC+ATG AAA CCC GGG TTT GAA TAA+GGG TGA.
 * r7 A>G plus RNA [8,11)del yields CCATGAGAGGGTTTGAATAAGGGTGA.
 */
class Pvs1FullRnaSourceProducerTest {
    @TempDir Path temporary;
    static final String COMPLETE = "CCATGAGAGGGTTTGAATAAGGGTGA";
    record Data(CodingTranscriptRnaFixture f, Path originals, ObjectNode original, ObjectNode binding) { }

    /** Reusable actual-file producer fixture; synthetic observations never grant criterion adoption. */
    static Data createNeutralSourceFixture(Path directory, int strand) throws Exception {
        var helper = new Pvs1FullRnaSourceProducerTest();helper.temporary = directory;
        Files.createDirectories(directory);return helper.data(strand);
    }
    static void repinNeutralSourceFixture(Data d) throws Exception {
        var helper = new Pvs1FullRnaSourceProducerTest();helper.repin(d);
    }
    static Pvs1SourceQualification.FullRnaResolution replayNeutralSourceFixture(Data d) {
        return Pvs1SourceQualification.resolveFullRna(d.f.mapper, d.originals, d.binding, d.f.context, d.f.token);
    }

    private CodingTranscriptRnaFixture fixture(int strand) throws Exception {
        var f = new CodingTranscriptRnaFixture(temporary.resolve("reference"), strand);
        f.context.put("dataClass", "SYNTHETIC");return f.reload();
    }
    private Data data(int strand) throws Exception { return original(fixture(strand), COMPLETE, true); }
    private Data original(CodingTranscriptRnaFixture f, String witness, boolean deletion) throws Exception {
        var o = f.mapper.createObjectNode().put("schemaVersion", Pvs1SourceQualification.FULL_RNA_ORIGINAL_SCHEMA)
                .put("reviewId", "SYNTHETIC_OBSERVATION_ONLY").put("basis", "Synthetic complete RNA original; not a clinical or expert source");
        var scope = o.putObject("scope");
        for (String key : List.of("assembly", "contig", "hgncId", "geneSymbol", "transcript")) scope.set(key, f.model.path(key));
        scope.put("modelSha256", f.token.modelSha256()).put("referenceRnaSha256", f.token.referenceRnaSha256());
        scope.set("allele", f.context.at("/variant/evidenceQueryAllele").deepCopy());
        o.putObject("use").put("purpose", "PROJECT_RESEARCH").put("dataClass", "SYNTHETIC")
                .put("clinicalAcceptance", false).put("createsExpertReview", false);
        o.putObject("assay").put("evidenceKind", "OBSERVED_RNA").put("alleleAttribution", "ASSESSED")
                .put("assayQuality", "ASSESSED").put("tissueRelevance", "ASSESSED")
                .put("residualNormalTranscript", "NOT_DETECTED_IN_ASSESSED_SCOPE").put("outcomeMultiplicity", "SINGLE_SUPPORTED_OUTCOME")
                .put("basis", "Synthetic allele-specific assay declaration, not real experimental evidence");
        o.putObject("physicalAllele").put("status", "SINGLE_PHYSICAL_ALLELE")
                .put("alleleSha256", f.adapter.canonicalSha256(f.context.at("/variant/evidenceQueryAllele")))
                .put("basis", "Synthetic single physical allele source declaration, not clinical phase adoption");
        var edits = o.putObject("events").put("coordinateSystem", CodingTranscriptEdits.RNA_COORDINATES)
                .put("sequenceSemantics", "REFERENCE_RNA_EDIT_DELTAS").putArray("rnaEdits");
        if (deletion) edits.addObject().put("eventId", "remove-three-cytosines").put("start0", 8).put("end0", 11)
                .put("expectedReference", "CCC").put("insertedSequence", "");
        o.putObject("sequence").put("completeAlteredRnaWitness", witness).put("alteredRnaSha256", CodingTranscriptMaterialSupply.sha(witness));
        var topology = o.putObject("topology").put("completeness", "COMPLETE").put("basis", "Synthetic declared junction list, not EJC qualification");
        var junctions = topology.putArray("junctions0");
        for (int j : deletion ? new int[]{8, 14, 22} : new int[]{8, 17, 25}) junctions.add(j);
        var binding = f.mapper.createObjectNode().put("relativePath", "original.json");
        binding.putObject("sourceRecord").put("sourceId", "SYNTHETIC_FULL_RNA_ORIGINAL").put("version", "TEST_ONLY")
                .put("url", "https://example.test/synthetic-full-rna-original").put("sha256", "0".repeat(64));
        var d = new Data(f, temporary.resolve("originals"), o, binding);repin(d);return d;
    }
    private void repin(Data d) throws Exception { writeRaw(d, d.f.mapper.writeValueAsBytes(d.original)); }
    private void writeRaw(Data d, byte[] raw) throws Exception {
        Files.createDirectories(d.originals);Files.write(d.originals.resolve("original.json"), raw);
        d.binding.withObject("sourceRecord").put("sha256", CodingTranscriptMaterialSupply.sha(raw));
    }
    private Pvs1SourceQualification.FullRnaResolution resolve(Data d) {
        return Pvs1SourceQualification.resolveFullRna(d.f.mapper, d.originals, d.binding, d.f.context, d.f.token);
    }
    private void blocked(Data d, String reason) {
        var actual = resolve(d);assertFalse(actual.ready(), actual.snapshot().toString());assertNull(actual.qualified());
        assertEquals("FULL_RNA_SOURCE_" + reason, actual.snapshot().path("reasonCode").asText(), actual.snapshot().toString());
        assertFalse(actual.snapshot().path("criterionQualificationGranted").asBoolean());
    }
    private void noAdoption(Pvs1SourceQualification.SourceBoundFullRna token) {
        assertEquals(Pvs1SourceQualification.FULL_RNA_STAGE, token.eligibilityStage());
        for (String key : List.of("clinicalAcceptance", "createsExpertReview", "scientificAdoptionGranted", "phaseQualificationGranted", "functionalQualificationGranted", "criterionQualificationGranted"))
            assertFalse(token.provenance().path(key).asBoolean(), key);
        assertFalse(token.topology().snapshot().path("ejcQualificationGranted").asBoolean());
        assertFalse(token.provenance().has("appliedStrength"));
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void actualPinnedOriginalReplaysDnaAndRnaExactlyOnce(int strand) throws Exception {
        var d = data(strand);var actual = resolve(d);assertTrue(actual.ready(), actual.snapshot().toString());var token = java.util.Objects.requireNonNull(actual.qualified());
        assertNotNull(token);assertEquals(COMPLETE, token.result().alteredRna());assertEquals(2, token.result().appliedEdits().size());
        assertTrue(token.result().snapshot(d.f.mapper).path("completeAllelicRnaWitnessVerified").asBoolean());
        assertEquals(List.of(8,14,22), token.topology().junctions0());assertEquals("NATIVE_REFERENCE_STOP", token.facts().firstStopKind());
        assertEquals(d.binding.at("/sourceRecord/sha256").asText(), token.originalSha256());
        assertEquals(Files.size(d.originals.resolve("original.json")), token.provenance().path("originalBytesRead").asLong());noAdoption(token);
        emit(d, actual, strand == 1 ? "plus" : "minus");
    }
    @Test void neutralReplayDoesNotRequireDiseaseMoiOrLofPolicyAndPreservesAssayLimitations() throws Exception {
        var f = fixture(1);f.context.remove(List.of("disease", "moi"));f.context.put("contextId", f.adapter.canonicalContextId(f.context));f.reload();
        var d = original(f, COMPLETE, true);d.original.withObject("assay").put("assayQuality", "UNKNOWN").put("tissueRelevance", "UNKNOWN")
                .put("residualNormalTranscript", "DETECTED_IN_ASSESSED_SCOPE");repin(d);
        var actual = resolve(d);assertTrue(actual.ready(), actual.snapshot().toString());var token = java.util.Objects.requireNonNull(actual.qualified());
        assertEquals("UNKNOWN", token.provenance().at("/assayConditions/assayQuality").asText());
        assertEquals("DETECTED_IN_ASSESSED_SCOPE", token.original().at("/assay/residualNormalTranscript").asText());noAdoption(token);
    }
    @Test void unknownTopologyIsPreservedWithoutManufacturingExonsOrNmd() throws Exception {
        var d = data(1);d.original.withObject("topology").put("completeness", "UNKNOWN").putArray("junctions0");repin(d);
        var actual = resolve(d);assertTrue(actual.ready(), actual.snapshot().toString());var token = java.util.Objects.requireNonNull(actual.qualified());
        assertTrue(token.topology().exons().isEmpty());assertNull(token.topology().lastJunctionRnaPosition0());noAdoption(token);
    }
    @Test void unknownTopologyCannotSmuggleConfirmedJunctions() throws Exception {
        var d = data(1);d.original.withObject("topology").put("completeness", "UNKNOWN");repin(d);blocked(d, "UNKNOWN_TOPOLOGY_HAS_DECLARED_JUNCTIONS");
    }
    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "TRANS", "CIS", "DECLARED_SINGLE_PHYSICAL_ALLELE"})
    void groupingHashOrUnresolvedPhaseDoesNotReplaceActualPhysicalAlleleDeclaration(String status) throws Exception {
        var d = data(1);d.original.withObject("physicalAllele").put("status", status);repin(d);blocked(d, "PHYSICAL_ALLELE_SCOPE_REQUIRED");
    }
    @ParameterizedTest @ValueSource(strings = {"alleleAttribution", "evidenceKind", "outcomeMultiplicity", "basis"})
    void unassessedAttributionPredictionsAndMultipleOrUnknownOutcomesAreRejected(String field) throws Exception {
        var d = data(1);d.original.withObject("assay").put(field, field.equals("basis") ? "" : "UNKNOWN");repin(d);blocked(d, "OBSERVED_ASSAY_SCOPE_REQUIRED");
    }
    @Test void declaredMultipleProductsCannotSelectOnlyTheFavorableWitness() throws Exception {
        var d = data(1);
        d.original.withObject("assay").put("outcomeMultiplicity", "MULTIPLE_OUTCOMES");
        repin(d);
        var result = resolve(d);
        assertFalse(result.ready());
        assertNull(result.qualified());
        var audit = result.snapshot();
        assertEquals("FULL_RNA_SOURCE_MULTIPLE_OUTCOMES_UNRESOLVED", audit.path("reasonCode").asText());
        assertEquals("MULTIPLE_OUTCOMES", audit.at("/assayConditions/outcomeMultiplicity").asText());
        assertEquals(d.binding.at("/sourceRecord/sha256"), audit.path("originalSha256"));
        assertFalse(audit.path("criterionQualificationGranted").asBoolean(true));
        assertFalse(audit.has("mechanicalEffectSha256"));
    }
    @ParameterizedTest @ValueSource(strings = {"status", "alleleSha256"})
    void multipleProductsAreNotAttributedWithoutPhysicalAlleleBinding(String field) throws Exception {
        var d = data(1);
        d.original.withObject("assay").put("outcomeMultiplicity", "MULTIPLE_OUTCOMES");
        d.original.withObject("physicalAllele").put(field, field.equals("status") ? "UNKNOWN" : "f".repeat(64));
        repin(d);

        var result = resolve(d);
        assertFalse(result.ready());
        assertNull(result.qualified());
        var audit = result.snapshot();
        assertEquals("FULL_RNA_SOURCE_PHYSICAL_ALLELE_SCOPE_REQUIRED", audit.path("reasonCode").asText());
        assertFalse(audit.has("assayConditions"));
        assertFalse(audit.has("sourceRecord"));
    }
    @ParameterizedTest @ValueSource(strings = {"purpose", "dataClass", "clinicalAcceptance", "createsExpertReview"})
    void useRestrictionsAndActualDataClassAreNotUpgraded(String field) throws Exception {
        var d = data(1);var use = d.original.withObject("use");
        if (field.equals("clinicalAcceptance") || field.equals("createsExpertReview")) use.put(field, true);
        else use.put(field, field.equals("dataClass") ? "PRIVACY_BOUNDED_RUNTIME" : "CLINICAL");
        repin(d);blocked(d,"ORIGINAL_USE_RESTRICTED");
    }
    @ParameterizedTest @ValueSource(strings = {"assembly", "contig", "hgncId", "geneSymbol", "transcript", "modelSha256", "referenceRnaSha256", "allele"})
    void exactNativeScopeAndReferenceCannotBeReplaced(String field) throws Exception {
        var d = data(1);d.original.withObject("scope").put(field, "WRONG");repin(d);blocked(d, "ORIGINAL_SCOPE_MISMATCH");
    }
    @Test void wrongContextAndMissingOpaqueReferenceNeverReachSourceReplay() throws Exception {
        var d = data(1);var different = d.f.context.deepCopy().put("contextId", "wrong");
        var wrong = Pvs1SourceQualification.resolveFullRna(d.f.mapper,d.originals,d.binding,different,d.f.token);
        assertFalse(wrong.ready());assertEquals("FULL_RNA_SOURCE_CONTEXT_BINDING_MISMATCH",wrong.snapshot().path("reasonCode").asText());
        assertFalse(Pvs1SourceQualification.resolveFullRna(d.f.mapper,d.originals,d.binding,d.f.context,null).ready());
    }
    @ParameterizedTest @ValueSource(strings = {"mapper", "binding", "context", "reference", "root"})
    void missingControlledInputsReturnAFailedResolutionRatherThanCrashing(String missing) throws Exception {
        var d = data(1);
        var actual = Pvs1SourceQualification.resolveFullRna(missing.equals("mapper") ? null : d.f.mapper,
                missing.equals("root") ? null : d.originals, missing.equals("binding") ? null : d.binding,
                missing.equals("context") ? null : d.f.context, missing.equals("reference") ? null : d.f.token);
        assertFalse(actual.ready());assertNull(actual.qualified());
        String reason = switch (missing) {
            case "mapper" -> "MAPPER_REQUIRED";
            case "binding" -> "CONFIGURATION_INVALID";
            case "root" -> "CONTROLLED_ROOT_REQUIRED";
            default -> "VERIFIED_REFERENCE_REQUIRED";
        };
        assertEquals("FULL_RNA_SOURCE_" + reason, actual.snapshot().path("reasonCode").asText());
    }
    @Test void invalidPathIsReportedAsAConfigurationFailure() throws Exception {
        var d = data(1);d.binding.put("relativePath", "invalid" + (char) 0 + "path");blocked(d, "ORIGINAL_PATH_INVALID");
    }
    @Test void actualOriginalBytesAreReadInsteadOfTrustingAHashOrStatus() throws Exception {
        var d = data(1);Files.writeString(d.originals.resolve("original.json"),"{}");blocked(d,"ORIGINAL_SHA_MISMATCH");
        Files.delete(d.originals.resolve("original.json"));blocked(d,"ORIGINAL_IO_UNAVAILABLE");
    }
    @ParameterizedTest @ValueSource(strings = {"duplicate", "trailing", "utf8", "deep", "too-large"})
    void originalJsonIsStrictAndBounded(String kind) throws Exception {
        var d = data(1);byte[] raw;
        if(kind.equals("duplicate")) raw="{\"schemaVersion\":\"a\",\"schemaVersion\":\"b\"}".getBytes(StandardCharsets.UTF_8);
        else if(kind.equals("trailing")) raw=(d.original.toString()+" {}").getBytes(StandardCharsets.UTF_8);
        else if(kind.equals("utf8")) raw=new byte[]{(byte)0xc3,(byte)0x28};
        else if(kind.equals("deep")) raw=("[".repeat(40)+"0"+"]".repeat(40)).getBytes(StandardCharsets.UTF_8);
        else raw=new byte[4_000_001];
        writeRaw(d,raw);var actual=resolve(d);assertFalse(actual.ready(),actual.snapshot().toString());assertNull(actual.qualified());
        String expected = kind.equals("too-large") ? "ORIGINAL_BYTES_INVALID" : kind.equals("deep") ? "ORIGINAL_JSON_LIMIT_EXCEEDED" : "ORIGINAL_JSON_INVALID";
        assertEquals("FULL_RNA_SOURCE_" + expected, actual.snapshot().path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"../outside.json", "sub/../../outside.json"})
    void relativePathTraversalIsRejected(String path) throws Exception {
        var d=data(1);d.binding.put("relativePath",path);blocked(d,"ORIGINAL_PATH_OUTSIDE_ROOT");
    }
    @Test void absoluteOriginalPathIsRejectedEvenInsideTheRoot() throws Exception {
        var d=data(1);d.binding.put("relativePath",d.originals.resolve("original.json").toAbsolutePath().toString());blocked(d,"ORIGINAL_PATH_OUTSIDE_ROOT");
    }
    @Test void directoryIsNotAnOriginalFile() throws Exception {
        var d=data(1);Files.createDirectories(d.originals.resolve("directory"));d.binding.put("relativePath","directory");blocked(d,"ORIGINAL_BYTES_INVALID");
    }
    @ParameterizedTest @ValueSource(strings = {"reference", "witness", "hash", "overlap", "duplicate-id", "no-op", "nonminimal"})
    void actualSequenceDeltasMustBeCompleteMinimalAndCompatible(String kind) throws Exception {
        var d=data(1);var event=(ObjectNode)d.original.at("/events/rnaEdits/0");
        switch(kind) {
            case "reference" -> event.put("expectedReference","AAA");
            case "witness" -> {d.original.withObject("sequence").put("completeAlteredRnaWitness",CodingTranscriptRnaFixture.DEFAULT_RNA)
                    .put("alteredRnaSha256",CodingTranscriptMaterialSupply.sha(CodingTranscriptRnaFixture.DEFAULT_RNA));}
            case "hash" -> d.original.withObject("sequence").put("alteredRnaSha256","a".repeat(64));
            case "overlap" -> d.original.withObject("events").withArray("rnaEdits").addObject().put("eventId","partial-overlap")
                    .put("start0",10).put("end0",12).put("expectedReference","CG").put("insertedSequence","");
            case "duplicate-id" -> d.original.withObject("events").withArray("rnaEdits").add(event.deepCopy());
            case "no-op" -> event.put("insertedSequence","CCC");
            case "nonminimal" -> event.put("start0",2).put("end0",6).put("expectedReference","ATGA").put("insertedSequence","ATGT");
        }
        repin(d);var actual=resolve(d);assertFalse(actual.ready(),actual.snapshot().toString());
        if(kind.equals("no-op")||kind.equals("nonminimal")) assertEquals("FULL_RNA_SOURCE_RNA_EVENT_NOT_MINIMAL_DELTA",actual.snapshot().path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(ints = {1,-1})
    void trueIntronicAlleleHasNoInventedRnaDnaOperation(int strand) throws Exception {
        var f=fixture(strand).withGenomicReference(1500,"A").alleleAtGenomic(1500,"A","G");
        var d=original(f,CodingTranscriptRnaFixture.DEFAULT_RNA,false);var actual=resolve(d);
        assertTrue(actual.ready(),actual.snapshot().toString());var token=java.util.Objects.requireNonNull(actual.qualified());assertTrue(token.result().appliedEdits().isEmpty());
        assertEquals("WHOLLY_WITHIN_SINGLE_REFERENCE_INTRON_NO_RNA_DNA_COMPONENT",token.provenance().path("dnaProjection").asText());noAdoption(token);
    }
    @Test void utrDnaChangeIsProjectedRatherThanMistakenForAnIntronicAllele() throws Exception {
        var f=fixture(1).alleleAtRna(1,"C","T");var d=original(f,"TCATGAAACCCGGGTTTGAATAAGGGTGA",false);var actual=resolve(d);
        assertTrue(actual.ready(),actual.snapshot().toString());assertEquals(1,java.util.Objects.requireNonNull(actual.qualified()).result().appliedEdits().size());
        assertEquals("ACTUAL_CONTEXT_ALLELE_PROJECTED_ONCE",java.util.Objects.requireNonNull(actual.qualified()).provenance().path("dnaProjection").asText());
    }
    @Test void outsideTranscriptIsNotAnIntron() throws Exception {
        var f=fixture(1).withGenomicReference(900,"A").alleleAtGenomic(900,"A","G");var d=original(f,CodingTranscriptRnaFixture.DEFAULT_RNA,false);
        blocked(d,"DNA_FOOTPRINT_NOT_WHOLLY_INTRONIC");
    }
    @Test void intronicInsertionCannotSilentlyCrossAnExonicFlank() throws Exception {
        var f=fixture(1).withGenomicReference(1999,"A").alleleAtGenomic(1999,"A","AG");var d=original(f,CodingTranscriptRnaFixture.DEFAULT_RNA,false);
        blocked(d,"DNA_FOOTPRINT_NOT_WHOLLY_INTRONIC");
    }
    @Test void referenceFootprintTouchingExonIsNotSilentlyIgnored() throws Exception {
        var f=fixture(1).withGenomicReference(1008,"A").alleleAtGenomic(1007,"AA","A");var d=original(f,CodingTranscriptRnaFixture.DEFAULT_RNA,false);
        blocked(d,"DNA_FOOTPRINT_NOT_WHOLLY_INTRONIC");
    }
    @Test void completeShortIntronFootprintRequiresExplicitJunctionHandling() throws Exception {
        var f=fixture(1);var m=f.model.deepCopy();var second=(ObjectNode)m.at("/exons/1");second.put("genomicStart",1011).put("genomicEnd",1019);
        f.configure(m,CodingTranscriptRnaFixture.DEFAULT_RNA).withGenomicReference(1008,"AAA").alleleAtGenomic(1008,"AAA","C");
        var d=original(f,CodingTranscriptRnaFixture.DEFAULT_RNA,false);blocked(d,"FULL_INTRON_FOOTPRINT_REQUIRES_JUNCTION_REVIEW");
    }
    @Test void exactOriginalBytesSurviveFormattingFileChangesAndCallerMutation() throws Exception {
        var d = data(1);
        String text = "  \n" + d.f.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(d.original)
                + "\r\n\t";
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        writeRaw(d, raw);
        var resolved = resolve(d);
        assertTrue(resolved.ready(), resolved.snapshot().toString());
        var token = java.util.Objects.requireNonNull(resolved.qualified());
        assertArrayEquals(raw, token.originalBytes());
        assertEquals(text, token.originalUtf8());
        assertEquals(CodingTranscriptMaterialSupply.sha(raw), token.originalSha256());
        token.originalBytes()[0] = (byte) 'x';
        Files.writeString(d.originals.resolve("original.json"), "{}");
        assertArrayEquals(raw, token.originalBytes());
        assertEquals(text, token.originalUtf8());
        assertNotEquals(d.f.mapper.writeValueAsString(token.original()), token.originalUtf8());
        noAdoption(token);
    }

    @Test void originalAndProvenanceCopiesCannotMutateTheOpaqueToken() throws Exception {
        var d=data(1);var actual=resolve(d);assertTrue(actual.ready(),actual.snapshot().toString());var token=java.util.Objects.requireNonNull(actual.qualified());
        token.original().withObject("assay").put("alleleAttribution","UNKNOWN");token.provenance().put("clinicalAcceptance",true);
        actual.snapshot().put("status","FAKE");assertEquals("ASSESSED",token.original().at("/assay/alleleAttribution").asText());noAdoption(token);
        for(var constructor:Pvs1SourceQualification.SourceBoundFullRna.class.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        assertThrows(Exception.class,()->d.f.mapper.readValue("{}",Pvs1SourceQualification.SourceBoundFullRna.class));
    }
    private void emit(Data d,Pvs1SourceQualification.FullRnaResolution actual,String name) throws Exception {
        String configured=System.getProperty("varvis.t07.fullrna.source.artifacts");if(configured==null||configured.isBlank())return;
        Path target=Path.of(configured).resolve(name);Files.createDirectories(target);
        Files.copy(d.originals.resolve("original.json"),target.resolve("original-review.json"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var sample=d.f.mapper.createObjectNode().put("dataClass","SYNTHETIC").put("purpose","PROJECT_RESEARCH")
                .put("clinicalAcceptance",false).put("scientificAdoptionGranted",false).put("normalSelectedBusinessVerified",false);
        sample.set("binding",d.binding.deepCopy());sample.set("actualResolution",actual.snapshot());sample.set("context",d.f.context.deepCopy());
        if(actual.ready()){sample.set("editFacts",java.util.Objects.requireNonNull(actual.qualified()).result().snapshot(d.f.mapper));sample.set("facts",java.util.Objects.requireNonNull(actual.qualified()).facts().snapshot());sample.set("topology",java.util.Objects.requireNonNull(actual.qualified()).topology().snapshot());}
        Files.write(target.resolve("source-gate-sample.json"),d.f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sample));
    }
}
