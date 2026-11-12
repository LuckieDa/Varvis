package org.nbscn.varvis.variantanalysis.provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Internal deterministic calculation after source qualification. Not an HTTP input or adoption receipt.
 * ClinGen SVI PM3 v1 (2019-05-02). Quarter points avoid floating point rounding.
 * Source verification, allele normalization and applicable specification selection are upstream duties.
 */
public final class Pm3LiteratureScoring {
    private Pm3LiteratureScoring() { }
    public enum Check { VERIFIED, UNKNOWN, CONFLICT, FAILED }
    public enum Phase { TRANS, UNKNOWN, CIS, CONFLICT }
    public enum Classification { PATHOGENIC, LIKELY_PATHOGENIC, VUS, BENIGN, LIKELY_BENIGN, UNKNOWN }
    public record Observation(String id, String independentOccurrenceId, String targetAlleleId,
            String partnerAlleleId, boolean homozygous, Phase phase, Classification partnerClassification,
            Check caseIdentity, Check affectedRecessiveContext, Check targetIdentity, Check partnerIdentity,
            Check targetRare, Check partnerRare, Check independentClassification,
            List<String> sourceReferences) {
        public Observation {
            for (Object value : List.of(id, independentOccurrenceId, targetAlleleId, phase,
                    partnerClassification, caseIdentity, affectedRecessiveContext, targetIdentity,
                    partnerIdentity, targetRare, partnerRare, independentClassification)) Objects.requireNonNull(value);
            if (id.isBlank() || independentOccurrenceId.isBlank() || targetAlleleId.isBlank())
                throw new IllegalArgumentException("PM3_OBSERVATION_ID_REQUIRED");
            sourceReferences = List.copyOf(sourceReferences);
            if (sourceReferences.isEmpty() || sourceReferences.stream().anyMatch(s->Objects.requireNonNull(s).isBlank()))
                throw new IllegalArgumentException("PM3_SOURCE_REFERENCES_REQUIRED");
        }
    }
    public record Requirement(String reason, String status) { }
    public record Contribution(String observationId, int proposedQuarterPoints, int awardedQuarterPoints,
            String qualificationStatus, String reason, List<String> sourceReferences, List<Requirement> requirements) {
        public Contribution {sourceReferences=List.copyOf(sourceReferences);requirements=List.copyOf(requirements);}
        Contribution(String id,int proposed,int awarded,String status,String reason,List<String> sources) {
            this(id,proposed,awarded,status,reason,sources,List.of());
        }
    }
    public record Result(String status, String strength, int quarterPoints,
            List<Contribution> contributions, List<String> issues) {
        public Result { contributions=List.copyOf(contributions); issues=List.copyOf(issues); }
    }

    public static Result calculate(String targetAlleleId, List<Observation> observations) {
        return calculate(targetAlleleId, observations, Map.of());
    }

