package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.springframework.lang.Nullable;
import static org.nbscn.varvis.variantanalysis.provider.Pm3LiteratureScoring.*;

/** Bounded PM3 source-condition compiler. No caller approval flag or new scoring mathematics. */
final class Pm3PublishedConditions {
    static final String VERSION = "pm3-published-conditions-4";
    private static final String CASE_ELIGIBILITY = "SVI_WITH_RELEASED_CASE_ELIGIBILITY";
    private static final Pattern CELL_ROW = Pattern.compile("^(R[1-9][0-9]*)C[1-9][0-9]*$");
    private static final Map<String,String> PHENOTYPE_FIELDS = Map.of(
            "ABSENT_OR_SEVERELY_DECREASED_ROD_ERG", "reportedClinicalAssay",
            "CONGENITAL_NYCTALOPIA_OR_NIGHT_BLINDNESS", "reportedSymptom",
            "LCA_OR_EARLY_ONSET_RETINAL_DYSTROPHY", "diagnosis");
    private static final String SVI = "CLINGEN_SVI_PM3_2019_V1";
    private static final String SVI_SOURCE = "https://www.clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf";
    private static final String SVI_SOURCE_SHA = "17877e54edd58decfe2c374b3d3e2103e52752ea808d48eb7147d007f1ca4c70";
    private static final String DELEGATION = "Applicable as described in SVI recommendations for in trans criterion";
    private static final String PHASE = "* Follow SVI guidance for PM3 ("
        + "[https://clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf]"
        + "(https://clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf)). "
        + "* Parental testing, or another appropriate molecular method (such as cloning each allele separately followed by sequencing), "
        + "must have been performed in order to confirm that the variants are in trans if the patient is compound heterozygous.";
    private static final String APPROVAL = "Any additional variants used to support the classification of the variant under assessment MUST have been "
        + "previously classified by the CCDS VCEP, using these criteria, and the classification MUST be approved. In other words, "
        + "when awarding points for PM3 for a compound heterozygote, the second variant MUST have been assessed by the VCEP and "
        + "an appropriate classification approved. As noted in the SVI guidance, circularity must be avoided. Variant A can support "
        + "the classification of Variant B, or vice versa but NOT both.";
    private static final Set<String> CLAUSE_FIELDS = Set.of("_uniqueProp", "additionalComments", "instructionsToUse", "applicability", "baseStrength",
        "defaultStrength", "disease", "evidenceCategory", "gene", "geneType", "label", "ns", "originalACMGSummary", "sepioID", "specificationType", "status", "strengthDescriptor");
    private static final Set<String> DESCRIPTOR_FIELDS = Set.of("applicability", "id", "instructionsToUse", "specificationType", "status", "strength", "strengthSepioID", "text", "type");
    private final ObjectMapper json;
    private final Map<String, JsonNode> rawCache = new HashMap<>();

    Pm3PublishedConditions(ObjectMapper json) { this.json=json; }
    static ObjectNode executionIdentity(ObjectMapper json) {
        return json.createObjectNode().put("version", VERSION).put("mathPolicy", SVI)
            .put("sourceFormat", "PINNED_RELEASED_CSPEC_RAW_AND_ORIGINAL_SCV")
            .put("program", "EXPLICIT_SVI_PHASE_AND_APPROVED_PARTNER");
    }
    static final class Decision {
        private final String rowSha;
        private final Phase phase;
        private final List<Requirement> requirements;
        private final ObjectNode display;
        private Decision(String rowSha, Phase phase, List<Requirement> requirements, ObjectNode display) {
            this.rowSha=rowSha; this.phase=phase; this.requirements=List.copyOf(requirements); this.display=display.deepCopy();
        }
        boolean binds(ObjectMapper json, JsonNode row) { return rowSha.equals(Pm3SourceDigest.sha256(json,row)); }
        Phase phase() { return phase; }
        List<Requirement> requirements() { return requirements; }
        ObjectNode display() { return display.deepCopy(); }
    }
    String rowKey(JsonNode row) { return Pm3SourceDigest.sha256(json,row); }

    Decision assess(JsonNode observations, JsonNode row, JsonNode context) {
        return assess(observations, row, context, json.missingNode());
    }

