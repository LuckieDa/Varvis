package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.provider.DeNovoSelectedBatch.PublishedBackground;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in binding of explicit patient hypotheses to local MANE and ClinGen snapshots.
 * This intake policy establishes a relationship binding, not a patient diagnosis or evidence context.
 * It does not discover hypotheses, select transcripts, rank candidates, or execute criteria.
 */
public final class PatientDiseaseContextBindingResolver {

    public static final String PROFILE_ID = "PATIENT_DISEASE_BINDING_INTAKE";
    public static final String PROFILE_VERSION = "1.0.0";
    public static final String READY = "READY";
    public static final String NOT_READY = "NOT_READY";

    private final JsonNode maneIdentity;
    private final JsonNode clingenIdentity;
    private final List<JsonNode> crosswalk;
    private final List<JsonNode> catalog;
    private boolean localInterpretationContexts;
    private boolean pp1SelectionContexts;

    /** A literature query may name an exact, unlisted disease. This is not a
     * gene-validity assertion; PP1 must independently qualify its policy later. */
    PatientDiseaseContextBindingResolver withPp1SelectionContexts() {
        var copy = withLocalInterpretationContexts();
        copy.pp1SelectionContexts = true;
        return copy;
    }

    boolean pp1SelectionContexts() { return pp1SelectionContexts; }

    boolean acceptsPreparation(DiseaseContextBinding binding) {
        return binding != null && (READY.equals(binding.status()) || pp1SelectionContexts
                && "DECLARED_SCOPE_ONLY".equals(binding.status())
                && "CLINGEN_COVERAGE_ABSENT".equals(binding.reasonCode()));
    }

    private PatientDiseaseContextBindingResolver(JsonNode manifest,
                                                List<JsonNode> crosswalk,
                                                List<JsonNode> catalog) {
        this.maneIdentity = manifest.path("resources").path("mane").deepCopy();
        this.clingenIdentity = manifest.path("resources").path("clingen").deepCopy();
        this.crosswalk = copyNodes(crosswalk);
        this.catalog = copyNodes(catalog);
    }

    /** Scoped copy: resource pins and catalog rows remain unchanged. */
    PatientDiseaseContextBindingResolver withLocalInterpretationContexts() {
        var manifest = new ObjectMapper().createObjectNode();
        var resources = manifest.putObject("resources");
        resources.set("mane", maneIdentity); resources.set("clingen", clingenIdentity);
        var copy = new PatientDiseaseContextBindingResolver(manifest, crosswalk, catalog);
        copy.localInterpretationContexts = true;
        return copy;
    }

    /** Reads only caller-selected local files; verifies the pinned derived snapshots before use. */
    public static PatientDiseaseContextBindingResolver load(ObjectMapper mapper,
                                                           Path manifestPath,
                                                           Path manePath,
                                                           Path clingenPath) throws IOException {
        JsonNode manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        require(PROFILE_ID.equals(value(manifest, "profileId"))
                        && PROFILE_VERSION.equals(value(manifest, "profileVersion"))
                        && PROFILE_VERSION.equals(value(manifest, "parserVersion")),
                "BINDING_RESOURCE_PROFILE_INVALID");
        JsonNode mane = manifest.path("resources").path("mane");
        JsonNode clingen = manifest.path("resources").path("clingen");
        require("MANE_GRCh38_SUMMARY".equals(value(mane, "resourceId"))
                        && "https://ftp.ncbi.nlm.nih.gov/refseq/MANE/MANE_human/release_1.5/MANE.GRCh38.v1.5.summary.txt.gz"
                        .equals(value(mane, "sourceUrl"))
                        && "CLINGEN_GENE_DISEASE_VALIDITY".equals(value(clingen, "resourceId"))
                        && "https://search.clinicalgenome.org/kb/gene-validity/download".equals(value(clingen, "sourceUrl")),
                "BINDING_RESOURCE_SOURCE_IDENTITY_INVALID");
        JsonNode expectedPolicy = mapper.readTree("""
                {"policyId":"PATIENT_DISEASE_BINDING_INTAKE","version":"1.0.0",
                 "readyClassifications":["Definitive","Strong","Moderate"],
                 "reviewRequiredClassifications":["Limited"],
                 "notReadyClassifications":["Disputed","Refuted","No Known Disease Relationship",""],
                 "catalogMoiMappings":{"AD":"AUTOSOMAL_DOMINANT","AR":"AUTOSOMAL_RECESSIVE"},
                 "unmappedCatalogMoi":["XL","SD","UD","MT"],
                 "claim":"VARVIS_BINDING_INTAKE_ONLY_NOT_PATIENT_DIAGNOSIS"}
                """);
        require(expectedPolicy.equals(manifest.path("intakePolicy")), "BINDING_RESOURCE_INTAKE_POLICY_INVALID");
        require("1.5".equals(value(mane, "resourceVersion"))
                        && "GRCh38".equals(value(mane, "assembly")),
                "MANE_RESOURCE_VERSION_INVALID");
        List<JsonNode> crosswalk = readResource(mapper, mane, manePath);
        List<JsonNode> catalog = readResource(mapper, clingen, clingenPath);
        for (JsonNode row : crosswalk) {
            require(matches(value(row, "ncbiGeneId"), "[1-9][0-9]*")
                            && matches(value(row, "hgncId"), "HGNC:[1-9][0-9]*")
                            && text(value(row, "symbol")) && row.path("sourceRows").isArray()
                            && !row.path("sourceRows").isEmpty(),
                    "MANE_CROSSWALK_RECORD_INVALID");
        }
        for (JsonNode row : catalog) {
            require(matches(value(row, "hgncId"), "HGNC:[1-9][0-9]*")
                            && matches(value(row, "mondoId"), "MONDO:[0-9]{7}")
                            && text(value(row, "symbol")) && text(value(row, "diseaseLabel"))
                            && row.path("moi").isTextual()
                            && row.path("classification").isTextual()
                            && row.path("sourceRow").isIntegralNumber() && row.path("sourceRow").asInt() > 0,
                    "CLINGEN_CATALOG_RECORD_INVALID");
        }
        return new PatientDiseaseContextBindingResolver(manifest, crosswalk, catalog);
    }

