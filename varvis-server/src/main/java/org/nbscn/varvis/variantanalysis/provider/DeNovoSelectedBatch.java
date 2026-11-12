package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.springframework.lang.Nullable;

/** Task-scoped material snapshot; optional bounded public citation supply follows selected-source gates. */
public final class DeNovoSelectedBatch {
    public static final String VERSION = "denovo-selected-batch-1.9.0";
    public static final List<String> CRITERIA = List.of("ACMG_AMP_2015:PS2", "ACMG_AMP_2015:PM6");
    private static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("DENOVO_MATERIAL_");
    private final ObjectMapper mapper;
    private final AcmgSpecificationSelector selector;
    private final ObjectNode catalog;
    private final ArrayNode selectorPolicies;
    private final DeNovoSpecialEvent.RegionSupply regions;
    private final Map<String, DeNovoApplicabilityPolicy> policies;
    private final List<DeNovoStructuredReport> reports;
    private final ObjectNode identity, originals;
    private final DeNovoApplicabilityPolicy.Use use;
    private DeNovoLiteratureEvidence literature;
    private final DeNovoLiteratureReader literatureReader;
    private final DeNovoLiteratureSupply supply;
    private final ArrayNode sourceDocuments, citationRecords;
    private final Map<String,byte[]> literatureOriginals;
    private ObjectNode supplySnapshot;
    private boolean sourcesPrepared;
    private int evaluations;

    private DeNovoSelectedBatch(ObjectMapper mapper, AcmgSpecificationSelector selector, ObjectNode catalog, ArrayNode selectorPolicies,
            Map<String, DeNovoApplicabilityPolicy> policies, List<DeNovoStructuredReport> reports,
            ObjectNode identity, ObjectNode originals, DeNovoApplicabilityPolicy.Use use, DeNovoLiteratureEvidence literature,
            DeNovoLiteratureReader literatureReader, DeNovoLiteratureSupply supply, ArrayNode sourceDocuments,
            ArrayNode citationRecords, Map<String,byte[]> literatureOriginals) {
        this.mapper = mapper; this.selector = selector; this.catalog = catalog.deepCopy(); this.selectorPolicies = selectorPolicies.deepCopy();
        this.regions = DeNovoA10RegionSupply.fromAssessor(mapper, SexChromosomeRegionResolver.CATALOG_SHA256,
                allele -> SexChromosomeRegionResolver.assess(mapper, allele));
        this.policies = Map.copyOf(policies);
        this.reports = List.copyOf(reports); this.identity = identity.deepCopy(); this.originals = originals.deepCopy(); this.use = use;
        this.literature = literature;
        this.literatureReader=literatureReader; this.supply=supply; this.sourceDocuments=sourceDocuments.deepCopy();
        this.citationRecords=citationRecords.deepCopy(); this.literatureOriginals=Map.copyOf(literatureOriginals);
    }

    /** Legacy bundles resolve their pinned files below the manifest directory. */
    public static DeNovoSelectedBatch load(ObjectMapper mapper, Path manifest, String expectedSha,
            DeNovoApplicabilityPolicy.Use use) throws IOException {
        return load(mapper, manifest, expectedSha, use, null);
    }
    public static DeNovoSelectedBatch load(ObjectMapper mapper, Path manifest, String expectedSha,
            DeNovoApplicabilityPolicy.Use use, DeNovoLiteratureReader literatureReader) throws IOException {
        return load(mapper,manifest,expectedSha,use,literatureReader,null);
    }
    public static DeNovoSelectedBatch load(ObjectMapper mapper, Path manifest, String expectedSha,
            DeNovoApplicabilityPolicy.Use use, DeNovoLiteratureReader literatureReader, DeNovoLiteratureSupply supply) throws IOException {
        return load(mapper, manifest, expectedSha, use, literatureReader, supply, null);
    }