    Decision assess(JsonNode observations, JsonNode row, JsonNode context, JsonNode caseRow) {
        var view=json.createObjectNode().put("schemaVersion", VERSION).put("mathPolicy", SVI);
        var checks=view.putArray("requirements"); var failures=new ArrayList<Requirement>();
        Phase phase=Phase.UNKNOWN;
        try {
            var policy=row.path("policySelection"); var matches=policy.path("matches");
            require("pm3-specification-context-v2".equals(policy.path("schemaVersion").asText()) && matches.isArray() && matches.size()<=1,
                    "PM3_PUBLISHED_SOURCE_SELECTION_REQUIRED");
            JsonNode entity=null;
            String family;
            if(matches.isEmpty()) {
                requireGeneral(policy,row,context);
                family="SVI_SOURCE_REVIEWED_GENERAL";
                view.set("source",policy.path("generalPolicy").deepCopy());
                view.set("catalogScope",policy.path("source").deepCopy());
            } else {
            var match=matches.get(0); var source=match.path("source"); String sha=source.path("rawSha256").asText();
            var raw=original(observations.path("specificationOriginals"), sha, 8_000_000);
            String cacheKey=sha+"#"+source.path("jsonPointer").asText();
            entity=rawCache.get(cacheKey);
            if(entity==null) {
                var strict=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                entity=strict.readTree(raw).at(source.path("jsonPointer").asText());
                if(entity==null || !entity.isObject())throw new IllegalArgumentException("PM3_PUBLISHED_ENTITY_POINTER_INVALID");
                rawCache.put(cacheKey, entity);
            }
            var states=new ArrayList<String>();
            for(var state:entity.at("/entContent/states")) if(state.path("current").asBoolean()) states.add(state.path("name").asText());
            boolean reviewed = match.path("reviewedCaseRule").isObject();
            String release = entity.at("/entContent/version").asText();
            String key = match.path("specificationKey").asText();
            require(states.equals(List.of("Released")) && (reviewed
                    ? key.startsWith(entity.path("entId").asText()+"@") && key.contains("@") && versionEquivalent(release,key.substring(key.indexOf('@')+1))
                    : (entity.path("entId").asText()+"@"+release).equals(key)),
                    "PM3_PUBLISHED_RELEASE_OR_VERSION_MISMATCH");
            var clause=entity.at(match.path("clausePointer").asText());
            require(clause.isObject() && clause.equals(match.path("clause"))
                    && Pm3SourceDigest.sha256(json,clause).equals(match.path("clauseSha256").asText()), "PM3_PUBLISHED_CLAUSE_PIN_MISMATCH");
            requireScope(entity, clause.path("entContent"), match, row, context);
            view.put("specificationKey",match.path("specificationKey").asText());
            view.putObject("source").put("rawSha256",sha).put("entityPointer",source.path("jsonPointer").asText())
                .put("clausePointer",match.path("clausePointer").asText()).put("clauseSha256",match.path("clauseSha256").asText());
            if(reviewed) {
                verifyReviewedRelease(entity, observations, match);
                family=CASE_ELIGIBILITY;
                view.set("publishedTables", match.path("reviewedAttachments").deepCopy());
                view.withObject("/source").set("sourceUrl", source.path("sourceUrl"));
            } else family=compile(entity,clause.path("entContent"));
            }
            view.put("family",family);
            require(("NOT_APPLICABLE".equals(family)?"NOT_APPLICABLE":"SELECTED").equals(policy.path("status").asText()),
                    "PM3_PUBLISHED_SELECTION_DISPOSITION_CONFLICT");
            if("NOT_APPLICABLE".equals(family)) {
                failures.add(new Requirement("PM3_PUBLISHED_CLAUSE_NOT_APPLICABLE","NOT_APPLICABLE"));
            } else {
                require(SVI.equals(policy.path("policy").asText()),"PM3_PUBLISHED_MATH_POLICY_MISMATCH");
                var reported=row.path("reportedObservation");
                phase=phase(reported);
                String evidence=phaseEvidenceStatus(reported,phase);
                String phaseStatus=switch(evidence) {
                    case "PHASE_CONFLICT" -> "CONFLICT";
                    case "REPORTED_UNDETERMINED" -> "UNKNOWN_PHASE_WEIGHT";
                    case "PHASE_ORIGINAL_MISSING" -> "MISSING_INFORMATION";
                    case "PHASE_NOT_REQUIRED" -> "SATISFIED";
                    default -> "SOURCE_PHASE_ESTABLISHED";
                };
                var p=checks.addObject().put("conditionId","QUALIFIED_PHASE_METHOD").put("status",phaseStatus)
                    .put("required","Source-bound phase evidence or an explicit unphased observation for this pair")
                    .put("observed",phase.name()).put("reported",reported.path("phase").asText())
                    .put("phaseEvidenceStatus",evidence);
                var phaseSources=p.putArray("sourceFacts");
                for(var f:row.at("/reportedObservation/facts"))if(f.has("parentTest") || "phaseText".equals(f.path("field").asText())
                        || Set.of("paternal allele","maternal allele").contains(f.path("columnHeading").asText().strip().toLowerCase(Locale.ROOT))) {
                    var ref=phaseSources.addObject().put("field",f.path("field").asText());ref.set("citation",f.path("citation").deepCopy());
                }
                for(var citation:row.at("/reportedObservation/phaseAssayProof/references")) {
                    phaseSources.addObject().put("field","parentalMolecularTesting").set("citation",citation.deepCopy());
                }
                if(phase==Phase.CONFLICT) failures.add(new Requirement("PM3_PHASE_CONFLICT","CONFLICT"));
                if("PHASE_ORIGINAL_MISSING".equals(evidence))
                    failures.add(new Requirement("PM3_PHASE_ORIGINAL_MISSING","MISSING_INFORMATION"));
                if(phase!=Phase.CIS && "SVI_PHASE_AND_APPROVED_PARTNER".equals(family)
                        && !"REPORTED_HOMOZYGOUS".equals(row.at("/reportedObservation/kind").asText())) {
                    if(entity==null)throw new IllegalArgumentException("PM3_PUBLISHED_APPROVAL_DOCUMENT_REQUIRED");
                    var approval=approvedPartner(entity, observations, row, context); checks.add(approval);
                    if(!"SATISFIED".equals(approval.path("status").asText())) failures.add(new Requirement(approval.path("reasonCode").asText(),approval.path("status").asText()));
                }
                if(CASE_ELIGIBILITY.equals(family)) {
                    var requirements=caseRequirements(observations,row,caseRow,phase);
                    for(var condition:requirements) {
                        checks.add(condition);
                        if(!"SATISFIED".equals(condition.path("status").asText()))
                            failures.add(new Requirement(condition.path("reasonCode").asText(),condition.path("status").asText()));
                    }
                }
            }
        } catch(Exception invalid) {
            String reason=invalid.getMessage();
            if(reason==null || !reason.matches("PM3_[A-Z0-9_]{1,140}"))reason="PM3_PUBLISHED_SOURCE_VALIDATION_FAILED";
            String status=reason.endsWith("_REQUIRED")?"MISSING_INFORMATION":reason.endsWith("_UNSUPPORTED")?"RULE_NOT_IMPLEMENTED":"CONFLICT";
            failures.add(new Requirement(reason,status));
            checks.addObject().put("conditionId","PUBLISHED_SOURCE_AND_SCOPE").put("status",status).put("reasonCode",reason)
                .put("required","Exact released source bytes, original clause, scope and supported complete condition form");
        }
        view.put("status",failures.isEmpty()?"QUALIFIED":failures.stream().anyMatch(r->"CONFLICT".equals(r.status()))?"CONFLICT":"NOT_QUALIFIED");
        return new Decision(rowKey(row),phase,failures,view);
    }

    private static void requireGeneral(JsonNode policy,JsonNode row,JsonNode context) {
        var source=policy.path("generalPolicy");var catalog=policy.path("source");
        require(SVI.equals(source.path("policy").asText()) && "1.0".equals(source.path("version").asText())
                && SVI_SOURCE.equals(source.path("sourceUrl").asText()) && SVI_SOURCE_SHA.equals(source.path("sourceRawSha256").asText())
                && "SOURCE_REVIEWED_FIXED_SVI_IMPLEMENTATION".equals(source.path("basis").asText()),"PM3_GENERAL_SVI_SOURCE_REQUIRED");
        require("PM3_GENERAL_WITH_NO_MATCH_IN_LOCAL_SNAPSHOT".equals(policy.path("reasonCode").asText())
                && "PINNED_RELEASED_LOCAL_SNAPSHOT".equals(catalog.path("scope").asText())
                && catalog.path("catalogRawSha256").asText().matches("[a-f0-9]{64}")
                && catalog.path("worldwideAbsenceClaim").isBoolean() && !catalog.path("worldwideAbsenceClaim").asBoolean()
                && catalog.path("priorBa1ApprovalInherited").isBoolean() && !catalog.path("priorBa1ApprovalInherited").asBoolean(),
                "PM3_GENERAL_CATALOG_SCOPE_REVIEW_REQUIRED");
        require("LOCAL_IDENTITY_VERIFIED".equals(row.at("/identities/target/status").asText())
                && context.at("/gene/hgncId").equals(row.at("/identities/target/hgncId"))
                && context.at("/disease/mondoId").equals(row.at("/diseaseContext/diseaseId"))
                && "VERIFIED".equals(row.at("/diseaseContext/qualification").asText())
                && "AR".equals(row.at("/diseaseContext/inheritance").asText()),"PM3_GENERAL_CONTEXT_MISMATCH");
    }

