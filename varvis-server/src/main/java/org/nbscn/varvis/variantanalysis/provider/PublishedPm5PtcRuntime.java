package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.springframework.lang.Nullable;

/** Applies the published direct genomic PTC rule to a mechanically verified short allele. */
public final class PublishedPm5PtcRuntime {
    static final String VERSION = "1.0.0";
    private static final String PM5 = "ACMG_AMP_2015:PM5";
    private static final String PVS1 = "ACMG_AMP_2015:PVS1";
    private static final int MAX_ANCHORED_ALLELE_LENGTH = 100;

    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    private final Pvs1TranscriptMaterialSupply supply;

    public PublishedPm5PtcRuntime(ObjectMapper mapper, CodingTranscriptMaterialSupply supply) {
        this.mapper = mapper;
        this.wiring = new RuntimeEvidenceV2ProviderWiring(mapper, new RuntimeEvidenceV2Adapter(mapper));
        this.supply = new Pvs1TranscriptMaterialSupply(mapper, supply);
    }

    record Result(ObjectNode pm5, @Nullable ObjectNode pvs1, ObjectNode facts) { }

    ObjectNode identity() {
        var result = mapper.createObjectNode().put("methodVersion", VERSION)
                .put("sourceTableSha256", PublishedPm5PtcTable.identity());
        result.set("transcriptSupply", supply.identity());
        return result;
    }