    /** Explicit VCEP scope is a different source type from a gene-validity classification. */
    public PatientDiseaseContextBindingResolver withMissenseSpecification(ObjectMapper mapper) {
        if (catalog.stream().anyMatch(PatientDiseaseContextBindingResolver::vcepScope)) return this;
        var profile = org.nbscn.varvis.variantanalysis.provider.ClinVarMissenseBusinessRuntime.loadProfile(mapper);
        var scope = profile.path("applicability");
        JsonNode specification;
        try (var input = getClass().getResourceAsStream("/acmg/cspec-gn049-v2.3-snapshot.json")) {
            byte[] bytes = Objects.requireNonNull(input).readAllBytes();
            require("c4d6bd2dab599a4b2e66a4704dc1238ca873dfc9be0e3a9f5f9944d7b7133aa4".equals(sha256(bytes)), "VCEP_SPECIFICATION_SOURCE_MISMATCH");
            specification = mapper.readTree(bytes);
        } catch (IOException invalid) { throw new IllegalArgumentException("VCEP_SPECIFICATION_SOURCE_UNAVAILABLE", invalid); }
        var source = mapper.createObjectNode().put("resourceId", "CLINGEN_VCEP_SPECIFICATION_SCOPE_GN049")
                .put("resourceVersion", "2.3.0").put("sourceUrl", "https://cspec.genome.network/cspec/ui/svi/doc/GN049")
                .put("snapshotSha256", "c4d6bd2dab599a4b2e66a4704dc1238ca873dfc9be0e3a9f5f9944d7b7133aa4")
                .put("identityFingerprintSha256", new org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts(mapper).requestHash(profile));
        var row = mapper.createObjectNode().put("hgncId", "HGNC:1097").put("symbol", scope.path("geneSymbol").asText())
                .put("mondoId", scope.path("mondoId").asText()).put("diseaseLabel", "RASopathy")
                .put("moi", "AD").put("classification", "VCEP_APPLICABLE")
                .put("relationshipType", "VCEP_SPECIFICATION_SCOPE").put("classificationIsGeneValidity", false)
                .put("classificationDate", specification.at("/entContent/approvedOn").asText())
                .put("dateSemantics", "SPECIFICATION_APPROVAL_DATE_NOT_GENE_VALIDITY_CLASSIFICATION_DATE")
                .put("gcep", "ClinGen RASopathy VCEP").put("reportLocator", source.path("sourceUrl").asText());
        row.set("relationshipSource", source);
        var expanded = new ArrayList<JsonNode>(catalog); expanded.add(row);
        var manifest = mapper.createObjectNode(); var resources = manifest.putObject("resources");
        resources.set("mane", maneIdentity); resources.set("clingen", clingenIdentity);
        return new PatientDiseaseContextBindingResolver(manifest, crosswalk, expanded);
    }

    /** Catalog references for explicit user selection, never patient hypotheses or READY bindings. */
    public PatientDiseaseContextBindingResolver withBs1Knowledge(ObjectMapper mapper,
            org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog knowledge) {
        var expanded = new ArrayList<JsonNode>(catalog);
        var knowledgeIdentity = knowledge.identity();
        require("bs1-knowledge-identity-v1".equals(knowledgeIdentity.path("schemaVersion").asText())
                && knowledgeIdentity.path("rawSha256").asText().matches("[0-9a-f]{64}"),
                "BS1_RELATIONSHIP_SOURCE_IDENTITY_INVALID");
        var symbolsByGene = new java.util.HashMap<String, java.util.Set<String>>();
        for (var row : crosswalk) symbolsByGene.computeIfAbsent(value(row, "hgncId"), ignored -> new java.util.HashSet<>()).add(value(row, "symbol"));
        for (var background : knowledge.relationships()) {
            String gene = value(background, "hgncId"), disease = value(background, "mondoId");
            String mode = normalizeCatalogMoi(value(background, "moi"));
            if (!matches(gene, "HGNC:[1-9][0-9]*") || !matches(disease, "MONDO:[0-9]{7}") || mode == null) continue;
            var symbols = symbolsByGene.getOrDefault(gene, java.util.Set.of());
            if (symbols.size() != 1) continue;
            String symbol = symbols.iterator().next();
            if (!text(symbol)) continue;
            var assertions = background.path("assertions");
            if (!assertions.isArray() || assertions.isEmpty()) continue;
            JsonNode first = assertions.get(0);
            // An unsupported frequency model is not a disputed gene-disease
            // relationship. Preserve source confidence independently of MCF.
            boolean eligible = "BS1_DISEASE_PARAMETERS_MISSING".equals(background.path("reasonCode").asText())
                    || "BS1_MODEL_MOI_UNSUPPORTED".equals(background.path("reasonCode").asText());
            String classification = eligible ? "Definitive" : "Limited";
            for (var a : assertions) {
                if (!gene.equals(value(a, "hgncId")) || !disease.equals(value(a, "mondoId"))
                        || !mode.equals(normalizeCatalogMoi(value(a, "moi")))
                        || !text(value(a, "sourceId")) || intakeBlocker(value(a, "classification")) != null) eligible = false;
                if ("Moderate".equals(a.path("classification").asText())) classification = "Moderate";
                else if ("Strong".equals(a.path("classification").asText()) && "Definitive".equals(classification)) classification = "Strong";
            }
            if (!eligible) classification = "BATCH_RELATIONSHIP_REQUIRES_REVIEW";
            var row = mapper.createObjectNode().put("hgncId", gene).put("symbol", symbol).put("mondoId", disease)
                    .put("diseaseLabel", first.path("diseaseLabel").asText()).put("moi", catalogSpelling(mode))
                    .put("classification", classification).put("classificationDate", first.path("classificationDate").asText(first.at("/original/submitted_as_date").asText()))
                    .put("gcep", "BATCH_SOURCE_ASSERTIONS").put("reportLocator", first.path("reportLocator").asText(first.at("/original/submitted_as_public_report_url").asText()))
                    .put("sourceRow", first.path("sourceRow").asInt()).put("relationshipType", "BS1_BATCH_KNOWLEDGE")
                    .put("classificationIsGeneValidity", true).put("sourceReasonCode", background.path("reasonCode").asText());
            row.set("relationshipSource", knowledgeIdentity); row.set("assertions", assertions.deepCopy());
            addKnowledgeRelationship(expanded, row);
        }
        for (var row : knowledge.expertRelationships()) {
            String gene = value(row, "hgncId"), symbol = value(row, "symbol");
            var symbols = symbolsByGene.getOrDefault(gene, java.util.Set.of());
            // Published scope stays distinct from gene validity; neither can
            // overwrite a contradictory assertion merely by arriving later.
            if (symbols.size() != 1 || !symbols.contains(symbol)
                    || relationshipKey(row) == null || !bs1ExpertScope(row)
                    || !knowledgeIdentity.equals(row.path("relationshipSource"))) continue;
            addKnowledgeRelationship(expanded, row);
        }
        var manifest = mapper.createObjectNode(); var resources = manifest.putObject("resources");
        resources.set("mane", maneIdentity); resources.set("clingen", clingenIdentity);
        var copy = new PatientDiseaseContextBindingResolver(manifest, crosswalk, expanded);
        copy.localInterpretationContexts = localInterpretationContexts;
        copy.pp1SelectionContexts = pp1SelectionContexts;
        return copy;
    }

