package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;

/** Actual pinned SYNTHETIC source replay shared by criterion consumers, never a source stub. */
final class FullRnaCriterionFixture {
    static final String REFERENCE = "CCATGAAACCCGGGTTTGAATAAGGGTGA";
    final Pvs1FullRnaSourceProducerTest.Data data;
    final CodingTranscriptRnaFixture f;
    final ObjectNode original;
    FullRnaCriterionFixture(Path path, int strand) throws Exception {
        data = Pvs1FullRnaSourceProducerTest.createNeutralSourceFixture(path, strand);f = data.f();original = data.original();
        intronicAndScope();
        original.withObject("events").putArray("rnaEdits");
    }
    void intronicAndScope() throws Exception {
        int strand=f.model.path("strand").asInt();
        int genomic = strand == 1 ? f.model.at("/exons/0/genomicEnd").asInt() + 1 : f.model.at("/exons/0/genomicStart").asInt() - 1;
        f.withGenomicReference(genomic, "A").alleleAtGenomic(genomic, "A", "G");
        var scope = original.withObject("scope");scope.put("modelSha256", f.token.modelSha256()).put("referenceRnaSha256", f.token.referenceRnaSha256());
        scope.set("allele", f.context.at("/variant/evidenceQueryAllele").deepCopy());
        original.withObject("physicalAllele").put("alleleSha256", f.adapter.canonicalSha256(f.context.at("/variant/evidenceQueryAllele")));
        original.withObject("events").putArray("rnaEdits");
    }
    void edit(String id, int lo, int hi, String inserted) {
        original.withObject("events").withArray("rnaEdits").addObject().put("eventId", id).put("start0", lo).put("end0", hi)
                .put("expectedReference", f.token.referenceRnaSequence().substring(lo, hi)).put("insertedSequence", inserted);
    }
    void product(String sequence, int...junctions) {
        original.withObject("sequence").put("completeAlteredRnaWitness", sequence)
                .put("alteredRnaSha256", CodingTranscriptMaterialSupply.sha(sequence));
        var js = original.withObject("topology").putArray("junctions0");for (int j : junctions) js.add(j);
    }
    Pvs1SourceQualification.SourceBoundFullRna source() throws Exception {
        Pvs1FullRnaSourceProducerTest.repinNeutralSourceFixture(data);
        var resolution = Pvs1FullRnaSourceProducerTest.replayNeutralSourceFixture(data);var source = resolution.qualified();
        if (!resolution.ready() || source == null) throw new AssertionError(resolution.snapshot().toPrettyString());return source;
    }
    void scenario(String scenario) {
        switch (scenario) {
            case "deletion" -> { edit("delete",8,11,"");product("CCATGAAAGGGTTTGAATAAGGGTGA",8,14,22); }
            case "insertion" -> { edit("insert",11,11,"CCC");product("CCATGAAACCCCCCGGGTTTGAATAAGGGTGA",8,20,28); }
            case "utr" -> { edit("utr-insert",1,1,"A");product("CACATGAAACCCGGGTTTGAATAAGGGTGA",9,18,26); }
            case "stop-loss" -> { edit("stop-del",20,23,"");product("CCATGAAACCCGGGTTTGAAGGGTGA",8,17,22); }
            case "cross-utr" -> { edit("cross-del",21,24,"");product("CCATGAAACCCGGGTTTGAATGGTGA",8,17,22); }
            case "start-loss" -> { edit("start-del",2,5,"");product("CCAAACCCGGGTTTGAATAAGGGTGA",5,14,22); }
            case "no-stop" -> { edit("stop-and-tail",20,29,"");product("CCATGAAACCCGGGTTTGAA",8,17); }
            case "ptc" -> { edit("stop-insert",11,11,"TAA");product("CCATGAAACCCTAAGGGTTTGAATAAGGGTGA",8,20,28); }
            case "frame-utr-cancellation" -> { edit("coding-insert",5,5,"A");edit("utr-delete",23,24,"");product("CCATGAAAACCCGGGTTTGAATAAGGTGA",9,18,25); }
            default -> throw new AssertionError(scenario);
        }
    }
}
