package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;

/** Small, optional saved-history display. No file reads, producer calls, qualification or restored handles. */
public final class AcmgClinicalDetailProjection {
    private static final String PP1 = "ACMG_AMP_2015:PP1";
    private AcmgClinicalDetailProjection() { }

    /** Only called on the response copy owned by AcmgEvidenceDetailProjection. */
    static void attach(ObjectMapper mapper, ObjectNode view, JsonNode batch, JsonNode inventory, JsonNode expanded) {
        if (!expanded.path("nativeClinicalDetails").isObject()) return; // Genuine older history stays under its original contract.
        for (var candidate : view.path("candidates")) for (var attempt : candidate.path("attempts"))
            for (var criterion : attempt.at("/localBatch/criteria")) if (PP1.equals(criterion.path("criterionId").asText())
                    && criterion.path("details") instanceof ObjectNode details) {
                try { details.set("clinicalContext", project(mapper, view, candidate, attempt, criterion, batch, inventory, expanded)); }
                catch (IllegalArgumentException invalid) { /* Optional display failure never changes a saved scientific result. */ }
            }
    }

    private static ObjectNode project(ObjectMapper mapper, JsonNode response, JsonNode candidate, JsonNode attempt,
            JsonNode criterion, JsonNode batch, JsonNode inventory, JsonNode expanded) {
        var hash = new RuntimeEvidenceV2Adapter(mapper); var session = inventory.path("sessionIdentity");
        require("current-clinical-archive-export-v1".equals(expanded.path("schemaVersion").asText())
                && expanded.path("historyOnly").asBoolean() && !expanded.path("authorityRestorationSupported").asBoolean(true)
                && "current-adoption-inventory-v1".equals(inventory.path("schemaVersion").asText())
                && session.isObject() && session.equals(expanded.path("sessionIdentity")), "SESSION");
        for (String key : List.of("analysisId", "runId", "requestSha256")) require(nonempty(response.path(key)) && response.path(key).equals(session.path(key)), "SESSION");
        require(response.path("currentClinicalArchiveSha256").equals(expanded.path("sourceArchiveSha256"))
                && pin(expanded.path("sourceArchiveSha256")) && hash.canonicalSha256(inventory).equals(expanded.path("inventorySnapshotSha256").asText())
                && hash.canonicalSha256(session).equals(inventory.path("sessionIdentitySha256").asText()), "INVENTORY");
        self(hash, inventory, "inventorySha256");
        require(response.path("analysisId").equals(batch.path("analysisId"))
                && response.path("executionSourceSha256").equals(session.path("executionSourceSha256"))
                && session.path("executionSourceSha256").equals(batch.path("sourceSnapshotSha256")), "BATCH");
        String candidateId = candidate.path("candidateId").asText(), hypothesis = attempt.path("hypothesisId").asText();
        require(!candidateId.isBlank() && !hypothesis.isBlank() && attempt.path("context").isObject(), "ATTEMPT");
        var savedAttempt = uniqueAttempt(batch.path("results"), candidateId, hypothesis);
        require(savedAttempt.path("context").equals(attempt.path("context")), "CONTEXT");
        var savedCriterion = uniqueCriterion(savedAttempt.at("/localBatch/criteria"));
        String pointer = criterion.at("/details/modulePointer").asText();
        require(pointer.matches("/modules/pp1/results/(0|[1-9][0-9]{0,5})")
                && pointer.equals(savedCriterion.at("/details/modulePointer").asText()), "MODULE");
        var module = batch.at(pointer);
        require(candidateId.equals(module.path("candidateId").asText()) && hypothesis.equals(module.path("hypothesisId").asText())
                && PP1.equals(module.path("criterionId").asText())
                && criterion.path("status").equals(module.at("/evaluationResult/status"))
                && criterion.path("reasonCode").equals(module.at("/evaluationResult/reasonCode"))
                && criterion.path("appliedStrength").equals(module.path("appliedStrength"))
                && savedCriterion.at("/details/originalResult").equals(module)
                && hash.canonicalSha256(module).equals(criterion.at("/details/originalResultSha256").asText()), "MODULE");
        for (String key : List.of("status", "appliedStrength", "reasonCode")) require(criterion.path(key).equals(savedCriterion.path(key)), "RESULT");
        var evaluation = criterion.at("/details/pp1Facts/evaluation"); var ledger = evaluation.path("ledger");
        require(evaluation.isObject() && evaluation.equals(module.at("/publicSummary/evaluation")), "LEDGER");
        var selected = uniqueAttempt(inventory.path("attempts"), candidateId, hypothesis); var identity = selected.at("/binding/identity");
        require("READY".equals(selected.path("bindingStatus").asText()) && identity.path("preparedContext").equals(attempt.path("context"))
                && candidateId.equals(identity.path("candidateId").asText()) && hypothesis.equals(identity.path("hypothesisId").asText())
                && session.path("executionSourceSha256").equals(identity.path("sourceSnapshotSha256")), "CONTEXT");
        String bindingSha = hash.canonicalSha256(identity);
        require(bindingSha.equals(selected.path("bindingIdentitySha256").asText())
                && bindingSha.equals(selected.at("/binding/selection/selectionKey").asText()) && bindingSha.equals(ledger.path("selectionKey").asText()), "BINDING");
        var audit = module.path("contributionAudit"); self(hash, audit, "snapshotSha256");
        require(audit.path("currentInventory").equals(inventory) && audit.path("currentSessionIdentity").equals(session)
                && hash.canonicalSha256(inventory).equals(audit.path("currentInventorySha256").asText()), "AUDIT");
        boolean ledgerBound = ledger.has("contributionSnapshotSha256") || ledger.has("contributionSourceIdentitySha256");
        if (ledgerBound) require(audit.path("snapshotSha256").equals(ledger.path("contributionSnapshotSha256"))
                && audit.path("sourceIdentitySha256").equals(ledger.path("contributionSourceIdentitySha256")), "AUDIT");
        var auditEntry = uniqueAttempt(audit.path("entries"), candidateId, hypothesis);
        require(bindingSha.equals(auditEntry.path("bindingIdentitySha256").asText()), "AUDIT");
        var receipt = selected.path("terminalReceipt"); self(hash, receipt, "receiptSha256");
        require(session.equals(receipt.path("sessionIdentity")) && selected.path("terminalReceiptSha256").equals(receipt.path("receiptSha256"))
                && receipt.path("registrationSha256").equals(inventory.at("/producerIdentity/registrationSha256"))
                && hash.canonicalSha256(expanded.path("registrationIdentity")).equals(receipt.path("registrationSha256").asText())
                && hash.canonicalSha256(receipt).equals(selected.path("terminalSnapshotSha256").asText())
                && receipt.path("attemptIdentity").path("candidateId").equals(candidate.path("candidateId"))
                && receipt.at("/attemptIdentity/hypothesisId").equals(attempt.path("hypothesisId"))
                && bindingSha.equals(receipt.at("/attemptIdentity/bindingIdentitySha256").asText()), "RECEIPT");
        var auditAttempt = auditEntry.path("nativeAttemptAudit"); int index = auditAttempt.path("attemptIndex").asInt(-1);
        require(index >= 0 && index < inventory.path("attempts").size() && selected.equals(inventory.path("attempts").get(index))
                && auditAttempt.path("currentInventorySha256").equals(audit.path("currentInventorySha256"))
                && auditAttempt.path("terminalReceiptSha256").equals(receipt.path("receiptSha256")), "AUDIT");
        var coverage = auditEntry.path("coverage");
        boolean reviewOnly = !ledgerBound;
        if (reviewOnly) reviewOnlySource(hash, ledger, audit, auditEntry, receipt, expanded);
        if (pin(coverage.path("provenanceSha256"))) {
            var provenance = mapper.createObjectNode().put("currentSessionIdentitySha256", hash.canonicalSha256(session))
                    .put("currentInventorySha256", hash.canonicalSha256(inventory)).put("bindingIdentitySha256", bindingSha)
                    .put("terminalReceiptSha256", receipt.path("receiptSha256").asText()).put("terminalSnapshotSha256", hash.canonicalSha256(receipt));
            require(hash.canonicalSha256(provenance).equals(coverage.path("provenanceSha256").asText())
                    && coverage.path("provenanceSha256").equals(ledger.path("inventoryProvenanceSha256")), "COVERAGE");
        } else require("UNKNOWN".equals(ledger.path("status").asText()) && ledger.path("pp4Points").isNull(), "COVERAGE");

        var out = mapper.createObjectNode().put("schemaVersion", "saved-clinical-detail-v1").put("projectionRole", "DISPLAY_ONLY")
                .put("historyOnly", true).put("authorityRestorationSupported", false).put("status", "SOURCE_ONLY");
        var binding = out.putObject("binding");
        for (String key : List.of("analysisId", "runId", "requestSha256")) binding.set(key, response.path(key).deepCopy());
        binding.put("candidateId", candidateId).put("hypothesisId", hypothesis).put("preparedContextSha256", hash.canonicalSha256(attempt.path("context")))
                .put("bindingIdentitySha256", bindingSha).put("inventorySha256", inventory.path("inventorySha256").asText())
                .put("inventorySnapshotSha256", hash.canonicalSha256(inventory)).put("archiveSha256", expanded.path("sourceArchiveSha256").asText())
                .put("terminalReceiptSha256", receipt.path("receiptSha256").asText());
        if (reviewOnly) binding.put("mode", "REVIEW_ONLY_SOURCE").put("reviewAuditSha256", audit.path("snapshotSha256").asText())
                .put("reviewSourceIdentitySha256", audit.path("sourceIdentitySha256").asText());
        else binding.put("contributionSnapshotSha256", audit.path("snapshotSha256").asText());
        if (pin(coverage.path("provenanceSha256"))) binding.set("contributionCoverageProvenanceSha256", coverage.path("provenanceSha256").deepCopy());
        var nativeDetails = expanded.path("nativeClinicalDetails"); var nativeSource = nativeDetails.path("source");
        var registeredSource = expanded.at("/registeredInputsIdentity/clinicalSampleSource");
        require("pp4-native-clinical-details-v1".equals(nativeDetails.path("schemaVersion").asText()), "SOURCE");
        for (String key : List.of("sourceId", "rawSha256", "factsSha256", "format")) require(nonempty(nativeSource.path(key))
                && nativeSource.path(key).equals(registeredSource.path(key)), "SOURCE");
        require(pin(nativeSource.path("rawSha256")) && pin(nativeSource.path("factsSha256")), "SOURCE");
        var source = out.putObject("source").put("title", "原始临床记录").put("format", nativeSource.path("format").asText());
        var sourceRef = source.putObject("sourceRef");
        for (String key : List.of("sourceId", "rawSha256", "factsSha256")) sourceRef.set(key, nativeSource.path(key).deepCopy());
        binding.set("sourceRawSha256", nativeSource.path("rawSha256").deepCopy()); binding.set("sourceFactsSha256", nativeSource.path("factsSha256").deepCopy());
        if (reviewOnly) out.putObject("contribution").put("ledgerStatus", "UNKNOWN").put("terminalKind", receipt.path("kind").asText()).putNull("pp4Points");
        else contribution(mapper, out, evaluation, receipt, coverage);
        review(mapper, out, receipt.path("qualification"));
        nativeReview(out, receipt.path("qualification"), expanded);
        if (reviewOnly) { out.put("projectionSha256", hash.canonicalSha256(out)); return out; }
        try {
            var member = linkedMember(hash, response, attempt.path("context"), receipt, nativeDetails, nativeSource, expanded);
            out.put("status", "CURRENT_SAMPLE_LINKED"); binding.set("sourceMemberPointer", member.path("jsonPointer").deepCopy());
            if (receipt.at("/material/sourceIdentityBridge").isObject())
                binding.set("sourceIdentityBridgeSha256", receipt.at("/material/sourceIdentityBridge/identitySha256").deepCopy());
            projectMember(mapper, out, member);
            qualifiedAge(out, expanded, receipt, attempt.path("context"), hash);
        } catch (IllegalArgumentException unbound) {
            out.remove("member"); out.withObject("review").remove("independentAssessmentAge");
            boolean noLink = !receipt.at("/currentUseGrant/originalSampleLink").isObject() || !receipt.at("/currentUseGrant/nativeCallProof").isObject();
            out.put("status", "CLINICAL_MULTIPLE_MEMBERS_OR_ASSOCIATIONS".equals(unbound.getMessage()) ? "CONFLICT" : noLink ? "SOURCE_ONLY" : "BINDING_UNAVAILABLE");
            out.put("reasonCode", unbound.getMessage());
            out.withArray("comparisons").addObject().put("requirement", "核实本次患者、分析样本与原文病例的唯一对应关系")
                    .put("known", "已保存原始临床资料及本次证据贡献清单").put("gap", "尚未建立唯一且一致的病例与样本关联")
                    .put("conclusion", "暂不将原文病例字段附到本次患者").put("suggestion", "核对原文病例、样本关联与本次分析样本");
        }
        out.put("projectionSha256", hash.canonicalSha256(out)); return out;
    }