    private void requireScope(JsonNode entity, JsonNode clause, JsonNode match, JsonNode row, JsonNode context) {
        String hgnc=context.at("/gene/hgncId").asText(), gene=row.at("/identities/target/geneSymbol").asText();
        String disease=context.at("/disease/mondoId").asText();
        require(!gene.isBlank() && hgnc.equals(row.at("/identities/target/hgncId").asText()) && disease.equals(row.at("/diseaseContext/diseaseId").asText())
                && "AR".equals(row.at("/diseaseContext/inheritance").asText()),"PM3_PUBLISHED_CONTEXT_MISMATCH");
        boolean geneBound=false;
        for(var g:entity.at("/ld/Gene")) if(gene.equals(g.at("/entContent/HGNC/symbol").asText()) && hgnc.equals(g.at("/entContent/HGNC/hgnc_id").asText()))geneBound=true;
        require(geneBound && contains(clause.path("gene"),gene),"PM3_PUBLISHED_GENE_MISMATCH");
        var association=entity.at(match.path("scopePointer").asText());
        require(disease.equals(association.path("preferredMondoId").asText()),"PM3_PUBLISHED_DISEASE_MISMATCH");
        if(clause.has("disease"))require(clause.path("disease").isArray() && (clause.path("disease").isEmpty() || contains(clause.path("disease"),disease)),"PM3_PUBLISHED_DISEASE_MISMATCH");
        String moi=association.path("preferredModeOfInheritance").asText();
        require(moi.isBlank() || Set.of("Autosomal recessive inheritance","Autosomal Recessive").contains(moi),"PM3_PUBLISHED_INHERITANCE_MISMATCH");
        for(var m:association.path("modesOfInheritance"))require(Set.of("Autosomal recessive inheritance","Autosomal Recessive").contains(m.path("modeOfInheritance").asText()),"PM3_PUBLISHED_INHERITANCE_MISMATCH");
        // The parent gene declaration owns the exact transcript restriction.
        String scopePointer=match.path("scopePointer").asText(); int at=scopePointer.lastIndexOf("/diseases/");
        require(at>0,"PM3_PUBLISHED_SCOPE_POINTER_INVALID");
        var g=entity.at(scopePointer.substring(0,at)); require(gene.equals(g.path("gene").asText()),"PM3_PUBLISHED_GENE_MISMATCH");
        var tx=new HashSet<String>(); if(g.path("preferredTranscript").isTextual())tx.add(g.path("preferredTranscript").asText());
        g.path("transcripts").forEach(t->tx.add(t.asText()));
        if(match.path("reviewedCaseRule").isObject()) {
            require(tx.size()==1 && "AUTOSOMAL_RECESSIVE".equals(context.path("moi").asText())
                    && gene.equals(context.at("/gene/symbol").asText())
                    && Set.of("Autosomal recessive inheritance","Autosomal Recessive").contains(moi),"PM3_PUBLISHED_EXACT_SCOPE_REQUIRED");
            var roles = "REPORTED_HOMOZYGOUS".equals(row.at("/reportedObservation/kind").asText())?List.of("target"):List.of("target","partner");
            var bindings=row.path("publishedTranscriptBinding");
            require(bindings.isObject() && bindings.size()==roles.size(),"PM3_PUBLISHED_TRANSCRIPT_BINDING_REQUIRED");
            for(var role:roles) {
                var binding=bindings.path(role); var identity=row.at("/identities/"+role);
                String ruleTx=binding.path("ruleTranscript").asText();
                boolean exact="EXACT_SOURCE_TRANSCRIPT".equals(binding.path("method").asText()) && ruleTx.equals(binding.path("reportedTranscript").asText());
                boolean resolved="BOTH_VERSIONS_RESOLVE_TO_EXACT_GRCH38_ALLELE".equals(binding.path("method").asText())
                        && (binding.path("gencodeRecordSha256").asText().matches("[a-f0-9]{64}") || binding.path("sourcePins").size()==3);
                require(tx.contains(ruleTx) && (exact || resolved) && hgnc.equals(identity.path("hgncId").asText())
                        && gene.equals(identity.path("geneSymbol").asText()) && "LOCAL_IDENTITY_VERIFIED".equals(identity.path("status").asText())
                        && binding.path("reportedTranscript").equals(identity.path("transcript")) && binding.path("coding").equals(identity.path("coding"))
                        && Pm3LiteratureAssessment.sameAllele(binding.path("allele"),identity.path("allele")),"PM3_PUBLISHED_TRANSCRIPT_MISMATCH");
            }
        } else require(tx.isEmpty() || tx.contains(row.at("/identities/target/transcript").asText()),"PM3_PUBLISHED_TRANSCRIPT_MISMATCH");
    }

    private void verifyReviewedRelease(JsonNode entity,JsonNode observations,JsonNode match) throws IOException {
        var requested=match.path("reviewedCaseRule");
        var source=match.path("source");
        try(var stream=Pm3PublishedConditions.class.getResourceAsStream("/acmg/pm3-published-case-rules.json")) {
            require(stream!=null,"PM3_PUBLISHED_CASE_RULE_REQUIRED");
            if(stream==null)throw new IOException("PM3_PUBLISHED_CASE_RULE_REQUIRED");
            var registered=json.readTree(stream);
            boolean known=false;
            for(var rule:registered.path("rules"))if(rule.equals(requested))known=true;
            require(known && "pm3-published-case-rules-v1".equals(registered.path("schemaVersion").asText()),
                    "PM3_PUBLISHED_CASE_RULE_UNSUPPORTED");
        }
        require(requested.path("specificationKey").equals(match.path("specificationKey"))
                && requested.path("releaseSha256").equals(source.path("rawSha256"))
                && requested.path("apiVersion").equals(entity.at("/entContent/version"))
                && requested.path("pm3Pointer").equals(match.path("clausePointer"))
                && CASE_ELIGIBILITY.equals(match.at("/program/family").asText()),"PM3_PUBLISHED_CASE_RULE_BINDING_MISMATCH");
        for(var purpose:List.of("casePoints","classificationPoints")) {
            var expected=requested.path("attachments").path(purpose);
            var attachment=match.path("reviewedAttachments").path(purpose);
            var nativeFile=entity.at(expected.path("pointer").asText());
            String id=expected.path("fileId").asText();
            require(expected.path("fileId").equals(nativeFile.path("entId")) && expected.path("fileId").equals(attachment.path("fileId"))
                    && expected.path("sha256").equals(attachment.path("sha256"))
                    && expected.path("locator").equals(attachment.path("locator"))
                    && nativeFile.path("rev").equals(attachment.path("revision"))
                    && nativeFile.at("/entContent/fileName").equals(attachment.path("name"))
                    && ("https://cspec.genome.network/cspec/File/id/"+id+"/data").equals(attachment.path("url").asText()),
                    "PM3_PUBLISHED_ATTACHMENT_BINDING_MISMATCH");
            var pdf=original(observations.path("specificationAttachmentOriginals"),attachment.path("sha256").asText(),2_000_000);
            require(pdf.length==nativeFile.at("/entContent/size").asInt(-1),"PM3_PUBLISHED_ATTACHMENT_SIZE_MISMATCH");
        }
    }

