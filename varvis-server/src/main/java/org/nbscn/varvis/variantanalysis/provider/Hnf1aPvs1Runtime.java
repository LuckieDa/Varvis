package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Criterion-only GN017 policy. Reuses stored VEP; never queries a prediction model. */
public final class Hnf1aPvs1Runtime {
    public static final String CRITERION = "ACMG_AMP_2015:PVS1";
    public static final String TRANSCRIPT = "ENST00000257555.11";
    public static final String PROFILE_SHA256 = "ebc8965d69a446159e94376894188cd90017095c3f1ebb1663ae544115b80ad6";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    private final RuntimeEvidenceV2ProviderWiring.ResourceBinding vep;
    private final ObjectNode profile;
    private final ObjectNode model;
    private final AtomicInteger evaluatorCalls = new AtomicInteger();
    private final AtomicInteger storedVepProjections = new AtomicInteger();

    public Hnf1aPvs1Runtime(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        profile = loadProfile(mapper);
        model = load(mapper, profile.at("/transcriptModel/classpathResource").asText());
        require(profile.at("/transcriptModel/canonicalSha256").asText().equals(adapter.canonicalSha256(model)), "PVS1_TRANSCRIPT_MODEL_IDENTITY_INVALID");
        var binding = profile.path("registryBinding");
        byte[] registry = bytes(binding.path("registryClasspathResource").asText());
        require(binding.path("registrySha256").asText().equals(sha(registry)), "PVS1_REGISTRY_IDENTITY_INVALID");
        require(binding.path("registryId").equals(load(mapper, binding.path("registryClasspathResource").asText()).path("registryId")), "PVS1_REGISTRY_IDENTITY_INVALID");
        wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        vep = wiring.bind(profile, "VEP_116_GRCH38");
    }

