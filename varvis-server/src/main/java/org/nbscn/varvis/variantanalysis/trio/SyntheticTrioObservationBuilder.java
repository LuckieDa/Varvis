package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class SyntheticTrioObservationBuilder {

    public TrioAnalysisInput build(List<TrioMember> members, List<SyntheticTrioVariantRecord> records) {
        List<TrioVariantObservation> variants = new ArrayList<TrioVariantObservation>();
        if (records != null) {
            for (SyntheticTrioVariantRecord record : records) {
                if (record != null) {
                    variants.add(toObservation(record));
                }
            }
        }
        return new TrioAnalysisInput(members == null ? Collections.<TrioMember>emptyList() : members, variants);
    }

    public TrioVariantObservation toObservation(SyntheticTrioVariantRecord record) {
        Map<TrioRole, TrioGenotypeObservation> genotypes =
                new EnumMap<TrioRole, TrioGenotypeObservation>(TrioRole.class);
        genotypes.put(TrioRole.PROBAND, parse(TrioRole.PROBAND, record.getProbandGenotype()));
        genotypes.put(TrioRole.FATHER, parse(TrioRole.FATHER, record.getFatherGenotype()));
        genotypes.put(TrioRole.MOTHER, parse(TrioRole.MOTHER, record.getMotherGenotype()));
        return new TrioVariantObservation(
                record.getVariantId(),
                record.getGeneSymbol(),
                record.getChromosome(),
                record.getPosition(),
                genotypes);
    }

    public TrioGenotypeObservation parse(TrioRole role, String genotype) {
        if (genotype == null || genotype.trim().isEmpty()) {
            return TrioGenotypeObservation.unobserved(role);
        }
        String normalized = genotype.trim();
        if (".".equals(normalized) || "./.".equals(normalized) || ".|.".equals(normalized)) {
            return TrioGenotypeObservation.noCall(role);
        }
        String unphased = normalized.replace('|', '/');
        if ("0/0".equals(unphased)) {
            return TrioGenotypeObservation.homRef(role);
        }
        if ("0/1".equals(unphased) || "1/0".equals(unphased)) {
            return TrioGenotypeObservation.het(role);
        }
        if ("1/1".equals(unphased)) {
            return TrioGenotypeObservation.homAlt(role);
        }
        if ("1".equals(unphased)) {
            return TrioGenotypeObservation.hemiAlt(role);
        }
        return new TrioGenotypeObservation(role, genotype, TrioGenotypeCall.OTHER, null, null, null);
    }
}