    private List<ObjectNode> caseRequirements(JsonNode observations,JsonNode row,JsonNode caseRow,Phase phase) {
        var phenotype=row.path("publishedPhenotype");
        require(phenotype.path("caseLabel").isTextual() && phenotype.path("caseLabel").equals(caseRow.path("caseLabel"))
                        && "RELEASED_PHENOTYPE_ELIGIBILITY_NOT_DIAGNOSIS".equals(phenotype.path("method").asText()),
                "PM3_PUBLISHED_CASE_PHENOTYPE_BINDING_MISMATCH");
        var routes = new HashSet<String>();
        var routeStates = new HashSet<String>();
        for(var route:phenotype.path("routes")) {
            String id=route.path("route").asText();
            require(PHENOTYPE_FIELDS.containsKey(id) && routes.add(id) && route.path("sourceFacts").isArray(),
                    "PM3_PUBLISHED_CASE_PHENOTYPE_BINDING_MISMATCH");
            var states = new HashSet<String>();
            for(var source:route.path("sourceFacts")) {
                var fact=source.path("fact");
                boolean reported=false;
                for(var original:row.at("/reportedObservation/facts"))if(original.equals(fact))reported=true;
                require(reported && fact.path("field").asText().equals(PHENOTYPE_FIELDS.get(id))
                                && sourceCaseCell(caseRow, fact),"PM3_PUBLISHED_CASE_PHENOTYPE_BINDING_MISMATCH");
                String state=source.path("qualification").asText();
                require(Set.of("VERIFIED","FAILED","UNKNOWN").contains(state),"PM3_PUBLISHED_CASE_PHENOTYPE_BINDING_MISMATCH");
                if(!"UNKNOWN".equals(state))states.add(state);
            }
            String outcome=states.size()>1?"CONFLICT":states.stream().findFirst().orElse("UNKNOWN");
            require(outcome.equals(route.path("qualification").asText()),"PM3_PUBLISHED_CASE_PHENOTYPE_BINDING_MISMATCH");
            routeStates.add(outcome);
        }
        require(routes.equals(PHENOTYPE_FIELDS.keySet()),"PM3_PUBLISHED_CASE_PHENOTYPE_BINDING_MISMATCH");
        String phenotypeStatus=routeStates.contains("CONFLICT")
                || "CONFLICT".equals(caseRow.at("/primarySourceQualification/qualification").asText())
                || "CONFLICT".equals(row.at("/reportedObservation/reportedAffectedStatus").asText())?"CONFLICT":
                routeStates.contains("VERIFIED")?"VERIFIED":routeStates.equals(Set.of("FAILED"))?"FAILED":"UNKNOWN";
        require(phenotypeStatus.equals(phenotype.path("qualification").asText())
                        && (!"VERIFIED".equals(phenotypeStatus)
                        || "VERIFIED".equals(caseRow.at("/primarySourceQualification/qualification").asText())
                        && "AFFECTED".equals(row.at("/reportedObservation/reportedAffectedStatus").asText())),
                "PM3_PUBLISHED_CASE_PHENOTYPE_BINDING_MISMATCH");
        var phenotypeCheck=check("PUBLISHED_CASE_PHENOTYPE", "At least one phenotype route in the released PM3 clause",
                phenotypeStatus,phenotype.path("reasonCode").asText());
        phenotypeCheck.set("observed",phenotype.path("routes").deepCopy());
        var checks=new ArrayList<ObjectNode>();checks.add(phenotypeCheck);
        if(phase==Phase.CIS)return checks;
        var match=row.at("/policySelection/matches/0");
        boolean homo="REPORTED_HOMOZYGOUS".equals(row.at("/reportedObservation/kind").asText());
        for(var role:homo?List.of("target"):List.of("target","partner")) {
            var classification=row.path("publishedClassifications").path(role);
            var assertion=classification.path("assertion");
            String qualification=classification.path("qualification").asText();
            if("VERIFIED".equals(qualification)) {
                var binding=row.path("publishedTranscriptBinding").path(role);
                require(assertion.path("specificationKey").equals(match.path("specificationKey"))
                        && assertion.path("version").equals(match.at("/reviewedCaseRule/apiVersion"))
                        && assertion.path("method").equals(match.at("/reviewedCaseRule/assertionMethod"))
                        && assertion.path("transcript").equals(binding.path("ruleTranscript"))
                        && Pm3LiteratureAssessment.sameAllele(assertion.path("sourceAllele"),binding.path("allele")),
                        "PM3_SAME_RELEASE_CLASSIFICATION_BINDING_MISMATCH");
                original(observations.path("partnerSourceOriginals"),assertion.path("sourceSha256").asText(),4*1024*1024);
                if(Set.of("Benign","Likely benign").contains(assertion.path("sourceClassification").asText()))qualification="FAILED";
            }
            var check=check("SAME_RELEASE_"+role.toUpperCase(Locale.ROOT)+"_CLASSIFICATION",
                    "Exact allele classified under the same released specification",qualification,
                    "FAILED".equals(qualification)?"PM3_SAME_RELEASE_CLASSIFICATION_NOT_ELIGIBLE":
                        classification.path("reasonCode").asText("PM3_SAME_RELEASE_CLASSIFICATION_REQUIRED"));
            if(assertion.isObject()) {
                var details=check.putObject("observed");
                for(String field:List.of("scv","sourceClassification","sourceSha256","sourceUrl","sourceLocator","method","methodLocator","version","transcript"))
                    details.set(field,assertion.path(field).deepCopy());
            }
            checks.add(check);
        }
        if(!homo) {
            var q=row.path("partnerQualification");
            String qualification=q.path("independentClassification").asText();
            var reason="VERIFIED".equals(qualification)?"PM3_PARTNER_TARGET_EXCLUSION_VERIFIED":
                    q.path("issues").path(0).asText("PM3_PARTNER_TARGET_EXCLUSION_SOURCES_REQUIRED");
            checks.add(check("PUBLISHED_TARGET_INDEPENDENT_PARTNER","Partner classification after removing target-dependent evidence",
                    qualification,reason));
        }
        return checks;
    }

    private static boolean sourceCaseCell(JsonNode caseRow,JsonNode fact) {
        var citation=fact.path("citation");
        var cell=CELL_ROW.matcher(citation.path("cell").asText());
        if(!cell.matches() || !"SUPPLEMENT_CELL".equals(citation.path("sourceKind").asText())
                || !citation.path("quote").isTextual() || !citation.path("quote").equals(fact.path("value")))return false;
        int matches=0;
        for(var anchor:caseRow.path("anchors")) {
            var sourceCell=CELL_ROW.matcher(anchor.path("cell").asText());
            if(!sourceCell.matches() || !cell.group(1).equals(sourceCell.group(1))
                    || !"SUPPLEMENT_CELL".equals(anchor.path("sourceKind").asText()))continue;
            boolean same=true;
            for(var key:List.of("bundleSha256","sourceSha256","sheet"))
                same&=citation.path(key).isTextual() && !citation.path(key).asText().isBlank()
                        && citation.path(key).equals(anchor.path(key));
            if(same)matches++;
        }
        return matches==1;
    }

    private ObjectNode check(String id,String required,String qualification,String reason) {
        String status=switch(qualification) {
            case "VERIFIED" -> "SATISFIED";
            case "FAILED" -> "REQUIREMENT_NOT_MET";
            case "CONFLICT" -> "CONFLICT";
            default -> "MISSING_INFORMATION";
        };
        return json.createObjectNode().put("conditionId",id).put("status",status).put("reasonCode",reason).put("required",required);
    }
    private static String compile(JsonNode entity, JsonNode clause) {
        require("PM3".equals(clause.path("label").asText()),"PM3_PUBLISHED_CRITERION_MISMATCH");
        if(Set.of("Not applicable","Not Applicable","Not Applicable for this VCEP").contains(clause.path("applicability").asText()))return "NOT_APPLICABLE";
        require("Applicable".equals(clause.path("applicability").asText()) && fields(clause,CLAUSE_FIELDS)
            && "Pathogenic".equals(clause.path("baseStrength").asText()) && "Pathogenic Moderate".equals(clause.path("defaultStrength").asText())
            && normal(clause.path("additionalComments")).isEmpty() && (entity.at("/ld/File").isMissingNode() || entity.at("/ld/File").isEmpty())
            && !(normal(entity.at("/entContent/notes"))+" "+normal(entity.at("/entContent/releaseNotes"))).matches("(?s).*\\bPM3\\b.*"),"PM3_PUBLISHED_CONDITION_FORM_UNSUPPORTED");
        var strengths=new HashSet<String>();var texts=new HashSet<String>();int active=0;
        for(var d:clause.path("strengthDescriptor")) {
            require(fields(d,DESCRIPTOR_FIELDS) && Set.of("Applicable","Not applicable","Not Applicable","Not Applicable for this VCEP").contains(d.path("applicability").asText()),"PM3_PUBLISHED_DESCRIPTOR_UNSUPPORTED");
            if(!"Applicable".equals(d.path("applicability").asText()))continue;
            active++;strengths.add(d.path("strength").asText());texts.add(normal(d.path("text")));
            require(normal(d.path("instructionsToUse")).isEmpty() && (!d.has("status") || "approved".equals(d.path("status").asText())),"PM3_PUBLISHED_DESCRIPTOR_UNSUPPORTED");
        }
        require(active==4 && strengths.equals(Set.of("Supporting","Moderate","Strong","Very Strong")),"PM3_PUBLISHED_STRENGTHS_UNSUPPORTED");
        String instructions=normal(clause.path("instructionsToUse"));
        if(texts.equals(Set.of(DELEGATION)) && instructions.isEmpty())return "SVI_EXPLICIT_DELEGATION";
        if(texts.equals(Set.of(PHASE))) {
            if(instructions.isEmpty())return "SVI_PHASE_METHOD";
            if(instructions.equals(APPROVAL))return "SVI_PHASE_AND_APPROVED_PARTNER";
        }
        throw new IllegalArgumentException("PM3_PUBLISHED_CONDITION_FORM_UNSUPPORTED");
    }
    static String phaseEvidenceStatus(JsonNode reported) {
        return phaseEvidenceStatus(reported,phase(reported));
    }

