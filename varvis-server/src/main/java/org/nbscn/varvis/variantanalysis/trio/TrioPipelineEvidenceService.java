package org.nbscn.varvis.variantanalysis.trio;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.trio.ranking.TrioRankingContribution;
import org.nbscn.varvis.variantanalysis.trio.ranking.TrioRankingContributionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class TrioPipelineEvidenceService {

    public static final String SOURCE = "TRIO";
    public static final String SOURCE_VERSION = "VARVIS_TRIO_PIPELINE_V1";
    public static final String EVIDENCE_TYPE = "SEGREGATION";

    private final VariantAnalysisProperties properties;
    private final TrioVcfGenotypeObservationBuilder parser;
    private final TrioPreflightService preflightService;
    private final TrioSegregationService segregationService;
    private final TrioEvidenceReportMapper reportMapper;
    private final TrioRankingContributionService rankingContributionService;

    @Autowired
    public TrioPipelineEvidenceService(VariantAnalysisProperties properties) {
        this(
                properties,
                new TrioVcfGenotypeObservationBuilder(),
                new TrioPreflightService(),
                new TrioSegregationService(),
                new TrioEvidenceReportMapper(),
                new TrioRankingContributionService(properties)
        );
    }

    public TrioPipelineEvidenceService(VariantAnalysisProperties properties,
                                       TrioVcfGenotypeObservationBuilder parser,
                                       TrioPreflightService preflightService,
                                       TrioSegregationService segregationService,
                                       TrioEvidenceReportMapper reportMapper) {
        this(properties, parser, preflightService, segregationService, reportMapper,
                new TrioRankingContributionService(properties));
    }

    public TrioPipelineEvidenceService(VariantAnalysisProperties properties,
                                       TrioVcfGenotypeObservationBuilder parser,
                                       TrioPreflightService preflightService,
                                       TrioSegregationService segregationService,
                                       TrioEvidenceReportMapper reportMapper,
                                       TrioRankingContributionService rankingContributionService) {
        this.properties = properties;
        this.parser = parser == null ? new TrioVcfGenotypeObservationBuilder() : parser;
        this.preflightService = preflightService == null ? new TrioPreflightService() : preflightService;
        this.segregationService = segregationService == null ? new TrioSegregationService() : segregationService;
        this.reportMapper = reportMapper == null ? new TrioEvidenceReportMapper() : reportMapper;
        this.rankingContributionService = rankingContributionService == null
                ? new TrioRankingContributionService(properties)
                : rankingContributionService;
    }

    public TrioPipelineResult attachEvidence(List<VariantInterpretation> interpretations,
                                             GenomeAssembly assembly,
                                             TrioPipelineInput input) {
        VariantAnalysisProperties.Trio trio = properties.getTrio();
        if (!trio.isEnabled() || !trio.isPipelineEnabled()) {
            return TrioPipelineResult.skipped("DISABLED", "Trio evidence pipeline is disabled.");
        }
        if (input == null || !input.isPresent()) {
            return TrioPipelineResult.skipped("NO_INPUT", "No trio input was supplied for this analysis.");
        }
        if (interpretations == null || interpretations.isEmpty()) {
            return TrioPipelineResult.skipped("NO_VARIANTS", "No interpretations are available for trio evidence.");
        }

        TrioVcfParseRequest request = input.toParseRequest(
                assembly == null ? GenomeAssembly.GRCh38.name() : assembly.name(),
                trio.normalizedMinDepthForConfirmedRef(),
                trio.normalizedMinGqForConfirmedRef()
        );
        Map<String, String> targetVariants = targetVariants(interpretations);
        if (!targetVariants.isEmpty()) {
            request = request.withTargetVariants(targetVariants);
        }
        TrioVcfParseResult parseResult = parser.parse(request);
        List<String> issues = parseIssues(parseResult);
        if (parseResult.hasErrors()) {
            return failOrOpen("PARSE_ERROR", issues, input.getInputMode());
        }

        TrioPreflightReport preflightReport =
                preflightService.preflight(input.getInputMode(), parseResult.getAnalysisInput());
        issues.addAll(preflightIssues(preflightReport));
        if (!preflightReport.isValid()) {
            return failOrOpen("PREFLIGHT_ERROR", issues, input.getInputMode());
        }

        List<SegregationEvidence> segregationEvidence =
                segregationService.evaluate(parseResult.getAnalysisInput());
        TrioEvidenceReport report = reportMapper.toReport(preflightReport, segregationEvidence);
        List<TrioEvidenceAttachment> attachments =
                attachReportEvidence(interpretations, parseResult, report, trio);
        return new TrioPipelineResult(
                "ATTACHED",
                true,
                trio.isEvidenceOnly(),
                false,
                input.getInputMode(),
                preflightReport,
                report,
                attachments,
                issues);
    }

    private Map<String, String> targetVariants(List<VariantInterpretation> interpretations) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (interpretations == null) {
            return result;
        }
        for (VariantInterpretation interpretation : interpretations) {
            if (interpretation == null
                    || interpretation.getObservation() == null
                    || interpretation.getObservation().getKey() == null) {
                continue;
            }
            NormalizedVariantKey key = interpretation.getObservation().getKey();
            String variantId = new TrioVcfVariantKey(
                    key.getGenomeAssembly(),
                    key.getChromosome(),
                    key.getPosition(),
                    key.getReference(),
                    key.getAlternate()
            ).stableVariantId();
            result.put(variantId, geneSymbols(interpretation));
        }
        return result;
    }

    private String geneSymbols(VariantInterpretation interpretation) {
        Set<String> genes = new LinkedHashSet<String>();
        addGeneSymbols(genes, interpretation.getTranscriptAnnotations());
        addGeneSymbols(genes, interpretation.getConsequences());
        StringBuilder result = new StringBuilder();
        for (String gene : genes) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(gene);
        }
        return result.toString();
    }

    private void addGeneSymbols(Set<String> genes, List<TranscriptConsequence> consequences) {
        if (consequences == null) {
            return;
        }
        for (TranscriptConsequence consequence : consequences) {
            if (consequence == null || !hasText(consequence.getGeneSymbol())) {
                continue;
            }
            String[] values = consequence.getGeneSymbol().split("[,;|]");
            for (String value : values) {
                if (hasText(value)) {
                    genes.add(value.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
    }

    private TrioPipelineResult failOrOpen(String status, List<String> issues, TrioInputMode inputMode) {
        if (!properties.getTrio().isFailOpen()) {
            throw new IllegalStateException("Trio evidence " + status + ": " + joinIssues(issues));
        }
        return new TrioPipelineResult(
                status,
                true,
                properties.getTrio().isEvidenceOnly(),
                true,
                inputMode,
                null,
                null,
                Collections.<TrioEvidenceAttachment>emptyList(),
                issues);
    }

    private List<TrioEvidenceAttachment> attachReportEvidence(List<VariantInterpretation> interpretations,
                                                              TrioVcfParseResult parseResult,
                                                              TrioEvidenceReport report,
                                                              VariantAnalysisProperties.Trio trio) {
        Map<String, TrioVariantObservation> observationsByVariantId = observationsByVariantId(parseResult);
        Map<String, TrioEvidenceReportEntry> entriesByVariantId = entriesByVariantId(report);
        Map<String, TrioEvidenceReportEntry> entriesByCoordinate = entriesByCoordinate(
                parseResult,
                entriesByVariantId);
        List<TrioEvidenceAttachment> attachments = new ArrayList<TrioEvidenceAttachment>();
        int attached = 0;
        int cap = trio.normalizedMaxVariantsForTrioEvidence();
        for (VariantInterpretation interpretation : interpretations) {
            if (cap > 0 && attached >= cap) {
                break;
            }
            TrioEvidenceReportEntry entry = matchingEntry(interpretation, entriesByVariantId, entriesByCoordinate);
            if (entry == null) {
                continue;
            }
            TrioVariantObservation observation = observationsByVariantId.get(entry.getVariantId());
            TrioRankingContribution contribution =
                    rankingContributionService.calculate(interpretation, entry, observation);
            interpretation.addEvidence(Collections.singletonList(toEvidenceItem(entry, observation, contribution)));
            rankingContributionService.applyContribution(interpretation, contribution);
            attachments.add(new TrioEvidenceAttachment(interpretation.getVariantId(), entry));
            attached++;
        }
        return attachments;
    }

    private EvidenceItem toEvidenceItem(TrioEvidenceReportEntry entry,
                                        TrioVariantObservation observation,
                                        TrioRankingContribution contribution) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("model", entry.getModel().name());
        details.put("confidence", entry.getConfidence().name());
        details.put("parentalOrigin", entry.getParentalOrigin().name());
        details.put("trioVariantId", entry.getVariantId());
        details.put("geneSymbol", entry.getGeneSymbol());
        details.put("partnerVariantIds", entry.getPartnerVariantIds());
        details.put("warnings", entry.getWarnings());
        details.put("rankingEffect", TrioEvidenceReport.RANKING_EFFECT);
        details.put("hardGatePolicy", TrioEvidenceReport.HARD_GATE_POLICY);
        details.put("evidenceOnly", Boolean.TRUE);
        addContributionDetails(details, contribution);
        if (observation != null) {
            details.put("probandGenotype", genotype(observation, TrioRole.PROBAND));
            details.put("fatherGenotype", genotype(observation, TrioRole.FATHER));
            details.put("motherGenotype", genotype(observation, TrioRole.MOTHER));
        }
        return new EvidenceItem(
                SOURCE,
                SOURCE_VERSION,
                EVIDENCE_TYPE,
                applicability(entry),
                null,
                label(entry),
                entry.getExplanation(),
                details
        );
    }

    private void addContributionDetails(Map<String, Object> details, TrioRankingContribution contribution) {
        if (contribution == null) {
            return;
        }
        Map<String, Object> contributionDetails = new LinkedHashMap<String, Object>();
        contributionDetails.put("source", SOURCE);
        contributionDetails.put("componentKey", TrioRankingContributionService.COMPONENT);
        contributionDetails.put("enabled", contribution.isEnabled());
        contributionDetails.put("implemented", contribution.isImplemented());
        contributionDetails.put("applied", contribution.isApplied());
        contributionDetails.put("direction", contribution.getContributionDirection().name());
        contributionDetails.put("level", contribution.getContributionLevel().name());
        contributionDetails.put("score", contribution.getContributionScore());
        contributionDetails.put("contributionScore", contribution.getContributionScore());
        contributionDetails.put("maxContributionCap",
                properties.getTrio().normalizedRankingMaxScoreContribution());
        contributionDetails.put("model", contribution.getModel());
        contributionDetails.put("confidence", contribution.getConfidence());
        contributionDetails.put("notAppliedReason", contribution.getNotAppliedReason() == null
                ? null
                : contribution.getNotAppliedReason().name());
        contributionDetails.put("hardGateProtected", contribution.isHardGateProtected());
        contributionDetails.put("explanation", contribution.getExplanation());
        contributionDetails.put("warnings", contribution.getWarnings());
        contributionDetails.put("rankingEffect", contribution.isApplied()
                ? "FEATURE_FLAGGED_TRIO_WEIGHTED_RANKING_APPLIED"
                : TrioEvidenceReport.RANKING_EFFECT);
        contributionDetails.put("rankingEffectDescription", contribution.isApplied()
                ? "Feature-flagged trio weighted ranking contribution was applied."
                : "Trio evidence is explanation-only and did not change ranking.");
        contributionDetails.put("hardGatePolicy", TrioEvidenceReport.HARD_GATE_POLICY);
        contributionDetails.put("rankingEffectSafety",
                "Ranking support only; not a diagnosis. Trio contribution does not override hard gates. "
                        + "EvidenceItem.rawScore remains separate from ranking contribution.");
        details.put("trioRankingContribution", contributionDetails);
        if (contribution.isApplied()) {
            details.put("rankingEffect", "FEATURE_FLAGGED_TRIO_WEIGHTED_RANKING_APPLIED");
            details.put("evidenceOnly", Boolean.FALSE);
        }
    }

    private String applicability(TrioEvidenceReportEntry entry) {
        if (entry.getModel() == InheritanceModelCandidate.UNINFORMATIVE
                || entry.getConfidence() == SegregationConfidence.UNINFORMATIVE) {
            return "NOT_APPLICABLE";
        }
        return "APPLICABLE";
    }

    private String label(TrioEvidenceReportEntry entry) {
        return entry.getModel().name() + " / " + entry.getConfidence().name();
    }

    private String genotype(TrioVariantObservation observation, TrioRole role) {
        TrioGenotypeObservation genotype = observation.genotype(role);
        if (genotype == null) {
            return "UNOBSERVED";
        }
        return genotype.getCall().name() + (genotype.getGenotype() == null ? "" : " (" + genotype.getGenotype() + ")");
    }

    private TrioEvidenceReportEntry matchingEntry(VariantInterpretation interpretation,
                                                  Map<String, TrioEvidenceReportEntry> entriesByVariantId,
                                                  Map<String, TrioEvidenceReportEntry> entriesByCoordinate) {
        TrioEvidenceReportEntry byVariantId = entriesByVariantId.get(interpretation.getVariantId());
        if (byVariantId != null) {
            return byVariantId;
        }
        return entriesByCoordinate.get(coordinateKey(interpretation));
    }

    private Map<String, TrioVariantObservation> observationsByVariantId(TrioVcfParseResult parseResult) {
        Map<String, TrioVariantObservation> result = new LinkedHashMap<String, TrioVariantObservation>();
        if (parseResult.getAnalysisInput() == null) {
            return result;
        }
        for (TrioVariantObservation observation : parseResult.getAnalysisInput().getVariants()) {
            if (observation != null && hasText(observation.getVariantId())) {
                result.put(observation.getVariantId(), observation);
            }
        }
        return result;
    }

    private Map<String, TrioEvidenceReportEntry> entriesByVariantId(TrioEvidenceReport report) {
        Map<String, TrioEvidenceReportEntry> result = new LinkedHashMap<String, TrioEvidenceReportEntry>();
        for (TrioEvidenceReportEntry entry : report.getEntries()) {
            if (entry != null && hasText(entry.getVariantId())) {
                result.put(entry.getVariantId(), entry);
            }
        }
        return result;
    }

    private Map<String, TrioEvidenceReportEntry> entriesByCoordinate(TrioVcfParseResult parseResult,
                                                                     Map<String, TrioEvidenceReportEntry> entries) {
        Map<String, TrioEvidenceReportEntry> result = new LinkedHashMap<String, TrioEvidenceReportEntry>();
        for (TrioVcfGenotypeRecord record : parseResult.getGenotypeRecords()) {
            if (record.getRole() != TrioRole.PROBAND || record.getVariantKey() == null) {
                continue;
            }
            TrioEvidenceReportEntry entry = entries.get(findVariantId(parseResult, record.getVariantKey()));
            if (entry != null) {
                result.put(coordinateKey(record.getVariantKey()), entry);
            }
        }
        return result;
    }

    private String findVariantId(TrioVcfParseResult parseResult, TrioVcfVariantKey key) {
        if (parseResult.getAnalysisInput() == null) {
            return null;
        }
        for (TrioVariantObservation observation : parseResult.getAnalysisInput().getVariants()) {
            if (observation != null
                    && observation.getPosition() == key.getPosition()
                    && sameChromosome(observation.getChromosome(), key.getChromosome())) {
                return observation.getVariantId();
            }
        }
        return null;
    }

    private String coordinateKey(VariantInterpretation interpretation) {
        VariantQcObservation observation = interpretation.getObservation();
        if (observation == null || observation.getKey() == null) {
            return null;
        }
        NormalizedVariantKey key = observation.getKey();
        return coordinateKey(
                key.getGenomeAssembly(),
                key.getChromosome(),
                key.getPosition(),
                key.getReference(),
                key.getAlternate());
    }

    private String coordinateKey(TrioVcfVariantKey key) {
        return coordinateKey(
                key.getAssembly(),
                key.getChromosome(),
                key.getPosition(),
                key.getReference(),
                key.getAlternate());
    }

    private String coordinateKey(String assembly, String chromosome, long position, String reference, String alternate) {
        return normalizeAssembly(assembly)
                + ":" + normalizeChromosome(chromosome)
                + ":" + position
                + ":" + normalizeAllele(reference)
                + ">"
                + normalizeAllele(alternate);
    }

    private List<String> parseIssues(TrioVcfParseResult parseResult) {
        List<String> result = new ArrayList<String>();
        for (TrioVcfParseIssue issue : parseResult.getIssues()) {
            result.add(issue.getSeverity().name() + ":" + issue.getCode() + ":" + issue.getMessage());
        }
        return result;
    }

    private List<String> preflightIssues(TrioPreflightReport preflightReport) {
        List<String> result = new ArrayList<String>();
        for (TrioPreflightIssue issue : preflightReport.getIssues()) {
            result.add(issue.getSeverity().name() + ":" + issue.getCode() + ":" + issue.getMessage());
        }
        return result;
    }

    private String joinIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return "unknown trio evidence failure";
        }
        StringBuilder builder = new StringBuilder();
        for (String issue : issues) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(issue);
        }
        return builder.toString();
    }

    private boolean sameChromosome(String left, String right) {
        return normalizeChromosome(left).equals(normalizeChromosome(right));
    }

    private String normalizeAssembly(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        if ("grch37".equals(normalized) || "hg19".equals(normalized) || "b37".equals(normalized)) {
            return "GRCh37";
        }
        if ("grch38".equals(normalized) || "hg38".equals(normalized)) {
            return "GRCh38";
        }
        return value.trim();
    }

    private String normalizeChromosome(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.toLowerCase(Locale.ROOT).startsWith("chr")) {
            normalized = normalized.substring(3);
        }
        if ("M".equalsIgnoreCase(normalized)) {
            return "MT";
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeAllele(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
