package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Allow-listed public projection. No local reports or current-patient identifiers. */
final class DeNovoResultSummary {
    static final String VERSION = "1.2.0";
    private DeNovoResultSummary() { }

    static void appendCoreMaterials(ObjectNode out, JsonNode source, ObjectNode context) {
        var materials = out.putArray("coreMaterials");
        for (var document : source.path("documents")) {
            if (!"SOURCE_READ".equals(document.path("status").asText())) continue;
            for (var row : document.path("facts").path("cases")) {
                var reviewed = row.path("reviewedCase");
                boolean reviewedPaper = DeNovoLiteratureEvidence.REVIEWED_PAPER.equals(reviewed.path("schemaVersion").asText());
                var core = reviewedPaper ? reviewed : row.path("coreEvaluation");
                var display = core.path("display");
                if (!"denovo-core-display-v1".equals(display.path("schemaVersion").asText())
                        || !Set.of("SYNTHETIC", "PUBLIC_PAPER_RESEARCH").contains(display.path("dataClass").asText())
                        || !display.path("caseKey").equals(core.path("caseKey"))
                        || !display.path("documentSha256").equals(core.path("documentSha256"))
                        || !display.path("ledgerSha256").equals(core.path("ledgerSha256"))) continue;
                var identity = row.path("alleleIdentity");
                boolean resolved = "VERIFIED".equals(identity.path("status").asText());
                boolean matches = resolved && identity.path("allele").equals(context.at("/variant/evidenceQueryAllele"))
                        && identity.path("hgncId").equals(context.at("/gene/hgncId"));
                if (reviewedPaper && !matches) continue;
                var view = materials.addObject();
                for (String key : List.of("schemaVersion", "caseKey", "dataClass", "status", "criterionDecision",
                        "documentSha256", "ledgerSha256", "coverageLimitations", "blockers"))
                    view.set(key, display.path(key).deepCopy());
                view.put("selectionMatch", matches ? "EXACT" : resolved ? "DIFFERENT_TARGET" : "UNRESOLVED");
                var discovery = display.path("sourceDiscovery");
                if ("denovo-source-event-discovery-v1".equals(discovery.path("version").asText())) {
                    var located = view.putObject("sourceDiscovery");
                    copy(located, discovery, "version", "checkedSourceSha256s", "criterionDecision", "issues");
                    var records = located.putArray("records");
                    for (var original : discovery.path("records")) {
                        var record = records.addObject();
                        copy(record, original, "sourceSha256", "recordLocator", "isEventCandidate", "missingPaths", "issues");
                        copy(record.putObject("caseReference"), original.path("caseReference"), "sourceSha256", "locator", "kind", "quote");
                        var found = record.putArray("fields");
                        for (var field : original.path("fields"))
                            copy(found.addObject(), field, "path", "sourceSha256", "locator", "kind", "quote");
                    }
                }
                var explanation = display.path("explanation");
                if ("literature-explanation-v1".equals(explanation.path("schemaVersion").asText())
                        && explanation.path("caseKey").equals(core.path("caseKey"))) {
                    var target = view.putObject("explanation");
                    copy(target, explanation, "schemaVersion", "caseKey", "summary", "scopeStatement");
                    copy(target.putObject("paper"), explanation.path("paper"), "paperId", "title", "url");
                    var items = target.putArray("items");
                    for (var original : explanation.path("items")) {
                        var item = items.addObject();
                        copy(item, original, "id", "fieldPaths", "category", "reasonCodes", "label", "requirement", "finding",
                                "explanation", "distinction", "nextStep", "effect", "observedFieldPaths", "scopeStatement");
                        publicSources(item, "checkedScope", original.path("checkedScope"));
                    }
                    var warnings = target.putArray("warnings");
                    for (var original : explanation.path("warnings")) {
                        var warning = warnings.addObject(); copy(warning, original, "text");
                        publicSource(warning.putObject("source"), original.path("source"));
                    }
                }
                var fields = view.putArray("fields");
                for (var original : display.path("fields")) {
                    var field = fields.addObject();
                    for (String key : List.of("path", "value", "availability", "verification", "origin", "subject", "issues"))
                        field.set(key, original.path(key).deepCopy());
                    if (original.has("sourceEvidence")) publicSources(field, "sourceEvidence", original.path("sourceEvidence"));
                    var citations = field.putArray("citations");
                    for (var originalCitation : original.path("citations")) {
                        var citation = citations.addObject();
                        for (String key : List.of("sourceSha256", "role", "locator", "kind"))
                            citation.set(key, originalCitation.path(key).deepCopy());
                        citation.put("quote", Set.of("PAPER_PRIMARY", "PAPER_ATTACHMENT").contains(originalCitation.path("role").asText())
                                ? originalCitation.path("quote").asText() : "");
                    }
                }
            }
        }
    }