    private static void reviewOnlySource(RuntimeEvidenceV2Adapter hash, JsonNode ledger, JsonNode audit,
            JsonNode entry, JsonNode receipt, JsonNode expanded) {
        require("UNKNOWN".equals(ledger.path("status").asText()) && ledger.path("pp4Points").isNull()
                && !ledger.has("contributionSnapshotSha256") && !ledger.has("contributionSourceIdentitySha256")
                && !ledger.has("inventoryProvenanceSha256") && ledger.path("eventIds").isArray() && ledger.path("eventIds").isEmpty()
                && ledger.path("exclusiveFactIds").isArray() && ledger.path("exclusiveFactIds").isEmpty()
                && "UNAVAILABLE".equals(entry.at("/coverage/status").asText()) && !entry.path("coverage").has("provenanceSha256")
                && "UNAVAILABLE".equals(entry.at("/nativeAttemptAudit/status").asText())
                && entry.path("contributions").isArray() && entry.path("contributions").isEmpty()
                && "UNAVAILABLE".equals(receipt.path("kind").asText()) && receipt.path("complete").isBoolean() && !receipt.path("complete").asBoolean()
                && receipt.path("finalPoints").isNull() && receipt.path("materialSha256").isNull()
                && !receipt.has("material") && !receipt.has("currentUseGrant") && !receipt.has("coreExecution") && !receipt.has("currentAdoption")
                && receipt.path("reasonCode").equals(entry.at("/coverage/reasonCode"))
                && receipt.path("reasonCode").equals(entry.at("/nativeAttemptAudit/reasonCode"))
                && receipt.path("reasonCode").equals(ledger.path("reasonCode"))
                && hash.canonicalSha256(receipt.path("sessionIdentity")).equals(audit.path("currentSessionIdentitySha256").asText())
                && pin(audit.path("sourceIdentitySha256")), "REVIEW_ONLY_SOURCE");
        var originals = expanded.at("/registrationIdentity/registeredInputs/originals");
        require(originals.isArray() && !originals.isEmpty() && originals.size() <= 16 && receipt.path("sourcePins").size() == originals.size(), "REVIEW_ONLY_ORIGINALS");
        var roles = new java.util.HashSet<String>();
        for (var expected : originals) {
            require(nonempty(expected.path("role")) && roles.add(expected.path("role").asText()), "REVIEW_ONLY_ORIGINALS");
            int pins = 0, bindings = 0;
            for (var source : receipt.path("sourcePins")) if (source.path("role").equals(expected.path("role"))
                    && source.path("sourceId").equals(expected.path("sourceId")) && source.path("rawSha256").equals(expected.path("rawSha256"))) pins++;
            for (var source : expanded.path("rawBindings")) if ("PRIMARY".equals(source.path("kind").asText())
                    && source.path("role").equals(expected.path("role")) && source.path("sourceId").equals(expected.path("sourceId"))
                    && source.path("rawSha256").equals(expected.path("rawSha256")) && source.path("artifact").path("rawSha256").equals(expected.path("rawSha256"))) bindings++;
            require(pins == 1 && bindings == 1, "REVIEW_ONLY_ORIGINALS");
        }
    }

