package org.nbscn.varvis.variantanalysis.service.impl;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.provider.SpliceAiEvidenceSupport;
import org.nbscn.varvis.variantanalysis.service.BasicVariantAnnotator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Service
public class VcfInfoBasicVariantAnnotator implements BasicVariantAnnotator {

    private final VariantAnalysisProperties properties;

    public VcfInfoBasicVariantAnnotator() {
        this(new VariantAnalysisProperties());
    }

    @Autowired
    public VcfInfoBasicVariantAnnotator(VariantAnalysisProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<VariantInterpretation> annotate(
            Path normalizedVcf,
            List<VariantQcObservation> observations) {
        List<VariantInterpretation> result = new ArrayList<VariantInterpretation>(observations.size());
        Map<NormalizedVariantKey, Queue<VariantQcObservation>> observationsByKey =
                observationsByKey(observations);
        Set<VariantQcObservation> annotated =
                Collections.newSetFromMap(new IdentityHashMap<VariantQcObservation, Boolean>());
        String genomeAssembly = observations.isEmpty()
                ? "GRCh38"
                : observations.get(0).getKey().getGenomeAssembly();
        try (VCFFileReader reader = new VCFFileReader(normalizedVcf.toFile(), false)) {
            for (VariantContext context : reader) {
                for (Allele alternate : context.getAlternateAlleles()) {
                    VariantQcObservation observation = pollObservation(
                            observationsByKey,
                            key(genomeAssembly, context, alternate)
                    );
                    if (observation == null) {
                        continue;
                    }
                    annotated.add(observation);
                    result.add(new VariantInterpretation(
                            variantId(observation.getKey()),
                            observation,
                            consequences(context, observation),
                            basicEvidence(context, observation)
                    ));
                }
            }
        }
        for (VariantQcObservation observation : observations) {
            if (!annotated.contains(observation)) {
                result.add(new VariantInterpretation(
                        variantId(observation.getKey()),
                        observation,
                        fallbackConsequences(observation),
                        basicEvidence(null, observation)
                ));
            }
        }
        return result;
    }

    private Map<NormalizedVariantKey, Queue<VariantQcObservation>> observationsByKey(
            List<VariantQcObservation> observations) {
        Map<NormalizedVariantKey, Queue<VariantQcObservation>> result =
                new LinkedHashMap<NormalizedVariantKey, Queue<VariantQcObservation>>();
        for (VariantQcObservation observation : observations) {
            if (observation == null || observation.getKey() == null) {
                continue;
            }
            Queue<VariantQcObservation> queue = result.get(observation.getKey());
            if (queue == null) {
                queue = new ArrayDeque<VariantQcObservation>();
                result.put(observation.getKey(), queue);
            }
            queue.add(observation);
        }
        return result;
    }

    private VariantQcObservation pollObservation(
            Map<NormalizedVariantKey, Queue<VariantQcObservation>> observationsByKey,
            NormalizedVariantKey key) {
        Queue<VariantQcObservation> queue = observationsByKey.get(key);
        return queue == null ? null : queue.poll();
    }

    private NormalizedVariantKey key(String genomeAssembly,
                                     VariantContext context,
                                     Allele alternate) {
        return NormalizedVariantKey.canonical(
                GenomeAssembly.valueOf(genomeAssembly),
                context.getContig(),
                context.getStart(),
                context.getReference().getDisplayString(),
                alternate.getDisplayString()
        );
    }

    private List<TranscriptConsequence> consequences(
            VariantContext context,
            VariantQcObservation observation) {
        if (context == null) {
            return fallbackConsequences(observation);
        }
        List<TranscriptConsequence> result = new ArrayList<TranscriptConsequence>();
        Instant timestamp = Instant.now();
        for (Object raw : context.getAttributeAsList("ANN")) {
            for (String annotation : String.valueOf(raw).split(",")) {
                String[] fields = annotation.split("\\|", -1);
                if (fields.length < 2 || !observation.getKey().getAlternate().equalsIgnoreCase(fields[0])) {
                    continue;
                }
                result.add(TranscriptConsequence.ann(
                        field(fields, 3),
                        field(fields, 4),
                        field(fields, 6),
                        field(fields, 5),
                        field(fields, 1),
                        field(fields, 2),
                        field(fields, 9),
                        field(fields, 10),
                        field(fields, 8),
                        null,
                        field(fields, 13),
                        null,
                        null,
                        timestamp
                ));
            }
        }
        return result.isEmpty() ? fallbackConsequences(observation) : result;
    }

    private List<TranscriptConsequence> fallbackConsequences(VariantQcObservation observation) {
        return Collections.singletonList(new TranscriptConsequence(
                null,
                null,
                null,
                null,
                null,
                observation.getVariantType()
        ));
    }

    private List<EvidenceItem> basicEvidence(VariantContext context, VariantQcObservation observation) {
        List<EvidenceItem> evidence = new ArrayList<EvidenceItem>();
        if (properties.getClinVar().isAllowVcfInfoFallback()) {
            evidence.add(clinVarEvidence(context));
        }
        if (properties.getGnomad().isAllowVcfInfoFallback()) {
            evidence.add(gnomadEvidence(context));
        }
        if (properties.getSpliceAi().isAllowVcfInfoFallback()) {
            evidence.add(spliceAiEvidence(context, observation));
        }
        evidence.add(identifierEvidence(context));
        return evidence;
    }

    private EvidenceItem identifierEvidence(VariantContext context) {
        String rsId = rsId(context);
        String clinVarVariationId = firstAttribute(context, "CLNVID", "VariationID", "VARIATIONID");
        if (!StringUtils.hasText(rsId) && !StringUtils.hasText(clinVarVariationId)) {
            return EvidenceItem.notApplicable(
                    "VCF_IDENTIFIER",
                    "VCF_INFO",
                    "OBSERVATION",
                    "No rsID or explicit ClinVar variation ID was present in the normalized VCF"
            );
        }
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("rsId", rsId);
        details.put("clinVarVariationId", clinVarVariationId);
        return new EvidenceItem(
                "VCF_IDENTIFIER",
                "VCF_INFO",
                "OBSERVATION",
                "APPLICABLE",
                null,
                "Known variant identifier",
                "Known identifier carried by the input VCF",
                details
        );
    }

    private EvidenceItem clinVarEvidence(VariantContext context) {
        String significance = attribute(context, "CLNSIG");
        String reviewStatus = attribute(context, "CLNREVSTAT");
        if (!StringUtils.hasText(significance) && !StringUtils.hasText(reviewStatus)) {
            return clinVarUnavailable("No ClinVar fields were present in the normalized VCF");
        }
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", "VCF_INFO_CLINVAR");
        details.put("status", clinVarApplicability(significance));
        details.put("value", significance);
        details.put("interpretation", "ClinVar fields carried by the input VCF are fallback evidence");
        details.put("timestamp", Instant.now().toString());
        details.put("clinicalSignificance", significance);
        details.put("reviewStatus", reviewStatus);
        details.put("matchType", "vcf_info");
        return new EvidenceItem(
                "CLINVAR",
                "VCF_INFO",
                "CLINICAL",
                clinVarApplicability(significance),
                null,
                significance,
                "ClinVar fields carried by the input VCF",
                details
        );
    }

    private EvidenceItem gnomadEvidence(VariantContext context) {
        Double maximumAlleleFrequency = maxFrequency(context);
        if (maximumAlleleFrequency == null) {
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("source", "VCF_INFO_GNOMAD");
            details.put("status", "UNAVAILABLE");
            details.put("interpretation", "No VCF INFO population frequency was available; this is not interpreted as low risk");
            details.put("timestamp", Instant.now().toString());
            return new EvidenceItem(
                    "GNOMAD",
                    "VCF_INFO",
                    "POPULATION",
                    "UNAVAILABLE",
                    null,
                    "Unavailable",
                    "No population allele frequency field was present in the normalized VCF",
                    details
            );
        }
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", "VCF_INFO_GNOMAD");
        details.put("status", "APPLICABLE");
        details.put("value", maximumAlleleFrequency);
        details.put("interpretation", "Population frequency carried by the input VCF is fallback evidence");
        details.put("timestamp", Instant.now().toString());
        details.put("maximumAlleleFrequency", maximumAlleleFrequency);
        return new EvidenceItem(
                "GNOMAD",
                "VCF_INFO",
                "POPULATION",
                "APPLICABLE",
                maximumAlleleFrequency,
                "Maximum allele frequency",
                "Population frequency carried by the input VCF",
                details
        );
    }

    private EvidenceItem spliceAiEvidence(VariantContext context, VariantQcObservation observation) {
        if (context == null || observation == null || observation.getKey() == null) {
            return spliceAiNoRecord("No SpliceAI INFO field was present in the normalized VCF");
        }
        String alternate = observation.getKey().getAlternate();
        List<SpliceAiEvidenceSupport.Prediction> predictions =
                new ArrayList<SpliceAiEvidenceSupport.Prediction>();
        for (Object raw : context.getAttributeAsList("SpliceAI")) {
            predictions.addAll(SpliceAiEvidenceSupport.predictionsFromInfoValue(
                    String.valueOf(raw),
                    alternate
            ));
        }
        SpliceAiEvidenceSupport.Prediction separateFields = spliceAiSeparateFields(
                context,
                alternate,
                alternateIndex(context, alternate)
        );
        if (separateFields != null) {
            predictions.add(separateFields);
        }
        SpliceAiEvidenceSupport.Prediction best = SpliceAiEvidenceSupport.best(predictions);
        if (best == null) {
            return spliceAiNoRecord("No allele-matched SpliceAI INFO evidence was present in the normalized VCF");
        }
        String interpretation = SpliceAiEvidenceSupport.interpretation(
                best.getMaximumDeltaScore(),
                properties.getSpliceAi().getHighThreshold(),
                properties.getSpliceAi().getModerateThreshold(),
                properties.getSpliceAi().getLowThreshold()
        );
        return new EvidenceItem(
                "SPLICEAI",
                "VCF_INFO",
                "COMPUTATIONAL",
                "APPLICABLE",
                best.getMaximumDeltaScore(),
                "SpliceAI maximum delta score",
                "SpliceAI fields carried by the input VCF",
                SpliceAiEvidenceSupport.applicableDetails(
                        SpliceAiEvidenceSupport.SOURCE_VCF_INFO,
                        best,
                        predictions,
                        interpretation
                )
        );
    }

    private EvidenceItem spliceAiNoRecord(String summary) {
        Map<String, Object> details = SpliceAiEvidenceSupport.baseDetails(
                SpliceAiEvidenceSupport.SOURCE_VCF_INFO,
                "NO_RECORD",
                "No VCF INFO SpliceAI evidence was available; this is not interpreted as low splice risk"
        );
        details.put("value", null);
        return new EvidenceItem(
                "SPLICEAI",
                "VCF_INFO",
                "COMPUTATIONAL",
                "NO_RECORD",
                null,
                "No record",
                summary,
                details
        );
    }

    private SpliceAiEvidenceSupport.Prediction spliceAiSeparateFields(VariantContext context,
                                                                      String alternate,
                                                                      int alternateIndex) {
        if (!hasAnyAttribute(context, "DS_AG", "DS_AL", "DS_DG", "DS_DL")) {
            return null;
        }
        return SpliceAiEvidenceSupport.predictionFromScores(
                alternate,
                firstAttribute(context, "SpliceAI_gene", "GENE", "SYMBOL"),
                attributeToken(context, "DS_AG", alternateIndex),
                attributeToken(context, "DS_AL", alternateIndex),
                attributeToken(context, "DS_DG", alternateIndex),
                attributeToken(context, "DS_DL", alternateIndex),
                attributeToken(context, "DP_AG", alternateIndex),
                attributeToken(context, "DP_AL", alternateIndex),
                attributeToken(context, "DP_DG", alternateIndex),
                attributeToken(context, "DP_DL", alternateIndex)
        );
    }

    private EvidenceItem clinVarUnavailable(String summary) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", "VCF_INFO_CLINVAR");
        details.put("status", "UNAVAILABLE");
        details.put("interpretation", "No VCF INFO ClinVar evidence was available; this is not interpreted as low risk");
        details.put("timestamp", Instant.now().toString());
        return new EvidenceItem(
                "CLINVAR",
                "VCF_INFO",
                "CLINICAL",
                "UNAVAILABLE",
                null,
                "Unavailable",
                summary,
                details
        );
    }

