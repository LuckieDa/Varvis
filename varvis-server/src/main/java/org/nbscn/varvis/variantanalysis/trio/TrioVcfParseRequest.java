package org.nbscn.varvis.variantanalysis.trio;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TrioVcfParseRequest {

    public static final int DEFAULT_MIN_DEPTH_FOR_CONFIRMED_REF = 10;
    public static final int DEFAULT_MIN_GQ_FOR_CONFIRMED_REF = 20;

    private final TrioInputMode inputMode;
    private final String assembly;
    private final Path jointVcfPath;
    private final Path probandVcfPath;
    private final Path fatherVcfPath;
    private final Path motherVcfPath;
    private final TrioVcfSampleMapping sampleMapping;
    private final List<TrioMember> members;
    private final int minDepthForConfirmedRef;
    private final int minGqForConfirmedRef;
    private final Map<String, String> targetGeneSymbols;

    public TrioVcfParseRequest(TrioInputMode inputMode,
                               String assembly,
                               Path jointVcfPath,
                               Path probandVcfPath,
                               Path fatherVcfPath,
                               Path motherVcfPath,
                               TrioVcfSampleMapping sampleMapping,
                               List<TrioMember> members,
                               int minDepthForConfirmedRef,
                               int minGqForConfirmedRef) {
        this(inputMode, assembly, jointVcfPath, probandVcfPath, fatherVcfPath, motherVcfPath,
                sampleMapping, members, minDepthForConfirmedRef, minGqForConfirmedRef, null);
    }

    private TrioVcfParseRequest(TrioInputMode inputMode,
                                String assembly,
                                Path jointVcfPath,
                                Path probandVcfPath,
                                Path fatherVcfPath,
                                Path motherVcfPath,
                                TrioVcfSampleMapping sampleMapping,
                                List<TrioMember> members,
                                int minDepthForConfirmedRef,
                                int minGqForConfirmedRef,
                                Map<String, String> targetGeneSymbols) {
        this.inputMode = inputMode;
        this.assembly = assembly;
        this.jointVcfPath = jointVcfPath;
        this.probandVcfPath = probandVcfPath;
        this.fatherVcfPath = fatherVcfPath;
        this.motherVcfPath = motherVcfPath;
        this.sampleMapping = sampleMapping;
        this.members = immutableMembers(members);
        this.minDepthForConfirmedRef = minDepthForConfirmedRef > 0
                ? minDepthForConfirmedRef
                : DEFAULT_MIN_DEPTH_FOR_CONFIRMED_REF;
        this.minGqForConfirmedRef = minGqForConfirmedRef > 0
                ? minGqForConfirmedRef
                : DEFAULT_MIN_GQ_FOR_CONFIRMED_REF;
        this.targetGeneSymbols = immutableGeneSymbols(targetGeneSymbols);
    }

    public static TrioVcfParseRequest joint(String assembly,
                                            Path jointVcfPath,
                                            TrioVcfSampleMapping sampleMapping,
                                            List<TrioMember> members) {
        return new TrioVcfParseRequest(
                TrioInputMode.JOINT_VCF,
                assembly,
                jointVcfPath,
                null,
                null,
                null,
                sampleMapping,
                members,
                DEFAULT_MIN_DEPTH_FOR_CONFIRMED_REF,
                DEFAULT_MIN_GQ_FOR_CONFIRMED_REF);
    }

    public static TrioVcfParseRequest threeSingleSample(String assembly,
                                                        Path probandVcfPath,
                                                        Path fatherVcfPath,
                                                        Path motherVcfPath,
                                                        TrioVcfSampleMapping sampleMapping,
                                                        List<TrioMember> members) {
        return new TrioVcfParseRequest(
                TrioInputMode.THREE_SINGLE_SAMPLE_VCFS,
                assembly,
                null,
                probandVcfPath,
                fatherVcfPath,
                motherVcfPath,
                sampleMapping,
                members,
                DEFAULT_MIN_DEPTH_FOR_CONFIRMED_REF,
                DEFAULT_MIN_GQ_FOR_CONFIRMED_REF);
    }

    private List<TrioMember> immutableMembers(List<TrioMember> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<TrioMember>(values));
    }

    private Map<String, String> immutableGeneSymbols(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }

    public TrioVcfParseRequest withTargetVariantIds(Set<String> values) {
        Map<String, String> targets = new LinkedHashMap<String, String>();
        if (values != null) {
            for (String value : values) {
                targets.put(value, null);
            }
        }
        return withTargetVariants(targets);
    }

    public TrioVcfParseRequest withTargetVariants(Map<String, String> values) {
        return new TrioVcfParseRequest(
                inputMode,
                assembly,
                jointVcfPath,
                probandVcfPath,
                fatherVcfPath,
                motherVcfPath,
                sampleMapping,
                members,
                minDepthForConfirmedRef,
                minGqForConfirmedRef,
                values
        );
    }

    public boolean accepts(TrioVcfVariantKey key) {
        return targetGeneSymbols.isEmpty()
                || (key != null && targetGeneSymbols.containsKey(key.stableVariantId()));
    }

    public Set<String> getTargetVariantIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(targetGeneSymbols.keySet()));
    }

    public String geneSymbolFor(TrioVcfVariantKey key, String fallback) {
        if (key == null) {
            return fallback;
        }
        String value = targetGeneSymbols.get(key.stableVariantId());
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    public TrioInputMode getInputMode() {
        return inputMode;
    }

    public String getAssembly() {
        return assembly;
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

    public int getMinDepthForConfirmedRef() {
        return minDepthForConfirmedRef;
    }

    public int getMinGqForConfirmedRef() {
        return minGqForConfirmedRef;
    }
}
