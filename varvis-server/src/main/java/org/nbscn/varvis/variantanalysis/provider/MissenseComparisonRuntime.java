package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.ClinVarConditionAwareComparatorInput.ReferenceAnnotationSnapshot;
import org.springframework.lang.Nullable;

/** Exact reviewed references, independent evidence, native identities and both splice checks precede comparison. */
public final class MissenseComparisonRuntime {
    public static final String ANNOTATION_SOURCE_BINDING_VERSION = "1.2.0";
    static final String PS1_DIFFERENT_SUBSTITUTION = "PS1_DIFFERENT_PROTEIN_SUBSTITUTION";
    @FunctionalInterface private interface SpliceFacts {
        SpliceAiRawBatchRunner.BatchResult run(ObjectNode context, JsonNode model, List<SpliceAiRawBatchRunner.Query> queries);
    }
    public static final String PS1 = "ACMG_AMP_2015:PS1";
    public static final String PM5 = "ACMG_AMP_2015:PM5";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    @Nullable private final ClinVarConditionAwareComparatorInput comparator;
    @Nullable private final SpliceFacts splice;
    private final String spliceResourceSha256;
    private final boolean completeReferenceSearchRequired;
    @Nullable private final ClinVarConditionAwareComparatorInput.SnapshotCapture capturedInput;

    public MissenseComparisonRuntime(ObjectMapper mapper, @Nullable ClinVarConditionAwareComparatorInput comparator,
                                     @Nullable Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult> splice,
                                     String spliceResourceSha256) {
        this(mapper, comparator, splice == null ? null : (context, model, queries) -> splice.apply(queries), spliceResourceSha256, false);
    }

    private MissenseComparisonRuntime(ObjectMapper mapper, @Nullable ClinVarConditionAwareComparatorInput comparator,
            @Nullable SpliceFacts splice,
            String spliceResourceSha256, boolean completeReferenceSearchRequired) {
        this(mapper, comparator, splice, spliceResourceSha256, completeReferenceSearchRequired, null);
    }

    private MissenseComparisonRuntime(ObjectMapper mapper, @Nullable ClinVarConditionAwareComparatorInput comparator,
            @Nullable SpliceFacts splice,
            String spliceResourceSha256, boolean completeReferenceSearchRequired,
            @Nullable ClinVarConditionAwareComparatorInput.SnapshotCapture capturedInput) {
        this.completeReferenceSearchRequired = completeReferenceSearchRequired;
        this.capturedInput = capturedInput;
        this.mapper = Objects.requireNonNull(mapper); this.adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        this.comparator = comparator; this.splice = splice; this.spliceResourceSha256 = spliceResourceSha256;
    }

    public static MissenseComparisonRuntime withNativeRaw(ObjectMapper mapper,
            @Nullable ClinVarConditionAwareComparatorInput comparator, MissenseNativeSpliceBridge bridge) {
        return new MissenseComparisonRuntime(mapper, comparator, bridge::run, bridge.resourceSha256(), false);
    }

    /** Only the new trusted PM5 bundle opts in; legacy PM5 runtime behavior remains stable. */
    MissenseComparisonRuntime requiringCompleteReferenceSearch() {
        return new MissenseComparisonRuntime(mapper, comparator, splice, spliceResourceSha256, true, capturedInput);
    }

    /** The original trusted bundle can use this supplier without a second discovery/annotation round. */
    public MissenseComparisonRuntime withCapturedInput(ClinVarConditionAwareComparatorInput.SnapshotCapture capture) {
        return new MissenseComparisonRuntime(mapper, comparator, splice, spliceResourceSha256,
                completeReferenceSearchRequired, Objects.requireNonNull(capture));
    }

