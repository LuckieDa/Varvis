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
class Pp4BusinessFixture extends Ps2BusinessIntegrationTest {
    @Override ObjectNode options() throws Exception {
        var before = store.runs(analysisId); var result = super.options();
        assertEquals(before, store.runs(analysisId)); return result;
    }
    @Override void rebuildAndCompare(ObjectNode request, ObjectNode saved) throws Exception {
        var originalRuns = store.runs(analysisId);
        var before = counts();
        new org.springframework.jdbc.core.JdbcTemplate(requireNonNull(dataSource)).execute("SHUTDOWN");
        assertFalse(Files.exists(temporary.resolve("business-db.lock.db")));
        artifact("shutdown", mapper.createObjectNode().put("dbRawSha256", sha(Files.readAllBytes(temporary.resolve("business-db.mv.db"))))
                .put("lockAbsent", true).put("reconstruction", "SAME_JVM"));
        repositories(); wire();
        var got = mapper.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/variant-analyses/{id}/acmg/runs/{run}", analysisId, saved.path("runId").asText()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved, got); artifact("get-after-store-service-rebuild", got);
        assertEquals(saved, submit(request)); assertEquals(originalRuns, store.runs(analysisId));
        assertEquals(before, counts()); assertEquals(0, forbidden.get()); artifact("counts-after-reuse", counts());
    }
    @Override void start(String name) throws Exception {
        Path inputs = Path.of(System.getProperty("varvis.pp4.business.inputs"));
        Path source = name.startsWith("frozen/") ? inputs.resolve(name + "/input.json")
                : name.startsWith("numerical/") ? inputs.resolve(name + "/loader-bytes.json") : inputs.resolve("positive/" + name + "-bundle.json");
        byte[] raw = Files.readAllBytes(source);
        var pinFile = name.contains("/") ? source.getParent().resolve("pins.json") : inputs.resolve("positive/" + name + "-pins.json");
        var pins = mapper.readTree(Files.readAllBytes(pinFile));
        String rawPin = pins.path("rawSha256").asText(pins.path("rawPin").asText());
        String canonicalPin = pins.path("canonicalSha256").asText(pins.path("canonicalPin").asText());
        assertEquals(rawPin, sha(raw)); assertEquals(canonicalPin, canonical(mapper.readTree(raw)));
        bundle = (ObjectNode) mapper.readTree(raw); material = temporary.resolve("material.json"); Files.write(material, raw);
        env = new MockEnvironment().withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.acceptance-scope", AcmgBusinessRuleScope.PP4_DEVELOPMENT);
        pins(raw); publish(name); wire();
        Files.write(archive.resolve("original-bundle-raw.json"), raw, StandardOpenOption.CREATE_NEW);
        artifact("original-pins", mapper.createObjectNode().put("rawSha256", sha(raw)).put("canonicalSha256", canonical(bundle)));
    }
    @Override String canonical(JsonNode node) { try { return org.nbscn.varvis.variantanalysis.provider.Pp4BusinessMaterials.hash(node); } catch (Exception e) { throw new IllegalArgumentException(e); } }
    @Override void pins(byte[] raw) throws Exception {
        env.withProperty("variant-analysis.acmg-business.pp4-bundle-file", requireNonNull(material.toString()))
                .withProperty("variant-analysis.acmg-business.pp4-bundle-file-sha256", requireNonNull(sha(raw)))
                .withProperty("variant-analysis.acmg-business.pp4-bundle-sha256", requireNonNull(canonical(bundle)));
    }
    @Override void wire() {
        service = new AcmgBusinessConfig().acmgBusinessService(mapper, jobs, store, sources, properties, env);
        Object value = ReflectionTestUtils.getField(requireNonNull(service), "pp4Preparation"); assertInstanceOf(Pp4PhenotypeContextPreparation.class, value);
        assertNull(ReflectionTestUtils.getField(requireNonNull(service), "ps2Preparation"));
        var preparation = spy((Pp4PhenotypeContextPreparation) value);
        doAnswer(call -> { loads.incrementAndGet(); return call.callRealMethod(); }).when(preparation).load();
        doAnswer(call -> { prepares.incrementAndGet(); return call.callRealMethod(); }).when(preparation).prepare(any(), any(), any(), any(), any(), any());
        doAnswer(call -> { assertTrue(store.runs(analysisId).stream().anyMatch(r -> "RUNNING".equals(r.status())));
            core.incrementAndGet(); return call.callRealMethod(); }).when(preparation).evaluate(any());
        ReflectionTestUtils.setField(requireNonNull(service), "pp4Preparation", preparation);
        for (String field : supplierFields()) ReflectionTestUtils.setField(requireNonNull(service), requireNonNull(field), (Supplier<Object>) () -> {
            forbidden.incrementAndGet(); throw new AssertionError("FORBIDDEN_SUPPLIER_" + field);
        });
        http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
    }
    static List<String> supplierFields() { return List.of("runtime", "ba1Runtime", "pm2Runtime", "pvs1Runtime", "missenseRuntime", "functionalRuntime", "pm1Runtime", "hnf1aFrequencyRuntime"); }
    @Override ObjectNode request(ObjectNode options) {
        ObjectNode r = mapper.createObjectNode().put("topN", 1).put("declarationVersion", AcmgBusinessRuleScope.PP4_DECLARATION)
                .put("sampleId", options.path("sampleId").asText()).put("dataClass", "SYNTHETIC"); r.putArray("hypotheses");
        ObjectNode patient = (ObjectNode) bundle.at("/context/patientContext").deepCopy(); patient.remove("affectedStatus"); r.set("patient", patient);
        r.set("pp4Selection", ((ObjectNode) options.path("pp4Selection")).deepCopy().put("confirmed", true)); return r;
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
        org.nbscn.varvis.variantanalysis.provider.Pp4BusinessMaterials.reissue(bundle);
        saveNew(label, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle));
    }
    void saveNew(String label, byte[] bytes) throws Exception {
        material = temporary.resolve(label + "-material.json"); Files.write(material, bytes, StandardOpenOption.CREATE_NEW);
        Files.write(archive.resolve(label + "-loader-bytes.json"), bytes, StandardOpenOption.CREATE_NEW);
        bundle = (ObjectNode) mapper.readTree(bytes); pins(bytes); wire();
        artifact(label + "-pins", mapper.createObjectNode().put("rawSha256", sha(bytes)).put("canonicalSha256", canonical(bundle)));
    }
    void verifyArtifact(ObjectNode saved) throws Exception {
        var coreValue = saved.at("/candidates/0/attempts/0/pp4"); var artifact = at(saved, "/bundles/0");
        assertEquals(coreValue, artifact.path("core")); assertEquals(bundle, coreValue.path("sourceBundle"));
        byte[] raw = mapper.writeValueAsBytes(coreValue);
        Files.write(archive.resolve("core-" + saved.path("runId").asText() + ".json"), raw, StandardOpenOption.CREATE_NEW);
        assertEquals(sha(raw), artifact.path("coreRawSha256").asText());
        var identity = artifact.deepCopy(); identity.remove(List.of("core", "bundleId"));
        assertEquals("pp4-business-artifact-sha256-" + canonical(identity), artifact.path("bundleId").asText());


    }
    String phaseGt = null;
    boolean phaseSet;
    String publishedFt;
    java.util.function.UnaryOperator<String> publishedVcf = value -> value;
    @Override void publish(String name) throws Exception {
        analysisId = UUID.randomUUID().toString();
        var facts = bundle.at("/observations/0/facts");
        var member = java.util.stream.StreamSupport.stream(facts.path("members").spliterator(), false)
                .filter(m -> "PROBAND".equals(m.path("role").asText())).findFirst().orElseThrow();
        var record = java.util.stream.StreamSupport.stream(facts.path("records").spliterator(), false)
                .filter(r -> "PROBAND".equals(r.path("role").asText())).findFirst().orElseThrow();
        String sample = member.path("sampleId").asText(); var key = record.path("variantKey");
        String contig = key.path("chromosome").asText(), position = key.path("position").asText(), ref = key.path("reference").asText(), alt = key.path("alternate").asText();
        String raw = record.path("rawSampleValue").asText(), format = member.at("/detection/sampleFormat").asText();
        if (publishedFt != null) raw = raw.replace("PASS", publishedFt);
        if (phaseGt != null) raw = phaseGt + raw.substring(raw.indexOf(':'));
        if (phaseSet) { raw += ":7"; format += ":PS"; }
        String gene = bundle.at("/context/gene/symbol").asText();
        String line = "chr" + contig + "\t" + position + "\t.\t" + ref + "\t" + alt + "\t.\tPASS\t.\t" + format + "\t" + raw;
        String vcf = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf(line)
                .replace("synthetic-proband", sample).replace("#CHROM", "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"depth\">\n##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"quality\">\n##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"allele depth\">\n##FORMAT=<ID=FT,Number=1,Type=String,Description=\"sample filter\">\n" + (phaseSet ? "##FORMAT=<ID=PS,Number=1,Type=Integer,Description=\"phase set\">\n" : "") + "#CHROM");
        String rank = "1\t" + contig + "\t" + position + "\t" + ref + "\t" + alt + "\t" + gene + "\t940011\tAD\t0.9";
        String tsv = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv(rank);
        if (decoyFirst) {
            vcf += "chr" + contig + "\t700011\t.\tA\tG\t.\tPASS\t.\t" + format + "\t" + raw + "\n";
            tsv = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv("1\t" + contig + "\t700011\tA\tG\t" + gene + "\t940011\tAD\t0.99", rank.replaceFirst("^1", "2"));
        }
        vcf = publishedVcf.apply(vcf);
        Files.writeString(archive.resolve("published-input-" + analysisId + ".vcf"), vcf, StandardOpenOption.CREATE_NEW);
        var fixture = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.prepare(properties.getStorageRoot().resolve(analysisId), analysisId,
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38, vcf, tsv, null, false);
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
