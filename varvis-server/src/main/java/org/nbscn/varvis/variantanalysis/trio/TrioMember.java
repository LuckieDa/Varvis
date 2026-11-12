package org.nbscn.varvis.variantanalysis.trio;

public class TrioMember {

    private final TrioRole role;
    private final String sampleId;
    private final TrioSex sex;
    private final boolean affected;

    public TrioMember(TrioRole role, String sampleId, TrioSex sex, boolean affected) {
        if (role == null) {
            throw new IllegalArgumentException("Trio role is required.");
        }
        this.role = role;
        this.sampleId = sampleId;
        this.sex = sex == null ? TrioSex.UNKNOWN : sex;
        this.affected = affected;
    }

    public TrioRole getRole() {
        return role;
    }

    public String getSampleId() {
        return sampleId;
    }

    public TrioSex getSex() {
        return sex;
    }

    public boolean isAffected() {
        return affected;
    }
}
