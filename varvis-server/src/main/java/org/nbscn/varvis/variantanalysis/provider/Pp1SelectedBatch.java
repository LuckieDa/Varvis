package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.math.BigDecimal;
import org.nbscn.varvis.variantanalysis.service.CurrentAcmgEvaluationSession;
import org.nbscn.varvis.variantanalysis.service.CurrentAdoptionInventory;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.springframework.lang.Nullable;

/** Current PP1 assessment from immutable source tasks, independently replayed
 * against the exact selected context. No network/model work during scoring. */
public final class Pp1SelectedBatch {
    public static final String VERSION = "pp1-selected-literature-v1";
    public static final String CRITERION = "ACMG_AMP_2015:PP1";
    /** Stable factory identity is hashable before claim; per-run authority is supplied only
     * after successful claim. Implementations must reject inventory/session mismatches.
     */
    public interface CurrentContributionFactory {
        ObjectNode identity();
        Pp1ContributionSnapshot.Source forCurrentSession(CurrentAcmgEvaluationSession session, CurrentAdoptionInventory inventory);
    }
    public interface Source {
        ObjectNode identity();
        ObjectNode capture(String analysisId, ObjectNode recovered) throws IOException;
        ObjectNode evaluate(ObjectNode captured, ObjectNode binding) throws IOException;
        default boolean verifiesPublishedPp4Exclusion() { return false; }
    }
    private final ObjectMapper json;
    private final Source source;
    private final ObjectNode identity;
    @Nullable private final Pp1ContributionSnapshot.Source contributionSource;
    private final ObjectNode contributionIdentity;
    @Nullable private final CurrentContributionFactory currentFactory;
    @Nullable private CurrentAcmgEvaluationSession targetSession;
    private boolean currentBound;
    @Nullable private String currentInputFailure;
    @Nullable private ObjectNode closedPp4Scope;
    private boolean maySealUnregisteredPp4;
    private Pp1ContributionSnapshot contributions;
    private ObjectNode snapshot;
    private int calls;
    public Pp1SelectedBatch(ObjectMapper json, Source source) {
        this(json, source, null);
    }
    /** This internal port must be wired by the service to frozen current adoption results.
     * No HTTP/declaration, historical score or author output is a contribution Source. */
    public Pp1SelectedBatch(ObjectMapper json, Source source, @Nullable Pp1ContributionSnapshot.Source contributionSource) {
        this(json, source, contributionSource, null);
    }
    private Pp1SelectedBatch(ObjectMapper json, Source source, @Nullable Pp1ContributionSnapshot.Source contributionSource,
            @Nullable CurrentContributionFactory currentFactory) {
        this.json=Objects.requireNonNull(json); this.source=Objects.requireNonNull(source);
        this.contributionSource=contributionSource; this.currentFactory=currentFactory;
        contributionIdentity=currentFactory!=null ? currentFactory.identity().deepCopy()
                : contributionSource==null ? json.createObjectNode().put("status","NOT_CONFIGURED")
                : contributionSource.identity().deepCopy();
        contributions=Pp1ContributionSnapshot.missing(json);
        identity=json.createObjectNode().put("version",VERSION)
                .put("sourceReuseCapability","VERIFIED_SAME_CONTEXT_ACROSS_CRITERIA_V1")
                .put("contributionBindingCapability",Pp1ContributionSnapshot.CAPABILITY);
        identity.set("source",source.identity());
        identity.set("contributionSource",contributionIdentity.deepCopy());
        if(currentFactory!=null) identity.put("currentContributionLifecycle", "POSTCLAIM_CONSUMER_TERMINALS_ONCE_BEFORE_PP1_V1");
    }
    /** Inputs are stable before hashing; authority and outputs only bind after the actual claim. */
    public static Pp1SelectedBatch withCurrentFactory(ObjectMapper json, Source source, CurrentContributionFactory factory) {
        return new Pp1SelectedBatch(json, source, null, Objects.requireNonNull(factory));
    }

    public static Pp1SelectedBatch withWorkbenchRegistry(ObjectMapper json, Source source) {
        var batch = new Pp1SelectedBatch(json, source);
        batch.maySealUnregisteredPp4 = true;
        batch.identity.put("currentContributionLifecycle", "POSTCLAIM_NO_PP4_REGISTERED_SCOPE_V1");
        return batch;
    }