    private static void copy(ObjectNode to, JsonNode from, String... keys) {
        for (String key : keys) if (from.has(key)) to.set(key, from.path(key).deepCopy());
    }

    private static void publicSource(ObjectNode to, JsonNode from) {
        if (Set.of("PAPER_PRIMARY", "PAPER_ATTACHMENT").contains(from.path("role").asText()))
            copy(to, from, "sourceSha256", "role", "locator", "kind", "quote", "paperId", "title",
                    "locationLabel", "url", "linkScope", "scopeOnly");
    }

    private static void publicSources(ObjectNode to, String name, JsonNode from) {
        var array = to.putArray(name);
        for (var source : from) if (Set.of("PAPER_PRIMARY", "PAPER_ATTACHMENT").contains(source.path("role").asText()))
            publicSource(array.addObject(), source);
    }

    /** Explain the actual saved decision, not a material precheck or invented score. */
    static void appendDecisions(ObjectNode summary, JsonNode criteria) {
        var explanations = summary.putArray("criterionExplanations");
        for (String code : List.of("PS2", "PM6")) {
            var criterion = criteria.path(code);
            if (!criterion.isObject()) continue;
            var result = criterion.path("evaluationResult");
            String status = result.path("status").asText(), reason = result.path("reasonCode").asText();
            String message = "MET".equals(status) && "EXPERT_FIXED_STRENGTH".equals(summary.path("calculationMethod").asText())
                    ? "已按适用专规采用固定强度；此方法不累计通用新发积分，零积分不表示证据未采用。下方保留实际条件与来源。"
                    : "MET".equals(status) && "ONE_LEVEL_AR_NO_SECOND_P_LP".equals(summary.path("strengthAdjustment").asText())
                    ? "已采用隐性背景的新发证据；因已核范围内未找到另一独立P/LP等位基因，最终强度按规范降低一档，原始积分仍保留。"
                    : "MET".equals(status) ? "已采用此项证据；实际强度和事件积分见保存结果，下方保留病例事实、原文依据及未采用资料。"
                    : reason.startsWith("DENOVO_SHARED_EVENT_ASSIGNED_TO_") ? "同一新发事件已经计入另一条证据，本项不重复计分；这不是缺少论文资料。"
                    : "DENOVO_AR_MIXED_REDUCTION_RULE_NOT_IMPLEMENTED".equals(reason)
                    ? "当前方法尚不支持混合汇总不同伙伴资格对应的隐性强度降档规则；本次未采用，不能把软件未实现解释为缺少资料。"
                    : "NOT_MET".equals(status) ? "原规则已完成评价，未达到本项采用条件；具体未满足条件和排除事件见保存的原因与事件明细。"
                    : "本次未能采用此项证据；请查看具体原因以及下方病例资料要求。未评价不等于已经证实条件不成立。";
            var item = explanations.addObject().put("schemaVersion", "literature-decision-explanation-v1")
                    .put("criterionId", criterion.path("criterionId").asText()).put("status", status)
                    .put("reasonCode", reason).put("appliedStrength", criterion.path("appliedStrength").asText())
                    .put("summary", message);
            item.put("basis", "SAVED_RULE_RESULT_NOT_MODEL_OPINION");
        }
    }

