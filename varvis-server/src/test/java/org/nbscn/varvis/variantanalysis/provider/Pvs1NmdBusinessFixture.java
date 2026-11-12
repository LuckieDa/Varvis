package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;

/** Entirely fictional TEST_ONLY backgrounds; reuses established toy sequence constructors, never evaluated output. */
public final class Pvs1NmdBusinessFixture {
    public final List<ObjectNode> models = new ArrayList<>(), policies = new ArrayList<>(), annotations = new ArrayList<>();
    public final List<NormalizedVariantKey> alleles = new ArrayList<>();
    public final List<ObjectNode> expected = new ArrayList<>();
    public final List<ObjectNode> spliceOutcomes = new ArrayList<>();
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    public Pvs1NmdBusinessFixture(ObjectMapper mapper, boolean frameshift) throws Exception {
        this(mapper,frameshift,false);
    }
    public Pvs1NmdBusinessFixture(ObjectMapper mapper, boolean frameshift, boolean escape) throws Exception {
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper);
        var old = new TruncatingNmdPvs1RuntimeTest();
        for (int i = 0; i < 2; i++) {
            int strand = i == 0 ? 1 : -1;
            var input = frameshift ? old.frameshift(i == 0, strand, escape ? 112 : 120)
                    : old.nonsense(i + 1, strand, escape ? 499 : i == 0 ? 31 : 199);
            String symbol = "TEST_NMD_" + (i == 0 ? "PLUS" : "MINUS");
            String tx = "NM_99900" + (i + 1) + ".1", protein = "NP_99900" + (i + 1) + ".1";
            String oldTx = input.model.path("transcript").asText(), oldProtein = input.model.path("protein").asText();
            var model = input.model.deepCopy().put("modelId", "TEST_ONLY_NMD_MODEL_" + i).put("modelVersion", "1")
                    .put("hgncId", "HGNC:99900" + (i + 1)).put("geneSymbol", symbol).put("contig", i == 0 ? "11" : "13")
                    .put("transcript", tx).put("refseqTranscript", tx).put("protein", protein);
            models.add(model);
            var policy = input.policy.deepCopy().put("policyId", "TEST_ONLY_NMD_POLICY_" + i);
            policy.withObject("scope").put("hgncId", model.path("hgncId").asText()).put("mondoId", "MONDO:099900" + (i + 1));
            policy.withObject("scope").putArray("transcripts").addObject().put("accession", tx.substring(0, tx.lastIndexOf('.'))).put("version", 1);
            policy.withObject("nmdScope").put("minimumDistanceToLastJunction", i == 0 ? 51 : 55);
            if(escape) {
                var scope=Pvs1NmdEscapeTest.scope(policy,model.path("exons").size());
                Pvs1NmdEscapeTest.critical(scope,160,190,1,"STRONG");
            }
            policies.add(policy);
            ObjectNode annotation = mapper.valueToTree(input.candidate.getTranscriptAnnotations().get(0));
            annotation.retain("geneSymbol", "transcriptAccession", "hgvsCoding", "hgvsProtein", "consequence", "exon", "proteinPosition",
                    "aminoAcids", "codons", "source", "status", "version", "timestamp", "maneSelect");
            annotation.put("geneSymbol", symbol).put("transcriptAccession", tx)
                    .put("hgvsCoding", annotation.path("hgvsCoding").asText().replace(oldTx, tx))
                    .put("hgvsProtein", annotation.path("hgvsProtein").asText().replace(oldProtein, protein));
            annotations.add(annotation);
            var key = input.candidate.getObservation().getKey();
            alleles.add(new NormalizedVariantKey("GRCh38", i == 0 ? "11" : "13", key.getPosition(), key.getReference(), key.getAlternate()));
            expected.add(mapper.createObjectNode().put("dataClass", "TEST_ONLY").put("kind", frameshift ? i == 0 ? "INSERTION" : "DELETION" : "NONSENSE")
                    .put("transcript", tx).put("ptcStart", frameshift ? i == 0 ? 64 : 61 : i == 0 ? 31 : 199)
                    .put("ptcEnd", frameshift ? i == 0 ? 66 : 63 : i == 0 ? 33 : 201)
                    .put("distanceToLastJunction", frameshift ? i == 0 ? 55 : 56 : i == 0 ? 417 : 249)
                    .put("lastJunction", frameshift ? i == 0 ? 121 : 119 : 450)
                    .put("strength", "VERY_STRONG").put("basis", "Fixed toy CDS and exon coordinates, not runtime output"));
            if(escape) expected.get(i).put("ptcStart",frameshift ? i==0 ? 64 : 61 : 499)
                    .put("ptcEnd",frameshift ? i==0 ? 66 : 63 : 501)
                    .put("lastJunction",frameshift ? i==0 ? 113 : 111 : 450)
                    .put("distanceToLastJunction",frameshift ? i==0 ? 47 : 48 : -51).put("strength","STRONG");
        }
    }
    public String canonical(JsonNode value) { return adapter.canonicalSha256(value); }
    public TranscriptConsequence annotation(int i) throws Exception { return mapper.treeToValue(annotations.get(i), TranscriptConsequence.class); }
    public ObjectNode document() {
        ObjectNode d = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-pvs1-v6")
                .put("bundleId", "TEST_ONLY_NMD_BUSINESS").put("bundleVersion", "1");
        d.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "TEST_ONLY_ENGINEERING_NOT_SCIENTIFIC_APPROVAL");
        d.putObject("activation").put("automaticTrigger", false).put("classificationEnabled", false);
        var materials = d.putObject("materials");
        materials.set("pvs1ResourceProfile", new TruncatingNmdPvs1RuntimeTest().resourceProfile());
        if (!spliceOutcomes.isEmpty()) materials.set("pvs1SpliceOutcomes", mapper.valueToTree(spliceOutcomes));
        var map = materials.putObject("transcriptModels");
        var ps = d.putArray("policies");
        ObjectNode catalog = d.putObject("catalog").put("schemaVersion", "acmg-specification-catalog-v1")
                .put("catalogId", "TEST_ONLY_NMD_CATALOG").put("catalogVersion", "1").put("searchedAt", "2026-09-27T00:00:00Z")
                .put("searchScope", "TEST_ONLY fictional gene disease mechanism and transcripts; no clinical applicability");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY");
        catalog.set("sources", policies.get(0).path("scientificSources").deepCopy());
        var entries = catalog.putArray("entries");
        for (int i = 0; i < 2; i++) {
            var m = models.get(i);var p = policies.get(i);
            p.putObject("transcriptModelBinding").put("modelId", m.path("modelId").asText()).put("modelVersion", m.path("modelVersion").asText())
                    .put("canonicalSha256", canonical(m));
            map.set(canonical(m), m.deepCopy()); ps.add(p.deepCopy());
            ObjectNode e = entries.addObject().put("entryId", "TEST_ONLY_ENTRY_" + i).put("criterionId", TruncatingNmdPvs1Runtime.CRITERION)
                    .put("searchStatus", "COMPLETE").put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "TEST_ONLY engineering clause");
            e.set("scope", p.path("scope").deepCopy());e.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY");
            e.putArray("sourceIds").add("synthetic-source");
            e.putObject("policyBinding").put("policyId", p.path("policyId").asText()).put("policyVersion", p.path("policyVersion").asText()).put("policySha256", canonical(p));
        }
        d.put("catalogSha256", canonical(catalog));return d;
    }
}
