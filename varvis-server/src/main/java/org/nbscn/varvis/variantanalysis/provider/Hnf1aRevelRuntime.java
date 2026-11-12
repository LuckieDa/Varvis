package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Explicit GN017 v3.1 protein prediction: a bounded precomputed query, never model inference. */
public final class Hnf1aRevelRuntime {
    public static final String TRANSCRIPT = "ENST00000257555.11";
    public static final String PROFILE_SHA256 = "5e6ea7f949c3bc8c49a2e9069ec29cf6147eb054c1d0bcbcd19e6fe0ceb29060";
    public static final String PROFILE_RESOURCE = "/acmg/hnf1a-revel-business-profile-v1.json";
    private static final String C14N = "varvis-json-c14n-v1";
    private static final Pattern CODING = Pattern.compile("^ENST00000257555\\.11:c\\.([1-9][0-9]*)([ACGT])>([ACGT])$");
    private static final String SOURCE_HEADER = "chr,hg19_pos,grch38_pos,ref,alt,aaref,aaalt,REVEL,Ensembl_transcriptid";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    private final RuntimeEvidenceV2ProviderWiring.ResourceBinding vep;
    private final ObjectNode profile;
    private final ObjectNode registry;
    private final ObjectNode model;
    private final Resource resource;
    private final AtomicInteger providerCalls = new AtomicInteger();
    private final AtomicInteger evaluatorCalls = new AtomicInteger();