    /** Fill uncovered relationships using the exact release checked by the selected evidence module. */
    PatientDiseaseContextBindingResolver withDeNovoPublishedBackgrounds(ObjectMapper mapper, List<PublishedBackground> backgrounds) {
        if (backgrounds.isEmpty()) return this;
        var expanded = new ArrayList<JsonNode>(catalog);
        for (var scope : backgrounds) {
            if (catalog.stream().anyMatch(row -> scope.hgncId().equals(value(row, "hgncId")) && scope.mondoId().equals(value(row, "mondoId"))))
                continue;
            if (backgrounds.stream().filter(other -> scope.hgncId().equals(other.hgncId()) && scope.mondoId().equals(other.mondoId())
                    && scope.moi().equals(other.moi())).count() != 1) continue;
            var row = mapper.createObjectNode().put("hgncId", scope.hgncId()).put("symbol", scope.symbol())
                    .put("mondoId", scope.mondoId()).put("diseaseLabel", scope.diseaseLabel()).put("moi", scope.moi())
                    .put("classification", "VCEP_APPLICABLE").put("classificationIsGeneValidity", false)
                    .put("relationshipType", "DENOVO_VCEP_SPECIFICATION_SCOPE")
                    .put("classificationDate", scope.approvedOn()).put("dateSemantics", "SPECIFICATION_APPROVAL_DATE_NOT_GENE_VALIDITY_CLASSIFICATION_DATE")
                    .put("gcep", scope.panel()).put("reportLocator", scope.sourceUrl());
            row.putObject("relationshipSource").put("resourceId", "CLINGEN_DENOVO_SPECIFICATION_SCOPE")
                    .put("resourceVersion", scope.specificationVersion()).put("sourceUrl", scope.sourceUrl())
                    .put("snapshotSha256", scope.releaseSha256()).put("phenotypePolicySha256", scope.phenotypePolicySha256());
            expanded.add(row);
        }
        var manifest = mapper.createObjectNode();
        var resources = manifest.putObject("resources");
        resources.set("mane", maneIdentity);
        resources.set("clingen", clingenIdentity);
        var copy = new PatientDiseaseContextBindingResolver(manifest, crosswalk, expanded);
        copy.localInterpretationContexts = localInterpretationContexts;
        copy.pp1SelectionContexts = pp1SelectionContexts;
        return copy;
    }

    private static String catalogSpelling(String canonical) {
        return switch (canonical) {
            case "AUTOSOMAL_DOMINANT" -> "AD";
            case "AUTOSOMAL_RECESSIVE" -> "AR";
            default -> canonical;
        };
    }

    private static String relationshipKey(JsonNode row) {
        String gene = value(row, "hgncId"), disease = value(row, "mondoId"), mode = normalizeCatalogMoi(value(row, "moi"));
        return matches(gene, "HGNC:[1-9][0-9]*") && matches(disease, "MONDO:[0-9]{7}") && mode != null
                ? gene + "|" + disease + "|" + mode : null;
    }

    private static boolean acceptedRelationship(JsonNode row) {
        return vcepScope(row) || bs1ExpertScope(row) || deNovoExpertScope(row) || intakeBlocker(value(row, "classification")) == null;
    }

