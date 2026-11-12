package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.springframework.lang.Nullable;

/** Discovers possible PM1 comparisons in the verified coding sequence. It cannot qualify a comparison. */
public final class Pm1ClinVarIntake {
    public static final String VERSION = "pm1-clinvar-intake-v2";
    private static final int RECORD_LIMIT = 128;
    private static final int SUBMISSION_LIMIT = 8;
    private final ObjectMapper mapper;
    private final Pvs1StartLossClinVarSearch search;
    private final String tabixExecutable;
    @Nullable private final Supplier<ClinVarComparatorSidecar> submissions;
    private final Supplier<ClinVarConditionIdentity> conditions;

    public Pm1ClinVarIntake(ObjectMapper mapper, VariantAnalysisProperties properties,
            @Nullable Supplier<ClinVarComparatorSidecar> submissions) {
        this(mapper, properties, submissions, () -> ClinVarConditionIdentity.unavailable("CONDITION_MAPPING_NOT_CONFIGURED"));
    }

    public Pm1ClinVarIntake(ObjectMapper mapper, VariantAnalysisProperties properties,
            @Nullable Supplier<ClinVarComparatorSidecar> submissions, Supplier<ClinVarConditionIdentity> conditions) {
        this(mapper, new Pvs1StartLossClinVarSearch(mapper, properties), properties.getCadd().getTabixExecutable(), submissions, conditions);
    }

    Pm1ClinVarIntake(ObjectMapper mapper, Pvs1StartLossClinVarSearch search, String tabixExecutable,
            @Nullable Supplier<ClinVarComparatorSidecar> submissions) {
        this(mapper, search, tabixExecutable, submissions, () -> ClinVarConditionIdentity.unavailable("CONDITION_MAPPING_NOT_CONFIGURED"));
    }

    Pm1ClinVarIntake(ObjectMapper mapper, Pvs1StartLossClinVarSearch search, String tabixExecutable,
            @Nullable Supplier<ClinVarComparatorSidecar> submissions, Supplier<ClinVarConditionIdentity> conditions) {
        this.mapper = mapper;
        this.search = search;
        this.tabixExecutable = tabixExecutable;
        this.submissions = submissions;
        this.conditions = conditions;
    }

    ObjectNode collect(JsonNode context, JsonNode model, JsonNode target, JsonNode reviewed) {
        String mondo = context.at("/disease/mondoId").asText();
        var out = mapper.createObjectNode().put("schemaVersion", VERSION).put("status", "UNAVAILABLE")
                .put("referenceSearchComplete", false).put("usedForScoring", false)
                .put("molecularScope", "CODING_MISSENSE_SNV")
                .put("transcript", model.path("transcript").asText()).put("protein", model.path("protein").asText())
                .put("mondoId", mondo).put("moi", context.path("moi").asText());
        try {
            return scan(context, model, target, reviewed, mondo, out);
        } catch (RuntimeException invalid) {
            // Discovery failure cannot replace a reviewed regional or published result.
            return out.put("status", "UNAVAILABLE").put("referenceSearchComplete", false)
                    .put("reasonCode", "PM1_CLINVAR_INTAKE_FAILED");
        }
    }

    private ObjectNode scan(JsonNode context, JsonNode model, JsonNode target, JsonNode reviewed, String mondo, ObjectNode out) {
        // The shared scanner accepts a one-based CDS end-exclusive boundary.
        var nativeScan = search.query(context, model, model.path("cdsLength").asInt() - 2);
        if (!nativeScan.path("complete").asBoolean()) return out.put("reasonCode", nativeScan.path("reasonCode").asText());
        out.set("source", nativeScan.path("resource").deepCopy());
        out.set("codingIntervals", nativeScan.path("regions").deepCopy());
        var counts = out.putObject("excluded");
        var seen = new LinkedHashSet<ClinVarComparatorSidecar.Query>();
        var regions = regions(reviewed);
        var candidates = new java.util.ArrayList<Candidate>();
        for (var row : nativeScan.path("records")) {
            Candidate candidate = project(model, target, row, counts);
            if (candidate != null && seen.add(candidate.query())) candidates.add(candidate);
        }
        var groupCounts = out.putArray("regions");
        for (var region : regions) {
            long inside = candidates.stream().filter(c -> Pm1RegionSelection.covers(region, c.position())).count();
            groupCounts.addObject().put("regionId", region.path("regionId").asText())
                    .put("label", region.path("label").asText()).put("start", region.path("start").asInt()).put("end", region.path("end").asInt())
                    .put("candidateRecordsInside", inside).put("candidateRecordsOutside", candidates.size() - inside);
        }
        candidates.sort(Comparator.comparing((Candidate c) -> regions.stream().noneMatch(r -> Pm1RegionSelection.covers(r, c.position())))
                .thenComparing(c -> !Set.of("PATHOGENIC", "BENIGN", "CONFLICT").contains(c.aggregate()))
                .thenComparingInt(c -> c.position()).thenComparing(c -> c.query().variationId()));
        var visible = candidates.stream().limit(RECORD_LIMIT).toList();
        var local = submissions(visible, out);
        var diseaseMapping = conditions.get();
        out.set("diseaseMappingSource", diseaseMapping.identity(mapper));
        var records = out.putArray("records");
        for (int i = 0; i < visible.size(); i++) {
            var candidate = visible.get(i);
            var row = records.addObject().put("variationId", candidate.query().variationId())
                    .put("genomicPosition", candidate.query().allele().getPosition()).put("reference", candidate.query().allele().getReference())
                    .put("alternate", candidate.query().allele().getAlternate()).put("proteinPosition", candidate.position())
                    .put("proteinChange", candidate.change()).put("aggregateClassification", candidate.aggregate());
            var inside = row.putArray("insideRegionIds");
            regions.stream().filter(r -> Pm1RegionSelection.covers(r, candidate.position()))
                    .forEach(r -> inside.add(r.path("regionId").asText()));
            appendSubmissions(row, local.isEmpty() ? null : local.get(i), mondo, context.at("/gene/symbol").asText(), diseaseMapping);
        }
        return out.put("status", "ENUMERATED").put("referenceSearchComplete", counts.path("referenceMismatch").asInt() == 0)
                .put("candidateRecordCount", candidates.size()).put("omittedRecordCount", candidates.size() - visible.size());
    }

