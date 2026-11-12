package org.nbscn.varvis.variantanalysis.service.llmshadow;

/**
 * Extension boundary for a future reviewed local provider; no implementation is shipped here.
 *
 * <p>{@link #externalTransmission()} is a provider attestation, not a Java sandbox. A future
 * provider must also be reviewed and run under deployment-level network controls before it is
 * trusted with this boundary.</p>
 */
public interface LlmShadowProvider {

    String providerName();

    /** Must be false. This module refuses any provider that can transmit externally. */
    boolean externalTransmission();

    String preview(LlmShadowInput input);
}
