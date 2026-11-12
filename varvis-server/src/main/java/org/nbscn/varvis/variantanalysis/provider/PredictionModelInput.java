package org.nbscn.varvis.variantanalysis.provider;

import java.math.BigDecimal;

/** A validated score reading, without any disease criterion conclusion. */
record PredictionModelInput(BigDecimal score, String issue) { }