    /** Published prerequisites change eligibility, never the existing weights/caps. */
    static Result calculate(String targetAlleleId, List<Observation> observations, Map<String,List<Requirement>> published) {
        Objects.requireNonNull(targetAlleleId); Objects.requireNonNull(observations);
        if (targetAlleleId.isBlank() || observations.size()>10000) throw new IllegalArgumentException("PM3_INPUT_LIMIT");
        List<Observation> ordered = observations.stream().sorted(Comparator.comparing(o->Objects.requireNonNull(o).id())).toList();
        Set<String> ids = new HashSet<>();
        for (Observation o: ordered) if (!ids.add(o.id())) throw new IllegalArgumentException("PM3_DUPLICATE_OBSERVATION_ID");
        Map<String,List<Observation>> occurrences = new HashMap<>();
        for (Observation o: ordered) occurrences.computeIfAbsent(o.independentOccurrenceId(), k->new ArrayList<>()).add(o);
        Set<String> conflicting = new HashSet<>();
        for (var entry: occurrences.entrySet()) {
            Observation first = entry.getValue().get(0);
            if (qualificationConflict(entry.getValue()) || entry.getValue().stream().anyMatch(o -> !sameAssessment(first,o)
                    || published.getOrDefault(o.id(),List.of()).stream().anyMatch(r->"CONFLICT".equals(r.status())))) conflicting.add(entry.getKey());
        }
        Set<String> used = new HashSet<>(), unknownPartners = new HashSet<>();
        List<Contribution> contributions = new ArrayList<>(); List<String> issues = new ArrayList<>();
        List<Observation> eligible = new ArrayList<>();
        for (Observation o: ordered) {
            String reason = !targetAlleleId.equals(o.targetAlleleId()) ? "PM3_TARGET_MISMATCH" :
                    conflicting.contains(o.independentOccurrenceId()) ? "PM3_OCCURRENCE_CONFLICT" :
                    !published.getOrDefault(o.id(),List.of()).isEmpty() ? published.get(o.id()).get(0).reason() : refusal(o);
            if (reason != null) {
                var requirements=new ArrayList<Requirement>();
                requirements.addAll(published.getOrDefault(o.id(),List.of()));
                if(requirements.stream().noneMatch(r->"NOT_APPLICABLE".equals(r.status())))
                    for(String issue:refusals(o)) requirements.add(new Requirement(issue,disposition(o,issue)));
                if(requirements.stream().noneMatch(r->r.reason().equals(reason))) requirements.add(0,new Requirement(reason,disposition(o,reason)));
                String status=requirements.stream().filter(r->r.reason().equals(reason)).findFirst()
                    .map(r->Objects.requireNonNull(r).status()).orElseGet(()->disposition(o,reason));
                if(requirements.stream().anyMatch(r->r.status().equals("CONFLICT"))) status="CONFLICT";
                else if(requirements.stream().anyMatch(r->r.status().equals("RULE_NOT_IMPLEMENTED"))) status="RULE_NOT_IMPLEMENTED";
                else if(requirements.stream().anyMatch(r->r.status().equals("MISSING_INFORMATION"))) status="MISSING_INFORMATION";
                contributions.add(new Contribution(o.id(),0,0,status,reason,o.sourceReferences(),requirements));
                for(var requirement:requirements) if(!issues.contains(requirement.reason())) issues.add(requirement.reason());
            } else if (!used.add(o.independentOccurrenceId())) {
                contributions.add(new Contribution(o.id(),points(o),0,"DUPLICATE","PM3_DUPLICATE_OCCURRENCE",o.sourceReferences()));
            } else {
                eligible.add(o);
                if (!o.homozygous() && o.phase()==Phase.UNKNOWN && points(o)>0) unknownPartners.add(o.partnerAlleleId());
            }
        }
        int total=0, homo=0, vus=0, unknown=0;
        for (Observation o: eligible) {
            int proposed=points(o), awarded=proposed; String reason="PM3_OBSERVATION_COUNTED";
            if (o.homozygous()) { awarded=Math.min(proposed,4-homo); homo+=awarded; }
            else if (o.partnerClassification()==Classification.VUS) { awarded=Math.min(proposed,2-vus); vus+=awarded; }
            else if (o.phase()==Phase.UNKNOWN) {
                // General-policy interpretation: one distinct partner cannot accumulate
                // the weight of one confirmed-trans occurrence. Preserve the reduction.
                if (unknownPartners.size()<2) awarded=Math.min(proposed,Math.max(0,2-unknown));
                unknown+=awarded;
            }
            if (awarded<proposed) reason="PM3_ACCUMULATION_CAP";
            if (proposed==0) reason="PM3_UNKNOWN_PHASE_VUS_NO_POINTS";
            total+=awarded;
            contributions.add(new Contribution(o.id(),proposed,awarded,awarded>0?"COUNTED":"NOT_COUNTED",reason,o.sourceReferences()));
        }
        contributions.sort(Comparator.comparing(c->Objects.requireNonNull(c).observationId()));
        String strength=total>=16?"VERY_STRONG":total>=8?"STRONG":total>=4?"MODERATE":total>=2?"SUPPORTING":"NONE";
        boolean unresolved=contributions.stream().anyMatch(c->Set.of("MISSING_INFORMATION","CONFLICT","RULE_NOT_IMPLEMENTED").contains(c.qualificationStatus()));
        boolean inapplicable=!contributions.isEmpty() && contributions.stream().allMatch(c->c.qualificationStatus().equals("NOT_APPLICABLE"));
        String status=total>=2?"MET":observations.isEmpty() || unresolved?"NOT_EVALUATED":inapplicable?"NOT_APPLICABLE":"NOT_MET";
        if (observations.isEmpty()) issues.add("PM3_NO_QUALIFIED_OBSERVATIONS");
        return new Result(status,strength,total,contributions,issues);
    }

    private static String disposition(Observation o,String reason) {
        if (reason.contains("CONFLICT") || reason.equals("PM3_TARGET_MISMATCH")) return "CONFLICT";
        Check check=switch(reason) {
            case "PM3_CASE_IDENTITY_OR_INDEPENDENCE_UNRESOLVED" -> o.caseIdentity();
            case "PM3_AFFECTED_RECESSIVE_CONTEXT_UNRESOLVED" -> o.affectedRecessiveContext();
            case "PM3_TARGET_IDENTITY_UNRESOLVED" -> o.targetIdentity();
            case "PM3_PARTNER_IDENTITY_UNRESOLVED" -> o.partnerIdentity()==Check.VERIFIED?Check.UNKNOWN:o.partnerIdentity();
            case "PM3_TARGET_RARITY_NOT_ESTABLISHED" -> o.targetRare();
            case "PM3_PARTNER_RARITY_NOT_ESTABLISHED" -> o.partnerRare();
            case "PM3_PARTNER_CLASSIFICATION_INDEPENDENCE_UNRESOLVED" -> o.independentClassification();
            default -> Check.UNKNOWN;
        };
        if (check==Check.CONFLICT) return "CONFLICT";
        if (reason.equals("PM3_CIS_NOT_APPLICABLE") || reason.equals("PM3_PARTNER_CLASSIFICATION_NOT_ELIGIBLE")
                || reason.equals("PM3_AFFECTED_RECESSIVE_CONTEXT_UNRESOLVED") && check==Check.FAILED) return "NOT_APPLICABLE";
        return check==Check.FAILED?"REQUIREMENT_NOT_MET":"MISSING_INFORMATION";
    }

