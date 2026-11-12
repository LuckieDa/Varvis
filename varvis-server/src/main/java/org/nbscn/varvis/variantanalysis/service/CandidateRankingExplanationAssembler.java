package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse.Completeness;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse.Contribution;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse.EvidenceProvenance;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse.Direction;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse.EngineCandidateContext;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse.AnalysisVcfCallContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a presentation model without invoking ranking or mutating the interpretation. */
public final class CandidateRankingExplanationAssembler {

    public CandidateRankingExplanationResponse assemble(String analysisId, VariantInterpretation variant) {
        return assemble(analysisId, variant, true);
    }

    public CandidateRankingExplanationResponse assembleLegacy(
            String analysisId,
            VariantInterpretation variant) {
        return assemble(analysisId, variant, false);
    }

    private CandidateRankingExplanationResponse assemble(
            String analysisId,
            VariantInterpretation variant,
            boolean candidateAware) {
        if (variant == null) throw new IllegalArgumentException("variant is required");
        List<Contribution> contributions = contributions(variant);
        List<EvidenceProvenance> available = new ArrayList<EvidenceProvenance>();
        List<EvidenceProvenance> conflicting = new ArrayList<EvidenceProvenance>();
        List<EvidenceProvenance> missing = new ArrayList<EvidenceProvenance>();
        for (EvidenceItem evidence : safeList(variant.getEvidence())) {
            if (evidence == null) continue;
            EvidenceProvenance safe = safeEvidence(evidence);
            String applicability = upper(evidence.getApplicability());
            if ("CONFLICTING".equals(applicability)) conflicting.add(safe);
            else if ("APPLICABLE".equals(applicability) || "AVAILABLE".equals(applicability)) available.add(safe);
            else missing.add(safe);
        }
        List<String> warnings = new ArrayList<String>();
        if (hasNonFiniteScore(variant)) {
            warnings.add("One or more persisted scores were non-finite and were omitted from this explanation.");
        }
        if (variant.isSummaryOnly() || !variant.isFullInterpretationAvailable()) {
            warnings.add("Only a compact candidate summary is available; detailed evidence may be incomplete.");
        } else if (completeness(variant) == Completeness.LEGACY_PARTIAL) {
            warnings.add("This older ranking contains only a partial persisted explanation.");
        } else if (completeness(variant) == Completeness.UNAVAILABLE) {
            warnings.add("No persisted ranking breakdown is available; no score was reconstructed.");
        }
        if (!candidateAware) {
            return new CandidateRankingExplanationResponse(
                    analysisId,
                    variant.getVariantId(),
                    safeEnumToken(variant.getPriorityTier()),
                    finite(variant.getRankingScore()),
                    null,
                    completeness(variant),
                    contributions,
                    available,
                    conflicting,
                    missing,
                    warnings
            );
        }
        return new CandidateRankingExplanationResponse(
                analysisId,
                variant.getCandidateId(),
                variant.getCanonicalVariantId(),
                variant.getVariantId(),
                safeEnumToken(variant.getPriorityTier()),
                finite(variant.getRankingScore()),
                null,
                completeness(variant),
                contributions,
                available,
                conflicting,
                missing,
                warnings,
                safeEngineCandidateContext(variant.getExomiserCandidateFeatures()),
                safeAnalysisVcfCallContext(variant.getAnalysisVcfCall())
        );
    }

    private Completeness completeness(VariantInterpretation variant) {
        if (variant.isSummaryOnly() || !variant.isFullInterpretationAvailable()) return Completeness.SUMMARY_ONLY;
        if (variant.getScoreComponents() != null && !variant.getScoreComponents().isEmpty()) return Completeness.COMPLETE;
        if ((variant.getScoreBreakdown() != null && !variant.getScoreBreakdown().isEmpty())
                || !safeList(variant.getRationale()).isEmpty()
                || !safeList(variant.getPositiveReasons()).isEmpty()
                || !safeList(variant.getNegativeReasons()).isEmpty()) return Completeness.LEGACY_PARTIAL;
        return Completeness.UNAVAILABLE;
    }

    private List<Contribution> contributions(VariantInterpretation variant) {
        List<Contribution> result = new ArrayList<Contribution>();
        if (variant.getScoreComponents() != null && !variant.getScoreComponents().isEmpty()) {
            for (Map.Entry<String, ScoreBreakdownComponent> entry : variant.getScoreComponents().entrySet()) {
                ScoreBreakdownComponent component = entry.getValue();
                Double score = component == null ? null : finite(component.getScore());
                result.add(new Contribution(safeKey(entry.getKey()), score,
                        fixedContributionReason(score), "SCORE_COMPONENT"));
            }
            return result;
        }
        if (variant.getScoreBreakdown() != null && !variant.getScoreBreakdown().isEmpty()) {
            for (Map.Entry<String, Double> entry : variant.getScoreBreakdown().entrySet()) {
                Double score = finite(entry.getValue());
                result.add(new Contribution(safeKey(entry.getKey()), score, fixedContributionReason(score),
                        "SCORE_BREAKDOWN"));
            }
            return result;
        }
        if (!safeList(variant.getPositiveReasons()).isEmpty()) {
            result.add(new Contribution("legacy_positive_reasons", null, Direction.POSITIVE,
                    Collections.singletonList("A persisted legacy positive reason was recorded; free text is withheld."),
                    "LEGACY_RATIONALE"));
        }
        if (!safeList(variant.getNegativeReasons()).isEmpty()) {
            result.add(new Contribution("legacy_negative_reasons", null, Direction.NEGATIVE,
                    Collections.singletonList("A persisted legacy negative reason was recorded; free text is withheld."),
                    "LEGACY_RATIONALE"));
        }
        if (result.isEmpty() && !safeList(variant.getRationale()).isEmpty()) {
            result.add(new Contribution("legacy_rationale", null,
                    Collections.singletonList("A persisted legacy rationale exists; free text is withheld."),
                    "LEGACY_RATIONALE"));
        }
        return result;
    }

