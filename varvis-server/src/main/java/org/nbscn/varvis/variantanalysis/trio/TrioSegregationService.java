package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class TrioSegregationService {

    private static final String MISSING_PARENT_WARNING =
            "Missing, no-call, unobserved or low-quality reference parental genotypes are not treated as confirmed 0/0.";

    public List<SegregationEvidence> evaluate(TrioAnalysisInput input) {
        if (input == null || input.getVariants().isEmpty()) {
            return Collections.emptyList();
        }
        Map<TrioVariantObservation, CompoundAssignment> compoundAssignments =
                findCompoundAssignments(input.getVariants());
        List<SegregationEvidence> result = new ArrayList<SegregationEvidence>();
        for (TrioVariantObservation variant : input.getVariants()) {
            if (variant != null) {
                result.add(evaluateVariant(input, variant, compoundAssignments.get(variant)));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private SegregationEvidence evaluateVariant(TrioAnalysisInput input,
                                                TrioVariantObservation variant,
                                                CompoundAssignment compoundAssignment) {
        TrioGenotypeObservation proband = variant.genotype(TrioRole.PROBAND);
        TrioGenotypeObservation father = variant.genotype(TrioRole.FATHER);
        TrioGenotypeObservation mother = variant.genotype(TrioRole.MOTHER);
        List<String> warnings = parentalUncertaintyWarnings(father, mother);

        if (proband == null) {
            return evidence(variant, InheritanceModelCandidate.UNINFORMATIVE,
                    SegregationConfidence.UNINFORMATIVE, ParentalOrigin.UNKNOWN,
                    Collections.<String>emptyList(),
                    singleton("Proband genotype is missing."), warnings);
        }
        if (!proband.carriesAlternateAllele()) {
            return evidence(variant, InheritanceModelCandidate.UNINFORMATIVE,
                    SegregationConfidence.UNINFORMATIVE, ParentalOrigin.NONE,
                    Collections.<String>emptyList(),
                    singleton("Proband does not carry the alternate allele."), warnings);
        }

        if (isSupportedDeNovo(proband, father, mother)) {
            return evidence(variant, InheritanceModelCandidate.DE_NOVO_CANDIDATE,
                    SegregationConfidence.SUPPORTED, ParentalOrigin.NONE,
                    Collections.<String>emptyList(),
                    singleton("Proband carries the allele and both parents are confirmed reference."),
                    warnings);
        }

        if (isSupportedHomozygousRecessive(proband, father, mother)) {
            return evidence(variant, InheritanceModelCandidate.HOMOZYGOUS_RECESSIVE_CANDIDATE,
                    SegregationConfidence.SUPPORTED, ParentalOrigin.BIPARENTAL,
                    Collections.<String>emptyList(),
                    singleton("Proband is homozygous alternate and both parents are heterozygous carriers."),
                    warnings);
        }

        if (isSupportedXLinked(input, variant, proband, father, mother)) {
            List<String> reasons = new ArrayList<String>();
            reasons.add("Male proband carries an X-linked alternate allele and mother carries the allele.");
            if (father == null || father.isUninformative()) {
                warnings = append(warnings, "Father X chromosome genotype is uninformative; paternal status is not assumed.");
            }
            return evidence(variant, InheritanceModelCandidate.X_LINKED_CANDIDATE,
                    SegregationConfidence.SUPPORTED, ParentalOrigin.MATERNAL,
                    Collections.<String>emptyList(), reasons, warnings);
        }

        if (compoundAssignment != null) {
            return evidence(variant, InheritanceModelCandidate.COMPOUND_HETEROZYGOUS_CANDIDATE,
                    SegregationConfidence.SUPPORTED, compoundAssignment.getOrigin(),
                    compoundAssignment.getPartnerVariantIds(),
                    singleton("Distinct heterozygous variants in the same gene have complementary parental origin."),
                    warnings);
        }

        ParentalOrigin inheritedOrigin = inheritedOrigin(father, mother);
        if (inheritedOrigin != ParentalOrigin.UNKNOWN && inheritedOrigin != ParentalOrigin.NONE) {
            return evidence(variant, InheritanceModelCandidate.INHERITED_CANDIDATE,
                    SegregationConfidence.POSSIBLE, inheritedOrigin,
                    Collections.<String>emptyList(),
                    singleton("The alternate allele is observed in at least one parent."),
                    warnings);
        }

        List<String> reasons = new ArrayList<String>();
        reasons.add("No supported trio segregation model was identified.");
        if (!hasGene(variant)) {
            reasons.add("Gene symbol is unavailable; compound heterozygous grouping cannot be evaluated.");
        }
        return evidence(variant, InheritanceModelCandidate.UNINFORMATIVE,
                SegregationConfidence.UNINFORMATIVE, ParentalOrigin.UNKNOWN,
                Collections.<String>emptyList(), reasons, warnings);
    }

    private Map<TrioVariantObservation, CompoundAssignment> findCompoundAssignments(
            List<TrioVariantObservation> variants) {
        Map<String, List<TrioVariantObservation>> paternalByGene =
                new HashMap<String, List<TrioVariantObservation>>();
        Map<String, List<TrioVariantObservation>> maternalByGene =
                new HashMap<String, List<TrioVariantObservation>>();

        for (TrioVariantObservation variant : variants) {
            if (variant == null || !hasGene(variant) || !isProbandHet(variant)) {
                continue;
            }
            ParentalOrigin origin = strictSingleParentOrigin(
                    variant.genotype(TrioRole.FATHER), variant.genotype(TrioRole.MOTHER));
            if (origin != ParentalOrigin.PATERNAL && origin != ParentalOrigin.MATERNAL) {
                continue;
            }
            for (String geneKey : geneKeys(variant.getGeneSymbol())) {
                Map<String, List<TrioVariantObservation>> target =
                        origin == ParentalOrigin.PATERNAL ? paternalByGene : maternalByGene;
                addByGene(target, geneKey, variant);
            }
        }

        Map<TrioVariantObservation, CompoundAssignment> assignments =
                new IdentityHashMap<TrioVariantObservation, CompoundAssignment>();
        for (String geneKey : paternalByGene.keySet()) {
            List<TrioVariantObservation> paternal = paternalByGene.get(geneKey);
            List<TrioVariantObservation> maternal = maternalByGene.get(geneKey);
            if (paternal == null || paternal.isEmpty() || maternal == null || maternal.isEmpty()) {
                continue;
            }
            for (TrioVariantObservation variant : paternal) {
                assignments.put(variant, new CompoundAssignment(
                        ParentalOrigin.PATERNAL, variantIdsExcluding(maternal, variant)));
            }
            for (TrioVariantObservation variant : maternal) {
                assignments.put(variant, new CompoundAssignment(
                        ParentalOrigin.MATERNAL, variantIdsExcluding(paternal, variant)));
            }
        }
        return assignments;
    }

    private boolean isSupportedDeNovo(TrioGenotypeObservation proband,
                                      TrioGenotypeObservation father,
                                      TrioGenotypeObservation mother) {
        return (proband.isHeterozygousAlternate() || proband.isHemizygousAlternate())
                && isConfirmedReference(father)
                && isConfirmedReference(mother);
    }

    private boolean isSupportedHomozygousRecessive(TrioGenotypeObservation proband,
                                                   TrioGenotypeObservation father,
                                                   TrioGenotypeObservation mother) {
        return proband.isHomozygousAlternate()
                && isHet(father)
                && isHet(mother);
    }

    private boolean isSupportedXLinked(TrioAnalysisInput input,
                                       TrioVariantObservation variant,
                                       TrioGenotypeObservation proband,
                                       TrioGenotypeObservation father,
                                       TrioGenotypeObservation mother) {
        TrioMember probandMember = input.member(TrioRole.PROBAND);
        return isChromosomeX(variant.getChromosome())
                && probandMember != null
                && probandMember.getSex() == TrioSex.MALE
                && proband.carriesAlternateAllele()
                && carriesAlternateAllele(mother)
                && !carriesAlternateAllele(father);
    }

    private boolean isProbandHet(TrioVariantObservation variant) {
        TrioGenotypeObservation proband = variant.genotype(TrioRole.PROBAND);
        return proband != null && proband.isHeterozygousAlternate();
    }

    private ParentalOrigin inheritedOrigin(TrioGenotypeObservation father,
                                           TrioGenotypeObservation mother) {
        boolean fatherCarries = carriesAlternateAllele(father);
        boolean motherCarries = carriesAlternateAllele(mother);
        if (fatherCarries && motherCarries) {
            return ParentalOrigin.BIPARENTAL;
        }
        if (fatherCarries && isConfirmedReference(mother)) {
            return ParentalOrigin.PATERNAL;
        }
        if (motherCarries && isConfirmedReference(father)) {
            return ParentalOrigin.MATERNAL;
        }
        if (fatherCarries || motherCarries) {
            return fatherCarries ? ParentalOrigin.PATERNAL : ParentalOrigin.MATERNAL;
        }
        if (isConfirmedReference(father) && isConfirmedReference(mother)) {
            return ParentalOrigin.NONE;
        }
        return ParentalOrigin.UNKNOWN;
    }

    private ParentalOrigin strictSingleParentOrigin(TrioGenotypeObservation father,
                                                    TrioGenotypeObservation mother) {
        if (carriesAlternateAllele(father) && isConfirmedReference(mother)) {
            return ParentalOrigin.PATERNAL;
        }
        if (carriesAlternateAllele(mother) && isConfirmedReference(father)) {
            return ParentalOrigin.MATERNAL;
        }
        return ParentalOrigin.UNKNOWN;
    }

    private boolean isHet(TrioGenotypeObservation genotype) {
        return genotype != null && genotype.isHeterozygousAlternate();
    }

    private boolean carriesAlternateAllele(TrioGenotypeObservation genotype) {
        return genotype != null && genotype.carriesAlternateAllele();
    }

    private boolean isConfirmedReference(TrioGenotypeObservation genotype) {
        return genotype != null && genotype.isConfirmedReference();
    }

    private boolean hasGene(TrioVariantObservation variant) {
        return variant.getGeneSymbol() != null && !variant.getGeneSymbol().trim().isEmpty();
    }

    private boolean isChromosomeX(String chromosome) {
        if (chromosome == null) {
            return false;
        }
        String normalized = chromosome.trim().toUpperCase(Locale.ROOT);
        return "X".equals(normalized) || "CHRX".equals(normalized);
    }

    private Set<String> geneKeys(String geneSymbol) {
        if (geneSymbol == null || geneSymbol.trim().isEmpty()) {
            return Collections.emptySet();
        }
        String[] parts = geneSymbol.split("[,;|]");
        Set<String> keys = new LinkedHashSet<String>();
        for (String part : parts) {
            String key = part == null ? "" : part.trim().toUpperCase(Locale.ROOT);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    private void addByGene(Map<String, List<TrioVariantObservation>> values,
                           String geneKey,
                           TrioVariantObservation variant) {
        List<TrioVariantObservation> list = values.get(geneKey);
        if (list == null) {
            list = new ArrayList<TrioVariantObservation>();
            values.put(geneKey, list);
        }
        list.add(variant);
    }

    private List<String> variantIdsExcluding(List<TrioVariantObservation> variants,
                                             TrioVariantObservation excluded) {
        List<String> result = new ArrayList<String>();
        for (TrioVariantObservation variant : variants) {
            if (variant != excluded) {
                result.add(variant.getVariantId());
            }
        }
        return result;
    }

    private List<String> parentalUncertaintyWarnings(TrioGenotypeObservation father,
                                                     TrioGenotypeObservation mother) {
        List<String> warnings = new ArrayList<String>();
        if (father == null || father.isUninformative() || mother == null || mother.isUninformative()) {
            warnings.add(MISSING_PARENT_WARNING);
        }
        return warnings;
    }

    private SegregationEvidence evidence(TrioVariantObservation variant,
                                         InheritanceModelCandidate model,
                                         SegregationConfidence confidence,
                                         ParentalOrigin parentalOrigin,
                                         List<String> partnerVariantIds,
                                         List<String> reasons,
                                         List<String> warnings) {
        return new SegregationEvidence(variant.getVariantId(), variant.getGeneSymbol(), model, confidence,
                parentalOrigin, partnerVariantIds, reasons, warnings);
    }

    private List<String> singleton(String value) {
        List<String> result = new ArrayList<String>();
        result.add(value);
        return result;
    }

    private List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<String>();
        if (values != null) {
            result.addAll(values);
        }
        result.add(value);
        return result;
    }

    private static class CompoundAssignment {
        private final ParentalOrigin origin;
        private final List<String> partnerVariantIds;

        CompoundAssignment(ParentalOrigin origin, List<String> partnerVariantIds) {
            this.origin = origin;
            this.partnerVariantIds = partnerVariantIds == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(partnerVariantIds));
        }

        ParentalOrigin getOrigin() {
            return origin;
        }

        List<String> getPartnerVariantIds() {
            return partnerVariantIds;
        }
    }
}