    Result evaluate(SelectedEvidencePreparation selected, String candidate, String hypothesis,
                    @Nullable ReviewedRuleBundle.FullRnaPrepared fullRna) {
        try {
            var projection = selected.legacyProjection(candidate, hypothesis);
            var context = projection.context();
            var formal = selected.context(candidate, hypothesis);
            var scope = PublishedPm5PtcTable.select(context);
            if (scope == null || !scope.matchesContext(formal))
                return pending("PM5_PTC_EXPERT_RULE_CONTEXT_UNRESOLVED", mapper.createObjectNode());
            var facts = facts(scope, projection.bindingSha256()).put("contextId", formal.path("contextId").asText());
            var tx = wiring.selectedVepConsequence(context, projection.queryCandidate());
            if (tx == null || !scope.matchesTranscript(tx.getVersionedTranscriptId()))
                return pending("PM5_PTC_TRANSCRIPT_IDENTITY_UNRESOLVED", facts);
            facts.put("selectedTranscript", tx.getVersionedTranscriptId());
            if (!"GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                    || !"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()))
                return pending("PM5_PTC_NATIVE_ALLELE_IDENTITY_REQUIRED", facts);
            if (!PublishedPm5PtcTable.directAnnotation(SelectedCriterionRouting.consequenceTerms(tx.getConsequence())))
                return pending("PM5_PTC_CONSEQUENCE_MECHANISM_UNRESOLVED", facts);
            if (fullRna != null && fullRna.targetsFullRna())
                return pending("PM5_PTC_RNA_MECHANISM_REQUIRES_REVIEW", facts);
            var material = supply.resolve(context, projection.queryCandidate());
            var model = material.model();
            if (model == null) return pending(material.audit().path("reasonCode").asText("PM5_PTC_SEQUENCE_UNAVAILABLE"), facts);
            if (!tx.getVersionedTranscriptId().equals(model.path("transcript").asText())
                    || !exactTranscript(scope, model))
                return pending("PM5_PTC_TRANSCRIPT_SEQUENCE_UNVERIFIED", facts);
            var stop = mapper.createObjectNode();
            var mechanics = mapper.createObjectNode();
            mechanics.putObject("frameshift").put("maximumAlleleLengthIncludingAnchor", MAX_ANCHORED_ALLELE_LENGTH);
            String transcript = model.path("transcript").asText();
            boolean snv = "SNV".equals(context.at("/variant/variantType").asText());
            String issue = snv
                    ? new TruncatingNonsenseResolver(model, mechanics, transcript, "", "", false).deriveGenomicStop(context, tx, stop)
                    : new TruncatingFrameshiftResolver(model, mechanics, transcript, "", "").deriveGenomicStop(context, tx, stop);
            if (issue != null) return pending(issue, facts);
            return evaluateStop(scope, stop, snv, facts);
        } catch (RuntimeException invalid) {
            String issue = invalid.getMessage();
            return pending(issue != null && issue.matches("[A-Z][A-Z0-9_]{2,120}") ? issue : "PM5_PTC_EVALUATION_FAILED",
                    mapper.createObjectNode());
        }
    }

    private static boolean exactTranscript(PublishedPm5PtcTable.Scope scope, JsonNode model) {
        var cds = model.path("cdsSequence").asText();
        if (cds.length() != scope.nativeStopPosition() * 3
                || !scope.refseqCdsSha().equals(CodingTranscriptMaterialSupply.sha(cds))) return false;
        var exons = model.path("exons");
        if (!exons.isArray()) return false;
        int senseEnd = (scope.nativeStopPosition() - 1) * 3;
        for (var row : scope.rows()) {
            var exon = exons.path(row.exon() - 1);
            if (exon.path("ordinal").asInt() != row.exon()
                    || Math.max(1, exon.path("cdsStart").asInt()) != row.cdsStart()
                    || Math.min(senseEnd, exon.path("cdsEnd").asInt()) != row.cdsEnd()) return false;
        }
        return true;
    }

    private ObjectNode facts(PublishedPm5PtcTable.Scope scope, String projection) {
        return mapper.createObjectNode().put("schemaVersion", "published-pm5-ptc-facts-v1")
                .put("specification", scope.specification()).put("transcript", scope.transcript())
                .put("hgncId", scope.hgncId()).put("mondoId", scope.disease()).put("moi", scope.inheritance())
                .put("sourceUrl", scope.specificationUrl()).put("releaseSha256", scope.releaseSha())
                .put("pm5ClausePointer", scope.pm5Pointer()).put("pvs1ClausePointer", scope.pvs1Pointer())
                .put("tableUrl", scope.tableUrl()).put("tableSha256", scope.tableSha())
                .put("tableSheet", scope.tableSheet()).put("projectionSha256", projection)
                .put("weightBasis", "STOP_CODON_EXON");
    }

    Result evaluateStop(PublishedPm5PtcTable.Scope scope, JsonNode stop, boolean snv, ObjectNode facts) {
        int variantExon = stop.path("exonOrdinal").asInt();
        int exon = snv ? variantExon : stop.path("ptcExonOrdinal").asInt();
        int start = stop.path("ptcStart").asInt();
        int end = stop.path("ptcEnd").asInt();
        if (start <= 0 || end != start + 2 || (start - 1) % 3 != 0)
            return pending("PM5_PTC_STOP_POSITION_UNRESOLVED", facts);
        int position = (start - 1) / 3 + 1;
        var row = scope.stopRow(exon, position);
        int refStart = snv ? start : stop.path("ptcReferenceStart").asInt();
        int refEnd = snv ? end : stop.path("ptcReferenceEnd").asInt();
        if (row == null || refStart < row.cdsStart() || refEnd != refStart + 2 || refEnd > row.cdsEnd()
                || !snv && stop.path("ptcReferenceMiddle").asInt() != refStart + 1)
            return pending("PM5_PTC_STOP_EXON_UNRESOLVED", facts);
        facts.put("variantExon", variantExon).put("stopExon", exon).put("stopExonLabel", row.exonLabel())
                .put("stopProteinPosition", position).put("stopReferenceCdsStart", refStart).put("tableRow", row.id());
        if (variantExon != exon && scope.rows().stream().noneMatch(r -> r.exon() == variantExon
                && r.pvs1Applicable() && r.firstStopPosition() == 1 && r.lastStopPosition() == scope.nativeStopPosition() - 1))
            return pending("PM5_PTC_VARIANT_EXON_PVS1_SCOPE_UNRESOLVED", facts);
        if (!row.pvs1Applicable()) {
            facts.put("pvs1Status", "NOT_APPLICABLE");
            return new Result(decision(PM5, "NOT_EVALUATED", "NOT_APPLICABLE", "NONE", "PM5_PTC_PVS1_EXCLUDED_BY_RULE"),
                    decision(PVS1, "NOT_EVALUATED", "NOT_APPLICABLE", "NONE", "PVS1_PUBLISHED_PTC_RULE_EXCLUDED"), facts);
        }
        var pvs1 = decision(PVS1, "MET", "APPLICABLE", "VERY_STRONG", "PVS1_PUBLISHED_GENOMIC_PTC_RULE_MET");
        facts.put("pvs1Status", "MET").put("pvs1Strength", "VERY_STRONG").put("pm5Strength", row.pm5Strength());
        var pm5 = "NONE".equals(row.pm5Strength())
                ? decision(PM5, "NOT_MET", "APPLICABLE", "NONE", "PM5_PTC_NO_WEIGHT_IN_PUBLISHED_STOP_EXON")
                : decision(PM5, "MET", "APPLICABLE", row.pm5Strength(), "PM5_PUBLISHED_GENOMIC_PTC_RULE_MET");
        return new Result(pm5, pvs1, facts);
    }

    private Result pending(String reason, ObjectNode facts) {
        return new Result(decision(PM5, "NOT_EVALUATED", "UNKNOWN", "NONE", reason), null, facts);
    }

    private ObjectNode decision(String criterion, String status, String applicability, String strength, String reason) {
        var result = mapper.createObjectNode().put("criterionId", criterion).put("appliedStrength", strength);
        result.putObject("criterionApplicability").put("status", applicability);
        result.putObject("evaluationResult").put("status", status).put("reasonCode", reason);
        return result;
    }
}