    private static JsonNode linkedMember(RuntimeEvidenceV2Adapter hash, JsonNode response, JsonNode context, JsonNode receipt, JsonNode details, JsonNode source, JsonNode expanded) {
        var grant = receipt.path("currentUseGrant"); self(hash, grant, "grantSha256");
        require(grant.path("sessionIdentity").equals(receipt.path("sessionIdentity"))
                && "READY".equals(grant.at("/attemptIdentity/bindingStatus").asText()), "NATIVE_GRANT");
        for (String key : List.of("candidateId", "hypothesisId", "bindingIdentitySha256"))
            require(grant.path("attemptIdentity").path(key).equals(receipt.path("attemptIdentity").path(key)), "NATIVE_GRANT");
        var link = grant.path("originalSampleLink"); var call = grant.path("nativeCallProof"); self(hash, call, "proofSha256");
        sourceIdentity(hash, context, receipt, link, call, expanded);
        require("current-native-clinical-call-proof-v1".equals(call.path("schemaVersion").asText())
                && response.path("analysisId").equals(call.path("analysisId")) && nonempty(link.path("personId"))
                && link.path("vcfSampleId").equals(call.path("vcfSampleId")) && nonempty(link.path("clinicalSampleId"))
                && pin(call.path("analysisVcfUriSha256")) && call.path("analysisVcfUriSha256").equals(link.path("nativeAnalysisVcfUriSha256")), "NATIVE_SAMPLE");
        for (String key : List.of("sourceId", "rawSha256", "factsSha256")) require(link.path("sourceRef").path(key).equals(source.path(key)), "NATIVE_SOURCE");
        if (link.path("vcfRawSha256").isTextual()) require(link.path("vcfRawSha256").equals(call.path("vcfRawSha256")), "NATIVE_SAMPLE");
        if (link.path("sampleOrdinal").isIntegralNumber()) require(link.path("sampleOrdinal").equals(call.path("sampleOrdinal")), "NATIVE_SAMPLE");
        var allele = context.at("/variant/evidenceQueryAllele"); var record = call.path("matchedRecord"); int alt = record.path("selectedAltIndex").asInt(-1);
        require(allele.path("assembly").equals(call.path("assembly")) && allele.path("position").equals(record.path("position"))
                && Objects.equals(CoordinateUtils.normalizeChromosome(allele.path("contig").asText()), CoordinateUtils.normalizeChromosome(record.path("contig").asText()))
                && allele.path("reference").equals(record.path("reference")) && record.path("alternates").isArray()
                && alt > 0 && alt <= record.path("alternates").size() && allele.path("alternate").equals(record.path("alternates").get(alt - 1))
                && call.path("targetAlleleIndex").equals(record.path("selectedAltIndex")), "NATIVE_ALLELE");
        JsonNode member = null, association = null; int members = 0, associations = 0;
        for (var item : details.path("members")) if (item.path("subjectId").equals(link.path("personId"))) { member = item; members++; }
        for (var item : details.path("sampleAssociations")) if (item.path("subjectId").equals(link.path("personId"))
                && item.path("vcfSampleId").equals(link.path("vcfSampleId")) && item.path("jsonPointer").equals(link.at("/sourceRef/jsonPointer"))) { association = item; associations++; }
        require(members <= 1 && associations <= 1, "MULTIPLE_MEMBERS_OR_ASSOCIATIONS");
        require(member != null && association != null && members == 1 && associations == 1
                && association.path("biosampleId").equals(link.path("clinicalSampleId")) && association.path("genomeAssembly").equals(allele.path("assembly"))
                && association.path("jsonPointer").asText().startsWith(member.path("jsonPointer").asText() + "/"), "SOURCE_SAMPLE_ASSOCIATION");
        var linked = Objects.requireNonNull(member); var selectedAssociation = Objects.requireNonNull(association);
        int biosamples = 0;
        for (var sample : linked.path("biosamples")) if (sample.path("id").equals(selectedAssociation.path("biosampleId"))
                && sample.path("subjectId").equals(link.path("personId"))) biosamples++;
        require(biosamples == 1, "SOURCE_SAMPLE_ASSOCIATION");
        // recordedFileUriSha256 hashes the literal source URI; the grant hashes its normalized file identity. Do not compare or reopen them here.
        return linked;
    }