    /** Catalog and materials must be loaded by a trusted factory, not accepted from an HTTP request. */
    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, JsonNode policy, JsonNode receipt, JsonNode materials) {
        if (capturedInput != null) return evaluateCaptured(context, candidate, policy, receipt, materials, capturedInput);
        ObjectNode result = base(receipt);
        try {
            String issue = inputs(context, candidate, policy, receipt, materials, result);
            if (issue != null) return refuse(result, issue);
            ClinVarConditionAwareComparatorInput comparatorInput = comparator;
            if (comparatorInput == null) return refuse(result, "MISSENSE_COMPARATOR_INPUT_UNAVAILABLE");
            TranscriptConsequence selected = wiring.selectedVepConsequence(context, candidate);
            if (selected == null) return refuse(result, "MISSENSE_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            result.put("providerCalls", 1);
            var captured = comparatorInput.buildWithSnapshots(context, candidate, selected);
            var input = captured.input();
            List<ObjectNode> atomics = wiring.projectClinVarComparatorEvidence(context, input, ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper));
            result.set("comparatorInput", mapper.valueToTree(input));
            if (!Set.of("READY", "NO_RECORD").contains(input.getStatus())) return refuse(result, "MISSENSE_COMPARATOR_INPUT_UNAVAILABLE");
            return assessValidated(context, policy, receipt, materials, result, atomics, captured.getReferenceSnapshots());
        } catch (RuntimeException invalid) { return refuse(result, "MISSENSE_INPUT_INTEGRITY_INVALID"); }
    }

    /** Consumes the immutable invocation that produced the draft; never rediscovers or reannotates it. */
    public ObjectNode evaluateCaptured(ObjectNode context, VariantInterpretation candidate, JsonNode policy,
                                       JsonNode receipt, JsonNode materials,
                                       ClinVarConditionAwareComparatorInput.SnapshotCapture captured) {
        ObjectNode result = base(receipt);
        try {
            String issue = inputs(context, candidate, policy, receipt, materials, result);
            if (issue != null) return refuse(result, issue);
            if (captured == null || !context.equals(captured.getEvidenceContext()))
                return refuse(result, "MISSENSE_CAPTURE_CONTEXT_MISMATCH");
            TranscriptConsequence selected = wiring.selectedVepConsequence(context, candidate);
            var snapshotMapper = new ObjectMapper().findAndRegisterModules()
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            if (selected == null || !snapshotMapper.valueToTree(selected).equals(captured.getSelectedTargetAnnotation()))
                return refuse(result, "MISSENSE_CAPTURE_TARGET_ANNOTATION_MISMATCH");
            var input = captured.input();
            result.set("comparatorInput", captured.getResult());
            result.put("referenceFactReuse", "SAME_INVOCATION_CAPTURE");
            if (!Set.of("READY", "NO_RECORD").contains(input.getStatus()))
                return refuse(result, "MISSENSE_COMPARATOR_INPUT_UNAVAILABLE");
            List<ObjectNode> atomics = wiring.projectClinVarComparatorEvidence(context, input,
                    ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper));
            return assessValidated(context, policy, receipt, materials, result, atomics, captured.getReferenceSnapshots());
        } catch (RuntimeException invalid) { return refuse(result, "MISSENSE_INPUT_INTEGRITY_INVALID"); }
    }

    /** Package-private deterministic seam. Production obtains the same atomics from the existing comparator factory. */
    ObjectNode assess(ObjectNode context, VariantInterpretation candidate, JsonNode policy, JsonNode receipt,
                      JsonNode materials, List<ObjectNode> atomics) {
        ObjectNode result = base(receipt);
        try {
            String issue = inputs(context, candidate, policy, receipt, materials, result);
            return issue == null ? assessValidated(context, policy, receipt, materials, result, atomics, List.of()) : refuse(result, issue);
        } catch (RuntimeException invalid) { return refuse(result, "MISSENSE_INPUT_INTEGRITY_INVALID"); }
    }

    @Nullable private String inputs(ObjectNode context, VariantInterpretation candidate, JsonNode policy, JsonNode receipt,
                                    JsonNode materials, ObjectNode result) {
        if (!(receipt instanceof ObjectNode object) || context == null || !context.path("contextId").equals(receipt.path("contextId"))) return "MISSENSE_SELECTION_CONTEXT_MISMATCH";
        ObjectNode projection = object.deepCopy(); projection.remove("selectionId");
        if (!AcmgSpecificationSelector.SCHEMA.equals(receipt.path("schemaVersion").asText())
                || !receipt.path("criterionIds").isArray() || receipt.path("criterionIds").size() != 1
                || !Set.of(PS1, PM5).contains(receipt.at("/criterionIds/0").asText())
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(projection)).equals(receipt.path("selectionId").asText())
                || !MissenseComparisonPolicy.text(receipt.path("catalogId")) || !MissenseComparisonPolicy.text(receipt.path("catalogVersion"))
                || !MissenseComparisonPolicy.hash(receipt.path("catalogSha256"))) return "MISSENSE_SELECTION_IDENTITY_INVALID";
        if (!Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(receipt.path("outcome").asText())) return receipt.path("reasonCode").asText("MISSENSE_SELECTION_INCOMPLETE");
        if (policy == null || !policy.isObject() || !receipt.path("policyId").equals(policy.path("policyId"))
                || !receipt.path("policyVersion").equals(policy.path("policyVersion"))
                || !receipt.path("policySha256").asText().equals(adapter.canonicalSha256(policy))) return "MISSENSE_SELECTION_POLICY_MISMATCH";
        adapter.validateCompleteContext(context);
        result.set("context", context.deepCopy()); result.set("policy", policy.deepCopy());
        String criterion = receipt.at("/criterionIds/0").asText();
        String issue = MissenseComparisonPolicy.issue(policy, criterion);
        if (issue == null) issue = MissenseComparisonPolicy.contextIssue(policy, context);
        if (issue != null) return issue;
        if (materials == null || !materials.isObject()) return "MISSENSE_REVIEWED_MATERIALS_MISSING";
        issue = MissenseComparisonPolicy.materialsIssue(policy, materials, criterion, adapter);
        if (issue != null) return issue;
        if (MissenseRnaApplicability.automated(materials.path("rnaReview"))) {
            ObjectNode screening = result.putObject("rnaApplicability").put("mode", MissenseRnaApplicability.MODE)
                    .put("sourceCoverage", MissenseRnaApplicability.COVERAGE).put("normalRnaClaimed", false)
                    .put("pairSpecificManualApprovalRequired", false).put("bothNativeRawChecksRequired", true);
            screening.set("sourceIds", materials.at("/rnaReview/sourceIds").deepCopy());
        }
        if (!policy.path("comparatorProfileSha256").asText().equals(adapter.canonicalSha256(ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper)))) return "MISSENSE_COMPARATOR_PROFILE_MISMATCH";
        if (!policy.at("/splicePolicy/resourceSha256").asText().equals(spliceResourceSha256)) return "MISSENSE_SPLICE_EXECUTION_RESOURCE_MISMATCH";
        JsonNode model = materials.path("nativeModel");
        if (CodingTranscriptMapping.modelIssue(model, context) != null || !model.path("protein").equals(policy.at("/scope/protein"))) return "MISSENSE_NATIVE_MODEL_INVALID";
        String path = policy.at("/registryBinding/registryClasspathResource").asText();
        if (!policy.at("/registryBinding/registrySha256").asText().equals(ClinVarMissenseBusinessArtifacts.resourceSha(path))) return "MISSENSE_REGISTRY_IDENTITY_INVALID";
        ObjectNode registry = ClinVarMissenseBusinessRuntime.read(mapper, path);
        if (!policy.at("/registryBinding/registryId").equals(registry.path("registryId"))) return "MISSENSE_REGISTRY_IDENTITY_INVALID";
        boolean found = false;
        for (JsonNode rule : registry.path("criteria")) if (criterion.equals(rule.path("criterionId").asText())) found = true;
        if (!found) return "MISSENSE_REGISTRY_CRITERION_MISSING";
        if (candidate == null) return "MISSENSE_CANDIDATE_MISSING";
        TranscriptConsequence tx = wiring.selectedVepConsequence(context, candidate);
        if (tx == null) return "MISSENSE_SELECTED_TRANSCRIPT_NOT_UNIQUE";
        if (!Set.of("VEP", "VEP_CACHED").contains(Objects.toString(tx.getSource(), ""))) return "MISSENSE_ANNOTATION_SOURCE_INVALID";
        JsonNode annotation = materials.path("annotationResource");
        if (!"VEP".equals(annotation.path("provider").asText())) return "MISSENSE_ANNOTATION_SOURCE_INVALID";
        boolean annotationBound = false;
        for (JsonNode resource : ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper).path("resources"))
            if (resource.path("resourceId").equals(annotation.path("resourceId"))
                    && resource.path("identityFingerprintSha256").equals(annotation.path("identityFingerprintSha256"))) annotationBound = true;
        if (!annotationBound) return "MISSENSE_COMPARATOR_ANNOTATION_RESOURCE_MISMATCH";
        ObjectNode manifest = mapper.createObjectNode(); manifest.putArray("resources").add(annotation);
        wiring.projectStoredTranscript(context, candidate, tx, wiring.bind(manifest, annotation.path("resourceId").asText()));
        ObjectNode target = result.putObject("targetIdentity");
        issue = CodingMissenseResolver.resolve(context, tx, model, target, adapter);
        if (issue != null) return issue;
        if (!SelectedCriterionRouting.proteinMissenseOnly(tx.getConsequence())) return "MISSENSE_PROTEIN_ONLY_CONSEQUENCE_REQUIRED";
        result.put("materialsSha256", adapter.canonicalSha256(materials)).put("storedVepProjections", 1);
        return null;
    }

    private ObjectNode assessValidated(ObjectNode context, JsonNode policy, JsonNode receipt, JsonNode materials,
                                       ObjectNode result, List<ObjectNode> atomics,
                                       List<ReferenceAnnotationSnapshot> annotations) {
        for (ObjectNode atomic : atomics) revalidateAtomic(context, atomic);
        String issue = new ClinVarPs1Pm5CriterionEvaluator(mapper).businessInputIssue(context, atomics);
        if (issue != null) return refuse(result, issue);
        result.set("atomicEvidence", mapper.valueToTree(atomics));
        String criterion = receipt.at("/criterionIds/0").asText();
        boolean ps1 = PS1.equals(criterion);
        JsonNode target = result.path("targetIdentity");
        LinkedHashMap<String, Reference> references = new LinkedHashMap<>();
        boolean countedMode = PM5.equals(criterion)
                && MissenseComparisonPolicy.countedMode(policy.at("/rules/PM5/mode").asText());
        Set<String> encounteredScvs = new HashSet<>();
        for (ObjectNode atomic : atomics) {
            if (!"HIT".equals(atomic.at("/queryOutcome/status").asText())) continue;
            String scv = metric(atomic, "clinvar.assertion.scv");
            ObjectNode assessment = result.withArray("referenceAssessments").addObject().put("scv", scv).put("evidenceId", atomic.path("evidenceId").asText());
            JsonNode trusted = null;
            for (JsonNode ref : materials.at("/referenceAudit/references")) if (scv.equals(ref.path("scv").asText())) {
                if (trusted != null) return refuse(result, "MISSENSE_REFERENCE_AUDIT_AMBIGUOUS"); trusted = ref;
            }
            if (trusted == null) {
                assessment.put("status", "NOT_ADOPTED").put("reasonCode", "REFERENCE_NOT_IN_AUDITED_CATALOG");
                if (ps1) excludeDifferentSubstitution(context, materials, target, atomic, annotations, assessment);
                continue;
            }
            ObjectNode identity = mapper.createObjectNode();
            issue = referenceIssue(context, policy, materials, atomic, trusted, target, atomics, identity);
            if (issue != null) { assessment.put("status", "REJECTED").put("reasonCode", issue); return refuse(result, issue); }
            String alleleId = adapter.canonicalSha256(trusted.path("allele"));
            if (!encounteredScvs.add(scv) || !countedMode && references.containsKey(alleleId)) {
                assessment.put("status", "NOT_ADOPTED").put("reasonCode", "SAME_REFERENCE_ALLELE_NOT_INDEPENDENT"); continue;
            }
            String relation = proteinRelation(target, identity);
            String adoptedClass = MissenseIndependentReference.adoptedClassification(trusted);
            if ("missense-independent-reference-v2".equals(trusted.at("/qualification/schemaVersion").asText()))
                assessment.put("sourceClassification", trusted.path("classification").asText()).put("adoptedClassification", adoptedClass);
            String selectedCode = ps1 ? "PS1" : "PM5";
            JsonNode ceiling = policy.path("rules").path(selectedCode).path("classificationCeiling");
            boolean matchesRule = "PS1".equals(selectedCode) == "SAME_AMINO_ACID_CHANGE".equals(relation);
            if (matchesRule && ceiling.isObject() && adoptedClass.equals(ceiling.path("referenceClassification").asText())) {
                assessment.put("status", "REJECTED").put("reasonCode", "MISSENSE_LP_CLASSIFICATION_CEILING_NOT_IMPLEMENTED");
                result.set("classificationConstraint", ceiling.deepCopy());
                return refuse(result, "MISSENSE_LP_CLASSIFICATION_CEILING_NOT_IMPLEMENTED");
            }
            var previous = references.get(alleleId);
            if (countedMode && previous != null) {
                String previousClass = MissenseIndependentReference.adoptedClassification(previous.audit());
                int previousRank = "PATHOGENIC".equals(previousClass) ? 2 : 1;
                int currentRank = "PATHOGENIC".equals(adoptedClass) ? 2 : 1;
                boolean replace = currentRank > previousRank || currentRank == previousRank
                        && scv.compareTo(previous.audit().path("scv").asText()) < 0;
                if (!replace) {
                    assessment.put("status", "NOT_ADOPTED").put("reasonCode", "SAME_REFERENCE_ALLELE_NOT_INDEPENDENT"); continue;
                }
                for (var prior : result.path("referenceAssessments"))
                    if (prior.path("evidenceId").equals(previous.atomic().path("evidenceId")))
                        ((ObjectNode) prior).put("status", "NOT_ADOPTED").put("reasonCode", "SAME_REFERENCE_ALLELE_STRONGER_QUALIFIED_ASSERTION_SELECTED");
            }
            references.put(alleleId, new Reference(atomic, trusted, identity, relation));
            assessment.put("status", "ELIGIBLE").put("proteinRelation", relation);
            if (ps1 && "SAME_RESIDUE_DIFFERENT_AMINO_ACID".equals(relation))
                recordDifferentSubstitution(assessment, trusted.path("variationId").asText(), identity);
        }
        if (references.isEmpty()) return refuse(result, "INDEPENDENT_REFERENCE_EVIDENCE_MISSING");
        List<SpliceAiRawBatchRunner.Query> queries = new ArrayList<>();
        String gene = context.at("/gene/symbol").asText(); String transcript = materials.at("/nativeModel/transcript").asText();
        queries.add(new SpliceAiRawBatchRunner.Query("TARGET", key(context.at("/variant/evidenceQueryAllele")), gene, transcript));
        for (Reference reference : references.values()) {
            queries.add(new SpliceAiRawBatchRunner.Query(reference.audit().path("referenceId").asText(), key(reference.audit().path("allele")), gene, transcript));
            ObjectNode display = ((ObjectNode) reference.audit()).deepCopy(); display.set("nativeProteinIdentity", reference.identity());
            if ("missense-independent-reference-v2".equals(reference.audit().at("/qualification/schemaVersion").asText()))
                display.put("adoptedClassification", MissenseIndependentReference.adoptedClassification(reference.audit()));
            result.withArray("references").add(display);
        }
        SpliceFacts spliceInput = splice;
        if (spliceInput == null) return refuse(result, "SPLICE_EVIDENCE_UNAVAILABLE");
        result.put("rawBatchCalls", 1);
        SpliceAiRawBatchRunner.BatchResult batch = spliceInput.run(context.deepCopy(), materials.path("nativeModel").deepCopy(), List.copyOf(queries));
        if (batch != null) result.putObject("rawSpliceReceipt").put("status", batch.getStatus()).put("reasonCode", batch.getReasonCode());
        ObjectNode raw = batch == null ? null : batch.getResult();
        if (batch == null || !"SUCCEEDED".equals(batch.getStatus()) || raw == null || !validBatchHeader(raw, queries)) return refuse(result, "SPLICE_EVIDENCE_UNAVAILABLE");
        result.set("rawSpliceBatch", raw.deepCopy());
        LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
        for (SpliceAiRawBatchRunner.Query query : queries) {
            BigDecimal score = score(raw, query);
            if (score == null) return refuse(result, "SPLICE_EVIDENCE_UNAVAILABLE");
            scores.put(query.getQueryId(), score);
            ObjectNode evidence = result.withArray("spliceEvidence").addObject().put("queryId", query.getQueryId()).put("maximumScore", score.toPlainString())
                    .put("resourceSha256", spliceResourceSha256).put("normalRnaClaimed", false);
            evidence.set("allele", allele(query.getKey()));
        }
        String expectedRelation = ps1 ? "SAME_AMINO_ACID_CHANGE" : "SAME_RESIDUE_DIFFERENT_AMINO_ACID";
        result.putArray("proteinComparisons");
        List<Choice> choices = new ArrayList<>();
        for (Reference reference : references.values()) if (expectedRelation.equals(reference.relation())) {
            if (!lowSplice(scores.get("TARGET"), policy) || !lowSplice(scores.get(reference.audit().path("referenceId").asText()), policy)) return refuse(result, "UNRESOLVED_SPLICE_MECHANISM");
            String strength = strength(policy, materials, criterion, result.path("targetIdentity"), reference);
            if (strength.startsWith("MISSENSE_")) return refuse(result, strength);
            ObjectNode comparison = result.withArray("proteinComparisons").addObject()
                    .put("referenceId", reference.audit().path("referenceId").asText()).put("proteinRelation", reference.relation()).put("appliedStrength", strength);
            JsonNode rule = policy.path("rules").path(PS1.equals(criterion) ? "PS1" : "PM5");
            if (MissenseComparisonPolicy.DIRECT_RESIDUE.equals(rule.path("mode").asText())
                    || MissenseComparisonPolicy.COUNTED_DIRECT.equals(rule.path("mode").asText()))
                comparison.put("ruleMode", rule.path("mode").asText());
            if ("SUBSTITUTION_MATRIX_SINGLE_REFERENCE".equals(rule.path("mode").asText())
                    || MissenseComparisonPolicy.COUNTED_GRANTHAM.equals(rule.path("mode").asText())) {
                JsonNode row = materials.at("/substitutionMatrix/matrix").path(result.at("/targetIdentity/refAminoAcid").asText());
                comparison.put("matrixKind", rule.path("matrixKind").asText()).put("matrixSha256", rule.path("matrixSha256").asText())
                        .put("comparison", rule.path("comparison").asText()).put("targetSubstitutionScore", row.path(result.at("/targetIdentity/altAminoAcid").asText()).asInt())
                        .put("referenceSubstitutionScore", row.path(reference.identity().path("altAminoAcid").asText()).asInt());
            }
            if (!"NONE".equals(strength)) choices.add(new Choice(reference, strength));
        }
        choices.sort(Comparator.comparingInt((Choice choice) -> strengthRank(choice.strength())).reversed().thenComparing(choice -> choice.reference().audit().path("scv").asText()));
        // Partial discovery or unaudited assertions cannot establish a negative in the general business scopes.
        if (choices.isEmpty() && (ps1 || completeReferenceSearchRequired) && incompleteReferenceSearch(result))
            return refuse(result, "MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE");
        if (choices.isEmpty() && ps1 && scores.values().stream().anyMatch(score -> !lowSplice(score, policy)))
            return refuse(result, "UNRESOLVED_SPLICE_MECHANISM");
        List<Choice> contributors = countedPathogenicChoices(policy, criterion, choices, result);
        boolean countUpgrade = contributors.size() == 2;
        ObjectNode decision = mapper.createObjectNode().put("status", choices.isEmpty() ? "NOT_MET" : "MET")
                .put("reasonCode", choices.isEmpty() ? "NO_ELIGIBLE_REVIEWED_PROTEIN_COMPARISON" : "INDEPENDENT_REFERENCE_AND_BOTH_SPLICE_GATES_MET")
                .put("appliedStrength", countUpgrade ? "STRONG" : choices.isEmpty() ? "NONE" : choices.get(0).strength());
        ArrayNode selected = decision.putArray("evidenceIds");
        if (countUpgrade) {
            var referenceIds = decision.putArray("selectedReferenceIds");
            for (var contributor : contributors) {
                selected.add(contributor.reference().atomic().path("evidenceId"));
                referenceIds.add(contributor.reference().audit().path("referenceId"));
            }
            decision.put("countRuleApplied", true);
            decision.set("pathogenicSubstitutionCount", result.path("pathogenicSubstitutionCount").deepCopy());
        } else if (!choices.isEmpty()) {
            selected.add(choices.get(0).reference().atomic().path("evidenceId"));
            decision.put("selectedReferenceId", choices.get(0).reference().audit().path("referenceId").asText());
        }
        result.set("scientificDecision", decision);
        ObjectNode inputTrace = mapper.createObjectNode().put("materialsSha256", adapter.canonicalSha256(materials))
                .put("rawSpliceBatchSha256", adapter.canonicalSha256(raw)).put("targetIdentitySha256", adapter.canonicalSha256(result.path("targetIdentity")))
                .put("referenceAssessmentsSha256", adapter.canonicalSha256(result.path("referenceAssessments")))
                .put("proteinComparisonsSha256", adapter.canonicalSha256(result.path("proteinComparisons")))
                .put("referenceStrengthCount", countUpgrade ? 2 : choices.isEmpty() ? 0 : 1).put("overlapPolicy", policy.path("overlapPolicy").asText());
        if (result.has("pathogenicSubstitutionCount")) {
            inputTrace.put("comparisonStrengthCount", choices.isEmpty() ? 0 : 1)
                    .put("pathogenicSubstitutionCountSha256", adapter.canonicalSha256(result.path("pathogenicSubstitutionCount")));
        }
        result.set("comparisonTrace", inputTrace);
        ObjectNode registry = ClinVarMissenseBusinessRuntime.read(mapper, policy.at("/registryBinding/registryClasspathResource").asText());
        result.withArray("criterionEvaluations").add(new ClinVarPs1Pm5CriterionEvaluator(mapper).evaluateReviewedPolicy(context, atomics, criterion, policy, receipt, decision, inputTrace, registry));
        result.put("evaluatorCalls", 1);
        return result;
    }

    private static boolean incompleteReferenceSearch(JsonNode result) {
        for (JsonNode decision : result.at("/comparatorInput/candidateDecisions"))
            if (!Set.of("READY", "NOT_APPLICABLE").contains(decision.path("status").asText())) return true;
        for (JsonNode assessment : result.path("referenceAssessments"))
            if ("REFERENCE_NOT_IN_AUDITED_CATALOG".equals(assessment.path("reasonCode").asText())) return true;
        return false;
    }

    // An unreviewed assertion can be excluded by a proven molecular mismatch.
    // It can never become the independent reference required for a decision.
    private void excludeDifferentSubstitution(ObjectNode context, JsonNode materials, JsonNode target,
            ObjectNode atomic, List<ReferenceAnnotationSnapshot> annotations, ObjectNode assessment) {
        if (!"SAME_RESIDUE_DIFFERENT_AMINO_ACID".equals(metric(atomic, "vep.proteinRelation"))) return;
        String variationId = metric(atomic, "clinvar.comparator.variationId");
        for (var annotation : annotations) {
            var allele = allele(annotation.getExactAllele());
            if (!variationId.equals(annotation.getVariationId()) || !matchesComparatorAllele(atomic, allele)
                    || allele.equals(target.path("allele"))) continue;
            var identity = mapper.createObjectNode();
            if (referenceProteinIssue(context, materials, atomic, allele, annotation.getAnnotation(), target, identity) == null) {
                recordDifferentSubstitution(assessment, variationId, identity);
            }
            return;
        }
    }

    private static void recordDifferentSubstitution(ObjectNode assessment, String variationId, ObjectNode identity) {
        assessment.put("variationId", variationId).put("proteinRelation", "SAME_RESIDUE_DIFFERENT_AMINO_ACID")
                .put("reasonCode", PS1_DIFFERENT_SUBSTITUTION);
        assessment.set("nativeProteinIdentity", identity);
    }

    private void revalidateAtomic(ObjectNode context, ObjectNode atomic) {
        ObjectNode carrier = mapper.createObjectNode().put("evidenceType", "CLINICAL_DATABASE")
                .put("producedAt", atomic.at("/provenance/producedAt").asText()).put("sourcePayloadSha256", atomic.at("/provenance/sourcePayloadSha256").asText());
        carrier.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        for (String field : List.of("source", "resourceExecution", "resourceAvailability", "resourceApplicability", "queryOutcome", "measurements")) carrier.set(field, atomic.path(field).deepCopy());
        ObjectNode rebuilt = adapter.adapt(context, carrier);
        if (!rebuilt.path("evidenceId").equals(atomic.path("evidenceId")) || !rebuilt.path("deduplicationKey").equals(atomic.path("deduplicationKey"))
                || !atomic.path("derivedFromEvidenceIds").isArray() || !atomic.path("derivedFromEvidenceIds").isEmpty()
                || !"UNDETERMINED".equals(atomic.path("direction").asText()) || !"NONE".equals(atomic.at("/conflict/status").asText()))
            throw new IllegalArgumentException("Comparator atomic identity changed");
    }

    @Nullable private String referenceIssue(ObjectNode context, JsonNode policy, JsonNode materials, ObjectNode atomic,
                                             JsonNode ref, JsonNode target, List<ObjectNode> atomics, ObjectNode identity) {
        String issue = new ClinVarPs1Pm5CriterionEvaluator(mapper).businessReferenceIssue(context, atomic);
        if (issue != null) return issue;
        if (!ref.path("variationId").asText().matches("[1-9][0-9]*") || !ref.path("evaluationDate").asText().equals(date(ref.path("evaluationDate").asText()))
                || !MissenseComparisonPolicy.text(ref.path("evaluationDate"))) return "REFERENCE_ASSERTION_IDENTITY_INCOMPLETE";
        if (!"ACTIVE".equals(ref.path("assertionStatus").asText())) return "REFERENCE_WITHDRAWN_OR_STATUS_UNREVIEWED";
        boolean sourceVerified = MissenseExpertClassificationAdoption.sourceVerified(ref);
        if (!(sourceVerified ? MissenseExpertClassificationAdoption.SOURCE_STATUS : "REVIEWED_SUFFICIENT_WITHOUT_PS1_PM5").equals(ref.at("/independence/status").asText())
                || !MissenseComparisonPolicy.text(ref.at("/independence/reviewId")) || !MissenseComparisonPolicy.text(ref.at("/independence/originalSpecificationVersion"))
                || !MissenseComparisonPolicy.boundSources(policy, ref.at("/independence/sourceIds"))
                || !ref.at("/independence/criteria").isArray() || ref.at("/independence/criteria").isEmpty()
                || !ref.at("/independence/dependencyAlleles").isArray()
                || !(sourceVerified ? MissenseExpertClassificationAdoption.SOURCE_DEPENDENCY : "REVIEWED_NO_COMPARISON_DEPENDENCY").equals(ref.at("/independence/dependencyReview").asText())) return "REFERENCE_NOT_INDEPENDENT";
        for (JsonNode code : ref.at("/independence/criteria")) {
            String value = code.isTextual() ? code.asText() : code.path("code").asText();
            value = value.replace("ACMG_AMP_2015:", "").toUpperCase(Locale.ROOT);
            if (!value.matches("(?:PVS1|PS[1-4]|PM[1-6]|PP[1-5]|BA1|BS[1-4]|BP[1-7])(?:_[A-Z]+)?") || value.startsWith("PS1") || value.startsWith("PM5")) return "REFERENCE_NOT_INDEPENDENT";
        }
        for (JsonNode dependency : ref.at("/independence/dependencyAlleles"))
            if (dependency.equals(context.at("/variant/evidenceQueryAllele")) || dependency.equals(ref.path("allele"))) return "REFERENCE_CIRCULAR_OR_TARGET_DEPENDENT";
        // Unknown dependency graphs cannot establish independence; this operator accepts explicitly reviewed standalone references.
        if (!ref.at("/independence/dependencyAlleles").isEmpty()) return "REFERENCE_DEPENDENCY_GRAPH_NOT_IMPLEMENTED";
        for (String[] pair : List.of(new String[]{"hgncId", "/gene/hgncId"}, new String[]{"geneSymbol", "/gene/symbol"}, new String[]{"mondoId", "/disease/mondoId"}, new String[]{"moi", "/moi"}))
            if (!ref.path(pair[0]).equals(context.at(pair[1]))) return "REFERENCE_DISEASE_OR_MECHANISM_MISMATCH";
        if (ref.path("allele").equals(context.at("/variant/evidenceQueryAllele"))) return "REFERENCE_SELF_COMPARISON";
        if (!matchesComparatorAllele(atomic, ref.path("allele"))) return "REFERENCE_ALLELE_IDENTITY_MISMATCH";
        if (!ref.path("variationId").asText().equals(metric(atomic, "clinvar.comparator.variationId"))) return "REFERENCE_ALLELE_IDENTITY_MISMATCH";
        String classification = classification(ref.path("classification").asText());
        if (!Set.of("PATHOGENIC", "LIKELY_PATHOGENIC").contains(classification)
                || !classification.equals(classification(metric(atomic, "clinvar.assertion.classification")))) return "REFERENCE_ASSERTION_CONFLICT";
        if (!"missense-independent-reference-v2".equals(ref.at("/qualification/schemaVersion").asText())
                && !classification.equals(classification(ref.at("/independence/classificationAfterExclusion").asText())))
            return "REFERENCE_ASSERTION_CONFLICT";
        boolean independentRoute = ref.has("qualification");
        if (independentRoute) {
            issue = MissenseIndependentReference.issue(policy, ref, adapter);
            if (issue != null) return issue;
            if (!ref.at("/sourceAssertion/reviewStatus").asText().equals(metric(atomic, "clinvar.assertion.reviewStatus")))
                return "REFERENCE_INDEPENDENT_ASSERTION_BINDING_MISMATCH";
        } else if (!classification.equals(classification(ref.at("/independence/classificationAfterExclusion").asText()))
                || !Set.of("reviewed by expert panel", "practice guideline").contains(metric(atomic, "clinvar.assertion.reviewStatus")))
            return "REFERENCE_ASSERTION_CONFLICT";
        if (!ref.path("evaluationDate").asText().equals(date(metric(atomic, "clinvar.assertion.dateLastEvaluated")))) return "REFERENCE_ASSERTION_VERSION_MISMATCH";
        String mondo = context.at("/disease/mondoId").asText();
        JsonNode mappings = ref.path("conditionMappings");
        boolean mapped = ref.has("conditionMappings");
        if (!exactDisease(atomic, mondo) && (!mapped || !mondo.equals(mappedDisease(atomic, mappings))))
            return "REFERENCE_DISEASE_OR_MECHANISM_MISMATCH";
        if (mapped) identity.set("conditionMappings", mappings.deepCopy());
        for (ObjectNode other : atomics) if (ref.path("variationId").asText().equals(metric(other, "clinvar.comparator.variationId"))
                && (independentRoute || Set.of("reviewed by expert panel", "practice guideline").contains(metric(other, "clinvar.assertion.reviewStatus")))
                && !Set.of("PATHOGENIC", "LIKELY_PATHOGENIC").contains(classification(metric(other, "clinvar.assertion.classification")))) {
            if (exactDisease(other, mondo) || mapped && mondo.equals(mappedDisease(other, mappings))) return "REFERENCE_ASSERTION_CONFLICT";
            if ((mapped || independentRoute) && mappedDisease(other, mappings).isEmpty() && !explicitOtherDisease(other, mondo))
                return "REFERENCE_CONFLICT_DISEASE_UNRESOLVED";
        }
        issue = referenceProteinIssue(context, materials, atomic, ref.path("allele"), ref.path("annotation"), target, identity);
        return issue == null ? rnaIssue(context, policy, materials.path("rnaReview"), ref) : issue;
    }

    private static boolean matchesComparatorAllele(JsonNode atomic, JsonNode allele) {
        for (String field : List.of("assembly", "contig", "position", "reference", "alternate"))
            if (!allele.path(field).asText().equals(metric(atomic, "clinvar.comparator." + field))) return false;
        return true;
    }

    @Nullable private String referenceProteinIssue(ObjectNode context, JsonNode materials, ObjectNode atomic,
            JsonNode allele, JsonNode annotationNode, JsonNode target, ObjectNode identity) {
        ObjectNode referenceContext = context.deepCopy(); ObjectNode variant = (ObjectNode) referenceContext.path("variant");
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) variant.set(field, allele.deepCopy());
        variant.put("alleleId", adapter.canonicalAlleleId(allele, variant.path("normalization")));
        referenceContext.put("contextId", adapter.canonicalContextId(referenceContext)); adapter.validateCompleteContext(referenceContext);
        TranscriptConsequence annotation;
        if (!(annotationNode instanceof ObjectNode annotationValue)) return "REFERENCE_ANNOTATION_INVALID";
        try { annotation = new MissenseReferenceDraftPreparation().parseAnnotation(annotationValue,
                materials.at("/nativeModel/transcript").asText()); }
        catch (java.io.IOException | IllegalArgumentException invalid) { return "REFERENCE_ANNOTATION_INVALID"; }
        if (annotation == null || !Set.of("VEP", "VEP_CACHED").contains(Objects.toString(annotation.getSource(), "")) || !"COMPLETED".equals(annotation.getStatus())
                || !context.at("/transcript/annotationSourceVersion").asText().equals(annotation.getVersion())
                || !SelectedCriterionRouting.proteinMissenseOnly(annotation.getConsequence())) return "REFERENCE_ANNOTATION_INVALID";
        String issue = CodingMissenseResolver.resolve(referenceContext, annotation, materials.path("nativeModel"), identity, adapter);
        if (issue != null) return issue;
        if (!target.path("proteinPosition").equals(identity.path("proteinPosition")) || !target.path("refAminoAcid").equals(identity.path("refAminoAcid"))) return "REFERENCE_PROTEIN_RELATION_MISMATCH";
        for (String role : List.of("target", "comparator")) {
            JsonNode proof = "target".equals(role) ? target : identity;
            if (!materials.at("/nativeModel/transcript").asText().equals(metric(atomic, "vep." + role + ".transcriptId"))) return "REFERENCE_TRANSCRIPT_MISMATCH";
            for (String[] pair : List.of(new String[]{"proteinPosition", "proteinPosition"}, new String[]{"referenceAminoAcid", "refAminoAcid"}, new String[]{"alternateAminoAcid", "altAminoAcid"}))
                if (!proof.path(pair[1]).asText().equals(metric(atomic, "vep." + role + "." + pair[0]))) return "REFERENCE_PROTEIN_RELATION_MISMATCH";
        }
        return proteinRelation(target, identity).equals(metric(atomic, "vep.proteinRelation"))
                ? null : "REFERENCE_PROTEIN_RELATION_MISMATCH";
    }

    private static String proteinRelation(JsonNode target, JsonNode reference) {
        return target.path("altAminoAcid").equals(reference.path("altAminoAcid"))
                ? "SAME_AMINO_ACID_CHANGE" : "SAME_RESIDUE_DIFFERENT_AMINO_ACID";
    }

    @Nullable private String rnaIssue(JsonNode context, JsonNode policy, JsonNode rna, JsonNode ref) {
        for (JsonNode concern : rna.path("knownConcerns")) if (concern.path("allele").equals(context.at("/variant/evidenceQueryAllele")) || concern.path("allele").equals(ref.path("allele"))) return "PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW";
        // Policy is checked at the material boundary; actual RAW checks for both alleles
        // still run after reference validation. This does not claim normal experimental RNA.
        if (MissenseRnaApplicability.automated(rna)) return MissenseRnaApplicability.materialIssue(policy, rna);
        JsonNode found = null;
        for (JsonNode review : rna.path("comparisons")) if (review.path("targetAllele").equals(context.at("/variant/evidenceQueryAllele")) && review.path("referenceAllele").equals(ref.path("allele"))) {
            if (found != null) return "RNA_REVIEW_AMBIGUOUS"; found = review;
        }
        return found == null || !"REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED".equals(found.path("status").asText())
                || !MissenseComparisonPolicy.text(found.path("reviewId")) || !MissenseComparisonPolicy.boundSources(policy, found.path("sourceIds"))
                ? "RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON" : null;
    }

    /** Count independently qualified protein changes, never SCVs or alternate DNA spellings. */
    private List<Choice> countedPathogenicChoices(JsonNode policy, String criterion, List<Choice> choices, ObjectNode result) {
        var rule = policy.at("/rules/PM5");
        if (!PM5.equals(criterion) || !MissenseComparisonPolicy.countedMode(rule.path("mode").asText())) return List.of();
        var distinct = new LinkedHashMap<String, Choice>();
        var ordered = new ArrayList<>(choices);
        ordered.sort(Comparator.comparing((Choice c) -> c.reference().identity().path("proteinVariantKey").asText())
                .thenComparing(c -> c.reference().audit().path("scv").asText()));
        var audit = result.putObject("pathogenicSubstitutionCount").put("countBy", "DISTINCT_REFERENCE_PROTEIN_SUBSTITUTION")
                .put("countedClassification", "PATHOGENIC").put("minimum", 2).put("strength", "STRONG");
        audit.set("sourceIds", rule.at("/countRule/sourceIds").deepCopy());
        var rows = audit.putArray("eligibleSubstitutions");
        for (var choice : ordered) {
            var reference = choice.reference();
            if (!"PATHOGENIC".equals(MissenseIndependentReference.adoptedClassification(reference.audit()))) continue;
            String key = reference.identity().path("nativeModelSha256").asText() + ":" + reference.identity().path("proteinVariantKey").asText();
            if (distinct.putIfAbsent(key, choice) != null) continue;
            rows.addObject().put("proteinVariantKey", reference.identity().path("proteinVariantKey").asText())
                    .put("nativeModelSha256", reference.identity().path("nativeModelSha256").asText()).put("adoptedClassification", "PATHOGENIC")
                    .put("referenceId", reference.audit().path("referenceId").asText())
                    .put("evidenceId", reference.atomic().path("evidenceId").asText());
        }
        boolean qualified = distinct.size() >= 2;
        audit.put("distinctPathogenicSubstitutions", distinct.size()).put("countRuleSatisfied", qualified)
                .put("comparisonStrengthCount", choices.isEmpty() ? 0 : 1);
        return qualified ? distinct.values().stream().limit(2).toList() : List.of();
    }

    /** Exact bridge for the MAIN-owned evaluator; does not requalify source records or rerun RNA.
     * Like the pre-existing single-reference decision, input must be the actual runtime output.
     */
    static boolean validCountedDecision(ObjectMapper mapper, JsonNode policy, JsonNode decision,
            JsonNode inputTrace, List<ObjectNode> atomics) {
        try {
            if (MissenseComparisonPolicy.issue(policy, PM5) != null
                    || !MissenseComparisonPolicy.countedMode(policy.at("/rules/PM5/mode").asText())
                    || !"MET".equals(decision.path("status").asText()) || !"STRONG".equals(decision.path("appliedStrength").asText())
                    || !decision.path("countRuleApplied").isBoolean() || !decision.path("countRuleApplied").asBoolean()
                    || !decision.path("evidenceIds").isArray() || decision.path("evidenceIds").size() != 2
                    || !decision.path("selectedReferenceIds").isArray() || decision.path("selectedReferenceIds").size() != 2
                    || inputTrace.path("referenceStrengthCount").asInt() != 2 || inputTrace.path("comparisonStrengthCount").asInt() != 1) return false;
            var proof = decision.path("pathogenicSubstitutionCount"); var rows = proof.path("eligibleSubstitutions");
            if (!new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(proof).equals(inputTrace.path("pathogenicSubstitutionCountSha256").asText())
                    || !"DISTINCT_REFERENCE_PROTEIN_SUBSTITUTION".equals(proof.path("countBy").asText())
                    || !"PATHOGENIC".equals(proof.path("countedClassification").asText()) || proof.path("minimum").asInt() != 2
                    || !"STRONG".equals(proof.path("strength").asText()) || !proof.path("countRuleSatisfied").asBoolean()
                    || !proof.path("sourceIds").equals(policy.at("/rules/PM5/countRule/sourceIds"))
                    || !rows.isArray() || rows.size() < 2 || proof.path("distinctPathogenicSubstitutions").asInt() != rows.size()) return false;
            var proteinKeys = new HashSet<String>(); var evidenceIds = new HashSet<String>(); var referenceIds = new HashSet<String>();
            for (var row : rows) {
                String key = row.path("proteinVariantKey").asText();
                if (!key.matches("[ACDEFGHIKLMNPQRSTVWY][1-9][0-9]*[ACDEFGHIKLMNPQRSTVWY]") || !proteinKeys.add(key)
                        || !row.path("nativeModelSha256").equals(policy.path("nativeModelSha256"))
                        || !"PATHOGENIC".equals(row.path("adoptedClassification").asText())
                        || !evidenceIds.add(row.path("evidenceId").asText()) || !referenceIds.add(row.path("referenceId").asText())) return false;
            }
            var selectedIds = new HashSet<String>(); var selectedRefs = new HashSet<String>(); String residue = null;
            for (int index = 0; index < 2; index++) {
                String id = decision.path("evidenceIds").get(index).asText(); String referenceId = decision.path("selectedReferenceIds").get(index).asText();
                if (!selectedIds.add(id) || !selectedRefs.add(referenceId)) return false;
                JsonNode matched = null; for (var row : rows) if (id.equals(row.path("evidenceId").asText())
                        && referenceId.equals(row.path("referenceId").asText())) matched = row;
                if (matched == null) return false;
                ObjectNode atomic = null; for (var row : atomics) if (id.equals(row.path("evidenceId").asText())) {
                    if (atomic != null && !atomic.equals(row)) return false; atomic = row;
                }
                if (atomic == null) return false;
                String key = metric(atomic, "vep.comparator.referenceAminoAcid") + metric(atomic, "vep.comparator.proteinPosition")
                        + metric(atomic, "vep.comparator.alternateAminoAcid");
                if (!key.equals(matched.path("proteinVariantKey").asText())
                        || !"SAME_RESIDUE_DIFFERENT_AMINO_ACID".equals(metric(atomic, "vep.proteinRelation"))) return false;
                String referenceResidue = key.substring(0, key.length() - 1);
                if (residue != null && !residue.equals(referenceResidue)) return false; residue = referenceResidue;
            }
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    private String strength(JsonNode policy, JsonNode materials, String criterion, JsonNode target, Reference reference) {
        JsonNode rule = policy.path("rules").path(PS1.equals(criterion) ? "PS1" : "PM5");
        String classification = MissenseIndependentReference.adoptedClassification(reference.audit());
        if (PM5.equals(criterion) && MissenseComparisonPolicy.COUNTED_DIRECT.equals(rule.path("mode").asText()))
            return "PATHOGENIC".equals(classification) ? "MODERATE" : "NONE";
        String pass = rule.path("classificationStrengths").path(classification).asText();
        if (pass.isBlank()) return "MISSENSE_REFERENCE_CLASSIFICATION_OUTSIDE_POLICY";
        if (PS1.equals(criterion)) return pass;
        // Exact allele/protein relationship, independent qualification and both splice gates
        // were checked before this operator. No implicit matrix or per-triple whitelist.
        if (MissenseComparisonPolicy.DIRECT_RESIDUE.equals(rule.path("mode").asText())) return pass;
        if ("AUDITED_AMINO_ACID_TRIPLES_SINGLE_REFERENCE".equals(rule.path("mode").asText())) {
            for (JsonNode relation : materials.path("aminoAcidRelations")) if (relation.path("referenceAminoAcid").equals(target.path("refAminoAcid"))
                    && relation.path("targetAlternate").equals(target.path("altAminoAcid")) && relation.path("comparatorAlternate").equals(reference.identity().path("altAminoAcid"))
                    && MissenseComparisonPolicy.text(relation.path("reviewId")) && MissenseComparisonPolicy.boundSources(policy, relation.path("sourceIds"))) return pass;
            return "MISSENSE_AMINO_ACID_RELATION_NOT_AUDITED";
        }
        JsonNode row = materials.at("/substitutionMatrix/matrix").path(target.path("refAminoAcid").asText());
        int candidate = row.path(target.path("altAminoAcid").asText()).asInt(); int comparator = row.path(reference.identity().path("altAminoAcid").asText()).asInt();
        boolean satisfied = "TARGET_GTE_REFERENCE".equals(rule.path("comparison").asText()) ? candidate >= comparator : candidate <= comparator;
        String fail = rule.path("failureStrengths").path(classification).asText();
        return satisfied ? pass : fail.isBlank() ? "MISSENSE_SUBSTITUTION_FAILURE_BRANCH_UNIMPLEMENTED" : fail;
    }

    private boolean validBatchHeader(JsonNode raw, List<SpliceAiRawBatchRunner.Query> queries) {
        ArrayNode requested = mapper.createArrayNode();
        for (SpliceAiRawBatchRunner.Query query : queries) {
            ObjectNode row = requested.addObject().put("queryId", query.getQueryId()); row.set("allele", allele(query.getKey()));
            row.put("geneSymbol", query.getGeneSymbol()).put("selectedTranscriptId", query.getSelectedTranscriptId());
        }
        try { return SpliceAiRawBatchRunner.RESULT_SCHEMA_VERSION.equals(raw.path("resultSchemaVersion").asText())
                && "SUCCEEDED".equals(raw.path("status").asText()) && raw.path("results").size() == queries.size()
                && ("spliceai-batch-sha256-" + AcmgBusinessStore.sha(mapper.writeValueAsString(requested))).equals(raw.path("requestId").asText()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return false; }
    }
    @Nullable private BigDecimal score(JsonNode batch, SpliceAiRawBatchRunner.Query query) {
        JsonNode found = null;
        for (JsonNode row : batch.path("results")) if (query.getQueryId().equals(row.path("queryId").asText())) { if (found != null) return null; found = row; }
        if (found == null || !"HIT".equals(found.path("status").asText()) || !found.path("exactAlleleMatch").isBoolean() || !found.path("exactAlleleMatch").asBoolean()
                || !allele(query.getKey()).equals(found.path("allele")) || found.path("annotations").size() != 1) return null;
        JsonNode annotation = found.at("/annotations/0");
        if (!query.getGeneSymbol().equals(annotation.path("geneSymbol").asText())) return null;
        BigDecimal maximum = BigDecimal.ZERO;
        for (String field : List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) {
            BigDecimal value = MissenseComparisonPolicy.decimal(annotation.path("deltaScores").path(field));
            if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) return null;
            maximum = maximum.max(value);
        }
        return maximum;
    }
    private static boolean lowSplice(@Nullable BigDecimal score, JsonNode policy) {
        BigDecimal maximum = MissenseComparisonPolicy.decimal(policy.at("/splicePolicy/maximumScore"));
        return score != null && maximum != null && ("LTE".equals(policy.at("/splicePolicy/operator").asText()) ? score.compareTo(maximum) <= 0 : score.compareTo(maximum) < 0);
    }
    private static String mappedDisease(JsonNode atomic, JsonNode mappings) {
        return MissenseConditionMapping.resolve(metric(atomic, "clinvar.assertion.condition.submittedRaw"),
                metric(atomic, "clinvar.assertion.condition.reportedRaw"), mappings);
    }
    private static boolean explicitOtherDisease(JsonNode atomic, String target) {
        for (String field : List.of("submittedRaw", "reportedRaw")) {
            String value = metric(atomic, "clinvar.assertion.condition." + field).replace("MONDO:MONDO:", "MONDO:");
            if (value.matches("MONDO:[0-9]{7}") && !value.equals(target) && exactDisease(atomic, value)) return true;
        }
        return false;
    }
    private static boolean exactDisease(JsonNode atomic, String mondo) {
        String submitted = metric(atomic, "clinvar.assertion.condition.submittedRaw").replace("MONDO:MONDO:", "MONDO:");
        String reported = metric(atomic, "clinvar.assertion.condition.reportedRaw").replace("MONDO:MONDO:", "MONDO:");
        return (submitted.equals(mondo) || reported.equals(mondo)) && (!submitted.startsWith("MONDO:") || submitted.equals(mondo)) && (!reported.startsWith("MONDO:") || reported.equals(mondo));
    }
    private static String classification(String value) { return value.trim().toUpperCase(Locale.ROOT).replace(' ', '_'); }
    private static String date(String value) { return ClinVarAssertionDate.iso(value); }
    private static String metric(JsonNode atomic, String id) { String result = null; for (JsonNode m : atomic.path("measurements")) if (id.equals(m.path("metricId").asText())) { if (result != null) return ""; result = m.path("rawValue").asText(); } return result == null ? "" : result; }
    private static int strengthRank(String strength) { return switch (strength) { case "STRONG" -> 3; case "MODERATE" -> 2; case "SUPPORTING" -> 1; default -> 0; }; }

    /** Reports one usable comparison criterion; never adds PS1 and PM5. */
    public ObjectNode summarizeOverlap(List<ObjectNode> results) {
        String contextId = null;
        JsonNode target = null;
        Set<String> seen = new HashSet<>(), met = new HashSet<>();
        for (ObjectNode result : results) {
            String current = result.at("/policySelection/contextId").asText();
            if (current.isBlank() || contextId != null && !current.equals(contextId)
                    || result.has("context") && !current.equals(result.at("/context/contextId").asText()))
                throw new IllegalArgumentException("Missense overlap context mismatch");
            contextId = current;
            JsonNode identity = result.path("targetIdentity");
            if (result.has("targetIdentity") && !identity.isEmpty()) {
                if (!identity.isObject() || target != null && !target.equals(identity)) throw new IllegalArgumentException("Missense overlap target mismatch");
                target = identity;
            }
            for (JsonNode evaluation : result.path("criterionEvaluations")) {
                String id = evaluation.path("criterionId").asText(), status = evaluation.at("/evaluationResult/status").asText();
                if (!Set.of(PS1, PM5).contains(id) || !seen.add(id)
                        || evaluation.has("contextId") && !current.equals(evaluation.path("contextId").asText()))
                    throw new IllegalArgumentException("Missense overlap requires unique same-context PS1/PM5");
                if (Set.of("MET", "NOT_MET").contains(status) && (!identity.isObject() || identity.isEmpty()
                        || !result.has("context") || !"PS1_PRECEDENCE_NO_ADDITIVE_REFERENCE_SUPPORT".equals(result.at("/policy/overlapPolicy").asText())))
                    throw new IllegalArgumentException("Missense completed comparison binding missing");
                if ("MET".equals(status)) met.add(id);
            }
        }
        ObjectNode summary = mapper.createObjectNode().put("comparisonStrengthCount", met.isEmpty() ? 0 : 1)
                .put("overlapPolicy", "PS1_PRECEDENCE_NO_ADDITIVE_REFERENCE_SUPPORT");
        if (!met.isEmpty()) summary.put("preferredComparisonCriterion", met.contains(PS1) ? PS1 : PM5);
        return summary;
    }
    private static NormalizedVariantKey key(JsonNode a) { return new NormalizedVariantKey(a.path("assembly").asText(), a.path("contig").asText(), a.path("position").asInt(), a.path("reference").asText(), a.path("alternate").asText()); }
    private ObjectNode allele(NormalizedVariantKey k) { return mapper.createObjectNode().put("assembly", k.getGenomeAssembly()).put("contig", k.getChromosome()).put("position", k.getPosition()).put("reference", k.getReference()).put("alternate", k.getAlternate()); }
    private ObjectNode base(JsonNode receipt) { ObjectNode result = mapper.createObjectNode().put("classificationEnabled", false).put("providerCalls", 0).put("evaluatorCalls", 0).put("rawBatchCalls", 0); if (receipt != null) result.set("policySelection", receipt.deepCopy()); result.putArray("criterionEvaluations"); return result; }
    private ObjectNode refuse(ObjectNode result, String reason) {
        ObjectNode evaluation = result.withArray("criterionEvaluations").addObject().put("criterionId", result.at("/policySelection/criterionIds/0").asText())
                .put("contextId", result.at("/policySelection/contextId").asText()).put("appliedStrength", "NONE");
        evaluation.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", reason);
        evaluation.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason); return result;
    }
    private record Reference(ObjectNode atomic, JsonNode audit, ObjectNode identity, String relation) { }
    private record Choice(Reference reference, String strength) { }
}
