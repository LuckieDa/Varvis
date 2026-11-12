package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.regex.Pattern;
import static org.nbscn.varvis.variantanalysis.provider.Bp1PublishedPolicy.require;

/** Reconstructs a supported published condition grammar; the gene and ranges are source data.
 * Attachment interpretation is a pinned project research assessment, never clinical approval. */
final class Bp1RegionPolicy {
    static final String TYPE = "PUBLISHED_OUTSIDE_REGION_SPLICE_STRONG_V1";
    static final String HEADER = "See Specifications Figure 1A for process to apply codes according to variant type, location and predicted bioinformatic impact. Missense prediction not applicable.";
    static final String PREFIX = "Apply BP1_Strong for silent substitution, missense or in-frame insertion, deletion or delins variants outside a (potentially) clinically important functional domain AND no splicing predicted (SpliceAI ≤0.1). As justified in the appendices, (potentially) clinically important functional domains are defined as: ";
    static final String SUFFIX = ". See Specifications Figure1A and Appendix J for details.";
    static final String WINDOW = "We recommend setting the Max Distance to 10 000 and display ‘unmasked’ and REF & ALT scores when curating variants. Using ‘unmasked’ scores may require additional interpretation to determine predicted splicing impact.";
    private Bp1RegionPolicy() { }

    static void validate(ObjectMapper mapper, JsonNode selection) {
        var record = selection.path("record"); var p = record.path("interpretation");
        var clause = selection.at("/specification/original").at(record.path("clausePointer").asText()).path("entContent");
        require(TYPE.equals(p.path("type").asText()) && "1.0.0".equals(p.path("version").asText())
                && p.size() == 11 && "STRONG".equals(p.path("appliedStrength").asText())
                && "PURE_MISSENSE_SNV".equals(p.path("supportedConsequence").asText())
                && "0.1".equals(p.path("maximumRawDelta").asText()) && integer(p.path("distanceBp"),10000)
                && integer(p.path("mask"),0) && isFalse(p.path("clinicalAdoption")) && isFalse(p.path("absenceMeansNormal")),
                "BP1_REGION_INTERPRETATION_INVALID");
        require("BP1".equals(clause.path("label").asText()) && "Applicable".equals(clause.path("applicability").asText())
                && "Benign".equals(clause.path("baseStrength").asText()) && "Benign Supporting".equals(clause.path("defaultStrength").asText())
                && HEADER.equals(plain(clause.path("instructionsToUse").asText())) && plain(clause.path("additionalComments").asText("")).isEmpty()
                && clause.path("gene").isArray() && clause.path("gene").size()==1, "BP1_REGION_SOURCE_CONDITIONS_UNSUPPORTED");
        JsonNode active = null;
        for (var s : clause.path("strengthDescriptor")) {
            if ("Applicable".equals(s.path("applicability").asText())) {
                require(active==null,"BP1_REGION_STRENGTH_UNSUPPORTED"); active=s;
            } else require(Set.of("Not applicable","Not Applicable","Not Applicable for this VCEP").contains(s.path("applicability").asText()),
                    "BP1_REGION_STRENGTH_UNSUPPORTED");
        }
        require(active!=null && "Strong".equals(active.path("strength").asText()) && plain(active.path("instructionsToUse").asText()).isEmpty(),
                "BP1_REGION_STRENGTH_UNSUPPORTED");
        String text=plain(java.util.Objects.requireNonNull(active).path("text").asText());
        require(text.startsWith(PREFIX) && text.endsWith(SUFFIX),"BP1_REGION_SOURCE_CONDITIONS_UNSUPPORTED");
        var regions=mapper.createArrayNode(); int previous=0;
        var pattern=Pattern.compile(Pattern.quote(clause.path("gene").get(0).asText())+" ([A-Za-z0-9 -]+) aa ([1-9][0-9]*)-([1-9][0-9]*)");
        for(String fragment:text.substring(PREFIX.length(),text.length()-SUFFIX.length()).split("; ",-1)) {
            var match=pattern.matcher(fragment);require(match.matches(),"BP1_REGION_SOURCE_RANGE_UNSUPPORTED");
            int start=Integer.parseInt(match.group(2)),end=Integer.parseInt(match.group(3));
            require(previous<start && start<=end && end<=100000,"BP1_REGION_SOURCE_RANGE_UNSUPPORTED");
            regions.addObject().put("label",match.group(1)).put("start",start).put("end",end);previous=end;
        }
        require(!regions.isEmpty() && regions.size()<=100 && regions.equals(p.path("excludedProteinRegions")),"BP1_REGION_SOURCE_RANGE_MISMATCH");
        var a=p.path("sourceAssessment");
        require("PROJECT_RESEARCH_SOURCE_INTERPRETATION".equals(a.path("purpose").asText())
                && record.path("specificationKey").equals(a.path("specificationKey"))
                && selection.at("/specification/entitySha256").equals(a.path("entitySha256"))
                && record.at("/scope/transcript").equals(a.path("sourceTranscript"))
                && WINDOW.equals(plain(a.path("windowRecommendation").asText()))
                && a.path("attachments").isArray() && a.path("attachments").size()>=2
                && a.path("functionalObservations").isArray() && a.path("functionalObservations").size()<=20000,
                "BP1_REGION_ATTACHMENT_ASSESSMENT_INVALID");
        for(var file:a.path("attachments")) require(file.path("rawSha256").asText().matches("[0-9a-f]{64}")
                && file.path("url").asText().startsWith("https://cspec.genome.network/cspec/File/id/")
                && !file.path("role").asText().isBlank(),"BP1_REGION_ATTACHMENT_SOURCE_INVALID");
        for(var row:a.path("functionalObservations")) require(row.path("codingChange").asText().matches("c\\.[1-9][0-9]*[ACGT]>[ACGT]")
                && row.path("proteinVariantKey").asText().matches("[ACDEFGHIKLMNPQRSTVWY][1-9][0-9]*[ACDEFGHIKLMNPQRSTVWY]")
                && row.path("reviewRequired").isBoolean() && row.path("spliceReviewRequired").isBoolean()
                && row.path("sourceRow").isIntegralNumber() && row.path("sourceRow").asInt()>0 && row.path("originalCells").isArray(),
                "BP1_REGION_FUNCTIONAL_OBSERVATION_INVALID");
    }