    private static String phaseEvidenceStatus(JsonNode reported, Phase phase) {
        return switch(phase) {
            case TRANS -> "CONFIRMED_TRANS";
            case CIS -> "CONFIRMED_CIS";
            case CONFLICT -> "PHASE_CONFLICT";
            case UNKNOWN -> {
                if("REPORTED_HOMOZYGOUS".equals(reported.path("kind").asText()))yield "PHASE_NOT_REQUIRED";
                for(var fact:reported.path("facts"))if(explicitUnknown(fact))yield "REPORTED_UNDETERMINED";
                yield "PHASE_ORIGINAL_MISSING";
            }
        };
    }

    private static boolean explicitUnknown(JsonNode fact) {
        String value=fact.path("value").asText().strip().toLowerCase(Locale.ROOT);
        return "phaseText".equals(fact.path("field").asText())
                && "EXPLICIT_CASE_TYPED_TABLE_PHASE".equals(fact.path("method").asText())
                && Set.of("unknown","phase unknown").contains(value)
                && !fact.at("/citation/unitId").asText().isBlank()
                && value.equals(fact.at("/citation/quote").asText().strip().toLowerCase(Locale.ROOT));
    }

    private static Phase phase(JsonNode reported) {
        // A source-known contradiction is never repaired by changing method.
        // Homozygous cases need no invented partner/confirmed-trans proof, but
        // retain the existing scorer's conflict/CIS exclusions.
        if("CONFLICT".equals(reported.path("phase").asText()))return Phase.CONFLICT;
        if("REPORTED_HOMOZYGOUS".equals(reported.path("kind").asText()))
            return "CIS".equals(reported.path("phase").asText())?Phase.CIS:Phase.UNKNOWN;
        String target=reported.path("targetRepresentation").asText(), partner=reported.path("partnerRepresentation").asText();
        if(target.isBlank() || partner.isBlank() || target.equals(partner))return Phase.CONFLICT;
        var paternal=new HashSet<String>();var maternal=new HashSet<String>();var results=new HashMap<String,Set<Boolean>>();
        boolean oneParent=false, molecularTrans=false, cis=false;var claims=new HashSet<String>();
        var publisher=reported.path("phaseAssayProof");
        boolean parentalWes=false;
        if(publisher.isObject()) {
            if("JATS_PROBAND_SEGREGATION_AND_CLONING_CIS".equals(publisher.path("method").asText())) {
                if(!jatsCisPhase(publisher,target,partner) || !"CIS".equals(reported.path("phase").asText()))return Phase.CONFLICT;
                cis=true;
                claims.add("CIS");
            } else {
                if(!publisherPhase(publisher,target,partner))return Phase.CONFLICT;
                parentalWes=true;
                maternal.add(publisher.path("maternalAllele").asText());
                paternal.add(publisher.path("paternalAllele").asText());
                claims.add("TRANS");
            }
        }
        for(var f:reported.path("facts")) {
            if(!f.at("/citation/unitId").isTextual() || f.at("/citation/unitId").asText().isBlank())continue;
            String field=f.path("field").asText(), value=f.path("value").asText();
            if(Set.of("targetVariant","partnerVariant").contains(field) && Set.of(target,partner).contains(value) && f.path("headingUnitId").isTextual()) {
                String heading=f.path("columnHeading").asText().strip().toLowerCase(Locale.ROOT);
                if(heading.equals("paternal allele"))paternal.add(value);
                if(heading.equals("maternal allele"))maternal.add(value);
            }
            var test=f.path("parentTest");var values=test.path("results");String parent=test.path("parent").asText();
            if(Set.of("MOTHER","FATHER").contains(parent) && values.isObject() && values.size()==2 && values.path(target).isBoolean() && values.path(partner).isBoolean()) {
                boolean a=values.path(target).asBoolean(),b=values.path(partner).asBoolean();oneParent|=a!=b;
                results.computeIfAbsent(parent+":"+target,k->new HashSet<>()).add(a);results.computeIfAbsent(parent+":"+partner,k->new HashSet<>()).add(b);
            }
            if("phaseText".equals(field) && "EXPLICIT_CASE_TYPED_TABLE_PHASE".equals(f.path("method").asText())) {
                if(value.equals("Cloning and sequencing of both alleles confirmed the variants in trans.")) {molecularTrans=true;claims.add("TRANS");}
                else if(value.equals("Cloning and sequencing of both alleles confirmed the variants in cis.")) {cis=true;claims.add("CIS");}
                else if(Set.of("cis","in cis").contains(value.toLowerCase(Locale.ROOT))) {cis=true;claims.add("CIS");}
                else if(Set.of("trans","in trans").contains(value.toLowerCase(Locale.ROOT)))claims.add("TRANS");
                else if(explicitUnknown(f))claims.add("UNKNOWN");
            }
        }
        boolean trans=molecularTrans || oneParent || paternal.size()==1 && maternal.size()==1 && !paternal.equals(maternal)
            && Set.of(target,partner).containsAll(paternal) && Set.of(target,partner).containsAll(maternal);
        if(parentalWes && (paternal.size()!=1 || maternal.size()!=1)
                || results.values().stream().anyMatch(s->s.size()>1) || trans && cis
                || claims.size()>1 || claims.contains("UNKNOWN") && (trans || cis))return Phase.CONFLICT;
        for(var a:paternal)if(results.getOrDefault("FATHER:"+a,Set.of()).equals(Set.of(false)))return Phase.CONFLICT;
        for(var a:maternal)if(results.getOrDefault("MOTHER:"+a,Set.of()).equals(Set.of(false)))return Phase.CONFLICT;
        return cis?Phase.CIS:trans?Phase.TRANS:Phase.UNKNOWN;
    }