    private static List<JsonNode> regions(JsonNode reviewed) {
        if (reviewed.path("assessments").isArray()) return reviewed.path("assessments").valueStream().toList();
        if (reviewed.path("region").isObject()) return List.of(reviewed.path("region"));
        return List.of();
    }

    @Nullable
    private static Candidate project(JsonNode model, JsonNode target, JsonNode row, ObjectNode counts) {
        String ref = row.path("reference").asText();
        String alt = row.path("alternate").asText();
        String id = row.path("variationId").asText();
        if (!ref.matches("[ACGT]") || !alt.matches("[ACGT]") || ref.equals(alt) || !id.matches("[1-9][0-9]{0,9}"))
            return excluded(counts, "outsideSnvScope");
        int genomic = row.path("position").asInt();
        int index = CodingTranscriptMapping.cdsPosition(model, genomic) - 1;
        if (index < 3 || index >= model.path("cdsLength").asInt() - 3) return excluded(counts, "outsideMissenseScope");
        String cds = model.path("cdsSequence").asText();
        if (cds.charAt(index) != CodingTranscriptMapping.orientAllele(model, ref).charAt(0))
            return excluded(counts, "referenceMismatch");
        int offset = index % 3;
        String codon = cds.substring(index - offset, index - offset + 3);
        char before = TruncatingSequence.translateCodon(codon);
        char after = TruncatingSequence.translateCodon(codon.substring(0, offset) + CodingTranscriptMapping.orientAllele(model, alt)
                + codon.substring(offset + 1));
        if (before == after || before == '*' || after == '*') return excluded(counts, "outsideMissenseScope");
        int position = index / 3 + 1;
        if (position == target.path("proteinPosition").asInt() && String.valueOf(after).equals(target.path("altAminoAcid").asText()))
            return excluded(counts, "targetProteinChange");
        var allele = NormalizedVariantKey.canonical(GenomeAssembly.GRCh38, model.path("contig").asText(), genomic, ref, alt);
        return new Candidate(new ClinVarComparatorSidecar.Query(allele, id), position,
                "p." + TruncatingSequence.aminoName(before) + position + TruncatingSequence.aminoName(after),
                classification(vcfSignificance(row.path("rawVcfLine").asText())));
    }

    @Nullable
    private static Candidate excluded(ObjectNode counts, String key) {
        counts.put(key, counts.path(key).asInt() + 1);
        return null;
    }

    private List<ClinVarComparatorSidecar.LookupResult> submissions(List<Candidate> candidates, ObjectNode out) {
        if (candidates.isEmpty() || submissions == null) {
            out.put("submissionSourceStatus", candidates.isEmpty() ? "NOT_NEEDED" : "NOT_CONFIGURED");
            return List.of();
        }
        try {
            var source = submissions.get();
            var results = source.lookupBatch(candidates.stream().map(candidate -> candidate.query()).toList(), tabixExecutable);
            var provenance = results.get(0).getProvenance();
            out.putObject("submissionSource").put("resourceId", provenance.getResourceId()).put("version", provenance.getResourceVersion())
                    .put("identityFingerprintSha256", provenance.getIdentityFingerprintSha256());
            out.put("submissionSourceStatus", results.stream().anyMatch(r -> r.getStatus() == ClinVarComparatorSidecar.Status.FAILED)
                    ? "READ_INCOMPLETE" : "READ");
            return results;
        } catch (RuntimeException invalid) {
            out.put("submissionSourceStatus", "UNAVAILABLE");
            return List.of();
        }
    }

