package org.nbscn.varvis.variantanalysis.trio;

public class TrioVcfSampleMapping {

    private final String probandSampleId;
    private final String fatherSampleId;
    private final String motherSampleId;

    public TrioVcfSampleMapping(String probandSampleId, String fatherSampleId, String motherSampleId) {
        this.probandSampleId = probandSampleId;
        this.fatherSampleId = fatherSampleId;
        this.motherSampleId = motherSampleId;
    }

    public String getProbandSampleId() {
        return probandSampleId;
    }

    public String getFatherSampleId() {
        return fatherSampleId;
    }

    public String getMotherSampleId() {
        return motherSampleId;
    }

    public String sampleId(TrioRole role) {
        if (role == TrioRole.PROBAND) {
            return probandSampleId;
        }
        if (role == TrioRole.FATHER) {
            return fatherSampleId;
        }
        if (role == TrioRole.MOTHER) {
            return motherSampleId;
        }
        return null;
    }
}