    /** A deployment may declare a common directory for the manifest and shared pinned originals. */
    public static DeNovoSelectedBatch load(ObjectMapper mapper, Path manifest, String expectedSha,
            DeNovoApplicabilityPolicy.Use use, DeNovoLiteratureReader literatureReader, DeNovoLiteratureSupply supply,
            @Nullable Path materialRoot) throws IOException {
        if (supply!=null && literatureReader==null) throw JSON.invalid("LITERATURE_READER_REQUIRED_FOR_SUPPLY");
        Path actual = manifest.toRealPath();
        Path root = materialRoot == null ? actual.getParent() : materialRoot.toRealPath();
        if (root == null || !Files.isDirectory(root) || !actual.startsWith(root)) throw JSON.invalid("PATH_OUTSIDE_ROOT");
        var reader = new Reader(root, mapper);
        byte[] raw = reader.read(actual, expectedSha);
        ObjectNode n = JSON.parse(mapper, raw);
        JSON.shape(n, "schemaVersion:s materialClass:s reports:a phenotypePolicies:a catalog:o selectorPolicies:o sources:a literature:!a citationRecords:!a");
        JSON.equal(n, "schemaVersion", "denovo-selected-materials-v1");
        JSON.enumeration(n, "materialClass", "SYNTHETIC SOURCE_ASSESSED_RESEARCH");
        if (use == DeNovoApplicabilityPolicy.Use.RESEARCH && "SYNTHETIC".equals(n.path("materialClass").asText()))
            throw JSON.invalid("SYNTHETIC_NOT_FOR_RESEARCH");
        if (n.path("sources").size() > 100 || n.path("reports").size() > 100 || n.path("phenotypePolicies").size() > 100)
            throw JSON.invalid("COUNT_LIMIT");
        Map<String, byte[]> sources = new TreeMap<>();
        for (var source : n.path("sources")) {
            JSON.shape(source, "sourceId:s path:s sha256:s");
            if (sources.putIfAbsent(source.path("sourceId").asText(), reader.readBinding(source)) != null)
                throw JSON.invalid("SOURCE_ID_DUPLICATE");
        }
        Map<String, DeNovoApplicabilityPolicy> policies = new TreeMap<>();
        for (var binding : n.path("phenotypePolicies")) {
            var policy = DeNovoApplicabilityPolicy.load(reader.readBinding(binding), binding.path("sha256").asText(), sources, use);
            if (policies.putIfAbsent(policy.sha256(), policy) != null) throw JSON.invalid("POLICY_DUPLICATE");
        }
        List<DeNovoStructuredReport> reports = new ArrayList<>();
        for (var binding : n.path("reports")) reports.add(DeNovoStructuredReport.read(reader.readBinding(binding), binding.path("sha256").asText()));
        ObjectNode catalog = JSON.parse(mapper, reader.readBinding(n.path("catalog")));
        ObjectNode policyDocument = JSON.parse(mapper, reader.readBinding(n.path("selectorPolicies")));
        JSON.shape(policyDocument, "policies:a");
        if (catalog.path("entries").size() > 1000 || policyDocument.path("policies").size() > 200) throw JSON.invalid("COUNT_LIMIT");
        // Catalog source declarations must resolve to actual retained originals, not only a claimed digest.
        for (var source : catalog.path("sources")) {
            byte[] bytes = sources.get(source.path("sourceId").asText());
            if (bytes == null || !FamilyDeNovoJson.sha256(bytes).equals(source.path("sha256").asText()))
                throw JSON.invalid("CATALOG_SOURCE_UNRESOLVED");
        }
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var selector = AcmgSpecificationSelector.forDeNovoSelected(mapper, catalog, adapter.canonicalSha256(catalog),
                (ArrayNode) policyDocument.path("policies"));
        if (selector.configurationIssue() != null) throw JSON.invalid(selector.configurationIssue());
        var identity = mapper.createObjectNode().put("version", VERSION).put("manifestSha256", expectedSha)
                .put("use", use.name()).put("calculatorVersion", DeNovoEvidenceCalculator.VERSION)
                .put("qualifierVersion", DeNovoLocalEvidenceEvaluator.VERSION).put("phenotypeVersion", DeNovoApplicabilityPolicy.VERSION)
                .put("readerVersion", DeNovoStructuredReport.VERSION).put("modelCalls", 0)
                .put("publicExplanationVersion", DeNovoResultSummary.VERSION)
                .put("modelValidation", "DEFERRED_NOT_CONFIGURED").put("literatureQualification", "NOT_YET_CONNECTED");
        identity.putObject("sexChromosomeRegion").put("schemaVersion", "sex-chromosome-region-v1")
                .put("methodVersion", SexChromosomeRegionResolver.VERSION).put("sourceCatalogSha256", SexChromosomeRegionResolver.CATALOG_SHA256)
                .put("consumer", "DeNovoA10RegionSupply").put("clinicalQualificationGranted", false).put("patientPloidyInferred", false);
        DeNovoLiteratureEvidence literature = null;
        if (literatureReader != null) {
            var modelBinding = literatureReader.modelBinding();
            if (modelBinding != null) identity.set("modelArtifactReplay", modelBinding);
        }
        var documents = mapper.createArrayNode(); Map<String, byte[]> rawSources = new TreeMap<>();
        var citationRecords=mapper.createArrayNode();
        if (n.path("citationRecords").size()>100) throw JSON.invalid("CITATION_RECORD_LIMIT");
        for (var binding:n.path("citationRecords")) {
            JSON.shape(binding,"sourceId:s vcv:s"); byte[] bytes=sources.get(binding.path("sourceId").asText());
            if (bytes==null || !binding.path("vcv").asText().matches("VCV[0-9]{9}")) throw JSON.invalid("CITATION_SOURCE_MISSING");
            String pin=FamilyDeNovoJson.sha256(bytes); rawSources.put(pin,bytes);
            citationRecords.addObject().put("sha256",pin).set("vcv",binding.path("vcv"));
        }
        if (n.has("literature") || literatureReader!=null && literatureReader.hasCandidates()) {
            if (n.path("literature").size() > 32) throw JSON.invalid("LITERATURE_DOCUMENT_LIMIT");
            Set<String> ids = new HashSet<>();
            for (var entry : n.path("literature")) {
                JSON.shape(entry, "sourceId:s pmcid:s alleles:a reviewedPaper:!o");
                if (!ids.add(entry.path("sourceId").asText()) || !entry.path("pmcid").asText().matches("PMC[1-9][0-9]*"))
                    throw JSON.invalid("LITERATURE_IDENTITY_INVALID");
                byte[] bytes = sources.get(entry.path("sourceId").asText());
                if (bytes == null) throw JSON.invalid("LITERATURE_SOURCE_MISSING");
                String sourcePin = FamilyDeNovoJson.sha256(bytes); rawSources.put(sourcePin, bytes);
                var document = documents.addObject().put("sourceId", entry.path("sourceId").asText()).put("pmcid", entry.path("pmcid").asText()).put("sha256", sourcePin);
                if (entry.has("reviewedPaper")) {
                    var review = entry.path("reviewedPaper");
                    JSON.shape(review, "sourceId:s mediaSourceIds:a followUpSourceIds:!a");
                    byte[] reviewed = sources.get(review.path("sourceId").asText());
                    if (reviewed == null || review.path("mediaSourceIds").size() > 16)
                        throw JSON.invalid("LITERATURE_REVIEW_SOURCE_MISSING_OR_LIMIT");
                    String reviewPin = FamilyDeNovoJson.sha256(reviewed);
                    rawSources.put(reviewPin, reviewed);
                    document.put("reviewedPaperSha256", reviewPin);
                    var mediaPins = document.putArray("reviewedMediaSha256s");
                    for (var media : review.path("mediaSourceIds")) {
                        byte[] image = sources.get(media.asText());
                        if (!media.isTextual() || image == null) throw JSON.invalid("LITERATURE_REVIEW_MEDIA_MISSING");
                        String imagePin = FamilyDeNovoJson.sha256(image);
                        mediaPins.add(imagePin);
                        rawSources.put(imagePin, image);
                    }
                    if (review.has("followUpSourceIds")) {
                        var followUps = review.path("followUpSourceIds");
                        if (followUps.size() > 16) throw JSON.invalid("LITERATURE_REVIEW_FOLLOW_UP_LIMIT");
                        var followUpPins = document.putArray("reviewedFollowUpSha256s");
                        for (var followUp : followUps) {
                            byte[] paper = sources.get(followUp.asText());
                            if (!followUp.isTextual() || paper == null) throw JSON.invalid("LITERATURE_REVIEW_FOLLOW_UP_MISSING");
                            String pin = FamilyDeNovoJson.sha256(paper);
                            followUpPins.add(pin);
                            rawSources.put(pin, paper);
                        }
                    }
                }
                var alleles = document.putArray("alleles");
                if (entry.path("alleles").size() > 100) throw JSON.invalid("LITERATURE_ALLELE_LIMIT");
                for (var allele : entry.path("alleles")) {
                    JSON.shape(allele, "sourceId:s vcv:s");
                    byte[] vcv = sources.get(allele.path("sourceId").asText());
                    if (vcv == null || !allele.path("vcv").asText().matches("VCV[0-9]{9}")) throw JSON.invalid("LITERATURE_ALLELE_SOURCE_MISSING");
                    String vcvPin = FamilyDeNovoJson.sha256(vcv); rawSources.put(vcvPin, vcv);
                    alleles.addObject().put("sha256", vcvPin).put("vcv", allele.path("vcv").asText());
                }
            }
            if(literatureReader!=null) literatureReader.appendCandidateDocuments(documents,rawSources,citationRecords);
            for(var entry:rawSources.entrySet()) reader.remember(entry.getValue(),entry.getKey());
            var read = literatureReader == null ? new DeNovoLiteratureReader.Read(null, null, null, "DENOVO_LITERATURE_READER_NOT_CONFIGURED", false)
                    : literatureReader.read(documents, rawSources, expectedSha);
            read = validateLiteratureUse(read, use);
            if (read.original() != null) reader.remember(read.original(), read.sha256());
            literature = new DeNovoLiteratureEvidence(read);
            identity.put("literatureVersion", DeNovoLiteratureEvidence.VERSION).put("literatureReaderVersion", literatureReader == null ? DeNovoLiteratureReader.VERSION : literatureReader.version())
                    .put("literatureQualification", read.issue() == null ? "SOURCE_REPLAYED_FOR_PER_TARGET_QUALIFICATION" : read.issue())
                    .put("modelValidation", modelStatus(read));
            if (read.sha256() != null) identity.put("literatureSourceResultSha256", read.sha256());
        }
        if (supply!=null) {
            identity.set("sourceSupply",supply.identity());
            identity.put("literatureQualification","SELECTED_SOURCE_SUPPLY_CONFIGURED");
        }
        return new DeNovoSelectedBatch(mapper, selector, catalog, (ArrayNode) policyDocument.path("policies"), policies, reports, identity, reader.originals, use, literature,
                literatureReader,supply,documents,citationRecords,rawSources);
    }

