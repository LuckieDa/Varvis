package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VepParseResult {

    private final Map<String, List<TranscriptConsequence>> annotationsByVariantId;
    private final List<String> warnings;

    public VepParseResult(Map<String, List<TranscriptConsequence>> annotationsByVariantId,
                          List<String> warnings) {
        this.annotationsByVariantId = immutableMapCopy(annotationsByVariantId);
        this.warnings = warnings == null || warnings.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public Map<String, List<TranscriptConsequence>> getAnnotationsByVariantId() {
        return annotationsByVariantId;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    private Map<String, List<TranscriptConsequence>> immutableMapCopy(
            Map<String, List<TranscriptConsequence>> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<TranscriptConsequence>> copy =
                new LinkedHashMap<String, List<TranscriptConsequence>>();
        for (Map.Entry<String, List<TranscriptConsequence>> entry : source.entrySet()) {
            List<TranscriptConsequence> values = entry.getValue();
            copy.put(
                    entry.getKey(),
                    values == null || values.isEmpty()
                            ? Collections.emptyList()
                            : Collections.unmodifiableList(new ArrayList<TranscriptConsequence>(values))
            );
        }
        return Collections.unmodifiableMap(copy);
    }
}
