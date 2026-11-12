package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

/** Fixed toy inputs with expectations chosen before runtime execution. No scientific approval. */
public final class Pvs1SpliceRound3Fixture {
    private Pvs1SpliceRound3Fixture() { }
    public static ObjectNode configure(Pvs1NmdBusinessFixture f, int i, String mode) {
        ObjectNode fact = Pvs1SpliceConsequenceTest.configure(f, i, false, false);
        if (mode.equals("INFRAME") || mode.equals("NEGATIVE")) {
            fact.withObject("event").put("type", "EXON_SHORTENING").put("deleteEndCds", 24);
            fact.withObject("review").put("maximumStrength", "MODERATE");
            var scope = f.policies.get(i).withObject("spliceScope").putObject("inFrameScope")
                    .put("status", "REVIEWED_FOR_PVS1_SPLICE_INFRAME").put("reviewId", "TEST_ONLY_LOCAL_REGION")
                    .put("basis", "TEST_ONLY exact residues 5 through 8, not clinical evidence");
            scope.putArray("sourceIds").add("synthetic-source");
            var exons = scope.putArray("exonAssessments");
            for (int ordinal = 1; ordinal <= 4; ordinal++) exons.addObject().put("ordinal", ordinal)
                    .put("biologicalRelevance", mode.equals("NEGATIVE") && ordinal == 2 ? "ABSENT" : "PRESENT")
                    .put("populationLof", "NOT_FREQUENT").put("basis", "TEST_ONLY source observation")
                    .putArray("sourceIds").add("synthetic-source");
            scope.putArray("rules").addObject().put("ruleId", "TEST_ONLY_RESIDUES_5_8").put("predicate", "CRITICAL_REGION")
                    .put("start", 5).put("end", 8).put("minimumAffectedResidues", 1).put("strength", "STRONG")
                    .put("basis", "TEST_ONLY bounded source rule").putArray("sourceIds").add("synthetic-source");
            fact.putObject("inFrameReview").put("status", "REVIEWED_FOR_PVS1_SPLICE_INFRAME").put("reviewId", "TEST_ONLY_OUTCOME_REGION")
                    .put("basis", "TEST_ONLY allele-local assignment").putArray("sourceIds").add("synthetic-source");
            if (mode.equals("NEGATIVE")) {
                var nmd=f.policies.get(i).withObject("nmdScope");
                nmd.putArray("relevantExonOrdinals").add(1).add(3).add(4);
                nmd.putArray("engineeringSupportedExonOrdinals").add(1).add(3);
            }
            f.expected.get(i).put("status", mode.equals("NEGATIVE") ? "NOT_MET" : "MET")
                    .put("strength", mode.equals("NEGATIVE") ? "NONE" : "MODERATE");
            f.expected.get(i).remove(List.of("ptcStart", "ptcEnd", "distanceToLastJunction"));
        } else if (mode.equals("TOPOLOGY")) {
            var model=f.models.get(i);var exons=model.putArray("exons");int start=1;int[] ends={12,25,62,450,600};
            for(int j=0;j<ends.length;j++) {
                int genomic=i==0 ? 2000+j*1000 : 20000-j*1000;
                exons.addObject().put("ordinal",j+1).put("cdsStart",start).put("cdsEnd",ends[j])
                        .put("genomicStart",genomic).put("genomicEnd",genomic+ends[j]-start);start=ends[j]+1;
            }
            var nmd=f.policies.get(i).withObject("nmdScope");
            nmd.putArray("relevantExonOrdinals").add(1).add(2).add(3).add(4).add(5);
            nmd.putArray("engineeringSupportedExonOrdinals").add(1).add(2).add(3).add(4);
            f.expected.get(i).put("status","MET");
        } else if(mode.equals("COMPOSED")) {
            var model=f.models.get(i);var exon=model.path("exons").path(2);
            int position=i==0 ? exon.path("genomicStart").asInt()+2 : exon.path("genomicEnd").asInt()-2;
            String ref=i==0?"C":"G", alt=i==0?"A":"T";
            var old=f.alleles.get(i);var allele=new NormalizedVariantKey("GRCh38",old.getChromosome(),position,ref,alt);
            f.alleles.set(i,allele);
            fact.withObject("allele").put("position",position).put("reference",ref).put("alternate",alt);
            fact.withObject("referenceWindow").put("start",position).put("sequence",ref);
            f.annotations.get(i).put("hgvsCoding",model.path("transcript").asText()+":c.28C>A")
                    .put("consequence","splice_region_variant&missense_variant");
            var composition=fact.putObject("codingEditComposition").put("schemaVersion","pvs1-splice-coding-composition-v1")
                    .put("status","REVIEWED_FOR_SAME_ALLELE_COMPOSITION").put("reviewId","TEST_ONLY_SAME_ALLELE")
                    .put("basis","TEST_ONLY same allele coding substitution retained after RNA deletion")
                    .put("alleleGroupSha256",f.canonical(fact.path("allele")))
                    .put("referenceCdsSha256",CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()))
                    .put("spliceEventSha256",f.canonical(fact.path("event"))).put("sequenceScope","REFERENCE_CDS_DELETION")
                    .put("codingHgvsc",model.path("transcript").asText()+":c.28C>A");
            composition.putArray("sourceIds").add("synthetic-source");
            f.expected.get(i).put("status","MET");
        } else throw new IllegalArgumentException("Unknown test mode");
        fact.put("transcriptModelSha256",f.canonical(f.models.get(i)));
        return fact;
    }
}
