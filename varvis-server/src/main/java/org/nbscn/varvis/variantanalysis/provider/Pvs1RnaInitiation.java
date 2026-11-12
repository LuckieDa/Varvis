package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** RNA-only source adapter to the existing generic initiation decision table.
 * It never chooses a start by an ATG scan, invents a DNA variant, or reuses a DNA-only expert scope. */
final class Pvs1RnaInitiation {
    static final String VERSION = "1.0.0";
    static final String REVIEW_SOURCE_ADMISSION_VERSION = "1.0.0";
    static final String RESCUE_DECISION_VERSION = "1.0.3";
    static final String ALGORITHM = "CLINGEN_SVI_2018_START_LOSS";
    private static final String SOURCE_SCHEMA = "pvs1-rna-initiation-reviewed-source-v1";
    private static final Set<String> ROLES = Set.of("PRIMARY_EVIDENCE", "TRANSCRIPT_FUNCTION_REVIEW", "RESTART_REVIEW", "UPSTREAM_REVIEW");
    private static final Set<String> REVIEWED_START_STATUSES = Set.of("OBSERVED", "SOURCE_REVIEWED_PUTATIVE", "UNKNOWN", "NOT_ASSESSED");
    private Pvs1RnaInitiation() { }

    static String policyIssue(JsonNode policy) {
        var scope = policy.at("/spliceScope/initiationScope");
        if (scope.isMissingNode()) return null;
        if (!only(scope, Set.of("status", "method", "methodVersion", "reviewId", "basis", "sourceIds", "maximumStrength", "assessedTranscripts", "sourceBindings"))
                || !"REVIEWED_FOR_FULL_RNA_INITIATION".equals(scope.path("status").asText())
                || !ALGORITHM.equals(scope.path("method").asText()) || !VERSION.equals(scope.path("methodVersion").asText())
                || !text(scope.path("reviewId")) || !sourced(scope, policy.path("scientificSources"))
                || !Set.of("MODERATE", "SUPPORTING").contains(scope.path("maximumStrength").asText())) return "PVS1_RNA_INITIATION_POLICY_NOT_REVIEWED";
        var transcripts = scope.path("assessedTranscripts");var txs = new LinkedHashSet<String>();
        if (!transcripts.isArray() || transcripts.isEmpty() || transcripts.size() > 1000) return "PVS1_RNA_INITIATION_TRANSCRIPT_SCOPE_INVALID";
        for (var tx : transcripts) if (!tx.isTextual() || !tx.asText().matches("(?:NM_[0-9]+|ENST[0-9]+)\\.[1-9][0-9]*") || !txs.add(tx.asText()))
            return "PVS1_RNA_INITIATION_TRANSCRIPT_SCOPE_INVALID";
        var bindings = scope.path("sourceBindings");var ids = new LinkedHashSet<String>();
        if (!bindings.isArray() || bindings.isEmpty() || bindings.size() > 16) return "PVS1_RNA_INITIATION_SOURCE_BINDINGS_INVALID";
        for (var b : bindings) {
            if (!only(b, Set.of("sourceId", "role", "sha256", "url", "version", "reviewId", "mediaType")) || !text(b.path("sourceId"))
                    || !ids.add(b.path("sourceId").asText()) || !ROLES.contains(b.path("role").asText())
                    || !b.path("sha256").asText().matches("[0-9a-f]{64}") || !text(b.path("reviewId"))
                    || !text(b.path("version")) || !b.path("url").asText().startsWith("https://")
                    || !("PRIMARY_EVIDENCE".equals(b.path("role").asText()) ? Set.of("text/plain", "text/html", "application/xml", "application/json").contains(b.path("mediaType").asText())
                        : "application/json".equals(b.path("mediaType").asText()))) return "PVS1_RNA_INITIATION_SOURCE_BINDINGS_INVALID";
            boolean bound = false;
            for (var s : policy.path("scientificSources")) if (b.path("sourceId").equals(s.path("sourceId")))
                bound = b.path("sha256").equals(s.path("sha256")) && b.path("url").equals(s.path("url")) && b.path("version").equals(s.path("version"));
            if (!bound) return "PVS1_RNA_INITIATION_SOURCE_BINDINGS_INVALID";
        }
        return null;
    }