    private static void addKnowledgeRelationship(List<JsonNode> rows, JsonNode incoming) {
        String key = relationshipKey(incoming);
        if (key == null) return;
        var existing = rows.stream().filter(row -> key.equals(relationshipKey(row))).toList();
        if (existing.stream().anyMatch(incoming::equals)) return;
        // A consistent, already present binding is stable. Preserve contrary
        // source rows so ordinary binding can expose the conflict and its proof.
        if (!existing.isEmpty() && acceptedRelationship(incoming)
                && existing.stream().allMatch(PatientDiseaseContextBindingResolver::acceptedRelationship)) return;
        rows.add(incoming.deepCopy());
    }

    /** Catalog references for explicit user selection, never patient hypotheses or READY bindings. */
    public JsonNode referenceOptions(ObjectMapper mapper, VariantInterpretation candidate) {
        var result = mapper.createObjectNode().put("claim", "CATALOG_REFERENCE_NOT_PATIENT_DIAGNOSIS");
        var relationships = result.putArray("relationships");
        var features = candidate.getExomiserCandidateFeatures();
        String ncbi = features == null ? null : normalizeNcbiGeneId(features.getEntrezGeneId());
        String symbol = features == null ? null : features.getGeneSymbol();
        var genes = crosswalk.stream().filter(row -> Objects.equals(ncbi, value(row, "ncbiGeneId"))
                || Objects.equals(symbol, value(row, "symbol"))).toList();
        if (ncbi == null || symbol == null || !text(symbol) || genes.isEmpty()
                || genes.stream().anyMatch(row -> !ncbi.equals(value(row, "ncbiGeneId")) || !symbol.equals(value(row, "symbol")))
                || genes.stream().map(row -> value(row, "hgncId")).distinct().count() != 1) {
            return result.put("reasonCode", "MANE_GENE_IDENTITY_UNAVAILABLE_OR_AMBIGUOUS");
        }
        String hgnc = value(genes.get(0), "hgncId");
        result.put("hgncId", hgnc);
        result.set("geneIdentitySource", maneIdentity.deepCopy());
        result.set("relationshipSource", clingenIdentity.deepCopy());
        catalog.stream().filter(row -> hgnc.equals(value(row, "hgncId")) && symbol.equals(value(row, "symbol")))
                .forEach(row -> relationships.add(row.deepCopy()));
        return result;
    }

    public CandidateBindingResult resolve(VariantInterpretation candidate,
                                          List<PatientDiseaseHypothesis> hypotheses) {
        String candidateId = candidate == null ? null : candidate.getCandidateId();
        if (candidateId == null || !text(candidateId)) {
            return blockedCandidate(candidateId, "CANDIDATE_ID_REQUIRED", hypotheses);
        }
        if (hypotheses == null || hypotheses.isEmpty()) {
            return blockedCandidate(candidateId, "DISEASE_HYPOTHESIS_REQUIRED", List.of());
        }
        Map<String, Integer> hypothesisIds = new LinkedHashMap<>();
        for (PatientDiseaseHypothesis hypothesis : hypotheses) {
            if (hypothesis != null && text(hypothesis.hypothesisId())) {
                hypothesisIds.merge(hypothesis.hypothesisId(), 1, (left, right) -> left + right);
            }
        }
        List<DiseaseContextBinding> bindings = new ArrayList<>();
        for (PatientDiseaseHypothesis hypothesis : hypotheses) {
            String blocker = hypothesis == null ? "DISEASE_HYPOTHESIS_REQUIRED"
                    : !text(hypothesis.candidateId()) ? "HYPOTHESIS_CANDIDATE_ID_REQUIRED"
                    : !candidateId.equals(hypothesis.candidateId()) ? "CANDIDATE_ID_MISMATCH"
                    : !text(hypothesis.hypothesisId()) ? "HYPOTHESIS_ID_REQUIRED"
                    : hypothesisIds.get(hypothesis.hypothesisId()) > 1 ? "HYPOTHESIS_ID_AMBIGUOUS"
                    : null;
            bindings.add(blocker == null ? bind(candidate, hypothesis)
                    : outcome(candidate, hypothesis, null, List.of(), List.of(), blocker));
        }
        return new CandidateBindingResult(candidateId, bindings, List.of());
    }

    /** A minimal ordered wrapper to reject duplicate candidate IDs before any binding. */
    public List<CandidateBindingResult> resolveCandidates(List<VariantInterpretation> candidates,
                                                         List<PatientDiseaseHypothesis> hypotheses) {
        Objects.requireNonNull(candidates, "candidates");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (VariantInterpretation candidate : candidates) {
            if (candidate != null) {
                counts.merge(candidate.getCandidateId(), 1, (left, right) -> left + right);
            }
        }
        List<CandidateBindingResult> results = new ArrayList<>();
        for (VariantInterpretation candidate : candidates) {
            String id = candidate == null ? null : candidate.getCandidateId();
            List<PatientDiseaseHypothesis> matching = hypotheses == null ? List.of()
                    : hypotheses.stream().filter(h -> h != null && Objects.equals(id, h.candidateId())).toList();
            results.add(id != null && counts.get(id) > 1
                    ? blockedCandidate(id, "CANDIDATE_ID_AMBIGUOUS", matching)
                    : resolve(candidate, matching));
        }
        // Unknown or missing target IDs are surfaced instead of silently discarding supplied hypotheses.
        if (hypotheses != null) {
            for (PatientDiseaseHypothesis hypothesis : hypotheses) {
                if (hypothesis == null || !counts.containsKey(hypothesis.candidateId())) {
                    String id = hypothesis == null ? null : hypothesis.candidateId();
                    results.add(blockedCandidate(id,
                            text(id) ? "CANDIDATE_ID_MISMATCH" : "HYPOTHESIS_CANDIDATE_ID_REQUIRED",
                            hypothesis == null ? List.of() : List.of(hypothesis)));
                }
            }
        }
        return List.copyOf(results);
    }

