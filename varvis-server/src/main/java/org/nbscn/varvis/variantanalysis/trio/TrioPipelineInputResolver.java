package org.nbscn.varvis.variantanalysis.trio;

import org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;

@Component
public class TrioPipelineInputResolver {

    public TrioPipelineInput resolve(TrioAnalysisInputRequest request,
                                     Path primaryVcfPath,
                                     Path fatherVcfPath,
                                     Path motherVcfPath) {
        if (request == null || request.isDisabled()) {
            return TrioPipelineInput.absent();
        }
        TrioVcfSampleMapping sampleMapping = new TrioVcfSampleMapping(
                request.getProbandSampleId(),
                request.getFatherSampleId(),
                request.getMotherSampleId()
        );
        TrioPipelineInput input;
        if (TrioAnalysisInputRequest.MODE_JOINT_VCF.equals(request.getInputMode())) {
            input = TrioPipelineInput.jointVcf(primaryVcfPath, sampleMapping, members(request));
        } else if (TrioAnalysisInputRequest.MODE_THREE_SINGLE_SAMPLE_VCFS.equals(request.getInputMode())) {
            input = TrioPipelineInput.threeSingleSampleVcfs(
                    primaryVcfPath,
                    fatherVcfPath,
                    motherVcfPath,
                    sampleMapping,
                    members(request)
            );
        } else {
            return TrioPipelineInput.absent();
        }
        return input.withConfirmedReferenceThresholds(
                request.getMinDepthForConfirmedRef(),
                request.getMinGqForConfirmedRef()
        );
    }

    private java.util.List<TrioMember> members(TrioAnalysisInputRequest request) {
        return Arrays.asList(
                new TrioMember(TrioRole.PROBAND, request.getProbandSampleId(),
                        TrioSex.fromRequest(request.getProbandSex()), Boolean.TRUE.equals(request.getProbandAffected())),
                new TrioMember(TrioRole.FATHER, request.getFatherSampleId(),
                        TrioSex.fromRequest(request.getFatherSex()), Boolean.TRUE.equals(request.getFatherAffected())),
                new TrioMember(TrioRole.MOTHER, request.getMotherSampleId(),
                        TrioSex.fromRequest(request.getMotherSex()), Boolean.TRUE.equals(request.getMotherAffected()))
        );
    }
}
