package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Invented clinical policy and family, with valid-shaped IDs. Absolutely no CDH1 scientific adoption. */
public final class DeNovoBatchFixture {
    /** One deliberately failed transport, then the actual offline Python reader; all data is synthetic. */
    public static DeNovoLiteratureSupply failOnceSupply(ObjectMapper mapper, Path python, Path scripts, Path work,
            Path cache, java.util.concurrent.atomic.AtomicInteger calls, java.util.concurrent.CountDownLatch release) throws Exception {
        ExternalProcessRunner runner=(command,cwd,timeout)->{
            if(calls.incrementAndGet()==1) {
                try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("SYNTHETIC_GATE_TIMEOUT"); }
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
                return new ProcessResult(2,"SYNTHETIC deliberately failed source, no process or network",false);
            }
            return new DeNovoSourceProcessRunner().run(command,cwd,timeout);
        };
        return new DeNovoLiteratureSupply(mapper,python,scripts,work,cache,false,runner).asynchronous(DeNovoSourceTasks.shared(),runner);
    }
    public final ObjectMapper mapper = new ObjectMapper();
    public final Path root;
    public final ObjectNode report, phenotype, catalog, selectorPolicies, manifest;
    public static final String PATIENT = "patient-context-e4-b1-synthetic", FAMILY = "family-context-synthetic-denovo-1";
    public DeNovoBatchFixture(Path root) throws Exception {
        this.root = root; Files.createDirectories(root);
        try (var input = getClass().getResourceAsStream("/denovo-automatic/synthetic-local-report.json")) { report = (ObjectNode) mapper.readTree(input); }
        report.put("familyId", FAMILY);
        for (var member : report.path("members")) if ("PROBAND".equals(member.path("role").asText())) ((ObjectNode) member).put("memberId", PATIENT);
        for (var test : report.path("tests")) if ("child".equals(test.path("memberId").asText())) ((ObjectNode) test).put("memberId", PATIENT);
        for (var row : report.path("parentage")) ((ObjectNode) row).put("probandId", PATIENT);
        ((ObjectNode) report.path("clinical")).put("probandId", PATIENT).put("reportedDiseaseId", "MONDO:0007648");
        ((ObjectNode) report.path("target")).put("hgncId", "HGNC:1748").put("contig", "11").put("position", 2).put("reference", "T").put("alternate", "A");
        byte[] source = "SYNTHETIC INVENTED policy: HP:0001250 is consistent in this test only. No clinical or gene knowledge.".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("source.txt"), source);
        phenotype = mapper.createObjectNode().put("schemaVersion", DeNovoApplicabilityPolicy.SCHEMA).put("policyId", "SYNTHETIC-fixture")
                .put("version", "1").put("materialClass", "SYNTHETIC").put("allowAssumedParentage", true).put("expertPolicyStatus", "GENERAL_ALLOWED");
        phenotype.putObject("scope").put("hgncId", "HGNC:1748").put("diseaseId", "MONDO:0007648").put("inheritance", "AD");
        phenotype.putArray("sources").addObject().put("sourceId", "s").put("sha256", sha(source)).put("locator", "synthetic line 1").put("quote", "HP:0001250 is consistent in this test only");
        var condition = phenotype.putArray("conditions").addObject().put("conditionId", "synthetic-condition").put("category", "CONSISTENT");
        condition.putArray("presentAll").add("HP:0001250"); condition.putArray("absentAll"); condition.putArray("sourceIds").add("s");
        catalog = mapper.createObjectNode().put("schemaVersion", "acmg-specification-catalog-v1").put("catalogId", "SYNTHETIC-only")
                .put("catalogVersion", "1").put("searchedAt", "2026-10-17T00:00:00Z").put("searchScope", "SYNTHETIC ONLY; not an actual specification search");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_NOT_REAL_REVIEW");
        catalog.putArray("sources").addObject().put("sourceId", "s").put("url", "https://example.invalid/SYNTHETIC").put("version", "1").put("sha256", sha(source));
        selectorPolicies = mapper.createObjectNode(); var policies = selectorPolicies.putArray("policies"); var entries = catalog.putArray("entries");
        for (String code : List.of("PS2", "PM6")) {
            var policy = policies.addObject().put("policyId", "SYNTHETIC-" + code).put("policyVersion", "1").put("denovoCalculatorVersion", DeNovoEvidenceCalculator.VERSION);
            policy.putArray("criterionIds").add("ACMG_AMP_2015:" + code); policy.set("scope", scope());
            policy.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "SYNTHETIC_NOT_REAL_REVIEW");
            var entry = entries.addObject().put("entryId", code).put("criterionId", "ACMG_AMP_2015:" + code).put("searchStatus", "COMPLETE")
                    .put("disposition", "GENERAL_CLAUSE_ALLOWED").put("baseRuleRelationship", "SYNTHETIC test of generic points");
            entry.set("scope", scope()); entry.putArray("sourceIds").add("s");
            entry.putObject("review").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_NOT_REAL_REVIEW");
            entry.putObject("generalPrerequisites").put("status", "COMPLETE").putArray("sourceIds").add("s");
        }
        manifest = mapper.createObjectNode().put("schemaVersion", "denovo-selected-materials-v1").put("materialClass", "SYNTHETIC");
        manifest.putArray("sources").addObject().put("sourceId", "s").put("path", "source.txt").put("sha256", sha(source));
        save();
    }
    private ObjectNode scope() {
        return mapper.createObjectNode().put("hgncId", "HGNC:1748").put("mondoId", "MONDO:0007648").put("moi", "AUTOSOMAL_DOMINANT")
                .put("assembly", "GRCh38").put("transcriptMode", "TRANSCRIPT_NOT_REQUIRED").put("variantType", "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT");
    }
    public void save() throws Exception {
        var binding = write("phenotype.json", phenotype); manifest.putArray("phenotypePolicies").add(binding);
        for (int i = 0; i < 2; i++) {
            var p = (ObjectNode) selectorPolicies.path("policies").get(i); p.set("denovoPhenotypePolicySha256", binding.path("sha256"));
            ((ObjectNode) catalog.path("entries").get(i)).putObject("policyBinding").setAll(mapper.createObjectNode()
                    .put("policyId", p.path("policyId").asText()).put("policyVersion", "1").put("policySha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(p)));
        }
        manifest.putArray("reports").add(write("report.json", report));
        manifest.set("catalog", write("catalog.json", catalog)); manifest.set("selectorPolicies", write("selector-policies.json", selectorPolicies));
        writeManifest();
    }
    public void writeManifest() throws Exception { Files.write(root.resolve("manifest.json"), mapper.writeValueAsBytes(manifest)); }
    private ObjectNode write(String name, ObjectNode value) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(value); Files.write(root.resolve(name), bytes);
        return mapper.createObjectNode().put("path", name).put("sha256", sha(bytes));
    }
    public DeNovoSelectedBatch load() throws Exception { return load(DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION); }
    public void addSyntheticLiterature() throws Exception {
        var term = phenotype.putArray("literatureTerms").addObject().put("hpoId", "HP:0001250");
        term.putArray("aliases").add("Synthetic trait"); term.putArray("sourceIds").add("s");
        save();
        for (String name : List.of("synthetic-literature.xml", "synthetic-literature-clinvar.xml")) {
            byte[] raw;
            try (var input = getClass().getResourceAsStream("/denovo-automatic/" + name)) {
                if (input == null) throw new IllegalStateException("Missing synthetic fixture " + name);
                raw = input.readAllBytes();
            }
            Files.write(root.resolve(name), raw);
            ((ArrayNode) manifest.path("sources")).addObject().put("sourceId", name).put("path", name).put("sha256", sha(raw));
        }
        var document = manifest.putArray("literature").addObject().put("sourceId", "synthetic-literature.xml").put("pmcid", "PMC999999997");
        document.putArray("alleles").addObject().put("sourceId", "synthetic-literature-clinvar.xml").put("vcv", "VCV999999997");
        writeManifest();
    }
    public DeNovoSelectedBatch load(DeNovoApplicabilityPolicy.Use use) throws Exception {
        return DeNovoSelectedBatch.load(mapper, root.resolve("manifest.json"), sha(Files.readAllBytes(root.resolve("manifest.json"))), use);
    }
    public ObjectNode context() {
        var c = mapper.createObjectNode().put("schemaVersion", "evidence-context-v3").put("canonicalizationProfile", "varvis-json-c14n-v1")
                .put("dataClass", "SYNTHETIC").put("moi", "AUTOSOMAL_DOMINANT");
        c.putObject("scope").put("analysisDomain", "GERMLINE").put("diseaseModel", "MENDELIAN").put("variantScope", "SMALL_SEQUENCE_VARIANT");
        c.putObject("gene").put("hgncId", "HGNC:1748").put("symbol", "CDH1"); c.putObject("disease").put("mondoId", "MONDO:0007648").put("label", "SYNTHETIC");
        c.putObject("patientContext").put("patientContextId", PATIENT).put("familyContextId", FAMILY).put("role", "PROBAND").put("affectedStatus", "AFFECTED").put("sex", "UNKNOWN");
        c.putObject("contextCompleteness").put("status", "COMPLETE").putArray("missingFields");
        var allele = ((ObjectNode) report.path("target")).deepCopy(); allele.remove("hgncId"); var v = c.putObject("variant").put("variantType", "SNV");
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) v.set(field, allele.deepCopy());
        v.putObject("normalization").put("status", "UNCHANGED").put("method", "SYNTHETIC").put("methodVersion", "1").put("referenceSequenceSha256", "a".repeat(64)).put("refValidation", "MATCH");
        v.putObject("assemblyLineage").put("mappingStatus", "NATIVE");
        var adapter = new RuntimeEvidenceV2Adapter(mapper); v.put("alleleId", adapter.canonicalAlleleId(allele, v.path("normalization")));
        c.put("contextId", adapter.canonicalVersionedContextId(c)); adapter.validateVersionedContext(c); return c;
    }
    public static String sha(byte[] bytes) { return FamilyDeNovoJson.sha256(bytes); }
}
