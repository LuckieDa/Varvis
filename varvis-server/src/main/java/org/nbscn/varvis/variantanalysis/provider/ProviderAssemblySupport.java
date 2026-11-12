package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProviderAssemblySupport {

    private ProviderAssemblySupport() {
    }

    public static GenomeAssembly queryAssembly(List<VariantInterpretation> variants, AnalysisContext context) {
        if (context != null && context.getGenomeAssembly() != null) {
            return context.getGenomeAssembly();
        }
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        for (VariantInterpretation variant : variants) {
            if (variant == null || variant.getObservation() == null
                    || variant.getObservation().getKey() == null) {
                continue;
            }
            try {
                return GenomeAssembly.require(variant.getObservation().getKey().getGenomeAssembly());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    public static boolean supportsOnlyGrch38(List<VariantInterpretation> variants, AnalysisContext context) {
        GenomeAssembly assembly = queryAssembly(variants, context);
        return assembly == null || assembly == GenomeAssembly.GRCh38;
    }

    public static Map<NormalizedVariantKey, List<EvidenceItem>> unsupportedForGrch38Only(
            List<VariantInterpretation> variants,
            AnalysisContext context,
            String source,
            String version,
            String evidenceType,
            String resourceName) {
        GenomeAssembly assembly = queryAssembly(variants, context);
        Map<NormalizedVariantKey, List<EvidenceItem>> result =
                new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        if (variants == null || variants.isEmpty()) {
            return result;
        }
        for (VariantInterpretation variant : variants) {
            if (variant == null || variant.getObservation() == null
                    || variant.getObservation().getKey() == null) {
                continue;
            }
            result.put(
                    variant.getObservation().getKey(),
                    Collections.singletonList(assemblyUnsupported(
                            source,
                            version,
                            evidenceType,
                            assembly,
                            "GRCh38",
                            resourceName
                    ))
            );
        }
        return result;
    }

    public static EvidenceItem assemblyUnsupported(String source,
                                                   String version,
                                                   String evidenceType,
                                                   GenomeAssembly requestedAssembly,
                                                   String supportedAssembly,
                                                   String resourceName) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("status", "ASSEMBLY_UNSUPPORTED");
        details.put("requestedAssembly", requestedAssembly == null ? "UNKNOWN" : requestedAssembly.name());
        details.put("supportedAssemblies", Collections.singletonList(supportedAssembly));
        details.put("configuredResourceAssembly", supportedAssembly);
        details.put("resource", resourceName);
        details.put("providerInvoked", Boolean.FALSE);
        details.put("resourceExecutionStatus", "NOT_RUN");
        details.put("resourceExecutionReasonCode", "ASSEMBLY_UNSUPPORTED");
        details.put("resourceAvailabilityStatus", "AVAILABLE");
        details.put("queryOutcomeStatus", "NOT_QUERIED");
        details.put("resourceApplicabilityStatus", "NOT_APPLICABLE");
        details.put("resourceApplicabilityReasonCode", "ASSEMBLY_UNSUPPORTED");
        details.put("interpretation",
                "Provider resource assembly does not match the query assembly; this is not a no-record result.");
        return new EvidenceItem(
                source,
                version,
                evidenceType,
                "ASSEMBLY_UNSUPPORTED",
                null,
                "Assembly unsupported",
                resourceName + " supports " + supportedAssembly
                        + " coordinates only; query assembly was "
                        + (requestedAssembly == null ? "UNKNOWN" : requestedAssembly.name()),
                details
        );
    }
}
