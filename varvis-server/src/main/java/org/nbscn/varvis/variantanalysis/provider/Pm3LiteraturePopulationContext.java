package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.nbscn.varvis.variantanalysis.domain.*;

/** Internal projection of a replay-verified PM3 worker observation, never HTTP input.
 * patientContext names the published case, not the uploaded patient's diagnosis.
 * This builds a population-query context, not PM3 scientific qualification.
 */
public final class Pm3LiteraturePopulationContext {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    public Pm3LiteraturePopulationContext(ObjectMapper mapper) {this.mapper=mapper;adapter=new RuntimeEvidenceV2Adapter(mapper);}

    public ObjectNode build(String pmcid,String bundleSha,String caseLabel,JsonNode row,String role) {
        if(pmcid==null || !pmcid.matches("PMC[1-9][0-9]*") || bundleSha==null || !bundleSha.matches("[a-f0-9]{64}")
                || caseLabel==null || caseLabel.isBlank() || caseLabel.length()>256 || !List.of("target","partner").contains(role))
            throw new IllegalArgumentException("PM3_POPULATION_SOURCE_BINDING_REQUIRED");
        JsonNode identity=row.path("identities").path(role), disease=row.path("diseaseContext");
        if(!"LOCAL_IDENTITY_VERIFIED".equals(identity.path("status").asText())
                || !"MATCHED".equals(row.path("selectedTargetBinding").asText()))
            throw new IllegalArgumentException("PM3_POPULATION_VERIFIED_IDENTITY_REQUIRED");
        if(!"VERIFIED".equals(disease.path("qualification").asText()) || !"AR".equals(disease.path("inheritance").asText())
                || !"AFFECTED".equals(row.at("/reportedObservation/reportedAffectedStatus").asText()))
            throw new IllegalArgumentException("PM3_POPULATION_AFFECTED_AR_CONTEXT_REQUIRED");
        if(!identity.path("geneSymbol").equals(row.at("/reportedObservation/geneSymbol")))
            throw new IllegalArgumentException("PM3_POPULATION_GENE_MISMATCH");
        JsonNode allele=identity.path("allele"), checksum=identity.at("/referenceIdentity/actualChecksumReceipt");
        if(!"GRCh38".equals(allele.path("assembly").asText())
                || !allele.path("reference").asText().matches("[ACGT]")
                || !allele.path("alternate").asText().matches("[ACGT]"))
            throw new IllegalArgumentException("PM3_POPULATION_IDENTITY_SCOPE_UNSUPPORTED");
        String refSha=checksum.path("observedSha256").asText();
        if(!"VERIFIED".equals(checksum.path("hashStatus").asText()) || !refSha.matches("[a-f0-9]{64}")
                || !refSha.equals(checksum.path("expectedSha256").asText())
                || !"GRCh38".equals(checksum.path("assembly").asText()))
            throw new IllegalArgumentException("PM3_POPULATION_REFERENCE_RECEIPT_REQUIRED");
        String baseHash=FamilyDeNovoJson.sha256(allele.path("reference").asText().getBytes(StandardCharsets.US_ASCII));
        boolean matched=false;
        for(JsonNode read:identity.path("referenceReads"))
            if(read.path("contig").equals(allele.path("contig")) && read.path("start").equals(allele.path("position"))
                    && read.path("end").equals(allele.path("position"))
                    && baseHash.equals(read.path("normalizedCaseBasesSha256").asText())) matched=true;
        if(!matched) throw new IllegalArgumentException("PM3_POPULATION_REFERENCE_BASE_NOT_VERIFIED");
        var c=mapper.createObjectNode().put("schemaVersion","evidence-context-v3")
                .put("canonicalizationProfile","varvis-json-c14n-v1").put("dataClass","PRIVACY_BOUNDED_RUNTIME").put("moi","AUTOSOMAL_RECESSIVE");
        c.putObject("scope").put("analysisDomain","GERMLINE").put("diseaseModel","MENDELIAN").put("variantScope","SMALL_SEQUENCE_VARIANT");
        var variant=c.putObject("variant").put("variantType","SNV");
        for(String field:List.of("inputAllele","normalizedAllele","evidenceQueryAllele"))variant.set(field,allele.deepCopy());
        var normalization=variant.putObject("normalization").put("status","UNCHANGED")
                .put("method","LOCAL_EXACT_TRANSCRIPT_CODING_SNV_REFERENCE_CHECK").put("methodVersion","1")
                .put("referenceSequenceSha256",refSha).put("refValidation","MATCH");
        variant.put("alleleId",adapter.canonicalAlleleId(allele,normalization));
        variant.putObject("assemblyLineage").put("mappingStatus","NATIVE");
        c.putObject("gene").set("hgncId",identity.path("hgncId"));c.withObject("/gene").set("symbol",identity.path("geneSymbol"));
        c.putObject("disease").set("mondoId",disease.path("diseaseId"));
        c.withObject("/disease").put("label",disease.path("diseaseId").asText());
        var caseBinding=mapper.createObjectNode().put("pmcid",pmcid).put("bundleSha256",bundleSha).put("caseLabel",caseLabel);
        c.putObject("patientContext").put("patientContextId","patient-context-"+adapter.canonicalSha256(caseBinding))
                .put("role","PROBAND").put("affectedStatus","AFFECTED").put("sex","UNKNOWN");
        c.putObject("contextCompleteness").put("status","COMPLETE").putArray("missingFields");
        c.put("contextId",adapter.canonicalVersionedContextId(c));adapter.validateVersionedContext(c);
        return c;
    }

    public static VariantInterpretation queryView(JsonNode context) {
        var a=context.at("/variant/evidenceQueryAllele");
        var key=new NormalizedVariantKey(a.path("assembly").asText(),a.path("contig").asText(),a.path("position").asInt(),
                a.path("reference").asText(),a.path("alternate").asText());
        return new VariantInterpretation(key.canonicalId(),new VariantQcObservation(key,null,null,null,null,null,null,null,null,null),List.of(),List.of());
    }
}