    private String clinVarApplicability(String significance) {
        return conflictingSignificance(significance) ? "CONFLICTING" : "APPLICABLE";
    }

    private boolean conflictingSignificance(String significance) {
        if (!StringUtils.hasText(significance)) {
            return false;
        }
        String normalized = significance.toLowerCase()
                .replace('_', ' ')
                .replace('-', ' ');
        if (normalized.contains("conflicting")) {
            return true;
        }
        boolean pathogenic = normalized.contains("pathogenic");
        boolean benign = normalized.contains("benign");
        return pathogenic && benign;
    }

    private Double maxFrequency(VariantContext context) {
        if (context == null) {
            return null;
        }
        String[] keys = {
                "gnomAD_AF",
                "GNOMAD_AF",
                "gnomADg_AF",
                "gnomADe_AF",
                "MAX_AF",
                "AF_popmax",
                "AF_grpmax",
                "AF_grpmax_non_ukb"
        };
        Double maximum = null;
        for (String key : keys) {
            for (Object raw : context.getAttributeAsList(key)) {
                for (String token : String.valueOf(raw).split(",")) {
                    try {
                        double value = Double.parseDouble(token);
                        maximum = maximum == null ? value : Math.max(maximum, value);
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed optional INFO values.
                    }
                }
            }
        }
        return maximum;
    }