    public ObjectNode identity() { return identity.deepCopy(); }
    public ObjectNode originals() { return originals.deepCopy(); }
    public int evaluations() { return evaluations; }
    public int sourceSupplyCalls() { return supply==null?0:supply.executions(); }
    public int publicNetworkRequests() { return supply==null?0:supply.networkRequests(); }
    public ObjectNode sourceSupplySnapshot() { return supplySnapshot==null?mapper.createObjectNode():supplySnapshot.deepCopy(); }

    /** Exact published scope may establish a selected background when gene-validity coverage is absent. */
    public record PublishedBackground(String hgncId, String symbol, String mondoId, String diseaseLabel, String moi,
                                      String sourceUrl, String specificationVersion, String approvedOn, String panel,
                                      String releaseSha256, String phenotypePolicySha256) {}

    public List<PublishedBackground> publishedBackgrounds(List<String> criteria, String dataClass) {
        if (use == DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION && !"SYNTHETIC".equals(dataClass)) return List.of();
        var backgrounds = new LinkedHashSet<PublishedBackground>();
        var canonical = new RuntimeEvidenceV2Adapter(mapper);
        for (var entry : catalog.path("entries")) {
            if (!CRITERIA.contains(entry.path("criterionId").asText()) || !criteria.contains(entry.path("criterionId").asText())
                    || !reviewedSpecializedEntry(entry)) continue;
            var binding = entry.path("policyBinding");
            var matching = selectorPolicies.valueStream().filter(p -> binding.path("policyId").equals(p.path("policyId"))
                    && binding.path("policyVersion").equals(p.path("policyVersion"))).toList();
            if (matching.size() != 1) continue;
            var selected = matching.get(0);
            if (!entry.path("scope").equals(selected.path("scope"))
                    || !canonical.canonicalSha256(selected).equals(binding.path("policySha256").asText())
                    || selected.path("criterionIds").valueStream().noneMatch(entry.path("criterionId")::equals)
                    || !"REVIEWED_IMPLEMENTED".equals(selected.at("/review/status").asText())
                    || !nonblank(selected.at("/review/reviewId"))) continue;
            var phenotype = policies.get(selected.path("denovoPhenotypePolicySha256").asText());
            if (phenotype == null || !compatibleCalculatorVersion(selected, phenotype)) continue;
            var events = phenotype.specialEvents();
            if (events == null || !events.publishedPoints()) continue;
            var scope = entry.path("scope");
            var snapshot = phenotype.snapshot();
            var phenotypeScope = snapshot.path("scope");
            if (!"NATIVE_NONPROJECTED_SNV".equals(scope.path("variantType").asText())
                    || !"EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText())
                    || !scope.path("hgncId").equals(phenotypeScope.path("hgncId"))
                    || !scope.path("mondoId").equals(phenotypeScope.path("diseaseId"))
                    || !"AUTOSOMAL_DOMINANT".equals(scope.path("moi").asText())) continue;
            var sources = catalog.path("sources").valueStream().filter(source ->
                    events.sourceIds().contains(source.path("sourceId").asText())
                            && entry.path("sourceIds").valueStream().anyMatch(source.path("sourceId")::equals)
                            && source.path("url").asText().matches("https://cspec\\.(genome\\.network|clinicalgenome\\.org)/cspec/ui/svi/doc/GN[0-9]+(\\?version=[0-9.]+)?")
                            && snapshot.path("sources").valueStream().anyMatch(pin -> source.path("sourceId").equals(pin.path("sourceId"))
                                    && source.path("sha256").equals(pin.path("sha256")))).toList();
            if (sources.size() == 1) readPublishedBackground(scope, sources.get(0), phenotype.sha256()).ifPresent(backgrounds::add);
        }
        return List.copyOf(backgrounds);
    }