    private DiseaseContextBinding bind(VariantInterpretation candidate, PatientDiseaseHypothesis hypothesis) {
        CandidateIdentity identity = candidate.getCandidateIdentity();
        ExomiserCandidateFeatures features = candidate.getExomiserCandidateFeatures();
        List<JsonNode> geneRows = List.of();
        List<JsonNode> relationships = List.of();
        String moi = null;
        String blocker;
        if (!validProvenance(hypothesis.provenance())) {
            blocker = "HYPOTHESIS_PROVENANCE_REQUIRED";
        } else if (localPath(hypothesis.provenance().source()) || localPath(hypothesis.provenance().version())
                || localPath(hypothesis.provenance().recordLocator())) {
            blocker = "HYPOTHESIS_PROVENANCE_LOCAL_PATH";
        } else if (!matches(hypothesis.hgncId(), "HGNC:[1-9][0-9]*")) {
            blocker = "HYPOTHESIS_HGNC_REQUIRED";
        } else if (!matches(hypothesis.mondoId(), "MONDO:[0-9]{7}")) {
            blocker = "EXACT_MONDO_REQUIRED";
        } else if (features == null || normalizeNcbiGeneId(features.getEntrezGeneId()) == null) {
            blocker = "CANDIDATE_NCBI_GENE_REQUIRED";
        } else if (!text(features.getGeneSymbol())) {
            blocker = "CANDIDATE_SYMBOL_REQUIRED";
        } else if (text(identity.getGeneSymbol()) && !identity.getGeneSymbol().equals(features.getGeneSymbol())) {
            blocker = "CANDIDATE_SYMBOL_CONFLICT";
        } else {
            String ncbi = normalizeNcbiGeneId(features.getEntrezGeneId());
            String symbol = features.getGeneSymbol();
            geneRows = crosswalk.stream().filter(row -> ncbi.equals(value(row, "ncbiGeneId"))
                    || symbol.equals(value(row, "symbol"))).toList();
            if (geneRows.isEmpty()) {
                blocker = "MANE_CROSSWALK_NO_RECORD";
            } else if (geneRows.stream().anyMatch(row -> !ncbi.equals(value(row, "ncbiGeneId"))
                    || !symbol.equals(value(row, "symbol")))) {
                blocker = "MANE_GENE_IDENTITY_CONFLICT";
            } else if (geneRows.stream().map(row -> value(row, "hgncId")).distinct().count() != 1) {
                blocker = "MANE_HGNC_AMBIGUOUS";
            } else if (!hypothesis.hgncId().equals(value(geneRows.get(0), "hgncId"))) {
                blocker = "HYPOTHESIS_HGNC_CONFLICT";
            } else if (crosswalk.stream().anyMatch(row -> hypothesis.hgncId().equals(value(row, "hgncId"))
                    && (!ncbi.equals(value(row, "ncbiGeneId")) || !symbol.equals(value(row, "symbol"))))) {
                geneRows = crosswalk.stream().filter(row -> ncbi.equals(value(row, "ncbiGeneId"))
                        || symbol.equals(value(row, "symbol")) || hypothesis.hgncId().equals(value(row, "hgncId"))).toList();
                blocker = "MANE_GENE_IDENTITY_CONFLICT";
            } else {
                moi = normalizeMoi(hypothesis.moi());
                blocker = localInterpretationContexts ? LocalInterpretationContextPolicy.assess(candidate, moi).reasonCode()
                        : candidateMoiBlocker(identity, features, moi);
                if (blocker == null) {
                    List<JsonNode> diseaseRows = catalog.stream().filter(row ->
                            hypothesis.hgncId().equals(value(row, "hgncId"))
                                    && hypothesis.mondoId().equals(value(row, "mondoId"))).toList();
                    String selectedMoi = moi;
                    var exact = diseaseRows.stream().filter(row -> selectedMoi.equals(normalizeCatalogMoi(value(row, "moi")))).toList();
                    relationships = !exact.isEmpty() ? exact : diseaseRows.stream().filter(row ->
                            localInterpretationContexts && LocalInterpretationContextPolicy.catalogCovers(value(row, "moi"), selectedMoi)).toList();
                    if (!exact.isEmpty() && ("X_LINKED_DOMINANT".equals(selectedMoi) || "X_LINKED_RECESSIVE".equals(selectedMoi))) {
                        var withContraryBroadSources = new ArrayList<JsonNode>(exact);
                        for (var row : diseaseRows) if ("XL".equals(value(row, "moi"))
                                && !acceptedRelationship(row) && relationshipProvenanceReady(row)) withContraryBroadSources.add(row);
                        relationships = List.copyOf(withContraryBroadSources);
                    }
                    if (conflictingExactXMode(diseaseRows, selectedMoi)) {
                        relationships = diseaseRows;
                        blocker = "CATALOG_MOI_CONFLICT";
                    } else if (relationships.isEmpty()) {
                        relationships = diseaseRows;
                        blocker = diseaseRows.isEmpty() ? "CLINGEN_COVERAGE_ABSENT"
                                : diseaseRows.stream().anyMatch(row -> normalizeCatalogMoi(value(row, "moi")) != null)
                                ? "CATALOG_MOI_CONFLICT" : "CATALOG_MOI_UNMAPPABLE";
                    } else if (relationships.stream().anyMatch(row -> !symbol.equals(value(row, "symbol")))) {
                        blocker = "CLINGEN_GENE_IDENTITY_CONFLICT";
                    } else if (relationships.size() > 1) {
                        blocker = relationships.stream().map(row -> value(row, "classification"))
                                .distinct().count() > 1 ? "CLINGEN_RELATIONSHIP_CONFLICT"
                                : "CLINGEN_RELATIONSHIP_DUPLICATE";
                    } else {
                        if (localInterpretationContexts && "XL".equals(value(relationships.get(0), "moi")))
                            blocker = LocalInterpretationContextPolicy.assessCatalog(candidate, "XL").reasonCode();
                        if (blocker == null && !acceptedRelationship(relationships.get(0)))
                            blocker = intakeBlocker(value(relationships.get(0), "classification"));
                        if (blocker == null && !relationshipProvenanceReady(relationships.get(0))) {
                            blocker = "CLINGEN_RELATIONSHIP_PROVENANCE_REQUIRED";
                        }
                    }
                }
            }
        }
        return outcome(candidate, hypothesis, moi, geneRows, relationships, blocker);
    }