    private static void sourceIdentity(RuntimeEvidenceV2Adapter hash, JsonNode context, JsonNode receipt,
            JsonNode link, JsonNode call, JsonNode expanded) {
        var bridge = receipt.at("/material/sourceIdentityBridge"); var granted = receipt.at("/currentUseGrant/nativeClinicalIdentity");
        if (bridge.isMissingNode() && granted.isMissingNode()) {
            require(link.path("personId").equals(context.at("/patientContext/patientContextId"))
                    && link.path("familyId").equals(context.at("/patientContext/familyContextId")), "NATIVE_SAMPLE");
            return;
        }
        self(hash, bridge, "identitySha256");
        var original = qualifiedOriginal(expanded, receipt, context, hash); var facts = original.path("nativeFacts");
        require(bridge.equals(granted) && bridge.equals(facts.path("sourceIdentityBridge"))
                && "current-native-clinical-identity-v1".equals(bridge.path("schemaVersion").asText())
                && "SELECTED_PROBAND_IDENTITY_ONLY_NO_CLINICAL_QUALIFICATION_OR_PARENTAGE".equals(bridge.path("scope").asText())
                && bridge.path("clinicalQualificationGranted").isBoolean() && !bridge.path("clinicalQualificationGranted").asBoolean()
                && bridge.path("familyRelationshipProven").isBoolean() && !bridge.path("familyRelationshipProven").asBoolean()
                && bridge.path("registrationSha256").equals(receipt.path("registrationSha256"))
                && hash.canonicalSha256(expanded.path("registeredInputsIdentity")).equals(bridge.path("registeredInputsSha256").asText())
                && hash.canonicalSha256(receipt.path("sessionIdentity")).equals(bridge.path("currentSessionIdentitySha256").asText())
                && bridge.path("currentPatientContextId").equals(context.at("/patientContext/patientContextId"))
                && bridge.path("currentFamilyContextId").equals(context.at("/patientContext/familyContextId"))
                && bridge.path("sourceSampleLink").equals(link) && bridge.path("nativeCallProof").equals(call)
                && hash.canonicalSha256(link).equals(bridge.path("sourceSampleLinkSha256").asText())
                && hash.canonicalSha256(call).equals(bridge.path("nativeCallProofSha256").asText()), "NATIVE_IDENTITY_BRIDGE");
        for (String key : List.of("candidateId", "hypothesisId", "bindingIdentitySha256", "bindingStatus"))
            require(bridge.path("attemptIdentity").path(key).equals(receipt.at("/currentUseGrant/attemptIdentity").path(key)), "NATIVE_IDENTITY_BRIDGE");
        var sourceCase = facts.path("case");
        for (String[] names : List.of(new String[]{"sourceCaseId", "case_id", "caseId"}, new String[]{"sourcePersonId", "person_id", "personId"},
                new String[]{"sourceFamilyId", "family_id", "familyId"}, new String[]{"sourceSampleId", "sample_id", "clinicalSampleId"}))
            require(nonempty(bridge.path(names[0])) && bridge.path(names[0]).equals(sourceCase.path(names[1]))
                    && bridge.path(names[0]).equals(link.path(names[2])), "NATIVE_IDENTITY_BRIDGE");
    }