    static ObjectNode assess(ObjectMapper mapper, JsonNode selection, JsonNode association, JsonNode model) {
        var p=selection.at("/record/interpretation"); int position=association.path("proteinPosition").asInt();
        require(association.path("proteinPosition").isIntegralNumber() && position>1
                && position<model.path("cdsSequence").asText().length()/3, "BP1_REGION_RESIDUE_UNRESOLVED");
        var out=mapper.createObjectNode().put("type",TYPE).put("status","READY").put("reasonCode","BP1_OUTSIDE_PUBLISHED_REGIONS")
                .put("proteinPosition",position).put("appliedStrength","STRONG").put("distanceBp",10000)
                .put("maximumRawDelta","0.1").put("sourceTranscript",selection.at("/record/scope/transcript").asText())
                .put("experimentalNormalityEstablished",false).put("functionalCoverage","LOADED_PUBLICATIONS_ONLY");
        out.set("excludedProteinRegions",p.path("excludedProteinRegions").deepCopy());
        for(var range:p.path("excludedProteinRegions")) if(position>=range.path("start").asInt() && position<=range.path("end").asInt())
            return out.put("status","NOT_APPLICABLE").put("reasonCode","BP1_INSIDE_PUBLISHED_EXCLUDED_REGION");
        int c=association.path("cdsPosition").asInt();
        require(c>3 && c<=model.path("cdsSequence").asText().length()-3,"BP1_REGION_CODING_CHANGE_UNRESOLVED");
        require(association.path("refCodon").asText().matches("[ACGT]{3}")
                && association.path("altCodon").asText().matches("[ACGT]{3}"),"BP1_REGION_CODING_CHANGE_UNRESOLVED");
        String coding="c."+c+association.path("refCodon").asText().charAt((c-1)%3)+">"+association.path("altCodon").asText().charAt((c-1)%3);
        out.put("codingChange",coding); var matches=out.putArray("functionalMatches");
        for(var row:p.at("/sourceAssessment/functionalObservations")) {
            boolean sameProtein=row.path("proteinVariantKey").equals(association.path("proteinVariantKey"));
            boolean sameAllele=coding.equals(row.path("codingChange").asText());
            if(!sameProtein && !sameAllele)continue;
            matches.add(row.deepCopy());
            if(sameProtein && row.path("reviewRequired").asBoolean() || sameAllele && row.path("spliceReviewRequired").asBoolean())
                out.put("status","REVIEW_REQUIRED").put("reasonCode","BP1_PUBLISHED_REGION_FUNCTIONAL_REVIEW_REQUIRED");
        }
        return out;
    }
    private static boolean integer(JsonNode n,int expected){return n.isIntegralNumber() && n.canConvertToInt() && n.asInt()==expected;}
    private static boolean isFalse(JsonNode n){return n.isBoolean()&&!n.asBoolean();}
    private static String plain(String s){return s.replace("\\_","_").replace("**","").replace("_Missense prediction not applicable_","Missense prediction not applicable").replace('\u00a0',' ').strip().replaceAll("\\s+"," ");}
}
