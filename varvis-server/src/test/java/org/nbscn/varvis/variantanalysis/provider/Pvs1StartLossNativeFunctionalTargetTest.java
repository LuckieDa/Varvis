package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.service.CodingTranscriptReferenceReader;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native public allele/model/offline VEP and pinned expert original. Only the
 * analysis identifier and unrelated modules in all-selection are synthetic.
 * HTTP transport replays acquired originals; scientific admission is not stubbed. */
@EnabledIfSystemProperty(named = "pvs1.nativeExpertRoot", matches = ".+")
class Pvs1StartLossNativeFunctionalTargetTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();

    @Test void nativeMecp2TargetUsesScopedCodingBridgeAndPreservesResultAcrossSelectionAndHistory() throws Exception {
        Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot"));
        Path captures = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102/goal-20261103");
        Path models = captures.resolve("native-functional-target-v2");
        byte[] original = Files.readAllBytes(captures.resolve("MECP2-VCV000192289.xml"));
        assertEquals("488db2f6c59b6e6e38a677be04fa054338506fd8f9332c4252fdd3614fae09df", CodingTranscriptMaterialSupply.sha(original));
        var xmlFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        xmlFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        xmlFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var xml = xmlFactory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(original));
        boolean alleleMatched = false;
        var locations = xml.getElementsByTagName("SequenceLocation");
        for (int i = 0; i < locations.getLength(); i++) if (locations.item(i) instanceof org.w3c.dom.Element e)
            alleleMatched |= "GRCh38".equals(e.getAttribute("Assembly")) && "X".equals(e.getAttribute("Chr"))
                    && "154092209".equals(e.getAttribute("positionVCF")) && "T".equals(e.getAttribute("referenceAlleleVCF"))
                    && "A".equals(e.getAttribute("alternateAlleleVCF"));
        assertTrue(alleleMatched, "Original ClinVar supplies allele identity, never the expected PVS1 outcome");
        assertTrue(new String(original, StandardCharsets.UTF_8).contains("NM_004992.4:c.1A&gt;T"));

        var properties = new java.util.Properties();
        for (String name : List.of("evidence-base.properties", "evidence-overrides.properties"))
            try (var reader = Files.newBufferedReader(root.resolve("local/runtime/bp7-evidence-workbench/" + name))) { properties.load(reader); }
        var values = new LinkedHashMap<String, Object>();
        for (String name : properties.stringPropertyNames()) values.put(name, properties.getProperty(name)
                .replace("${VARVIS_PROJECT_ROOT}", root.toString().replace('\\', '/'))
                .replace("${VARVIS_RESOURCE_ROOT}", root.resolve("local/resources").toString().replace('\\', '/'))
                .replace("${VARVIS_TOOL_ROOT}", root.resolve("local/tools").toString().replace('\\', '/')));
        var settings = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(values))
                .bind("variant-analysis", org.springframework.boot.context.properties.bind.Bindable.of(VariantAnalysisProperties.class)).get();
        String prefix = "variant-analysis.acmg-business.";
        var ledger = ExplicitContextSourceLedger.load(mapper, Path.of(values.get(prefix + "reference-receipt-file").toString()),
                values.get(prefix + "reference-receipt-sha256").toString());
        var reference = new CodingTranscriptReferenceReader(mapper, settings, root.resolve("local/resources"), ledger,
                values.get(prefix + "reference-receipt-locator").toString());
        var supply = new CodingTranscriptMaterialSupply(mapper, models,
                "2b55fa74e0c5982765b41c4b234d64dd3bdd51f3d06f56f05785cec743886bbe", reference);
        byte[] modelRaw = Files.readAllBytes(models.resolve("ENST00000303391.11.model.json"));
        assertEquals("819ac24824c106624aa1e447ba440b43dea5c5d9376ad4b700ac38bf3a72b65e", CodingTranscriptMaterialSupply.sha(modelRaw));
        var model = (ObjectNode) mapper.readTree(modelRaw);
        assertEquals("FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE", model.at("/transcriptBridge/status").asText());
        assertEquals("NM_004992.4", model.at("/transcriptBridge/refseqTranscript").asText());
        assertTrue(PublishedCriterionKnowledge.exactBridge("NM_004992.4", model), "Semantic source identity and full-RNA proof must both be usable");
        assertEquals("7e7c041d679b1a52b1df98d709854c8f35e45db21deccfcd00377bab40c2a824",
                CodingTranscriptMaterialSupply.sha(Files.readAllBytes(captures.resolve("MECP2-NM_004992.4.gb"))));
        assertEquals("0537f44a9133813612d6ef1d6cf67456905ac8c78c4aebffd21c6b59a40e8c36",
                CodingTranscriptMaterialSupply.sha(Files.readAllBytes(captures.resolve("MECP2-ENST00000303391-expanded.json"))));
        Path vep = captures.resolve("native-functional-target/MECP2-startloss.vep.jsonl");
        assertEquals("4a41a6af4cad99113615cbcb1f51e1f238b6544c4c43b1c37163695869a03d58", CodingTranscriptMaterialSupply.sha(Files.readAllBytes(vep)));
        var annotations = new VepJsonOutputParser(mapper).parse(vep, "VEP_OFFLINE", "116.0").stream()
                .filter(t -> "ENST00000303391.11".equals(t.getVersionedTranscriptId())).toList();
        assertEquals(1, annotations.size()); assertEquals("ENST00000303391.11:c.1A>T", annotations.get(0).getHgvsc());
        var helper = new TruncatingNmdPvs1RuntimeTest(); var data = helper.inputs(1, -1, "ATGAAATAA", 9);
        data.model = model;
        ObjectNode annotation = new ObjectMapper().findAndRegisterModules().valueToTree(annotations.get(0));
        annotation.put("timestamp", annotations.get(0).getTimestamp().toString());
        annotation.remove(List.of("hgvsc", "hgvsp"));
        helper.candidate(data, 154092209, "T", "A", annotation);
        data.context.withObject("transcript").put("accession", "ENST00000303391").put("version", 11);
        data.context.withObject("gene").put("hgncId", "HGNC:6990").put("symbol", "MECP2");
        data.context.withObject("disease").put("mondoId", "MONDO:0010726"); data.context.put("moi", "X_LINKED_DOMINANT");
        data.context.withObject("variant").withObject("normalization").put("referenceSequenceSha256", settings.getNormalization().getGrch38ReferenceSha256());
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        data.context.withObject("variant").put("alleleId", adapter.canonicalAlleleId(data.context.at("/variant/normalizedAllele"), data.context.at("/variant/normalization")));
        data.context.put("contextId", adapter.canonicalContextId(data.context)); adapter.validateCompleteContext(data.context);
        var initialContext = data.context.deepCopy();
        var check = supply.resolveStructure(data.context, data.candidate); assertTrue(check.ready(), check.snapshot()::toPrettyString);
        assertEquals(initialContext, data.context);
        var knowledge = Pvs1KnowledgeCatalog.load(mapper,
                Path.of(properties.getProperty(prefix + "pvs1-knowledge-source-file")), properties.getProperty(prefix + "pvs1-knowledge-source-sha256"),
                Path.of(properties.getProperty(prefix + "pvs1-knowledge-adoption-file")), properties.getProperty(prefix + "pvs1-knowledge-adoption-sha256"));
        var bundle = ReviewedRuleBundle.loadPvs1Selected(mapper, Path.of(properties.getProperty(prefix + "pvs1-bundle-file")),
                properties.getProperty(prefix + "pvs1-bundle-file-sha256"), properties.getProperty(prefix + "pvs1-bundle-sha256"),
                new Pvs1TranscriptMaterialSupply(mapper, supply), knowledge);
        var expert = new Pvs1StartLossPublishedRuleTest().nativeGuard();
        var calls = new AtomicInteger();
        var sources = new Pvs1StartLossPublicSources(mapper, true, (url, timeout, limit) -> {
            calls.incrementAndGet(); String query = url.getQuery();
            assertEquals("eutils.ncbi.nlm.nih.gov", url.getHost(), "Functional exclusion must not fetch upstream references");
            assertNotNull(query); assertTrue(query.contains("rettype=gb"));
            String accession = query.contains("id=NM_004992.3&") ? "NM_004992.3" : query.contains("id=NM_004992.4&") ? "NM_004992.4" : "";
            assertFalse(accession.isEmpty(), "Unexpected source: " + url);
            return new ProteinRegionHttpTransport.Response(200, Map.of(), Files.readAllBytes(captures.resolve("MECP2-" + accession + ".gb")));
        });
        var preparation = new Pvs1StartLossAutomaticPreparation(mapper, () -> supply, sources, () -> expert);
        // Source replay must succeed independently of the orchestration path.
        var molecular = Pvs1StartLossCodingScopeBridge.verify(mapper, data.context, model, "NM_004992.3", sources);
        assertEquals("CODING_START_SCOPE_VERIFIED", molecular.audit().path("status").asText());
        var scoped = Pvs1StartLossTranscriptQualifier.sourceScopedExclusion(mapper, data.context, model, expert, sources);
        assertNotNull(scoped, "The pinned expert assertion must replay in the verified coding scope");
        var prepared = preparation.prepareForEvaluation(data.context, data.candidate, bundle); var result = prepared.decision();
        assertNotNull(result, prepared.preparation()::toPrettyString);
        assertEquals("NOT_APPLICABLE", result.at("/evaluation/criterionApplicability/status").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals("PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT", result.at("/evaluation/criterionApplicability/reasonCode").asText());
        assertEquals(2, calls.get()); assertEquals("NOT_REQUIRED", prepared.preparation().at("/upstreamReview/status").asText());
        assertEquals(initialContext, data.context);
        var proof = result.at("/automaticStartLossFacts/sourceQualifiedTranscriptAssessment/evidence");
        assertEquals("EXPLICIT_SOURCE_START_LOSS_EXCLUSION", proof.path("qualificationBasis").asText());
        assertTrue(proof.path("expertSpecificationKeys").toString().contains("GN036@6.0"));
        assertEquals("NM_004992.3", proof.at("/codingScopeBridge/sourceTranscript").asText());
        assertEquals("ENST00000303391.11", proof.at("/codingScopeBridge/selectedTranscript").asText());
        assertEquals("NM_004992.4", proof.at("/codingScopeBridge/selectedRefseqTranscript").asText());
        assertFalse(proof.at("/codingScopeBridge/fullTranscriptEquivalenceGranted").asBoolean());
        result.set("startLossAutomaticPreparation", prepared.preparation());
        var publicView = Pvs1SelectedBatch.publicSummary(mapper, result);
        assertEquals("1.6.0", publicView.path("methodVersion").asText());
        var archive = mapper.createObjectNode(); archive.set("result", result); archive.set("originals", preparation.originals());
        archive.withObject("originals").putObject(CodingTranscriptMaterialSupply.sha(original)).put("rawXml", new String(original, StandardCharsets.UTF_8));
        archive.withObject("originals").putObject(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(captures.resolve("MECP2-ENST00000303391-expanded.json"))))
                .put("rawJson", Files.readString(captures.resolve("MECP2-ENST00000303391-expanded.json")));
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = Files.createDirectory(temporary.resolve("history"));
        persistence.saveAndRead(mapper.writeValueAsString(publicView), mapper.writeValueAsString(archive));
        compareSelected(data, bundle, preparation);
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            Files.createDirectories(Path.of(output));
            var criterion = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PVS1").put("status", "NOT_APPLICABLE")
                    .put("appliedStrength", "NONE").put("reasonCode", "PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT");
            criterion.putObject("details").set("pvs1Facts", publicView);
            Files.writeString(Path.of(output).resolve("NATIVE_TARGET_MECP2_FUNCTIONAL_EXCLUSION.json"), mapper.writeValueAsString(criterion));
        }
    }

    private void compareSelected(TruncatingNmdPvs1RuntimeTest.Inputs data, ReviewedRuleBundle bundle,
            Pvs1StartLossAutomaticPreparation preparation) throws Exception {
        data.context.withObject("transcript").put("selectionPolicy",
                org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.POLICY_ID + "@1.0.0-unified-local-fourteen");
        data.context.put("contextId", new RuntimeEvidenceV2Adapter(mapper).canonicalContextId(data.context));
        var pvs1 = new Pvs1SelectedBatch(mapper, bundle, preparation); var business = new LocalEvidenceBatchRuntimeTest();
        SelectedEvidencePreparation selected = org.springframework.test.util.ReflectionTestUtils.invokeMethod(business, "selected", "one", data.context, data.candidate);
        assertNotNull(selected); JsonNode first = null;
        for (boolean all : new boolean[]{false, true}) {
            var denovo = mock(DeNovoSelectedBatch.class); when(denovo.identity()).thenReturn(mapper.createObjectNode());
            var pm3 = mock(Pm3SelectedBatch.class); when(pm3.identity()).thenReturn(mapper.createObjectNode()); when(pm3.originals()).thenReturn(mapper.createObjectNode());
            var pp1 = mock(Pp1SelectedBatch.class); when(pp1.identity()).thenReturn(mapper.createObjectNode()); when(pp1.originals()).thenReturn(mapper.createObjectNode());
            var bp7 = mock(Bp7SelectedBatch.class); when(bp7.identity()).thenReturn(mapper.createObjectNode());
            var runtime = new LocalEvidenceBatchRuntime(mapper, mapper.createObjectNode(), null, null, null, null, bp7,
                    () -> { throw new IllegalStateException("ISOLATED_OTHER_MODULE"); }, () -> pvs1,
                    () -> { throw new IllegalStateException("ISOLATED_OTHER_MODULE"); }, () -> { throw new IllegalStateException("ISOLATED_OTHER_MODULE"); },
                    () -> { throw new IllegalStateException("ISOLATED_OTHER_MODULE"); }, () -> { throw new IllegalStateException("ISOLATED_OTHER_MODULE"); }, false, denovo, pm3, pp1);
            runtime.bindExecutionCriteria(all ? SelectedCriterionRouting.CODES.stream().map(c -> "ACMG_AMP_2015:" + c).toList() : List.of("ACMG_AMP_2015:PVS1"));
            var full = runtime.execute(selected, business.analysis, business.sourceSha); var view = runtime.publicView(full); JsonNode criterion = null;
            for (var row : view.at("/results/0/localBatch/criteria")) if ("ACMG_AMP_2015:PVS1".equals(row.path("criterionId").asText())) criterion = row;
            assertNotNull(criterion, view::toPrettyString); assertEquals("NOT_APPLICABLE", criterion.path("status").asText());
            assertEquals("NONE", criterion.path("appliedStrength").asText());
            assertTrue(criterion.path("reason").asText().contains("功能替代转录本"));
            assertFalse(criterion.path("reason").asText().contains("保留未评价"));
            if (first == null) first = criterion; else assertEquals(first, criterion);
            assertFalse(full.at("/modules/pvs1/startLossSourceDocuments").isEmpty());
            if (all) {
                var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = Files.createDirectory(temporary.resolve("batch-history"));
                persistence.saveAndRead(mapper.writeValueAsString(view), mapper.writeValueAsString(full));
            }
        }
    }
}