    private static void projectMember(ObjectMapper mapper, ObjectNode out, JsonNode source) {
        var member = out.putObject("member").put("alias", "本次对应病例"); var features = member.putArray("features");
        for (var feature : source.path("phenotypicFeatures")) if (features.size() < 40 && feature.path("featureId").asText().matches("HP:[0-9]{7}")
                && Set.of("PRESENT", "ABSENT").contains(feature.path("presence").asText())) {
            var row = features.addObject().put("id", feature.path("featureId").asText()).put("presence", feature.path("presence").asText())
                    .put("sourcePointer", feature.path("jsonPointer").asText()).put("assessmentAgeStatus", "UNKNOWN");
            if (bounded(feature.path("label"), 300)) row.set("label", feature.path("label").deepCopy());
            if (feature.path("onsetSource").isObject()) row.set("onset", time(mapper, feature.at("/onsetSource/recordedValue"), "FEATURE_ONSET"));
        }
        member.put("totalRecordedFeatures", source.path("phenotypicFeatures").size()).put("truncated", source.path("phenotypicFeatures").size() > 40);
        if (source.path("lastEncounterAgeSource").isObject()) member.set("lastEncounter", time(mapper, source.at("/lastEncounterAgeSource/recordedTimeElement"), "LAST_ENCOUNTER"));
    }
    static ObjectNode time(ObjectMapper mapper, JsonNode element, String role) {
        var out = mapper.createObjectNode().put("semanticRole", role).put("status", "UNKNOWN");
        var duration = element.at("/age/iso8601duration");
        if (duration.isTextual() && duration.asText().length() <= 80 && duration.asText().matches("P(?=.+)(?:[0-9]+Y)?(?:[0-9]+M)?(?:[0-9]+W)?(?:[0-9]+D)?(?:T(?=.+)(?:[0-9]+H)?(?:[0-9]+M)?(?:[0-9]+(?:\\.[0-9]+)?S)?)?"))
            out.put("status", "RECORDED").put("kind", "AGE").put("iso8601duration", duration.asText());
        return out;
    }
    private static void qualifiedAge(ObjectNode out, JsonNode expanded, JsonNode receipt, JsonNode context, RuntimeEvidenceV2Adapter hash) {
        if (!"NATIVE_FACTS_REPLAYED_AND_SCOPE_CHECKED".equals(receipt.at("/qualification/status").asText())) return;
        var qualifiedOriginal = qualifiedOriginal(expanded, receipt, context, hash); var value = qualifiedOriginal.at("/nativeFacts/case/age_years");
        if (decimal(value)) {
            var age = out.withObject("review").putObject("independentAssessmentAge").put("value", value.asText()).put("unit", "YEAR")
                    .put("semanticRole", "QUALIFIED_CLINICAL_ASSESSMENT").put("sourcePointer", "/nativeFacts/case/age_years");
            if (bounded(qualifiedOriginal.at("/nativeFacts/case/observed_at"), 80)) age.set("observedAt", qualifiedOriginal.at("/nativeFacts/case/observed_at").deepCopy());
        }
    }
    private static JsonNode qualifiedOriginal(JsonNode expanded, JsonNode receipt, JsonNode context, RuntimeEvidenceV2Adapter hash) {
        require("NATIVE_FACTS_REPLAYED_AND_SCOPE_CHECKED".equals(receipt.at("/qualification/status").asText()), "QUALIFIED_MATERIAL");
        JsonNode original = null; int found = 0;
        for (var item : expanded.path("materialReferences")) if (item.path("sha256").equals(receipt.path("materialOriginalsSha256"))) { original = item.path("originals"); found++; }
        require(found == 1 && original != null && hash.canonicalSha256(original).equals(receipt.path("materialOriginalsSha256").asText())
                && original.at("/nativeFacts/context").equals(context)
                && hash.canonicalSha256(original.path("nativeFacts")).equals(receipt.at("/qualification/nativeFactsSha256").asText()), "QUALIFIED_MATERIAL");
        return Objects.requireNonNull(original);
    }
    static void contribution(ObjectMapper mapper, ObjectNode out, JsonNode evaluation, JsonNode receipt, JsonNode coverage) {
        var ledger = evaluation.path("ledger"); var target = out.putObject("contribution").put("ledgerStatus", ledger.path("status").asText())
                .put("terminalKind", receipt.path("kind").asText()); target.putNull("pp4Points");
        if (receipt.path("complete").asBoolean() && "COMPLETE".equals(coverage.path("status").asText())
                && Set.of("KNOWN", "KNOWN_EMPTY").contains(ledger.path("status").asText()) && decimal(ledger.path("pp4Points"))
                && receipt.path("finalPoints").equals(ledger.path("pp4Points")) && receipt.at("/currentAdoption/awardedPoints").equals(ledger.path("pp4Points"))
                && receipt.at("/currentAdoption/complete").asBoolean() && receipt.path("kind").equals(receipt.at("/currentAdoption/kind")))
            target.set("pp4Points", ledger.path("pp4Points").deepCopy());
        if (decimal(evaluation.path("remainingLocusCapacity"))) target.set("remainingLocusCapacity", evaluation.path("remainingLocusCapacity").deepCopy());
    }
    static void review(ObjectMapper mapper, ObjectNode out, JsonNode qualification) {
        var review = out.putObject("review").put("status", "NATIVE_FACTS_REPLAYED_AND_SCOPE_CHECKED".equals(qualification.path("status").asText()) ? "SOURCE_CONDITIONS_CHECKED" : "NOT_ESTABLISHED");
        var requirements = review.putArray("requirements");
        for (var required : qualification.path("requiredMaterial")) if (requirements.size() < 12 && bounded(required, 500)) requirements.add(required.asText());
        var missing = review.putArray("missing");
        var gaps = mapper.createArrayNode();
        for (var gap : qualification.path("qualificationGaps")) if (gaps.size() < 12 && gap.isObject()) {
            var row = gaps.addObject();
            for (String key : List.of("kind", "code", "required", "available", "gap")) if (bounded(gap.path(key), 600)) row.set(key, gap.path(key).deepCopy());
            if (!Set.of("APPLICABLE_CONDITION_NOT_SATISFIED", "CIRCULAR_SOURCE_BASIS_NOT_ADMITTED").contains(row.path("kind").asText())) missing.add(row.deepCopy());
        }
        if (!gaps.isEmpty()) {
            var comparison = out.putArray("comparisons");
            for (var gap : gaps) comparison.addObject().put("requirement", gap.path("required").asText("核实相应临床判定资料"))
                    .put("known", gap.path("available").asText("已保存原始资料"))
                    .put("gap", gap.path("gap").asText("相应条件尚未核实"))
                    .put("conclusion", switch (gap.path("kind").asText()) {
                        case "APPLICABLE_CONDITION_NOT_SATISFIED" -> "已有资料不满足本次适用条件";
                        case "CIRCULAR_SOURCE_BASIS_NOT_ADMITTED" -> "当前依据不能独立用于这一条件";
                        default -> "尚不能据此确认相应临床判定条件";
                    }).put("suggestion", "补充或核对所列原始资料");
        }
    }
    static void nativeReview(ObjectNode out, JsonNode qualification, JsonNode expanded) {
        var nativeReview = qualification.path("nativeClinicalSourceReview");
        if (nativeReview.isMissingNode()) return;
        var facts = expanded.path("nativeClinicalReviewFacts");
        require("pp4-native-clinical-source-review-v1".equals(nativeReview.path("schemaVersion").asText())
                && "SOURCE_REQUIREMENTS_ONLY_CURRENT_NATIVE_GRANT_IS_SEPARATE".equals(nativeReview.path("scope").asText())
                && "pp4-native-clinical-facts-v1".equals(facts.path("schemaVersion").asText())
                && nativeReview.path("sourceRawSha256").equals(expanded.at("/nativeClinicalDetails/source/rawSha256"))
                && nativeReview.path("sourceRawSha256").equals(facts.path("sourceRawSha256"))
                && nativeReview.path("nativeFactsSha256").equals(facts.path("factsSha256")), "NATIVE_REVIEW_SOURCE");
        var conditions = out.withObject("review").putArray("conditions"); var seen = new java.util.HashSet<String>();
        for (var condition : nativeReview.path("obligations")) {
            String name = condition.path("condition").asText(), status = condition.path("status").asText();
            require(Set.of("APPLICABLE_INDEPENDENT_PHENOTYPE_DEFINITION", "COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE",
                    "REQUIRED_DIFFERENTIAL_AND_LOCUS_REVIEW", "COMPARABLE_INDEPENDENT_TESTED_COHORT", "NONCIRCULAR_ORIGINAL_DIAGNOSIS_AND_SUPPORT").contains(name)
                    && seen.add(name) && Set.of("VERIFIED_BY_EXISTING_SOURCE_QUALIFIER", "NOT_REVIEWED", "NOT_ESTABLISHED", "NOT_SATISFIED", "NOT_ADMITTED_CIRCULAR_BASIS").contains(status), "NATIVE_REVIEW_CONDITION");
            var row = conditions.addObject().put("condition", name).put("status", status);
            if ("VERIFIED_BY_EXISTING_SOURCE_QUALIFIER".equals(status))
                require("NATIVE_FACTS_REPLAYED_AND_SCOPE_CHECKED".equals(qualification.path("status").asText())
                        && condition.path("nativeFactsSha256").equals(qualification.path("nativeFactsSha256"))
                        && nativeReview.path("qualifiedMaterialSha256").equals(qualification.path("materialSha256")), "NATIVE_REVIEW_QUALIFICATION");
            if (bounded(condition.path("reasonCode"), 160)) row.set("reasonCode", condition.path("reasonCode").deepCopy());
            if ("NOT_SATISFIED".equals(status) && "COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE".equals(name))
                ageDifference(out, qualification);
        }
    }
    private static void ageDifference(ObjectNode out, JsonNode qualification) {
        var originals = qualification.at("/availableSourceInputs/literalOriginalFields");
        var cases = originals.path("CLINICAL:case"); var definitions = originals.path("DEFINITION:definition");
        if (!"PP4_NATIVE_AGE_SCOPE_MISMATCH".equals(qualification.path("reasonCode").asText())
                || !cases.isArray() || cases.size() != 1 || !definitions.isArray() || definitions.size() != 1) return;
        // This saved native method requires exactly one case and one definition.
        // Do not choose among multiple rows or invent a case-to-definition ID field.
        var actual = cases.path(0); var definition = definitions.path(0);
        for (String field : List.of("hgnc_id", "mondo_id", "moi"))
            if (!nonempty(actual.path(field)) || !actual.path(field).equals(definition.path(field))) return;
        if (!decimal(actual.path("age_years"))
                || !decimal(definition.path("minimum_age_years")) || !decimal(definition.path("maximum_age_years"))) return;
        var difference = out.withArray("comparisons").addObject().put("condition", "COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE")
                .put("requirement", "适用评估年龄：" + definition.path("minimum_age_years").asText() + "–" + definition.path("maximum_age_years").asText() + " 岁")
                .put("known", "原始临床评估年龄：" + actual.path("age_years").asText() + " 岁")
                .put("gap", "已记录的评估年龄不满足适用范围").put("conclusion", "本次不能采用这一临床条件")
                .put("suggestion", "核对适用年龄及独立临床评估记录");
        if (bounded(actual.path("observed_at"), 80)) difference.set("observedAt", actual.path("observed_at").deepCopy());
    }
    private static JsonNode uniqueAttempt(JsonNode rows, String candidate, String hypothesis) {
        JsonNode found = null; int count = 0;
        for (var row : rows) if (candidate.equals(row.path("candidateId").asText()) && hypothesis.equals(row.path("hypothesisId").asText())) { found = row; count++; }
        require(count == 1 && found != null, "UNIQUE_ATTEMPT"); return Objects.requireNonNull(found);
    }
    private static JsonNode uniqueCriterion(JsonNode rows) {
        JsonNode found = null; int count = 0;
        for (var row : rows) if (PP1.equals(row.path("criterionId").asText())) { found = row; count++; }
        require(count == 1 && found != null, "UNIQUE_CRITERION"); return Objects.requireNonNull(found);
    }
    private static void self(RuntimeEvidenceV2Adapter hash, JsonNode value, String key) {
        require(value instanceof ObjectNode && pin(value.path(key)), "SAVED_PIN");
        var copy = ((ObjectNode) value).deepCopy(); copy.remove(key); require(hash.canonicalSha256(copy).equals(value.path(key).asText()), "SAVED_PIN");
    }
    private static boolean pin(JsonNode node) { return node.isTextual() && node.asText().matches("[a-f0-9]{64}"); }
    private static boolean nonempty(JsonNode node) { return bounded(node, 512); }
    private static boolean bounded(JsonNode node, int length) { return node.isTextual() && !node.asText().isBlank() && node.asText().length() <= length; }
    private static boolean decimal(JsonNode node) { return node.isTextual() && node.asText().length() <= 100 && node.asText().matches("[0-9]+(?:\\.[0-9]+)?") && new BigDecimal(node.asText()).signum() >= 0; }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException("CLINICAL_" + reason); }
}
