package org.nbscn.varvis.variantanalysis.benchmark.selection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthPolicyAssessment;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthPolicyManifest;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthVariant;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.PublicPilotTruthPolicy;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketBenchmarkAdapter;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketEligibilityStatus;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketImportContext;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketImportResult;
import org.nbscn.varvis.variantanalysis.benchmark.review.HpoReviewPacketGenerationOptions;
import org.nbscn.varvis.variantanalysis.benchmark.review.HpoReviewGenerationResult;
import org.nbscn.varvis.variantanalysis.benchmark.review.TruthMaskedHpoReviewPacketGenerator;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Freezes a non-executable 25+5 public pilot proposal and its review packets. */
public class PublicBenchmarkPilotProposalFreezer {
    public static final String DATASET_ID = "phenopacket-store-0.1.27-grch38-singleton-pilot-proposal-v1";
    private static final int MAIN_TARGET = 25;
    private static final int BACKUP_TARGET = 5;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final PhenopacketBenchmarkAdapter adapter = new PhenopacketBenchmarkAdapter();
    private final PublicBenchmarkPilotSelector selector = new PublicBenchmarkPilotSelector();

    public Map<String, Object> freeze(Path phenopacketRoot, Path bedPath,
                                      Path smokeDatasetManifest, Path smokeSelectionAcceptance,
                                      Path outputDirectory, String archiveSha256,
                                      String bedSha256, String hpoSha256,
                                      String backgroundFingerprint) throws IOException {
        validateChecksum(archiveSha256, "archiveSha256");
        validateChecksum(bedSha256, "bedSha256");
        validateChecksum(hpoSha256, "hpoSha256");
        validateChecksum(backgroundFingerprint, "backgroundFingerprint");
        PublicBenchmarkSmokeSetFreezer.BedIndex bed = PublicBenchmarkSmokeSetFreezer.BedIndex.read(bedPath);
        PublicBenchmarkPilotExclusionRegistry prior = priorRegistry(smokeDatasetManifest,
                smokeSelectionAcceptance);
        List<Path> files;
        try (Stream<Path> stream = Files.walk(phenopacketRoot)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> logical(phenopacketRoot.relativize(path))))
                    .collect(Collectors.toList());
        }
        Map<String, Integer> adapterCounts = new TreeMap<String, Integer>();
        List<PublicBenchmarkSmokeCandidate> candidates = new ArrayList<PublicBenchmarkSmokeCandidate>();
        for (Path file : files) {
            String relative = logical(phenopacketRoot.relativize(file));
            PhenopacketImportContext context = new PhenopacketImportContext(DATASET_ID, "1.0", relative,
                    "https://github.com/monarch-initiative/phenopacket-store/releases/tag/0.1.27",
                    "BSD-3-Clause");
            PhenopacketImportResult imported = adapter.importJson(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8), context);
            increment(adapterCounts, imported.getStatus().name());
            if (imported.getStatus() == PhenopacketEligibilityStatus.ELIGIBLE) {
                PublicBenchmarkSmokeCandidate candidate = new PublicBenchmarkSmokeCandidate();
                candidate.setImported(imported);
                candidate.setSourceRelativePath(relative);
                candidate.setSourceSha256(sha256(file));
                candidate.setAllTruthInsideBenchmarkRegion(allInside(imported, bed));
                candidates.add(candidate);
            }
        }
        PilotSelectionResult selection = selector.select(candidates, prior, MAIN_TARGET, BACKUP_TARGET);
        if (!selection.isComplete()) {
            throw new IOException("PILOT_PROPOSAL_SELECTION_INCOMPLETE");
        }
        List<PilotSelectionDecision> selected = selection.getDecisions().stream()
                .filter(decision -> decision.getRole() == PilotCandidateRole.MAIN
                        || decision.getRole() == PilotCandidateRole.BACKUP)
                .sorted(Comparator.comparingInt((@NonNull PilotSelectionDecision decision) -> decision.getSelectionOrder()))
                .collect(Collectors.toList());
        BenchmarkPilotDatasetProposal proposal = proposal(selected, selection, archiveSha256,
                bedSha256, hpoSha256, backgroundFingerprint);
        Map<String, Object> acceptance = acceptance(files.size(), adapterCounts, candidates,
                selection, selected, prior);
        BenchmarkTruthPolicyManifest truthPolicy = truthPolicy(selected);
        Files.createDirectories(outputDirectory);
        Path manifest = outputDirectory.resolve("dataset-proposal-manifest.json");
        Path acceptancePath = outputDirectory.resolve("selection-acceptance.json");
        write(manifest, mapper.writeValueAsString(proposal) + "\n");
        write(acceptancePath, mapper.writeValueAsString(acceptance) + "\n");
        write(outputDirectory.resolve("truth-policy-manifest.json"),
                mapper.writeValueAsString(truthPolicy) + "\n");
        write(outputDirectory.resolve("prior-smoke-exclusion-registry.json"),
                mapper.writeValueAsString(priorReport(prior)) + "\n");
        TruthMaskedHpoReviewPacketGenerator reviewGenerator = new TruthMaskedHpoReviewPacketGenerator();
        HpoReviewGenerationResult reviews = reviewGenerator.generate(manifest, acceptancePath,
                outputDirectory.resolve("truth-masked-hpo-review-v1"),
                HpoReviewPacketGenerationOptions.pilotProposal(MAIN_TARGET + BACKUP_TARGET));
        reviewGenerator.assertReviewerVisibleContentIsMasked(reviews.getReviewerDirectory(),
                forbiddenReviewerValues(selected));
        return acceptance;
    }

    private BenchmarkPilotDatasetProposal proposal(List<PilotSelectionDecision> selected,
                                                    PilotSelectionResult selection,
                                                    String archiveSha256, String bedSha256,
                                                    String hpoSha256, String backgroundFingerprint) {
        BenchmarkPilotDatasetProposal value = new BenchmarkPilotDatasetProposal();
        value.setDatasetId(DATASET_ID);
        value.setDatasetVersion("1.0");
        value.setProtocolVersion("gold-standard-benchmark-protocol-v1");
        value.setCreatedAt("2026-07-17T00:00:00Z");
        value.setStatus("PROPOSED_NOT_APPROVED");
        value.setPurpose("DEVELOPMENT_PILOT_PROPOSAL");
        value.setDatasetRole("DEVELOPMENT_PREPARATION_PILOT");
        value.setEligibleForLockedTest(Boolean.FALSE);
        value.setApprovalStatus("HUMAN_REVIEW_PENDING");
        value.setExecutionStatus("NOT_STARTED");
        value.setDescription("Candidate proposal only; not executable and not an accuracy conclusion");
        value.setPhenopacketStoreVersion("0.1.27");
        value.setOfficialSourceUri("https://github.com/monarch-initiative/phenopacket-store/releases/tag/0.1.27");
        value.setLicense("BSD-3-Clause");
        value.setArchiveFileName("all_phenopackets.zip");
        value.setArchiveByteSize(19431098L);
        value.setArchiveSha256(archiveSha256);
        value.setSelectorVersion(selection.getAlgorithmVersion());
        value.setSelectorFingerprint(selection.getAlgorithmFingerprint());
        value.setDeterministicSeed(selection.getDeterministicSeed());
        value.setTruthPolicyVersion(PublicPilotTruthPolicy.POLICY_VERSION);
        value.setAssembly("GRCh38");
        value.setBenchmarkRegionVersion("gencode-v47-exome-v1-pad20");
        value.setBenchmarkRegionSha256(bedSha256);
        value.setHpoOntologyVersion("2026-06-23");
        value.setHpoOntologySha256(hpoSha256);
        value.setBackgroundFingerprint(backgroundFingerprint);
        value.setMainCaseCount(MAIN_TARGET);
        value.setBackupCaseCount(BACKUP_TARGET);
        value.setHumanReviewStatus("HUMAN_REVIEW_PENDING");
        List<BenchmarkDatasetCase> cases = new ArrayList<BenchmarkDatasetCase>();
        for (PilotSelectionDecision decision : selected) {
            BenchmarkDatasetCase datasetCase = decision.getCandidate().getImported().getCaseData().getDatasetCase();
            datasetCase.setInclusionStatus(decision.getRole() == PilotCandidateRole.MAIN
                    ? "PROPOSED_MAIN" : "PROPOSED_BACKUP");
            cases.add(datasetCase);
        }
        value.setCases(cases);
        return value;
    }

    private BenchmarkTruthPolicyManifest truthPolicy(List<PilotSelectionDecision> selected) {
        BenchmarkTruthPolicyManifest manifest = new BenchmarkTruthPolicyManifest();
        manifest.setDatasetId(DATASET_ID);
        manifest.setDatasetVersion("1.0");
        manifest.setCreatedAt("2026-07-17T00:00:00Z");
        manifest.setStatus("PROPOSED_NOT_APPROVED");
        List<BenchmarkTruthPolicyAssessment> assessments = selected.stream()
                .map((@NonNull PilotSelectionDecision decision) -> decision.getTruthPolicyAssessment())
                .collect(Collectors.toList());
        manifest.setAssessments(assessments);
        return manifest;
    }

    private Map<String, Object> acceptance(int inputCount, Map<String, Integer> adapterCounts,
                                           List<PublicBenchmarkSmokeCandidate> candidates,
                                           PilotSelectionResult selection,
                                           List<PilotSelectionDecision> selected,
                                           PublicBenchmarkPilotExclusionRegistry prior) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "benchmark-pilot-selection-acceptance-v1");
        result.put("datasetId", DATASET_ID);
        result.put("status", "PROPOSED_NOT_APPROVED");
        result.put("datasetRole", "DEVELOPMENT_PREPARATION_PILOT");
        result.put("eligibleForLockedTest", false);
        result.put("executionStatus", "NOT_STARTED");
        result.put("inputJsonCount", inputCount);
        result.put("adapterStatusCounts", adapterCounts);
        result.put("adapterEligibleCandidateCount", candidates.size());
        result.put("selectorVersion", selection.getAlgorithmVersion());
        result.put("selectorFingerprint", selection.getAlgorithmFingerprint());
        result.put("deterministicSeed", selection.getDeterministicSeed());
        result.put("truthPolicyVersion", PublicPilotTruthPolicy.POLICY_VERSION);
        result.put("mainTarget", MAIN_TARGET);
        result.put("backupTarget", BACKUP_TARGET);
        result.put("selectionComplete", selection.isComplete());
        result.put("selectionStatusCounts", statusCounts(selection));
        result.put("exclusionReasonCounts", exclusionCounts(selection));
        result.put("priorSmokeExclusionDimensions", priorReport(prior));
        result.put("strata", strata(selected));
        List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
        for (PilotSelectionDecision decision : selected) cases.add(caseReport(decision));
        result.put("cases", cases);
        result.put("rankingFieldsUsed", false);
        result.put("clinvarFieldsUsed", false);
        result.put("diseaseFameFieldsUsed", false);
        result.put("publicPilotExecuted", false);
        result.put("humanReviewStatus", "HUMAN_REVIEW_PENDING");
        return result;
    }

    private Map<String, Object> caseReport(PilotSelectionDecision decision) {
        PublicBenchmarkSmokeCandidate candidate = decision.getCandidate();
        BenchmarkDatasetCase datasetCase = candidate.getImported().getCaseData().getDatasetCase();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("selectionOrder", decision.getSelectionOrder());
        result.put("proposalRole", decision.getRole().name());
        result.put("caseId", decision.getCaseId());
        result.put("sourceRelativePath", candidate.getSourceRelativePath());
        result.put("phenopacketSha256", candidate.getSourceSha256());
        result.put("truthSetFingerprint", decision.getTruthPolicyAssessment().getTruthSetFingerprint());
        result.put("truthSemantics", decision.getTruthPolicyAssessment().getSemantics().name());
        result.put("truthCanonicalIdentities", truthIds(datasetCase));
        result.put("chromosomes", chromosomes(datasetCase));
        result.put("variantStratum", decision.getVariantStratum());
        result.put("hpoCountBand", decision.getHpoCountBand());
        result.put("observedHpoCount", candidate.getImported().getCaseData().getObservedHpo().size());
        result.put("excludedHpoCount", candidate.getImported().getCaseData().getExcludedHpo().size());
        result.put("truthGenes", candidate.getImported().getCaseData().getTruthGenes());
        result.put("truthRegionStatus", "INSIDE_FROZEN_BED");
        result.put("hpoReviewStatus", "HUMAN_REVIEW_PENDING");
        return result;
    }

    private Map<String, Object> strata(List<PilotSelectionDecision> selected) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Integer> roles = new TreeMap<String, Integer>();
        Map<String, Integer> chromosomes = new TreeMap<String, Integer>(Comparator.comparingInt(this::chromosomeOrder));
        Map<String, Integer> types = new TreeMap<String, Integer>();
        Map<String, Integer> hpoBands = new TreeMap<String, Integer>();
        Map<String, Integer> genes = new TreeMap<String, Integer>();
        for (PilotSelectionDecision decision : selected) {
            increment(roles, decision.getRole().name());
            increment(types, decision.getVariantStratum());
            increment(hpoBands, decision.getHpoCountBand());
            for (String chromosome : chromosomes(decision.getCandidate().getImported().getCaseData().getDatasetCase())) increment(chromosomes, chromosome);
            for (String gene : decision.getCandidate().getImported().getCaseData().getTruthGenes()) increment(genes, gene);
        }
        result.put("roleCounts", roles);
        result.put("chromosomeCounts", chromosomes);
        result.put("variantTypeCounts", types);
        result.put("observedHpoCountBands", hpoBands);
        result.put("uniqueGeneCount", genes.size());
        result.put("maximumCasesPerGene", genes.values().stream()
                .mapToInt((@NonNull Integer value) -> value.intValue()).max().orElse(0));
        result.put("maximumCasesPerChromosome", chromosomes.values().stream()
                .mapToInt((@NonNull Integer value) -> value.intValue()).max().orElse(0));
        return result;
    }

    private Map<String, Integer> statusCounts(PilotSelectionResult selection) {
        Map<String, Integer> result = new TreeMap<String, Integer>();
        for (PilotSelectionDecision decision : selection.getDecisions()) increment(result, decision.getRole().name());
        return result;
    }

    private Map<String, Integer> exclusionCounts(PilotSelectionResult selection) {
        Map<String, Integer> result = new TreeMap<String, Integer>();
        for (PilotSelectionDecision decision : selection.getDecisions())
            for (PilotSelectionExclusionReason reason : decision.getReasons()) increment(result, reason.name());
        return result;
    }

    private PublicBenchmarkPilotExclusionRegistry priorRegistry(Path datasetPath,
                                                                 Path acceptancePath) throws IOException {
        JsonNode dataset = mapper.readTree(datasetPath.toFile());
        JsonNode acceptance = mapper.readTree(acceptancePath.toFile());
        PublicBenchmarkPilotExclusionRegistry result = new PublicBenchmarkPilotExclusionRegistry();
        for (JsonNode item : dataset.path("cases")) {
            BenchmarkDatasetCase datasetCase = mapper.treeToValue(item, BenchmarkDatasetCase.class);
            result.getCaseIds().add(datasetCase.getCaseId());
            for (String identity : truthIds(datasetCase)) result.getTruthCanonicalIdentities().add(identity);
            result.getTruthSetFingerprints().add(PublicPilotTruthPolicy.assess(datasetCase).getTruthSetFingerprint());
        }
        for (JsonNode item : acceptance.path("cases")) {
            String checksum = item.path("phenopacketSha256").asText();
            if (checksum.matches("[0-9a-f]{64}")) result.getSourceChecksums().add(checksum);
        }
        return result;
    }

    private Map<String, Object> priorReport(PublicBenchmarkPilotExclusionRegistry prior) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "benchmark-prior-smoke-exclusion-registry-v2");
        result.put("handling", "INTERNAL_SELECTION_CONTROL_NOT_REVIEWER_VISIBLE");
        result.put("sourceDatasetId", "phenopacket-store-0.1.27-grch38-singleton-smoke-v1");
        result.put("caseIds", sorted(prior.getCaseIds()));
        result.put("phenopacketSha256", sorted(prior.getSourceChecksums()));
        result.put("truthCanonicalIdentities", sorted(prior.getTruthCanonicalIdentities()));
        result.put("truthSetFingerprints", sorted(prior.getTruthSetFingerprints()));
        return result;
    }

    private boolean allInside(PhenopacketImportResult imported,
                              PublicBenchmarkSmokeSetFreezer.BedIndex bed) {
        if (imported.getCaseData() == null || imported.getCaseData().getDatasetCase() == null) return false;
        for (BenchmarkTruthVariant truth : imported.getCaseData().getDatasetCase().getTruthVariants()) {
            NormalizedVariantKey key = truth.getCanonicalIdentity();
            if (key == null || !bed.contains(key.getChromosome(), key.getPosition() - 1,
                    key.getPosition() - 1 + key.getReference().length())) return false;
        }
        return true;
    }

    private List<String> truthIds(BenchmarkDatasetCase datasetCase) {
        List<String> result = new ArrayList<String>();
        if (datasetCase != null && datasetCase.getTruthVariants() != null)
            for (BenchmarkTruthVariant truth : datasetCase.getTruthVariants())
                if (truth != null && truth.getCanonicalIdentity() != null)
                    result.add(truth.getCanonicalIdentity().canonicalId());
        Collections.sort(result);
        return result;
    }

    private List<String> chromosomes(BenchmarkDatasetCase datasetCase) {
        List<String> result = new ArrayList<String>();
        if (datasetCase != null && datasetCase.getTruthVariants() != null)
            for (BenchmarkTruthVariant truth : datasetCase.getTruthVariants())
                if (truth != null && truth.getCanonicalIdentity() != null
                        && !result.contains(truth.getCanonicalIdentity().getChromosome()))
                    result.add(truth.getCanonicalIdentity().getChromosome());
        result.sort(Comparator.comparingInt(this::chromosomeOrder));
        return result;
    }

    private List<String> forbiddenReviewerValues(List<PilotSelectionDecision> selected) {
        List<String> result = new ArrayList<String>();
        for (PilotSelectionDecision decision : selected) {
            PublicBenchmarkSmokeCandidate candidate = decision.getCandidate();
            BenchmarkDatasetCase datasetCase = candidate.getImported().getCaseData().getDatasetCase();
            addForbidden(result, decision.getCaseId());
            addForbidden(result, candidate.getSourceRelativePath());
            if (candidate.getSourceRelativePath() != null) {
                int slash = candidate.getSourceRelativePath().lastIndexOf('/');
                addForbidden(result, slash < 0 ? candidate.getSourceRelativePath()
                        : candidate.getSourceRelativePath().substring(slash + 1));
            }
            for (String value : truthIds(datasetCase)) addForbidden(result, value);
            for (String value : datasetCase.getTruthGenes()) addForbidden(result, value);
            for (String value : datasetCase.getTruthGeneIds()) addForbidden(result, value);
            for (String value : datasetCase.getDiseaseIds()) addForbidden(result, value);
            for (String value : datasetCase.getDiseaseLabels()) addForbidden(result, value);
        }
        return result;
    }

    private void addForbidden(List<String> values, String value) {
        if (value != null && value.length() >= 3 && !values.contains(value)) values.add(value);
    }

    private int chromosomeOrder(String chromosome) {
        String value = chromosome == null ? "" : chromosome.replaceFirst("(?i)^chr", "");
        if ("X".equalsIgnoreCase(value)) return 23;
        try { return Integer.parseInt(value); } catch (NumberFormatException ex) { return 100; }
    }

    private List<String> sorted(java.util.Set<String> input) { List<String> result = new ArrayList<String>(input); Collections.sort(result); return result; }
    private void increment(Map<String, Integer> values, String key) { values.put(key, values.containsKey(key) ? values.get(key) + 1 : 1); }
    private String logical(Path path) { return path.toString().replace('\\', '/'); }
    private String sha256(Path path) throws IOException { return sha256(Files.readAllBytes(path)); }
    private String sha256(byte[] value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value); StringBuilder result = new StringBuilder(); for (byte item : digest) result.append(String.format("%02x", item & 0xff)); return result.toString(); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private void validateChecksum(String value, String field) { if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " must be lowercase SHA-256"); }
    private void write(Path path, String text) throws IOException { byte[] content = text.getBytes(StandardCharsets.UTF_8); if (Files.exists(path)) { if (Arrays.equals(content, Files.readAllBytes(path))) return; throw new IOException("PILOT_PROPOSAL_ARTIFACT_CONFLICT: " + path.getFileName()); } Path temporary = path.resolveSibling(path.getFileName() + ".temporary"); Files.write(temporary, content); try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, path); } }
}
