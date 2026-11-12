package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Shared acquisition only. PM4 non-repeat inference cannot grant BP3 functional eligibility. */
public final class ProteinLengthRegionFactSupply {
    private final ObjectMapper mapper;
    private final CodingTranscriptMaterialSupply sequences;
    @Nullable private final UniProtProteinRegionSupply uniprot;
    @Nullable private final CombinedProteinRegionSupply combined;
    @Nullable private final ProteinRegionDemandSupply demand;

    public ProteinLengthRegionFactSupply(ObjectMapper mapper, CodingTranscriptMaterialSupply sequences,
            @Nullable UniProtProteinRegionSupply uniprot, @Nullable CombinedProteinRegionSupply combined,
            @Nullable ProteinRegionDemandSupply demand) {
        if (uniprot == null && demand == null) throw new IllegalArgumentException("PROTEIN_REGION_LOCAL_NOT_CONFIGURED");
        this.mapper = mapper; this.sequences = Objects.requireNonNull(sequences); this.uniprot = uniprot;
        this.combined = combined; this.demand = demand;
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode(); out.set("sequenceSupply", sequences.proteinLengthChangeIdentity());
        var localUniProt=uniprot;var localCombined=combined;var localDemand=demand;
        if (localUniProt != null) out.set("uniprot", localUniProt.identity());
        if (localCombined != null) out.set("combined", localCombined.identity());
        if (localDemand != null) out.set("onDemand", localDemand.identity());
        return out;
    }
    public ObjectNode counts() { var source=demand;return source == null ? mapper.createObjectNode() : source.counts(); }
    public boolean complexAllelesEnabled() { return sequences.complexAllelesEnabled(); }
    record Facts(CodingTranscriptMaterialSupply.Material material, ObjectNode snapshot) { }
    static final String FULL_RNA_REGION_VERSION="1.0.0";
    Facts resolveFullRnaSequence(ObjectNode context,VariantInterpretation candidate,
            @Nullable Pvs1SourceQualification.SourceBoundFullRna source) {
        var out=mapper.createObjectNode();var material=sequences.resolveFullRnaLength(context,candidate,source);
        out.set("sequenceMaterial",material.snapshot());return new Facts(material,out);
    }
    /** Exact physical/translated RNA effect intervals; no DNA equivalent-edit envelope.
     * Every interval is mapped independently, so unrelated gaps do not become evidence. */
    Facts resolveFullRnaRegions(Facts sequence,boolean bp3) {
        var localUniProt=uniprot;var localCombined=combined;var localDemand=demand;
        var out=sequence.snapshot().deepCopy();var material=sequence.material();var model=material.model();
        var audit=out.putObject("proteinRegionEffects").put("schemaVersion","full-rna-protein-effect-regions-v1")
                .put("methodVersion",FULL_RNA_REGION_VERSION).put("status","UNAVAILABLE")
                .put("functionalQualificationGranted",false).put("repeatQualificationGranted",false);
        if(!material.ready()||model==null)return new Facts(material,out);
        var snapshot=material.snapshot();var association=snapshot.path("alleleAssociation");
        if(!"SOURCE_BOUND_FULL_RNA_LENGTH_CHANGE".equals(snapshot.path("associationKind").asText())
                || !"SOURCE_BOUND_RNA_INFRAME_LENGTH_CHANGE".equals(association.path("branch").asText())) {
            audit.put("reasonCode","FULL_RNA_REGION_INFRAME_MATERIAL_REQUIRED");return new Facts(material,out);
        }
        var ranges=association.path("proteinEffectReferenceResidueRanges");
        if(!ranges.isArray()||ranges.isEmpty()||ranges.size()>1000) {
            audit.put("reasonCode","FULL_RNA_REGION_EFFECT_INTERVALS_UNAVAILABLE");return new Facts(material,out);
        }
        audit.put("factsSha256",association.path("factsSha256").asText()).put("sourceOriginalSha256",association.path("sourceOriginalSha256").asText());
        var rows=audit.putArray("intervals");int previous=0;boolean complete=true;
        for(var range:ranges) {
            var a=range.path("start");var b=range.path("end");
            if(!a.isIntegralNumber()||!b.isIntegralNumber()||!a.canConvertToInt()||!b.canConvertToInt()
                    || a.asInt()<=previous||b.asInt()<a.asInt()||b.asInt()>model.path("proteinLength").asInt()) {
                audit.put("reasonCode","FULL_RNA_REGION_EFFECT_INTERVALS_INVALID");return new Facts(material,out);
            }
            previous=b.asInt();var row=rows.addObject();row.set("referenceProteinRange",range.deepCopy());
            var mapped=localUniProt==null?mapper.createObjectNode().put("status","UNAVAILABLE").put("reasonCode","PROTEIN_REGION_LOCAL_NOT_CONFIGURED")
                    :localUniProt.mapModel(model,a.asInt(),b.asInt());
            var extra=localCombined==null?mapper.createObjectNode().put("status","UNAVAILABLE").put("reasonCode","PM4_COMBINED_RESOURCE_NOT_CONFIGURED")
                    :bp3?localCombined.resolveForBp3(mapped):localCombined.resolve(mapped);
            if(localDemand!=null) {
                var configured=localDemand.identity();
                boolean ready="READY".equals(mapped.path("status").asText()) && "READY".equals(extra.path("status").asText());
                if(ready && (!configured.path("uniprotRelease").equals(mapped.at("/source/release"))
                        || !configured.path("uniprotRelease").equals(extra.at("/sources/uniprotRelease"))
                        || !configured.path("interproRelease").equals(extra.at("/sources/interproRelease"))
                        || !configured.path("interproMinor").equals(extra.at("/sources/interproMinor")))) {
                    mapped=mapper.createObjectNode().put("status","UNAVAILABLE").put("reasonCode","PROTEIN_REGION_RELEASE_MISMATCH");
                    extra=mapped.deepCopy();row.putObject("regionSupply").put("origin","LOCAL_SOURCE_REJECTED");
                }
                boolean absent="READY".equals(mapped.path("status").asText())
                        ?java.util.List.of("PM4_COMBINED_RESOURCE_NOT_CONFIGURED","PM4_COMBINED_ACCESSION_NOT_IN_SNAPSHOT").contains(extra.path("reasonCode").asText())
                        :java.util.List.of("PROTEIN_REGION_LOCAL_NOT_CONFIGURED","PROTEIN_REGION_GENE_NOT_IN_LOCAL_SNAPSHOT","PROTEIN_REGION_EXACT_MAPPING_UNAVAILABLE").contains(mapped.path("reasonCode").asText());
                if(absent) try {
                    var packet=localDemand.obtain(model);var acquired=new UniProtProteinRegionSupply(mapper,packet.directory(),packet.manifestSha());
                    mapped=acquired.mapModel(model,a.asInt(),b.asInt());
                    var additional=new CombinedProteinRegionSupply(mapper,packet.directory().resolve("combined.json"),packet.combinedSha());
                    extra=bp3?additional.resolveForBp3(mapped):additional.resolve(mapped);row.set("regionSupply",packet.acquisition());
                } catch(java.io.IOException|IllegalArgumentException unavailable) {
                    row.put("acquisitionReasonCode",unavailable instanceof RuntimeException runtime ? Bp3ProteinRegionAssessment.safeReason(runtime) : "PROTEIN_REGION_DEMAND_IO_UNAVAILABLE");
                }
            }
            mapped.set("contextId",snapshot.path("contextId").deepCopy());mapped.set("queryAllele",snapshot.path("queryAllele").deepCopy());
            mapped.put("sequenceMaterialSha256",new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(snapshot));
            row.set("proteinAnnotations",mapped);row.set("combinedRegions",extra);
            if(!"READY".equals(mapped.path("status").asText())||!"READY".equals(extra.path("status").asText()))complete=false;
        }
        audit.put("status",complete?"READY":"PARTIAL").put("reasonCode",complete?"FULL_RNA_EXACT_EFFECT_REGIONS_AVAILABLE":"FULL_RNA_EFFECT_REGION_SOURCES_INCOMPLETE");
        return new Facts(material,out);
    }

