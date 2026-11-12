package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.lang.Nullable;

/** Replays one published cohort-enrichment method, not variant phenotype evidence.
 * Recognising a source method never certifies the absence of another phenotype. */
final class Pp2HcmPublishedSource {
    static final String TYPE = "PP2_PUBLISHED_HCM_ENRICHMENT_WITH_PHENOTYPE_EXCLUSION_V1";
    static final String VERSION = "1.0.0";
    static final String TRANSCRIPT_SCOPE_VERSION = "1.0.0";
    private static final String PREFIX = "Application of this rule takes into consideration empirical data quantifying levels of rare missense variant enrichment in HCM referral cohorts compared to population-based cohorts (Walsh _et al._ 2019[<sup>12</sup>](#pmid_30696458)) rather than the missense constraint score in gnomAD. On the basis of data from Walsh _et al._ 2019[<sup>12</sup>](#pmid_30696458), **PP2 is currently** **only applicable to** _**";
    private static final String MIDDLE = "**_ **for HCM** (transcripts ";
    private static final String NM_SEPARATOR = " and NM\\_";
    private static final String SUFFIX = ")_._ Data from HCM case cohorts was used to derive these cluster regions. Therefore, this rule should NOT be applied when additional evidence for the variant supports that the variant causes a phenotype other than HCM (e.g., variant seen in multiple DCM cases). Enrichment was not observed for DCM in any genes.";
    private static final Pattern TEXT = Pattern.compile(Pattern.quote(PREFIX) + "([A-Za-z0-9_.-]{1,100})"
            + Pattern.quote(MIDDLE) + "(ENST[0-9]+)" + Pattern.quote(NM_SEPARATOR) + "([0-9]+\\.[1-9][0-9]*)" + Pattern.quote(SUFFIX));
    private static final Pattern COMMENTS = Pattern.compile(Pattern.quote("Application of this rule takes into consideration empirical data quantifying levels of rare missense variant enrichment in HCM referral cohorts compared to population-based cohorts (Walsh et al. 2019 PMID:30696458) rather than the missense constraint score in gnomAD. For ")
            + "([A-Za-z0-9_.-]{1,100})" + Pattern.quote(", there is evidence for regional enrichment of rare missense variants (see PM1 specifications)."));
    private Pp2HcmPublishedSource() { }

    static final class Method {
        private final ObjectNode value;
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        private Method(ObjectNode value) { this.value = value.deepCopy(); }
        ObjectNode snapshot() { return value.deepCopy(); }
        boolean recognised() { return "SOURCE_METHOD_RECOGNISED".equals(value.path("status").asText()); }
    }

    /** A source-method check only. Caller owns the live catalog/selection and actual
     * sequence verification. This value cannot award PP2 or grant a clinical review. */
    static Method inspect(ObjectMapper mapper, ObjectNode selection) {
        var out = mapper.createObjectNode().put("schemaVersion", "pp2-hcm-published-method-v1")
                .put("methodVersion", VERSION).put("status", "SOFTWARE_UNSUPPORTED")
                .put("reasonCode", "PP2_HCM_SOURCE_NOT_INTERPRETED").put("scoringPermissionGranted", false)
                .put("otherPhenotypeReviewGranted", false).put("cohortStatisticsRecalculated", false);
        try {
            ObjectNode expected = derive(mapper, selection);
            require(expected.equals(selection.at("/record/interpretation")), "INTERPRETATION_SOURCE_MISMATCH");
            out.set("sourceInterpretation", expected);
            var record = selection.path("record"); var spec = selection.path("specification");
            out.putObject("sourceBinding").put("specificationKey", spec.path("specificationKey").asText())
                    .put("entitySha256", spec.path("entitySha256").asText()).put("clausePointer", record.path("clausePointer").asText())
                    .put("clauseSha256", record.path("clauseSha256").asText()).put("scopePointer", record.path("scopePointer").asText())
                    .put("sourceRawSha256", spec.at("/source/rawSha256").asText());
            out.put("status", "SOURCE_METHOD_RECOGNISED").put("reasonCode", "PP2_HCM_PUBLISHED_METHOD_BOUND");
        } catch (IllegalArgumentException invalid) {
            String reason = invalid.getMessage();
            out.put("reasonCode", reason != null && reason.matches("PP2_HCM_[A-Z0-9_]+") ? reason : "PP2_HCM_SOURCE_INVALID");
        }
        return new Method(out);
    }

