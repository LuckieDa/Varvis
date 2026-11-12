package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.IOException;
import java.util.Map;

public interface AlphaGenomeClient {

    Map<String, Object> score(NormalizedVariantKey key) throws IOException;
}