    Facts resolveSequence(ObjectNode context, VariantInterpretation candidate) {
        var out = mapper.createObjectNode();
        var material = sequences.resolveProteinLengthChange(context, candidate); out.set("sequenceMaterial", material.snapshot());
        return new Facts(material, out);
    }
    Facts resolve(ObjectNode context, VariantInterpretation candidate, boolean bp3) {
        return resolveRegions(resolveSequence(context, candidate), bp3);
    }
    Facts resolveRegions(Facts sequence, boolean bp3) {
        var localUniProt=uniprot;var localCombined=combined;var localDemand=demand;
        var out = sequence.snapshot(); var material = sequence.material();
        if (!material.ready() || material.model() == null) return new Facts(material, out);
        var mapped = localUniProt == null ? mapper.createObjectNode().put("status", "UNAVAILABLE")
                .put("reasonCode", "PROTEIN_REGION_LOCAL_NOT_CONFIGURED") : localUniProt.resolve(material);
        var extra = localCombined == null ? mapper.createObjectNode().put("status", "UNAVAILABLE")
                .put("reasonCode", "PM4_COMBINED_RESOURCE_NOT_CONFIGURED") : bp3 ? localCombined.resolveForBp3(mapped) : localCombined.resolve(mapped);
        if (localDemand != null) {
            var supplied = bp3 ? localDemand.resolveForBp3(material, mapped, extra) : localDemand.resolve(material, mapped, extra);
            mapped = supplied.annotations(); extra = supplied.combined(); out.set("regionSupply", supplied.acquisition());
        }
        out.set("proteinAnnotations", mapped); out.set("combinedRegions", extra);
        return new Facts(material, out);
    }
}