    private java.util.Optional<PublishedBackground> readPublishedBackground(JsonNode scope, JsonNode source, String policyPin) {
        String releasePin = source.path("sha256").asText();
        String url = source.path("url").asText(), version = source.path("version").asText();
        JsonNode release;
        try {
            release = JSON.parse(mapper, Base64.getDecoder().decode(originals.path(releasePin).asText())).path("data");
        } catch (IllegalArgumentException invalid) {
            return java.util.Optional.empty();
        }
        var description = release.path("entContent");
        String namespace = description.path("namespace").asText(), releaseVersion = description.path("version").asText();
        if (!namespace.matches("GN[0-9]+") || !(url.endsWith("/" + namespace) || url.endsWith("/" + namespace + "?version=" + version))
                || !(releaseVersion.equals(version) || releaseVersion.equals(version + ".0"))
                || !nonblank(description.path("approvedOn")) || !nonblank(description.path("shortTitle")))
            return java.util.Optional.empty();
        String diseaseId = scope.path("mondoId").asText();
        var rows = new LinkedHashSet<PublishedBackground>();
        for (var rules : release.at("/ld/RuleSet")) for (var gene : rules.at("/entContent/genes"))
            for (var disease : gene.path("diseases")) if (diseaseId.equals(disease.path("preferredMondoId").asText())
                    && "Autosomal dominant inheritance".equals(disease.path("preferredModeOfInheritance").asText())
                    && nonblank(gene.path("gene")) && nonblank(disease.path("preferredTitle")))
                rows.add(new PublishedBackground(scope.path("hgncId").asText(), gene.path("gene").asText(), diseaseId,
                        disease.path("preferredTitle").asText(), scope.path("moi").asText(), url, version,
                        description.path("approvedOn").asText(), description.path("shortTitle").asText(), releasePin, policyPin));
        return rows.size() == 1 ? java.util.Optional.of(rows.iterator().next()) : java.util.Optional.empty();
    }
    public ObjectNode sourceTaskStatus(String id) throws IOException {
        if (supply==null) throw new IOException("DENOVO_TASK_NOT_CONFIGURED");
        return supply.taskStatus(id);
    }
    public void explicitSourceRetry(String id) {
        if(sourcesPrepared) throw new IllegalStateException("DENOVO_TASK_RETRY_AFTER_PREPARATION");
        if(supply!=null) supply.explicitRetry(id);
    }

    /** Use the same policy and event calculator as the saved evaluation on the local snapshot. */
    public Set<String> localDiscoveryCandidates(SelectedEvidencePreparation selected, List<String> criteria) {
        var requested = CRITERIA.stream().filter(criteria::contains).toList();
        Set<String> missing = new LinkedHashSet<>();
        if (requested.isEmpty()) return missing;
        for (var candidate : selected.assembly().candidates()) for (var attempt : candidate.attempts()) {
            if (!attempt.blockers().isEmpty() || attempt.context() == null) continue;
            var candidateId = candidate.candidateId();
            var context = selected.context(candidateId, attempt.hypothesisId());
            var local = evaluateBound(context, selected.formalCandidate(candidateId), requested);
            if (requiresSourceDiscovery(local)) missing.add(candidateId);
        }
        return missing;
    }

    static boolean requiresSourceDiscovery(JsonNode local) {
        if (!local.path("qualifiedResult").isObject()) return false;
        for (var criterion : local.path("criteria"))
            if (Set.of("MET", "NOT_APPLICABLE").contains(criterion.at("/evaluationResult/status").asText())) return false;
        return true;
    }

    /** Called once after formal source/selected context gates, before any per-candidate evaluation. */
    public synchronized void prepareSelectedSources(SelectedEvidencePreparation selected) {
        prepareSelectedSources(selected, CRITERIA);
    }

