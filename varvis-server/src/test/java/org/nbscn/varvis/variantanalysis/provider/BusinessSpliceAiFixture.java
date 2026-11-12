package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Synthetic raw transport only; production context, provider projection and evaluators remain real Java. */
public final class BusinessSpliceAiFixture {
    public int batches;
    public String failure;
    public SpliceAiBusinessRuntime runtime(ObjectMapper mapper) {
        return new SpliceAiBusinessRuntime(mapper, queries -> {
            batches++;
            if (failure != null) return SpliceAiRawBatchRunner.BatchResult.failed(failure, 1);
            var result = mapper.createObjectNode(); var rows = result.putArray("results");
            for (var query : queries) {
                var key = query.getKey(); var row = rows.addObject().put("queryId", query.getQueryId()).put("status", "HIT").put("exactAlleleMatch", true);
                row.putObject("allele").put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome()).put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
                var annotation = row.putArray("annotations").addObject().put("geneSymbol", query.getGeneSymbol()).put("maximumRawScore", "0.01");
                var ds = annotation.putObject("deltaScores"); var dp = annotation.putObject("deltaPositions");
                for (String field : List.of("AG", "AL", "DG", "DL")) { ds.put("DS_" + field, "0.01"); dp.put("DP_" + field, "0"); }
            }
            return SpliceAiRawBatchRunner.BatchResult.succeeded(result, 0);
        });
    }
}
