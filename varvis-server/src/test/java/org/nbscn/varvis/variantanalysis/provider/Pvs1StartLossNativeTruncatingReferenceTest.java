package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Real original expert interpretations and exact original guidelines. c.352
 * tests independence only (outside Met42); c.48 tests a real upstream witness.
 * Neither source classification is the target's PVS1 decision. */
@EnabledIfSystemProperty(named = "pvs1.nativeExpertRoot", matches = ".+")
class Pvs1StartLossNativeTruncatingReferenceTest {
    final ObjectMapper mapper = new ObjectMapper();
    @org.junit.jupiter.api.io.TempDir Path temporary;
    private record Fixture(ObjectNode summary, ObjectNode original, ObjectNode model,
                           PredictionSpecializationGuard.ReferenceSpecification specification) { }
    private Fixture fixture() throws Exception {
        Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot"));
        Path folder = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102");
        byte[] originalRaw = Files.readAllBytes(folder.resolve("erepo-gamt-c352-original.json"));
        assertEquals("ba80b8dadb81eb221d7ad5162a4adfcba1b9bf97925559119b30edcbb9ba1d41", CodingTranscriptMaterialSupply.sha(originalRaw));
        var original = (ObjectNode) mapper.readTree(originalRaw);
        ObjectNode summary = null;
        byte[] summaryRaw = Files.readAllBytes(folder.resolve("erepo-gamt-reference-page1.json"));
        assertEquals("99894c6600bca40c0e745c7cf880597c098fb0189e59adbc4dc9171c7712af15", CodingTranscriptMaterialSupply.sha(summaryRaw));
        for (var row : mapper.readTree(summaryRaw).path("data")) if (row.path("uuid").equals(original.path("uuid"))) summary = (ObjectNode) row;
        assertNotNull(summary);
        var archive = mapper.readTree(Files.readAllBytes(folder.resolve("native-target-gamt-c1-private.json")));
        String rawModel = archive.at("/originals/d9dbaba21eae06cec7018afed06e22b1553af2d2609b74d2738870752598b62a/rawJson").asText();
        assertEquals("d9dbaba21eae06cec7018afed06e22b1553af2d2609b74d2738870752598b62a", CodingTranscriptMaterialSupply.sha(rawModel.getBytes(StandardCharsets.UTF_8)));
        var model = (ObjectNode) mapper.readTree(rawModel);
        assertTrue(PublishedCriterionKnowledge.exactBridge("NM_000156.6", model));
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_RECESSIVE");
        context.putObject("gene").put("hgncId", "HGNC:4136").put("symbol", "GAMT"); context.putObject("disease").put("mondoId", "MONDO:0012999");
        context.putObject("transcript").put("accession", "NM_000156").put("version", 6);
        var guard = PredictionSpecializationGuard.load(mapper, root.resolve("local/resources/acmg/ba1/cspec-20260914-a01/snapshot.json"),
                "b2d2e43216ea15758490a1eb97d55eccd8ded98494e60230102754091fde32e0",
                root.resolve("local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture"));
        var specification = guard.referenceSpecification(context, original.path("assertionMethod"), java.util.Objects.requireNonNull(summary)); assertNotNull(specification);
        return new Fixture(java.util.Objects.requireNonNull(summary), original, model, java.util.Objects.requireNonNull(specification));
    }
    @Test void sameVersionSourceCombinationRemainsPathogenicWithASequenceReplayedNonStartLossGround() throws Exception {
        var f = fixture();
        var result = Pvs1StartLossCuratedGrounds.assess(mapper, f.summary(), f.original(), f.specification(), f.model());
        assertEquals("INDEPENDENT_CURATED_BASIS_ESTABLISHED", result.path("status").asText(), result::toPrettyString);
        assertEquals("Pathogenic", result.at("/independentCombination/classification").asText());
        assertEquals(3, result.at("/independentCombination/appliedCodes").size());
        assertEquals(352, result.at("/grounds/0/sourceClaim/truncatingMechanism/prematureStopCdsPosition").asInt());
        assertTrue(result.at("/grounds/0/sourceClaim/truncatingMechanism/originalStartRetained").asBoolean());
        assertFalse(result.path("referenceQualificationComplete").asBoolean());
        assertFalse(result.path("primaryExperimentReevaluated").asBoolean());
        assertFalse(result.at("/grounds/0/sourceClaim/truncatingMechanism/nmdReevaluated").asBoolean());
    }
    @Test void sourceStrengthMismatchMissingMeasurementOrTruncatingModelNeverFallsBackToLabel() throws Exception {
        var f = fixture();
        assertFalse(Pvs1StartLossCuratedGrounds.assess(mapper, f.summary(), f.original(), f.specification()).path("independenceEstablished").asBoolean());
        var clinical = (ObjectNode) f.original().at("/evidenceLine/1/evidenceItem/0/evidenceLine/0/evidenceItem/0/contribution/0");
        String text = clinical.path("comments").asText(); clinical.put("comments", text.replace("PP4_Moderate", "PP4_Strong"));
        assertFalse(Pvs1StartLossCuratedGrounds.assess(mapper, f.summary(), f.original(), f.specification(), f.model()).path("independenceEstablished").asBoolean());
        clinical.put("comments", text.replace("had significantly reduced", "might have significantly reduced"));
        assertFalse(Pvs1StartLossCuratedGrounds.assess(mapper, f.summary(), f.original(), f.specification(), f.model()).path("independenceEstablished").asBoolean());
    }
    @Test void historicalReleasedOriginalRulesAreFetchedAndReplayedExactlyWithoutUpgradingTheSourceVersion() throws Exception {
        Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot"));
        Path folder = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102");
        byte[] raw = Files.readAllBytes(folder.resolve("GN026-version1.1.0-contents.json"));
        assertEquals("7060ea61149b497a7d576a35dfe69ed9393646322dc3b265aaada4816a5e4793", CodingTranscriptMaterialSupply.sha(raw));
        byte[] originalRaw = Files.readAllBytes(folder.resolve("erepo-gamt-c48-original.json"));
        assertEquals("ac05eb068cc177649f26a190b9f18ce641e457530c95fae8c963dee413f9fb12", CodingTranscriptMaterialSupply.sha(originalRaw));
        var original = (ObjectNode) mapper.readTree(originalRaw); ObjectNode summary = null;
        for (var row : mapper.readTree(Files.readAllBytes(folder.resolve("erepo-gamt-reference-page1.json"))).path("data"))
            if (row.path("uuid").equals(original.path("uuid"))) summary = (ObjectNode) row;
        summary = java.util.Objects.requireNonNull(summary);
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_RECESSIVE");
        context.putObject("gene").put("hgncId", "HGNC:4136").put("symbol", "GAMT");
        context.putObject("disease").put("mondoId", "MONDO:0012999");
        context.putObject("transcript").put("accession", "NM_000156").put("version", 6);
        var guard = PredictionSpecializationGuard.load(mapper, root.resolve("local/resources/acmg/ba1/cspec-20260914-a01/snapshot.json"),
                "b2d2e43216ea15758490a1eb97d55eccd8ded98494e60230102754091fde32e0",
                root.resolve("local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture"));
        assertNull(guard.referenceSpecification(context, original.path("assertionMethod"), summary));
        var sources = new Pvs1StartLossPublicSources(mapper, true, (uri, timeout, maximum) -> {
            assertEquals("cspec.genome.network", uri.getHost());
            assertEquals("/cspec/SequenceVariantInterpretation/id/GN026/version/1.1.0", uri.getPath());
            return new ProteinRegionHttpTransport.Response(200, java.util.Map.of(), raw);
        });
        var spec = guard.historicalReferenceSpecification(context, original.path("assertionMethod"), summary, sources);
        assertNotNull(spec); spec = java.util.Objects.requireNonNull(spec);
        assertEquals("1.1.0", spec.entity().at("/entContent/version").asText());
        assertEquals("1.1.0", original.at("/assertionMethod/version").asText());
        assertFalse(spec.source().path("usedCurrentGuidelineRules").asBoolean());
        assertEquals("ORIGINAL_EXPERT_SUMMARY_EXPLICIT_INHERITANCE", spec.source().at("/inheritanceScopeJoin/basis").asText());
        var result = Pvs1StartLossCuratedGrounds.assess(mapper, summary, original, spec, fixture().model());
        assertEquals("INDEPENDENT_CURATED_BASIS_ESTABLISHED", result.path("status").asText(), result::toPrettyString);
        assertEquals("Pathogenic", result.at("/independentCombination/classification").asText());
        assertEquals(2, result.at("/independentCombination/appliedCodes").size());
        assertEquals(46, result.at("/grounds/0/sourceClaim/truncatingMechanism/prematureStopCdsPosition").asInt());
    }
    @Test void realUpstreamNonsenseReferenceIsValidatedUnderItsHistoricalGuidelineAndCurrentIndividualScvs() throws Exception {
        Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot"));
        Path folder = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102");
        byte[] page = Files.readAllBytes(folder.resolve("erepo-gamt-reference-page1.json"));
        byte[] original = Files.readAllBytes(folder.resolve("erepo-gamt-c48-original.json"));
        byte[] guideline = Files.readAllBytes(folder.resolve("GN026-version1.1.0-contents.json"));
        byte[] clinical = Files.readAllBytes(folder.resolve("VCV002412845.xml"));
        assertEquals("6afc435f2e6dd75ad7e538a3a92410a10dd2488f75223f7bdf9fcbfc6b42d33f", CodingTranscriptMaterialSupply.sha(clinical));
        var model = fixture().model();
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_RECESSIVE");
        context.putObject("gene").put("hgncId", "HGNC:4136").put("symbol", "GAMT");
        context.putObject("disease").put("mondoId", "MONDO:0012999");
        context.putObject("transcript").put("accession", "ENST00000252288").put("version", 8);
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "19")
                .put("position", 1401476).put("reference", "T").put("alternate", "C");
        var source = new Pvs1StartLossPublicSources(mapper, true, (uri, timeout, maximum) -> {
            byte[] body = null;
            if (uri.getHost().equals("erepo.genome.network") && uri.getPath().contains("/summary/")) {
                if (uri.getQuery().endsWith("&pg=1")) body = page;
                else throw new java.io.IOException("Only first native page captured; whole gene enumeration remains incomplete");
            } else if (uri.getPath().endsWith("/interpretation/3a761134-fdab-4d0f-a4da-dcb7b040a030")) body = original;
            else if (uri.getHost().equals("cspec.genome.network") && uri.getPath().endsWith("/GN026/version/1.1.0")) body = guideline;
            else if (uri.toString().endsWith("&id=VCV002412845")) body = clinical;
            return body == null ? new ProteinRegionHttpTransport.Response(404, java.util.Map.of(), new byte[0])
                    : new ProteinRegionHttpTransport.Response(200, java.util.Map.of(), body);
        });
        var guard = PredictionSpecializationGuard.load(mapper, root.resolve("local/resources/acmg/ba1/cspec-20260914-a01/snapshot.json"),
                "b2d2e43216ea15758490a1eb97d55eccd8ded98494e60230102754091fde32e0",
                root.resolve("local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture"));
        var properties = new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties(); properties.getClinVar().setEnabled(true);
        properties.getClinVar().setGrch38File(root.resolve("local/resources/clinvar/clinvar.vcf.gz").toString()); properties.getClinVar().setVersion("2026-03-21");
        String cds = model.path("cdsSequence").asText(); int restart = 0;
        for (int i = 3; i + 3 <= cds.length(); i += 3) if (cds.substring(i, i + 3).equals("ATG")) { restart = i + 1; break; }
        assertEquals(124, restart);
        var scan = new Pvs1StartLossClinVarSearch(mapper, properties).query(context, model, restart);
        assertEquals("ENUMERATED", scan.path("status").asText(), scan::toPrettyString);
        scan.put("sourceSha256", CodingTranscriptMaterialSupply.sha(mapper.writeValueAsBytes(scan)));
        var preparation = new Pvs1StartLossAutomaticPreparation(mapper, () -> null, source, () -> guard);
        var sequence = mapper.createObjectNode().put("candidateRestartCdsPosition", restart);
        var result = preparation.upstreamWithCoverage(context, model, sequence, scan);
        assertEquals("WITNESS_VALIDATED", result.path("status").asText(), result::toPrettyString);
        assertFalse(result.path("negativeCoverageGranted").asBoolean()); assertFalse(result.path("sourceEnumerationComplete").asBoolean());
        ObjectNode adopted = null;
        for (var row : result.path("records")) if (row.path("recordId").asText().equals("3a761134-fdab-4d0f-a4da-dcb7b040a030")) adopted = (ObjectNode) row;
        adopted = java.util.Objects.requireNonNull(adopted);
        assertEquals("QUALIFYING", adopted.path("disposition").asText());
        assertEquals("1.1.0", adopted.at("/nativeAssessment/originalRuleReplay/specificationSource/declaredVersion").asText());
        assertTrue(adopted.at("/nativeAssessment/curatedGrounds/independenceEstablished").asBoolean());
        assertTrue(adopted.at("/nativeAssessment/clinvarAssertions/localEquivalentRecordReviewComplete").asBoolean());
        var archive = mapper.createObjectNode(); archive.set("upstreamReview", result); archive.set("originals", preparation.originals()); archive.set("localClinvarQuery", scan);
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        persistence.saveAndRead(mapper.writeValueAsString(result), mapper.writeValueAsString(archive));
    }
    @Test void historicalSourceDraftWrongVersionScopeAndExplicitContraryInheritanceCannotBeJoined() throws Exception {
        Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot"));
        Path folder = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102");
        var document = (ObjectNode) mapper.readTree(Files.readAllBytes(folder.resolve("GN026-version1.1.0-contents.json")));
        var original = mapper.readTree(Files.readAllBytes(folder.resolve("erepo-gamt-c48-original.json"))); ObjectNode summary = null;
        for (var row : mapper.readTree(Files.readAllBytes(folder.resolve("erepo-gamt-reference-page1.json"))).path("data"))
            if (row.path("uuid").equals(original.path("uuid"))) summary = (ObjectNode) row;
        summary = java.util.Objects.requireNonNull(summary);
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_RECESSIVE");
        context.putObject("gene").put("hgncId", "HGNC:4136").put("symbol", "GAMT"); context.putObject("disease").put("mondoId", "MONDO:0012999");
        context.putObject("transcript").put("accession", "NM_000156").put("version", 6);
        var guard = PredictionSpecializationGuard.load(mapper, root.resolve("local/resources/acmg/ba1/cspec-20260914-a01/snapshot.json"),
                "b2d2e43216ea15758490a1eb97d55eccd8ded98494e60230102754091fde32e0",
                root.resolve("local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture"));
        for (String mode : java.util.List.of("draft", "version", "transcript", "moi", "gene")) {
            var changed = document.deepCopy();
            if (mode.equals("draft")) for (var state : changed.at("/data/entContent/states"))
                if (state.path("current").asBoolean()) ((ObjectNode) state).put("name", "Draft");
            if (mode.equals("version")) ((ObjectNode) changed.at("/data/entContent")).put("version", "2.0.0");
            if (mode.equals("transcript")) ((ObjectNode) changed.at("/data/ld/RuleSet/0/entContent/genes/0")).put("preferredTranscript", "NM_000156.7");
            if (mode.equals("moi")) ((ObjectNode) changed.at("/data/ld/RuleSet/0/entContent/genes/0/diseases/0")).put("preferredModeOfInheritance", "Autosomal dominant inheritance");
            if (mode.equals("gene")) ((ObjectNode) changed.at("/data/ld/Gene/0/entContent/HGNC")).put("hgnc_id", "HGNC:99999");
            var source = new Pvs1StartLossPublicSources(mapper, true,
                    (uri, timeout, maximum) -> new ProteinRegionHttpTransport.Response(200, java.util.Map.of(), mapper.writeValueAsBytes(changed)));
            assertNull(guard.historicalReferenceSpecification(context, original.path("assertionMethod"), summary, source), mode);
        }
        for (String field : java.util.List.of("gene", "mondoId", "moi")) {
            var wrong = summary.deepCopy().put(field, "wrong");
            assertNull(guard.referenceSpecification(context, fixture().original().path("assertionMethod"), wrong));
            var noTransport = new Pvs1StartLossPublicSources(mapper, true, (uri, timeout, maximum) -> { throw new AssertionError("Wrong source summary must not start acquisition"); });
            assertNull(guard.historicalReferenceSpecification(context, original.path("assertionMethod"), wrong, noTransport));
        }
    }
}