    static String evaluate(ObjectNode context, ObjectNode policy, ObjectNode selection, Pvs1SourceQualification.SourceBoundFullRna source, ObjectNode input) {
        var mapper = new ObjectMapper();var hashes = new RuntimeEvidenceV2Adapter(mapper);
        var audit = input.putObject("fullRnaInitiationAssessment").put("schemaVersion", "pvs1-full-rna-initiation-assessment-v1")
                .put("methodVersion", VERSION).put("algorithmId", ALGORITHM).put("status", "NOT_EVALUATED")
                .put("mechanicalEffectSha256", source.facts().mechanicalEffectSha256()).put("sourceOriginalSha256", source.originalSha256())
                .put("startChosenByAtgScan", false).put("nmdInferredFromSelectedStart", false).put("clinicalValidationCompleted", false);
        try {
            require(hashes.canonicalSha256(context).equals(source.result().reference().material().contextSha256()), "CONTEXT_BINDING_MISMATCH");
            hashes.validateCompleteContext(context);
            String parentIssue=TruncatingNmdPolicy.issue(context,policy,selection,source.result().reference().material().model(),hashes);
            if(parentIssue!=null)return issue(audit,parentIssue);
            var boundAssessment=source.original().path("pvs1Assessment");
            String parentRescueReview = boundAssessment.at("/parentReview/functionalRescue").asText();
            require(hashes.canonicalSha256(policy).equals(boundAssessment.path("policySha256").asText())
                    && source.facts().mechanicalEffectSha256().equals(boundAssessment.path("mechanicalEffectSha256").asText()), "EFFECT_REVIEW_BINDING_MISMATCH");
            String assayIssue=Pvs1SourceQualification.fullRnaAssayIssue(source);if(assayIssue!=null)return issue(audit,assayIssue);
            if (source.facts().nativeStartRetained()) return issue(audit, "PVS1_RNA_INITIATION_NATIVE_START_RETAINED");
            var scope = policy.at("/spliceScope/initiationScope");
            if (scope.isMissingNode()) return issue(audit, "PVS1_RNA_INITIATION_POLICY_SCOPE_REQUIRED");
            String policyIssue = policyIssue(policy);if (policyIssue != null) return issue(audit, policyIssue);
            var expected = new LinkedHashSet<String>();
            for (var tx : scope.path("assessedTranscripts")) expected.add(tx.asText());
            String selected = source.result().reference().material().model().path("transcript").asText();
            if (!expected.contains(selected)) {
                var transcriptScope = audit.putObject("transcriptReviewScope").put("selectedTranscript", selected);
                transcriptScope.set("requiredTranscripts", scope.path("assessedTranscripts").deepCopy());
                return issue(audit, "PVS1_RNA_INITIATION_SELECTED_TRANSCRIPT_OUTSIDE_SCOPE");
            }
            var review = source.original().at("/pvs1Assessment/initiationReview");
            if (!only(review, Set.of("schemaVersion", "reviewId", "basis", "sourceIds", "sourceDocuments"))
                    || !"pvs1-rna-initiation-review-v1".equals(review.path("schemaVersion").asText()) || !text(review.path("reviewId"))
                    || !sourced(review, policy.path("scientificSources"))) return issue(audit, "PVS1_RNA_INITIATION_SOURCE_REVIEW_REQUIRED");
            var loaded = load(mapper, hashes, context, policy, source, review, audit);
            audit.put("sourceDocumentsQualified", true);
            var restartSource = unique(loaded, "RESTART_REVIEW");
            ObjectNode incompleteProductReview = null;
            if (restartSource != null) {
                var restart = restartSource.getValue();
                String finding = restart.path("functionFinding").asText();
                require(Set.of("UNKNOWN", "FUNCTION_PRESERVED_IN_ASSESSED_SCOPE", "FUNCTION_LOSS_SUPPORTED").contains(finding), "FUNCTION_FINDING_INVALID");
                if ("FUNCTION_PRESERVED_IN_ASSESSED_SCOPE".equals(finding)) {
                    String startStatus = restart.path("startStatus").asText();
                    boolean observedStart = "OBSERVED".equals(startStatus);
                    boolean relevantFunctionReviewed = "ASSESSED".equals(restart.path("physiologicalRelevance").asText());
                    boolean sameProductAssayReviewed = "REVIEWED_FUNCTIONAL_EXPERIMENT_FOR_FULL_RNA_PRODUCT".equals(restart.path("functionalQualification").asText());
                    if (!observedStart || !relevantFunctionReviewed || !sameProductAssayReviewed) {
                        incompleteProductReview = mapper.createObjectNode();
                        if (REVIEWED_START_STATUSES.contains(startStatus)) incompleteProductReview.put("availableStartStatus", startStatus);
                        incompleteProductReview.putObject("functionalReviewConditions").put("observedStartReported", observedStart)
                                .put("physiologicalRelevanceReviewed", relevantFunctionReviewed)
                                .put("sameRnaProductExperimentReviewed", sameProductAssayReviewed);
                    } else {
                        var observed = observeStart(source, restart, audit);
                        require(observed != null, "SOURCE_START_UNRESOLVED");
                        markReviewed(audit, restartSource.getKey());
                        requireParentRescueReview(parentRescueReview, true);
                        return nonApplicable(audit, "PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_CONFIRMED");
                    }
                }
            }
            var transcriptSource = unique(loaded, "TRANSCRIPT_FUNCTION_REVIEW");
            if (transcriptSource == null) return incompleteProductReview == null ? issue(audit, "PVS1_RNA_INITIATION_TRANSCRIPT_REVIEW_REQUIRED")
                    : incompleteProductIssue(audit, incompleteProductReview);
            var transcript = transcriptSource.getValue();
            var transcriptAudit = incompleteProductReview == null ? audit : mapper.createObjectNode();
            var transcriptScope=transcriptAudit.putObject("transcriptReviewScope");
            transcriptScope.set("requiredTranscripts",scope.path("assessedTranscripts").deepCopy());
            for(String field:java.util.List.of("complete","truncated","unresolvedCount"))copyAvailable(transcriptScope,field,transcript.path(field));
            require(transcript.path("findings").isArray() && transcript.path("findings").size() <= 1000, "TRANSCRIPT_SET_MISMATCH");
            var seen = new LinkedHashSet<String>();boolean alternative = false, unresolved = false;
            var checks = transcriptAudit.putArray("transcriptFindings");
            for (var finding : transcript.path("findings")) {
                String tx = finding.path("transcript").asText();require(expected.contains(tx) && seen.add(tx), "TRANSCRIPT_SET_MISMATCH");
                if (tx.equals(selected)) continue;
                require(quotes(finding.path("sourceQuotes"), loaded.primary), "FUNCTION_SOURCE_LOCATION_INVALID");
                // Same genomic AUG alone does not resolve another isoform's function or response to the RNA event.
                var function = finding.path("functionallyRelevant");
                var unaffected = finding.path("targetUnaffected");
                boolean excluded = (function.isBoolean() && !function.asBoolean()) || (unaffected.isBoolean() && !unaffected.asBoolean());
                if (!excluded && (!function.isBoolean() || !unaffected.isBoolean())) { unresolved=true;continue; }
                require("REVIEWED_RNA_FUNCTION_AND_TARGET_EVENT_SCOPE".equals(finding.path("qualification").asText()), "FUNCTION_REVIEW_SCOPE_UNRESOLVED");
                checks.add(finding.deepCopy());
                if (!excluded) alternative=true;
            }
            // An incomplete experiment cannot establish rescue or clear a possible rescue for LoF scoring.
            if (incompleteProductReview != null && !alternative) return incompleteProductIssue(audit, incompleteProductReview);
            if (transcriptAudit != audit) audit.setAll(transcriptAudit);
            markReviewed(audit, transcriptSource.getKey());
            if (alternative) {
                requireParentRescueReview(parentRescueReview, true);
                return nonApplicable(audit, "PVS1_RNA_INITIATION_FUNCTIONAL_ALTERNATIVE_CONFIRMED");
            }
            boolean complete = complete(transcript) && seen.equals(expected) && !unresolved;
            audit.put("transcriptSetComplete", complete);
            var seenTranscripts=transcriptScope.putArray("observedTranscripts");seen.forEach(seenTranscripts::add);
            if (!complete) return issue(audit, "PVS1_RNA_INITIATION_TRANSCRIPT_REVIEW_INCOMPLETE");
            if (restartSource == null) return issue(audit, "PVS1_RNA_INITIATION_SOURCE_START_REQUIRED");
            var restartReview = restartSource.getValue();
            String startStatus = restartReview.path("startStatus").asText();
            if (REVIEWED_START_STATUSES.contains(startStatus)) audit.put("availableStartStatus", startStatus);
            var observed = observeStart(source, restartReview, audit);
            markReviewed(audit, restartSource.getKey());
            if (observed == null) return issue(audit, "PVS1_RNA_INITIATION_SOURCE_START_REQUIRED");
            int referenceRestart = baselineScope(source, observed, audit);
            var upstream = new LinkedHashMap<String, JsonNode>();var pointers = new LinkedHashMap<String, String>();
            for (var entry : loaded.documents.entrySet()) if ("UPSTREAM_REVIEW".equals(loaded.roles.get(entry.getKey()))) {
                upstream.put(entry.getKey(), entry.getValue());pointers.put(entry.getKey(), "/pvs1Assessment/initiationReview/sourceDocuments/" + pointer(entry.getKey()));
                for (var record : entry.getValue().path("records")) require(quotes(record.path("sourceQuotes"), loaded.primary), "UPSTREAM_SOURCE_LOCATION_INVALID");
            }
            if (upstream.isEmpty()) return issue(audit, "PVS1_RNA_INITIATION_UPSTREAM_REVIEW_REQUIRED");
            var upstreamScope=audit.putObject("upstreamReviewScope").put("requiredCdsStart",1).put("requiredCdsEnd",referenceRestart-1);
            var upstreamDocuments=upstreamScope.putArray("availableSourceScopes");
            for(var entry:upstream.entrySet()) {
                var observedScope=upstreamDocuments.addObject().put("sourceId",entry.getKey());
                for(String field:java.util.List.of("cdsStart","cdsEnd","complete","truncated","unresolvedCount"))copyAvailable(observedScope,field,entry.getValue().path(field));
                var unresolvedCount=entry.getValue().path("unresolvedCount");
                require(unresolvedCount.isIntegralNumber() && unresolvedCount.canConvertToLong() && unresolvedCount.asLong()>=0,"UPSTREAM_REVIEW_INCOMPLETE");
            }
            var references = Pvs1StartLossReferenceQualifier.reviewedSources(mapper, context, source.result().reference().material().model(), referenceRestart,
                    upstream, pointers, upstream.keySet());
            for (String id : references.assessedSourceIds()) markReviewed(audit, id);
            audit.set("referenceReviews", references.records());audit.put("upstreamScopeComplete", references.complete()).put("qualifyingReferenceCount", references.qualifyingCount());
            if (references.reasonCode() != null) return issue(audit, "PVS1_RNA_INITIATION_" + references.reasonCode());
            requireParentRescueReview(parentRescueReview, false);
            var result = Pvs1StartLossDecision.evaluate(new Pvs1StartLossDecision.Facts("AVAILABLE", "PERMITS", "ESTABLISHED_LOF",
                    "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET", complete, referenceRestart,
                    references.qualifyingCount() > 0 ? "QUALIFYING_FOUND" : references.complete() ? "COMPLETE" : "UNAVAILABLE",
                    references.complete(), references.qualifyingCount() > 0));
            audit.put("decisionOutcome", result.outcome().name()).put("decisionReasonCode", result.reasonCode());
            if (result.outcome() != Pvs1StartLossDecision.Outcome.MET) return issue(audit, result.reasonCode());
            String strength = Pvs1SpliceConsequence.lower(result.strength(), scope.path("maximumStrength").asText());
            audit.put("status", "MET").put("strength", strength).put("putativeRestartDoesNotProveTranslation", !"OBSERVED".equals(restartReview.path("startStatus").asText()));
            input.put("variantMechanism", "SOURCE_BOUND_FULL_RNA_INITIATION_LOSS");return null;
        } catch (Invalid invalid) { return issue(audit, invalid.getMessage()); }
        catch (IOException invalid) { return issue(audit, "PVS1_RNA_INITIATION_SOURCE_JSON_INVALID"); }
        catch (IllegalArgumentException invalid) { return issue(audit, "PVS1_RNA_INITIATION_SOURCE_OR_INPUT_INVALID"); }
    }