    private boolean hasAnyAttribute(VariantContext context, String... keys) {
        if (context == null) {
            return false;
        }
        for (String key : keys) {
            if (context.hasAttribute(key)) {
                return true;
            }
        }
        return false;
    }

    private int alternateIndex(VariantContext context, String alternate) {
        if (context == null || !StringUtils.hasText(alternate)) {
            return 0;
        }
        List<Allele> alternates = context.getAlternateAlleles();
        for (int index = 0; index < alternates.size(); index++) {
            if (alternate.equalsIgnoreCase(alternates.get(index).getDisplayString())) {
                return index;
            }
        }
        return 0;
    }

    private String attributeToken(VariantContext context, String key, int alternateIndex) {
        List<String> tokens = attributeTokens(context, key);
        if (tokens.isEmpty()) {
            return null;
        }
        if (tokens.size() > alternateIndex) {
            return tokens.get(alternateIndex);
        }
        return tokens.size() == 1 ? tokens.get(0) : null;
    }

    private List<String> attributeTokens(VariantContext context, String key) {
        if (context == null || !context.hasAttribute(key)) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<String>();
        for (Object raw : context.getAttributeAsList(key)) {
            for (String token : String.valueOf(raw).split(",")) {
                if (StringUtils.hasText(token)) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    private String attribute(VariantContext context, String key) {
        if (context == null || !context.hasAttribute(key)) {
            return null;
        }
        List<Object> values = context.getAttributeAsList(key);
        return values.isEmpty() ? null : String.valueOf(values.get(0));
    }

    private String firstAttribute(VariantContext context, String... keys) {
        for (String key : keys) {
            String value = attribute(context, key);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String rsId(VariantContext context) {
        if (context == null || !StringUtils.hasText(context.getID())) {
            return null;
        }
        for (String value : context.getID().split("[;,]")) {
            if (value.matches("(?i)rs\\d+")) {
                return value;
            }
        }
        return null;
    }

    private String field(String[] fields, int index) {
        return fields.length > index && StringUtils.hasText(fields[index]) ? fields[index] : null;
    }

    private String variantId(NormalizedVariantKey key) {
        return key.getGenomeAssembly()
                + ":" + key.getChromosome()
                + ":" + key.getPosition()
                + ":" + key.getReference()
                + ":" + key.getAlternate();
    }
}