    private static boolean conflictingExactXMode(List<JsonNode> rows, String selectedMode) {
        if (!"X_LINKED_DOMINANT".equals(selectedMode) && !"X_LINKED_RECESSIVE".equals(selectedMode)) return false;
        for (var row : rows) {
            String mode = normalizeCatalogMoi(value(row, "moi"));
            if (("X_LINKED_DOMINANT".equals(mode) || "X_LINKED_RECESSIVE".equals(mode))
                    && !selectedMode.equals(mode) && acceptedRelationship(row) && relationshipProvenanceReady(row)) return true;
        }
        return false;
    }

    private String candidateMoiBlocker(CandidateIdentity identity,
                                       ExomiserCandidateFeatures features,
                                       String hypothesisMoi) {
        if (hypothesisMoi == null) {
            return "HYPOTHESIS_MOI_UNMAPPABLE";
        }
        List<String> values = new ArrayList<>();
        if (text(identity.getModeOfInheritance())) { values.add(identity.getModeOfInheritance()); }
        if (text(features.getRawModeOfInheritance())) { values.add(features.getRawModeOfInheritance()); }
        if (text(features.getNormalizedModeOfInheritance())) { values.add(features.getNormalizedModeOfInheritance()); }
        if (values.isEmpty()) {
            return "CANDIDATE_MOI_REQUIRED";
        }
        for (String value : values) {
            String normalized = normalizeMoi(value);
            if (normalized == null) { return "CANDIDATE_MOI_UNMAPPABLE"; }
            if (!hypothesisMoi.equals(normalized)) { return "CANDIDATE_HYPOTHESIS_MOI_CONFLICT"; }
        }
        return null;
    }

    private DiseaseContextBinding outcome(VariantInterpretation candidate,
                                           PatientDiseaseHypothesis hypothesis,
                                           String moi,
                                           List<JsonNode> geneRows,
                                           List<JsonNode> relationships,
                                           String blocker) {
        JsonNode relationship = relationships.size() == 1 ? relationships.get(0) : null;
        ExomiserCandidateFeatures features = candidate == null ? null : candidate.getExomiserCandidateFeatures();
        BindingProvenance provenance = provenance(hypothesis, geneRows, relationships);
        return new DiseaseContextBinding(candidate == null ? null : candidate.getCandidateId(),
                hypothesis == null ? null : hypothesis.hypothesisId(),
                hypothesis == null ? null : hypothesis.hgncId(),
                features == null ? null : features.getGeneSymbol(),
                hypothesis == null ? null : hypothesis.mondoId(),
                value(relationship, "diseaseLabel"), moi,
                value(relationship, "classification"), value(relationship, "classificationDate"),
                value(relationship, "gcep"), value(relationship, "reportLocator"),
                blocker == null ? READY : pp1SelectionContexts && "CLINGEN_COVERAGE_ABSENT".equals(blocker)
                        ? "DECLARED_SCOPE_ONLY" : NOT_READY, blocker, provenance);
    }

    private CandidateBindingResult blockedCandidate(String candidateId, String reason,
                                                    List<PatientDiseaseHypothesis> hypotheses) {
        List<PatientDiseaseHypothesis> sources = hypotheses == null ? List.of() : hypotheses.stream()
                .filter(Objects::nonNull).map(PatientDiseaseContextBindingResolver::safeHypothesis).toList();
        return new CandidateBindingResult(candidateId, List.of(), List.of(new BindingBlocker(
                NOT_READY, reason, sources, maneIdentity, clingenIdentity)));
    }

    private BindingProvenance provenance(PatientDiseaseHypothesis hypothesis,
                                         List<JsonNode> geneRows, List<JsonNode> relationships) {
        boolean specification = relationships.size() == 1 && (vcepScope(relationships.get(0)) || deNovoExpertScope(relationships.get(0)));
        boolean batch = relationships.size() == 1 && ("BS1_BATCH_KNOWLEDGE".equals(value(relationships.get(0), "relationshipType")) || bs1ExpertScope(relationships.get(0)));
        JsonNode source = specification || batch ? relationships.get(0).path("relationshipSource") : clingenIdentity;
        return new BindingProvenance(safeHypothesis(hypothesis), maneIdentity, source, geneRows, relationships,
                localInterpretationContexts ? LocalInterpretationContextPolicy.ID : PROFILE_ID,
                localInterpretationContexts ? LocalInterpretationContextPolicy.VERSION : specification ? "1.1.0-ps1-pm5" : PROFILE_VERSION);
    }

