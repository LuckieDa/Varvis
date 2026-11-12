package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

import java.util.List;

public interface TranscriptAnnotationCache {

    List<TranscriptConsequence> get(TranscriptAnnotationCacheKey key);

    void put(TranscriptAnnotationCacheKey key, List<TranscriptConsequence> annotations);
}
