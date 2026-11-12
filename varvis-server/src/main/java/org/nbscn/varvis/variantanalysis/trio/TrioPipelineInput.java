package org.nbscn.varvis.variantanalysis.trio;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TrioPipelineInput {

    private static final TrioPipelineInput ABSENT =
            new TrioPipelineInput(false, null, null, null, null, null, null, null, null, null);

    private final boolean present;
    private final TrioInputMode inputMode;
    private final Path jointVcfPath;
    private final Path probandVcfPath;
    private final Path fatherVcfPath;
    private final Path motherVcfPath;
    private final TrioVcfSampleMapping sampleMapping;
    private final List<TrioMember> members;
    private final Integer minDepthForConfirmedRef;
    private final Integer minGqForConfirmedRef;

    private TrioPipelineInput(boolean present,
                              TrioInputMode inputMode,
                              Path jointVcfPath,
                              Path probandVcfPath,
                              Path fatherVcfPath,
                              Path motherVcfPath,
                              TrioVcfSampleMapping sampleMapping,
                              List<TrioMember> members,
                              Integer minDepthForConfirmedRef,
                              Integer minGqForConfirmedRef) {
        this.present = present;
        this.inputMode = inputMode;
        this.jointVcfPath = jointVcfPath;
        this.probandVcfPath = probandVcfPath;
        this.fatherVcfPath = fatherVcfPath;
        this.motherVcfPath = motherVcfPath;
        this.sampleMapping = sampleMapping;
        this.members = immutableMembers(members);
        this.minDepthForConfirmedRef = positiveOrNull(minDepthForConfirmedRef);
        this.minGqForConfirmedRef = positiveOrNull(minGqForConfirmedRef);
    }

    public static TrioPipelineInput absent() {
        return ABSENT;
    }

    public static TrioPipelineInput jointVcf(Path jointVcfPath,
                                             TrioVcfSampleMapping sampleMapping,
                                             List<TrioMember> members) {
        return new TrioPipelineInput(
                true,
                TrioInputMode.JOINT_VCF,
                jointVcfPath,
                null,
                null,
                null,
                sampleMapping,
                members,
                null,
                null);
    }

    public static TrioPipelineInput threeSingleSampleVcfs(Path probandVcfPath,
                                                          Path fatherVcfPath,
                                                          Path motherVcfPath,
                                                          TrioVcfSampleMapping sampleMapping,
                                                          List<TrioMember> members) {
        return new TrioPipelineInput(
                true,
                TrioInputMode.THREE_SINGLE_SAMPLE_VCFS,
                null,
                probandVcfPath,
                fatherVcfPath,
                motherVcfPath,
                sampleMapping,
                members,
                null,
                null);
    }

    public TrioPipelineInput withConfirmedReferenceThresholds(Integer minDepthForConfirmedRef,
                                                              Integer minGqForConfirmedRef) {
        if (!present) {
            return this;
        }
        return new TrioPipelineInput(
                present,
                inputMode,
                jointVcfPath,
                probandVcfPath,
                fatherVcfPath,
                motherVcfPath,
                sampleMapping,
                members,
                minDepthForConfirmedRef,
                minGqForConfirmedRef
        );
    }

    public boolean isPresent() {
        return present;
    }

    public TrioInputMode getInputMode() {
        return inputMode;
    }

    public Path getJointVcfPath() {
        return jointVcfPath;
    }

    public Path getProbandVcfPath() {
        return probandVcfPath;
    }

    public Path getFatherVcfPath() {
        return fatherVcfPath;
    }

    public Path getMotherVcfPath() {
        return motherVcfPath;
    }

    public TrioVcfSampleMapping getSampleMapping() {
        return sampleMapping;
    }

    public List<TrioMember> getMembers() {
        return members;
    }

    public Integer getMinDepthForConfirmedRef() {
        return minDepthForConfirmedRef;
    }

    public Integer getMinGqForConfirmedRef() {
        return minGqForConfirmedRef;
    }

    public TrioVcfParseRequest toParseRequest(String assembly,
                                              int minDepthForConfirmedRef,
                                              int minGqForConfirmedRef) {
        return new TrioVcfParseRequest(
                inputMode,
                assembly,
                jointVcfPath,
                probandVcfPath,
                fatherVcfPath,
                motherVcfPath,
                sampleMapping,
                members,
                this.minDepthForConfirmedRef == null
                        ? minDepthForConfirmedRef
                        : this.minDepthForConfirmedRef,
                this.minGqForConfirmedRef == null
                        ? minGqForConfirmedRef
                        : this.minGqForConfirmedRef);
    }

    private List<TrioMember> immutableMembers(List<TrioMember> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<TrioMember>(values));
    }

    private Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }
}