    boolean hasWorkbenchRegistry() { return maySealUnregisteredPp4; }

    synchronized void bindUnregisteredPp4(CurrentAcmgEvaluationSession session, LocalEvidenceBatchRuntime.CurrentConsumerExecution execution) {
        if (!maySealUnregisteredPp4 || snapshot == null || currentBound || calls != 0 || !execution.matches(session))
            throw new IllegalStateException("PP1_CURRENT_CONTRIBUTION_LATE_BIND_PHASE_INVALID");
        var actual = execution.snapshot();
        var configured = actual.path("configuredCriteria");
        if (!"ALL_ATTEMPTS_STAGE1_COMPLETE_BEFORE_PP1".equals(actual.path("phase").asText())
                || !actual.at("/registry/pp4/configured").isBoolean() || actual.at("/registry/pp4/configured").asBoolean()
                || configured.valueStream().anyMatch(value -> "ACMG_AMP_2015:PP4".equals(value.asText()))
                || configured.valueStream().noneMatch(value -> CRITERION.equals(value.asText())))
            throw new IllegalStateException("PP1_CURRENT_CONTRIBUTION_PP4_REGISTRY_NOT_CLOSED");
        var identity = session.identity();
        if (!snapshot.path("analysisId").equals(identity.path("analysisId"))
                || !identity.path("executionSourceSha256").equals(actual.path("sourceSnapshotSha256")))
            throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_LATE_BIND_IDENTITY_MISMATCH");
        var proof = json.createObjectNode().put("schemaVersion","pp1-closed-workbench-pp4-scope-v1")
                .put("reasonCode","PP1_CURRENT_CONTRIBUTION_PP4_NOT_REGISTERED");
        for (String name : List.of("analysisId","runId","requestSha256","executionSourceSha256"))
            proof.set(name,identity.path(name).deepCopy());
        proof.set("configuredCriteria", configured.deepCopy());
        proof.set("consumerExecutionSha256", actual.path("executionSha256").deepCopy());
        proof.put("proofSha256",new RuntimeEvidenceV2Adapter(json).canonicalSha256(proof));
        closedPp4Scope=proof;
        targetSession=session;
        currentBound=true;
    }
    /** A real configuration failure remains unavailable; it is never a zero-producing factory. */
    public static Pp1SelectedBatch unavailableCurrentInputs(ObjectMapper json,Source source,ObjectNode inputReadiness) {
        var batch=new Pp1SelectedBatch(json,source);
        batch.currentInputFailure="PP1_CURRENT_REGISTERED_INPUTS_UNAVAILABLE";
        batch.identity.set("currentInputReadiness",inputReadiness.deepCopy());
        return batch;
    }
    public Optional<Pp1CurrentContributionSource.Factory> currentNativeFactory() {
        return currentFactory instanceof Pp1CurrentContributionSource.Factory nativeFactory ? Optional.of(nativeFactory) : Optional.empty();
    }
    public synchronized void bindCurrentContributions(CurrentAcmgEvaluationSession session, CurrentAdoptionInventory inventory) {
        CurrentContributionFactory factory=currentFactory;
        if(factory==null || snapshot==null || currentBound || calls!=0)
            throw new IllegalStateException("PP1_CURRENT_CONTRIBUTION_LATE_BIND_PHASE_INVALID");
        if(!contributionIdentity.equals(factory.identity()) || !inventory.matches(session)
                || !snapshot.path("analysisId").equals(session.identity().path("analysisId")))
            throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_LATE_BIND_IDENTITY_MISMATCH");
        var actual=factory.forCurrentSession(session,inventory);
        contributions=Pp1ContributionSnapshot.capture(json,actual,contributionIdentity,
                session.identity().path("analysisId").asText(),json.createObjectNode());
        targetSession=session; currentBound=true;
    }
    public ObjectNode currentContributionOriginals() {
        return closedPp4Scope == null ? contributions.originals() : closedPp4Scope.deepCopy();
    }
    public ObjectNode identity() { return identity.deepCopy(); }
    public int evaluations() { return calls; }
    public ObjectNode originals() { return snapshot == null ? json.createObjectNode() : snapshot.deepCopy(); }
    public ObjectNode prepare(String analysis, ObjectNode recovered) {
        if(snapshot!=null)throw new IllegalStateException("PP1_SNAPSHOT_ALREADY_PREPARED");
        try {
            if(!identity.path("source").equals(source.identity()))throw new IOException("PP1_SOURCE_DEPENDENCY_CHANGED");
            snapshot=source.capture(analysis,recovered).deepCopy();
            var content=snapshot.deepCopy();String sha=content.path("snapshotSha256").asText();content.remove("snapshotSha256");
            if(!"pp1-business-source-snapshot-v1".equals(snapshot.path("schemaVersion").asText())
                    || !analysis.equals(snapshot.path("analysisId").asText()) || !snapshot.path("documents").isArray()
                    || !sha.equals(new RuntimeEvidenceV2Adapter(json).canonicalSha256(content)))
                throw new IOException("PP1_SOURCE_SNAPSHOT_INVALID");
            if(!identity.path("source").equals(source.identity()))throw new IOException("PP1_SOURCE_DEPENDENCY_CHANGED");
        } catch(IOException|IllegalArgumentException failure) {
            snapshot=json.createObjectNode().put("schemaVersion","pp1-business-source-snapshot-v1")
                    .put("analysisId",analysis).put("reasonCode",reason(failure,"PP1_SOURCE_CAPTURE_FAILED"));
            snapshot.putArray("documents"); snapshot.put("snapshotSha256",new RuntimeEvidenceV2Adapter(json).canonicalSha256(snapshot));
        }
        // Legacy sources keep their existing behavior. The current factory binds only
        // registered input identity here; no session, producer or adopted inventory exists yet.
        if(currentFactory==null) contributions=Pp1ContributionSnapshot.capture(json,contributionSource,contributionIdentity,analysis,recovered);
        else if(!contributionIdentity.equals(currentFactory.identity()))
            throw new IllegalStateException("PP1_CONTRIBUTION_SOURCE_CHANGED");
        var result=json.createObjectNode().put("snapshotSha256",snapshot.path("snapshotSha256").asText());
        result.set("contributionPreparation",contributions.summary());
        if(currentInputFailure!=null) result.withObject("/contributionPreparation").put("reasonCode",currentInputFailure)
                .set("inputReadiness",identity.path("currentInputReadiness").deepCopy());
        result.put("documents",snapshot.path("documents").size());
        result.put("reasonCode",snapshot.path("reasonCode").asText("PP1_SOURCE_SNAPSHOT_CAPTURED"));return result;
    }
    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidate, String hypothesis,
            String sourcePin, List<String> criteria) {
        var out=json.createObjectNode().put("criterionId",CRITERION).put("version",VERSION)
                .put("candidateId",candidate).put("hypothesisId",hypothesis);
        var summary=out.putObject("publicSummary").put("schemaVersion","pp1-selected-facts-v1").put("clinicalApproval",false);
        if(snapshot==null)return unavailable(out,"PP1_SOURCE_SNAPSHOT_NOT_PREPARED");
        summary.put("sourceSnapshotSha256",snapshot.path("snapshotSha256").asText());
        summary.set("excludedTasks",snapshot.path("excludedTasks").isArray()?snapshot.path("excludedTasks").deepCopy():json.createArrayNode());
        if(currentInputFailure!=null) {
            summary.putObject("contributionPreparation").put("status","UNKNOWN").put("reasonCode",currentInputFailure)
                    .set("inputReadiness",identity.path("currentInputReadiness").deepCopy());
            return unavailable(out,currentInputFailure);
        }
        if(snapshot.has("reasonCode"))return unavailable(out,snapshot.path("reasonCode").asText());
        try {
            if(!identity.path("source").equals(source.identity()))throw new IOException("PP1_SOURCE_DEPENDENCY_CHANGED");
            if(contributionSource!=null && !contributionIdentity.equals(contributionSource.identity()))
                throw new IOException("PP1_CONTRIBUTION_SOURCE_CHANGED");
            if(currentFactory!=null && !contributionIdentity.equals(currentFactory.identity()))
                throw new IOException("PP1_CONTRIBUTION_SOURCE_CHANGED");
            var assembly=selected.assembly();
            var preparedBinding=new Pp1AnalysisContext(json).prepare(selected,candidate,hypothesis,sourcePin,criteria);
            var binding=currentFactory==null ? contributions.apply(preparedBinding,assembly==null?null:assembly.analysisId())
                    : contributions.apply(preparedBinding,assembly==null?null:assembly.analysisId(),targetSession);
            var closedScope = closedPp4Scope;
            var session = targetSession;
            if (closedScope != null && session != null) {
                if (session.attempts().stream().noneMatch(attempt -> preparedBinding.path("identity").equals(attempt.at("/binding/identity"))))
                    throw new IOException("PP1_CURRENT_CONTRIBUTION_ATTEMPT_SCOPE_MISMATCH");
                binding.withObject("/ledger").put("status","KNOWN_EMPTY").put("reasonCode",closedScope.path("reasonCode").asText())
                        .put("pp4Points",0).set("workbenchScope",closedScope.deepCopy());
            }
            out.set("contributionAudit",currentContributionOriginals());
            var preparation=contributions.summary();
            preparation.put("ledgerStatus",binding.at("/ledger/status").asText())
                    .put("reasonCode",binding.at("/ledger/reasonCode").asText())
                    .put("requirement","当前变异及疾病背景下，经系统核验的完整证据采用、家系贡献及排他事实清单。")
                    .put("finding","UNKNOWN".equals(binding.at("/ledger/status").asText())
                            ? contributionSource==null && currentFactory==null ? "当前未接入正式可信PP4采用来源，未取得可核验的当前贡献清单。"
                                : "内部来源已配置，但本次贡献清单尚未通过身份、范围或完整性核验。"
                            : "已核对当前贡献清单的身份、范围、来源和采用分配。")
                    .put("gap","UNKNOWN".equals(binding.at("/ledger/status").asText())
                            ? "尚不能核定已采用PP4贡献及已分配的共享事实，不能将未知视为零。" : "当前贡献清单核验完整。")
                    .put("impact","UNKNOWN".equals(binding.at("/ledger/status").asText())
                            ? "保留已取得的文献及病例事实，本次不依据未知账本采用PP1强度；不表示证据阴性。"
                            : "按现有规则检查剩余容量与重复事实；清单就绪本身不授予证据。")
                    .put("nextStep","UNKNOWN".equals(binding.at("/ledger/status").asText())
                            ? "由系统接通相应可信采用清单；重复检索或补填患者资料不能代替该系统接线。"
                            : "继续现有PP1规则评价。");
            if (closedScope != null) {
                preparation.put("status","COMPLETE").put("finding","已核对本次工作台的证据采用范围，本次没有 PP4 贡献。")
                        .put("gap","");
                preparation.set("workbenchScope",closedScope.deepCopy());
            }
            summary.set("contributionPreparation",preparation);
            var captured=snapshot.deepCopy();var documents=captured.putArray("documents");
            for(var doc:snapshot.path("documents")) {
                if(!sameSourceContext(binding.path("identity"), doc.at("/request/analysisBinding/identity"))) {
                    summary.withArray("excludedTasks").addObject().put("taskId",doc.path("taskId").asText())
                            .put("reasonCode","PP1_TASK_SELECTED_CONTEXT_STALE");continue;
                }
                documents.add(doc.deepCopy());
            }
            var result=source.evaluate(captured,binding);
            var assessment=result.path("evaluation");
            var currentLedger=binding.path("ledger");
            var evaluatedLedger=assessment.path("ledger");
            if(!"pp1-business-evaluation-v1".equals(result.path("schemaVersion").asText())
                    || !new RuntimeEvidenceV2Adapter(json).canonicalSha256(binding).equals(result.path("analysisBindingSha256").asText())
                    || result.path("modelCalls").asInt(-1)!=0 || result.path("networkRequests").asInt(-1)!=0
                    || !binding.path("selection").equals(assessment.path("target")))
                throw new IOException("PP1_EVALUATION_BINDING_INVALID");
            if(!currentLedger.equals(evaluatedLedger)) {
                if (!source.verifiesPublishedPp4Exclusion() || currentFactory != null || contributionSource != null)
                    throw new IOException("PP1_EVALUATION_BINDING_INVALID");
                verifyPublishedLedger(json, currentLedger, assessment);
                preparation.put("status","COMPLETE").put("ledgerStatus","KNOWN_EMPTY")
                        .put("reasonCode",evaluatedLedger.path("reasonCode").asText())
                        .put("finding","当前适用的已发布专家组规范明确规定 PP4 不适用。")
                        .put("gap","")
                        .put("impact","当前疾病背景没有 PP4 分值或共享事实占用。")
                        .put("nextStep","继续现有 PP1 规则评价。");
                preparation.set("publishedPp4Exclusion",evaluatedLedger.path("publishedPp4Exclusion").deepCopy());
            }
            if(!identity.path("source").equals(source.identity()))throw new IOException("PP1_SOURCE_DEPENDENCY_CHANGED");
            if(contributionSource!=null && !contributionIdentity.equals(contributionSource.identity()))
                throw new IOException("PP1_CONTRIBUTION_SOURCE_CHANGED");
            calls+=result.path("evaluatorCalls").asInt();
            String status=assessment.path("status").asText();
            if(!List.of("MET","NOT_MET","NOT_EVALUATED").contains(status))throw new IOException("PP1_EVALUATION_STATUS_INVALID");
            boolean contributionKnown=List.of("KNOWN","KNOWN_EMPTY").contains(evaluatedLedger.path("status").asText());
            if(!contributionKnown && !"NOT_EVALUATED".equals(status))throw new IOException("PP1_CURRENT_CONTRIBUTION_REQUIRED");
            boolean formal="pp1-segregation-evaluation-v2".equals(assessment.path("schemaVersion").asText())
                    && "VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1".equals(assessment.path("method").asText());
            if(!formal && !("pp1-segregation-evaluation-v1".equals(assessment.path("schemaVersion").asText())
                    && !assessment.has("method")))throw new IOException("PP1_EVALUATION_METHOD_INVALID");
            BigDecimal remaining=contributionKnown
                    ? BigDecimal.valueOf(5).subtract(new BigDecimal(evaluatedLedger.path("pp4Points").asText())) : BigDecimal.ZERO;
            if(formal && !assessment.path("awardedPP1Points").isIntegralNumber())
                throw new IOException("PP1_EVALUATION_SHARED_CAPACITY_INVALID");
            if(formal || "MET".equals(status) || assessment.has("awardedPP1Points")) {
                BigDecimal awarded=Pp1ContributionSnapshot.pointValue(assessment.path("awardedPP1Points"),"PP1_EVALUATION_SHARED_CAPACITY_INVALID");
                if(!"MET".equals(status) && awarded.signum()!=0 || awarded.compareTo(remaining)>0)
                    throw new IOException("PP1_EVALUATION_SHARED_CAPACITY_INVALID");
                if(formal && "MET".equals(status)) {
                    int expected=switch(assessment.path("strength").asText()) {
                        case "SUPPORTING" -> 1; case "MODERATE" -> 2; case "STRONG" -> 4; default -> -1;
                    };
                    if(expected<0)throw new IOException("PP1_EVALUATION_STRENGTH_INVALID");
                    if(awarded.compareTo(BigDecimal.valueOf(expected))!=0)
                        throw new IOException("PP1_EVALUATION_SHARED_CAPACITY_INVALID");
                }
            }
            // Formal allocated points are the capped positive log display quantity,
            // not the adopted award: a below-threshold NOT_MET may retain a fraction.
            if(formal && ("MET".equals(status) || assessment.has("allocatedPP1Points"))) {
                BigDecimal potential=Pp1ContributionSnapshot.pointValue(assessment.path("allocatedPP1Points"),"PP1_EVALUATION_SHARED_CAPACITY_INVALID");
                if(potential.compareTo(remaining)>0 || "NOT_EVALUATED".equals(status) && potential.signum()!=0)
                    throw new IOException("PP1_EVALUATION_SHARED_CAPACITY_INVALID");
            }
            if("MET".equals(status) && !List.of("SUPPORTING","MODERATE","STRONG").contains(assessment.path("strength").asText()))
                throw new IOException("PP1_EVALUATION_STRENGTH_INVALID");
            if(!"MET".equals(status) && !assessment.path("strength").isNull() && !assessment.path("strength").isMissingNode()
                    && !"NONE".equals(assessment.path("strength").asText()))throw new IOException("PP1_EVALUATION_STRENGTH_INVALID");
            // Publish only a validated assessment. A rejected worker response
            // must not survive beside NOT_EVALUATED as an apparent positive.
            for(String field:List.of("evaluation","sourceCoverage","qualifications"))summary.set(field,result.path(field).deepCopy());
            out.putObject("criterionApplicability").put("status","APPLICABLE");
            out.put("appliedStrength","MET".equals(status)?assessment.path("strength").asText():"NONE");
            out.putObject("evaluationResult").put("status",status).put("reasonCode","MET".equals(status)?"PP1_QUALIFIED_SEGREGATION_THRESHOLD_MET"
                    :"NOT_MET".equals(status)?"PP1_QUALIFIED_SEGREGATION_BELOW_THRESHOLD":"PP1_LITERATURE_REQUIREMENTS_UNRESOLVED");
            return out;
        } catch(IOException|IllegalArgumentException failure) {return unavailable(out,reason(failure,"PP1_EVALUATION_FAILED"));}
    }
    static void verifyPublishedLedger(ObjectMapper json, JsonNode original, JsonNode assessment) throws IOException {
        var context=assessment.path("context");
        var proof=context.at("/policy/pp4Exclusion");
        var evidence=context.path("sourceEvidence");
        if (!(original instanceof ObjectNode input) || !"UNKNOWN".equals(original.path("status").asText())
                || !"PP1_CURRENT_CONTRIBUTION_SOURCE_UNAVAILABLE".equals(original.path("reasonCode").asText())
                || !original.path("pp4Points").isNull() || original.has("unmergedCurrentOccupancy")
                || !original.path("eventIds").equals(json.createArrayNode())
                || !original.path("exclusiveFactIds").equals(json.createArrayNode())
                || !"QUALIFIED".equals(context.path("status").asText())
                || !"ORIGINAL_PUBLISHED_EXPERT_SPECIFICATION".equals(context.path("qualificationBasis").asText())
                || !"pp1-published-pp4-exclusion-v1".equals(proof.path("schemaVersion").asText())
                || !"ACMG_AMP_2015:PP4".equals(proof.path("criterion").asText())
                || !"NOT_APPLICABLE".equals(proof.path("status").asText())
                || !original.path("scope").equals(proof.path("scope"))
                || !proof.path("clausePointer").asText().matches("/ld/CriteriaCode/[0-9]+")
                || !proof.path("clauseSha256").asText().matches("[a-f0-9]{64}")
                || !context.at("/policy/ruleClause").asText().startsWith(proof.path("specificationKey").asText()+"/ld/CriteriaCode/")
                || !evidence.at("/specification/rawSha256").asText().matches("[a-f0-9]{64}"))
            throw new IOException("PP1_EVALUATION_BINDING_INVALID");
        var expected=input.deepCopy().put("status","KNOWN_EMPTY").put("pp4Points",0)
                .put("reasonCode","PP1_CURRENT_CONTRIBUTION_PUBLISHED_PP4_NOT_APPLICABLE");
        expected.set("publishedPp4Exclusion",proof);
        if(!expected.equals(assessment.path("ledger"))) throw new IOException("PP1_EVALUATION_BINDING_INVALID");
    }
    private ObjectNode unavailable(ObjectNode out,String code) {
        out.putObject("criterionApplicability").put("status","UNKNOWN");out.put("appliedStrength","NONE");
        out.putObject("evaluationResult").put("status","NOT_EVALUATED").put("reasonCode",code);return out;
    }
    private static boolean sameSourceContext(JsonNode current, JsonNode captured) {
        if (!(current instanceof ObjectNode now) || !(captured instanceof ObjectNode prior)
                || !pp1Criteria(now.path("enabledCriteria")) || !pp1Criteria(prior.path("enabledCriteria"))) return false;
        // Evidence selection changes the current ledger and score, not the
        // source facts. Everything else, including the exact prepared context
        // and publication source pin, must remain identical for raw replay.
        var nowContext = now.deepCopy(); nowContext.remove("enabledCriteria");
        var priorContext = prior.deepCopy(); priorContext.remove("enabledCriteria");
        return nowContext.equals(priorContext);
    }
    private static boolean pp1Criteria(JsonNode values) {
        if (!values.isArray() || values.isEmpty() || values.size() > 28) return false;
        var seen = new java.util.HashSet<String>();
        for (var value : values)
            if (!value.isTextual() || !value.asText().matches("ACMG_AMP_2015:[A-Z]+[0-9]+") || !seen.add(value.asText())) return false;
        return seen.contains(CRITERION);
    }
    private static String reason(Exception failure,String fallback) {
        String code=failure.getMessage();return code!=null && code.matches("[A-Z][A-Z0-9_]{1,160}")?code:fallback;
    }
}
