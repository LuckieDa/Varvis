package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import static org.nbscn.varvis.variantanalysis.provider.Bp1PublishedPolicy.require;

/** A pinned publication screen can withhold BP1, never award evidence or clinical adoption.
 * Scope is the loaded experiments, not an exhaustive search of external literature. */
final class Bp1PublishedFunctionScreen {
    static final String SCHEMA = "bp1-published-function-screen-v1";
    private final ObjectMapper mapper;
    private final JsonNode document;
    private final String sha;

    Bp1PublishedFunctionScreen(ObjectMapper mapper, JsonNode input) {
        this.mapper = mapper;
        if (!(input != null && input.isObject() && SCHEMA.equals(input.path("schemaVersion").asText())
                && "RESEARCH_REVIEW_BLOCK_ONLY".equals(input.path("purpose").asText())
                && "LOADED_PUBLICATIONS_ONLY".equals(input.path("coverage").asText())
                && isFalse(input.path("clinicalAdoption")) && isFalse(input.path("absenceMeansNormal"))
                && input.path("screens").isArray() && input.path("screens").size() <= 100)) {
            throw new IllegalArgumentException("BP1_FUNCTION_SCREEN_DOCUMENT_INVALID");
        }
        var ids = new HashSet<String>();
        for (var screen : input.path("screens")) {
            require(text(screen,"screenId") && ids.add(screen.path("screenId").asText())
                    && screen.path("hgncId").asText().matches("HGNC:[1-9][0-9]*")
                    && screen.path("transcript").asText().matches("NM_[0-9]+\\.[1-9][0-9]*")
                    && screen.path("protein").asText().matches("NP_[0-9]+\\.[1-9][0-9]*")
                    && screen.path("cdsSha256").asText().matches("[0-9a-f]{64}")
                    && screen.at("/source/rawSha256").asText().matches("[0-9a-f]{64}")
                    && screen.at("/source/url").asText().startsWith("https://") && text(screen.path("source"),"sourceId")
                    && screen.path("ranges").isArray() && !screen.path("ranges").isEmpty() && screen.path("ranges").size() <= 100
                    && screen.path("records").isArray() && screen.path("records").size() <= 100_000,
                    "BP1_FUNCTION_SCREEN_SOURCE_INVALID");
            for (var range : screen.path("ranges")) require(integer(range.path("start")) && integer(range.path("end"))
                    && range.path("start").asInt() <= range.path("end").asInt(), "BP1_FUNCTION_SCREEN_RANGE_INVALID");
            var variants = new HashSet<String>();
            for (var row : screen.path("records")) require(row.path("proteinVariantKey").asText().matches("[ACDEFGHIKLMNPQRSTVWY][1-9][0-9]*[ACDEFGHIKLMNPQRSTVWY]")
                    && variants.add(row.path("proteinVariantKey").asText())
                    && Set.of("DAMAGING","INTERMEDIATE","FUNCTIONAL").contains(row.path("reportedClass").asText())
                    && integer(row.path("sourceRow")) && text(row,"sourceSheet") && row.path("originalRow").isObject(),
                    "BP1_FUNCTION_SCREEN_RECORD_INVALID");
        }
        document=input.deepCopy(); sha=new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(document);
    }
    ObjectNode assess(JsonNode model, JsonNode association) {
        var out=mapper.createObjectNode().put("schemaVersion","bp1-function-screen-check-v1")
                .put("status","CHECKED").put("reasonCode","BP1_NO_MATCH_IN_LOADED_FUNCTION_SCREENS")
                .put("catalogSha256",sha).put("coverage","LOADED_PUBLICATIONS_ONLY")
                .put("absenceMeansNormal",false).put("clinicalAdoption",false);
        var matches=out.putArray("matches");
        String proteinKey=association.path("proteinVariantKey").asText();
        require(proteinKey.matches("[ACDEFGHIKLMNPQRSTVWY][1-9][0-9]*[ACDEFGHIKLMNPQRSTVWY]")
                && integer(association.path("proteinPosition")), "BP1_FUNCTION_SCREEN_VARIANT_IDENTITY_REQUIRED");
        int position=association.path("proteinPosition").asInt();
        require(proteinKey.equals(association.path("refAminoAcid").asText()+position+association.path("altAminoAcid").asText()),
                "BP1_FUNCTION_SCREEN_VARIANT_IDENTITY_REQUIRED");
        for (var screen:document.path("screens")) {
            if (!screen.path("hgncId").equals(model.path("hgncId"))) continue;
            var match=matches.addObject().put("screenId",screen.path("screenId").asText());
            match.set("source",screen.path("source").deepCopy()); match.set("ranges",screen.path("ranges").deepCopy());
            boolean same = screen.path("cdsSha256").asText().equals(CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()))
                    && (screen.path("transcript").equals(model.path("transcript")) && screen.path("protein").equals(model.path("protein"))
                    || "FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE".equals(model.at("/transcriptBridge/status").asText())
                    && screen.path("transcript").equals(model.at("/transcriptBridge/refseqTranscript"))
                    && screen.path("protein").equals(model.at("/transcriptBridge/refseqProtein"))
                    && model.path("transcript").equals(model.at("/transcriptBridge/ensemblTranscript"))
                    && model.path("protein").equals(model.at("/transcriptBridge/ensemblProtein")));
            if (!same) return out.put("status","UNAVAILABLE").put("reasonCode","BP1_FUNCTION_SCREEN_SEQUENCE_UNRESOLVED");
            match.put("proteinVariantKey",proteinKey).put("sequenceAssociation","VERIFIED_MISSENSE_MODEL_CDS_AND_REFERENCE_PROTEIN");
            JsonNode found=null;
            for(var row:screen.path("records")) if(proteinKey.equals(row.path("proteinVariantKey").asText())) {found=row;break;}
            if(found!=null) {
                match.set("record",found.deepCopy());
                if(!"FUNCTIONAL".equals(found.path("reportedClass").asText()))
                    return out.put("status","REVIEW_REQUIRED").put("reasonCode","BP1_PUBLISHED_FUNCTIONAL_EFFECT_REVIEW_REQUIRED");
                match.put("outcome","NO_ADVERSE_SIGNAL_IN_THIS_ASSAY_NOT_NORMALITY_PROOF");
            } else {
                boolean within=false;
                for(var range:screen.path("ranges")) if(position>=range.path("start").asInt() && position<=range.path("end").asInt())within=true;
                match.put("outcome",within?"RECORD_MISSING_IN_REPORTED_SCREEN_RANGE":"OUTSIDE_REPORTED_SCREEN_RANGES");
                if(within)return out.put("status","UNAVAILABLE").put("reasonCode","BP1_FUNCTION_SCREEN_RECORD_UNAVAILABLE");
            }
        }
        if(!matches.isEmpty())out.put("reasonCode","BP1_LOADED_FUNCTION_SCREENS_CHECKED");
        return out;
    }
    private static boolean integer(JsonNode value) {return value.isIntegralNumber() && value.canConvertToInt() && value.asInt()>0;}
    private static boolean text(JsonNode value,String key) {return value.path(key).isTextual()&&!value.path(key).asText().isBlank();}
    private static boolean isFalse(JsonNode value) {return value.isBoolean()&&!value.asBoolean();}
}
