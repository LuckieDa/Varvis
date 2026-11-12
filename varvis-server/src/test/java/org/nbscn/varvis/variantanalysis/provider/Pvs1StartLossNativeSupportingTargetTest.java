package org.nbscn.varvis.variantanalysis.provider;

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
import static org.junit.jupiter.api.Assertions.*;

/** Reported FOXL2 allele with actual RNA/model/reference/offline VEP, complete
 * declared transcript sources and bounded upstream enumeration. The original
 * ClinVar VUS is used for identity only, never as a PVS1 expected classification.
 * Public HTTP originals are replayed; no scientific-admission stub is used. */
@EnabledIfSystemProperty(named = "pvs1.nativeExpertRoot", matches = ".+")
class Pvs1StartLossNativeSupportingTargetTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();

    @Test void nativeReportedFoxl2StartLossUsesCompleteDeclaredSourcesWithoutPromotingTheTargetVus() throws Exception {
        Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot"));
        Path captures = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102/goal-20261103");
        Path models = captures.resolve("native-foxl2-reported-target");
        byte[] targetXml = Files.readAllBytes(captures.resolve("FOXL2-VCV002110813.xml"));
        assertEquals("4e9fdfe3ce576f501fe7081e33e5cc7efdd8e9125c98028ade3ab2152f46c3ac", CodingTranscriptMaterialSupply.sha(targetXml));
        String targetText = new String(targetXml, StandardCharsets.UTF_8);
        assertTrue(targetText.contains("NM_023067.4:c.3G&gt;C"));
        var xmlFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        xmlFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        xmlFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var xml = xmlFactory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(targetXml));
        var locations = xml.getElementsByTagName("SequenceLocation"); boolean alleleMatched = false;
        for (int i = 0; i < locations.getLength(); i++) if (locations.item(i) instanceof org.w3c.dom.Element e)
            alleleMatched |= "GRCh38".equals(e.getAttribute("Assembly")) && "3".equals(e.getAttribute("Chr"))
                    && "138946720".equals(e.getAttribute("positionVCF")) && "C".equals(e.getAttribute("referenceAlleleVCF"))
                    && "G".equals(e.getAttribute("alternateAlleleVCF"));
        assertTrue(alleleMatched);

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
        var gencode = new GencodeTranscriptIndex(mapper, root.resolve("local/resources/gencode/derived/v38-transcripts-a1-inventory-20261102"),
                "44a09eb4bfc2e1cf5c4e5d7bc90b56958866e2f3fe6b9fce6697554c87e8cc42");
        var supply = new CodingTranscriptMaterialSupply(mapper, models,
                "7526a1db92010495395b2435689555827793ff1fa711e79164b95a1ed786264c", reference, gencode);
        byte[] modelRaw = Files.readAllBytes(models.resolve("ENST00000648323.1.model.json"));
        assertEquals("031c2f6c03f9791618c96718e0d64f900bed5ddbef349afc303915c1b9be53b0", CodingTranscriptMaterialSupply.sha(modelRaw));
        var model = (ObjectNode) mapper.readTree(modelRaw);
        assertTrue(PublishedCriterionKnowledge.exactBridge("NM_023067.4", model));
        assertEquals("a4ef1a5f826ed2dd24307ad2b3a4d4dd6008fab065710a2a581d7be43460162c", model.at("/sequenceVerification/cdsSha256").asText());
        Path vep = models.resolve("FOXL2-c3-reported.vep.jsonl");
        assertEquals("f1c0d8e2284e5eae111fedf1dd8662dab60948536fa709add6139c5d40e0fb5b", CodingTranscriptMaterialSupply.sha(Files.readAllBytes(vep)));
        var annotations = new VepJsonOutputParser(mapper).parse(vep, "VEP_OFFLINE", "116.0").stream()
                .filter(t -> "ENST00000648323.1".equals(t.getVersionedTranscriptId())).toList();
        assertEquals(1, annotations.size()); assertEquals("ENST00000648323.1:c.3G>C", annotations.get(0).getHgvsc());
        var helper = new TruncatingNmdPvs1RuntimeTest(); var data = helper.inputs(1, -1, "ATGAAATAA", 9); data.model = model;
        ObjectNode annotation = new ObjectMapper().findAndRegisterModules().valueToTree(annotations.get(0));
        annotation.put("timestamp", annotations.get(0).getTimestamp().toString()); annotation.remove(List.of("hgvsc", "hgvsp"));
        helper.candidate(data, 138946720, "C", "G", annotation);
        data.context.withObject("transcript").put("accession", "ENST00000648323").put("version", 1);
        data.context.withObject("gene").put("hgncId", "HGNC:1092").put("symbol", "FOXL2");
        data.context.withObject("disease").put("mondoId", "MONDO:0007201"); data.context.put("moi", "AUTOSOMAL_DOMINANT");
        data.context.withObject("variant").withObject("normalization").put("referenceSequenceSha256", settings.getNormalization().getGrch38ReferenceSha256());
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        data.context.withObject("variant").put("alleleId", adapter.canonicalAlleleId(data.context.at("/variant/normalizedAllele"), data.context.at("/variant/normalization")));
        data.context.put("contextId", adapter.canonicalContextId(data.context)); adapter.validateCompleteContext(data.context);
        assertTrue(supply.resolveStructure(data.context, data.candidate).ready());
        var knowledge = Pvs1KnowledgeCatalog.load(mapper,
                Path.of(properties.getProperty(prefix + "pvs1-knowledge-source-file")), properties.getProperty(prefix + "pvs1-knowledge-source-sha256"),
                Path.of(properties.getProperty(prefix + "pvs1-knowledge-adoption-file")), properties.getProperty(prefix + "pvs1-knowledge-adoption-sha256"));
        var bundle = ReviewedRuleBundle.loadPvs1Selected(mapper, Path.of(properties.getProperty(prefix + "pvs1-bundle-file")),
                properties.getProperty(prefix + "pvs1-bundle-file-sha256"), properties.getProperty(prefix + "pvs1-bundle-sha256"),
                new Pvs1TranscriptMaterialSupply(mapper, supply), knowledge);
        var count = new AtomicInteger(); var erepoCount = new AtomicInteger();
        var sources = new Pvs1StartLossPublicSources(mapper, true, (url, timeout, maximum) -> {
            count.incrementAndGet(); String source = url.toString(), file;
            if (source.equals(Pvs1StartLossClinGenMechanism.DOSAGE_URL)) file = "ClinGen-dosage-genes-current.csv";
            else if (source.equals(Pvs1StartLossClinGenMechanism.VALIDITY_URL)) file = "ClinGen-validity-current.csv";
            else if (source.equals(Pvs1StartLossClinGenMechanism.DISEASE_DOSAGE_URL)) file = "ClinGen-gene-dosage-GRCh38-current.tsv";
            else if (url.getHost().equals("api.ncbi.nlm.nih.gov") && url.getPath().contains("dataset_report")) file = "FOXL2-gene668-current.json";
            else if (url.getHost().equals("api.ncbi.nlm.nih.gov") && url.getPath().contains("product_report")) file = "FOXL2-products668-current.json";
            else if (url.getHost().equals("eutils.ncbi.nlm.nih.gov") && source.contains("db=nuccore") && source.contains("rettype=fasta")) file = "FOXL2-NM_023067.4.fasta";
            else if (url.getHost().equals("erepo.genome.network") && url.getPath().contains("/summary/")) {
                int pass = erepoCount.incrementAndGet(); assertTrue(pass <= 2); file = "FOXL2-erepo-page1-pass" + pass + ".json";
            } else throw new AssertionError("Unexpected source request: " + source);
            int status = mapper.readTree(captures.resolve(file + ".receipt.json").toFile()).path("httpStatus").asInt(-1);
            assertTrue(status == 200 || status == 404, "Capture must retain the actual original HTTP status");
            return new ProteinRegionHttpTransport.Response(status, Map.of(), Files.readAllBytes(captures.resolve(file)));
        });
        var search = new Pvs1StartLossClinVarSearch(mapper, settings);
        var expert = new Pvs1StartLossPublishedRuleTest().nativeGuard();
        var preparation = new Pvs1StartLossAutomaticPreparation(mapper, () -> supply, sources,
                () -> expert, search);
        var prepared = preparation.prepareForEvaluation(data.context, data.candidate, bundle); var result = prepared.decision();
        assertNotNull(result, prepared.preparation()::toPrettyString);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("SUPPORTING", result.at("/evaluation/appliedStrength").asText());
        assertEquals("PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE", result.at("/evaluation/evaluationResult/reasonCode").asText());
        var p = prepared.preparation();
        assertEquals("COMPLETE_STRUCTURAL_SOURCE_SET_NO_DIFFERENT_START", p.at("/transcriptReview/qualificationBasis").asText());
        assertEquals(4, p.at("/sequence/candidateRestartCdsPosition").asInt()); assertEquals(3, p.at("/sequence/cdsPosition").asInt());
        assertEquals("ATC", p.at("/sequence/alternateCodon").asText());
        assertEquals("NO_QUALIFYING_REFERENCE_WITHIN_SCOPE", p.at("/upstreamReview/status").asText());
        assertTrue(p.at("/upstreamReview/negativeCoverageGranted").asBoolean());
        assertEquals(1, p.at("/clinvarSearch/records").size(), "The real local interval contains the target VUS, not zero records");
        assertEquals(2, erepoCount.get());
        assertTrue(p.at("/mechanismAssessment/clingenAssessment").isObject());
        result.set("startLossAutomaticPreparation", p); var publicView = Pvs1SelectedBatch.publicSummary(mapper, result);
        var archive = mapper.createObjectNode(); archive.set("result", result); archive.set("originals", preparation.originals());
        archive.withObject("originals").putObject(CodingTranscriptMaterialSupply.sha(targetXml)).put("rawXml", targetText);
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = Files.createDirectory(temporary.resolve("history"));
        persistence.saveAndRead(mapper.writeValueAsString(publicView), mapper.writeValueAsString(archive));
        new Pvs1StartLossPublishedRuleTest().assertSelectedBatch(data, bundle, preparation, "SUPPORTING", Files.createDirectory(temporary.resolve("batch")));
        assertEquals(2, erepoCount.get(), "Same acquired scope is reused across the selected batch");
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            Files.createDirectories(Path.of(output));
            var criterion = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PVS1").put("status", "MET").put("appliedStrength", "SUPPORTING")
                    .put("reasonCode", "PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE");
            criterion.putObject("details").set("pvs1Facts", publicView);
            Files.writeString(Path.of(output).resolve("NATIVE_TARGET_FOXL2_C3_SUPPORTING.json"), mapper.writeValueAsString(criterion));
        }
    }
}