    public Hnf1aRevelRuntime(ObjectMapper mapper) { this(mapper, null, false); }
    public Hnf1aRevelRuntime(ObjectMapper mapper, Path manifest) { this(mapper, manifest, false); }
    private Hnf1aRevelRuntime(ObjectMapper mapper, Path manifest, boolean disabled) { this(mapper, manifest, disabled ? "REVEL_RESOURCE_DISABLED" : null); }
    private Hnf1aRevelRuntime(ObjectMapper mapper, Path manifest, String configurationIssue) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        profile = loadProfile(mapper);
        registry = load(mapper, profile.at("/registryBinding/registryClasspathResource").asText());
        model = load(mapper, profile.at("/transcriptModel/classpathResource").asText());
        require(profile.at("/transcriptModel/canonicalSha256").asText().equals(adapter.canonicalSha256(model)), "REVEL_TRANSCRIPT_MODEL_IDENTITY_INVALID");
        wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        vep = wiring.bind(profile, "VEP_116_GRCH38");
        resource = configurationIssue == null ? readResource(manifest) : emptyResource(configurationIssue);
    }

    public static Hnf1aRevelRuntime unavailable(ObjectMapper mapper) { return new Hnf1aRevelRuntime(mapper); }
    public static Hnf1aRevelRuntime disabled(ObjectMapper mapper) { return new Hnf1aRevelRuntime(mapper, null, true); }
    public static Hnf1aRevelRuntime configurationUnavailable(ObjectMapper mapper) { return new Hnf1aRevelRuntime(mapper, null, "REVEL_CONFIGURATION_INVALID"); }
    public int providerCalls() { return providerCalls.get(); }
    public int evaluatorCalls() { return evaluatorCalls.get(); }
    public String resourceIdentitySha256() { return resource.identity; }
    public ObjectNode providerInfo() {
        var info = mapper.createObjectNode().put("provider", "REVEL").put("queryType", "PRECOMPUTED_SCORE_LOOKUP")
                .put("modelInferenceCalls", 0).put("profileSha256", PROFILE_SHA256)
                .put("resourceIdentitySha256", resource.identity).put("resourceStatus", resource.issue == null ? "AVAILABLE" : "REVEL_RESOURCE_DISABLED".equals(resource.issue) ? "DISABLED" : "UNAVAILABLE");
        if (resource.issue != null) info.put("reasonCode", resource.issue);
        info.set("manifest", resource.manifest.deepCopy());
        return info;
    }

    public static ObjectNode loadProfile(ObjectMapper mapper) {
        var result = load(mapper, PROFILE_RESOURCE);
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        require(PROFILE_SHA256.equals(adapter.canonicalSha256(result)), "REVEL_PROFILE_IDENTITY_INVALID");
        JsonNode registry = result.path("registryBinding");
        require(registry.path("registrySha256").asText().equals(sha(bytes(registry.path("registryClasspathResource").asText()))), "REVEL_REGISTRY_IDENTITY_INVALID");
        JsonNode overlay = result.path("overlayBinding");
        require(overlay.path("registrySha256").asText().equals(sha(bytes(overlay.path("registryClasspathResource").asText())))
                && overlay.path("authorityCatalogSha256").asText().equals(sha(bytes(overlay.path("authorityCatalogClasspathResource").asText()))), "REVEL_OVERLAY_AUTHORITY_IDENTITY_INVALID");
        JsonNode overlayDocument = load(mapper, overlay.path("registryClasspathResource").asText());
        require(overlay.path("overlayCanonicalSha256").asText().equals(adapter.canonicalSha256(overlayDocument.path("overlays").path(0))), "REVEL_OVERLAY_IDENTITY_INVALID");
        return result;
    }

    public static boolean hasProductionPolicy(JsonNode relationship, String moi) {
        return relationship != null && "HGNC:11621".equals(relationship.path("hgncId").asText())
                && "MONDO:0015967".equals(relationship.path("mondoId").asText()) && "AUTOSOMAL_DOMINANT".equals(moi);
    }
    public static boolean matchesContext(JsonNode context) {
        return context != null && "HGNC:11621".equals(context.at("/gene/hgncId").asText())
                && "MONDO:0015967".equals(context.at("/disease/mondoId").asText()) && "AUTOSOMAL_DOMINANT".equals(context.path("moi").asText());
    }
    /** Context offering does not inspect resource presence or any prediction score. */
    public static boolean contextTranscript(TranscriptConsequence tx) {
        return tx != null && TRANSCRIPT.equals(tx.getVersionedTranscriptId()) && "HNF1A".equals(tx.getGeneSymbol())
                && "116.0".equals(tx.getVersion()) && terms(tx).contains("missense_variant");
    }

    public static ObjectNode registryPairing(ObjectMapper mapper) {
        var p = loadProfile(mapper); var overlay = p.path("overlayBinding"); var rule = p.path("registryBinding");
        return mapper.createObjectNode().put("authorityCatalogId", overlay.path("authorityCatalogId").asText())
                .put("authorityCatalogSha256", overlay.path("authorityCatalogSha256").asText())
                .put("ruleRegistryId", rule.path("registryId").asText()).put("ruleRegistrySha256", rule.path("registrySha256").asText())
                .put("overlayRegistryId", overlay.path("registryId").asText()).put("overlayRegistrySha256", overlay.path("registrySha256").asText())
                .put("authorityCutoff", overlay.path("authorityCutoff").asText()).put("asOf", overlay.path("asOf").asText());
    }
    public static ObjectNode selection(ObjectMapper mapper, ObjectNode context) {
        var p = loadProfile(mapper); var b = p.path("overlayBinding"); var a = new RuntimeEvidenceV2Adapter(mapper);
        boolean exact = matchesContext(context) && "ENST00000257555".equals(context.at("/transcript/accession").asText())
                && context.at("/transcript/version").asInt() == 11;
        var s = mapper.createObjectNode().put("schemaVersion", "cspec-selection-v1").put("canonicalizationProfile", C14N)
                .put("contextId", context.path("contextId").asText()).put("overlayRegistryId", b.path("registryId").asText())
                .put("overlayRegistrySha256", b.path("registrySha256").asText()).put("policyId", "CSPEC_EXACT_CONTEXT_SELECTION_V1")
                .put("outcome", exact ? "SINGLE_SELECTABLE_MATCH" : "ZERO_MATCH");
        ArrayNode candidates = s.putArray("candidateOverlayIds");
        if (exact) {
            candidates.add(b.path("overlayId").asText());
            s.put("selectedOverlayId", b.path("overlayId").asText()).put("selectedOverlaySha256", b.path("overlaySha256").asText());
        }
        s.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        s.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        var identity = s.deepCopy(); identity.remove(List.of("conflict", "review"));
        s.put("selectionId", "cspec-selection-sha256-" + a.canonicalSha256(identity));
        return s;
    }

    public Result evaluate(ObjectNode context, VariantInterpretation candidate) {
        return evaluate(context, candidate, java.util.Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4"), false);
    }
    Result evaluate(ObjectNode context, VariantInterpretation candidate, java.util.Set<String> clauses) {
        return evaluate(context, candidate, clauses, true);
    }
    private Result evaluate(ObjectNode context, VariantInterpretation candidate, java.util.Set<String> clauses, boolean honorCancellation) {
        if (!java.util.Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4").containsAll(clauses))
            throw new IllegalArgumentException("REVEL_CLAUSE_MASK_INVALID");
        ObjectNode selected = selection(mapper, context);
        var input = mapper.createObjectNode().put("predictionPath", "HNF1A_REVEL").put("tool", "REVEL")
                .put("profileSha256", PROFILE_SHA256).put("selectedTranscript", TRANSCRIPT)
                .put("resourceIdentitySha256", resource.identity).put("evidenceMechanism", "PROTEIN_MISSENSE_PREDICTION")
                .put("bp7DependencyEligible", false).put("modelInferenceCalls", 0);
        input.set("policyAuthority", profile.at("/policy/authority").deepCopy());
        input.set("providerInfo", providerInfo());
        if (honorCancellation && Thread.currentThread().isInterrupted())
            return interrupted(selected, input, List.of(), 0, List.of());
        if (clauses.isEmpty()) return new Result(selected, List.of(), List.of(), input, List.of(), 0, 0);
        String issue;
        try { adapter.validateCompleteContext(context); issue = contextIssue(context); }
        catch (RuntimeException invalid) { issue = "REVEL_CONTEXT_IDENTITY_INVALID"; }
        if (issue != null) return blocked(selected, input, issue);
        TranscriptConsequence tx = wiring.selectedVepConsequence(context, candidate);
        if (tx == null) return blocked(selected, input, "REVEL_SELECTED_TRANSCRIPT_NOT_UNIQUE");
        ObjectNode annotation;
        try { annotation = wiring.projectStoredTranscript(context, candidate, tx, vep); }
        catch (RuntimeException invalid) { return blocked(selected, input, "REVEL_VEP_SOURCE_IDENTITY_INVALID"); }
        input.put("source", tx.getSource()).put("annotationVersion", tx.getVersion()).put("versionedTranscriptId", tx.getVersionedTranscriptId())
                .put("hgvsc", tx.getHgvsc()).put("hgvsp", tx.getHgvsp()).put("consequence", tx.getConsequence());
        try { issue = resolve(context, tx, input); }
        catch (RuntimeException invalid) { issue = "REVEL_VARIANT_TRANSCRIPT_INPUT_INVALID"; }
        if (issue != null) return evaluated(context, selected, input, List.of(annotation), null, issue, 0, clauses, honorCancellation);
        if (resource.issue != null) {
            ObjectNode unavailable = atomic(context, List.of(), resource.issue, input);
            return evaluated(context, selected, input, List.of(annotation, unavailable), null, resource.issue, 0, clauses, honorCancellation);
        }
        if (honorCancellation && Thread.currentThread().isInterrupted())
            return interrupted(selected, input, List.of(annotation), 0, List.of());
        providerCalls.incrementAndGet();
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        List<Row> rows = resource.rows.getOrDefault(key(allele.path("position").asInt(), allele.path("reference").asText(), allele.path("alternate").asText()), List.of());
        Query query = resolveRows(rows, input);
        input.set("sourceRecords", mapper.valueToTree(rows));
        if (query.score != null) input.put("score", query.score.toPlainString());
        ObjectNode prediction = atomic(context, rows, query.issue, input);
        return evaluated(context, selected, input, List.of(annotation, prediction), query.score, query.issue, 1, clauses, honorCancellation);
    }

    private String contextIssue(ObjectNode context) {
        if (!matchesContext(context) || !"HNF1A".equals(context.at("/gene/symbol").asText())) return "REVEL_POLICY_NOT_APPLICABLE";
        String selection = context.at("/transcript/selectionPolicy").asText();
        if (selection.contains("PM2_CONTEXT")) return "REVEL_TRANSCRIPT_CONFIRMATION_REQUIRED";
        if (!TRANSCRIPT.equals(context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asInt())
                || !"116.0".equals(context.at("/transcript/annotationSourceVersion").asText())) return "REVEL_TRANSCRIPT_NOT_SUPPORTED";
        JsonNode v = context.path("variant"); JsonNode a = v.path("evidenceQueryAllele");
        if (!"SNV".equals(v.path("variantType").asText()) || !"NATIVE".equals(v.at("/assemblyLineage/mappingStatus").asText())
                || !"GRCh38".equals(a.path("assembly").asText()) || !"12".equals(a.path("contig").asText())
                || !a.path("reference").asText().matches("[ACGT]") || !a.path("alternate").asText().matches("[ACGT]")
                || a.path("reference").equals(a.path("alternate"))) return "REVEL_NATIVE_GRCH38_MISSENSE_SNV_REQUIRED";
        return null;
    }
    private String resolve(ObjectNode context, TranscriptConsequence tx, ObjectNode input) {
        Set<String> terms = terms(tx);
        if (terms.stream().anyMatch(t -> t.contains("splice") || t.equals("intron_variant"))) return "REVEL_SPLICE_BRANCH_NOT_IMPLEMENTED";
        if (!terms.contains("missense_variant") || terms.stream().anyMatch(t -> Set.of("stop_gained", "stop_lost", "start_lost", "frameshift_variant").contains(t)))
            return "REVEL_CONSEQUENCE_NOT_APPLICABLE";
        var coding = CODING.matcher(Objects.toString(tx.getHgvsc(), ""));
        if (!coding.matches()) return "REVEL_CODING_POSITION_UNRESOLVED";
        int c = Integer.parseInt(coding.group(1)); int g = context.at("/variant/evidenceQueryAllele/position").asInt();
        String ref = context.at("/variant/evidenceQueryAllele/reference").asText(); String alt = context.at("/variant/evidenceQueryAllele/alternate").asText();
        JsonNode exon = exon(g); String cds = model.path("cdsSequence").asText();
        if (exon == null || c != exon.path("cdsStart").asInt() + g - exon.path("genomicStart").asInt()
                || !ref.equals(coding.group(2)) || !alt.equals(coding.group(3))) return "REVEL_GENOMIC_CDS_MAPPING_MISMATCH";
        if (c < 4 || c > cds.length() - 3 || cds.charAt(c - 1) != ref.charAt(0)) return "REVEL_CDS_REFERENCE_MISMATCH";
        if (!(exon.path("ordinal").asInt() + "/10").equals(tx.getExon())) return "REVEL_EXON_STRUCTURE_MISMATCH";
        int start = (c - 1) / 3 * 3; int amino = start / 3 + 1;
        String original = cds.substring(start, start + 3); var change = new StringBuilder(original); change.setCharAt((c - 1) % 3, alt.charAt(0));
        String altered = change.toString(); char refAa = Hnf1aPvs1Runtime.translate(original); char altAa = Hnf1aPvs1Runtime.translate(altered);
        if (refAa == altAa || refAa == '*' || altAa == '*') return "REVEL_MISSENSE_NOT_CONFIRMED";
        if (!model.path("protein").asText().equals(tx.getProteinIdentity()) || !Integer.toString(amino).equals(tx.getProteinPosition())
                || !(refAa + "/" + altAa).equals(tx.getAminoAcids())
                || !(original + "/" + altered).equals(Objects.toString(tx.getCodons(), "").toUpperCase(Locale.ROOT))
                || !(model.path("protein").asText() + ":p." + Hnf1aPvs1Runtime.aminoName(refAa) + amino + Hnf1aPvs1Runtime.aminoName(altAa)).equals(tx.getHgvsp()))
            return "REVEL_PROTEIN_CODON_MISMATCH";
        input.put("cdsPosition", c).put("proteinPosition", amino).put("refCodon", original).put("altCodon", altered)
                .put("refAminoAcid", Character.toString(refAa)).put("altAminoAcid", Character.toString(altAa))
                .put("transcriptModelSha256", profile.at("/transcriptModel/canonicalSha256").asText())
                .put("sourceTranscriptVersionStatus", "UNVERSIONED_SOURCE_NO_VERSION_EQUIVALENCE_CLAIM")
                .put("proteinIdentityMappingStatus", "CURRENT_VERSIONED_CDS_GENOMIC_CODON_AND_STORED_VEP_VERIFIED");
        return null;
    }
    private JsonNode exon(int position) {
        for (JsonNode exon : model.path("exons")) if (position >= exon.path("genomicStart").asInt() && position <= exon.path("genomicEnd").asInt()) return exon;
        return null;
    }
    private Query resolveRows(List<Row> rows, ObjectNode input) {
        if (rows.isEmpty()) return new Query(null, resource.missingMappings ? "REVEL_PUBLISHED_MAPPING_MISSING" : "REVEL_NO_RECORD");
        List<Row> selected = rows.stream().filter(r -> selectedTranscript(r.transcripts)).toList();
        if (selected.isEmpty()) return new Query(null, "REVEL_SOURCE_TRANSCRIPT_MISMATCH");
        BigDecimal score = null; Integer originalPosition = null;
        for (Row row : selected) {
            if (!row.refAa.equals(input.path("refAminoAcid").asText()) || !row.altAa.equals(input.path("altAminoAcid").asText())) return new Query(null, "REVEL_SOURCE_AMINO_ACID_MISMATCH");
            if (row.hg19Position <= 0) return new Query(null, "REVEL_PUBLISHED_MAPPING_MISSING");
            if (originalPosition != null && originalPosition.intValue() != row.hg19Position) return new Query(null, "REVEL_MAPPING_AMBIGUOUS");
            originalPosition = row.hg19Position;
            if (row.score.isBlank() || Set.of(".", "NA", "nan", "NaN").contains(row.score)) return new Query(null, "REVEL_SCORE_MISSING");
            BigDecimal value;
            try { value = new BigDecimal(row.score); } catch (NumberFormatException invalid) { return new Query(null, "REVEL_SCORE_INVALID"); }
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) return new Query(null, "REVEL_SCORE_INVALID");
            if (score != null && score.compareTo(value) != 0) return new Query(null, "REVEL_RECORD_CONFLICT");
            score = value;
        }
        input.put("sourceOriginalAssembly", "GRCh37").put("sourceOriginalPosition", originalPosition)
                .put("sourcePublishedAssembly", "GRCh38").put("sourceMappingMethod", "REVEL_publisher_liftOver")
                .put("sourceTranscriptMatch", "UNVERSIONED_ACCESSION_AND_ALLELE_SPECIFIC_SEQUENCE_VALIDATED")
                .put("matchingSourceRecordCount", selected.size());
        return new Query(score, null);
    }

    private Result evaluated(ObjectNode context, ObjectNode selection, ObjectNode input, List<ObjectNode> evidence, BigDecimal score, String issue, int queries, java.util.Set<String> clauses, boolean honorCancellation) {
        var evaluations = new ArrayList<ObjectNode>();
        for (JsonNode rule : profile.at("/policy/criteria")) if (clauses.contains(rule.path("criterionId").asText())) {
            if (honorCancellation && Thread.currentThread().isInterrupted())
                return interrupted(selection, input, evidence, queries, evaluations);
            evaluations.add(evaluation(context, selection, input, evidence, rule, score, issue));
        }
        if (honorCancellation && Thread.currentThread().isInterrupted())
            return interrupted(selection, input, evidence, queries, evaluations);
        evaluatorCalls.addAndGet(evaluations.size());
        return new Result(selection, evidence, evaluations, input, issue == null ? List.of() : List.of(new Blocker(issue, issue)), queries, evaluations.size());
    }
    private Result interrupted(ObjectNode selection, ObjectNode input, List<ObjectNode> evidence, int queries, List<ObjectNode> completed) {
        String reason = "PREDICTION_BUNDLE_INTERRUPTED";
        input.put("reasonCode", reason).put("executionStatus", "CANCELLED");
        evaluatorCalls.addAndGet(completed.size());
        return new Result(selection, evidence, List.copyOf(completed), input, List.of(new Blocker(reason, reason)), queries, completed.size());
    }
    private Result blocked(ObjectNode selection, ObjectNode input, String issue) {
        input.put("reasonCode", issue);
        return new Result(selection, List.of(), List.of(), input, List.of(new Blocker(issue, issue)), 0, 0);
    }
    private ObjectNode evaluation(ObjectNode context, ObjectNode selection, ObjectNode input, List<ObjectNode> atomic, JsonNode rule, BigDecimal score, String issue) {
        boolean ready = issue == null && score != null; BigDecimal threshold = new BigDecimal(rule.path("threshold").asText());
        boolean met = ready && score != null && ("GE".equals(rule.path("comparison").asText()) ? score.compareTo(threshold) >= 0 : score.compareTo(threshold) <= 0);
        var out = mapper.createObjectNode().put("schemaVersion", "criterion-evaluation-v2").put("canonicalizationProfile", C14N)
                .put("contextId", context.path("contextId").asText()).put("criterionId", rule.path("criterionId").asText())
                .put("baseStrength", "SUPPORTING").put("appliedStrength", met ? "SUPPORTING" : "NONE");
        var binding = out.putObject("ruleBinding").put("ruleRegistryId", profile.at("/registryBinding/registryId").asText())
                .put("ruleRegistrySha256", profile.at("/registryBinding/registrySha256").asText())
                .put("currentGeneralDesignation", rule.path("criterionId").asText().substring("ACMG_AMP_2015:".length()))
                .put("cspecSelectionId", selection.path("selectionId").asText());
        // Frozen ruleBinding describes the general registry; GN017 is the selected overlay authority.
        for (JsonNode registered : registry.path("criteria")) if (registered.path("criterionId").equals(rule.path("criterionId")))
            binding.set("authoritySourceIds", registered.at("/currentGeneral/authoritySourceIds").deepCopy());
        var applicable = out.putObject("criterionApplicability").put("status", ready ? "APPLICABLE" : "REVEL_CONSEQUENCE_NOT_APPLICABLE".equals(issue) ? "NOT_APPLICABLE" : "UNKNOWN");
        var result = out.putObject("evaluationResult").put("status", ready ? met ? "MET" : "NOT_MET" : "NOT_EVALUATED");
        if (!ready) { applicable.put("reasonCode", issue); result.put("reasonCode", issue); }
        ArrayNode evidenceIds = out.putArray("evidenceIds"); atomic.stream().map(a -> a.path("evidenceId").asText()).sorted().forEach(evidenceIds::add);
        var trace = out.putObject("deterministicTrace").put("executionStatus", ready ? "COMPLETED" : "NOT_RUN")
                .put("evaluatorId", "hnf1a-revel-pp3-bp4").put("evaluatorVersion", profile.path("profileVersion").asText());
        trace.set("inputEvidenceIds", evidenceIds.deepCopy());
        ArrayNode parameters = trace.putArray("parameters");
        Map<String, String> values = new java.util.TreeMap<>();
        values.put("profileSha256", PROFILE_SHA256); values.put("resourceIdentitySha256", resource.identity);
        values.put("evidenceMechanism", "PROTEIN_MISSENSE_PREDICTION"); values.put("bp7DependencyEligible", "false");
        values.put("tool", "REVEL"); values.put("threshold", rule.path("threshold").asText()); values.put("comparison", rule.path("comparison").asText());
        values.put("policyId", profile.at("/policy/policyId").asText()); values.put("policyVersion", "GN017 3.1");
        values.put("resolvedInputSha256", adapter.canonicalSha256(input)); if (score != null) values.put("revel.score", score.toPlainString());
        values.forEach((name, value) -> parameters.addObject().put("name", name).put("value", value));
        var step = trace.putArray("steps").addObject().put("stepCode", ready ? "GN017_REVEL_THRESHOLD" : issue).put("outcome", ready ? met ? "TRUE" : "FALSE" : "UNKNOWN");
        step.set("inputEvidenceIds", evidenceIds.deepCopy());
        if (ready) {
            trace.put("algorithmId", profile.at("/policy/policyId").asText()).put("algorithmVersion", profile.path("profileVersion").asText());
            trace.put("traceSha256", adapter.canonicalSha256(trace));
        } else trace.put("reasonCode", issue);
        out.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        out.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        var identity = out.deepCopy(); identity.remove(List.of("conflict", "review"));
        out.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(identity));
        return out;
    }

    private ObjectNode atomic(ObjectNode context, List<Row> rows, String issue, ObjectNode input) {
        String now = Instant.now().toString(); boolean available = resource.issue == null;
        var carrier = mapper.createObjectNode().put("evidenceType", "COMPUTATIONAL_PREDICTION").put("producedAt", now)
                .put("sourcePayloadSha256", adapter.canonicalSha256(mapper.valueToTree(rows)));
        carrier.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        var source = carrier.putObject("source").put("sourceId", "REVEL_HNF1A_2021").put("resourceVersion", "REVEL 1.3 / HNF1A CDS map v1")
                .put("resourceSha256", resource.identity).put("retrievedAt", available ? resource.manifest.at("/source/retrievedAt").asText() : now);
        String url = available ? resource.manifest.at("/source/url").asText() : profile.at("/revelResource/sourceUrl").asText();
        if (url.isBlank()) url = resource.manifest.at("/source/archiveUrl").asText();
        source.putArray("sourceLocators").addObject().put("locatorType", "REPOSITORY_ARTIFACT").put("locatorValue", url)
                .put("artifactSha256", available ? resource.manifest.at("/source/sha256").asText() : PROFILE_SHA256).put("retrievedAt", source.path("retrievedAt").asText());
        ArrayNode records = source.putArray("sourceRecords");
        for (Row row : rows) {
            String id = "REVEL:1.3:chr12:line:" + row.sourceLine;
            records.addObject().put("recordId", id).putObject("locator").put("locatorType", "DATABASE_RECORD")
                    .put("locatorValue", "REVEL 1.3 chromosome 12 source CSV line " + row.sourceLine)
                    .put("sourceRecordId", id).put("artifactSha256", resource.manifest.at("/source/sha256").asText()).put("retrievedAt", source.path("retrievedAt").asText());
        }
        var execution = carrier.putObject("resourceExecution").put("status", available ? "SUCCEEDED" : "REVEL_RESOURCE_DISABLED".equals(issue) ? "DISABLED" : "NOT_RUN");
        if (available) execution.put("attemptedAt", now).put("completedAt", now); else execution.put("reasonCode", issue);
        var availability = carrier.putObject("resourceAvailability").put("status", available ? "AVAILABLE" : "UNAVAILABLE"); if (!available) availability.put("reasonCode", issue);
        var query = carrier.putObject("queryOutcome").put("status", available ? rows.isEmpty() ? "NO_RECORD" : "HIT" : "NOT_QUERIED").put("recordCount", rows.size());
        if (available) query.put("queryFingerprint", adapter.canonicalSha256(context.at("/variant/evidenceQueryAllele")));
        var applicability = carrier.putObject("resourceApplicability").put("status", issue == null ? "APPLICABLE" : "UNKNOWN");
        if (issue != null) applicability.put("reasonCode", issue);
        var measurements = carrier.putArray("measurements");
        if (issue == null && input.has("score")) measurements.addObject().put("metricId", "revel.score").put("rawValue", input.path("score").asText())
                .put("normalizedValue", input.path("score").asText()).put("unit", "dimensionless").put("calibrationProfileId", "HNF1A_GN017_3_1_REVEL@1.0.0");
        return adapter.adapt(context, carrier);
    }

    private Resource readResource(Path path) {
        if (path == null || !Files.isRegularFile(path)) return emptyResource("REVEL_RESOURCE_UNAVAILABLE");
        ObjectNode manifest;
        try {
            JsonNode value = mapper.readTree(Files.readAllBytes(path));
            if (!(value instanceof ObjectNode object)) return emptyResource("REVEL_RESOURCE_IDENTITY_INVALID");
            manifest = object;
        } catch (IOException invalid) { return emptyResource("REVEL_RESOURCE_UNAVAILABLE"); }
        String identity;
        try { identity = adapter.canonicalSha256(manifest); }
        catch (RuntimeException invalid) { return emptyResource("REVEL_RESOURCE_IDENTITY_INVALID"); }
        try {
            require("revel-hnf1a-resource-v1".equals(manifest.path("schemaVersion").asText()) && "1.3".equals(manifest.path("resourceVersion").asText())
                    && "hnf1a-revel-cds-map-v1".equals(manifest.path("derivationVersion").asText()), "REVEL_RESOURCE_VERSION_INVALID");
            require(profile.at("/transcriptModel/canonicalSha256").asText().equals(manifest.path("transcriptModelSha256").asText()), "REVEL_RESOURCE_TRANSCRIPT_MODEL_MISMATCH");
            JsonNode scope = manifest.path("scope"); JsonNode source = manifest.path("source");
            require("HGNC:11621".equals(scope.path("hgncId").asText()) && TRANSCRIPT.equals(scope.path("transcript").asText())
                    && "NM_000545.8".equals(scope.path("refseqTranscript").asText()) && "GRCh38".equals(scope.path("assembly").asText())
                    && "12".equals(scope.path("contig").asText()), "REVEL_RESOURCE_SCOPE_INVALID");
            require("GRCh37".equals(source.path("originalAssembly").asText()) && "GRCh38".equals(source.path("publishedAssembly").asText())
                    && "REVEL_publisher_liftOver".equals(source.path("mappingMethod").asText()), "REVEL_SOURCE_MAPPING_IDENTITY_INVALID");
            Instant.parse(source.path("retrievedAt").asText());
            Path base = path.toAbsolutePath().normalize().getParent(); require(base != null, "REVEL_RESOURCE_PATH_INVALID");
            Path data = confined(base, manifest.path("dataFile").asText()); Path csv = confined(base, source.path("file").asText());
            require(manifest.path("dataSha256").asText().equals(fileSha(data)) && source.path("sha256").asText().equals(fileSha(csv)), "REVEL_RESOURCE_CHECKSUM_MISMATCH");
            JsonNode validation = manifest.path("selectedTranscriptValidation");
            if (validation.isObject()) {
                require(validation.path("sha256").asText().equals(fileSha(confined(base, validation.path("file").asText()))), "REVEL_TRANSCRIPT_VALIDATION_CHECKSUM_MISMATCH");
                if (validation.has("codingCoverageFile")) require(validation.path("codingCoverageSha256").asText().equals(fileSha(confined(base,
                        validation.path("codingCoverageFile").asText()))), "REVEL_CODING_COVERAGE_CHECKSUM_MISMATCH");
            }
            Map<Integer, String> sourceLines = new HashMap<>(); List<Row> all = new ArrayList<>();
            try (var reader = Files.newBufferedReader(data, StandardCharsets.UTF_8)) {
                require((SOURCE_HEADER.replace(',', '\t') + "\tsourceLine").equals(reader.readLine()), "REVEL_RESOURCE_HEADER_INVALID");
                String line; while ((line = reader.readLine()) != null) {
                    String[] fields = line.split("\t", -1); require(fields.length == 10, "REVEL_RESOURCE_ROW_INVALID");
                    int sourceLine = Integer.parseInt(fields[9]); require(sourceLine > 1, "REVEL_RESOURCE_SOURCE_LINE_INVALID");
                    String raw = String.join(",", java.util.Arrays.copyOf(fields, 9));
                    require(sourceLines.put(sourceLine, raw) == null, "REVEL_RESOURCE_SOURCE_LINE_DUPLICATE");
                    require("12".equals(fields[0]) && fields[3].matches("[ACGT]") && fields[4].matches("[ACGT]") && !fields[3].equals(fields[4]), "REVEL_RESOURCE_ALLELE_INVALID");
                    all.add(new Row(Integer.parseInt(fields[1]), missing(fields[2]) ? 0 : Integer.parseInt(fields[2]), fields[3], fields[4], fields[5], fields[6], fields[7], fields[8], sourceLine));
                }
            }
            require(!all.isEmpty(), "REVEL_RESOURCE_EMPTY");
            try (var reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
                require(SOURCE_HEADER.equals(reader.readLine()), "REVEL_SOURCE_HEADER_INVALID");
                int number = 1; String line;
                while ((line = reader.readLine()) != null) {
                    number++; String expected = sourceLines.remove(number);
                    if (expected != null) require(expected.equals(line), "REVEL_SOURCE_ROW_MISMATCH");
                    String[] fields = line.split(",", -1);
                    if (fields.length == 9 && "12".equals(fields[0]) && selectedTranscript(fields[8])) {
                        int mapped = missing(fields[2]) ? 0 : Integer.parseInt(fields[2]);
                        int original = Integer.parseInt(fields[1]);
                        boolean inScope = mapped > 0 ? mapped >= scope.path("minPosition").asInt() && mapped <= scope.path("maxPosition").asInt()
                                : original >= scope.path("originalMinPosition").asInt() && original <= scope.path("originalMaxPosition").asInt();
                        require(!inScope || expected != null, "REVEL_DERIVED_INDEX_INCOMPLETE");
                    }
                }
            }
            require(sourceLines.isEmpty(), "REVEL_SOURCE_LINE_MISSING");
            Map<String, List<Row>> rows = new HashMap<>(); boolean missingMappings = false;
            for (Row row : all) {
                if (row.grch38Position == 0) { missingMappings = true; continue; }
                require(row.grch38Position >= scope.path("minPosition").asInt() && row.grch38Position <= scope.path("maxPosition").asInt(), "REVEL_RESOURCE_SCOPE_INVALID");
                rows.computeIfAbsent(key(row.grch38Position, row.reference, row.alternate), unused -> new ArrayList<>()).add(row);
            }
            Map<String, List<Row>> frozen = new HashMap<>(); rows.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
            return new Resource(manifest.deepCopy(), identity, Map.copyOf(frozen), missingMappings, null);
        } catch (RuntimeException | IOException invalid) {
            String issue = invalid.getMessage();
            if (issue == null || !issue.matches("REVEL_[A-Z0-9_]+")) issue = "REVEL_RESOURCE_IDENTITY_INVALID";
            return new Resource(manifest.deepCopy(), identity, Map.of(), false, issue);
        }
    }
    private Resource emptyResource(String issue) {
        ObjectNode identity = mapper.createObjectNode().put("profileSha256", PROFILE_SHA256).put("resourceStatus", issue);
        return new Resource(identity, adapter.canonicalSha256(identity), Map.of(), false, issue);
    }
    private static Path confined(Path base, String name) {
        require(!name.isBlank() && !Path.of(name).isAbsolute(), "REVEL_RESOURCE_PATH_INVALID");
        Path resolved = base.resolve(name).normalize(); require(resolved.startsWith(base) && !resolved.equals(base), "REVEL_RESOURCE_PATH_INVALID");
        return resolved;
    }
    private static boolean missing(String value) { return value.isBlank() || Set.of(".", "NA", "nan", "NaN").contains(value); }
    private static boolean selectedTranscript(String value) { return java.util.Arrays.asList(value.split("[;&]", -1)).contains("ENST00000257555"); }
    private static String key(int position, String reference, String alternate) { return position + ":" + reference + ":" + alternate; }
    private static Set<String> terms(TranscriptConsequence tx) { return new HashSet<>(List.of(Objects.toString(tx.getConsequence(), "").split("[&,|]"))); }
    private static void require(boolean valid, String issue) { if (!valid) throw new IllegalArgumentException(issue); }
    private static ObjectNode load(ObjectMapper mapper, String path) {
        try { JsonNode node = mapper.readTree(bytes(path)); if (node instanceof ObjectNode object) return object; }
        catch (IOException invalid) { throw new IllegalArgumentException("REVEL_PROFILE_INVALID", invalid); }
        throw new IllegalArgumentException("REVEL_PROFILE_INVALID");
    }
    private static byte[] bytes(String path) {
        try (var stream = Hnf1aRevelRuntime.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("REVEL_PROFILE_MISSING"); return stream.readAllBytes();
        } catch (IOException invalid) { throw new IllegalArgumentException("REVEL_PROFILE_UNAVAILABLE", invalid); }
    }
    private static String fileSha(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) { byte[] chunk = new byte[65536]; int count; while ((count = stream.read(chunk)) >= 0) digest.update(chunk, 0, count); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public record Blocker(String code, String message) { }
    public record Result(ObjectNode selection, List<ObjectNode> atomicEvidence, List<ObjectNode> criterionEvaluations,
                         ObjectNode transcriptInput, List<Blocker> blockers, int providerCalls, int evaluatorCalls) {
        public Result {
            selection = selection.deepCopy(); atomicEvidence = atomicEvidence.stream().map(value -> Objects.requireNonNull(value).deepCopy()).toList();
            criterionEvaluations = criterionEvaluations.stream().map(value -> Objects.requireNonNull(value).deepCopy()).toList(); transcriptInput = transcriptInput.deepCopy(); blockers = List.copyOf(blockers);
        }
    }
    private record Query(BigDecimal score, String issue) { }
    private record Resource(ObjectNode manifest, String identity, Map<String, List<Row>> rows, boolean missingMappings, String issue) { }
    private record Row(int hg19Position, int grch38Position, String reference, String alternate, String refAa, String altAa, String score, String transcripts, int sourceLine) { }
}