    private EvidenceProvenance safeEvidence(EvidenceItem item) {
        String applicability = safeEnumToken(item.getApplicability());
        return new EvidenceProvenance(safeEnumToken(item.getSource()), safeVersionToken(item.getEffectiveResourceVersion()),
                safeEnumToken(item.getEvidenceType()), applicability,
                safeEnumToken(item.getEffectiveStatus()), safeEnumToken(item.getEvidenceStrength()),
                safeEnumToken(item.getExplanationCode()), safeTimestamp(item.getRetrievedAt()),
                availabilityMeaning(upper(item.getApplicability())));
    }

    private EngineCandidateContext safeEngineCandidateContext(ExomiserCandidateFeatures features) {
        if (features == null) {
            return null;
        }
        return new EngineCandidateContext(
                safeVersionToken(features.getEngineVersion()),
                safeGeneSymbol(features.getGeneSymbol()),
                safeIdentifier(features.getEntrezGeneId(), 80),
                safeIdentifier(features.getRawModeOfInheritance(), 80),
                safeIdentifier(features.getNormalizedModeOfInheritance(), 80),
                positive(features.getGeneMoiGroupRank()),
                safeEnumToken(features.getGeneMoiGroupRankSemantics()),
                finite(features.getGeneCombinedScore()),
                finite(features.getGenePhenotypeScore()),
                finite(features.getGeneVariantScore()),
                finite(features.getVariantScore()),
                safeFunctionalClass(features.getFunctionalClass()),
                safeHgvs(features.getHgvs()),
                features.getContributingVariant(),
                // The VCF ID column is uploader-controlled free text.  It is retained in the
                // analysis/export domain but intentionally excluded from the explanation API's
                // narrow provenance allowlist.
                null,
                positive(features.getVariantSourceOrdinal()),
                positive(features.getGeneSourceOrdinal()),
                safeEnumToken(features.getGeneRowMatchStrategy()),
                safeIdentifier(features.getVariantArtifactName(), 80),
                safeIdentifier(features.getGeneArtifactName(), 80),
                safeIdentifier(features.getRankingScoreSourceColumn(), 80),
                safeFieldSources(features.getFieldSources()),
                safeFieldNames(features.getMissingFields()),
                safeFieldNames(features.getInvalidFields())
        );
    }

    private AnalysisVcfCallContext safeAnalysisVcfCallContext(AnalysisVcfCall call) {
        if (call == null) {
            return null;
        }
        return new AnalysisVcfCallContext(
                AnalysisVcfCall.CURRENT_SCHEMA_VERSION,
                enumName(call.getMatchStatus()),
                enumName(call.getTargetAlleleCall()),
                safeCandidateRelativeGenotype(call.getCandidateRelativeGenotype()),
                positive(call.getTargetAlleleIndex()),
                nonNegative(call.getDepth()),
                nonNegative(call.getGenotypeQuality()),
                nonNegative(call.getReferenceDepth()),
                nonNegative(call.getTargetAlleleDepth()),
                finiteUnitInterval(call.getAlleleBalance()),
                enumName(call.getFilterEvaluation()),
                enumName(call.getPhasingStatus()),
                safeIdentifier(call.getPhaseSetToken(), 128),
                nonNegative(call.getMatchingRecordCount()),
                positive(call.getMatchedRecordOrdinal()),
                safeEnumToken(call.getAssembly()),
                AnalysisVcfCall.ARTIFACT_ROLE,
                AnalysisVcfCall.PROVENANCE,
                safeFieldNames(call.getMissingFields()),
                safeFieldNames(call.getInvalidFields())
        );
    }