    static boolean vcepScope(JsonNode row) {
        return row != null && "VCEP_APPLICABLE".equals(value(row, "classification"))
                && "VCEP_SPECIFICATION_SCOPE".equals(value(row, "relationshipType"))
                && row.has("classificationIsGeneValidity") && !row.path("classificationIsGeneValidity").asBoolean(true)
                && "CLINGEN_VCEP_SPECIFICATION_SCOPE_GN049".equals(row.at("/relationshipSource/resourceId").asText())
                && "2.3.0".equals(row.at("/relationshipSource/resourceVersion").asText())
                && "https://cspec.genome.network/cspec/ui/svi/doc/GN049".equals(row.at("/relationshipSource/sourceUrl").asText())
                && "c4d6bd2dab599a4b2e66a4704dc1238ca873dfc9be0e3a9f5f9944d7b7133aa4".equals(row.at("/relationshipSource/snapshotSha256").asText())
                && ScopeFingerprint.HASH.equals(row.at("/relationshipSource/identityFingerprintSha256").asText())
                && "HGNC:1097".equals(value(row, "hgncId")) && "BRAF".equals(value(row, "symbol"))
                && "MONDO:0021060".equals(value(row, "mondoId")) && "AD".equals(value(row, "moi"));
    }
    static boolean bs1ExpertScope(JsonNode row) {
        return row != null && "BS1_VCEP_APPLICABLE".equals(value(row, "classification"))
                && "BS1_EXPERT_SPECIFICATION_SCOPE".equals(value(row, "relationshipType"))
                && row.path("classificationIsGeneValidity").isBoolean() && !row.path("classificationIsGeneValidity").asBoolean()
                && "bs1-knowledge-identity-v1".equals(row.at("/relationshipSource/schemaVersion").asText())
                && row.at("/relationshipSource/rawSha256").asText().matches("[0-9a-f]{64}")
                && row.path("knowledgeRecordSha256").asText().matches("[0-9a-f]{64}");
    }

    private static boolean deNovoExpertScope(JsonNode row) {
        return "DENOVO_VCEP_SPECIFICATION_SCOPE".equals(value(row, "relationshipType"))
                && "VCEP_APPLICABLE".equals(value(row, "classification"))
                && row.path("classificationIsGeneValidity").isBoolean() && !row.path("classificationIsGeneValidity").asBoolean()
                && "CLINGEN_DENOVO_SPECIFICATION_SCOPE".equals(row.at("/relationshipSource/resourceId").asText())
                && row.at("/relationshipSource/snapshotSha256").asText().matches("[0-9a-f]{64}")
                && row.at("/relationshipSource/phenotypePolicySha256").asText().matches("[0-9a-f]{64}");
    }
    private static final class ScopeFingerprint {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final String HASH = new org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts(MAPPER)
                .requestHash(org.nbscn.varvis.variantanalysis.provider.ClinVarMissenseBusinessRuntime.loadProfile(MAPPER));
    }

    private static String intakeBlocker(String classification) {
        return switch (classification == null ? "" : classification) {
            case "Definitive", "Strong", "Moderate" -> null;
            case "Limited" -> "REVIEW_REQUIRED";
            case "Disputed", "Refuted", "No Known Disease Relationship", "" -> "CLINGEN_CLASSIFICATION_NOT_ACCEPTED";
            default -> "CLINGEN_CLASSIFICATION_UNSUPPORTED";
        };
    }

