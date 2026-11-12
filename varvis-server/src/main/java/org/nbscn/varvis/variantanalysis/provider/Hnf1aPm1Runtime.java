package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** GN017 critical protein locations, checked against stored VEP and the spliced CDS. */
public final class Hnf1aPm1Runtime {
    public static final String CRITERION = "ACMG_AMP_2015:PM1";
    public static final String TRANSCRIPT = "ENST00000257555.11";
    public static final String PROFILE_SHA256 = "f19b744054455c99494c410e812c6347a3f38580b978baafbbec5e6680bf777f";
    private static final Pattern CODING = Pattern.compile("^ENST00000257555\\.11:c\\.([1-9][0-9]*)([ACGT])>([ACGT])$");
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    private final RuntimeEvidenceV2ProviderWiring.ResourceBinding vep;
    private final ObjectNode profile;
    private final ObjectNode model;
    private final AtomicInteger evaluations = new AtomicInteger();
    private final AtomicInteger projections = new AtomicInteger();

    public Hnf1aPm1Runtime(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        profile = loadProfile(mapper);
        model = load(mapper, profile.at("/transcriptModel/classpathResource").asText());
        require(profile.at("/transcriptModel/canonicalSha256").asText().equals(adapter.canonicalSha256(model)), "PM1_MODEL_IDENTITY_INVALID");
        var source = load(mapper, profile.at("/sourceBinding/classpathResource").asText());
        require(profile.at("/sourceBinding/canonicalSha256").asText().equals(adapter.canonicalSha256(source)), "PM1_SOURCE_IDENTITY_INVALID");
        var registry = profile.path("registryBinding");
        require(registry.path("registrySha256").asText().equals(sha(bytes(registry.path("registryClasspathResource").asText()))), "PM1_REGISTRY_IDENTITY_INVALID");
        for (var residue : profile.at("/policy/moderateResidues")) {
            int start = (residue.path("position").asInt() - 1) * 3;
            require(Character.toString(Hnf1aPvs1Runtime.translate(model.path("cdsSequence").asText().substring(start, start + 3)))
                    .equals(residue.path("referenceAminoAcid").asText()), "PM1_REFERENCE_RESIDUE_MISMATCH");
        }
        wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        vep = wiring.bind(profile, "VEP_116_GRCH38");
    }