    private String safeCandidateRelativeGenotype(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.matches("(?:REF|TARGET_ALT|OTHER_ALT|\\.)(?:[|/](?:REF|TARGET_ALT|OTHER_ALT|\\.))*")
                ? text : null;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private Integer nonNegative(Integer value) {
        return value != null && value.intValue() >= 0 ? value : null;
    }

    private Double finiteUnitInterval(Double value) {
        return value != null && Double.isFinite(value.doubleValue())
                && value.doubleValue() >= 0.0d && value.doubleValue() <= 1.0d
                ? value : null;
    }

    private String safeGeneSymbol(String value) {
        if (value == null) return null;
        String text = value.trim();
        return !text.isEmpty() && text.length() <= 32 && text.matches("[A-Za-z0-9.-]+")
                ? text : null;
    }

    private String safeIdentifier(String value, int maxLength) {
        if (value == null) return null;
        String text = value.trim();
        return !text.isEmpty() && text.length() <= maxLength
                && text.matches("[A-Za-z0-9_.:#-]+")
                && !containsSensitiveMarker(text) ? text : null;
    }

    private String safeFunctionalClass(String value) {
        if (value == null) return null;
        String text = value.trim();
        return !text.isEmpty() && text.length() <= 120
                && text.matches("[A-Za-z0-9_.:,+&=| -]+")
                && !containsSensitiveMarker(text) ? text : null;
    }

    private String safeHgvs(String value) {
        if (value == null) return null;
        String text = value.trim();
        return !text.isEmpty() && text.length() <= 240
                && text.matches("[A-Za-z0-9_.:;=()>*?+\\-\\[\\]]+")
                && !containsSensitiveMarker(text) ? text : null;
    }

    private Integer positive(Integer value) {
        return value != null && value.intValue() > 0 ? value : null;
    }

    private List<String> safeFieldNames(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<String> safe = new ArrayList<String>();
        for (String value : values) {
            if (value == null) continue;
            String text = value.trim();
            if (!text.isEmpty() && text.length() <= 80
                    && text.matches("[#A-Za-z0-9_.:-]+")
                    && !containsSensitiveMarker(text)) {
                safe.add(text);
            }
            if (safe.size() >= 32) break;
        }
        return safe;
    }

    private Map<String, String> safeFieldSources(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyMap();
        Map<String, String> safe = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = safeIdentifier(entry.getKey(), 80);
            String value = safeIdentifier(entry.getValue(), 80);
            if (key != null && value != null) {
                safe.put(key, value);
            }
            if (safe.size() >= 64) break;
        }
        return safe;
    }

    private String safeKey(String key) {
        if (key == null || key.trim().isEmpty()) return "unknown_component";
        String value = key.trim();
        if (value.length() > 80 || !value.matches("[A-Za-z0-9_.-]+") || containsSensitiveMarker(value))
            return "unknown_component";
        return value;
    }
    private List<String> fixedContributionReason(Double score) {
        if (score == null) return Collections.singletonList("The persisted contribution was unavailable or non-finite.");
        if (score.doubleValue() > 0.0d) return Collections.singletonList("A positive persisted score contribution was recorded.");
        if (score.doubleValue() < 0.0d) return Collections.singletonList("A negative persisted score contribution was recorded.");
        return Collections.singletonList("A neutral persisted score contribution was recorded.");
    }
    private String availabilityMeaning(String applicability) {
        if ("APPLICABLE".equals(applicability) || "AVAILABLE".equals(applicability))
            return "EVIDENCE_REPORTED_AVAILABLE";
        if ("CONFLICTING".equals(applicability)) return "EVIDENCE_REPORTED_CONFLICTING";
        if ("NO_RECORD".equals(applicability)) return "NO_SOURCE_RECORD_REPORTED";
        if ("NOT_APPLICABLE".equals(applicability)) return "EVIDENCE_NOT_APPLICABLE";
        if ("UNAVAILABLE".equals(applicability)) return "EVIDENCE_UNAVAILABLE";
        return "EVIDENCE_AVAILABILITY_UNPROVEN";
    }
    private String safeEnumToken(String value) {
        if (value == null) return null;
        String text = value.trim();
        return !text.isEmpty() && text.length() <= 80 && text.matches("[A-Za-z0-9_.-]+")
                && !containsSensitiveMarker(text) ? text : null;
    }
    private String safeVersionToken(String value) {
        return safeEnumToken(value);
    }
    private String safeTimestamp(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.length() <= 40 && text.matches("[0-9TZ:+.\\-]+") ? text : null;
    }
    private boolean containsSensitiveMarker(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("patient") || normalized.contains("sample")
                || normalized.contains("proband") || normalized.contains("subject")
                || normalized.contains("person") || normalized.contains("participant")
                || normalized.contains("case-id") || normalized.contains("case_id");
    }
    private Double finite(Double value) {
        return value != null && Double.isFinite(value.doubleValue()) ? value : null;
    }
    private Double finite(double value) {
        return Double.isFinite(value) ? Double.valueOf(value) : null;
    }
    private boolean hasNonFiniteScore(VariantInterpretation variant) {
        if (variant.getRankingScore() != null && !Double.isFinite(variant.getRankingScore())) return true;
        if (variant.getScoreComponents() != null) {
            for (ScoreBreakdownComponent component : variant.getScoreComponents().values())
                if (component != null && !Double.isFinite(component.getScore())) return true;
        }
        if (variant.getScoreBreakdown() != null) {
            for (Double score : variant.getScoreBreakdown().values())
                if (score != null && !Double.isFinite(score)) return true;
        }
        return false;
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT); }
    private <T> List<T> safeList(List<T> values) { return values == null ? Collections.emptyList() : values; }
}