    /** Explicit spellings only; generic XL, SD, UD and MT have no supported lossless v2 mapping. */
    public static String normalizeMoi(String value) {
        // Match only the explicit Y-linked forms already recognized by the candidate parser.
        // Keep all other raw-mode and scientific catalog vocabulary rules unchanged.
        if (value != null && "Y_LINKED".equals(value.trim().toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_').replace(' ', '_'))) return "Y_LINKED";
        return switch (value == null ? "" : value) {
            case "AD", "AUTOSOMAL_DOMINANT" -> "AUTOSOMAL_DOMINANT";
            case "AR", "AUTOSOMAL_RECESSIVE" -> "AUTOSOMAL_RECESSIVE";
            case "XD", "X_DOMINANT", "X_LINKED_DOMINANT" -> "X_LINKED_DOMINANT";
            case "XR", "X_RECESSIVE", "X_LINKED_RECESSIVE" -> "X_LINKED_RECESSIVE";
            case "Y_LINKED" -> "Y_LINKED";
            default -> null;
        };
    }

    static String normalizeCatalogMoi(String value) {
        return switch (value == null ? "" : value) {
            case "AD", "AUTOSOMAL_DOMINANT" -> "AUTOSOMAL_DOMINANT";
            case "AR", "AUTOSOMAL_RECESSIVE" -> "AUTOSOMAL_RECESSIVE";
            case "X_LINKED_DOMINANT" -> "X_LINKED_DOMINANT";
            case "X_LINKED_RECESSIVE" -> "X_LINKED_RECESSIVE";
            case "Y_LINKED" -> "Y_LINKED";
            default -> null;
        };
    }

    private static String normalizeNcbiGeneId(String value) {
        if (value == null) { return null; }
        String id = value.startsWith("GeneID:") ? value.substring("GeneID:".length()) : value;
        return matches(id, "[1-9][0-9]*") ? id : null;
    }

    private static List<JsonNode> readResource(ObjectMapper mapper, JsonNode identity, Path path) throws IOException {
        for (String field : List.of("resourceId", "resourceVersion", "sourceUrl", "retrievedAt",
                "fileDate", "rawSha256", "headerSha256", "derivedSha256")) {
            require(text(value(identity, field)), "BINDING_RESOURCE_IDENTITY_REQUIRED:" + field);
        }
        require(utcDateTime(value(identity, "retrievedAt")), "BINDING_RESOURCE_RETRIEVAL_UTC_REQUIRED");
        require(PROFILE_VERSION.equals(value(identity, "parserVersion")), "BINDING_RESOURCE_PARSER_INVALID");
        for (String field : List.of("rawSha256", "headerSha256", "derivedSha256")) {
            require(matches(value(identity, field), "[0-9a-f]{64}"), "BINDING_RESOURCE_HASH_INVALID:" + field);
        }
        require(identity.path("derivedRecordCount").isIntegralNumber(), "BINDING_RESOURCE_COUNT_INVALID");
        byte[] bytes = Files.readAllBytes(path);
        require(sha256(bytes).equals(value(identity, "derivedSha256")), "BINDING_RESOURCE_HASH_MISMATCH");
        JsonNode rows = mapper.readTree(bytes);
        if (!(rows != null && rows.isArray() && rows.size() == identity.path("derivedRecordCount").asInt())) { throw new IllegalArgumentException("BINDING_RESOURCE_COUNT_MISMATCH"); }
        List<JsonNode> result = new ArrayList<>();
        rows.forEach(result::add);
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", exception);
        }
    }

    private static boolean validProvenance(HypothesisProvenance source) {
        return source != null && text(source.source()) && text(source.version()) && text(source.recordLocator());
    }

    private static boolean localPath(String value) {
        value = value == null ? null : value.strip();
        return value != null && (value.matches("^[A-Za-z]:[\\\\/].*")
                || value.startsWith("\\\\") || value.startsWith("/")
                || value.regionMatches(true, 0, "file:", 0, 5));
    }

    private static boolean utcDateTime(String value) {
        if (!matches(value, "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
                + "(?:\\.[0-9]{1,9})?(?:Z|\\+00:00)")) { return false; }
        try {
            OffsetDateTime retrieved = OffsetDateTime.parse(value);
            return retrieved.getYear() > 0 && ZoneOffset.UTC.equals(retrieved.getOffset());
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    static PatientDiseaseHypothesis safeHypothesis(PatientDiseaseHypothesis hypothesis) {
        if (hypothesis == null || hypothesis.provenance() == null) { return hypothesis; }
        HypothesisProvenance source = hypothesis.provenance();
        return new PatientDiseaseHypothesis(hypothesis.candidateId(), hypothesis.hypothesisId(), hypothesis.hgncId(),
                hypothesis.mondoId(), hypothesis.moi(), new HypothesisProvenance(
                localPath(source.source()) ? "[LOCAL_PATH_REJECTED]" : source.source(),
                localPath(source.version()) ? "[LOCAL_PATH_REJECTED]" : source.version(),
                localPath(source.recordLocator()) ? "[LOCAL_PATH_REJECTED]" : source.recordLocator()));
    }

    private static boolean relationshipProvenanceReady(JsonNode row) {
        return text(value(row, "classificationDate")) && text(value(row, "gcep"))
                && text(value(row, "reportLocator"));
    }

    private static String value(JsonNode node, String field) {
        return node == null || !node.path(field).isTextual() ? null : node.path(field).asText();
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static boolean matches(String value, String pattern) { return value != null && value.matches(pattern); }
    private static void require(boolean condition, String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
    private static List<JsonNode> copyNodes(List<JsonNode> values) {
        return values.stream().map(node -> (JsonNode) node.deepCopy()).toList();
    }

    public record HypothesisProvenance(String source, String version, String recordLocator) { }

    /** The caller, never the catalog, supplies every patient-specific hypothesis. */
    public record PatientDiseaseHypothesis(String candidateId, String hypothesisId, String hgncId,
                                           String mondoId, String moi, HypothesisProvenance provenance) { }

    public record BindingProvenance(PatientDiseaseHypothesis patientHypothesis,
                                     JsonNode maneResourceIdentity, JsonNode clingenResourceIdentity,
                                     List<JsonNode> crosswalkRecords, List<JsonNode> relationshipRecords,
                                     String intakePolicyId, String intakePolicyVersion) {
        public BindingProvenance {
            maneResourceIdentity = maneResourceIdentity.deepCopy();
            clingenResourceIdentity = clingenResourceIdentity.deepCopy();
            crosswalkRecords = copyNodes(crosswalkRecords);
            relationshipRecords = copyNodes(relationshipRecords);
        }
        @Override public JsonNode maneResourceIdentity() { return maneResourceIdentity.deepCopy(); }
        @Override public JsonNode clingenResourceIdentity() { return clingenResourceIdentity.deepCopy(); }
        @Override public List<JsonNode> crosswalkRecords() { return copyNodes(crosswalkRecords); }
        @Override public List<JsonNode> relationshipRecords() { return copyNodes(relationshipRecords); }
    }

    public record DiseaseContextBinding(String candidateId, String hypothesisId, String hgncId,
                                         String symbol, String mondoId, String diseaseLabel, String normalizedMoi,
                                         String clingenClassification, String clingenClassificationDate,
                                         String clingenGcep, String clingenReportLocator,
                                         String status, String reasonCode, BindingProvenance provenance) { }

    public record BindingBlocker(String status, String reasonCode, List<PatientDiseaseHypothesis> hypotheses,
                                  JsonNode maneResourceIdentity, JsonNode clingenResourceIdentity) {
        public BindingBlocker {
            hypotheses = List.copyOf(hypotheses);
            maneResourceIdentity = maneResourceIdentity.deepCopy();
            clingenResourceIdentity = clingenResourceIdentity.deepCopy();
        }
        @Override public JsonNode maneResourceIdentity() { return maneResourceIdentity.deepCopy(); }
        @Override public JsonNode clingenResourceIdentity() { return clingenResourceIdentity.deepCopy(); }
    }

    /** Bindings are individual READY or NOT_READY hypothesis outcomes; no hypothesis means no binding. */
    public record CandidateBindingResult(String candidateId, List<DiseaseContextBinding> bindings,
                                         List<BindingBlocker> blockers) {
        public CandidateBindingResult { bindings = List.copyOf(bindings); blockers = List.copyOf(blockers); }
    }
}