    private static boolean jatsCisPhase(JsonNode proof,String target,String partner) {
        var alleles=proof.path("alleles");var refs=proof.path("references");
        if(!"SEGREGATION_CLONING_ALLELE_SPECIFIC_PCR".equals(proof.path("assay").asText())
                || !proof.path("caseLabel").asText().matches("[IVX]{1,5}-[1-9][0-9]*")
                || !Set.of("MOTHER","FATHER").contains(proof.path("parent").asText())
                || !alleles.isArray() || alleles.size()!=2 || !contains(alleles,target) || !contains(alleles,partner)
                || !refs.isArray() || refs.size()!=3)return false;
        var units=new HashSet<String>();
        String sha=refs.path(0).path("sourceSha256").asText(),bundle=refs.path(0).path("bundleSha256").asText();
        if(!sha.matches("[a-f0-9]{64}") || !bundle.matches("[a-f0-9]{64}"))return false;
        for(var ref:refs) {
            String unit=ref.path("unitId").asText();
            if(!unit.startsWith("/article[1]/body[1]/") || !units.add(unit)
                    || !sha.equals(ref.path("sourceSha256").asText()) || !bundle.equals(ref.path("bundleSha256").asText())
                    || ref.path("quote").asText().isBlank())return false;
        }
        return refs.path(2).path("unitId").asText().endsWith("/caption[1]")
                && refs.path(2).path("sourceObjectId").asText().matches("[A-Za-z][A-Za-z0-9_.-]{0,79}");
    }

    private static boolean publisherPhase(JsonNode proof,String target,String partner) {
        if(!"PUBLISHER_EXPLICIT_PARENTAL_WES".equals(proof.path("method").asText())
                || !"PARENTAL_WES".equals(proof.path("assay").asText())
                || !proof.path("caseLabel").asText().matches("[1-9][0-9]*"))return false;
        String mother=proof.path("maternalAllele").asText(), father=proof.path("paternalAllele").asText();
        if(mother.equals(father) || !Set.of(mother,father).equals(Set.of(target,partner)))return false;
        var refs=proof.path("references");
        if(!refs.isArray() || refs.size()!=3)return false;
        var units=new HashSet<String>();var sourcePins=new HashSet<String>();var bundlePins=new HashSet<String>();
        for(var ref:refs) {
            String unit=ref.path("unitId").asText(), sha=ref.path("sourceSha256").asText(), bundle=ref.path("bundleSha256").asText();
            if(!unit.startsWith("/body/") || !units.add(unit) || !sha.matches("[a-f0-9]{64}")
                    || !bundle.matches("[a-f0-9]{64}") || ref.path("quote").asText().isBlank())return false;
            sourcePins.add(sha);bundlePins.add(bundle);
        }
        return sourcePins.size()==1 && bundlePins.size()==1 && refs.path(2).at("/publishedFigure/sha256").asText().matches("[a-f0-9]{64}")
            && refs.path(2).at("/publishedFigure/url").asText().startsWith("https://");
    }

