package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkAnalysisTelemetryManifest;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryArtifact;

import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BenchmarkManifestValidator {

    private BenchmarkManifestValidator() {
    }

    public static List<BenchmarkValidationError> validate(BenchmarkDatasetManifest manifest) {
        List<BenchmarkValidationError> errors = new ArrayList<BenchmarkValidationError>();
        if (manifest == null) {
            error(errors, "REQUIRED_FIELD", "$", "dataset manifest is required");
            return errors;
        }
        common(errors, manifest.getSchemaVersion(), BenchmarkManifestSchema.DATASET, "schemaVersion");
        required(errors, manifest.getDatasetId(), "datasetId");
        identifier(errors, manifest.getDatasetId(), "datasetId");
        required(errors, manifest.getDatasetVersion(), "datasetVersion");
        required(errors, manifest.getProtocolVersion(), "protocolVersion");
        timestamp(errors, manifest.getCreatedAt(), "createdAt");
        required(errors, manifest.getStatus(), "status");
        allowed(errors, manifest.getStatus(), "status", "FROZEN", "PLANNED", "ACTIVE", "DEPRECATED");
        required(errors, manifest.getOfficialSourceUri(), "officialSourceUri");
        required(errors, manifest.getLicense(), "license");
        required(errors, manifest.getSelectionRuleVersion(), "selectionRuleVersion");
        required(errors, manifest.getBackgroundDatasetId(), "backgroundDatasetId");
        required(errors, manifest.getBackgroundDatasetVersion(), "backgroundDatasetVersion");
        required(errors, manifest.getBackgroundAssembly(), "backgroundAssembly");
        required(errors, manifest.getImmutableBackgroundFingerprint(), "immutableBackgroundFingerprint");
        if (manifest.getSingleton() == null || manifest.getMonogenic() == null
                || manifest.getSmallVariantsOnly() == null) {
            error(errors, "REQUIRED_FIELD", "scope", "singleton, monogenic and smallVariantsOnly are required");
        }
        resourceList(errors, manifest.getBackgroundResources(), "backgroundResources");
        if (manifest.getCases() == null || manifest.getCases().isEmpty()) {
            error(errors, "REQUIRED_FIELD", "cases", "at least one dataset case is required");
        } else {
            Set<String> ids = new HashSet<String>();
            for (int i = 0; i < manifest.getCases().size(); i++) {
                BenchmarkDatasetCase datasetCase = manifest.getCases().get(i);
                String path = "cases[" + i + "]";
                if (datasetCase == null) {
                    error(errors, "REQUIRED_FIELD", path, "case is required");
                    continue;
                }
                required(errors, datasetCase.getCaseId(), path + ".caseId");
                identifier(errors, datasetCase.getCaseId(), path + ".caseId");
                if (hasText(datasetCase.getCaseId()) && !ids.add(datasetCase.getCaseId())) {
                    error(errors, "DUPLICATE_CASE_ID", path + ".caseId", "case ID must be unique within a dataset");
                }
                required(errors, datasetCase.getSourceRecordId(), path + ".sourceRecordId");
                required(errors, datasetCase.getSourceLicense(), path + ".sourceLicense");
                required(errors, datasetCase.getInclusionStatus(), path + ".inclusionStatus");
                allowed(errors, datasetCase.getInclusionStatus(), path + ".inclusionStatus",
                        "INCLUDED", "EXCLUDED", "QUARANTINED");
                required(errors, datasetCase.getAssembly(), path + ".assembly");
                required(errors, datasetCase.getInheritanceMode(), path + ".inheritanceMode");
                required(errors, datasetCase.getTruthProvenance(), path + ".truthProvenance");
                required(errors, datasetCase.getTruthConfidence(), path + ".truthConfidence");
                if ("EXCLUDED".equals(datasetCase.getInclusionStatus())
                        && !hasText(datasetCase.getExclusionReason())) {
                    error(errors, "REQUIRED_FIELD", path + ".exclusionReason", "excluded cases require a reason");
                }
                if ("INCLUDED".equals(datasetCase.getInclusionStatus())
                        && (empty(datasetCase.getTruthGenes()) && empty(datasetCase.getTruthVariants()))) {
                    error(errors, "TRUTH_REQUIRED", path, "included case needs a truth gene or variant");
                }
                truthVariants(errors, datasetCase.getTruthVariants(), datasetCase.getAssembly(), path + ".truthVariants");
                preparationProvenance(errors, datasetCase.getPreparationProvenance(),
                        path + ".preparationProvenance");
            }
        }
        return errors;
    }

    public static List<BenchmarkValidationError> validate(BenchmarkRunManifest manifest) {
        List<BenchmarkValidationError> errors = new ArrayList<BenchmarkValidationError>();
        if (manifest == null) {
            error(errors, "REQUIRED_FIELD", "$", "run manifest is required");
            return errors;
        }
        boolean versionTwo = BenchmarkManifestSchema.RUN_V2.equals(manifest.getSchemaVersion());
        if (!versionTwo && !BenchmarkManifestSchema.RUN.equals(manifest.getSchemaVersion())) {
            error(errors, "UNSUPPORTED_SCHEMA_VERSION", "schemaVersion",
                    "expected benchmark-run-manifest-v1 or benchmark-run-manifest-v2");
        }
        required(errors, manifest.getRunId(), "runId");
        identifier(errors, manifest.getRunId(), "runId");
        timestamp(errors, manifest.getCreatedAt(), "createdAt");
        required(errors, manifest.getDatasetId(), "datasetId");
        identifier(errors, manifest.getDatasetId(), "datasetId");
        required(errors, manifest.getDatasetVersion(), "datasetVersion");
        required(errors, manifest.getProtocolVersion(), "protocolVersion");
        timestamp(errors, manifest.getStartedAt(), "startedAt");
        required(errors, manifest.getStatus(), "status");
        allowed(errors, manifest.getStatus(), "status", "PLANNED", "RUNNING", "COMPLETED",
                "COMPLETED_WITH_FAILURES", "FAILED", "CANCELLED");
        if (!versionTwo) {
            required(errors, manifest.getGitCommit(), "gitCommit");
        } else {
            executionProvenance(errors, manifest.getExecutionProvenance(),
                    manifest.getSharedConfigurationFingerprint(), "executionProvenance");
        }
        required(errors, manifest.getBranch(), "branch");
        required(errors, manifest.getApplicationVersion(), "applicationVersion");
        required(errors, manifest.getJavaVersion(), "javaVersion");
        required(errors, manifest.getOsFamily(), "osFamily");
        required(errors, manifest.getRankingContractVersion(), "rankingContractVersion");
        required(errors, manifest.getBackgroundFingerprint(), "backgroundFingerprint");
        required(errors, manifest.getTruthFingerprint(), "truthFingerprint");
        required(errors, manifest.getSharedConfigurationFingerprint(), "sharedConfigurationFingerprint");
        if (manifest.getConfiguration() == null) {
            error(errors, "REQUIRED_FIELD", "configuration", "run configuration is required");
        } else {
            validateConfiguration(errors, manifest.getConfiguration());
            if (hasText(manifest.getSharedConfigurationFingerprint())
                    && !manifest.getSharedConfigurationFingerprint().equals(
                    manifest.getConfiguration().getConfigurationFingerprint())) {
                error(errors, "CONFIGURATION_MISMATCH", "configuration.configurationFingerprint",
                        "configuration must match sharedConfigurationFingerprint");
            }
        }
        if (manifest.getGroups() == null || manifest.getGroups().isEmpty()) {
            error(errors, "REQUIRED_FIELD", "groups", "at least one run group is required");
        } else {
            Set<String> ids = new HashSet<String>();
            for (int i = 0; i < manifest.getGroups().size(); i++) {
                BenchmarkRunGroup group = manifest.getGroups().get(i);
                String path = "groups[" + i + "]";
                if (group == null) {
                    error(errors, "REQUIRED_FIELD", path, "group is required");
                    continue;
                }
                required(errors, group.getGroupId(), path + ".groupId");
                required(errors, group.getCaseId(), path + ".caseId");
                identifier(errors, group.getGroupId(), path + ".groupId");
                identifier(errors, group.getCaseId(), path + ".caseId");
                if (hasText(group.getGroupId()) && hasText(group.getCaseId())
                        && !ids.add(group.getCaseId() + "/" + group.getGroupId())) {
                    error(errors, "DUPLICATE_CASE_GROUP", path, "case/group must be unique within a run");
                }
                required(errors, group.getHpoInputMode(), path + ".hpoInputMode");
                required(errors, group.getHpoInputChecksum(), path + ".hpoInputChecksum");
                checksum(errors, group.getHpoInputChecksum(), path + ".hpoInputChecksum");
                required(errors, group.getHpoProvenance(), path + ".hpoProvenance");
                required(errors, group.getConfigurationFingerprint(), path + ".configurationFingerprint");
                if (hasText(manifest.getSharedConfigurationFingerprint())
                        && !manifest.getSharedConfigurationFingerprint().equals(group.getConfigurationFingerprint())) {
                    error(errors, "CONFIGURATION_MISMATCH", path + ".configurationFingerprint",
                            "G0/G1/G2 may differ only in HPO input");
                }
                if ("G0".equals(group.getGroupId())) {
                    if (!"NONE".equals(group.getHpoInputMode()) || !empty(group.getHpoTerms())) {
                        error(errors, "G0_HPO_NOT_EMPTY", path,
                                "G0 must use NONE mode and an empty HPO term list");
                    }
                } else if ("G1".equals(group.getGroupId()) || "G2".equals(group.getGroupId())) {
                    if (empty(group.getHpoTerms())) {
                        error(errors, "HPO_REQUIRED", path + ".hpoTerms", "G1/G2 require HPO terms");
                    }
                    String expected = "G1".equals(group.getGroupId()) ? "SOURCE_PROVIDED" : "HUMAN_REVIEWED";
                    if (!expected.equals(group.getHpoInputMode())) {
                        error(errors, "HPO_MODE_MISMATCH", path + ".hpoInputMode", "group mode does not match frozen G0/G1/G2 contract");
                    }
                } else {
                    error(errors, "UNSUPPORTED_GROUP", path + ".groupId", "only G0, G1 and G2 are frozen in this protocol");
                }
            }
        }
        return errors;
    }

    public static List<BenchmarkValidationError> validate(BenchmarkCaseResultManifest manifest) {
        List<BenchmarkValidationError> errors = new ArrayList<BenchmarkValidationError>();
        if (manifest == null) {
            error(errors, "REQUIRED_FIELD", "$", "case result manifest is required");
            return errors;
        }
        boolean versionTwo = BenchmarkManifestSchema.CASE_RESULT_V2.equals(manifest.getSchemaVersion());
        if (!versionTwo && !BenchmarkManifestSchema.CASE_RESULT.equals(manifest.getSchemaVersion())) {
            error(errors, "UNSUPPORTED_SCHEMA_VERSION", "schemaVersion",
                    "expected benchmark-case-result-v1 or benchmark-case-result-v2");
        }
        required(errors, manifest.getResultId(), "resultId");
        identifier(errors, manifest.getResultId(), "resultId");
        timestamp(errors, manifest.getCreatedAt(), "createdAt");
        required(errors, manifest.getRunId(), "runId");
        identifier(errors, manifest.getRunId(), "runId");
        required(errors, manifest.getDatasetId(), "datasetId");
        identifier(errors, manifest.getDatasetId(), "datasetId");
        required(errors, manifest.getCaseId(), "caseId");
        identifier(errors, manifest.getCaseId(), "caseId");
        required(errors, manifest.getGroupId(), "groupId");
        identifier(errors, manifest.getGroupId(), "groupId");
        required(errors, manifest.getCaseExecutionStatus(), "caseExecutionStatus");
        allowed(errors, manifest.getCaseExecutionStatus(), "caseExecutionStatus",
                "COMPLETED", "COMPLETED_WITH_FAILURES", "FAILED", "SKIPPED");
        required(errors, manifest.getAnalysisReference(), "analysisReference");
        identifier(errors, manifest.getAnalysisReference(), "analysisReference");
        if (versionTwo) {
            resultBinding(errors, manifest.getResultBinding(), "resultBinding");
            databaseAttestation(errors, manifest.getDatabaseAttestation(),
                    manifest.getAnalysisReference(), "databaseAttestation");
            telemetryArtifact(errors, manifest.getTelemetryArtifact(), "telemetryArtifact");
        }
        if (manifest.getTruthMatches() == null || manifest.getTruthMatches().isEmpty()) {
            error(errors, "REQUIRED_FIELD", "truthMatches", "at least one truth match is required");
        } else {
            Set<NormalizedVariantKey> identities = new HashSet<NormalizedVariantKey>();
            for (int i = 0; i < manifest.getTruthMatches().size(); i++) {
                BenchmarkTruthMatch match = manifest.getTruthMatches().get(i);
                String path = "truthMatches[" + i + "]";
                if (match == null) {
                    error(errors, "REQUIRED_FIELD", path, "truth match is required");
                    continue;
                }
                required(errors, match.getTruthGene(), path + ".truthGene");
                required(errors, match.getMatchStatus(), path + ".matchStatus");
                required(errors, match.getAssembly(), path + ".assembly");
                required(errors, match.getRankingContractVersion(), path + ".rankingContractVersion");
                if (!"ranking-contract-v1".equals(match.getRankingContractVersion())) {
                    error(errors, "RANKING_CONTRACT_MISMATCH", path + ".rankingContractVersion", "must reuse ranking-contract-v1");
                }
                identity(errors, match.getTruthCanonicalIdentity(), path + ".truthCanonicalIdentity");
                if (match.getTruthCanonicalIdentity() != null) {
                    if (!"ASSEMBLY_MISMATCH".equals(match.getMatchStatus())
                            && !match.getAssembly().equals(match.getTruthCanonicalIdentity().getGenomeAssembly())) {
                        error(errors, "ASSEMBLY_MISMATCH", path + ".assembly", "assembly must match the truth identity");
                    }
                    if (!identities.add(match.getTruthCanonicalIdentity())) {
                        error(errors, "DUPLICATE_TRUTH_IDENTITY", path + ".truthCanonicalIdentity", "truth identity must be unique");
                    }
                }
                if (!allowedMatchStatus(match.getMatchStatus())) {
                    error(errors, "UNSUPPORTED_MATCH_STATUS", path + ".matchStatus",
                            "match status must be MATCHED, NOT_IN_RANKING_UNIVERSE, RANK_UNAVAILABLE, AMBIGUOUS or ASSEMBLY_MISMATCH");
                }
                if (match.getRankStatus() == null) {
                    error(errors, "REQUIRED_FIELD", path + ".rankStatus", "rank status is required");
                } else if (match.getRankStatus() == RankStatus.RANKED) {
                    if (match.getRank() == null || match.getRank() <= 0) {
                        error(errors, "RANK_REQUIRED", path + ".rank", "RANKED requires a positive 1-based rank");
                    }
                    if (match.getRankingUniverseSize() == null || match.getRankingUniverseSize() <= 0) {
                        error(errors, "UNIVERSE_REQUIRED", path + ".rankingUniverseSize", "RANKED requires a positive universe size");
                    } else if (match.getRank() != null && match.getRank() > match.getRankingUniverseSize()) {
                        error(errors, "RANK_OUT_OF_RANGE", path + ".rank", "rank cannot exceed the ranking universe size");
                    }
                    if (match.getMatchedCanonicalIdentity() == null) {
                        error(errors, "MATCHED_IDENTITY_REQUIRED", path + ".matchedCanonicalIdentity", "RANKED requires a matched identity");
                    }
                } else if (match.getRank() != null) {
                    error(errors, "RANK_MUST_BE_NULL", path + ".rank", "non-RANKED status must have a null rank");
                }
                if (match.getMatchedCanonicalIdentity() != null) {
                    identity(errors, match.getMatchedCanonicalIdentity(), path + ".matchedCanonicalIdentity");
                    if (!match.getAssembly().equals(match.getMatchedCanonicalIdentity().getGenomeAssembly())) {
                        error(errors, "ASSEMBLY_MISMATCH", path + ".matchedCanonicalIdentity", "matched identity assembly differs from result assembly");
                    }
                }
            }
        }
        failureCode(errors, manifest.getPrimaryFailureCode(), "primaryFailureCode");
        if (manifest.getSecondaryFailureCodes() != null) {
            for (int i = 0; i < manifest.getSecondaryFailureCodes().size(); i++) {
                failureCode(errors, manifest.getSecondaryFailureCodes().get(i), "secondaryFailureCodes[" + i + "]");
            }
        }
        return errors;
    }

    public static void requireValid(BenchmarkDatasetManifest manifest) {
        require(validate(manifest));
    }

    public static void requireValid(BenchmarkRunManifest manifest) {
        require(validate(manifest));
    }

    public static void requireValid(BenchmarkCaseResultManifest manifest) {
        require(validate(manifest));
    }

    private static void require(List<BenchmarkValidationError> errors) {
        if (!errors.isEmpty()) {
            throw new BenchmarkManifestValidationException(errors);
        }
    }

    private static void validateConfiguration(List<BenchmarkValidationError> errors, BenchmarkRunConfiguration config) {
        required(errors, config.getAssembly(), "configuration.assembly");
        required(errors, config.getNormalizationMode(), "configuration.normalizationMode");
        required(errors, config.getNormalizationProfile(), "configuration.normalizationProfile");
        required(errors, config.getHpoOntologyVersion(), "configuration.hpoOntologyVersion");
        required(errors, config.getPhen2GeneVersion(), "configuration.phen2GeneVersion");
        required(errors, config.getVepVersion(), "configuration.vepVersion");
        required(errors, config.getClinvarVersion(), "configuration.clinvarVersion");
        required(errors, config.getGnomadVersion(), "configuration.gnomadVersion");
        required(errors, config.getCaddVersion(), "configuration.caddVersion");
        required(errors, config.getConfigurationFingerprint(), "configuration.configurationFingerprint");
        if (config.getCandidateCap() == null || config.getCandidateCap() <= 0) {
            error(errors, "INVALID_CAP", "configuration.candidateCap", "candidate cap must be positive");
        }
        if (config.getVepCap() == null || config.getVepCap() <= 0) {
            error(errors, "INVALID_CAP", "configuration.vepCap", "VEP cap must be positive");
        }
        if (config.getFeatureFlags() == null) {
            error(errors, "REQUIRED_FIELD", "configuration.featureFlags", "feature flags must be explicit");
        }
    }

    private static void truthVariants(List<BenchmarkValidationError> errors,
                                      List<BenchmarkTruthVariant> variants,
                                      String assembly,
                                      String path) {
        if (variants == null) {
            return;
        }
        for (int i = 0; i < variants.size(); i++) {
            BenchmarkTruthVariant variant = variants.get(i);
            String itemPath = path + "[" + i + "]";
            if (variant == null) {
                error(errors, "REQUIRED_FIELD", itemPath, "truth variant is required");
                continue;
            }
            identity(errors, variant.getCanonicalIdentity(), itemPath + ".canonicalIdentity");
            required(errors, variant.getTruthProvenance(), itemPath + ".truthProvenance");
            required(errors, variant.getConfidence(), itemPath + ".confidence");
            if (variant.getCanonicalIdentity() != null && hasText(assembly)
                    && !assembly.equals(variant.getCanonicalIdentity().getGenomeAssembly())) {
                error(errors, "ASSEMBLY_MISMATCH", itemPath + ".canonicalIdentity", "truth identity assembly differs from case assembly");
            }
        }
    }

    private static void resourceList(List<BenchmarkValidationError> errors,
                                     List<BenchmarkResourceReference> resources,
                                     String path) {
        if (resources == null) {
            error(errors, "REQUIRED_FIELD", path, "resource list must be explicit");
            return;
        }
        for (int i = 0; i < resources.size(); i++) {
            BenchmarkResourceReference resource = resources.get(i);
            String itemPath = path + "[" + i + "]";
            if (resource == null) {
                error(errors, "REQUIRED_FIELD", itemPath, "resource is required");
                continue;
            }
            required(errors, resource.getResourceId(), itemPath + ".resourceId");
            required(errors, resource.getResourceType(), itemPath + ".resourceType");
            required(errors, resource.getVersion(), itemPath + ".version");
            required(errors, resource.getSourceUri(), itemPath + ".sourceUri");
            required(errors, resource.getChecksumAlgorithm(), itemPath + ".checksumAlgorithm");
            required(errors, resource.getChecksumValue(), itemPath + ".checksumValue");
            required(errors, resource.getStatus(), itemPath + ".status");
            checksum(errors, resource.getChecksumValue(), itemPath + ".checksumValue");
            if (hasText(resource.getChecksumAlgorithm())
                    && !"SHA-256".equals(resource.getChecksumAlgorithm())) {
                error(errors, "UNSUPPORTED_CHECKSUM_ALGORITHM", itemPath + ".checksumAlgorithm",
                        "only SHA-256 is supported by this contract");
            }
            safePath(errors, resource.getLocalRelativePath(), itemPath + ".localRelativePath");
            safePath(errors, resource.getFileName(), itemPath + ".fileName");
            if (resource.getByteSize() != null && resource.getByteSize() < 0) {
                error(errors, "INVALID_SIZE", itemPath + ".byteSize", "byte size cannot be negative");
            }
        }
    }

    private static void preparationProvenance(List<BenchmarkValidationError> errors,
                                              BenchmarkPreparationProvenance provenance,
                                              String path) {
        if (provenance == null) {
            return;
        }
        safePath(errors, provenance.getExpectedArtifactLogicalReference(),
                path + ".expectedArtifactLogicalReference");
        safePath(errors, provenance.getGeneratedArtifactLogicalReference(),
                path + ".generatedArtifactLogicalReference");
        checksum(errors, provenance.getSourceHpoFingerprint(), path + ".sourceHpoFingerprint");
        checksum(errors, provenance.getTruthFingerprint(), path + ".truthFingerprint");
        checksum(errors, provenance.getBackgroundFingerprint(), path + ".backgroundFingerprint");
        checksum(errors, provenance.getCommandPlanFingerprint(), path + ".commandPlanFingerprint");
        checksum(errors, provenance.getOutputChecksum(), path + ".outputChecksum");
    }

    private static void executionProvenance(List<BenchmarkValidationError> errors,
                                            BenchmarkExecutionProvenance provenance,
                                            String sharedConfigurationFingerprint,
                                            String path) {
        if (provenance == null) {
            error(errors, "REQUIRED_FIELD", path, "run v2 requires execution provenance");
            return;
        }
        common(errors, provenance.getSchemaVersion(), BenchmarkManifestSchema.EXECUTION_PROVENANCE,
                path + ".schemaVersion");
        gitCommit(errors, provenance.getApplicationSourceCommit(), path + ".applicationSourceCommit");
        gitCommit(errors, provenance.getOrchestrationSourceCommit(), path + ".orchestrationSourceCommit");
        required(errors, provenance.getApplicationArtifactSha256(), path + ".applicationArtifactSha256");
        checksum(errors, provenance.getApplicationArtifactSha256(), path + ".applicationArtifactSha256");
        required(errors, provenance.getConfigurationFingerprint(), path + ".configurationFingerprint");
        if (hasText(sharedConfigurationFingerprint)
                && !sharedConfigurationFingerprint.equals(provenance.getConfigurationFingerprint())) {
            error(errors, "CONFIGURATION_MISMATCH", path + ".configurationFingerprint",
                    "execution provenance must match shared configuration fingerprint");
        }
        required(errors, provenance.getWorkingTreeState(), path + ".workingTreeState");
        allowed(errors, provenance.getWorkingTreeState(), path + ".workingTreeState",
                "CLEAN", "DIRTY", "UNKNOWN");
    }

    private static void resultBinding(List<BenchmarkValidationError> errors,
                                      BenchmarkResultBinding binding,
                                      String path) {
        if (binding == null) {
            error(errors, "REQUIRED_FIELD", path, "case result v2 requires request binding");
            return;
        }
        required(errors, binding.getAttemptId(), path + ".attemptId");
        identifier(errors, binding.getAttemptId(), path + ".attemptId");
        requiredChecksum(errors, binding.getRequestFingerprint(), path + ".requestFingerprint");
        required(errors, binding.getDatasetVersion(), path + ".datasetVersion");
        requiredChecksum(errors, binding.getPhenopacketSha256(), path + ".phenopacketSha256");
        requiredChecksum(errors, binding.getInputVcfSha256(), path + ".inputVcfSha256");
        requiredChecksum(errors, binding.getTruthSetFingerprint(), path + ".truthSetFingerprint");
        requiredChecksum(errors, binding.getExecutionProvenanceFingerprint(),
                path + ".executionProvenanceFingerprint");
        required(errors, binding.getConfigurationFingerprint(), path + ".configurationFingerprint");
    }

    private static void databaseAttestation(List<BenchmarkValidationError> errors,
                                            BenchmarkDatabaseAttestation attestation,
                                            String analysisReference,
                                            String path) {
        if (attestation == null) {
            error(errors, "REQUIRED_FIELD", path, "case result v2 requires database attestation");
            return;
        }
        required(errors, attestation.getAnalysisReference(), path + ".analysisReference");
        if (hasText(analysisReference) && !analysisReference.equals(attestation.getAnalysisReference())) {
            error(errors, "DATABASE_ATTESTATION_MISMATCH", path + ".analysisReference",
                    "database attestation must match analysisReference");
        }
        required(errors, attestation.getJobStatus(), path + ".jobStatus");
        if (hasText(attestation.getJobStatus()) && !"READY".equals(attestation.getJobStatus())) {
            error(errors, "DATABASE_JOB_NOT_READY", path + ".jobStatus", "persisted job must be READY");
        }
        if (attestation.getPersistedInterpretationCount() == null
                || attestation.getPersistedInterpretationCount() < 0) {
            error(errors, "INVALID_DATABASE_COUNT", path + ".persistedInterpretationCount",
                    "persisted interpretation count must be non-negative");
        }
        if (attestation.getRankedInterpretationCount() == null
                || attestation.getRankedInterpretationCount() < 0) {
            error(errors, "INVALID_DATABASE_COUNT", path + ".rankedInterpretationCount",
                    "ranked interpretation count must be non-negative");
        } else if (attestation.getPersistedInterpretationCount() != null
                && !attestation.getPersistedInterpretationCount().equals(
                attestation.getRankedInterpretationCount())) {
            error(errors, "DATABASE_RANK_COUNT_MISMATCH", path,
                    "persisted and formal ranked counts must match");
        }
        timestamp(errors, attestation.getVerifiedAt(), path + ".verifiedAt");
        requiredChecksum(errors, attestation.getAttestationFingerprint(),
                path + ".attestationFingerprint");
    }

    private static void requiredChecksum(List<BenchmarkValidationError> errors,
                                         String value, String path) {
        required(errors, value, path);
        checksum(errors, value, path);
    }

    private static void telemetryArtifact(List<BenchmarkValidationError> errors,
                                          BenchmarkTelemetryArtifact artifact,
                                          String path) {
        if (artifact == null) {
            error(errors, "REQUIRED_FIELD", path, "case result v2 requires benchmark telemetry");
            return;
        }
        common(errors, artifact.getSchemaVersion(), BenchmarkAnalysisTelemetryManifest.SCHEMA_VERSION,
                path + ".schemaVersion");
        required(errors, artifact.getLogicalFileName(), path + ".logicalFileName");
        safePath(errors, artifact.getLogicalFileName(), path + ".logicalFileName");
        requiredChecksum(errors, artifact.getSha256(), path + ".sha256");
        if (!"COMPLETED".equals(artifact.getStatus())) {
            error(errors, "TELEMETRY_NOT_COMPLETE", path + ".status",
                    "benchmark telemetry must be complete before accepting a case result");
        }
    }

    private static void gitCommit(List<BenchmarkValidationError> errors, String value, String path) {
        required(errors, value, path);
        if (hasText(value) && !value.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            error(errors, "INVALID_GIT_COMMIT", path, "full lowercase Git object ID required");
        }
    }

    private static void identity(List<BenchmarkValidationError> errors, NormalizedVariantKey identity, String path) {
        if (identity == null) {
            error(errors, "REQUIRED_FIELD", path, "assembly-aware canonical identity is required");
            return;
        }
        if (!hasText(identity.getGenomeAssembly()) || !hasText(identity.getChromosome())
                || identity.getPosition() <= 0 || !hasText(identity.getReference())
                || !hasText(identity.getAlternate())) {
            error(errors, "INVALID_CANONICAL_IDENTITY", path, "assembly, chromosome, positive position, REF and ALT are required");
        }
    }

    private static void common(List<BenchmarkValidationError> errors, String actual, String expected, String path) {
        if (!expected.equals(actual)) {
            error(errors, "UNSUPPORTED_SCHEMA_VERSION", path,
                    "expected " + expected + " but received " + String.valueOf(actual));
        }
    }

    private static void timestamp(List<BenchmarkValidationError> errors, String value, String path) {
        required(errors, value, path);
        if (hasText(value)) {
            try {
                Instant.parse(value);
            } catch (DateTimeParseException ex) {
                error(errors, "INVALID_TIMESTAMP", path, "timestamp must be UTC ISO-8601");
            }
        }
    }

    private static void checksum(List<BenchmarkValidationError> errors, String value, String path) {
        if (hasText(value) && !value.matches("[0-9a-f]{64}")) {
            error(errors, "INVALID_CHECKSUM", path, "checksum must be 64 lowercase hexadecimal characters");
        }
    }

    private static void safePath(List<BenchmarkValidationError> errors, String value, String path) {
        if (!hasText(value)) {
            return;
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")
                || normalized.equals("..") || normalized.startsWith("../") || normalized.contains("/../")) {
            error(errors, "UNSAFE_PATH", path, "only a logical relative path is allowed");
        }
    }

    private static void failureCode(List<BenchmarkValidationError> errors, String code, String path) {
        if (hasText(code) && !BenchmarkFailureCode.isAllowed(code)) {
            error(errors, "UNSUPPORTED_FAILURE_CODE", path, "failure code must be one of F0-F11");
        }
    }

    private static void allowed(List<BenchmarkValidationError> errors,
                                String value,
                                String path,
                                String... values) {
        if (!hasText(value)) {
            return;
        }
        for (String allowed : values) {
            if (allowed.equals(value)) {
                return;
            }
        }
        error(errors, "UNSUPPORTED_STATUS", path, "value is outside the manifest status contract");
    }

    private static boolean allowedMatchStatus(String status) {
        return "MATCHED".equals(status)
                || "NOT_IN_RANKING_UNIVERSE".equals(status)
                || "RANK_UNAVAILABLE".equals(status)
                || "AMBIGUOUS".equals(status)
                || "ASSEMBLY_MISMATCH".equals(status);
    }

    private static void required(List<BenchmarkValidationError> errors, String value, String path) {
        if (!hasText(value)) {
            error(errors, "REQUIRED_FIELD", path, "value is required");
        }
    }

    private static void identifier(List<BenchmarkValidationError> errors, String value, String path) {
        if (hasText(value) && !value.matches("[A-Za-z0-9._:-]+")) {
            error(errors, "UNSAFE_IDENTIFIER", path, "identifier must not contain a path separator or whitespace");
        }
    }

    private static void error(List<BenchmarkValidationError> errors, String code, String path, String message) {
        errors.add(new BenchmarkValidationError(code, path, message));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean empty(List<?> values) {
        return values == null || values.isEmpty();
    }
}
