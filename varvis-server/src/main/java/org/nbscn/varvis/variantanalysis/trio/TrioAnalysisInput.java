package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class TrioAnalysisInput {

    private final List<TrioMember> members;
    private final List<TrioVariantObservation> variants;

    public TrioAnalysisInput(List<TrioMember> members, List<TrioVariantObservation> variants) {
        this.members = immutableMembers(members);
        this.variants = immutableVariants(variants);
    }

    private List<TrioMember> immutableMembers(List<TrioMember> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<TrioMember>(values));
    }

    private List<TrioVariantObservation> immutableVariants(List<TrioVariantObservation> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<TrioVariantObservation>(values));
    }

    public List<TrioMember> getMembers() {
        return members;
    }

    public List<TrioVariantObservation> getVariants() {
        return variants;
    }

    public TrioMember member(TrioRole role) {
        if (role == null || members.isEmpty()) {
            return null;
        }
        for (TrioMember member : members) {
            if (member != null && role == member.getRole()) {
                return member;
            }
        }
        return null;
    }

    public Map<TrioRole, TrioMember> memberMap() {
        Map<TrioRole, TrioMember> result = new EnumMap<TrioRole, TrioMember>(TrioRole.class);
        for (TrioMember member : members) {
            if (member != null) {
                result.put(member.getRole(), member);
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