    public static ObjectNode loadProfile(ObjectMapper mapper) {
        var value = load(mapper, "/acmg/pvs1-hnf1a-business-profile-v3.json");
        require(PROFILE_SHA256.equals(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value)), "PVS1_PROFILE_IDENTITY_INVALID");
        return value;
    }

    public static boolean hasProductionPolicy(JsonNode relationship, String moi) {
        return "HGNC:11621".equals(relationship.path("hgncId").asText())
                && "MONDO:0015967".equals(relationship.path("mondoId").asText())
                && "AUTOSOMAL_DOMINANT".equals(moi);
    }

    /** Eligibility to offer context, not a PVS1 result. Out-of-branch truncations stay inspectable. */
    public static boolean contextTranscript(TranscriptConsequence transcript) {
        return transcript != null && TRANSCRIPT.equals(transcript.getVersionedTranscriptId())
                && "HNF1A".equals(transcript.getGeneSymbol()) && "116.0".equals(transcript.getVersion())
                && (terms(transcript).contains("stop_gained") || terms(transcript).contains("frameshift_variant"));
    }

    public int evaluatorCalls() { return evaluatorCalls.get(); }
    public int storedVepProjections() { return storedVepProjections.get(); }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate) {
        var result = mapper.createObjectNode().put("criterionId", CRITERION).put("sourceOfTruth", "ACMG_EVIDENCE_V2")
                .put("classificationEnabled", false).put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("profileSha256", PROFILE_SHA256);
        result.set("context", context.deepCopy());
        result.set("policy", profile.path("policy").deepCopy());
        result.set("transcriptModelBinding", profile.path("transcriptModel").deepCopy());
        String issue;
        try { adapter.validateCompleteContext(context); issue = contextIssue(context); }
        catch (RuntimeException invalid) { issue = "PVS1_CONTEXT_IDENTITY_INVALID"; }
        if (issue != null) return disposition(result, issue);

        var selection = mapper.createObjectNode().put("schemaVersion", "pvs1-cspec-policy-selection-v1")
                .put("contextId", context.path("contextId").asText()).put("criterionId", CRITERION)
                .put("outcome", "SINGLE_EXACT_MATCH").put("profileSha256", PROFILE_SHA256)
                .put("selectedPolicyId", profile.at("/policy/policyId").asText());
        selection.set("authority", profile.at("/policy/authority").deepCopy());
        selection.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(selection));
        result.set("policySelection", selection);
        TranscriptConsequence annotation = wiring.selectedVepConsequence(context, candidate);
        if (annotation == null) return disposition(result, "PVS1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
        ObjectNode atomic;
        try { atomic = wiring.projectStoredTranscript(context, candidate, annotation, vep); }
        catch (RuntimeException invalid) { return disposition(result, "PVS1_VEP_SOURCE_IDENTITY_INVALID"); }
        storedVepProjections.incrementAndGet();
        result.put("storedVepProjections", 1).set("evidence", atomic);
        ObjectNode annotationSource = mapper.valueToTree(annotation);
        annotationSource.put("timestamp", Objects.toString(annotation.getTimestamp(), null));
        result.set("storedAnnotation", annotationSource);
        var inputs = mapper.createObjectNode().put("transcriptModelSha256", profile.at("/transcriptModel/canonicalSha256").asText());
        inputs.put("storedAnnotationSha256", adapter.canonicalSha256(annotationSource));
        try { issue = resolve(context, annotation, inputs); }
        catch (RuntimeException invalid) { issue = "PVS1_VARIANT_TRANSCRIPT_INPUT_INVALID"; }
        result.set("resolvedInput", inputs);
        evaluatorCalls.incrementAndGet(); result.put("evaluatorCalls", 1);
        result.set("evaluation", evaluation(context, atomic, selection, inputs, issue));
        return result;
    }

    private String contextIssue(JsonNode context) {
        String selection = context.at("/transcript/selectionPolicy").asText();
        if (org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.populationOnlySelection(selection)
                || "PM2_CONTEXT_ONLY".equals(selection)) return "PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED";
        if (!hasProductionPolicy(mapper.createObjectNode().put("hgncId", context.at("/gene/hgncId").asText())
                .put("mondoId", context.at("/disease/mondoId").asText()), context.path("moi").asText())
                || !"HNF1A".equals(context.at("/gene/symbol").asText())) return "PVS1_POLICY_NOT_APPLICABLE";
        if (!TRANSCRIPT.equals(context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asInt())
                || !"116.0".equals(context.at("/transcript/annotationSourceVersion").asText())) return "PVS1_TRANSCRIPT_NOT_SUPPORTED";
        var variant = context.path("variant"); var allele = variant.path("evidenceQueryAllele");
        if (!"NATIVE".equals(variant.at("/assemblyLineage/mappingStatus").asText())
                || !"GRCh38".equals(allele.path("assembly").asText())
                || !Set.of("SNV", "SMALL_INSERTION", "SMALL_DELETION").contains(variant.path("variantType").asText())
                || !"12".equals(allele.path("contig").asText())
                || !allele.path("reference").asText().matches("[ACGT]{1,50}") || !allele.path("alternate").asText().matches("[ACGT]{1,50}")
                || allele.path("reference").equals(allele.path("alternate"))) return "PVS1_NATIVE_SMALL_VARIANT_REQUIRED";
        if ("SNV".equals(variant.path("variantType").asText())
                && (allele.path("reference").asText().length() != 1 || allele.path("alternate").asText().length() != 1))
            return "PVS1_NATIVE_SMALL_VARIANT_REQUIRED";
        return null;
    }

    private String resolve(JsonNode context, TranscriptConsequence tx, ObjectNode inputs) {
        Set<String> consequences = terms(tx);
        if (!consequences.contains("stop_gained") && !consequences.contains("frameshift_variant"))
            return "PVS1_CONSEQUENCE_NOT_APPLICABLE";
        if (consequences.stream().anyMatch(t -> t.contains("splice") || t.equals("intron_variant"))) return "PVS1_SPLICE_BRANCH_NOT_IMPLEMENTED";
        if (!"SNV".equals(context.at("/variant/variantType").asText())) {
            if (!consequences.contains("frameshift_variant")) return "PVS1_CONSEQUENCE_NOT_APPLICABLE";
            return new Hnf1aFrameshiftResolver(model, profile.path("policy")).resolve(context, tx, inputs);
        }
        if (!consequences.contains("stop_gained")) return "PVS1_CONSEQUENCE_NOT_APPLICABLE";
        return new TruncatingNonsenseResolver(model, profile.path("policy"), TRANSCRIPT,
                "GN017_DISEASE_TRANSCRIPT_EXONS_1_TO_9", "HNF1A_MONOGENIC_DIABETES_LOSS_OF_FUNCTION", true)
                .resolve(context, tx, inputs);
    }

    private ObjectNode evaluation(ObjectNode context, ObjectNode atomic, ObjectNode selection, ObjectNode inputs, String issue) {
        boolean ready = issue == null;
        var result = mapper.createObjectNode().put("schemaVersion", "criterion-evaluation-v2")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("contextId", context.path("contextId").asText())
                .put("criterionId", CRITERION).put("baseStrength", "VERY_STRONG").put("appliedStrength", ready ? "VERY_STRONG" : "NONE");
        var binding = result.putObject("ruleBinding");
        binding.put("ruleRegistryId", profile.at("/registryBinding/registryId").asText())
                .put("ruleRegistrySha256", profile.at("/registryBinding/registrySha256").asText())
                .put("currentGeneralDesignation", "PVS1").put("cspecSelectionId", selection.path("selectionId").asText());
        binding.putArray("authoritySourceIds").add("authority-clingen-svi-pvs1-2018");
        var applicability = result.putObject("criterionApplicability").put("status", ready ? "APPLICABLE" : notApplicable(issue) ? "NOT_APPLICABLE" : "UNKNOWN");
        var status = result.putObject("evaluationResult").put("status", ready ? "MET" : "NOT_EVALUATED");
        if (!ready) { applicability.put("reasonCode", issue); status.put("reasonCode", issue); }
        String evidenceId = atomic.path("evidenceId").asText();
        result.putArray("evidenceIds").add(evidenceId);
        var trace = result.putObject("deterministicTrace").put("executionStatus", ready ? "COMPLETED" : "NOT_RUN")
                .put("evaluatorId", "hnf1a-truncating-nmd-pvs1").put("evaluatorVersion", profile.path("profileVersion").asText());
        trace.putArray("inputEvidenceIds").add(evidenceId);
        ArrayNode parameters = trace.putArray("parameters");
        parameters.addObject().put("name", "profileSha256").put("value", PROFILE_SHA256);
        parameters.addObject().put("name", "policySelectionSha256").put("value", adapter.canonicalSha256(selection));
        parameters.addObject().put("name", "resolvedInputSha256").put("value", adapter.canonicalSha256(inputs));
        inputs.properties().forEach(entry -> parameters.addObject().put("name", entry.getKey()).put("value", entry.getValue().asText()));
        var ordered = new java.util.ArrayList<JsonNode>();
        parameters.forEach(ordered::add);
        ordered.sort(java.util.Comparator.comparing(p -> p.path("name").asText()));
        parameters.removeAll(); ordered.forEach(parameters::add);
        var steps = trace.putArray("steps");
        for (String code : ready ? List.of("CSPEC_SINGLE_EXACT_MATCH", "GENOMIC_CDS_AND_VEP_AGREE", "GN017_NMD_BRANCH_SUPPORTED") : List.of(issue))
            steps.addObject().put("stepCode", code).put("outcome", ready ? "TRUE" : "UNKNOWN").putArray("inputEvidenceIds").add(evidenceId);
        if (ready) {
            trace.put("algorithmId", profile.at("/policy/policyId").asText()).put("algorithmVersion", profile.path("profileVersion").asText());
            trace.put("traceSha256", adapter.canonicalSha256(trace));
        } else trace.put("reasonCode", issue);
        result.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        result.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        ObjectNode identity = result.deepCopy(); identity.remove(List.of("conflict", "review"));
        result.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(identity));
        return result;
    }

    private static boolean notApplicable(String issue) {
        return Set.of("PVS1_POLICY_NOT_APPLICABLE", "PVS1_CONSEQUENCE_NOT_APPLICABLE", "PVS1_FRAMESHIFT_NOT_CONFIRMED")
                .contains(Objects.toString(issue, ""));
    }
    private ObjectNode disposition(ObjectNode result, String issue) {
        result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", issue);
        result.putObject("criterionApplicability").put("status", notApplicable(issue) ? "NOT_APPLICABLE" : "UNKNOWN").put("reasonCode", issue);
        return result;
    }
    private static Set<String> terms(TranscriptConsequence tx) {
        return new java.util.HashSet<>(List.of(Objects.toString(tx.getConsequence(), "").split("[&,|]")));
    }
    static char translate(String codon) {
        return TruncatingSequence.translateCodon(codon);
    }
    static String aminoName(char code) {
        return TruncatingSequence.aminoName(code);
    }
    private static ObjectNode load(ObjectMapper mapper, String path) {
        try {
            JsonNode value = mapper.readTree(bytes(path));
            if (value instanceof ObjectNode object) return object;
            throw new IllegalArgumentException("PVS1_RESOURCE_INVALID");
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("PVS1_RESOURCE_INVALID", invalid); }
    }
    private static byte[] bytes(String path) {
        try (var input = Hnf1aPvs1Runtime.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException("PVS1_RESOURCE_MISSING");
            return input.readAllBytes();
        } catch (java.io.IOException unavailable) { throw new IllegalArgumentException("PVS1_RESOURCE_UNAVAILABLE", unavailable); }
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
