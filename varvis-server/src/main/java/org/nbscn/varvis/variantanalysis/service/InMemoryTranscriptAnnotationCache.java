package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryTranscriptAnnotationCache implements TranscriptAnnotationCache {

    private final ConcurrentMap<TranscriptAnnotationCacheKey, List<TranscriptConsequence>> cache =
            new ConcurrentHashMap<TranscriptAnnotationCacheKey, List<TranscriptConsequence>>();

    @Override
    public List<TranscriptConsequence> get(TranscriptAnnotationCacheKey key) {
        List<TranscriptConsequence> value = cache.get(key);
        return value == null ? null : value;
    }

    @Override
    public void put(TranscriptAnnotationCacheKey key, List<TranscriptConsequence> annotations) {
        if (key == null || annotations == null) {
            return;
        }
        cache.put(
                key,
                Collections.unmodifiableList(new ArrayList<TranscriptConsequence>(annotations))
        );
    }
}
