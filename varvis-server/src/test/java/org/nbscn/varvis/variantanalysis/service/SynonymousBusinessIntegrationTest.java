package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.SynonymousExonPosition;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import java.time.Instant;

/** Runs the existing HTTP publication/reconstruction/reuse and isolated-failure contracts for synonymous input. */
class SynonymousBusinessIntegrationTest extends SpliceAiBusinessIntegrationTest {
    @Override String expectedPath() { return "SPLICEAI_SYNONYMOUS"; }
    @Override TranscriptConsequence spliceTranscript(TranscriptConsequence t) {
        var proof = new SynonymousExonPosition("GRCh38", "chr11", 2, "T", "A", t.getVersionedTranscriptId(),
                "ENSE000001", 1, 1, 10, "ENSE000001|1|start|10", "116.0", SynonymousExonPosition.PLUGIN_SHA256);
        return new TranscriptConsequence(t.getGeneSymbol(),t.getEntrezGeneId(),t.getTranscriptAccession(),
                t.getVersionedTranscriptId()+":c.30T>A",null,"synonymous_variant",t.getGeneId(),t.getTranscriptId(),
                "protein_coding","LOW","1/2",null,true,true,false,null,null,null,null,null,null,
                "VEP_SYNTHETIC","COMPLETED","116.0",Instant.parse("2026-09-01T00:00:00Z")).withSynonymousExonPosition(proof);
    }
}