    static String issue(ObjectMapper mapper, ObjectNode selection, boolean germlineDeclared) {
        var method = inspect(mapper, selection);
        return !method.recognised() ? method.snapshot().path("reasonCode").asText()
                : !germlineDeclared ? "PP2_HCM_GERMLINE_SCOPE_REQUIRED" : null;
    }

    /** Deterministically derived required conditions, never clinical assertions. */
    static ObjectNode derive(ObjectMapper mapper, ObjectNode selection) {
        JsonNode record = selection.path("record"), spec = selection.path("specification"), original = spec.path("original");
        String cp = record.path("clausePointer").asText(), sp = record.path("scopePointer").asText();
        require(cp.matches("/ld/CriteriaCode/[0-9]+") && sp.matches("/ld/RuleSet/[0-9]+/entContent/genes/[0-9]+/diseases/[0-9]+"), "SOURCE_POINTER_INVALID");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        require(adapter.canonicalSha256(original).equals(spec.path("entitySha256").asText())
                && adapter.canonicalSha256(original.at(cp)).equals(record.path("clauseSha256").asText())
                && spec.path("specificationKey").equals(record.path("specificationKey"))
                && spec.at("/source/rawSha256").asText().matches("[a-f0-9]{64}"), "SOURCE_BINDING_MISMATCH");
        var clause = original.at(cp).path("entContent");
        require("PP2".equals(clause.path("label").asText()) && "Applicable".equals(clause.path("applicability").asText())
                && "Pathogenic".equals(clause.path("baseStrength").asText())
                && "Pathogenic Supporting".equals(clause.path("defaultStrength").asText())
                && blank(clause.path("instructionsToUse")), "SOURCE_METHOD_UNSUPPORTED");
        int pp2Clauses = 0;
        for (var other : original.at("/ld/CriteriaCode")) if ("PP2".equals(other.at("/entContent/label").asText())) pp2Clauses++;
        require(pp2Clauses == 1, "SOURCE_CLAUSE_AMBIGUOUS");
        require(blank(original.at("/entContent/notes")), "GENERAL_NOTES_UNSUPPORTED");
        JsonNode files = original.at("/ld/File");
        require(files.isMissingNode() || files.isNull() || files.isArray() && files.isEmpty(), "ATTACHMENTS_UNSUPPORTED");
        JsonNode active = null;
        require(clause.path("strengthDescriptor").isArray(), "STRENGTH_UNSUPPORTED");
        for (var strength : clause.path("strengthDescriptor")) {
            if ("Applicable".equals(strength.path("applicability").asText())) {
                require(active == null, "STRENGTH_AMBIGUOUS"); active = strength;
            } else require(inactive(strength.path("applicability").asText()), "STRENGTH_UNSUPPORTED");
        }
        if (active == null) throw new IllegalArgumentException("PP2_HCM_SUPPORTING_REQUIRED");
        require("Supporting".equals(active.path("strength").asText()) && "approved".equals(active.path("status").asText())
                && blank(active.path("instructionsToUse")), "STRENGTH_UNSUPPORTED");
        var text = TEXT.matcher(normalize(active.path("text").asText()));
        require(text.matches() && COMMENTS.matcher(normalize(clause.path("additionalComments").asText())).matches(), "SOURCE_CONDITIONS_UNSUPPORTED");
        String symbol = text.group(1), unversionedEnsembl = text.group(2), refseq = "NM_" + text.group(3);
        String gp = sp.substring(0, sp.lastIndexOf("/diseases/")); JsonNode gene = original.at(gp), disease = original.at(sp);
        require(clause.path("gene").isArray() && clause.path("gene").size() == 1
                && symbol.equals(clause.path("gene").path(0).asText()), "SOURCE_GENE_OR_TRANSCRIPT_RESTRICTION");
        require(symbol.equals(gene.path("gene").asText()) && refseq.equals(gene.path("preferredTranscript").asText()), "SOURCE_GENE_OR_TRANSCRIPT_RESTRICTION");
        require("hypertrophic cardiomyopathy".equals(normalize(disease.path("preferredTitle").asText()).toLowerCase(java.util.Locale.ROOT))
                && "Autosomal dominant inheritance".equals(disease.path("preferredModeOfInheritance").asText())
                && disease.path("preferredMondoId").asText().matches("MONDO:[0-9]{7}"), "SOURCE_DISEASE_RESTRICTION");
        String hgnc = ""; int geneMatches = 0;
        for (var entity : original.at("/ld/Gene")) if (symbol.equals(entity.at("/entContent/HGNC/symbol").asText())) {
            geneMatches++; hgnc = entity.at("/entContent/HGNC/hgnc_id").asText();
        }
        require(geneMatches == 1 && hgnc.matches("HGNC:[1-9][0-9]*"), "SOURCE_GENE_IDENTITY_AMBIGUOUS");
        JsonNode scope = record.path("scope");
        require(hgnc.equals(scope.path("hgncId").asText()) && disease.path("preferredMondoId").equals(scope.path("mondoId"))
                && "AUTOSOMAL_DOMINANT".equals(scope.path("moi").asText()) && refseq.equals(scope.path("transcript").asText()), "RECORD_SCOPE_MISMATCH");
        String rp = sp.substring(0, sp.indexOf("/entContent/genes/"));
        require(blank(original.at(rp).at("/entContent/generalComments")), "GENERAL_COMMENTS_UNSUPPORTED");
        for (var other : original.at("/ld/CriteriaCode")) {
            var c = other.path("entContent"); if ("PP2".equals(c.path("label").asText()) || Pp2PublishedAssessment.entirelyInactive(c)) continue;
            require(!mentions(c), "CROSS_RULE_CONDITION_UNSUPPORTED");
            for (var strength : c.path("strengthDescriptor")) if (!inactive(strength.path("applicability").asText()))
                require(!mentionsText(strength.path("text").asText()) && !mentionsText(strength.path("instructionsToUse").asText()), "CROSS_RULE_CONDITION_UNSUPPORTED");
        }
        var result = mapper.createObjectNode().put("type", TYPE).put("version", VERSION).put("appliedStrength", "SUPPORTING")
                .put("germlineOnly", true).put("requiresQualifiedVariantOtherPhenotypeReview", true);
        result.putObject("sourceScope").put("geneSymbol", symbol).put("hgncId", hgnc)
                .put("mondoId", disease.path("preferredMondoId").asText()).put("moi", "AUTOSOMAL_DOMINANT")
                .put("preferredTranscript", refseq).put("unversionedSourceEnsembl", unversionedEnsembl);
        var bindings = result.putObject("sourceTextBindings");
        bindings.set("supportingText", active.path("text").deepCopy()); bindings.set("comments", clause.path("additionalComments").deepCopy());
        return result;
    }
    static ObjectNode policyBinding(ObjectMapper mapper, ObjectNode selection) {
        var method = inspect(mapper, selection);
        require(method.recognised(), "SOURCE_METHOD_UNRECOGNISED");
        require("READY".equals(selection.path("status").asText())
                && selection.path("declaredTranscript").equals(selection.at("/record/scope/transcript"))
                && selection.path("executionTranscript").asText().matches("(?:NM_[0-9]+|ENST[0-9]+)\\.[1-9][0-9]*"),
                "SELECTED_TRANSCRIPT_BINDING_REQUIRED");
        var snapshot = method.snapshot(); var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var binding = mapper.createObjectNode().put("schemaVersion", "pp2-hcm-policy-binding-v1")
                .put("methodVersion", VERSION).put("criterionId", GeneralPp2Runtime.CRITERION)
                .put("declaredTranscript", selection.path("declaredTranscript").asText())
                .put("executionTranscript", selection.path("executionTranscript").asText())
                .put("sourceInterpretationSha256", adapter.canonicalSha256(snapshot.path("sourceInterpretation")));
        binding.set("sourceBinding", snapshot.path("sourceBinding").deepCopy());
        binding.set("scope", selection.at("/record/scope").deepCopy());
        if (selection.path("sourceTranscriptScope").isObject()) binding.set("sourceTranscriptScope", selection.path("sourceTranscriptScope").deepCopy());
        return binding;
    }
    /** The source names an accession without a version. Each current model is still checked independently.
     * This is a rule-scope match, never RefSeq/RNA/protein equivalence or criterion adoption. */
    @Nullable static ObjectNode transcriptScope(ObjectMapper mapper, ObjectNode record, ObjectNode specification,
            ObjectNode context, ObjectNode model) {
        if (!TYPE.equals(record.at("/interpretation/type").asText()) || !"EXECUTABLE".equals(record.path("status").asText())) return null;
        var selection = mapper.createObjectNode(); selection.set("record", record); selection.set("specification", specification);
        var method = inspect(mapper, selection); if (!method.recognised()) return null;
        var scope = method.snapshot().at("/sourceInterpretation/sourceScope");
        String accession = scope.path("unversionedSourceEnsembl").asText(), execution = model.path("transcript").asText();
        if (!accession.matches("ENST[0-9]+") || !execution.matches(Pattern.quote(accession) + "\\.[1-9][0-9]*")
                || !context.at("/transcript/version").isIntegralNumber() || context.at("/transcript/version").asInt() <= 0
                || !accession.equals(context.at("/transcript/accession").asText())
                || !execution.equals(accession + "." + context.at("/transcript/version").asText())
                || !scope.path("geneSymbol").equals(context.at("/gene/symbol")) || !scope.path("geneSymbol").equals(model.path("geneSymbol"))
                || !scope.path("hgncId").equals(context.at("/gene/hgncId")) || !scope.path("hgncId").equals(model.path("hgncId"))
                || !scope.path("mondoId").equals(context.at("/disease/mondoId")) || !scope.path("moi").equals(context.path("moi"))
                || !record.at("/scope/assembly").equals(model.path("assembly"))
                || !record.at("/scope/assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"))) return null;
        return mapper.createObjectNode().put("schemaVersion", "source-declared-unversioned-transcript-scope-v1")
                .put("methodVersion", TRANSCRIPT_SCOPE_VERSION).put("mode", "EXPLICIT_UNVERSIONED_SOURCE_SCOPE")
                .put("sourceAccession", accession).put("executionTranscript", execution)
                .put("preferredTranscript", scope.path("preferredTranscript").asText())
                .put("sourceClauseSha256", record.path("clauseSha256").asText())
                .put("sourceEntitySha256", specification.path("entitySha256").asText())
                .put("executionModelSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(model))
                .put("refseqEquivalenceGranted", false).put("scientificAdoptionGranted", false);
    }
    private static boolean mentionsText(String s) { return s.toUpperCase(java.util.Locale.ROOT).contains("PP2"); }
    private static boolean mentions(JsonNode c) { return mentionsText(c.path("instructionsToUse").asText()) || mentionsText(c.path("additionalComments").asText()); }
    private static boolean inactive(String s) { return Set.of("Not applicable", "Not Applicable", "Not Applicable for this VCEP").contains(s); }
    private static boolean blank(JsonNode n) { return n.isMissingNode() || n.isNull() || n.isTextual() && n.asText().isBlank(); }
    private static String normalize(String s) { return s.replace('\u00a0', ' ').strip().replaceAll("\\s+", " "); }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException("PP2_HCM_" + reason); }
}