    private void appendSubmissions(ObjectNode row, @Nullable ClinVarComparatorSidecar.LookupResult lookup, String mondo, String gene,
            ClinVarConditionIdentity diseaseMapping) {
        var record = lookup == null ? null : lookup.getRecord();
        if (record == null) {
            row.put("reviewStatus", "SUBMISSIONS_UNAVAILABLE");
            if (lookup != null) row.put("submissionLookupReason", lookup.getReasonCode());
            return;
        }
        row.put("sourceRecord", record.getProvenance().getRecordLocator());
        var all = record.getSubmissions();
        int matched = 0;
        int other = 0;
        var classifications = new LinkedHashSet<String>();
        var facts = row.putArray("submissionReferences");
        for (var item : all) {
            var identity = diseaseMapping.resolve(mapper, item.path("condition"), mondo);
            String geneBinding = geneBinding(item, gene, record.getGeneSymbols());
            if ("UNVERIFIED".equals(geneBinding)) identity.put("status", "UNVERIFIED").put("reasonCode", "SUBMISSION_GENE_UNVERIFIED");
            String scope = identity.path("status").asText();
            String label = classification(item.path("classification").asText());
            if ("EXACT".equals(scope)) {
                matched++;
                classifications.add(label);
            }
            if ("DIFFERENT".equals(scope)) other++;
            if (facts.size() >= SUBMISSION_LIMIT) continue;
            var fact = facts.addObject().put("conditionScope", scope).put("classification", label).put("geneBinding", geneBinding);
            fact.set("conditionIdentity", identity);
            fact.set("condition", item.path("condition").deepCopy());
            var locator = item.path("sourceLocator");
            if (locator.isObject()) fact.set("sourceLocator", locator.deepCopy());
            String accession = item.path("scv").asText();
            if (accession.matches("SCV[0-9]+(?:\\.[0-9]+)?")) fact.put("scv", accession);
        }
        boolean conflict = classifications.contains("CONFLICT") || classifications.stream()
                .filter(Set.of("PATHOGENIC", "BENIGN", "UNCERTAIN")::contains).count() > 1;
        int unresolved = all.size() - matched - other;
        row.put("submissionCount", all.size()).put("sameDiseaseSubmissionCount", matched)
                .put("otherDiseaseSubmissionCount", other).put("unresolvedDiseaseSubmissionCount", unresolved)
                .put("omittedSubmissionReferenceCount", all.size() - facts.size())
                .put("reviewStatus", conflict ? "SAME_DISEASE_CONFLICT"
                        : unresolved > 0 && "CONFLICT".equals(row.path("aggregateClassification").asText()) ? "AGGREGATE_CONFLICT_SCOPE_UNVERIFIED"
                        : unresolved > 0 && matched > 0 ? "DISEASE_SCOPE_PARTIALLY_VERIFIED"
                        : matched > 0 ? "INDEPENDENT_BASIS_UNVERIFIED"
                        : all.isEmpty() ? "SUBMISSIONS_UNAVAILABLE"
                        : other == all.size() ? "DIFFERENT_DISEASE" : "DISEASE_SCOPE_UNVERIFIED");
        classifications.forEach(row.putArray("sameDiseaseClassifications")::add);
    }

    private static String geneBinding(JsonNode item, String gene, List<String> alleleGenes) {
        if (gene.isBlank()) return "UNVERIFIED";
        String submittedGene = item.path("geneSymbol").asText("");
        if (!submittedGene.isBlank()) return gene.equals(submittedGene) ? "SUBMISSION_GENE" : "UNVERIFIED";
        return alleleGenes.equals(List.of(gene)) ? "EXACT_ALLELE_SINGLE_GENE" : "UNVERIFIED";
    }

    private static String vcfSignificance(String line) {
        var columns = line.split("\t", 9);
        if (columns.length >= 8) for (String token : columns[7].split(";"))
            if (token.startsWith("CLNSIG=")) return token.substring(7);
        return "";
    }

    private static String classification(String value) {
        String label = value.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return switch (label) {
            case "pathogenic", "likely pathogenic", "pathogenic/likely pathogenic" -> "PATHOGENIC";
            case "benign", "likely benign", "benign/likely benign" -> "BENIGN";
            case "uncertain significance" -> "UNCERTAIN";
            case "conflicting classifications of pathogenicity", "conflicting interpretations of pathogenicity" -> "CONFLICT";
            default -> "OTHER";
        };
    }

    private record Candidate(ClinVarComparatorSidecar.Query query, int position, String change, String aggregate) { }
}