    private record Loaded(Map<String, JsonNode> documents, Map<String, String> roles, Map<String, String> primary) { }
    private static Loaded load(ObjectMapper mapper, RuntimeEvidenceV2Adapter hashes, ObjectNode context, ObjectNode policy,
            Pvs1SourceQualification.SourceBoundFullRna source, JsonNode review, ObjectNode audit) throws IOException {
        var documents = new LinkedHashMap<String, JsonNode>();var roles = new LinkedHashMap<String, String>();var primary = new LinkedHashMap<String, String>();
        var originals=review.path("sourceDocuments");var requiredIds=new LinkedHashSet<String>();var observedIds=new LinkedHashSet<String>();
        for(var binding:policy.at("/spliceScope/initiationScope/sourceBindings"))requiredIds.add(binding.path("sourceId").asText());
        originals.fieldNames().forEachRemaining(observedIds::add);
        var setCheck=audit.putObject("sourceDocumentSet");
        var requiredDocuments=setCheck.putArray("requiredSourceIds");requiredIds.forEach(requiredDocuments::add);
        var observedDocuments=setCheck.putArray("availableSourceIds");observedIds.forEach(observedDocuments::add);
        require(originals.isObject() && requiredIds.equals(observedIds),"SOURCE_DOCUMENT_SET_MISMATCH");
        var reviewedIds = new LinkedHashSet<String>();
        for (var id : review.path("sourceIds")) reviewedIds.add(id.asText());
        var outsideReview = requiredIds.stream().filter(id -> !reviewedIds.contains(id)).toList();
        if (!outsideReview.isEmpty()) {
            var missing = setCheck.putArray("outsideReviewSourceIds");
            outsideReview.forEach(missing::add);
            throw new Invalid("SOURCE_DOCUMENT_NOT_IN_REVIEW_SCOPE");
        }
        var checks = audit.putArray("sourceChecks");
        for (var binding : policy.at("/spliceScope/initiationScope/sourceBindings")) {
            String id = binding.path("sourceId").asText(), role = binding.path("role").asText();
            var check=checks.addObject().put("sourceId",id).put("role",role).put("expectedRawSha256",binding.path("sha256").asText())
                    .put("status","ORIGINAL_REQUIRED").put("sourceLocator","/pvs1Assessment/initiationReview/sourceDocuments/"+pointer(id)+"/rawText");
            var original=originals.path(id);
            require(only(original,Set.of("rawText")),"SOURCE_DOCUMENT_FORMAT_INVALID");
            var text = original.path("rawText");
            require(text.isTextual() && !text.asText().isEmpty() && text.asText().length() <= 1_000_000 && StandardCharsets.UTF_8.newEncoder().canEncode(text.asText()), "SOURCE_ORIGINAL_REQUIRED");
            byte[] bytes = text.asText().getBytes(StandardCharsets.UTF_8);String actualSha=CodingTranscriptMaterialSupply.sha(bytes);
            check.put("actualRawSha256",actualSha).put("bytes",bytes.length).put("status","SHA_MISMATCH");
            require(bytes.length <= 1_000_000 && actualSha.equals(binding.path("sha256").asText()), "SOURCE_ORIGINAL_SHA_MISMATCH");
            check.put("rawSha256",actualSha).put("status","RAW_SHA_MATCH");
            roles.put(id, role);
            if ("PRIMARY_EVIDENCE".equals(role)) {primary.put(id, text.asText());continue;}
            var document = Pvs1SourceQualification.parseRnaInitiationScientificJson(bytes);
            require(SOURCE_SCHEMA.equals(document.path("schemaVersion").asText()) && role.equals(document.path("role").asText())
                    && document.path("reviewId").equals(binding.path("reviewId")) && document.path("dataClass").equals(context.path("dataClass"))
                    && document.path("clinicalAcceptance").isBoolean() && !document.path("clinicalAcceptance").asBoolean()
                    && "REVIEWED_FOR_THIS_RNA_PRODUCT".equals(document.at("/review/status").asText()) && text(document.at("/review/basis")), "SOURCE_REVIEW_NOT_QUALIFIED");
            require(hashes.canonicalSha256(context).equals(document.path("contextSha256").asText())
                    && source.facts().mechanicalEffectSha256().equals(document.at("/scope/mechanicalEffectSha256").asText())
                    && source.result().alteredRnaSha256().equals(document.at("/scope/alteredRnaSha256").asText())
                    && source.result().reference().material().modelSha256().equals(document.at("/scope/modelSha256").asText())
                    && source.result().reference().material().model().path("transcript").equals(document.at("/scope/selectedTranscript")), "SOURCE_PRODUCT_SCOPE_MISMATCH");
            documents.put(id, document);
        }
        require(!primary.isEmpty(), "PRIMARY_ORIGINAL_REQUIRED");
        for (var doc : documents.values()) require(quotes(doc.at("/review/sourceQuotes"), primary), "SOURCE_LOCATION_INVALID");
        return new Loaded(documents, roles, primary);
    }
    @org.springframework.lang.Nullable
    private static Map.Entry<String, JsonNode> unique(Loaded loaded, String role) {
        Map.Entry<String, JsonNode> found = null;
        for (var entry : loaded.documents.entrySet()) if (role.equals(loaded.roles.get(entry.getKey()))) {
            require(found == null, "SOURCE_ROLE_AMBIGUOUS");found = entry;
        }
        return found;
    }
    private static void markReviewed(ObjectNode audit, String sourceId) {
        for (var check : audit.path("sourceChecks")) if (check instanceof ObjectNode row && sourceId.equals(row.path("sourceId").asText())) {
            row.put("reviewApplied", true);
            return;
        }
    }
    private record Start(int rna1, String protein) { }
    @org.springframework.lang.Nullable
    private static Start observeStart(Pvs1SourceQualification.SourceBoundFullRna source, JsonNode document, ObjectNode audit) {
        String status = document.path("startStatus").asText();
        if (Set.of("UNKNOWN", "NOT_ASSESSED").contains(status)) return null;
        require(Set.of("OBSERVED", "SOURCE_REVIEWED_PUTATIVE").contains(status), "SOURCE_START_STATUS_INVALID");
        var start = document.path("alteredStartRna1");String rna = source.result().alteredRna();
        require(start.isIntegralNumber() && start.canConvertToInt() && start.asInt() > 0 && (long) start.asInt() + 2 <= rna.length(), "SOURCE_START_POSITION_INVALID");
        int first = start.asInt();require(rna.substring(first - 1, first + 2).equals("ATG"), "SOURCE_START_NOT_ATG");
        var protein = new StringBuilder();
        for (int i = first - 1;i + 2 < rna.length();i += 3) {char aa = TruncatingSequence.translateCodon(rna.substring(i, i + 3));protein.append(aa);if (aa == '*') break;}
        require(protein.toString().endsWith("*") && protein.toString().equals(document.path("proteinThroughFirstStop").asText()), "SOURCE_TRANSLATION_WITNESS_MISMATCH");
        audit.putObject("sourceSpecifiedStart").put("alteredStartRna1", first).put("startStatus", status).put("actualCodon", "ATG")
                .put("proteinThroughFirstStop", protein.toString()).put("interpretation", "OBSERVED".equals(status) ? "SOURCE_REPORTED_START_WITH_SEQUENCE_REPLAY" : "SOURCE_SPECIFIED_PUTATIVE_START_NOT_OBSERVED_TRANSLATION");
        return new Start(first, protein.toString());
    }
    private static int baselineScope(Pvs1SourceQualification.SourceBoundFullRna source, Start start, ObjectNode audit) {
        var result = source.result();var material = result.reference().material();int a = result.alteredToReferenceRnaBase(start.rna1());
        int b = result.alteredToReferenceRnaBase(start.rna1() + 1), c = result.alteredToReferenceRnaBase(start.rna1() + 2);
        require(a >= material.cdsStartRna1() && c <= material.cdsEndRna1() && b == a + 1 && c == b + 1, "REFERENCE_RESTART_MAPPING_UNRESOLVED");
        int cds = a - material.cdsStartRna1() + 1;require(cds > 3 && (cds - 1) % 3 == 0, "REFERENCE_RESTART_NOT_DOWNSTREAM_INFRAME");
        String reference = material.model().path("cdsSequence").asText();
        audit.put("referenceRestartCdsPosition",cds);
        if("SOURCE_REVIEWED_PUTATIVE".equals(audit.path("availableStartStatus").asText())) {
            // Check a source-selected candidate; never select a start or infer translation.
            // A new/reframed earlier ATG changes the bounded upstream interpretation.
            for(int earlier=start.rna1()-3;earlier>=1;earlier-=3) {
                if(!result.alteredRna().substring(earlier-1,earlier+2).equals("ATG"))continue;
                int ra=result.alteredToReferenceRnaBase(earlier), rb=result.alteredToReferenceRnaBase(earlier+1), rc=result.alteredToReferenceRnaBase(earlier+2);
                boolean retainedReferenceSameFrameMet=ra>0 && rb==ra+1 && rc==rb+1 && (ra-a)%3==0;
                // Preserve unchanged reference UTR candidates; this check does not invent a new uORF gate.
                if(!retainedReferenceSameFrameMet) {
                    audit.put("earlierAlteredInframeMetRnaPosition",earlier);
                    var fact=audit.putObject("earlierAlteredInframeMet").put("alteredStartRna1",earlier).put("actualCodon","ATG")
                            .put("observedTranslationEstablished",false).put("startChosenByThisCheck",false);
                    var positions=fact.putArray("referenceRnaPositions1");
                    for(int value:new int[]{ra,rb,rc})if(value>0)positions.add(value);else positions.addNull();
                    throw new Invalid("EARLIER_ALTERED_INFRAME_MET_REQUIRES_SEPARATE_RULE");
                }
            }
        }
        audit.put("referenceInframeMetOrderChecked",true);
        for(int offset=3;offset<cds-1;offset+=3) {
            if(!reference.substring(offset,offset+3).equals("ATG"))continue;
            int earlier=result.referenceRnaToAlteredBase(material.cdsStartRna1()+offset);
            if(earlier>0 && result.referenceRnaToAlteredBase(material.cdsStartRna1()+offset+1)==earlier+1
                    && result.referenceRnaToAlteredBase(material.cdsStartRna1()+offset+2)==earlier+2
                    && result.alteredRna().substring(earlier-1,earlier+2).equals("ATG")) {
                audit.put("earlierReferenceInframeMetCdsPosition",offset+1);
                throw new Invalid("EARLIER_INFRAME_MET_REQUIRES_SEPARATE_RULE");
            }
        }
        require(reference.substring(cds - 1, cds + 2).equals("ATG") && start.protein().equals(ProteinLengthChangeObservation.translate(reference.substring(cds - 1))), "DOWNSTREAM_EFFECT_REQUIRES_SEPARATE_RULE");
        for (int p = a;p <= material.cdsEndRna1();p++) require(result.referenceRnaToAlteredBase(p) == start.rna1() + p - a, "DOWNSTREAM_EFFECT_REQUIRES_SEPARATE_RULE");
        audit.put("referenceRestartCdsPosition", cds).put("baselineScope", "REFERENCE_INFRAME_RESTART_UNCHANGED_DOWNSTREAM_CODING_SEQUENCE");return cds;
    }
    private static boolean quotes(JsonNode values, Map<String, String> originals) {
        if (!values.isArray() || values.isEmpty() || values.size() > 32) return false;
        for (var quote : values) {
            String raw = originals.get(quote.path("sourceId").asText());var first = quote.path("start");var last = quote.path("end");
            if (raw == null || !first.isIntegralNumber() || !first.canConvertToInt() || !last.isIntegralNumber() || !last.canConvertToInt()
                    || first.asInt() < 0 || last.asInt() <= first.asInt() || last.asInt() > raw.length() || last.asInt() - first.asInt() > 4000
                    || !raw.substring(first.asInt(), last.asInt()).equals(quote.path("text").asText())) return false;
        }
        return true;
    }
    private static boolean complete(JsonNode node) { return node.path("complete").isBoolean() && node.path("complete").asBoolean()
            && node.path("truncated").isBoolean() && !node.path("truncated").asBoolean() && node.path("unresolvedCount").isIntegralNumber() && node.path("unresolvedCount").canConvertToLong() && node.path("unresolvedCount").asLong(-1) == 0; }
    private static boolean sourced(JsonNode node, JsonNode sources) {
        if (!text(node.path("basis")) || !node.path("sourceIds").isArray() || node.path("sourceIds").isEmpty()) return false;
        var seen = new LinkedHashSet<String>();
        for (var id : node.path("sourceIds")) {boolean found = false;if (!text(id) || !seen.add(id.asText())) return false;for (var s : sources) if (id.equals(s.path("sourceId"))) found = true;if (!found) return false;}
        return true;
    }
    private static boolean only(JsonNode node, Set<String> fields) { if (!node.isObject()) return false;var names = node.fieldNames();while (names.hasNext()) if (!fields.contains(names.next())) return false;return true; }
    private static boolean text(JsonNode node) { return node.isTextual() && !node.asText().isBlank(); }
    private static final class Invalid extends IllegalArgumentException {private static final long serialVersionUID = 1L;private Invalid(String code) {super("PVS1_RNA_INITIATION_" + code);} }
    private static void require(boolean condition, String code) {if (!condition) throw new Invalid(code);}
    private static void copyAvailable(ObjectNode target,String field,JsonNode value) {if(value.isMissingNode())target.putNull(field);else target.set(field,value.deepCopy());}
    private static String pointer(String value) {return value.replace("~","~0").replace("/","~1");}
    private static void requireParentRescueReview(String disposition, boolean functionPreserved) {
        boolean rescueExcluded = "EXCLUDED_IN_REVIEWED_SCOPE".equals(disposition);
        require(!functionPreserved || !rescueExcluded, "FUNCTIONAL_RESCUE_FACTS_CONFLICT");
        require(rescueExcluded || "ASSESSED_IN_INITIATION_REVIEW".equals(disposition),
                "PARENT_FUNCTIONAL_RESCUE_REVIEW_REQUIRED");
    }