    static void append(ObjectNode summary, JsonNode qualified) {
        summary.put("detailVersion", VERSION).put("clinicalApproval", "NONE").put("completeLiteratureSearch", false);
        var result = qualified.path("result");
        if (Set.of("SVI_POINTS", "EXPERT_FIXED_STRENGTH").contains(result.path("calculationMethod").asText()))
            summary.put("calculationMethod", result.path("calculationMethod").asText());
        if (Set.of("NONE", "ONE_LEVEL_AR_NO_SECOND_P_LP", "NO_SVI_POINT_SUM").contains(result.path("strengthAdjustment").asText()))
            summary.put("strengthAdjustment", result.path("strengthAdjustment").asText());
        var events = summary.putArray("events");
        for (var original : result.path("events")) {
            int ordinal = events.size() + 1;
            var event = events.addObject().put("ordinal", ordinal);
            for (String key : List.of("sourceKind", "disposition", "reasonCode", "rawQuarterPoints", "confirmedParentage"))
                if (original.has(key)) event.set(key, original.path(key));
            var reasons = event.putArray("issues");
            var comparisons = event.putArray("factComparisons");
            for (var code : original.path("details")) {
                String safe = safeIssue(code.asText());
                if (code.isTextual() && safe != null) reasons.add(safe);
                var match = java.util.regex.Pattern.compile("DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT:(subject|mother|father):ordinary=(PRESENT|ABSENT):special=(PRESENT|ABSENT)").matcher(code.asText());
                if (match.matches()) comparisons.addObject().put("reasonCode", "DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT")
                        .put("memberRole", match.group(1).toUpperCase(java.util.Locale.ROOT))
                        .put("ordinaryResult", match.group(2)).put("specialEventResult", match.group(3));
            }
            var refs = event.putArray("sourceRefs");
            for (var ref : original.path("sourceRefs"))
                if (ref.isTextual() && ref.asText().matches("[0-9a-f]{64}#(?:/[A-Za-z0-9_~/-]*)?")) refs.add(ref);
            var phenotypes = event.putArray("phenotypeCategories");
            var seen = new java.util.LinkedHashSet<String>();
            for (var q : qualified.path("qualifications")) if (q.path("eventId").equals(original.path("eventId"))) {
                String value = q.path("phenotype").asText();
                if (Set.of("HIGHLY_SPECIFIC", "RASOPATHY_COMPATIBLE", "CONSISTENT", "CONSISTENT_HIGH_HETEROGENEITY", "NOT_CONSISTENT", "UNKNOWN").contains(value)) seen.add(value);
            }
            seen.forEach(phenotypes::add);
        }
        if (result.isObject()) for (String key : List.of("highHeterogeneityRawQuarterPoints", "highHeterogeneityEffectiveQuarterPoints"))
            if (result.has(key)) summary.set(key, result.path(key));
        var reasons = summary.putArray("qualificationIssues");
        var unique = new java.util.LinkedHashSet<String>();
        for (var q : qualified.path("qualifications")) for (var code : q.path("reasons"))
            if (code.isTextual()) { String safe = safeIssue(code.asText()); if (safe != null) unique.add(safe); }
        unique.forEach(reasons::add);
    }

