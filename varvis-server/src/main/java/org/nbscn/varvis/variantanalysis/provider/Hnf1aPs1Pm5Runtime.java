package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Scoped GN017 adapter for the existing condition-aware comparator and evidence-v2 evaluator. */
public final class Hnf1aPs1Pm5Runtime {
    public static final String PROFILE_RESOURCE = "/acmg/hnf1a-ps1-pm5-business-profile-v2.json";
    public static final String PROFILE_SHA256 = "807551fa9a484cfdca9a6a1051226b16b0cce96a7ae2525e84fd3f370c4cda77";
    public static final String REGISTRY_RESOURCE = "/acmg/rule-registry-v2/acmg-rule-registry-hnf1a-ps1-pm5-2026-09-11.json";
    public static final String OVERLAY_RESOURCE = "/acmg/cspec-overlay-registry-v1/cspec-overlay-registry-hnf1a-ps1-pm5-2026-09-11.json";
    static final String ROOT = "/acmg/hnf1a-ps1-pm5-v1/";
    static final String TRANSCRIPT = "ENST00000257555.11";
    private static final Pattern CODING = Pattern.compile("^ENST00000257555\\.11:c\\.([1-9][0-9]*)([ACGT])>([ACGT])$");
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ClinVarConditionAwareComparatorInput comparator;
    private final Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult> splice;
    private final ObjectNode policy;
    private final ObjectNode audit;
    private final ObjectNode model;
    private final ObjectNode matrix;
    private final ObjectNode rna;

    Hnf1aPs1Pm5Runtime(ObjectMapper mapper, ClinVarConditionAwareComparatorInput comparator,
                       Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult> splice) {
        this(mapper, comparator, splice, loadProfile(mapper), read(mapper, "reference-audit.json"), read(mapper, "rna-review.json"));
    }

    Hnf1aPs1Pm5Runtime(ObjectMapper mapper, ClinVarConditionAwareComparatorInput comparator,
                       Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult> splice,
                       ObjectNode policy, ObjectNode audit, ObjectNode rna) {
        this.mapper = Objects.requireNonNull(mapper); this.adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.comparator = comparator; this.splice = splice;
        this.policy = policy.deepCopy(); this.audit = audit.deepCopy(); this.rna = rna.deepCopy();
        this.model = ClinVarMissenseBusinessRuntime.read(mapper, "/acmg/pvs1-hnf1a-transcript-model-v1.json");
        this.matrix = read(mapper, "grantham.json");
    }

    public static ObjectNode loadProfile(ObjectMapper mapper) {
        ObjectNode p = ClinVarMissenseBusinessRuntime.read(mapper, PROFILE_RESOURCE);
        if (!PROFILE_SHA256.equals(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(p)))
            throw new IllegalStateException("HNF1A_PS1_PM5_PROFILE_IDENTITY_INVALID");
        for (JsonNode artifact : p.path("sourceArtifacts")) verify(artifact.path("resource").asText(), artifact.path("sha256").asText());
        verify(REGISTRY_RESOURCE, p.at("/registryBinding/registrySha256").asText());
        verify(OVERLAY_RESOURCE, p.at("/scientificArtifactBinding/overlaySha256").asText());
        verify(p.at("/scientificArtifactBinding/authorityResource").asText(), p.at("/scientificArtifactBinding/authoritySha256").asText());
        return p;
    }

    private static void verify(String path, String hash) {
        if (!hash.matches("[0-9a-f]{64}") || !hash.equals(ClinVarMissenseBusinessArtifacts.resourceSha(path)))
            throw new IllegalStateException("HNF1A_PS1_PM5_RESOURCE_IDENTITY_INVALID: " + path);
    }

    static ObjectNode read(ObjectMapper mapper, String name) { return ClinVarMissenseBusinessRuntime.read(mapper, ROOT + name); }

