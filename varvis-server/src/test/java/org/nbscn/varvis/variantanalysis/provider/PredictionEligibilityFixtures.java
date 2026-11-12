package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TEST_ONLY fictional identities and self-owned bytes. No scientific adoption or real signatures. */
final class PredictionEligibilityFixtures {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter hash = new RuntimeEvidenceV2Adapter(mapper);
    final Map<String, ObjectNode> docs = new LinkedHashMap<>();
    final ObjectNode settings = mapper.createObjectNode();
    SavedPredictionApplicationInputs.Supply roots;
    Map<String, SavedPredictionApplicationInputs.Artifact> artifacts;

    PredictionEligibilityFixtures() throws Exception {
        settings.put("analysisId", "test-analysis").put("runId", "test-calibration-run").put("requestSourcePointer", "/declaration/calibration");
        settings.put("publicationAdapterVersion", SavedPredictionApplicationInputs.FLAT_PUBLICATION);
        settings.putObject("task").put("id", "TEST_ONLY").put("version", "1").put("maxCandidates", 10)
                .put("maxHypothesesPerCandidate", 4).put("computeBudget", 0).put("purpose", "TEST_ONLY")
                .put("dataClass", "SYNTHETIC").put("assessmentAt", "2026-09-19T10:00:00Z");
        settings.putArray("candidates"); settings.putArray("policies");
        for (String field : List.of("adoptions", "modelProofs", "compatibilities", "effectSnapshots", "coAdoptions", "calibrationContracts")) settings.putObject(field);
        settings.withObject("/calibrationContracts").putObject("TEST_ONLY_CALIBRATION").put("calibrationId", "TEST_ONLY_CALIBRATION").put("version", "1").put("source", "TEST_ONLY_PRELABELLED_NO_THRESHOLDS");
        ObjectNode catalog = settings.putObject("catalog").put("schemaVersion", "acmg-specification-catalog-v1")
                .put("catalogId", "TEST_ONLY").put("catalogVersion", "1").put("searchedAt", "2026-09-19T09:00:00Z")
                .put("searchScope", "TEST_ONLY fictional scopes, no real applicability");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY");
        catalog.putArray("sources").addObject().put("sourceId", "TEST_ONLY").put("url", "https://example.invalid/test-only")
                .put("version", "1").put("sha256", hash.canonicalSha256(mapper.getNodeFactory().textNode("TEST_ONLY own source")));
        catalog.putArray("entries");
        ObjectNode profile = mapper.createObjectNode().put("profileId", "TEST_ONLY_saved_profile").put("profileVersion", "1");
        profile.putArray("enabledCriteria").add("ACMG_AMP_2015:PP3").add("ACMG_AMP_2015:BP4");
        for (String role : List.of("run", "parent", "publication")) {
            ObjectNode doc = mapper.createObjectNode().put("analysisId", "test-analysis")
                    .put("status", "COMPLETED").put("dataClass", "SYNTHETIC").put("executionSourceSha256", hash.canonicalSha256(mapper.getNodeFactory().textNode("TEST_ONLY execution")));
            doc.putArray("candidates"); docs.put(role, doc);
        }
        docs.get("run").put("runId", "test-calibration-run").put("sourceRunId", "test-parent").put("selectedCount", 2);
        docs.get("run").putArray("candidateIds"); docs.get("run").set("executionProfile", profile);
        docs.get("run").set("enabledCriteria", profile.path("enabledCriteria").deepCopy());
        docs.get("parent").put("runId", "test-parent").put("requestSha256", hash.canonicalSha256(mapper.getNodeFactory().textNode("TEST_ONLY parent request")));
        ObjectNode declaration = mapper.createObjectNode().put("analysisId", "test-analysis").put("sourceRunId", "test-parent");
        declaration.putObject("calibration").put("sourceRunId", "test-parent").put("sourceRequestSha256", docs.get("parent").path("requestSha256").asText()).put("executionSourceSha256", docs.get("run").path("executionSourceSha256").asText());
        ObjectNode request = mapper.createObjectNode(); request.set("executionProfile", profile.deepCopy()); request.set("declaration", declaration);
        docs.put("request", request);
        ObjectNode privateData = mapper.createObjectNode(); privateData.set("declaration", declaration.deepCopy()); docs.put("private", privateData);
        addCandidate(0, "BENIGN"); addCandidate(1, "HARMFUL");
        seal();
    }

