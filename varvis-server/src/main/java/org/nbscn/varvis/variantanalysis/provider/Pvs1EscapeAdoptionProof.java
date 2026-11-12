package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded witness of an already executed escape decision. No scientific adoption or rescoring. */
final class Pvs1EscapeAdoptionProof {
    static final String SCHEMA = "pvs1-escape-adoption-proof-v1";
    static final String VERSION = "1.0.0";
    private static final int LIMIT = 32;
    private static final Set<String> STRENGTHS = Set.of("VERY_STRONG", "STRONG", "MODERATE", "SUPPORTING");
    private static final List<String> RULE_FIELDS = List.of("ruleId", "predicate", "start", "end", "minimumAffectedResidues", "metric", "comparison", "numerator", "denominator", "strength");
    private Pvs1EscapeAdoptionProof() { }

    /** Separate source eligibility from a structurally replayable decision audit.
     * The absence of a qualification never becomes evidence of source approval.
     */
    static boolean sourceQualifiedResearch(ObjectMapper mapper, JsonNode result) {
        var knowledge = result.path("knowledgeAssessment");
        if (!knowledge.at("/adoption/qualification").isObject() || !"READY".equals(knowledge.path("status").asText())) return false;
        try {
            var policy = knowledge.at("/binding/qualificationPolicy");var adoption = knowledge.path("adoption");
            return Pvs1SourceQualification.policyValid(policy)
                    && "PUBLIC_REFERENCE".equals(knowledge.at("/binding/sourceDataClass").asText())
                    && policy.path("purpose").equals(adoption.path("purpose"))
                    && policy.path("allowedDataClasses").equals(adoption.path("allowedDataClasses"))
                    && Pvs1SourceQualification.issue(adoption, result.path("policy"), new RuntimeEvidenceV2Adapter(mapper)) == null;
        } catch (RuntimeException invalid) { return false; }
    }