    public static ObjectNode loadProfile(ObjectMapper mapper) {
        var value = load(mapper, "/acmg/pm1-hnf1a-business-profile-v1.json");
        require(PROFILE_SHA256.equals(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value)), "PM1_PROFILE_IDENTITY_INVALID");
        return value;
    }
    public static boolean hasProductionPolicy(JsonNode relationship, String moi) {
        return "HGNC:11621".equals(relationship.path("hgncId").asText())
                && "MONDO:0015967".equals(relationship.path("mondoId").asText()) && "AUTOSOMAL_DOMINANT".equals(moi);
    }
    public static boolean contextTranscript(TranscriptConsequence tx) {
        return tx != null && TRANSCRIPT.equals(tx.getVersionedTranscriptId()) && "HNF1A".equals(tx.getGeneSymbol())
                && "116.0".equals(tx.getVersion()) && terms(tx).contains("missense_variant");
    }
    public int evaluatorCalls() { return evaluations.get(); }
    public int storedVepProjections() { return projections.get(); }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate) {
        var out = mapper.createObjectNode().put("criterionId", CRITERION).put("sourceOfTruth", "ACMG_EVIDENCE_V2")
                .put("classificationEnabled", false).put("providerCalls", 0).put("evaluatorCalls", 0).put("profileSha256", PROFILE_SHA256);
        out.set("context", context.deepCopy()); out.set("policy", profile.path("policy").deepCopy());
        out.set("transcriptModelBinding", profile.path("transcriptModel").deepCopy());
        out.set("sourceBinding", profile.path("sourceBinding").deepCopy());
        String issue;
        try { adapter.validateCompleteContext(context); issue = contextIssue(context); }
        catch (RuntimeException invalid) { issue = "PM1_CONTEXT_IDENTITY_INVALID"; }
        if (issue != null) return blocked(out, issue);
        var selection = mapper.createObjectNode().put("schemaVersion", "pm1-cspec-policy-selection-v1")
                .put("contextId", context.path("contextId").asText()).put("criterionId", CRITERION)
                .put("outcome", "SINGLE_EXACT_MATCH").put("profileSha256", PROFILE_SHA256)
                .put("selectedPolicyId", profile.at("/policy/policyId").asText());
        selection.set("authority", profile.at("/policy/authority").deepCopy());
        selection.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(selection));
        out.set("policySelection", selection);
        var tx = wiring.selectedVepConsequence(context, candidate);
        if (tx == null) return blocked(out, "PM1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
        ObjectNode evidence;
        try { evidence = wiring.projectStoredTranscript(context, candidate, tx, vep); }
        catch (RuntimeException invalid) { return blocked(out, "PM1_VEP_SOURCE_IDENTITY_INVALID"); }
        projections.incrementAndGet(); out.put("storedVepProjections", 1).set("evidence", evidence);
        ObjectNode annotation = mapper.valueToTree(tx);
        annotation.put("timestamp", Objects.toString(tx.getTimestamp(), null)); out.set("storedAnnotation", annotation);
        var input = mapper.createObjectNode().put("transcriptModelSha256", profile.at("/transcriptModel/canonicalSha256").asText())
                .put("storedAnnotationSha256", adapter.canonicalSha256(annotation));
        try { issue = resolve(context, tx, input); }
        catch (RuntimeException invalid) { issue = "PM1_VARIANT_TRANSCRIPT_INPUT_INVALID"; }
        out.set("resolvedInput", input); evaluations.incrementAndGet(); out.put("evaluatorCalls", 1);
        out.set("evaluation", evaluation(context, evidence, selection, input, issue));
        return out;
    }

    private String contextIssue(JsonNode c) {
        String selection = c.at("/transcript/selectionPolicy").asText();
        if (selection.contains("PM2_CONTEXT") || selection.startsWith("PM2_ONLY_VERIFIED_CLINICAL_ANNOTATION@"))
            return "PM1_TRANSCRIPT_CONFIRMATION_REQUIRED";
        if (!hasProductionPolicy(mapper.createObjectNode().put("hgncId", c.at("/gene/hgncId").asText())
                .put("mondoId", c.at("/disease/mondoId").asText()), c.path("moi").asText())
                || !"HNF1A".equals(c.at("/gene/symbol").asText())) return "PM1_POLICY_NOT_APPLICABLE";
        if (!TRANSCRIPT.equals(c.at("/transcript/accession").asText() + "." + c.at("/transcript/version").asInt())
                || !"116.0".equals(c.at("/transcript/annotationSourceVersion").asText())) return "PM1_TRANSCRIPT_NOT_SUPPORTED";
        var v = c.path("variant"); var a = v.path("evidenceQueryAllele");
        if (!"SNV".equals(v.path("variantType").asText()) || !"NATIVE".equals(v.at("/assemblyLineage/mappingStatus").asText())
                || !"GRCh38".equals(a.path("assembly").asText()) || !"12".equals(a.path("contig").asText())
                || !a.path("reference").asText().matches("[ACGT]") || !a.path("alternate").asText().matches("[ACGT]")
                || a.path("reference").equals(a.path("alternate"))) return "PM1_NATIVE_GRCH38_SNV_REQUIRED";
        return null;
    }

    private String resolve(JsonNode context, TranscriptConsequence tx, ObjectNode input) {
        var terms = terms(tx);
        if (!terms.contains("missense_variant") || terms.stream().anyMatch(t -> Set.of("stop_gained", "stop_lost", "start_lost", "frameshift_variant", "synonymous_variant").contains(t)))
            return "PM1_CONSEQUENCE_NOT_APPLICABLE";
        // A proximity label alone does not establish aberrant splicing.
        if (terms.stream().anyMatch(t -> (t.contains("splice") && !t.equals("splice_region_variant")) || t.equals("intron_variant")))
            return "PM1_SPLICE_MECHANISM_UNRESOLVED";
        if (!"HNF1A".equals(tx.getGeneSymbol())) return "PM1_ANNOTATION_GENE_MISMATCH";
        var coding = CODING.matcher(Objects.toString(tx.getHgvsc(), ""));
        if (!coding.matches()) return "PM1_CODING_POSITION_UNRESOLVED";
        int c = Integer.parseInt(coding.group(1)), g = context.at("/variant/evidenceQueryAllele/position").asInt();
        String ref = context.at("/variant/evidenceQueryAllele/reference").asText(), alt = context.at("/variant/evidenceQueryAllele/alternate").asText();
        JsonNode exon = null;
        for (var e : model.path("exons")) if (g >= e.path("genomicStart").asInt() && g <= e.path("genomicEnd").asInt()) {
            if (exon != null) return "PM1_GENOMIC_CDS_MAPPING_AMBIGUOUS";
            exon = e;
        }
        if (exon == null || c != exon.path("cdsStart").asInt() + g - exon.path("genomicStart").asInt()
                || !ref.equals(coding.group(2)) || !alt.equals(coding.group(3))) return "PM1_GENOMIC_CDS_MAPPING_MISMATCH";
        int ordinal = exon.path("ordinal").asInt();
        if (!(ordinal + "/10").equals(tx.getExon())) return "PM1_EXON_STRUCTURE_MISMATCH";
        String cds = model.path("cdsSequence").asText();
        if (c < 1 || c > cds.length() - 3 || cds.charAt(c - 1) != ref.charAt(0)) return "PM1_CDS_REFERENCE_MISMATCH";
        int start = (c - 1) / 3 * 3, amino = start / 3 + 1;
        String original = cds.substring(start, start + 3);
        var change = new StringBuilder(original); change.setCharAt((c - 1) % 3, alt.charAt(0));
        String altered = change.toString(); char refAa = Hnf1aPvs1Runtime.translate(original), altAa = Hnf1aPvs1Runtime.translate(altered);
        if (amino == 1) return "PM1_INITIATION_BRANCH_NOT_IMPLEMENTED";
        if (refAa == altAa || refAa == '*' || altAa == '*') return "PM1_MISSENSE_NOT_CONFIRMED";
        if (!model.path("protein").asText().equals(tx.getProteinIdentity()) || !Integer.toString(amino).equals(tx.getProteinPosition())
                || !(refAa + "/" + altAa).equals(tx.getAminoAcids())
                || !(original + "/" + altered).equals(Objects.toString(tx.getCodons(), "").toUpperCase(Locale.ROOT))
                || !(model.path("protein").asText() + ":p." + Hnf1aPvs1Runtime.aminoName(refAa) + amino + Hnf1aPvs1Runtime.aminoName(altAa)).equals(tx.getHgvsp()))
            return "PM1_PROTEIN_CODON_MISMATCH";
        input.put("cdsPosition", c).put("exonOrdinal", ordinal).put("refCodon", original).put("altCodon", altered)
                .put("proteinPosition", amino).put("refAminoAcid", Character.toString(refAa)).put("altAminoAcid", Character.toString(altAa))
                .put("mechanism", "DISEASE_SPECIFIC_CRITICAL_PROTEIN_LOCATION");
        String strength = "NONE", match = "GN017_POSITION_NOT_SUPPORTED";
        for (var range : profile.at("/policy/supportingRegions")) if (amino >= range.path("first").asInt() && amino <= range.path("last").asInt()) {
            strength = "SUPPORTING"; match = "GN017_REGION_" + range.path("first").asInt() + "_" + range.path("last").asInt();
        }
        for (var residue : profile.at("/policy/moderateResidues")) if (amino == residue.path("position").asInt()) {
            if (!Character.toString(refAa).equals(residue.path("referenceAminoAcid").asText())) return "PM1_REFERENCE_RESIDUE_MISMATCH";
            strength = "MODERATE"; match = "GN017_DIRECT_DNA_BINDING_RESIDUE";
        }
        if (amino >= 281 && amino <= 631) { strength = "NONE"; match = "GN017_TAD_EXCLUDED"; }
        input.put("matchedPolicy", match).put("strength", strength);
        return null;
    }

    private ObjectNode evaluation(ObjectNode context, ObjectNode evidence, ObjectNode selection, ObjectNode input, String issue) {
        boolean ready = issue == null, met = ready && !"NONE".equals(input.path("strength").asText("NONE"));
        var out = mapper.createObjectNode().put("schemaVersion", "criterion-evaluation-v2").put("canonicalizationProfile", "varvis-json-c14n-v1")
                .put("contextId", context.path("contextId").asText()).put("criterionId", CRITERION).put("baseStrength", "MODERATE")
                .put("appliedStrength", met ? input.path("strength").asText() : "NONE");
        var binding = out.putObject("ruleBinding").put("ruleRegistryId", profile.at("/registryBinding/registryId").asText())
                .put("ruleRegistrySha256", profile.at("/registryBinding/registrySha256").asText())
                .put("currentGeneralDesignation", "PM1").put("cspecSelectionId", selection.path("selectionId").asText());
        binding.putArray("authoritySourceIds").add("authority-acmg-amp-2015-richards");
        var applicable = out.putObject("criterionApplicability").put("status", ready ? "APPLICABLE" : notApplicable(issue) ? "NOT_APPLICABLE" : "UNKNOWN");
        var status = out.putObject("evaluationResult").put("status", ready ? met ? "MET" : "NOT_MET" : "NOT_EVALUATED");
        if (!ready) { applicable.put("reasonCode", issue); status.put("reasonCode", issue); }
        String id = evidence.path("evidenceId").asText(); out.putArray("evidenceIds").add(id);
        var trace = out.putObject("deterministicTrace").put("executionStatus", ready ? "COMPLETED" : "NOT_RUN")
                .put("evaluatorId", "hnf1a-protein-region-pm1").put("evaluatorVersion", profile.path("profileVersion").asText());
        trace.putArray("inputEvidenceIds").add(id);
        var params = new java.util.TreeMap<String, String>();
        params.put("profileSha256", PROFILE_SHA256); params.put("policySelectionSha256", adapter.canonicalSha256(selection));
        params.put("resolvedInputSha256", adapter.canonicalSha256(input));
        input.properties().forEach(e -> params.put(e.getKey(), e.getValue().asText()));
        var parameters = trace.putArray("parameters"); params.forEach((k,v) -> parameters.addObject().put("name", k).put("value", v));
        trace.putArray("steps").addObject().put("stepCode", ready ? input.path("matchedPolicy").asText() : issue)
                .put("outcome", ready ? met ? "TRUE" : "FALSE" : "UNKNOWN").putArray("inputEvidenceIds").add(id);
        if (ready) {
            trace.put("algorithmId", profile.at("/policy/policyId").asText()).put("algorithmVersion", profile.path("profileVersion").asText());
            trace.put("traceSha256", adapter.canonicalSha256(trace));
        } else trace.put("reasonCode", issue);
        out.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        out.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        var identity = out.deepCopy(); identity.remove(List.of("conflict", "review"));
        out.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(identity)); return out;
    }
    private ObjectNode blocked(ObjectNode out, String issue) {
        out.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", issue);
        out.putObject("criterionApplicability").put("status", notApplicable(issue) ? "NOT_APPLICABLE" : "UNKNOWN").put("reasonCode", issue);
        return out;
    }
    private static boolean notApplicable(String issue) {
        return Set.of("PM1_POLICY_NOT_APPLICABLE", "PM1_CONSEQUENCE_NOT_APPLICABLE", "PM1_MISSENSE_NOT_CONFIRMED").contains(Objects.toString(issue, ""));
    }
    private static Set<String> terms(TranscriptConsequence tx) { return new java.util.HashSet<>(List.of(Objects.toString(tx.getConsequence(), "").split("[&,|]"))); }
    private static ObjectNode load(ObjectMapper mapper, String path) {
        try { JsonNode v = mapper.readTree(bytes(path)); if (v instanceof ObjectNode o) return o; }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("PM1_RESOURCE_INVALID", invalid); }
        throw new IllegalArgumentException("PM1_RESOURCE_INVALID");
    }
    private static byte[] bytes(String path) {
        try (var input = Hnf1aPm1Runtime.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException("PM1_RESOURCE_MISSING"); return input.readAllBytes();
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("PM1_RESOURCE_UNAVAILABLE", invalid); }
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