    private static String incompleteProductIssue(ObjectNode audit, ObjectNode conditions) {
        audit.setAll(conditions);
        return issue(audit, "PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_REVIEW_INCOMPLETE");
    }

    private static String issue(ObjectNode audit, String code) {
        audit.put("status", "NOT_EVALUATED").put("reasonCode", code);audit.remove("strength");
        String required=switch(code) {
            case "PVS1_RNA_INITIATION_PARENT_FUNCTIONAL_RESCUE_REVIEW_REQUIRED" -> "The parent PVS1 review must exclude functional rescue in scope or explicitly refer to the initiation review";
            case "PVS1_RNA_INITIATION_SELECTED_TRANSCRIPT_OUTSIDE_SCOPE" -> "The exact selected transcript version must belong to the reviewed RNA initiation policy scope";
            case "PVS1_RNA_INITIATION_TRANSCRIPT_REVIEW_REQUIRED", "PVS1_RNA_INITIATION_TRANSCRIPT_REVIEW_INCOMPLETE" -> "Every exact declared transcript reviewed; complete=true, truncated=false, unresolvedCount=0; no unresolved function/start/event scope";
            case "PVS1_RNA_INITIATION_SOURCE_START_REQUIRED" -> "A source-specified OBSERVED or SOURCE_REVIEWED_PUTATIVE start on this altered RNA, with exact ATG and protein witness";
            case "PVS1_RNA_INITIATION_UPSTREAM_REVIEW_REQUIRED", "PVS1_RNA_INITIATION_UPSTREAM_REVIEW_INCOMPLETE" -> "An independent qualifying pathogenic reference or complete bounded upstream coverage; absence of records alone is insufficient";
            case "PVS1_RNA_INITIATION_SOURCE_DOCUMENT_SET_MISMATCH" -> "Exactly the source documents pinned by the selected policy, without missing or extra documents";
            case "PVS1_RNA_INITIATION_SOURCE_DOCUMENT_NOT_IN_REVIEW_SCOPE" -> "The current product review must explicitly include every required original document";
            case "PVS1_RNA_INITIATION_SOURCE_ORIGINAL_SHA_MISMATCH" -> "The exact original UTF-8 bytes pinned by the selected policy";
            case "PVS1_RNA_INITIATION_EARLIER_ALTERED_INFRAME_MET_REQUIRES_SEPARATE_RULE" -> "A separately source-supported rule for the earlier edit-created or reframed same-frame ATG; do not infer actual translation or use a later putative start to define upstream references";
            case "PVS1_RNA_INITIATION_EARLIER_INFRAME_MET_REQUIRES_SEPARATE_RULE" -> "The next retained reference in-frame Met for this generic baseline; a later start requires a separately supported rule";
            default -> "";
        };
        if(!required.isEmpty())audit.putObject("blockingCondition").put("reasonCode",code).put("required",required)
                .put("availableFactsLocator","/fullRnaInitiationAssessment");
        return code;
    }
    private static String nonApplicable(ObjectNode audit, String code) {audit.put("status", "NOT_APPLICABLE").put("reasonCode", code);return code;}
}