    /** Stable additive public contract. Older saved summaries need no new adoption proof to remain readable. */
    static void appendAdoption(ObjectNode summary, JsonNode evaluation, JsonNode batch) {
        var source = evaluation.path("adoptionProof");
        var proof = summary.putObject("adoptionProof");
        if (!DeNovoAdoptionProof.SCHEMA.equals(source.path("schemaVersion").asText())) {
            proof.put("schemaVersion", DeNovoAdoptionProof.SCHEMA).put("status", "NOT_RUN")
                    .put("projectionStatus", "NOT_AVAILABLE");
        } else {
            copy(proof, source, "schemaVersion", "status", "projectionStatus", "basis", "calculationMethod", "selectedCriterion",
                    "appliedStrength", "reasonCode", "totalEventCount", "limitations");
            var policy = source.path("policy");
            if (policy.isObject()) {
                var target = proof.putObject("policy");
                copy(target, policy, "eventMode", "phenotypePolicySha256", "materialClass", "policyId", "policyVersion", "clauseIdentityMethod",
                        "maximumPm6Strength");
                proofSources(target, "sources", policy.path("sources"));
            }
            var events = proof.putArray("events");
            for (var original : source.path("events")) {
                if (events.size() >= 100) break;
                var event = events.addObject();
                copy(event, original, "ordinal", "disposition", "sourceKind", "reasonCode", "status", "conditionBranch", "adoptedStrength", "phenotypeCategory");
                for (String name : List.of("c1", "parentalAvailability", "combined")) if (original.path(name).isObject()) {
                    var condition = event.putObject(name); var raw = original.path(name);
                    copy(condition, raw, "status", "probandTarget", "motherTarget", "fatherTarget", "maternity", "paternity", "confirmedParentage",
                            "maternityMethod", "paternityMethod", "method", "targetCoverage", "detectionCapability", "quality", "value",
                            "clauseMode", "allowAssumedParentage", "phenotypeCategory", "rawQuarterPoints", "sourceMappingStatus", "familyHistory");
                    proofSources(condition, "sources", raw.path("sources"));
                }
                if (original.path("c2").isObject()) {
                    var c2 = event.putObject("c2"); var raw = original.path("c2");
                    copy(c2, raw, "status", "comparisonMethod", "samePerson", "distinctTissues", "detectionAdequate", "limitation");
                    var observations = c2.putArray("observations");
                    for (var measurement : raw.path("observations")) {
                        if (observations.size() >= 20) break;
                        var view = observations.addObject();
                        copy(view, measurement, "observationOrdinal", "role", "personRole", "altReads", "totalReads", "limit", "method", "quality");
                        proofSources(view, "sources", measurement.path("sources"));
                    }
                }
            }
        }
        for (String key : List.of("specificationSelections", "specializedPolicyAdoptions", "calculationBindings")) {
            var array = summary.putArray(key);
            for (var original : batch.path(key)) {
                if (array.size() >= 2) break;
                var target = array.addObject();
                for (String name : List.of("schemaVersion", "selectionId", "criterionId", "catalogId", "catalogVersion", "catalogSha256",
                        "catalogEntryId", "disposition", "outcome", "reasonCode", "status", "globalSelectionId", "policyId", "policyVersion", "policySha256",
                        "phenotypePolicySha256", "eventMode", "declaredCalculatorVersion", "runtimeCalculatorVersion", "executedCalculatorVersion",
                        "selectorPolicySha256", "compatibilityMode")) {
                    var value = original.path(name);
                    if (value.isMissingNode()) continue;
                    if (value.isTextual() && value.asText().matches("[A-Za-z0-9_.:@-]{1,240}")) target.set(name, value.deepCopy());
                    else {
                        proof.put("projectionStatus", "INCOMPLETE");
                        var limits = proof.withArray("limitations");
                        boolean recorded = false;
                        for (var code : limits) if ("PUBLIC_SELECTION_IDENTITY_NOT_PROJECTABLE".equals(code.asText())) recorded = true;
                        if (!recorded) limits.add("PUBLIC_SELECTION_IDENTITY_NOT_PROJECTABLE");
                    }
                }
                if (original.path("criterionIds").isArray()) {
                    var ids = target.putArray("criterionIds");
                    for (var id : original.path("criterionIds")) if (Set.of("ACMG_AMP_2015:PS2", "ACMG_AMP_2015:PM6").contains(id.asText())) ids.add(id);
                }
            }
        }
    }

    private static void proofSources(ObjectNode to, String key, JsonNode from) {
        var array = to.putArray(key);
        for (var original : from) {
            if (array.size() >= 128) break;
            var source = array.addObject();
            copy(source, original, "sourceId", "sourceSha256", "role", "kind", "locator", "mapping", "fieldPath");
        }
    }

    @Nullable private static String safeIssue(String code) {
        if (code.matches("[A-Z][A-Z0-9_]{0,160}")) return code;
        if (code.matches("DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT:(subject|mother|father):ordinary=(PRESENT|ABSENT):special=(PRESENT|ABSENT)")
                || code.matches("(?:DENOVO_TARGET_TEST_CONFLICT|DENOVO_TARGET_TEST_QUALITY_FAILED|DENOVO_PARENTAL_AVAILABILITY_SOURCE_CONFLICT):(subject|mother|father)")
                || code.matches("(?:DENOVO_PARENTAGE_EXCLUDED|DENOVO_SPECIAL_PARENTAGE_SOURCE_UNRESOLVED):(maternity|paternity)")
                || code.matches("DENOVO_SPECIAL_PARENT_IDENTITY_CONFLICT:(MOTHER|FATHER)")
                || code.matches("DENOVO_ORDINARY_SOURCE_FACT_CONFLICT:/groups/(?:(?:proband|mother|father)/targetResult|parentage/(?:maternity|paternity))"))
            return code.substring(0, code.indexOf(':'));
        return null;
    }
}
