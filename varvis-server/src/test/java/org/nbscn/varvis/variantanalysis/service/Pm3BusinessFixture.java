package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Support only, not a selected JUnit class. Reuses existing actual publication/H2/HTTP helpers. */
class Pm3BusinessFixture extends Ps2BusinessIntegrationTest {
    @Override void start(String name) throws Exception {
        byte[] raw;
        try (var in = getClass().getResourceAsStream("/pm3-in-trans-core/" + name + "-bundle.json")) {
            assertNotNull(in); raw = in.readAllBytes();
        }
        bundle = (ObjectNode) mapper.readTree(raw); material = temporary.resolve("material.json"); Files.write(material, raw);
        env = new MockEnvironment().withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.acceptance-scope", AcmgBusinessRuleScope.PM3_DEVELOPMENT);
        pins(raw); publish(name); wire();
        Files.write(archive.resolve("original-bundle-raw.json"), raw, StandardOpenOption.CREATE_NEW);
        artifact("original-pins", mapper.createObjectNode().put("rawSha256", sha(raw)).put("canonicalSha256", canonical(bundle)));
    }
    @Override String canonical(JsonNode node) { try { return org.nbscn.varvis.variantanalysis.provider.Pm3BusinessMaterials.hash(node); } catch (Exception e) { throw new IllegalArgumentException(e); } }
    @Override void pins(byte[] raw) throws Exception {
        env.withProperty("variant-analysis.acmg-business.pm3-bundle-file", requireNonNull(material.toString()))
                .withProperty("variant-analysis.acmg-business.pm3-bundle-file-sha256", requireNonNull(sha(raw)))
                .withProperty("variant-analysis.acmg-business.pm3-bundle-sha256", requireNonNull(canonical(bundle)));
    }
    @Override void wire() {
        service = new AcmgBusinessConfig().acmgBusinessService(mapper, jobs, store, sources, properties, env);
        Object value = ReflectionTestUtils.getField(requireNonNull(service), "pm3Preparation"); assertInstanceOf(Pm3InTransContextPreparation.class, value);
        assertNull(ReflectionTestUtils.getField(requireNonNull(service), "ps2Preparation"));
        var preparation = spy((Pm3InTransContextPreparation) value);
        doAnswer(call -> { loads.incrementAndGet(); return call.callRealMethod(); }).when(preparation).load();
        doAnswer(call -> { prepares.incrementAndGet(); return call.callRealMethod(); }).when(preparation).prepare(any(), any(), any(), any(), any(), any());
        doAnswer(call -> { assertTrue(store.runs(analysisId).stream().anyMatch(r -> "RUNNING".equals(r.status())));
            core.incrementAndGet(); return call.callRealMethod(); }).when(preparation).evaluate(any());
        ReflectionTestUtils.setField(requireNonNull(service), "pm3Preparation", preparation);
        for (String field : supplierFields()) ReflectionTestUtils.setField(requireNonNull(service), requireNonNull(field), (Supplier<Object>) () -> {
            forbidden.incrementAndGet(); throw new AssertionError("FORBIDDEN_SUPPLIER_" + field);
        });
        http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
    }
    static List<String> supplierFields() { return List.of("runtime", "ba1Runtime", "pm2Runtime", "pvs1Runtime", "missenseRuntime", "functionalRuntime", "pm1Runtime", "hnf1aFrequencyRuntime"); }
    @Override ObjectNode request(ObjectNode options) {
        ObjectNode r = mapper.createObjectNode().put("topN", 1).put("declarationVersion", AcmgBusinessRuleScope.PM3_DECLARATION)
                .put("sampleId", options.path("sampleId").asText()).put("dataClass", "SYNTHETIC"); r.putArray("hypotheses");
        ObjectNode patient = (ObjectNode) bundle.at("/context/patientContext").deepCopy(); patient.remove("affectedStatus"); r.set("patient", patient);
        r.set("pm3Selection", ((ObjectNode) options.path("pm3Selection")).deepCopy().put("confirmed", true)); return r;
    }
    @Override ObjectNode submit(ObjectNode request) throws Exception {
        ObjectNode saved = super.submit(request);
        var record = store.run(analysisId, saved.path("runId").asText()).orElseThrow();
        artifact("stored-run-record", mapper.valueToTree(record)); artifact("calls-after-request", counts());
        return saved;
    }
    static ObjectNode at(JsonNode n, String p) { return (ObjectNode) n.at(p); }
    /** Independent TEST_ONLY issuer shared with core tests, never called by production. */
    void issue(String label, java.util.function.Consumer<ObjectNode> change) throws Exception {
        artifact(label + "-before", bundle); change.accept(bundle);
        org.nbscn.varvis.variantanalysis.provider.Pm3BusinessMaterials.reissue(bundle);
        saveNew(label, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle));
    }
    void saveNew(String label, byte[] bytes) throws Exception {
        material = temporary.resolve(label + "-material.json"); Files.write(material, bytes, StandardOpenOption.CREATE_NEW);
        Files.write(archive.resolve(label + "-loader-bytes.json"), bytes, StandardOpenOption.CREATE_NEW);
        bundle = (ObjectNode) mapper.readTree(bytes); pins(bytes); wire();
        artifact(label + "-pins", mapper.createObjectNode().put("rawSha256", sha(bytes)).put("canonicalSha256", canonical(bundle)));
    }
    void verifyArtifact(ObjectNode saved) throws Exception {
        var coreValue = saved.at("/candidates/0/attempts/0/pm3"); var artifact = at(saved, "/bundles/0");
        assertEquals(coreValue, artifact.path("core")); assertEquals(bundle, coreValue.path("sourceBundle"));
        byte[] raw = mapper.writeValueAsBytes(coreValue);
        Files.write(archive.resolve("core-" + saved.path("runId").asText() + ".json"), raw, StandardOpenOption.CREATE_NEW);
        assertEquals(sha(raw), artifact.path("coreRawSha256").asText());
        var identity = artifact.deepCopy(); identity.remove(List.of("core", "bundleId"));
        assertEquals("pm3-business-artifact-sha256-" + canonical(identity), artifact.path("bundleId").asText());


    }
    String phaseGt = null;
    boolean phaseSet;
    String partnerMode = "normal";
    @Override void publish(String name) throws Exception {
        analysisId = java.util.UUID.randomUUID().toString();
        var facts = bundle.at("/observations/0/facts"); String sample = facts.at("/members/0/sampleId").asText();
        var lines = new java.util.ArrayList<String>(); var ranks = new java.util.ArrayList<String>();
        String gene = bundle.at("/context/gene/symbol").asText();
        for (int index = 0; index < 3; index++) {
            boolean decoy = index == 1; int recordIndex = index == 2 ? 1 : 0;
            var r = facts.path("records").path(recordIndex); var key = r.path("variantKey");
            String contig = key.path("chromosome").asText(), pos = decoy ? "700011" : key.path("position").asText();
            String ref = decoy ? "A" : key.path("reference").asText(), alt = decoy ? "G" : key.path("alternate").asText();
            String raw = r.path("rawSampleValue").asText();
            if (phaseGt != null) raw = phaseGt + raw.substring(raw.indexOf(':'));
            if (phaseSet) raw += ":7";
            lines.add("chr" + contig + "\t" + pos + "\t.\t" + ref + "\t" + alt + "\t.\tPASS\t.\tGT:DP:GQ:AD:FT" + (phaseSet ? ":PS" : "") + "\t" + raw);
            if (!(index == 2 && partnerMode.equals("unpublished"))) ranks.add((index + 1) + "\t" + contig + "\t" + pos + "\t" + ref + "\t" + alt + "\t" + gene + "\t930031\tAR\t" + (index == 0 ? "0.9" : index == 1 ? "0.8" : "0.7"));
        }
        if (partnerMode.equals("ambiguous")) ranks.add(ranks.get(2).replaceFirst("^3", "4"));
        if (partnerMode.equals("outside-window")) {
            String partner = ranks.remove(2);
            for (int i = 3; i <= 100; i++) ranks.add(ranks.get(1).replaceFirst("^2", Integer.toString(i)));
            ranks.add(partner.replaceFirst("^3", "101").replace("0.7", "0.1"));
        }
        if (decoyFirst) { String first = ranks.get(0); ranks.set(0, ranks.get(1).replaceFirst("^2", "1").replace("0.8", "0.99")); ranks.set(1, first.replaceFirst("^1", "2").replace("0.9", "0.8")); }
        String vcf = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf(lines.toArray(String[]::new))
                .replace("synthetic-proband", sample).replace("#CHROM", "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"depth\">\n##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"quality\">\n##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"allele depth\">\n##FORMAT=<ID=FT,Number=1,Type=String,Description=\"sample filter\">\n" + (phaseSet ? "##FORMAT=<ID=PS,Number=1,Type=Integer,Description=\"phase set\">\n" : "") + "#CHROM");
        var fixture = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.prepare(properties.getStorageRoot().resolve(analysisId), analysisId,
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38, vcf,
                org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv(ranks.toArray(String[]::new)), null, false);
        var old = fixture.job(); var job = new org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob(analysisId, "TEST_ONLY.vcf", old.getFileSha256(),
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38, org.nbscn.varvis.variantanalysis.domain.AnalysisMode.WES, sample,
                properties.getStorageRoot().resolve(analysisId), old.getUploadedFile()); jobs.save(job);
        var converter = mock(VcfBuildConverter.class); when(converter.convert(any(), any(), any(), any())).thenReturn(fixture.conversion());
        var enrichment = mock(VariantInterpretationService.class); when(enrichment.enrichEvidenceOnly(any(), any())).thenAnswer(call -> call.getArgument(0));
        var annotation = mock(VepTranscriptAnnotationEnrichmentService.class);
        when(annotation.annotateCandidates(any(), any(), any(), any(), any(org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.class))).thenReturn(
                new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(Map.of(), VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested()));
        var processor = new VariantAnalysisJobProcessor(jobs, converter, mock(VcfNormalizer.class), mock(VcfQcService.class), enrichment, properties, annotation);
        ReflectionTestUtils.setField(processor, "acmgBusinessEnabled", true); processor.setExomiserEngineAdapter(fixture.adapter()); processor.setAcmgExecutionSources(sources);
        processor.process(analysisId);
        assertEquals(org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus.READY, jobs.findById(analysisId).orElseThrow().getStatus(), jobs.findById(analysisId).orElseThrow().getErrorMessage());
        artifact("TEST_ONLY-published-source", mapper.readTree(store.source(analysisId).orElseThrow().json()));
        artifact("TEST_ONLY-published-candidates", mapper.valueToTree(jobs.findInterpretations(analysisId, 0, 100)));
    }

}