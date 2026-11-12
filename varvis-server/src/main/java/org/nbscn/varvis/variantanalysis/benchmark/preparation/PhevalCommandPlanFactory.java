package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import java.util.Arrays;
import java.util.Collections;

public final class PhevalCommandPlanFactory {

    private PhevalCommandPlanFactory() {
    }

    public static PhevalCommandPlan create(PhevalToolDescriptor tool,
                                           PhevalPreparationRequest request) {
        if (tool == null || request == null) throw new IllegalArgumentException("TOOL_AND_REQUEST_REQUIRED");
        tool.validate();
        request.validate();
        return new PhevalCommandPlan(
                tool.getLogicalInstallationPath() + "/pheval",
                Arrays.asList("prepare", "--case-id", request.getCaseId(), "--group-id", request.getGroupId(),
                        "--phenopacket", request.getPhenopacketInputReference(), "--background-vcf",
                        request.getBackgroundVcfReference(), "--region", request.getRegionReference(),
                        "--assembly", request.getReferenceAssembly(), "--output",
                        request.getExpectedOutputVcf(), "--timeout-seconds",
                        String.valueOf(request.getTimeoutSeconds())),
                Collections.singletonList("PYTHONPATH"), request.getOutputDirectoryReference(),
                Collections.singletonList(request.getExpectedOutputVcf()));
    }
}