    /** Optional coding-only public semantics. It preserves the old facts version
     * and proof schema; incomplete legacy material is explained rather than repaired.
     */
    static ObjectNode codingAssessment(ObjectMapper mapper, JsonNode result, JsonNode proof) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-coding-escape-assessment-v1").put("methodVersion", "1.0.0")
                .put("proofPointer", "#/escapeAdoptionProof").put("scientificApprovalCreated", false).put("rescoringPerformed", false);
        boolean qualified = sourceQualifiedResearch(mapper, result);
        out.put("sourceQualifiedResearch", qualified);
        out.putObject("sourceQualification").put("status", qualified ? "VERIFIED"
                : result.at("/knowledgeAssessment/adoption/qualification").isObject() ? "INVALID" : "NOT_AVAILABLE")
                .put("reasonCode", qualified ? "PVS1_SOURCE_QUALIFIED_CONDITIONS_BOUND"
                        : result.at("/knowledgeAssessment/adoption/qualification").isObject()
                            ? "PVS1_CODING_ESCAPE_SOURCE_QUALIFICATION_INVALID" : "PVS1_CODING_ESCAPE_SOURCE_QUALIFICATION_NOT_AVAILABLE");
        String status = proof.path("status").asText();
        if (!"AVAILABLE".equals(status)) return out.put("status", "NOT_ADOPTED".equals(status) ? status : "UNAVAILABLE")
                .put("reasonCode", proof.path("reasonCode").asText("PVS1_ESCAPE_PUBLIC_PROOF_INPUT_INVALID"));
        try {
            var input = result.path("resolvedInput");var annotation = result.path("storedAnnotation");
            require(!input.path("spliceAssessment").isObject(), "CODING_METHOD_CONTRADICTION");
            require(annotation.isObject() && new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(annotation)
                    .equals(input.path("storedAnnotationSha256").asText()), "CODING_ANNOTATION_BINDING_MISMATCH");
            String transcript = input.path("selectedTranscript").asText(), hgvsc = annotation.path("hgvsCoding").asText();
            require(usableText(annotation.path("hgvsCoding")) && hgvsc.startsWith(transcript + ":c."), "CODING_HGVS_REQUIRED");
            int cds = positive(input, "cdsPosition"), protein = positive(input, "proteinPosition");
            int start = positive(input, "ptcStart"), end = positive(input, "ptcEnd");
            require((long) end - start == 2 && (start - 1) % 3 == 0, "CODING_PTC_SPAN_INVALID");
            var positions = mapper.createObjectNode().put("cdsPosition", cds).put("proteinPosition", protein);
            if (input.has("firstChangedProteinPosition")) positions.put("firstChangedProteinPosition", positive(input, "firstChangedProteinPosition"));
            var span = positions.putObject("ptcSpan").put("start", start).put("end", end);
            int modelLength = positive(result.path("transcriptModelFacts"), "cdsLength");
            if ("FRAMESHIFT".equals(input.path("variantMechanism").asText())) {
                require("ALTERED_CDS".equals(input.path("coordinateSystem").asText()) && input.path("hgvscSequenceEquivalent").isBoolean()
                                && input.path("hgvscSequenceEquivalent").asBoolean(),
                        "CODING_HGVS_EQUIVALENCE_REQUIRED");
                span.put("coordinateSystem", "ALTERED_CDS_1_BASED_INCLUSIVE");
                var normalized = event(mapper, input, "reference", "insertedSequence", modelLength);
                var hgvs = event(mapper, input, "hgvscReference", "hgvscInsertedSequence", modelLength);
                int delta = integer(input, "netLengthChange");
                require(delta % 3 != 0 && delta == normalized.path("insertedBaseCount").asInt() - normalized.path("deletedBaseCount").asInt()
                        && delta == hgvs.path("insertedBaseCount").asInt() - hgvs.path("deletedBaseCount").asInt()
                        && cds == normalized.path("start0").asInt() + 1 && end <= (long) modelLength + delta,
                        "CODING_EVENT_DELTA_MISMATCH");
                verifyHgvsEvent(hgvsc.substring((transcript + ":c.").length()), hgvs, input.path("hgvscInsertedSequence").asText());
                positions.set("referenceEvent", normalized);positions.set("hgvscEvent", hgvs.put("sequenceEquivalent", true));
                var mapping = positions.putObject("referencePtc").put("coordinateSystem", "REFERENCE_CDS_1_BASED");
                var bases = mapping.putArray("positions");int mapped = 0;
                int alteredPosition = start;
                for (String name : List.of("ptcReferenceStart", "ptcReferenceMiddle", "ptcReferenceEnd")) {
                    var value = input.path(name);
                    int expected = referenceBase(alteredPosition++, normalized);
                    if (value.isNull()) { require(expected == 0, "CODING_PTC_MAPPING_INVALID");bases.addNull(); }
                    else { int position = positive(input, name);require(position <= modelLength && position == expected, "CODING_PTC_MAPPING_INVALID");bases.add(position);mapped++; }
                }
                String mappingStatus = mapped == 3 ? "REFERENCE_BASES" : "CONTAINS_INSERTED_BASES";
                require(mappingStatus.equals(input.path("ptcReferenceMapping").asText()), "CODING_PTC_MAPPING_INVALID");
                mapping.put("status", mappingStatus);
                out.put("mechanism", "FRAMESHIFT").put("coordinateSystem", "ALTERED_CDS");
            } else {
                require((!input.has("variantMechanism") || "STOP_GAINED".equals(input.path("variantMechanism").asText()))
                        && "SNV".equals(result.at("/context/variant/variantType").asText()) && start == (cds - 1) / 3 * 3 + 1
                        && protein == (start - 1) / 3 + 1 && end <= modelLength, "CODING_STOP_POSITION_MISMATCH");
                var snv = Pattern.compile("([1-9][0-9]*)([ACGT])>([ACGT])").matcher(hgvsc.substring((transcript + ":c.").length()));
                require(snv.matches() && coordinate(snv.group(1)) == cds, "CODING_HGVS_POSITION_MISMATCH");
                String ref = input.path("refCodon").asText(), alt = input.path("altCodon").asText();int offset = (cds - 1) % 3;
                require(ref.matches("[ACGT]{3}") && alt.matches("[ACGT]{3}") && snv.group(2).charAt(0) == ref.charAt(offset)
                        && snv.group(3).charAt(0) == alt.charAt(offset) && !snv.group(2).equals(snv.group(3))
                        && !Set.of("TAA", "TAG", "TGA").contains(ref) && Set.of("TAA", "TAG", "TGA").contains(alt), "CODING_HGVS_CODON_MISMATCH");
                for (int base = 0; base < 3; base++)
                    require(base == offset || ref.charAt(base) == alt.charAt(base), "CODING_HGVS_CODON_MISMATCH");
                span.put("coordinateSystem", "REFERENCE_CDS_1_BASED_INCLUSIVE");
                // A verified coding SNV identifies its substituted reference base.
                // No absent reference-PTC mapping triple is invented for this path.
                var normal = typedEvent(mapper, cds - 1, cds, 1);
                positions.set("referenceEvent", normal);positions.set("hgvscEvent", normal.deepCopy().put("sequenceEquivalent", true));
                out.put("mechanism", "STOP_GAINED").put("coordinateSystem", "REFERENCE_CDS");
            }
            out.set("positions", positions);out.set("transcriptModelFacts", proof.path("transcriptModelFacts").deepCopy());
            return out.put("status", "AVAILABLE").put("reasonCode", "PVS1_CODING_ESCAPE_PUBLIC_FACTS_VERIFIED");
        } catch (Rejected rejected) { return out.put("status", "UNAVAILABLE").put("reasonCode", rejected.getMessage()); }
        catch (RuntimeException invalid) { return out.put("status", "UNAVAILABLE").put("reasonCode", "PVS1_CODING_ESCAPE_PUBLIC_FACTS_INPUT_INVALID"); }
    }

    private static ObjectNode event(ObjectMapper mapper, JsonNode input, String prefix, String sequenceField, int modelLength) {
        int start = integer(input, prefix + "EditStart0"), end = integer(input, prefix + "EditEnd0");
        var inserted = input.path(sequenceField);
        require(start >= 0 && end >= start && end <= modelLength && inserted.isTextual() && (start != end || !inserted.asText().isEmpty())
                && inserted.asText().matches("[ACGT]*") && inserted.asText().length() <= 1_000_000, "CODING_EVENT_BOUNDARY_INVALID");
        return typedEvent(mapper, start, end, inserted.asText().length());
    }
    private static ObjectNode typedEvent(ObjectMapper mapper, int start, int end, int inserted) {
        return mapper.createObjectNode().put("coordinateSystem", "REFERENCE_CDS_0_BASED_HALF_OPEN").put("start0", start).put("end0", end)
                .put("deletedBaseCount", end - start).put("insertedBaseCount", inserted)
                .put("kind", start == end ? "INSERTION" : inserted == 0 ? "DELETION" : end - start == 1 && inserted == 1 ? "SUBSTITUTION" : "DELINS");
    }
    /** Coordinate projection of this one already executed event; no sequence editing. */
    private static int referenceBase(int alteredPosition, JsonNode event) {
        int offset = alteredPosition - 1, start = event.path("start0").asInt(), inserted = event.path("insertedBaseCount").asInt();
        if (offset < start) return alteredPosition;
        if (offset < (long) start + inserted) return 0;
        return offset - inserted + event.path("deletedBaseCount").asInt() + 1;
    }
    private static void verifyHgvsEvent(String expression, ObjectNode event, String inserted) {
        var parsed = Pattern.compile("([1-9][0-9]*)(?:_([1-9][0-9]*))?(delins|del|dup|ins)([ACGT]*)").matcher(expression);
        require(parsed.matches(), "CODING_HGVS_REQUIRED");
        int first = coordinate(parsed.group(1)), last = parsed.group(2) == null ? first : coordinate(parsed.group(2));
        require(last >= first, "CODING_HGVS_POSITION_MISMATCH");
        String operation = parsed.group(3), bases = parsed.group(4);
        if ("ins".equals(operation)) require((long) last == (long) first + 1 && !bases.isEmpty() && bases.equals(inserted)
                && event.path("start0").asInt() == first && event.path("end0").asInt() == first, "CODING_HGVS_POSITION_MISMATCH");
        else if ("dup".equals(operation)) require(event.path("start0").asInt() == last && event.path("end0").asInt() == last
                && inserted.length() == last - first + 1 && (bases.isEmpty() || bases.equals(inserted)), "CODING_HGVS_POSITION_MISMATCH");
        else require(event.path("start0").asInt() == first - 1 && event.path("end0").asInt() == last
                && ("delins".equals(operation) ? !bases.isEmpty() && bases.equals(inserted) : inserted.isEmpty()
                        && (bases.isEmpty() || bases.length() == last - first + 1)), "CODING_HGVS_POSITION_MISMATCH");
    }
    private static int coordinate(String value) {
        try { long parsed = Long.parseLong(value);require(parsed > 0 && parsed <= Integer.MAX_VALUE, "CODING_HGVS_POSITION_MISMATCH");return (int) parsed; }
        catch (NumberFormatException invalid) { throw new Rejected("PVS1_ESCAPE_PUBLIC_PROOF_CODING_HGVS_POSITION_MISMATCH"); }
    }

    static ObjectNode project(ObjectMapper mapper, JsonNode result) {
        var proof = mapper.createObjectNode().put("schemaVersion", SCHEMA).put("methodVersion", VERSION)
                .put("scientificApprovalCreated", false).put("rescoringPerformed", false);
        var evaluation = result.path("evaluation");var input = result.path("resolvedInput");
        if (!"MET".equals(evaluation.at("/evaluationResult/status").asText())
                || !"NMD_NOT_PREDICTED".equals(input.path("nmdPrediction").asText()))
            return proof.put("status", "NOT_ADOPTED").put("reasonCode", "PVS1_ESCAPE_PUBLIC_PROOF_NO_ADOPTED_ESCAPE");
        try {
            populate(mapper, result, proof);
            return proof.put("status", "AVAILABLE").put("reasonCode", "PVS1_ESCAPE_PUBLIC_PROOF_VERIFIED");
        } catch (Rejected rejected) {
            // Do not leave a partially verified source/rule attached to an unavailable proof.
            return mapper.createObjectNode().put("schemaVersion", SCHEMA).put("methodVersion", VERSION)
                    .put("scientificApprovalCreated", false).put("rescoringPerformed", false)
                    .put("status", "UNAVAILABLE").put("reasonCode", rejected.getMessage());
        } catch (RuntimeException invalid) {
            return mapper.createObjectNode().put("schemaVersion", SCHEMA).put("methodVersion", VERSION)
                    .put("scientificApprovalCreated", false).put("rescoringPerformed", false)
                    .put("status", "UNAVAILABLE").put("reasonCode", "PVS1_ESCAPE_PUBLIC_PROOF_INPUT_INVALID");
        }
    }

    private static void populate(ObjectMapper mapper, JsonNode result, ObjectNode proof) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var policy = result.path("policy");var selection = result.path("policySelection");var input = result.path("resolvedInput");
        var evaluation = result.path("evaluation");var trace = evaluation.path("deterministicTrace");
        if (!(result.path("context") instanceof ObjectNode context)) throw new Rejected("PVS1_ESCAPE_PUBLIC_PROOF_CONTEXT_REQUIRED");
        adapter.validateCompleteContext(context);
        require(context.path("contextId").asText().equals(adapter.canonicalContextId(context))
                && context.path("contextId").equals(evaluation.path("contextId")) && context.path("contextId").equals(selection.path("contextId")),"CONTEXT_BINDING_MISMATCH");
        require(context.at("/gene/hgncId").equals(policy.at("/scope/hgncId")) && context.at("/disease/mondoId").equals(policy.at("/scope/mondoId"))
                && context.path("moi").equals(policy.at("/scope/moi")) && context.at("/variant/evidenceQueryAllele/assembly").equals(policy.at("/scope/assembly"))
                && (context.at("/transcript/accession").asText()+"."+context.at("/transcript/version").asText()).equals(input.path("selectedTranscript").asText()),"SCOPE_BINDING_MISMATCH");
        var audit = input.path("escapeAssessment");var scope = policy.path("escapeScope");var rule = audit.path("selectedRule");
        require(policy.isObject() && input.isObject() && evaluation.isObject() && selection.isObject(), "INPUT_REQUIRED");
        require("REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                && "REVIEWED_FOR_PVS1_ESCAPE".equals(scope.path("status").asText())
                && audit.path("reviewId").equals(scope.path("reviewId")) && "MET".equals(audit.path("status").asText()), "REVIEW_MISMATCH");
        String policySha = adapter.canonicalSha256(policy), inputSha = adapter.canonicalSha256(input), selectionSha = adapter.canonicalSha256(selection);
        require(policySha.equals(result.path("policySha256").asText()) && policySha.equals(selection.path("policySha256").asText())
                && policy.path("policyId").equals(selection.path("policyId")) && policy.path("policyVersion").equals(selection.path("policyVersion")), "POLICY_BINDING_MISMATCH");
        var selectionIdentity = ((ObjectNode) selection).deepCopy();selectionIdentity.remove("selectionId");
        require(("cspec-selection-sha256-"+adapter.canonicalSha256(selectionIdentity)).equals(selection.path("selectionId").asText()), "SELECTION_BINDING_MISMATCH");
        String modelSha = policy.at("/transcriptModelBinding/canonicalSha256").asText();
        require(hash(modelSha) && modelSha.equals(input.path("transcriptModelSha256").asText())
                && policy.path("transcriptModelBinding").equals(result.path("transcriptModelBinding")), "MODEL_BINDING_MISMATCH");
        require("COMPLETED".equals(trace.path("executionStatus").asText())
                && policy.path("policyId").equals(trace.path("algorithmId")) && policy.path("policyVersion").equals(trace.path("algorithmVersion")), "EXECUTION_BINDING_MISMATCH");
        var traceIdentity = ((ObjectNode) trace).deepCopy();traceIdentity.remove("traceSha256");
        require(adapter.canonicalSha256(traceIdentity).equals(trace.path("traceSha256").asText())
                && parameter(trace, "policySha256").equals(policySha) && parameter(trace, "policySelectionSha256").equals(selectionSha)
                && parameter(trace, "resolvedInputSha256").equals(inputSha), "TRACE_BINDING_MISMATCH");
        var evaluationIdentity = ((ObjectNode) evaluation).deepCopy();evaluationIdentity.remove(List.of("evaluationId", "conflict", "review"));
        require(("evaluation-sha256-"+adapter.canonicalSha256(evaluationIdentity)).equals(evaluation.path("evaluationId").asText()), "EVALUATION_BINDING_MISMATCH");
        require(CodingTranscriptMapping.sourcesValid(policy.path("scientificSources")), "SOURCE_BINDING_MISMATCH");
        var knowledge = result.path("knowledgeAssessment");
        if (knowledge.at("/adoption/qualification").isObject())
            require("READY".equals(knowledge.path("status").asText())
                    && Pvs1SourceQualification.issue(knowledge.path("adoption"), policy, adapter) == null, "SOURCE_QUALIFICATION_MISMATCH");
        int length = positive(audit,"referenceProteinLength"), first = positive(audit,"firstAlteredResidue"), altered = integer(audit,"alteredProteinLength");
        int ptc = positive(input,"ptcStart");
        int actualFirst = positive(input,input.has("firstChangedProteinPosition")?"firstChangedProteinPosition":"proteinPosition");
        require(first == actualFirst && (!input.has("proteinPosition") || positive(input,"proteinPosition") == actualFirst),"PROTEIN_EFFECT_BINDING_MISMATCH");
        var modelFacts = result.path("transcriptModelFacts");
        require("pvs1-transcript-model-facts-v1".equals(modelFacts.path("schemaVersion").asText())
                && modelSha.equals(modelFacts.path("modelSha256").asText())
                && adapter.canonicalSha256(modelFacts).equals(parameter(trace,"transcriptModelFactsSha256")),"MODEL_FACTS_BINDING_MISMATCH");
        int modelLength = positive(modelFacts,"proteinLength"), modelCdsLength = positive(modelFacts,"cdsLength");
        require(modelCdsLength%3 == 0 && modelLength == modelCdsLength/3-1 && length == modelLength,"MODEL_FACTS_BINDING_MISMATCH");
        var qualifiedModel = knowledge.at("/adoption/qualification/spliceVerification/model");
        if(qualifiedModel.isObject())require(modelSha.equals(adapter.canonicalSha256(qualifiedModel))
                && integer(qualifiedModel,"proteinLength") == modelLength && integer(qualifiedModel,"cdsLength") == modelCdsLength,"MODEL_FACTS_BINDING_MISMATCH");
        copy(proof.putObject("transcriptModelFacts"),modelFacts,List.of("schemaVersion","modelSha256","proteinLength","cdsLength"));
        require(first <= length && altered >= 0 && (ptc-1)%3 == 0 && altered == (ptc-1)/3
                && integer(audit,"referenceResiduesAffected") == length-first+1
                && integer(audit,"netProteinShortening") == Math.max(0,length-altered), "PROTEIN_EFFECT_MISMATCH");
        JsonNode matched = null;int count = 0;
        require(scope.path("rules").isArray() && scope.path("rules").size() > 0 && scope.path("rules").size() <= LIMIT
                && audit.path("ruleChecks").isArray() && audit.path("ruleChecks").size() == scope.path("rules").size(), "RULE_CHECKS_INCOMPLETE");
        var publicChecks = proof.putArray("ruleChecks");
        for (int i = 0; i < scope.path("rules").size(); i++) {
            var declared = scope.path("rules").path(i);var check = audit.path("ruleChecks").path(i);
            require(declared.equals(check.path("rule")), "RULE_BINDING_MISMATCH");
            require(Set.of("MATCH", "NO_MATCH").contains(check.path("status").asText()), "RULE_CHECKS_INCOMPLETE");
            checkArithmetic(audit,declared,check,length,first);
            var row = publicChecks.addObject();var projectedRule = row.putObject("rule");copy(projectedRule,declared,RULE_FIELDS);ids(projectedRule,declared);
            copy(row,check,List.of("status","affectedResiduesInRegion","actualNumerator","actualDenominator","leftProduct","rightProduct"));
            if ("MATCH".equals(check.path("status").asText())) { count++;matched = check; }
        }
        if(matched == null) throw new Rejected("PVS1_ESCAPE_PUBLIC_PROOF_SELECTED_RULE_MISMATCH");
        require(count == 1 && rule.equals(matched.path("rule")), "SELECTED_RULE_MISMATCH");
        require(STRENGTHS.contains(rule.path("strength").asText()), "STRENGTH_MISMATCH");
        require(!"VERY_STRONG".equals(rule.path("strength").asText()) || "SPECIALIZED_POLICY_SELECTED".equals(selection.path("outcome").asText()), "SPECIALIZED_STRENGTH_REQUIRED");
        var selected = proof.putObject("selectedRule");copy(selected,rule,RULE_FIELDS);ids(selected,rule);
        var selectedCheck = proof.putObject("ruleCheck");copy(selectedCheck,matched,List.of("status","affectedResiduesInRegion","actualNumerator","actualDenominator","leftProduct","rightProduct"));
        copy(proof.putObject("proteinEffect"),audit,List.of("firstAlteredResidue","referenceProteinLength","alteredProteinLength","referenceResiduesAffected","netProteinShortening"));
        var sources = new LinkedHashMap<String,JsonNode>();addSources(sources,policy.path("scientificSources"));
        var bindings = proof.putObject("bindings").put("policySha256",policySha).put("escapeScopeSha256",adapter.canonicalSha256(scope))
                .put("selectedRuleSha256",adapter.canonicalSha256(rule)).put("transcriptModelSha256",modelSha)
                .put("resolvedInputSha256",inputSha).put("selectionSha256",selectionSha).put("evaluationSha256",adapter.canonicalSha256(evaluation))
                .put("evaluationId",evaluation.path("evaluationId").asText()).put("transcriptModelFactsSha256",adapter.canonicalSha256(modelFacts));
        var caps = proof.putObject("strengthDerivation").put("sourceRuleStrength",rule.path("strength").asText());
        String parentCap = policy.path("appliedStrength").asText();require(STRENGTHS.contains(parentCap),"STRENGTH_MISMATCH");
        caps.put("parentPolicyCap",parentCap);String adopted = Pvs1SpliceConsequence.lower(rule.path("strength").asText(),parentCap);
        if (input.path("spliceAssessment").isObject()) {
            var fact = result.path("spliceConsequenceMaterial");
            require(fact.isObject() && adapter.canonicalSha256(fact).equals(input.path("spliceMaterialSha256").asText())
                    && modelSha.equals(fact.path("transcriptModelSha256").asText())
                    && fact.path("allele").equals(result.at("/context/variant/evidenceQueryAllele")), "MATERIAL_BINDING_MISMATCH");
            if (knowledge.at("/adoption/qualification").isObject())
                require(Pvs1SourceQualification.spliceMaterialIssue(knowledge,result.path("context"),policy,knowledge.at("/adoption/qualification/spliceVerification/model"),fact,adapter) == null,"MATERIAL_QUALIFICATION_MISMATCH");
            String scopeCap = policy.at("/spliceScope/maximumStrength").asText(), reviewCap = fact.at("/review/maximumStrength").asText();
            require(STRENGTHS.contains(scopeCap) && STRENGTHS.contains(reviewCap),"STRENGTH_MISMATCH");
            String local = Pvs1SpliceConsequence.lower(scopeCap,reviewCap);
            require(local.equals(input.at("/spliceAssessment/maximumStrength").asText()),"CAP_BINDING_MISMATCH");
            adopted = Pvs1SpliceConsequence.lower(adopted,local);
            require("MET".equals(input.at("/spliceAssessment/status").asText())
                    && adopted.equals(input.at("/spliceAssessment/adoptedStrength").asText()),"CAP_BINDING_MISMATCH");
            caps.put("spliceScopeCap",scopeCap).put("outcomeReviewCap",reviewCap).put("localMaximumStrength",local);
            bindings.put("spliceMaterialSha256",adapter.canonicalSha256(fact));addSources(sources,fact.path("sourceRecords"));
            var localReviews = proof.putObject("localReviews");
            copy(localReviews.putObject("scope"),policy.path("spliceScope"),List.of("status","reviewId"));ids(localReviews.withObject("scope"),policy.path("spliceScope"));
            copy(localReviews.putObject("outcome"),fact.path("review"),List.of("status","reviewId"));ids(localReviews.withObject("outcome"),fact.path("review"));
        }
        require(adopted.equals(evaluation.path("appliedStrength").asText()),"CAP_BINDING_MISMATCH");caps.put("adoptedStrength",adopted);
        var policyPublic = proof.putObject("policy");copy(policyPublic,policy,List.of("policyId","policyVersion","schemaVersion"));
        copy(policyPublic,policy.path("review"),List.of("reviewId"));
        var publicScope = proof.putObject("scope");copy(publicScope,policy.path("scope"),List.of("hgncId","mondoId","moi","assembly","variantType"));
        publicScope.put("transcript",input.path("selectedTranscript").asText()).put("selectionOutcome",selection.path("outcome").asText());
        require(usableText(publicScope.path("transcript")),"SCOPE_BINDING_MISMATCH");
        bindings.put("alleleSha256",adapter.canonicalSha256(result.at("/context/variant/evidenceQueryAllele")));
        var review = proof.putObject("review");copy(review,scope,List.of("status","reviewId"));ids(review,scope);
        var checks = audit.path("exonChecks");require(checks.isArray() && !checks.isEmpty() && checks.size() <= LIMIT,"EXON_CHECKS_INCOMPLETE");
        var affected=audit.path("affectedReferenceExonOrdinals");
        require(affected.isArray() && affected.size()==checks.size(),"EXON_CHECKS_INCOMPLETE");
        var publicExons = proof.putArray("exonChecks");var ordinals = new HashSet<Integer>();int exonIndex=0;
        for (var check : checks) {
            int ordinal = positive(check,"ordinal");require(ordinals.add(ordinal)&&affected.path(exonIndex++).isIntegralNumber()
                    &&affected.path(exonIndex-1).asInt()==ordinal,"EXON_CHECKS_INCOMPLETE");
            int found = 0;for (var declared : scope.path("exonAssessments")) if (declared.equals(check)) found++;
            require(found == 1 && "PRESENT".equals(check.path("biologicalRelevance").asText())
                    && Set.of("NOT_FREQUENT","UNKNOWN").contains(check.path("populationLof").asText()),"EXON_CHECKS_MISMATCH");
            var row = publicExons.addObject();copy(row,check,List.of("ordinal","biologicalRelevance","populationLof"));ids(row,check);
        }
        proof.set("affectedReferenceExonOrdinals",affected.deepCopy());
        var conditions = proof.putObject("positionConditions");
        conditionWitness(conditions.putObject("escape"),scope.path("positionConditions"),input.path("escapePositionConditionChecks"),input,true);
        // nmdScope conditions belong to the NMD method, not a claimed escape PASS.
        conditionWitness(conditions.putObject("nmd"),policy.at("/nmdScope/positionConditions"),input.path("positionConditionChecks"),input,false);
        var required = new HashSet<String>();collectIds(proof,required);
        require(required.size() <= LIMIT,"SOURCE_LIMIT_EXCEEDED");var sourceList = proof.putArray("sources");
        for (var entry : sources.entrySet()) if (required.remove(entry.getKey())) {
            var row = sourceList.addObject();copy(row,entry.getValue(),List.of("sourceId","version","sourceVersion","sha256"));
            String url=entry.getValue().path("url").asText();
            if (safeUrl(url))row.put("url",url);else row.put("urlOmitted",true);
        }
        require(required.isEmpty(),"SOURCE_BINDING_MISMATCH");
    }

    private static void checkArithmetic(JsonNode audit,JsonNode rule,JsonNode row,int length,int first) {
        boolean hit;
        if("CRITICAL_REGION".equals(rule.path("predicate").asText())) {
            int affected=Math.max(0,Math.min(length,positive(rule,"end"))-Math.max(first,positive(rule,"start"))+1);
            require(affected==integer(row,"affectedResiduesInRegion"),"RULE_ARITHMETIC_MISMATCH");
            hit=affected>=positive(rule,"minimumAffectedResidues");
        } else {
            require("PROTEIN_LOSS_FRACTION".equals(rule.path("predicate").asText()),"RULE_PREDICATE_UNSUPPORTED");
            String metric=rule.path("metric").asText();require(Set.of("REFERENCE_TAIL_ALTERED_OR_REMOVED","NET_PROTEIN_SHORTENING").contains(metric),"RULE_PREDICATE_UNSUPPORTED");
            int numerator=integer(audit,metric.equals("REFERENCE_TAIL_ALTERED_OR_REMOVED")?"referenceResiduesAffected":"netProteinShortening");
            long left=(long)numerator*positive(rule,"denominator"),right=(long)length*positive(rule,"numerator");
            require(numerator==integer(row,"actualNumerator")&&length==integer(row,"actualDenominator")
                    &&row.path("leftProduct").isIntegralNumber()&&left==row.path("leftProduct").asLong()
                    &&row.path("rightProduct").isIntegralNumber()&&right==row.path("rightProduct").asLong(),"RULE_ARITHMETIC_MISMATCH");
            require(Set.of("GT","LT").contains(rule.path("comparison").asText()),"RULE_PREDICATE_UNSUPPORTED");
            hit="GT".equals(rule.path("comparison").asText())?left>right:left<right;
        }
        require((hit?"MATCH":"NO_MATCH").equals(row.path("status").asText()),"RULE_ARITHMETIC_MISMATCH");
    }
    private static void conditionWitness(ObjectNode out, JsonNode conditions, JsonNode checks, JsonNode input, boolean executed) {
        if (conditions.isMissingNode()) { require(!executed || checks.isMissingNode(),"POSITION_CHECKS_MISMATCH");out.put("status","NOT_DECLARED");return; }
        require(conditions.isArray() && conditions.size() <= 16,"POSITION_CHECKS_MISMATCH");
        out.put("status",executed?"CHECKED":"NOT_USED_BY_ESCAPE_METHOD");var rows=out.putArray("conditions");
        if(executed)require(checks.isArray() && checks.size()==conditions.size(),"POSITION_CHECKS_MISMATCH");
        for(int i=0;i<conditions.size();i++) {
            var c=conditions.path(i);var row=rows.addObject();copy(row,c,List.of("conditionId","coordinate","minimumInclusive","maximumExclusive"));ids(row,c);
            if(executed) {
                var check=checks.path(i);require(c.equals(check.path("condition")) && "PASS".equals(check.path("status").asText())
                        && check.path("checkedPositions").isArray() && !check.path("checkedPositions").isEmpty() && check.path("checkedPositions").size()<=8,"POSITION_CHECKS_MISMATCH");
                List<Integer> actualPositions=Pvs1NmdConditions.positions(c.path("coordinate").asText(),input);
                require(!actualPositions.isEmpty() && actualPositions.size()<=8 && actualPositions.size()==check.path("checkedPositions").size(),"POSITION_CHECKS_MISMATCH");
                for(int p=0;p<actualPositions.size();p++)require(check.path("checkedPositions").path(p).isIntegralNumber()
                        && check.path("checkedPositions").path(p).canConvertToInt() && actualPositions.get(p)==check.path("checkedPositions").path(p).asInt(),"POSITION_CHECKS_MISMATCH");
                for(var value:check.path("checkedPositions"))require(value.isIntegralNumber() && value.canConvertToInt() && value.asInt()>0
                        && (!c.has("minimumInclusive")||value.asInt()>=c.path("minimumInclusive").asInt())
                        && (!c.has("maximumExclusive")||value.asInt()<c.path("maximumExclusive").asInt()),"POSITION_CHECKS_MISMATCH");
                row.put("status","PASS").set("checkedPositions",check.path("checkedPositions").deepCopy());
            }
        }
    }
    private static void addSources(Map<String,JsonNode> sources,JsonNode records) {
        require(CodingTranscriptMapping.sourcesValid(records),"SOURCE_BINDING_MISMATCH");
        for(var source:records) {
            require(usableText(source.path("sourceId")),"SOURCE_BINDING_MISMATCH");
            var prior=sources.putIfAbsent(source.path("sourceId").asText(),source);
            require(prior==null||prior.equals(source),"SOURCE_BINDING_MISMATCH");
        }
    }
    private static void collectIds(JsonNode value,Set<String> ids) {
        if(value.isObject()) {
            if(value.has("sourceIds"))for(var id:value.path("sourceIds"))ids.add(id.asText());
            for(var child:value)collectIds(child,ids);
        } else if(value.isArray())for(var child:value)collectIds(child,ids);
    }
    private static void ids(ObjectNode target,JsonNode source) {
        var values=source.path("sourceIds");require(values.isArray()&&!values.isEmpty()&&values.size()<=LIMIT,"SOURCE_BINDING_MISMATCH");
        var seen=new HashSet<String>();for(var id:values)require(usableText(id)&&seen.add(id.asText()),"SOURCE_BINDING_MISMATCH");
        target.set("sourceIds",values.deepCopy());
    }
    private static String parameter(JsonNode trace,String name) {
        String value=null;int count=0;for(var row:trace.path("parameters"))if(name.equals(row.path("name").asText())){value=row.path("value").asText();count++;}
        require(count==1&&value!=null,"TRACE_BINDING_MISMATCH");return value;
    }
    private static boolean safeUrl(String url) {
        try {var parsed=URI.create(url);return url.length()<=2048&&Set.of("http","https").contains(parsed.getScheme())&&parsed.getHost()!=null&&parsed.getUserInfo()==null&&parsed.getRawQuery()==null&&parsed.getFragment()==null;}
        catch(RuntimeException invalid){return false;}
    }
    private static void copy(ObjectNode target,JsonNode source,List<String> fields) {
        for(String field:fields)if(source.has(field)) {
            var value=source.path(field);require(value.isValueNode()&&(!value.isTextual()||usableText(value)),"FIELD_LIMIT_EXCEEDED");target.set(field,value.deepCopy());
        }
    }
    private static boolean usableText(JsonNode n){return n.isTextual()&&!n.asText().isBlank()&&n.asText().length()<=256&&!n.asText().contains("\n")&&!n.asText().contains("\r");}
    private static boolean hash(String s){return s.matches("[0-9a-f]{64}");}
    private static int positive(JsonNode n,String field){int v=integer(n,field);require(v>0,"NUMERIC_FACT_INVALID");return v;}
    private static int integer(JsonNode n,String field){var v=n.path(field);require(v.isIntegralNumber()&&v.canConvertToInt(),"NUMERIC_FACT_INVALID");return v.asInt();}
    private static void require(boolean valid,String reason){if(!valid)throw new Rejected("PVS1_ESCAPE_PUBLIC_PROOF_"+reason);}
    private static final class Rejected extends RuntimeException {private Rejected(String reason){super(reason);}}
}