    public synchronized void prepareSelectedSources(SelectedEvidencePreparation selected, List<String> criteria) {
        if (sourcesPrepared) return;
        sourcesPrepared=true;
        if (literature!=null && literatureReader!=null) literature.retainReviewed(literatureReader);
        if (supply==null) return;
        var targets=mapper.createArrayNode(); Set<String> seen=new HashSet<>();
        var missing = localDiscoveryCandidates(selected, criteria);
        for (var candidate:selected.assembly().candidates()) for (var attempt:candidate.attempts()) try {
            if (!missing.contains(candidate.candidateId())) continue;
            var context=selected.context(candidate.candidateId(),attempt.hypothesisId());
            if (!DeNovoLocalEvidenceEvaluator.contextIssues(context,context.at("/variant/evidenceQueryAllele"),false).isEmpty()) continue;
            var target=mapper.createObjectNode(); target.set("hgncId",context.at("/gene/hgncId")); target.set("allele",context.at("/variant/evidenceQueryAllele"));
            if (seen.add(target.toString())) targets.add(target);
        } catch (RuntimeException unavailable) { /* Original selected-context blockers remain on each candidate. */ }
        if (targets.isEmpty()) { supplySnapshot=mapper.createObjectNode().put("status","NO_ELIGIBLE_SELECTED_TARGETS"); return; }
        var prepared=supply.prepare(targets,citationRecords,sourceDocuments,literatureOriginals); supplySnapshot=prepared.snapshot();
        if (prepared.issue()!=null) {
            if (literature==null) literature=new DeNovoLiteratureEvidence(new DeNovoLiteratureReader.Read(null,null,null,prepared.issue(),false));
            return;
        }
        var read=literatureReader.read(prepared.documents(),prepared.originals(),identity.path("manifestSha256").asText());
        read = validateLiteratureUse(read, use);
        identity.put("modelValidation", modelStatus(read));
        if (read.issue()!=null) {
            supplySnapshot.put("reasonCode",read.issue());
            if (literature==null) literature=new DeNovoLiteratureEvidence(read);
            return;
        }
        var additional=new TreeMap<>(prepared.originals());
        if (read.original()!=null) additional.put(read.sha256(),read.original());
        long retained=0;
        for (var item:originals.properties()) retained+=Base64.getDecoder().decode(item.getValue().asText()).length;
        for (var entry:additional.entrySet()) if (!originals.has(entry.getKey())) retained+=entry.getValue().length;
        if (retained>16777216) {
            supplySnapshot.put("reasonCode","DENOVO_SUPPLY_OUTPUT_LIMIT");
            if (literature==null) literature=new DeNovoLiteratureEvidence(new DeNovoLiteratureReader.Read(null,null,null,"DENOVO_SUPPLY_OUTPUT_LIMIT",false));
            return;
        }
        for (var entry:additional.entrySet()) originals.put(entry.getKey(),Base64.getEncoder().encodeToString(entry.getValue()));
        literature=new DeNovoLiteratureEvidence(read);
        literature.retainReviewed(literatureReader);
    }

    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId) {
        return evaluate(selected,candidateId,hypothesisId,CRITERIA);
    }
    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId, List<String> criteria) {
        // Formal selected preparation enforces original source, normalization and confirmed context gates first.
        ObjectNode context = selected.context(candidateId, hypothesisId);
        return evaluateBound(context, selected.formalCandidate(candidateId), criteria);
    }

    ObjectNode evaluateBound(ObjectNode context, ObjectNode formal) {
        return evaluateBound(context,formal,CRITERIA);
    }
    ObjectNode evaluateBound(ObjectNode context, ObjectNode formal, List<String> selectedCriteria) {
        ObjectNode out = mapper.createObjectNode().put("version", VERSION);
        if (literature != null) literature.appendCoreMaterials(out, context);
        if (use == DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION && !"SYNTHETIC".equals(context.path("dataClass").asText()))
            return finish(out, null, "DENOVO_SYNTHETIC_MATERIAL_CONTEXT_FORBIDDEN", selectedCriteria);
        var scopeIssues = DeNovoLocalEvidenceEvaluator.contextIssues(context, context.at("/variant/evidenceQueryAllele"), literature == null && supply==null);
        if (!scopeIssues.isEmpty()) return finish(out, null, scopeIssues.get(0), selectedCriteria);
        var selections = out.putArray("specificationSelections");
        var calculationBindings = out.putArray("calculationBindings");
        String pin = null;
        boolean onlySpecializedAdoptions = true;
        for (String criterion : CRITERIA) {
            if (!selectedCriteria.contains(criterion)) continue;
            var selection = selector.select(context, criterion); selections.add(selection.receipt());
            var chosen = selection.selectedPolicy();
            if (chosen == null && "DENOVO_SPECIALIZED_REQUIREMENTS_NOT_IMPLEMENTED".equals(selection.receipt().path("reasonCode").asText())) {
                var adoption = out.withArray("specializedPolicyAdoptions").addObject()
                        .put("schemaVersion", "denovo-specialized-policy-adoption-v1").put("criterionId", criterion)
                        .put("globalSelectionId", selection.receipt().path("selectionId").asText());
                chosen = specializedPolicy(context, criterion, selection.receipt(), adoption);
                if (chosen == null) return finish(out, null, adoption.path("reasonCode").asText(), selectedCriteria);
            } else onlySpecializedAdoptions = false;
            if (chosen == null) return finish(out, null, selection.receipt().path("reasonCode").asText("DENOVO_SPECIFICATION_UNAVAILABLE"), selectedCriteria);
            String next = chosen.path("denovoPhenotypePolicySha256").asText();
            var phenotypePolicy = policies.get(next);
            if (phenotypePolicy == null || !compatibleCalculatorVersion(chosen, phenotypePolicy))
                return finish(out, null, "DENOVO_SELECTED_POLICY_BINDING_UNAVAILABLE", selectedCriteria);
            String declaredVersion = chosen.path("denovoCalculatorVersion").asText();
            calculationBindings.addObject().put("criterionId", criterion).put("declaredCalculatorVersion", declaredVersion)
                    .put("runtimeCalculatorVersion", DeNovoEvidenceCalculator.VERSION)
                    .put("compatibilityMode", DeNovoEvidenceCalculator.VERSION.equals(declaredVersion) ? "EXACT_VERSION" : "LEGACY_AD_1_0_1_1")
                    .put("selectorPolicySha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(chosen)).put("phenotypePolicySha256", next);
            if (pin != null && !pin.equals(next)) return finish(out, null, "DENOVO_SHARED_POLICY_CONFLICT", selectedCriteria);
            pin = next;
        }
        if (pin == null) return finish(out, null, "DENOVO_SELECTED_POLICY_BINDING_UNAVAILABLE", selectedCriteria);
        var policy = policies.get(pin);
        if (!"GENERAL_ALLOWED".equals(policy.snapshot().path("expertPolicyStatus").asText())
                && !(onlySpecializedAdoptions && implementedExpert(policy)))
            return finish(out, null, "DENOVO_EXPERT_REQUIREMENTS_UNIMPLEMENTED", selectedCriteria);
        evaluations++;
        var result = new DeNovoLocalEvidenceEvaluator(mapper, regions).evaluateBound(context, formal, policy, reports, literature);
        for (var binding : calculationBindings) if (binding instanceof ObjectNode object)
            object.put("executedCalculatorVersion", result.result().calculatorVersion());
        out.set("qualifiedResult", mapper.valueToTree(result));
        if (supply!=null) {
            var issues=out.putArray("sourceSupplyIssues");
            if (supplySnapshot==null) issues.add("DENOVO_SUPPLY_NOT_PREPARED");
            else if (supplySnapshot.has("reasonCode")) issues.add(supplySnapshot.path("reasonCode"));
            else for (var t:supplySnapshot.path("targets")) if (t.path("hgncId").equals(context.at("/gene/hgncId"))
                    && t.path("allele").equals(context.at("/variant/evidenceQueryAllele"))) issues.addAll((ArrayNode)t.path("reasonCodes"));
        }
        return finish(out, result.result(), null, selectedCriteria);
    }

    private boolean compatibleCalculatorVersion(JsonNode declared, DeNovoApplicabilityPolicy phenotype) {
        String version = declared.path("denovoCalculatorVersion").asText();
        if (DeNovoEvidenceCalculator.VERSION.equals(version)) return true;
        JsonNode raw = phenotype.snapshot();
        return Set.of("denovo-event-calculator-1.0.0", "denovo-event-calculator-1.1.0").contains(version)
                && "AD".equals(DeNovoLocalEvidenceEvaluator.inheritance(declared.at("/scope/moi").asText()))
                && "AD".equals(raw.at("/scope/inheritance").asText()) && "GENERAL_ALLOWED".equals(raw.path("expertPolicyStatus").asText())
                && !raw.has("specialEvents");
    }

    private static boolean implementedExpert(DeNovoApplicabilityPolicy phenotype) {
        var special = phenotype.specialEvents();
        return "APPLICABLE_IMPLEMENTED".equals(phenotype.snapshot().path("expertPolicyStatus").asText()) && special != null
                && Set.of(DeNovoEventPolicy.Mode.SVI_1_1_SECOND_ALLELE_REQUIRED, DeNovoEventPolicy.Mode.SVI_1_1_COMBINED_PS2,
                        DeNovoEventPolicy.Mode.TISSUE_DIFFERENTIAL_PS2, DeNovoEventPolicy.Mode.RASOPATHY_POINTS,
                        DeNovoEventPolicy.Mode.SVI_1_1_PHENOTYPE_ONLY, DeNovoEventPolicy.Mode.SVI_1_1_PUBLISHED_POINTS).contains(special.mode());
    }

    /** The global refusal remains intact. This route rechecks only its already unique, reviewed entry. */
    @Nullable private ObjectNode specializedPolicy(JsonNode context, String criterion, JsonNode receipt, ObjectNode adoption) {
        String entryId = receipt.path("catalogEntryId").asText();
        List<JsonNode> entries = new ArrayList<>();
        for (var entry : catalog.path("entries")) if (entryId.equals(entry.path("entryId").asText())) entries.add(entry);
        if (entries.size() != 1) return rejectAdoption(adoption, "DENOVO_SPECIALIZED_ENTRY_IDENTITY_INVALID");
        var entry = entries.get(0);
        if (!reviewedSpecializedEntry(entry) || !criterion.equals(entry.path("criterionId").asText())
                || !entry.path("scope").equals(receipt.path("scope")))
            return rejectAdoption(adoption, "DENOVO_SPECIALIZED_ENTRY_SOURCE_REVIEW_REQUIRED");
        JsonNode binding = entry.path("policyBinding");
        if (!nonblank(binding.path("policyId")) || !nonblank(binding.path("policyVersion")))
            return rejectAdoption(adoption, "DENOVO_SPECIALIZED_POLICY_BINDING_INVALID");
        List<ObjectNode> candidates = new ArrayList<>();
        for (var policy : selectorPolicies) if (policy instanceof ObjectNode candidate
                && binding.path("policyId").equals(candidate.path("policyId")) && binding.path("policyVersion").equals(candidate.path("policyVersion")))
            candidates.add(candidate);
        if (candidates.size() != 1) return rejectAdoption(adoption, "DENOVO_SPECIALIZED_POLICY_BINDING_INVALID");
        var candidate = candidates.get(0); String policySha = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(candidate);
        if (!policySha.equals(binding.path("policySha256").asText())) return rejectAdoption(adoption, "DENOVO_SPECIALIZED_POLICY_INTEGRITY_MISMATCH");
        if (!"REVIEWED_IMPLEMENTED".equals(candidate.at("/review/status").asText()) || !nonblank(candidate.at("/review/reviewId")))
            return rejectAdoption(adoption, "DENOVO_SPECIALIZED_POLICY_REVIEW_REQUIRED");
        Set<String> criteria = new HashSet<>();
        if (!candidate.path("criterionIds").isArray()) return rejectAdoption(adoption, "DENOVO_SPECIALIZED_POLICY_CRITERION_MISMATCH");
        for (var id : candidate.path("criterionIds")) if (!id.isTextual() || !CRITERIA.contains(id.asText()) || !criteria.add(id.asText()))
            return rejectAdoption(adoption, "DENOVO_SPECIALIZED_POLICY_CRITERION_MISMATCH");
        if (!criteria.contains(criterion) || !candidate.path("scope").equals(entry.path("scope")) || !nativeVariantScope(context, candidate.path("scope")))
            return rejectAdoption(adoption, "DENOVO_SPECIALIZED_POLICY_SCOPE_MISMATCH");
        var phenotype = policies.get(candidate.path("denovoPhenotypePolicySha256").asText());
        if (phenotype == null || !implementedExpert(phenotype) || !compatibleCalculatorVersion(candidate, phenotype))
            return rejectAdoption(adoption, "DENOVO_SPECIALIZED_PHENOTYPE_POLICY_NOT_IMPLEMENTED");
        var eventPolicy = phenotype.specialEvents();
        if (eventPolicy == null) return rejectAdoption(adoption, "DENOVO_SPECIALIZED_PHENOTYPE_POLICY_NOT_IMPLEMENTED");
        if (eventPolicy.publishedPoints()) {
            var publishedSources = new HashSet<String>();
            entry.path("sourceIds").forEach(id -> publishedSources.add(id.asText()));
            if (!publishedSources.containsAll(eventPolicy.sourceIds()))
                return rejectAdoption(adoption, "DENOVO_PUBLISHED_RULE_SOURCES_UNBOUND");
            if (!"EXACT_ACCESSIONS".equals(entry.at("/scope/transcriptMode").asText())
                    || !"NATIVE_NONPROJECTED_SNV".equals(entry.at("/scope/variantType").asText()))
                return rejectAdoption(adoption, "DENOVO_PUBLISHED_VARIANT_SCOPE_NOT_IMPLEMENTED");
        }
        JsonNode scope = phenotype.snapshot().path("scope");
        if (!scope.path("hgncId").equals(context.at("/gene/hgncId")) || !scope.path("diseaseId").equals(context.at("/disease/mondoId"))
                || !scope.path("inheritance").asText().equals(DeNovoLocalEvidenceEvaluator.inheritance(context.path("moi").asText())))
            return rejectAdoption(adoption, "DENOVO_SPECIALIZED_PHENOTYPE_SCOPE_MISMATCH");
        adoption.put("status", "SELECTED").put("catalogEntryId", entryId).put("policyId", candidate.path("policyId").asText())
                .put("policyVersion", candidate.path("policyVersion").asText()).put("policySha256", policySha)
                .put("phenotypePolicySha256", phenotype.sha256()).put("eventMode", eventPolicy.mode().name());
        return candidate.deepCopy();
    }

    private boolean reviewedSpecializedEntry(JsonNode entry) {
        return "SPECIALIZED_IMPLEMENTED".equals(entry.path("disposition").asText())
                && "COMPLETE".equals(entry.path("searchStatus").asText()) && "REVIEWED".equals(entry.at("/review/status").asText())
                && nonblank(entry.at("/review/reviewId")) && boundCatalogSources(entry.path("sourceIds"))
                && nonblank(entry.path("baseRuleRelationship"));
    }

    private boolean boundCatalogSources(JsonNode sourceIds) {
        if (!sourceIds.isArray() || sourceIds.isEmpty()) return false;
        Set<String> seen = new HashSet<>();
        for (var id : sourceIds) {
            if (!id.isTextual() || !seen.add(id.asText())) return false;
            boolean found = false;
            for (var source : catalog.path("sources")) if (id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }
        return true;
    }

    private static boolean nativeVariantScope(JsonNode context, JsonNode scope) {
        if (!"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())) return false;
        return switch (scope.path("variantType").asText()) {
            case "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT" -> true;
            case "NATIVE_NONPROJECTED_SNV" -> "SNV".equals(context.at("/variant/variantType").asText())
                    && snv(context.at("/variant/normalizedAllele")) && snv(context.at("/variant/evidenceQueryAllele"));
            default -> false;
        };
    }
    private static boolean snv(JsonNode allele) {
        return allele.path("reference").asText().matches("[ACGT]") && allele.path("alternate").asText().matches("[ACGT]")
                && !allele.path("reference").equals(allele.path("alternate"));
    }
    private static boolean nonblank(JsonNode value) { return value.isTextual() && !value.asText().isBlank(); }
    @Nullable private static ObjectNode rejectAdoption(ObjectNode receipt, String reason) { receipt.put("status", "REJECTED").put("reasonCode", reason); return null; }

    private ObjectNode finish(ObjectNode out, DeNovoEvidenceCalculator.Result result, String issue, List<String> selectedCriteria) {
        var criteria = out.putObject("criteria");
        for (String criterion : CRITERIA) {
            if (!selectedCriteria.contains(criterion)) continue;
            String code = criterion.substring(criterion.indexOf(':') + 1);
            boolean chosen = result != null && code.equals(result.code());
            String status = result == null ? "NOT_EVALUATED" : "MET".equals(result.status()) && !chosen ? "NOT_EVALUATED" : result.status();
            String reason = result == null ? issue : "MET".equals(result.status()) && !chosen ? "DENOVO_SHARED_EVENT_ASSIGNED_TO_" + result.code() : result.reasonCode();
            var entry = criteria.putObject(code).put("criterionId", criterion).put("appliedStrength", result != null && chosen ? result.strength() : "NONE");
            entry.putObject("evaluationResult").put("status", status).put("reasonCode", reason);
        }
        var summary = out.putObject("publicSummary").put("schemaVersion", "denovo-selected-facts-v1")
                .put("sourceScope", literature == null ? "CURRENT_CASE_LOCAL_REPORTS" : "CURRENT_CASE_AND_LITERATURE")
                .put("modelValidation", identity.path("modelValidation").asText("DEFERRED_NOT_CONFIGURED"))
                .put("literatureQualification", identity.path("literatureQualification").asText()).put("materialUse", use.name());
        if (result != null) summary.put("status", result.status()).put("selectedCriterion", result.code())
                .put("rawQuarterPoints", result.rawQuarterPoints()).put("effectiveQuarterPoints", result.effectiveQuarterPoints())
                .put("hasExcludedObservations", result.hasExcludedObservations()).put("eventCount", result.events().size());
        else summary.put("status", "NOT_EVALUATED").put("reasonCode", issue);
        DeNovoResultSummary.append(summary, out.path("qualifiedResult"));
        DeNovoResultSummary.appendDecisions(summary, criteria);
        DeNovoResultSummary.appendAdoption(summary, out.path("qualifiedResult"), out);
        if (out.has("coreMaterials")) summary.set("coreMaterials", out.path("coreMaterials"));
        if (out.has("sourceSupplyIssues")) summary.set("sourceSupplyIssues",out.path("sourceSupplyIssues"));
        return out;
    }

    private static DeNovoLiteratureReader.Read validateLiteratureUse(DeNovoLiteratureReader.Read read, DeNovoApplicabilityPolicy.Use use) {
        var document = read.document();
        if (document != null && use != DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION) {
            for (var entry : document.path("documents")) {
                if ("SYNTHETIC".equals(entry.at("/facts/dataClass").asText()))
                    return new DeNovoLiteratureReader.Read(null, null, null, "DENOVO_SYNTHETIC_REVIEW_NOT_FOR_RESEARCH", read.cached());
                var receipt = entry.at("/facts/modelProvenance/receipt");
                if (!receipt.isMissingNode() && !"REAL_PROVIDER".equals(receipt.path("transportKind").asText()))
                    return new DeNovoLiteratureReader.Read(null, null, null, "DENOVO_MODEL_SYNTHETIC_ARTIFACT_NOT_RESEARCH", read.cached());
            }
        }
        return read;
    }
    private static String modelStatus(DeNovoLiteratureReader.Read read) {
        var document = read.document();
        if (document == null) return "DEFERRED_NOT_CONFIGURED";
        boolean real = false, synthetic = false;
        for (var entry : document.path("documents")) {
            String kind = entry.at("/facts/modelProvenance/receipt/transportKind").asText();
            real |= "REAL_PROVIDER".equals(kind); synthetic |= "SYNTHETIC_TEST".equals(kind);
        }
        return synthetic ? "SYNTHETIC_MODEL_REPLAY_ONLY" : real ? "MODEL_CANDIDATES_SOURCE_REPLAYED_NOT_CLINICALLY_VALIDATED" : "DEFERRED_NOT_CONFIGURED";
    }

    private static final class Reader {
        private final Path root;
        private final ObjectNode originals;
        private int retained;
        Reader(Path root, ObjectMapper mapper) { this.root = root; originals = mapper.createObjectNode(); }
        byte[] readBinding(JsonNode binding) throws IOException {
            // sourceId is allowed only for source entries; all remaining shape is checked here.
            var b = ((ObjectNode) binding).deepCopy(); b.remove("sourceId"); JSON.shape(b, "path:s sha256:s");
            Path relative = Path.of(b.path("path").asText());
            if (relative.isAbsolute()) throw JSON.invalid("PATH_OUTSIDE_ROOT");
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root)) throw JSON.invalid("PATH_OUTSIDE_ROOT");
            Path actual = path.toRealPath();
            if (!actual.startsWith(root)) throw JSON.invalid("PATH_OUTSIDE_ROOT");
            return read(actual, b.path("sha256").asText());
        }
        byte[] read(Path path, String pin) throws IOException {
            if (pin == null || !pin.matches("[0-9a-f]{64}") || !Files.isRegularFile(path)) throw JSON.invalid("PIN_OR_FILE_INVALID");
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(1_048_577); }
            if (bytes.length == 0 || bytes.length > 1_048_576) throw JSON.invalid("FILE_SIZE_LIMIT");
            if (!FamilyDeNovoJson.sha256(bytes).equals(pin)) throw JSON.invalid("PIN_MISMATCH");
            remember(bytes, pin);
            return bytes;
        }
        void remember(byte[] bytes, String pin) {
            if (!originals.has(pin)) {
                retained += bytes.length;
                if (retained > 16_777_216) throw JSON.invalid("TOTAL_SIZE_LIMIT");
                originals.put(pin, Base64.getEncoder().encodeToString(bytes));
            }
        }
    }
}