    void addCandidate(int index, String direction) throws Exception {
        String id = "TEST_ONLY_" + index;
        ObjectNode context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, hash,
                RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "1", 100000 + index, "A", "G"), "ENST0000099000" + (index + 1));
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:" + (999999991 + index)).put("symbol", "TEST_ONLY_" + index);
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:999900" + (index + 1));
        context.put("contextId", hash.canonicalContextId(context));
        ObjectNode candidate = settings.withArray("candidates").addObject().put("candidateId", id).put("rank", index + 1);
        candidate.putObject("evidence");
        ObjectNode hypothesis = candidate.putArray("hypotheses").addObject().put("hypothesisId", "test-hypothesis-" + index)
                .put("verification", "VERIFIED").put("reviewId", "TEST_ONLY_CONTEXT").put("reviewerId", "TEST_ONLY_REVIEWER")
                .put("sourceSha256", hash.canonicalSha256(context)).put("effectSnapshotId", id);
        hypothesis.set("context", context);
        ObjectNode published = docs.get("publication").withArray("candidates").addObject().put("candidateId", id).put("rank", index + 1);
        published.set("inputAllele", context.at("/variant/inputAllele").deepCopy());
        published.set("hgncId", context.at("/gene/hgncId").deepCopy());
        ObjectNode key = published.putObject("queryAllele").put("genomeAssembly", "GRCh38").put("chromosome", "1")
                .put("position", 100000 + index).put("reference", "A").put("alternate", "G");
        published.putArray("annotations");
        docs.get("run").withArray("candidateIds").add(id);
        for (String role : List.of("run", "parent")) docs.get(role).withArray("candidates").addObject().put("candidateId", id).put("rank", index + 1).putArray("attempts");
        ObjectNode snapshot = settings.withObject("/effectSnapshots").putObject(id).put("snapshotId", id).put("version", "1")
                .put("status", "COMPLETE_FOR_DECLARED_SCOPE").put("reviewId", "TEST_ONLY_REVIEW").put("externalWorldCovered", false)
                .put("candidateId", id).put("hypothesisId", hypothesis.path("hypothesisId").asText()).put("contextId", context.path("contextId").asText())
                .put("equivalenceScope", "VERIFIED").put("validFrom", "2026-09-19T00:00:00Z").put("validThrough", "2026-09-20T00:00:00Z");
        snapshot.putObject("coverage").put("TEST_ONLY_PROTEIN", "COMPLETE"); snapshot.putArray("effects");
        ObjectNode template = mapper.createObjectNode().put("templateId", "TEST_ONLY_SHARED").put("version", "1");
        template.putArray("requiredCoverage").add("TEST_ONLY_PROTEIN");
        ObjectNode requirement = template.putObject("mechanisms").putObject("PROTEIN").put("model", "TEST_ONLY_MODEL")
                .put("calibrationId", "TEST_ONLY_CALIBRATION").put("calibrationVersion", "1").put("calibrationContractSha256", hash.canonicalSha256(settings.at("/calibrationContracts/TEST_ONLY_CALIBRATION"))).put("compatibilityId", "TEST_ONLY_COMPAT");
        requirement.putObject("strengthMap").put("SUPPORTING", "SUPPORTING");
        ObjectNode criteria = template.putObject("criteria");
        for (String code : List.of("PP3", "BP4")) criteria.putObject(code).put("mode", "SINGLE").putArray("mechanisms").add("PROTEIN");
        ObjectNode policy = settings.withArray("policies").addObject().put("policyId", "TEST_ONLY_POLICY_" + index).put("policyVersion", "1");
        policy.putArray("criterionIds").add("ACMG_AMP_2015:PP3").add("ACMG_AMP_2015:BP4");
        policy.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "TEST_ONLY");
        ObjectNode scope = policy.putObject("scope").put("assembly", "GRCh38").put("variantType", "NATIVE_NONPROJECTED_SNV");
        scope.set("hgncId", context.at("/gene/hgncId")); scope.set("mondoId", context.at("/disease/mondoId")); scope.set("moi", context.path("moi"));
        ObjectNode app = policy.putObject("application").put("schemaVersion", "prediction-application-policy-v1")
                .put("adoptionId", id).put("modelProofId", id); app.set("template", template);
        settings.withObject("/adoptions").putObject(id).put("status", "REVIEWED").put("reviewId", "TEST_ONLY").put("reviewerId", "TEST_ONLY")
                .put("version", "1").put("policyId", policy.path("policyId").asText()).put("policyVersion", "1");
        settings.withObject("/modelProofs").putObject(id).put("kind", "PRESELECTED").put("reviewId", "TEST_ONLY").put("reviewerId", "TEST_ONLY")
                .put("sourceSha256", hash.canonicalSha256(mapper.getNodeFactory().textNode("TEST_ONLY preselection"))).put("sourceRunId", "test-calibration-run");
        for (String code : List.of("PP3", "BP4")) {
            ObjectNode entry = settings.withObject("/catalog").withArray("entries").addObject().put("entryId", id + code)
                    .put("criterionId", "ACMG_AMP_2015:" + code).put("searchStatus", "COMPLETE").put("disposition", "GENERAL_CLAUSE_ALLOWED")
                    .put("baseRuleRelationship", "TEST_ONLY routing, not scientific policy");
            ObjectNode entryScope = scope.deepCopy(); entryScope.remove("variantType"); entryScope.put("transcriptMode", "ANY_VALIDATED_TRANSCRIPT"); entry.set("scope", entryScope);
            entry.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY"); entry.putArray("sourceIds").add("TEST_ONLY");
            entry.putObject("generalPrerequisites").put("status", "COMPLETE").putArray("sourceIds").add("TEST_ONLY");
            entry.putObject("policyBinding").put("policyId", policy.path("policyId").asText()).put("policyVersion", "1");
        }
        addMechanism(index, "PROTEIN", direction, key);
    }

    void addMechanism(int index, String name, String direction, JsonNode key) {
        ObjectNode published = (ObjectNode) docs.get("publication").path("candidates").get(index);
        ObjectNode context = (ObjectNode) candidate(index).at("/hypotheses/0/context");
        String tx = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asInt();
        int a = published.path("annotations").size();
        ObjectNode annotation = published.withArray("annotations").addObject().put("transcriptId", tx).put("source", "TEST_ONLY_VEP")
                .put("version", "1").put("proteinPosition", "1").put("aminoAcids", "A/G");
        ObjectNode observation = mapper.createObjectNode().put("schemaVersion", "prediction-observation-v1").put("status", "HIT");
        observation.set("queryAllele", key.deepCopy());
        observation.putObject("resource").put("model", "TEST_ONLY_MODEL").put("dataVersion", "1").put("scorePrecision", "ORIGINAL_DECIMAL_STRING");
        observation.putArray("rows").addObject().put("sourceRow", "chr1\t" + (100000 + index) + "\tA\tG\thg38\tTEST_ONLY_PROTEIN\t" + tx + "\tA1G\t0.5\tTEST_ONLY").put("transcriptId", tx).put("rawScore", "0.5").put("uniprotId", "TEST_ONLY_PROTEIN").put("proteinVariant", "A1G").put("rawClass", "TEST_ONLY");
        digest(observation, "observationSha256", "");
        ObjectNode prediction = mapper.createObjectNode(); prediction.set("observation", observation);
        prediction.putObject("selection").put("candidateId", candidate(index).path("candidateId").asText())
                .put("selectedAnnotationId", "test-annotation-" + a).put("versionedTranscriptId", tx)
                .put("annotationSourceSha256", hash.canonicalSha256(annotation)).put("sourceObservationSha256", observation.path("observationSha256").asText());
        ObjectNode in = prediction.putObject("associationInput").put("transcriptId", tx).put("annotationSource", "TEST_ONLY_VEP").put("annotationSourceVersion", "1")
                .put("annotationSourceSha256", hash.canonicalSha256(annotation)).put("executionSourceSha256", docs.get("run").path("executionSourceSha256").asText());
        in.set("queryAllele", key.deepCopy());
        prediction.putObject("association").put("status", "ASSOCIATED").put("rawScore", "0.5").putArray("matchedRowIndices").add(0);
        prediction.set("calibration", standard(direction));
        int attemptIndex = docs.get("run").path("candidates").get(index).path("attempts").size();
        ((ArrayNode) docs.get("run").path("candidates").get(index).path("attempts")).addObject().set("prediction", prediction);
        ((ArrayNode) docs.get("parent").path("candidates").get(index).path("attempts")).addObject().set("prediction", prediction.deepCopy());
        ObjectNode binding = candidate(index).withObject("/evidence").putObject(name).put("model", "TEST_ONLY_MODEL")
                .put("predictionPointer", "/attempts/" + attemptIndex + "/prediction").put("parentPredictionPointer", "/attempts/" + attemptIndex + "/prediction")
                .put("rawRowFormat", "AM_TSV_V1").put("annotationPointer", "/annotations/" + a).put("calibrationPointer", "/calibration");
        binding.putObject("sourceWitness");
        ObjectNode compat = settings.withObject("/compatibilities").withObject("/TEST_ONLY_COMPAT").put("id", "TEST_ONLY_COMPAT")
                .put("status", "REVIEWED").put("reviewId", "TEST_ONLY").put("reviewerId", "TEST_ONLY").put("version", "1")
                .put("scope", "EXACT_SINGLE_ROW").put("model", "TEST_ONLY_MODEL").put("purpose", "TEST_ONLY")
                .put("calibrationId", "TEST_ONLY_CALIBRATION").put("calibrationVersion", "1");
        compat.put("calibrationContractSha256", hash.canonicalSha256(settings.at("/calibrationContracts/TEST_ONLY_CALIBRATION")));
        compat.set("resource", observation.path("resource").deepCopy());
        compat.withArray("observationSha256s").add(observation.path("observationSha256").asText());
    }

    ObjectNode standard(String direction) {
        ObjectNode c = mapper.createObjectNode().put("schemaVersion", "prediction-standard-calibration-result-v1")
                .put("status", "CALIBRATED").put("direction", direction).put("rawScore", "0.5")
                .put("calibratedStrength", "UNINFORMATIVE".equals(direction) ? "NONE" : "SUPPORTING")
                .put("appliedStrength", "NONE").put("applicationStatus", "NOT_EVALUATED")
                .put("calibrationId", "TEST_ONLY_CALIBRATION").put("calibrationVersion", "1");
        c.putObject("sources").put("source", "TEST_ONLY");
        c.putObject("savedSource").put("calibrationCanonicalSha256", hash.canonicalSha256(settings.at("/calibrationContracts/TEST_ONLY_CALIBRATION"))); return c;
    }
    ObjectNode candidate(int i) { return (ObjectNode) settings.path("candidates").get(i); }
    ObjectNode prediction(int i) { return (ObjectNode) docs.get("run").path("candidates").get(i).at("/attempts/0/prediction"); }
    ObjectNode policy(int i) { return (ObjectNode) settings.path("policies").get(i); }
    ObjectNode template(int i) { return (ObjectNode) policy(i).at("/application/template"); }
    void digest(ObjectNode node, String field, String prefix) { node.remove(field); node.put(field, prefix + hash.canonicalSha256(node)); }

    /** Test author re-signs their OWN fixtures; not a production trust factory. */
    boolean refreshSourceLinks = true;

    void refreshBindings() {
        ArrayNode references = mapper.createArrayNode();
        for (int i = 0; i < settings.path("candidates").size(); i++) {
            JsonNode candidate = candidate(i);
            for (JsonNode b : candidate.path("evidence")) {
                ObjectNode binding = (ObjectNode) b;
                ObjectNode p = (ObjectNode) docs.get("run").path("candidates").get(i).at(b.path("predictionPointer").asText());
                ObjectNode prior = (ObjectNode) docs.get("parent").path("candidates").get(i).at(b.path("parentPredictionPointer").asText());
                if (refreshSourceLinks) {
                    prior.remove("calibration");
                    prior.set("selection", p.path("selection").deepCopy());
                    prior.set("associationInput", p.path("associationInput").deepCopy());
                    prior.set("association", p.path("association").deepCopy());
                }
                digest(prior, "resultId", "prediction-result-sha256-");
                if (refreshSourceLinks) {
                    references.addObject().put("candidateId", candidate.path("candidateId").asText())
                            .put("sourceResultId", prior.path("resultId").asText())
                            .put("sourceObservationSha256", prior.at("/observation/observationSha256").asText());
                    if (p.path("calibration").isObject()) {
                        ObjectNode source = ((ObjectNode) p.path("calibration")).withObject("/savedSource");
                        source.put("analysisId", docs.get("run").path("analysisId").asText())
                                .put("candidateId", candidate.path("candidateId").asText())
                                .put("parentRunId", "test-ancestor").put("childRunId", "test-parent")
                                .put("childResultId", prior.path("resultId").asText())
                                .put("observationSha256", prior.at("/observation/observationSha256").asText());
                        source.put("sourceRowSha256", PredictionCalibration.sha256(prior.at("/observation/rows/0/sourceRow").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        for (String field : List.of("selection", "associationInput")) source.set(field, prior.path(field).deepCopy());
                        source.set("resource", prior.at("/observation/resource").deepCopy());
                    }
                }
                digest(p, "resultId", "prediction-result-sha256-");
                binding.put("predictionSha256", hash.canonicalSha256(p)).put("calibrationSha256", hash.canonicalSha256(p.has("calibration") ? p.path("calibration") : mapper.nullNode()));
                ObjectNode witness = binding.withObject("/sourceWitness").put("observationSha256", p.at("/observation/observationSha256").asText())
                        .put("associationSha256", hash.canonicalSha256(p.path("association")));
                ArrayNode rows = witness.putArray("rowDigests");
                for (JsonNode row : p.at("/observation/rows")) rows.add(PredictionCalibration.sha256(row.path("sourceRow").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        if (refreshSourceLinks) {
            docs.get("parent").put("sourceRunId", "test-ancestor");
            ObjectNode reference = docs.get("request").withObject("/declaration/calibration");
            reference.put("operation", "CALIBRATE_SAVED_OBSERVATIONS");
            reference.set("results", references);
            docs.get("private").set("declaration", docs.get("request").path("declaration").deepCopy());
        }
        if (refreshSourceLinks) {
            try {
                ObjectNode adoption = mapper.createObjectNode();
                adoption.putObject("inputArtifacts").put("child", PredictionCalibration.sha256(mapper.writeValueAsBytes(docs.get("parent"))));
                adoption.putObject("localAdoption").put("reviewId", "TEST_ONLY_SOURCE_REVIEW");
                docs.put("calibrationAdoption", adoption);
                String adoptionSha = PredictionCalibration.sha256(mapper.writeValueAsBytes(adoption));
                ObjectNode reference = docs.get("request").withObject("/declaration/calibration");
                reference.put("adoptionSha256", adoptionSha).put("calibrationCanonicalSha256",
                        hash.canonicalSha256(settings.at("/calibrationContracts/TEST_ONLY_CALIBRATION")));
                docs.get("private").set("declaration", docs.get("request").path("declaration").deepCopy());
                for (int i = 0; i < settings.path("candidates").size(); i++) for (JsonNode b : candidate(i).path("evidence")) {
                    ObjectNode prediction = (ObjectNode) docs.get("run").path("candidates").get(i).at(b.path("predictionPointer").asText());
                    if (prediction.path("calibration").isObject()) {
                        ObjectNode source = ((ObjectNode) prediction.path("calibration")).withObject("/savedSource");
                        source.put("compatibilityEvidenceSha256", adoptionSha);
                        source.set("inputArtifacts", adoption.path("inputArtifacts").deepCopy());
                        source.set("localAdoption", adoption.path("localAdoption").deepCopy());
                        digest(prediction, "resultId", "prediction-result-sha256-");
                        ((ObjectNode) b).put("predictionSha256", hash.canonicalSha256(prediction))
                                .put("calibrationSha256", hash.canonicalSha256(prediction.path("calibration")));
                    }
                }
            } catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        }
        for (JsonNode p : settings.path("policies")) {
            ObjectNode app = (ObjectNode) p.path("application"); app.put("templateSha256", hash.canonicalSha256(app.path("template")));
            JsonNode proof = settings.path("modelProofs").path(app.path("modelProofId").asText());
            if (proof instanceof ObjectNode object) object.put("templateSha256", app.path("templateSha256").asText());
            JsonNode adoption = settings.path("adoptions").path(app.path("adoptionId").asText());
            if (adoption instanceof ObjectNode object) object.put("policySha256", hash.canonicalSha256(p));
            for (JsonNode entry : settings.at("/catalog/entries")) if (entry.at("/policyBinding/policyId").equals(p.path("policyId")))
                ((ObjectNode) entry.path("policyBinding")).put("policySha256", hash.canonicalSha256(p));
        }
        docs.get("run").put("requestSha256", hash.canonicalSha256(docs.get("request")));
        try { docs.get("run").put("privateArtifactSha256", PredictionCalibration.sha256(mapper.writeValueAsBytes(docs.get("private")))); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    void seal() throws Exception { refreshBindings(); sealBytes(); }
    void sealBytes() throws Exception {
        artifacts = new LinkedHashMap<>(); Map<String, String> pins = new LinkedHashMap<>();
        for (var entry : docs.entrySet()) {
            byte[] raw = mapper.writeValueAsBytes(entry.getValue()); pins.put(entry.getKey(), PredictionCalibration.sha256(raw));
            artifacts.put(entry.getKey(), new SavedPredictionApplicationInputs.Artifact(raw, entry.getValue()));
        }
        ObjectNode semantic = settings.deepCopy(); semantic.remove("readAt");
        for (JsonNode snapshot : semantic.path("effectSnapshots")) ((ObjectNode) snapshot).remove("readAt");
        roots = new SavedPredictionApplicationInputs.Supply("TEST_ONLY_INTERNAL_SUPPLY", pins, hash.canonicalSha256(semantic));
    }
    SavedPredictionApplicationInputs.Input input() { return SavedPredictionApplicationInputs.verify(mapper, artifacts, settings, roots); }
    List<PredictionApplicationEligibility.Receipt> assess() { return new PredictionApplicationEligibility(mapper).assess(input()); }
    ObjectNode result(int candidate, String criterion) { return assess().get(candidate * 2 + ("PP3".equals(criterion) ? 0 : 1)).json(); }
}