    public static boolean matchesContext(JsonNode context) {
        return context != null && "HNF1A".equals(context.at("/gene/symbol").asText())
                && "HGNC:11621".equals(context.at("/gene/hgncId").asText())
                && "MONDO:0015967".equals(context.at("/disease/mondoId").asText())
                && "AUTOSOMAL_DOMINANT".equals(context.path("moi").asText());
    }

    ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, TranscriptConsequence selected) {
        ObjectNode result = base(context);
        String issue = targetIssue(context, selected, result.putObject("targetIdentity"));
        if (issue != null) return disposition(mapper, context, issue, issue.startsWith("OUTSIDE_") ? "NOT_APPLICABLE" : "UNKNOWN");
        if (comparator == null) return disposition(mapper, context, "COMPARATOR_INPUT_UNAVAILABLE", "UNKNOWN");
        List<ObjectNode> atomics;
        result.put("providerCalls", 1);
        try {
            var input = comparator.build(context, candidate, selected);
            result.set("comparatorInput", mapper.valueToTree(input));
            atomics = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).projectClinVarComparatorEvidence(context, input, ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper));
            if (!Set.of("READY", "NO_RECORD").contains(input.getStatus()))
                return finish(result, atomics, decisions("NOT_EVALUATED", Objects.toString(input.getReasonCode(), "COMPARATOR_INPUT_UNAVAILABLE")));
        } catch (RuntimeException unavailable) {
            return finish(result, List.of(), decisions("NOT_EVALUATED", "COMPARATOR_INPUT_UNAVAILABLE"));
        }
        return assess(result, atomics, selected);
    }

    // Deterministic test seam: production atomics are never rewritten by the adapter.
    ObjectNode assess(ObjectNode result, List<ObjectNode> atomics, TranscriptConsequence selected) {
        ObjectNode context = (ObjectNode) result.path("context");
        ObjectNode target = mapper.createObjectNode();
        String issue = targetIssue(context, selected, target);
        if (issue == null) issue = new ClinVarPs1Pm5CriterionEvaluator(mapper).businessInputIssue(context, atomics);
        result.set("targetIdentity", target);
        if (issue != null) return finish(result, atomics, decisions("NOT_EVALUATED", issue));
        Map<String, Reference> references = new LinkedHashMap<>();
        for (ObjectNode atomic : atomics) {
            if (!"HIT".equals(atomic.at("/queryOutcome/status").asText())) continue;
            String scv = metric(atomic, "clinvar.assertion.scv");
            JsonNode trusted = null;
            for (JsonNode ref : audit.path("references")) if (ref.path("scv").asText().equals(scv)) trusted = ref;
            ObjectNode review = result.withArray("referenceAssessments").addObject().put("evidenceId", atomic.path("evidenceId").asText()).put("scv", scv);
            if (trusted == null) {
                review.put("status", "REJECTED").put("reasonCode", "REFERENCE_NOT_IN_AUDITED_CATALOG");
                continue;
            }
            String refIssue = referenceIssue(context, atomic, trusted, target, atomics);
            if (refIssue == null) refIssue = rnaIssue(context, trusted, target);
            if (refIssue != null) {
                issue = refIssue;
                review.put("status", "REJECTED").put("reasonCode", refIssue);
                continue;
            }
            String relation = metric(atomic, "vep.proteinRelation");
            String identity = adapter.canonicalSha256(trusted.path("allele"));
            if (references.containsKey(identity)) {
                review.put("status", "NOT_ADOPTED").put("reasonCode", "SAME_REFERENCE_ALLELE_DUPLICATE_SUBMISSION");
                continue;
            }
            review.put("status", "ELIGIBLE").put("proteinRelation", relation);
            references.put(identity, new Reference(atomic, trusted, relation));
        }
        if (issue != null) return finish(result, atomics, decisions("NOT_EVALUATED", issue));
        if (references.isEmpty()) return finish(result, atomics, decisions("NOT_EVALUATED", "INDEPENDENT_REFERENCE_EVIDENCE_MISSING"));
        List<SpliceAiRawBatchRunner.Query> queries = new ArrayList<>();
        queries.add(new SpliceAiRawBatchRunner.Query("TARGET", key(context.at("/variant/evidenceQueryAllele")), "HNF1A", TRANSCRIPT));
        for (Reference ref : references.values()) {
            queries.add(new SpliceAiRawBatchRunner.Query(ref.audit().path("scv").asText(), key(ref.audit().path("allele")), "HNF1A", TRANSCRIPT));
            ObjectNode display = ((ObjectNode) ref.audit()).deepCopy(); display.put("proteinRelation", ref.relation());
            String wild = target.path("refAminoAcid").asText();
            display.put("targetGrantham", distance(wild, target.path("altAminoAcid").asText()))
                    .put("referenceGrantham", distance(wild, ref.audit().path("alternateAminoAcid").asText()));
            result.withArray("references").add(display);
        }
        result.set("annotationEquivalence", read(mapper, "splice-annotation-proof.json"));
        if (splice == null) return finish(result, atomics, decisions("NOT_EVALUATED", "SPLICE_EVIDENCE_UNAVAILABLE"));
        result.put("rawBatchCalls", 1);
        SpliceAiRawBatchRunner.BatchResult batch;
        try { batch = splice.apply(List.copyOf(queries)); }
        catch (RuntimeException failure) { return finish(result, atomics, decisions("NOT_EVALUATED", "SPLICE_EVIDENCE_UNAVAILABLE")); }
        ObjectNode raw = batch == null ? null : batch.getResult();
        if (batch == null || !"SUCCEEDED".equals(batch.getStatus()) || raw == null)
            return finish(result, atomics, decisions("NOT_EVALUATED", "SPLICE_EVIDENCE_UNAVAILABLE"));
        result.set("rawSpliceBatch", raw);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (var query : queries) {
            Double score = score(raw, query);
            ObjectNode evidence = result.withArray("spliceEvidence").addObject().put("queryId", query.getQueryId())
                    .put("role", "TARGET".equals(query.getQueryId()) ? "TARGET" : "REFERENCE").put("method", "SpliceAI")
                    .put("status", score == null ? "UNAVAILABLE" : "AVAILABLE").put("sourceUrl", policy.at("/splicePolicy/sourceUrl").asText());
            evidence.set("allele", allele(query.getKey()));
            if (score != null) { evidence.put("maximumScore", score); scores.put(query.getQueryId(), score); }
        }
        ObjectNode decisions = decisions("NOT_MET", "NO_ELIGIBLE_PROTEIN_RELATION");
        for (String rule : List.of("PS1", "PM5")) {
            List<Reference> eligible = references.values().stream().filter(ref -> ("PS1".equals(rule)
                    ? "SAME_AMINO_ACID_CHANGE" : "SAME_RESIDUE_DIFFERENT_AMINO_ACID").equals(ref.relation())).toList();
            if (eligible.isEmpty()) continue;
            String spliceIssue = null;
            for (Reference ref : eligible) {
                Double a = scores.get("TARGET"), b = scores.get(ref.audit().path("scv").asText());
                if (a == null || b == null) spliceIssue = "SPLICE_EVIDENCE_UNAVAILABLE";
                else if (a > 0.1 || b > 0.1) spliceIssue = "HNF1A_UNRESOLVED_SPLICE_MECHANISM";
            }
            ObjectNode decision = (ObjectNode) decisions.path(rule);
            if (spliceIssue != null) { decision.put("status", "NOT_EVALUATED").put("reasonCode", spliceIssue).put("applicability", "UNKNOWN"); continue; }
            List<Reference> ordered = eligible.stream().sorted(Comparator
                    .comparingInt((Reference ref) -> strengthRank(strength(rule, ref.audit().path("classification").asText(),
                            distance(target.path("refAminoAcid").asText(), target.path("altAminoAcid").asText()),
                            distance(ref.audit().path("referenceAminoAcid").asText(), ref.audit().path("alternateAminoAcid").asText())))).reversed()
                    .thenComparing(ref -> ref.audit().path("scv").asText())).toList();
            Reference chosen = ordered.get(0);
            String applied = strength(rule, chosen.audit().path("classification").asText(),
                    distance(target.path("refAminoAcid").asText(), target.path("altAminoAcid").asText()),
                    distance(chosen.audit().path("referenceAminoAcid").asText(), chosen.audit().path("alternateAminoAcid").asText()));
            decision.put("status", "MET").put("reasonCode", "TRUSTED_REFERENCE_AND_SPLICE_CONDITIONS_MET")
                    .put("appliedStrength", applied).put("selectedReferenceScv", chosen.audit().path("scv").asText());
            decision.withArray("evidenceIds").add(chosen.atomic().path("evidenceId").asText());
            for (int i = 1; i < ordered.size(); i++) decision.withArray("notAdoptedReferences").addObject()
                    .put("scv", ordered.get(i).audit().path("scv").asText()).put("reasonCode", "SINGLE_REFERENCE_POLICY_NO_ADDITIVE_STRENGTH");
        }
        if ("MET".equals(decisions.at("/PS1/status").asText())) {
            ObjectNode pm5 = (ObjectNode) decisions.path("PM5");
            pm5.put("status", "NOT_EVALUATED").put("reasonCode", "PS1_PM5_OVERLAPPING_SUPPORT_NOT_INDEPENDENT")
                    .put("applicability", "APPLICABLE").put("appliedStrength", "NONE").putArray("evidenceIds");
            pm5.remove("selectedReferenceScv");
        }
        decisions.put("rawSpliceBatchSha256", adapter.canonicalSha256(raw));
        return finish(result, atomics, decisions);
    }

    String targetIssue(ObjectNode context, TranscriptConsequence tx, ObjectNode proof) {
        try { adapter.validateCompleteContext(context); } catch (RuntimeException invalid) { return "CONTEXT_INCOMPLETE"; }
        if (!matchesContext(context)) return "OUTSIDE_HNF1A_PS1_PM5_POLICY";
        JsonNode a = context.at("/variant/evidenceQueryAllele");
        if (!"GRCh38".equals(a.path("assembly").asText()) || !"12".equals(a.path("contig").asText())
                || !"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !"SNV".equals(context.at("/variant/variantType").asText()) || !a.path("reference").asText().matches("[ACGT]")
                || !a.path("alternate").asText().matches("[ACGT]") || a.path("reference").equals(a.path("alternate"))) return "OUTSIDE_HNF1A_NATIVE_MISSENSE_SNV_POLICY";
        if (tx == null || !TRANSCRIPT.equals(tx.getVersionedTranscriptId()) || !("VEP".equals(tx.getSource()) || "VEP_CACHED".equals(tx.getSource())) || !"116.0".equals(tx.getVersion())
                || !"HNF1A".equals(tx.getGeneSymbol()) || !"ENST00000257555".equals(context.at("/transcript/accession").asText())
                || context.at("/transcript/version").asInt() != 11 || !"116.0".equals(context.at("/transcript/annotationSourceVersion").asText())) return "SELECTED_TRANSCRIPT_VERSION_MISMATCH";
        if (!"missense_variant".equals(tx.getConsequence())) return "HNF1A_PROTEIN_ONLY_CONSEQUENCE_REQUIRED";
        var coding = CODING.matcher(Objects.toString(tx.getHgvsc(), ""));
        if (!coding.matches()) return "HNF1A_CODING_POSITION_UNRESOLVED";
        int c = Integer.parseInt(coding.group(1));
        if (!coding.group(2).equals(a.path("reference").asText()) || !coding.group(3).equals(a.path("alternate").asText())) return "HNF1A_GENOMIC_CDS_MAPPING_MISMATCH";
        String issue = sequence(a, proof);
        if (issue != null) return issue;
        if (proof.path("cdsPosition").asInt() != c || !(proof.path("exonOrdinal").asInt() + "/10").equals(tx.getExon())) return "HNF1A_GENOMIC_CDS_MAPPING_MISMATCH";
        String ref = proof.path("refAminoAcid").asText(), alt = proof.path("altAminoAcid").asText();
        String protein = model.path("protein").asText(); int position = proof.path("proteinPosition").asInt();
        if (!protein.equals(tx.getProteinIdentity()) || !Integer.toString(position).equals(tx.getProteinPosition())
                || !(ref + "/" + alt).equals(tx.getAminoAcids())
                || !(proof.path("refCodon").asText() + "/" + proof.path("altCodon").asText()).equals(Objects.toString(tx.getCodons(), "").toUpperCase(Locale.ROOT))
                || !(protein + ":p." + Hnf1aPvs1Runtime.aminoName(ref.charAt(0)) + position + Hnf1aPvs1Runtime.aminoName(alt.charAt(0))).equals(tx.getHgvsp())) return "HNF1A_PROTEIN_CODON_MISMATCH";
        proof.put("hgvsc", tx.getHgvsc()).put("hgvsp", tx.getHgvsp()).put("transcriptModelSha256", adapter.canonicalSha256(model));
        return null;
    }

    private String sequence(JsonNode allele, ObjectNode proof) {
        int pos = allele.path("position").asInt(); JsonNode exon = null;
        for (JsonNode e : model.path("exons")) if (pos >= e.path("genomicStart").asInt() && pos <= e.path("genomicEnd").asInt()) { if (exon != null) return "HNF1A_GENOMIC_CDS_MAPPING_MISMATCH"; exon = e; }
        if (exon == null) return "HNF1A_GENOMIC_CDS_MAPPING_MISMATCH";
        int c = exon.path("cdsStart").asInt() + pos - exon.path("genomicStart").asInt(); String cds = model.path("cdsSequence").asText();
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        if (c < 4 || c > cds.length() - 3 || !ref.matches("[ACGT]") || !alt.matches("[ACGT]") || cds.charAt(c-1) != ref.charAt(0)) return "HNF1A_CDS_REFERENCE_MISMATCH";
        int start = (c-1)/3*3; String before = cds.substring(start, start+3); var changed = new StringBuilder(before); changed.setCharAt((c-1)%3, alt.charAt(0));
        char a = Hnf1aPvs1Runtime.translate(before), b = Hnf1aPvs1Runtime.translate(changed.toString());
        if (a == b || a == '*' || b == '*') return "HNF1A_MISSENSE_NOT_CONFIRMED";
        proof.put("cdsPosition", c).put("proteinPosition", start/3+1).put("refCodon", before).put("altCodon", changed.toString())
                .put("refAminoAcid", Character.toString(a)).put("altAminoAcid", Character.toString(b)).put("exonOrdinal", exon.path("ordinal").asInt());
        return null;
    }

    private String referenceIssue(ObjectNode context, ObjectNode atomic, JsonNode ref, ObjectNode target, List<ObjectNode> atomics) {
        String issue = new ClinVarPs1Pm5CriterionEvaluator(mapper).businessReferenceIssue(context, atomic);
        if (issue != null) return issue;
        if (!"VERIFIED".equals(ref.path("independentEvidenceStatus").asText()) || !ref.path("independentCriteriaSufficient").asBoolean() || ref.path("independentCriteria").isEmpty()) return "REFERENCE_NOT_INDEPENDENT";
        for (JsonNode code : ref.path("independentCriteria")) if (code.asText().startsWith("PS1") || code.asText().startsWith("PM5")) return "REFERENCE_NOT_INDEPENDENT";
        if (!"HGNC:11621".equals(ref.path("hgncId").asText()) || !"HNF1A".equals(ref.path("geneSymbol").asText()) || !"MONDO:0015967".equals(ref.path("mondoId").asText())) return "REFERENCE_DISEASE_MISMATCH";
        JsonNode allele = ref.path("allele");
        if (!"GRCh38".equals(allele.path("assembly").asText()) || !"12".equals(allele.path("contig").asText())) return "REFERENCE_IDENTITY_MISMATCH";
        if (!TRANSCRIPT.equals(ref.path("transcriptId").asText()) || !"NM_000545.8".equals(ref.path("refseqTranscript").asText())) return "REFERENCE_TRANSCRIPT_MISMATCH";
        if (allele.equals(context.at("/variant/evidenceQueryAllele"))) return "REFERENCE_SELF_COMPARISON";
        for (String field : List.of("assembly", "contig", "position", "reference", "alternate")) if (!allele.path(field).asText().equals(metric(atomic, "clinvar.comparator." + field))) return "REFERENCE_IDENTITY_MISMATCH";
        if (!ref.path("variationId").asText().equals(metric(atomic, "clinvar.comparator.variationId"))) return "REFERENCE_IDENTITY_MISMATCH";
        if (!ref.path("classification").asText().equalsIgnoreCase(metric(atomic, "clinvar.assertion.classification")) || !"reviewed by expert panel".equals(metric(atomic, "clinvar.assertion.reviewStatus"))) return "REFERENCE_ASSERTION_CONFLICT";
        if (!ref.path("evaluationDate").asText().equals(date(metric(atomic, "clinvar.assertion.dateLastEvaluated")))) return "REFERENCE_ASSERTION_VERSION_MISMATCH";
        String submitted = metric(atomic, "clinvar.assertion.condition.submittedRaw"), reported = metric(atomic, "clinvar.assertion.condition.reportedRaw");
        if (!(mondo(submitted) || mondo(reported)) || submitted.startsWith("MONDO:") && !mondo(submitted) || reported.startsWith("MONDO:") && !mondo(reported)) return "REFERENCE_DISEASE_MISMATCH";
        ObjectNode refProof = mapper.createObjectNode(); issue = sequence(allele, refProof);
        if (issue != null) return issue;
        for (String role : List.of("target", "comparator")) {
            JsonNode identity = "target".equals(role) ? target : refProof;
            if (!TRANSCRIPT.equals(metric(atomic, "vep." + role + ".transcriptId"))) return "REFERENCE_TRANSCRIPT_MISMATCH";
            for (String[] pair : List.of(new String[]{"proteinPosition", "proteinPosition"}, new String[]{"referenceAminoAcid", "refAminoAcid"}, new String[]{"alternateAminoAcid", "altAminoAcid"}))
                if (!identity.path(pair[1]).asText().equals(metric(atomic, "vep." + role + "." + pair[0]))) return "REFERENCE_PROTEIN_MISMATCH";
        }
        JsonNode vep = ref.path("selectedVep");
        String expectedProtein = model.path("protein").asText() + ":p." + Hnf1aPvs1Runtime.aminoName(refProof.path("refAminoAcid").asText().charAt(0))
                + refProof.path("proteinPosition").asInt() + Hnf1aPvs1Runtime.aminoName(refProof.path("altAminoAcid").asText().charAt(0));
        if (!expectedProtein.equals(vep.path("hgvsp").asText()) || !expectedProtein.equals(ref.path("proteinChange").asText())
                || !("NM_000545.8:c." + refProof.path("cdsPosition").asInt() + allele.path("reference").asText() + ">" + allele.path("alternate").asText()).equals(ref.path("codingChange").asText())) return "REFERENCE_PROTEIN_MISMATCH";
        if (!refProof.path("proteinPosition").equals(vep.path("protein_start")) || !(refProof.path("refCodon").asText()+"/"+refProof.path("altCodon").asText()).equals(vep.path("codons").asText().toUpperCase(Locale.ROOT))
                || !(TRANSCRIPT + ":c." + refProof.path("cdsPosition").asInt() + allele.path("reference").asText() + ">" + allele.path("alternate").asText()).equals(vep.path("hgvsc").asText())
                || !"NM_000545.8".equals(vep.path("mane_select").asText()) || !"missense_variant".equals(vep.path("consequence_terms").path(0).asText()) || vep.path("consequence_terms").size() != 1) return "REFERENCE_PROTEIN_MISMATCH";
        if (!target.path("proteinPosition").equals(refProof.path("proteinPosition")) || !target.path("refAminoAcid").equals(refProof.path("refAminoAcid"))) return "REFERENCE_PROTEIN_MISMATCH";
        String relation = target.path("altAminoAcid").equals(refProof.path("altAminoAcid")) ? "SAME_AMINO_ACID_CHANGE" : "SAME_RESIDUE_DIFFERENT_AMINO_ACID";
        if (!relation.equals(metric(atomic, "vep.proteinRelation"))) return "REFERENCE_PROTEIN_MISMATCH";
        for (JsonNode other : atomics) if (metric(atomic, "clinvar.comparator.variationId").equals(metric(other, "clinvar.comparator.variationId"))
                && Set.of("reviewed by expert panel", "practice guideline").contains(metric(other, "clinvar.assertion.reviewStatus"))
                && (mondo(metric(other, "clinvar.assertion.condition.submittedRaw")) || mondo(metric(other, "clinvar.assertion.condition.reportedRaw")))
                && !Set.of("pathogenic", "likely pathogenic").contains(metric(other, "clinvar.assertion.classification").toLowerCase(Locale.ROOT))) return "REFERENCE_ASSERTION_CONFLICT";
        return null;
    }

    private String rnaIssue(ObjectNode context, JsonNode ref, ObjectNode target) {
        if (!TRANSCRIPT.equals(rna.path("transcript").asText()) || !"HNF1A".equals(rna.path("geneSymbol").asText())) return "RNA_REVIEW_SCOPE_MISMATCH";
        if (!reviewed(rna) || !reviewed(ref.path("rnaReview")) || rna.path("sourceArtifacts").isEmpty()) return "RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON";
        for (JsonNode concern : rna.path("knownConcerns")) if (concern.path("allele").equals(context.at("/variant/evidenceQueryAllele")) || concern.path("allele").equals(ref.path("allele"))) return "PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW";
        for (JsonNode relation : rna.path("reviewedProteinRelations")) if (relation.path("proteinPosition").equals(target.path("proteinPosition"))
                && relation.path("referenceAminoAcid").equals(target.path("refAminoAcid")) && relation.path("targetAlternate").equals(target.path("altAminoAcid"))
                && relation.path("comparatorAlternate").equals(ref.path("alternateAminoAcid"))) return null;
        return "RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON";
    }

    private static boolean reviewed(JsonNode review) { return "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED".equals(review.path("status").asText()) && "UNKNOWN".equals(review.path("rnaEffect").asText()) && !review.path("normalRnaClaimed").asBoolean(true); }
    private static boolean mondo(String s) { return "MONDO:0015967".equals(s) || "MONDO:MONDO:0015967".equals(s); }
    static String strength(String rule, String classification, int target, int reference) {
        if (target < 0 || reference < 0) throw new IllegalArgumentException("Grantham input missing");
        boolean pathogenic = "Pathogenic".equalsIgnoreCase(classification);
        if (!pathogenic && !"Likely pathogenic".equalsIgnoreCase(classification)) throw new IllegalArgumentException("Independent P/LP required");
        return "PS1".equals(rule) ? pathogenic ? "STRONG" : "MODERATE" : pathogenic && target >= reference ? "MODERATE" : "SUPPORTING";
    }
    private static int strengthRank(String s) { return "STRONG".equals(s) ? 3 : "MODERATE".equals(s) ? 2 : 1; }
    int distance(String a, String b) { JsonNode value = matrix.path("matrix").path(a).path(b); if (!value.isIntegralNumber()) throw new IllegalArgumentException("Grantham residue unsupported"); return value.asInt(); }
    private static String date(String value) {
        try { return LocalDate.parse(value).toString(); }
        catch (DateTimeParseException notIso) { try { return LocalDate.parse(value, DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)).toString(); } catch (DateTimeParseException invalid) { return ""; } }
    }
    static String metric(JsonNode atomic, String name) { String value = null; for (JsonNode m : atomic.path("measurements")) if (name.equals(m.path("metricId").asText())) { if (value != null) return ""; value = m.path("rawValue").asText(); } return value == null ? "" : value; }
    private Double score(JsonNode raw, SpliceAiRawBatchRunner.Query query) {
        JsonNode row = null;
        for (JsonNode r : raw.path("results")) if (query.getQueryId().equals(r.path("queryId").asText())) { if (row != null) return null; row = r; }
        if (row == null || !"HIT".equals(row.path("status").asText()) || !row.path("exactAlleleMatch").asBoolean() || !allele(query.getKey()).equals(row.path("allele")) || row.path("annotations").size() != 1) return null;
        JsonNode annotation = row.path("annotations").path(0); if (!"HNF1A".equals(annotation.path("geneSymbol").asText())) return null;
        double max = 0;
        for (String key : List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) {
            double v; try { v = Double.parseDouble(annotation.path("deltaScores").path(key).asText()); } catch (NumberFormatException invalid) { return null; }
            if (!Double.isFinite(v) || v < 0 || v > 1) return null; max = Math.max(max, v);
        }
        return max;
    }
    ObjectNode base(ObjectNode context) {
        var r = mapper.createObjectNode().put("applicability", "APPLICABLE").put("providerCalls", 0).put("evaluatorCalls", 0).put("rawBatchCalls", 0);
        r.set("context", context.deepCopy()); r.set("policy", policy.deepCopy()); r.putObject("targetIdentity"); r.putArray("references"); r.putArray("spliceEvidence"); r.putArray("referenceAssessments"); r.putArray("blockers"); return r;
    }
    private ObjectNode decisions(String status, String reason) {
        var d = mapper.createObjectNode();
        for (String c : List.of("PS1", "PM5")) d.putObject(c).put("status", status).put("reasonCode", reason).put("applicability", "NOT_EVALUATED".equals(status) ? "UNKNOWN" : "APPLICABLE").put("appliedStrength", "NONE").putArray("evidenceIds");
        return d;
    }
    private ObjectNode finish(ObjectNode r, List<ObjectNode> atomics, ObjectNode decisions) {
        ObjectNode context = (ObjectNode) r.path("context");
        ObjectNode selection = ClinVarMissenseBusinessArtifacts.selection(mapper, context, OVERLAY_RESOURCE);
        decisions.put("referenceAuditSha256", adapter.canonicalSha256(audit)).put("rnaReviewSha256", adapter.canonicalSha256(rna)).put("granthamSha256", adapter.canonicalSha256(matrix));
        decisions.put("targetIdentitySha256", adapter.canonicalSha256(r.path("targetIdentity"))).put("referenceAssessmentsSha256", adapter.canonicalSha256(r.path("referenceAssessments")));
        r.set("selection", selection); r.set("atomicEvidence", mapper.valueToTree(atomics)); r.set("scientificDecisions", decisions);
        r.set("criterionEvaluations", mapper.valueToTree(new ClinVarPs1Pm5CriterionEvaluator(mapper).evaluateHnf1aBusiness(context, atomics, selection.path("selectionId").asText(), policy, decisions)));
        r.put("evaluatorCalls", 1);
        for (String code : List.of("PS1", "PM5")) if ("NOT_EVALUATED".equals(decisions.at("/"+code+"/status").asText()))
            r.withArray("blockers").addObject().put("criterionId", "ACMG_AMP_2015:"+code).put("reasonCode", decisions.at("/"+code+"/reasonCode").asText());
        r.set("bundle", ClinVarMissenseBusinessArtifacts.bundle(mapper, r, REGISTRY_RESOURCE, OVERLAY_RESOURCE, "varvis-hnf1a-ps1-pm5-business-entry")); return r;
    }
    static ObjectNode disposition(ObjectMapper mapper, ObjectNode context, String reason, String applicability) {
        ObjectNode result = ClinVarMissenseBusinessRuntime.baseDisposition(mapper, context, reason, applicability);
        result.set("policy", loadProfile(mapper)); return result;
    }
    private NormalizedVariantKey key(JsonNode a) { return new NormalizedVariantKey(a.path("assembly").asText(), a.path("contig").asText(), a.path("position").asInt(), a.path("reference").asText(), a.path("alternate").asText()); }
    private ObjectNode allele(NormalizedVariantKey k) { return mapper.createObjectNode().put("assembly", k.getGenomeAssembly()).put("contig", k.getChromosome()).put("position", k.getPosition()).put("reference", k.getReference()).put("alternate", k.getAlternate()); }
    private record Reference(ObjectNode atomic, JsonNode audit, String relation) { }
}