    private ObjectNode approvedPartner(JsonNode entity, JsonNode observations, JsonNode row, JsonNode context) throws Exception {
        var out=json.createObjectNode().put("conditionId","PARTNER_APPROVED_BY_SPECIFICATION_ISSUER")
            .put("status","MISSING_INFORMATION").put("reasonCode","PM3_PARTNER_SPECIFICATION_APPROVAL_REQUIRED");
        String doi=entity.at("/entContent/doi/docDoi").asText();
        require(doi.matches("10\\.5281/zenodo\\.[0-9]+"),"PM3_PUBLISHED_APPROVAL_DOCUMENT_REQUIRED");
        var issuers=new HashSet<String>();
        for(var a:entity.at("/entContent/doi/authors"))if("researchgroup".equals(a.at("/role/id").asText())) {
            String name=a.at("/person_or_org/name").asText();if(!name.isBlank()) {issuers.add(name);issuers.add("ClinGen "+name);}
        }
        require(!issuers.isEmpty(),"PM3_PUBLISHED_APPROVAL_ISSUER_REQUIRED");
        out.putObject("required").put("documentDoi",doi).put("expertReview","reviewed by expert panel");
        out.withObject("/required").set("issuerNames",json.valueToTree(new TreeSet<>(issuers)));
        var findings=out.putArray("observed");
        var q=row.path("partnerQualification");var qualified=new HashSet<String>();q.path("qualifiedScvs").forEach(s->qualified.add(s.asText()));
        if(!"VERIFIED".equals(q.path("independentClassification").asText()))return out;
        for(var assertion:q.path("sourceAssertions")) {
            String scv=assertion.path("scv").asText(),sha=assertion.path("sourceSha256").asText();
            if(!qualified.contains(scv))continue;
            var finding=findings.addObject().put("scv",scv).put("sourceSha256",sha).put("status","NOT_ESTABLISHED");
            byte[] raw;
            try {raw=original(observations.path("partnerSourceOriginals"),sha,4*1024*1024);}
            catch(IllegalArgumentException absent){finding.put("reasonCode","PM3_PARTNER_APPROVAL_ORIGINAL_REQUIRED");continue;}
            var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
            var archives=document.getElementsByTagName("VariationArchive");
            require(archives.getLength()==1,"PM3_PARTNER_APPROVAL_RECORD_CONFLICT");
            var archive=(Element)archives.item(0);
            require((archive.getAttribute("Accession")+"."+archive.getAttribute("Version")).equals(assertion.path("vcv").asText()),
                    "PM3_PARTNER_APPROVAL_RECORD_MISMATCH");
            var classified=child(archive,"ClassifiedRecord");var allele=classified==null?null:child(classified,"SimpleAllele");
            if(allele==null)throw new IllegalArgumentException("PM3_PARTNER_APPROVAL_ALLELE_REQUIRED");
            var location=child(allele,"Location");var expected=row.at("/identities/partner/allele");int matches=0,assemblyLocations=0;
            if(location!=null)for(Node n=location.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element loc && "SequenceLocation".equals(loc.getTagName())
                    && expected.path("assembly").asText().equals(loc.getAttribute("Assembly"))) {
                assemblyLocations++;
                if(expected.path("contig").asText().equals(loc.getAttribute("Chr").replaceFirst("^chr",""))
                    && Long.toString(expected.path("position").asLong()).equals(loc.getAttribute("positionVCF"))
                    && expected.path("reference").asText().equals(loc.getAttribute("referenceAlleleVCF"))
                    && expected.path("alternate").asText().equals(loc.getAttribute("alternateAlleleVCF")))matches++;
            }
            require(matches==1 && assemblyLocations==1,"PM3_PARTNER_APPROVAL_ALLELE_MISMATCH");
            boolean matchingGene=false;var genes=child(allele,"GeneList");
            if(genes!=null)for(Node n=genes.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element g && "Gene".equals(g.getTagName())
                    && context.at("/gene/hgncId").asText().equals(g.getAttribute("HGNC_ID"))
                    && row.at("/identities/target/geneSymbol").asText().equals(g.getAttribute("Symbol")))matchingGene=true;
            require(matchingGene,"PM3_PARTNER_APPROVAL_GENE_MISMATCH");
            var assertions=document.getElementsByTagName("ClinicalAssertion");Element selected=null;
            for(int i=0;i<assertions.getLength();i++) {
                var a=(Element)assertions.item(i);var accession=child(a,"ClinVarAccession");
                if(accession!=null && scv.equals(accession.getAttribute("Accession")+"."+accession.getAttribute("Version"))) {
                    require(selected==null,"PM3_PARTNER_APPROVAL_ASSERTION_CONFLICT");selected=a;
                }
            }
            if(selected==null)continue;
            var acc=child(selected,"ClinVarAccession");var classification=child(selected,"Classification");
            if(acc==null || classification==null)continue;
            String status=text(selected,"RecordStatus"),review=text(classification,"ReviewStatus"),label=text(classification,"GermlineClassification");
            finding.put("reviewStatus",review).put("submitterName",acc.getAttribute("SubmitterName")).put("classification",label);
            String submitter=acc.getAttribute("SubmitterName").replaceFirst(", ClinGen$","");
            if(!status.equals("current") || !review.equals("reviewed by expert panel") || !issuers.contains(submitter)
                    || !acc.getAttribute("OrgID").matches("[1-9][0-9]*") || !label.equals(assertion.path("sourceClassification").asText()))continue;
            var ids=selected.getElementsByTagName("XRef");var diseases=new HashSet<String>();
            for(int i=0;i<ids.getLength();i++) {var id=(Element)ids.item(i);if("MONDO".equals(id.getAttribute("DB")))diseases.add(id.getAttribute("ID").replace("MONDO:",""));}
            if(!diseases.equals(Set.of(context.at("/disease/mondoId").asText().replace("MONDO:",""))))continue;
            LocalDate date=LocalDate.parse(classification.getAttribute("DateLastEvaluated"));
            LocalDate approved=LocalDate.parse(entity.at("/entContent/approvedOn").asText().substring(0,10));
            if(date.isBefore(approved) || date.isAfter(LocalDate.now(java.time.ZoneOffset.UTC)))continue;
            if(!approveIndependentAssay(entity,row,scv,finding))continue;
            // Expert review and issuer/date/scope above establish the approved
            // source assertion. Its applied specification is a separate fact:
            // accept a source method/citation or an explicit positive statement,
            // not a code-invented mandatory English sentence.
            var statements=new ArrayList<MethodSource>();
            var pdf=versionedMethod(entity, observations, selected, classification, acc,
                row.at("/identities/target/geneSymbol").asText(), assertion.path("sourceLocator").asText(), finding);
            if(pdf!=null)statements.add(pdf);
            var attributes=selected.getElementsByTagName("Attribute");
            for(int i=0;i<attributes.getLength();i++) {
                var attribute=(Element)attributes.item(i);
                if("AssertionMethod".equals(attribute.getAttribute("Type")) && hasDoi(attribute.getTextContent(),doi)) {
                    statements.add(new MethodSource(attribute.getTextContent(),assertion.path("sourceLocator").asText()+"//Attribute[@Type='AssertionMethod']["+(i+1)+"]",true));
                }
            }
            var citations=selected.getElementsByTagName("Citation");
            for(int i=0;i<citations.getLength();i++) {
                var citation=(Element)citations.item(i);
                if(!Set.of("criteria provided","assertion criteria","assertion method").contains(citation.getAttribute("Type").strip().toLowerCase(Locale.ROOT)))continue;
                var references=citation.getElementsByTagName("XRef");
                for(int j=0;j<references.getLength();j++) {
                    var reference=(Element)references.item(j);
                    if("DOI".equalsIgnoreCase(reference.getAttribute("DB")) && hasDoi(reference.getAttribute("ID"),doi)) {
                        statements.add(new MethodSource(reference.getAttribute("ID"),assertion.path("sourceLocator").asText()+"//Citation["+(i+1)+"]/XRef["+(j+1)+"]",true));
                    }
                }
            }
            var comments=classification.getElementsByTagName("Comment");
            for(int i=0;i<comments.getLength();i++) {
                String comment=comments.item(i).getTextContent().strip();
                if(hasDoi(comment,doi)) {
                    statements.add(new MethodSource(comment,assertion.path("sourceLocator").asText()+"/Classification[1]/Comment["+(i+1)+"]",false));
                }
            }
            MethodSource positive=null;boolean denied=false;
            var sourceStatements=finding.putArray("methodStatements");
            for(var statement:statements) {
                boolean negative=deniedDocumentUse(statement.value(),doi);
                boolean applied=!negative && (statement.structured() || appliedDocument(statement.value(),doi));
                sourceStatements.addObject().put("statement",statement.value()).put("locator",statement.locator())
                        .put("status",negative?"USE_DENIED_OR_NOT_ESTABLISHED":applied?"APPLIED":"REFERENCE_ONLY_OR_UNINTERPRETED");
                denied|=negative;
                if(applied && positive==null)positive=statement;
            }
            if(denied) {
                finding.put("reasonCode",positive==null?"PM3_PARTNER_SPECIFICATION_USE_NOT_ESTABLISHED":"PM3_PARTNER_SPECIFICATION_METHOD_CONFLICT");
                if(positive!=null) {
                    finding.put("status","CONFLICT");out.put("status","CONFLICT").put("reasonCode","PM3_PARTNER_SPECIFICATION_METHOD_CONFLICT");
                }
                continue;
            }
            if(positive!=null)approval(out,finding,doi,positive.value(),positive.locator());
        }
        for(var finding:findings)if("CONFLICT".equals(finding.path("status").asText()))
            out.put("status","CONFLICT").put("reasonCode","PM3_PARTNER_SPECIFICATION_METHOD_CONFLICT");
        return out;
    }
    private boolean approveIndependentAssay(JsonNode entity,JsonNode row,String scv,ObjectNode finding) {
        for(var audit:row.at("/partnerQualification/assertionAssessments")) {
            if(!scv.equals(audit.path("scv").asText()))continue;
            for(var dependency:audit.path("criterionDependencies")) {
                if(!"TARGET_INDEPENDENT_BRAIN_MRS_ORIGINAL".equals(dependency.path("status").asText()))continue;
                var rule=row.at("/policySelection/matches/0/independentPp4");
                var desc=entity.at(rule.path("clausePointer").asText());
                var assay=dependency.path("sourceAssay");
                String text=normal(desc.path("text")).replace("\\","");
                if(!rule.isObject() || !"Applicable".equals(desc.path("applicability").asText())
                        || !"Moderate".equals(desc.path("strength").asText())
                        || !Pm3SourceDigest.sha256(json,desc).equals(rule.path("clauseSha256").asText())
                        || !text.contains("Significantly decreased creatine peak in brain magnetic resonance spectroscopy")
                        || !text.contains("Variant must meet PM2_Supporting for PP4 to apply at any strength.")
                        || !"BRAIN_MRS".equals(assay.path("assay").asText())
                        || !"SIGNIFICANTLY_REDUCED_CREATINE_PEAK".equals(assay.path("observedResult").asText())
                        || assay.path("references").size()!=2) {
                    finding.put("reasonCode","PM3_PARTNER_PP4_PUBLISHED_ASSAY_BINDING_REQUIRED");return false;
                }
                var rarity=row.at("/rarityQualification/partner");
                if(!Pm3PublishedRarity.SCOPE.equals(rarity.path("scope").asText()) || !"VERIFIED".equals(rarity.path("qualification").asText())
                        || !Pm3LiteratureAssessment.sameAllele(rarity.path("queryAllele"),row.at("/identities/partner/allele"))) {
                    finding.put("reasonCode","PM3_PARTNER_PP4_PARTNER_RARITY_REQUIRED");return false;
                }
                var details=finding.putObject("independentPp4");
                details.set("sourceClause",rule.deepCopy());
                details.set("sourceAssay",assay.deepCopy());
                details.put("requiredPartnerRarity","VERIFIED");
            }
        }
        return true;
    }
    private record MethodSource(String value,String locator,boolean structured) { }
    private static @Nullable MethodSource versionedMethod(JsonNode entity,JsonNode observations,Element selected,
            Element classification,Element accession,String gene,String locator,ObjectNode finding) {
        String submitter=accession.getAttribute("SubmitterName").replaceFirst(", ClinGen$","");
        var abbreviation=Pattern.compile("ClinGen ([A-Z][A-Z0-9]{1,15}) VCEP").matcher(accession.getAttribute("OrgAbbreviation"));
        if(!abbreviation.matches())return null;
        var methodName=Pattern.compile("ClinGen "+abbreviation.group(1)+" ACMG Specifications "+Pattern.quote(gene)
            +" V([0-9]+(?:\\.[0-9]+){1,3})");
        String release=entity.at("/entContent/version").asText();
        String key=entity.path("entId").asText()+"@"+release, doi=entity.at("/entContent/doi/docDoi").asText();
        var attributes=selected.getElementsByTagName("Attribute");
        var comments=classification.getElementsByTagName("Comment");
        for(int i=0;i<attributes.getLength();i++) {
            var attribute=(Element)attributes.item(i);
            if(!"AssertionMethod".equals(attribute.getAttribute("Type")) || !(attribute.getParentNode() instanceof Element group))continue;
            var method=methodName.matcher(attribute.getTextContent().strip());
            if(!method.matches() || !versionEquivalent(release,method.group(1)))continue;
            var citation=child(group,"Citation");String url=citation==null?"":text(citation,"URL");
            if(!url.matches("https://submit\\.ncbi\\.nlm\\.nih\\.gov/api/2\\.0/files/[a-z0-9]+/[A-Za-z0-9_.-]+\\.pdf/\\?format=attachment"))continue;
            String application="ACMG/AMP criteria applied, as specified by the "+submitter+" (Specifications Version "+method.group(1)+"):";
            int declarations=0;
            for(int j=0;j<comments.getLength();j++)if(comments.item(j) instanceof Element comment
                    && "public".equals(comment.getAttribute("Type")) && comment.getTextContent().contains(application))declarations++;
            if(declarations!=1)continue;
            for(var ref:observations.path("specificationMethodDocuments"))if(key.equals(ref.path("specificationKey").asText())
                    && doi.equals(ref.path("documentDoi").asText()) && url.equals(ref.path("url").asText())
                    && method.group(1).equals(ref.path("version").asText())) {
                byte[] bytes=original(observations.path("specificationMethodOriginals"),ref.path("sha256").asText(),2*1024*1024);
                require(bytes.length>=5 && bytes[0]=='%' && bytes[1]=='P' && bytes[2]=='D' && bytes[3]=='F' && bytes[4]=='-',
                        "PM3_PARTNER_ASSERTION_METHOD_PDF_REQUIRED");
                finding.set("assertionMethodDocument",ref.deepCopy());
                return new MethodSource(application,locator+"//Attribute[@Type='AssertionMethod']",true);
            }
        }
        return null;
    }
    private static boolean versionEquivalent(String release,String declared) {
        return release.matches("[0-9]+(?:\\.[0-9]+){1,3}")
                && release.replaceFirst("(?:\\.0)+$","").equals(declared.replaceFirst("(?:\\.0)+$",""));
    }
    private static boolean hasDoi(String text,String doi) {
        return java.util.regex.Pattern.compile("(?i)(?<![a-z0-9.])"+java.util.regex.Pattern.quote(doi)
                +"(?=$|[\\s)\\]},;\"<>]|\\.(?:\\s|$))").matcher(text).find();
    }
    private static boolean appliedDocument(String text,String doi) {
        if(!hasDoi(text,doi))return false;
        if(deniedDocumentUse(text,doi))return false;
        for(String sentence:methodSentences(text,doi))if(sentence.contains("specification_document")) {
            if(sentence.matches("(?s).*\\b(?:classification|classified|assessed|evaluated|criteria|specifications?|guidelines)\\b.{0,160}\\b(?:using|used|applied|following|according to|based on|in accordance with)\\b.{0,160}specification_document.*")
                    || sentence.matches("(?s).*\\b(?:used|applied|followed)\\b.{0,100}\\b(?:criteria|specifications?|guidelines)\\b.{0,100}specification_document.*"))return true;
        }
        return false;
    }
    private static List<String> methodSentences(String text,String doi) {
        String document="(?i)(?:https?://(?:www\\.)?doi\\.org/|doi:\\s*)?"+java.util.regex.Pattern.quote(doi);
        String normalized=text.replaceAll(document,"specification_document").toLowerCase(Locale.ROOT);
        return Arrays.stream(normalized.split("(?<=[.!?;])\\s+|[\\r\\n]+"))
                .map(s->s.replaceAll("[\\s\\p{Z}]+"," ").strip()).toList();
    }
    private static boolean deniedDocumentUse(String text,String doi) {
        for(String sentence:methodSentences(text,doi))if(sentence.contains("specification_document")) {
            // Negation/future applies to the method predicate or this document,
            // not to unrelated population/clinical statements in the paragraph.
            String verb="(?:perform(?:ed)?|classif(?:y|ied)|assess(?:ed)?|evaluat(?:e|ed)|us(?:e|ed|ing)|appl(?:y|ied|ying|icable)|follow(?:ed|ing)?|based)";
            String auxiliaries="(?:(?:be|been|being|yet|previously|actually|currently|have)\\s+){0,3}";
            String predicate="(?:\\b(?:not|never|without|neither)\\s+"+auxiliaries+verb
                    +"|\\b(?:will|would|may|might|could|plan to|planned to|intend to|propose to)\\s+"+auxiliaries+verb+")";
            if(sentence.matches("(?s).*"+predicate+".{0,180}specification_document.*")
                    || sentence.matches("(?s).*specification_document.{0,100}"+predicate+".*")
                    || sentence.matches("(?s).*\\b(?:unknown|unclear|uncertain|undetermined)\\s+(?:whether.{0,100}|method.{0,100})?specification_document.*"))return true;
        }
        return false;
    }
    private static void approval(ObjectNode out,ObjectNode finding,String doi,String statement,String locator) {
        finding.put("status","SOURCE_APPROVAL_QUALIFIED").put("documentDoi",doi).put("sourceStatement",statement).put("locator",locator);
        out.put("status","SATISFIED").put("reasonCode","PM3_PARTNER_SOURCE_SPECIFICATION_APPROVAL_VERIFIED");
    }
    private static @Nullable Element child(Element parent,String name) {
        Element found=null;
        for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e && name.equals(e.getTagName())) {
            require(found==null,"PM3_PARTNER_APPROVAL_SOURCE_CONFLICT");found=e;
        }
        return found;
    }
    private static String text(Element parent,String name) {var e=child(parent,name);return e==null?"":e.getTextContent().strip();}
    private static byte[] original(JsonNode originals,String sha,int maximum) {
        require(sha.matches("[0-9a-f]{64}") && originals.path(sha).isTextual(),"PM3_PUBLISHED_ORIGINAL_REQUIRED");
        String encoded=originals.path(sha).asText();require(encoded.length()<=4L*(maximum+2)/3,"PM3_PUBLISHED_ORIGINAL_SIZE_INVALID");
        byte[] raw=Base64.getDecoder().decode(encoded);require(raw.length<=maximum && FamilyDeNovoJson.sha256(raw).equals(sha),"PM3_PUBLISHED_ORIGINAL_PIN_MISMATCH");return raw;
    }
    private static boolean fields(JsonNode node,Set<String> allowed) {var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);return allowed.containsAll(names);}
    private static boolean contains(JsonNode list,String value) {for(var n:list)if(n.isTextual() && value.equals(n.asText()))return true;return false;}
    private static String normal(JsonNode value) {return value.isTextual()?value.asText().replaceAll("[\\s\\p{Z}]+"," ").strip():"";}
    private static void require(boolean ok,String code) {if(!ok)throw new IllegalArgumentException(code);}
}