    private static String refusal(Observation o) {
        var reasons=refusals(o);return reasons.isEmpty()?null:reasons.get(0);
    }
    private static List<String> refusals(Observation o) {
        var reasons=new ArrayList<String>();
        if(o.caseIdentity()!=Check.VERIFIED) reasons.add("PM3_CASE_IDENTITY_OR_INDEPENDENCE_UNRESOLVED");
        if(o.affectedRecessiveContext()!=Check.VERIFIED) reasons.add("PM3_AFFECTED_RECESSIVE_CONTEXT_UNRESOLVED");
        if(o.targetIdentity()!=Check.VERIFIED) reasons.add("PM3_TARGET_IDENTITY_UNRESOLVED");
        boolean unresolvedPartner=o.partnerAlleleId()==null || o.partnerAlleleId().isBlank()
                || o.partnerAlleleId().equals(o.targetAlleleId()) || o.partnerIdentity()!=Check.VERIFIED;
        if(!o.homozygous() && o.phase()==Phase.CIS) {
            if(unresolvedPartner) reasons.add("PM3_PARTNER_IDENTITY_UNRESOLVED");
            reasons.add("PM3_CIS_NOT_APPLICABLE");
            return reasons;
        }
        if(o.targetRare()!=Check.VERIFIED) reasons.add("PM3_TARGET_RARITY_NOT_ESTABLISHED");
        if(o.homozygous()) {
            if(o.partnerAlleleId()!=null || o.phase()==Phase.CIS || o.phase()==Phase.CONFLICT) reasons.add("PM3_HOMOZYGOUS_OBSERVATION_CONFLICT");
            return reasons;
        }
        if(unresolvedPartner) reasons.add("PM3_PARTNER_IDENTITY_UNRESOLVED");
        if(o.partnerRare()!=Check.VERIFIED) reasons.add("PM3_PARTNER_RARITY_NOT_ESTABLISHED");
        if(o.phase()==Phase.CONFLICT) reasons.add("PM3_PHASE_CONFLICT");
        if(o.independentClassification()!=Check.VERIFIED) reasons.add("PM3_PARTNER_CLASSIFICATION_INDEPENDENCE_UNRESOLVED");
        if(o.partnerClassification()==Classification.UNKNOWN) reasons.add("PM3_PARTNER_CLASSIFICATION_MISSING");
        if(o.partnerClassification()==Classification.BENIGN || o.partnerClassification()==Classification.LIKELY_BENIGN) reasons.add("PM3_PARTNER_CLASSIFICATION_NOT_ELIGIBLE");
        return reasons;
    }
    private static int points(Observation o) {
        if (o.homozygous()) return 2;
        if (o.partnerClassification()==Classification.VUS) return o.phase()==Phase.TRANS?1:0;
        if (o.phase()==Phase.TRANS) return 4;
        return o.partnerClassification()==Classification.PATHOGENIC?2:1;
    }
    private static boolean sameAssessment(Observation a, Observation b) {
        return a.targetAlleleId().equals(b.targetAlleleId()) && Objects.equals(a.partnerAlleleId(),b.partnerAlleleId())
            && a.homozygous()==b.homozygous() && a.phase()==b.phase() && a.partnerClassification()==b.partnerClassification();
    }
    private static boolean qualificationConflict(List<Observation> occurrence) {
        // UNKNOWN is an unestablished qualification, not an opposing observed
        // fact. Compare all known states, so UNKNOWN cannot hide VERIFIED/FAILED.
        var known=new ArrayList<Set<Check>>();for(int i=0;i<7;i++)known.add(new HashSet<>());
        for(var o:occurrence) {
            var checks=List.of(o.caseIdentity(),o.affectedRecessiveContext(),o.targetIdentity(),o.partnerIdentity(),
                    o.targetRare(),o.partnerRare(),o.independentClassification());
            for(int i=0;i<checks.size();i++)if(checks.get(i)!=Check.UNKNOWN)known.get(i).add(checks.get(i));
        }
        return known.stream().anyMatch(values->values.size()>1);
    }
}
