package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.NonNull;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkPreparationProvenance;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthVariant;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Conservative adapter for the supported synthetic Phenopacket v2-like shape.
 * It only consumes explicit VCF-like alleles and source HPO terms.
 */
public class PhenopacketBenchmarkAdapter {

    private static final String DEFAULT_PROVENANCE = "Phenopacket source record";
    private final ObjectMapper objectMapper;

    public PhenopacketBenchmarkAdapter() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public PhenopacketBenchmarkAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    public PhenopacketImportResult importJson(String json, PhenopacketImportContext context) {
        PhenopacketImportResult result = new PhenopacketImportResult();
        result.setProvenance(DEFAULT_PROVENANCE);
        if (context == null) {
            return invalid(result, "MISSING_CONTEXT", "$", "dataset/source context is required");
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(json == null ? "" : json);
        } catch (IOException ex) {
            return invalid(result, PhenopacketExclusionReason.MALFORMED_JSON.name(), "$", "Phenopacket JSON is malformed");
        }
        if (root == null || !root.isObject()) {
            return invalid(result, PhenopacketExclusionReason.MALFORMED_JSON.name(), "$", "Phenopacket root must be an object");
        }

        String schema = firstText(root, "schemaVersion");
        if (schema == null && root.path("metaData").isObject()) {
            schema = firstText(root.path("metaData"), "phenopacketSchemaVersion", "schemaVersion");
        }
        if (!supportedSchema(schema)) {
            return excluded(result, PhenopacketExclusionReason.UNSUPPORTED_SCHEMA_VERSION,
                    "schemaVersion", "only the synthetic Phenopacket v2-like contract is supported");
        }

        String caseId = context.getCaseIdStrategy() == null
                ? null : context.getCaseIdStrategy().derive(root, context);
        if (!hasText(caseId)) {
            return excluded(result, PhenopacketExclusionReason.MISSING_PUBLIC_CASE_ID,
                    "id", "a public Phenopacket case ID is required");
        }

        List<PhenopacketImportIssue> issues = result.getIssues();
        List<PhenopacketHpoTerm> observed = new ArrayList<PhenopacketHpoTerm>();
        List<PhenopacketHpoTerm> excluded = new ArrayList<PhenopacketHpoTerm>();
        extractHpo(root, observed, excluded, issues);
        if (observed.isEmpty()) {
            return excluded(result, PhenopacketExclusionReason.NO_USABLE_OBSERVED_HPO,
                    "phenotypicFeatures", "at least one positive HPO term is required for G1 preparation");
        }

        List<String> diseaseIds = new ArrayList<String>();
        List<String> diseaseLabels = new ArrayList<String>();
        extractDiseases(root, diseaseIds, diseaseLabels);
        List<String> truthGeneIds = new ArrayList<String>();
        List<String> truthGenes = new ArrayList<String>();
        extractGenes(root, truthGeneIds, truthGenes);
        if (truthGenes.isEmpty() && truthGeneIds.isEmpty()) {
            return excluded(result, PhenopacketExclusionReason.MISSING_TRUTH_GENE,
                    "interpretations", "truth gene must be explicitly present");
        }

        List<NormalizedVariantKey> identities = new ArrayList<NormalizedVariantKey>();
        boolean sawVcfRecord = false;
        boolean sawIncomplete = false;
        boolean sawStructural = hasStructuralMarker(root);
        Set<String> assemblies = new LinkedHashSet<String>();
        for (JsonNode vcfNode : root.findValues("vcfRecord")) {
            List<JsonNode> records = new ArrayList<JsonNode>();
            if (vcfNode.isArray()) {
                vcfNode.forEach(records::add);
            } else {
                records.add(vcfNode);
            }
            for (JsonNode record : records) {
                sawVcfRecord = true;
                String assemblyText = firstText(record, "genomeAssembly", "assembly");
                String chromosome = firstText(record, "chromosome", "chrom", "contig");
                int position = firstInt(record, "position", "pos");
                String reference = firstText(record, "ref", "reference");
                String alternate = firstText(record, "alt", "alternate");
                if (!hasText(assemblyText)) {
                    return excluded(result, PhenopacketExclusionReason.MISSING_ASSEMBLY,
                            "vcfRecord.genomeAssembly", "truth allele assembly is required");
                }
                final GenomeAssembly assembly;
                try {
                    assembly = GenomeAssembly.require(assemblyText);
                } catch (RuntimeException ex) {
                    return excluded(result, PhenopacketExclusionReason.UNSUPPORTED_ASSEMBLY,
                            "vcfRecord.genomeAssembly", "truth allele assembly is unsupported");
                }
                assemblies.add(assembly.name());
                if (!hasText(chromosome) || position <= 0 || !hasText(reference) || !hasText(alternate)) {
                    sawIncomplete = true;
                    continue;
                }
                for (String alt : alternate.split(",")) {
                    if (!hasText(alt) || alt.indexOf('<') >= 0 || alt.indexOf('>') >= 0
                            || alt.indexOf('[') >= 0 || alt.indexOf(']') >= 0) {
                        sawStructural = true;
                        continue;
                    }
                    try {
                        identities.add(NormalizedVariantKey.canonical(
                                assembly, chromosome, position, reference, alt));
                    } catch (RuntimeException ex) {
                        sawIncomplete = true;
                    }
                }
            }
        }
        if (assemblies.size() > 1) {
            return excluded(result, PhenopacketExclusionReason.CONFLICTING_TRUTH_ASSEMBLY,
                    "vcfRecord", "truth alleles use conflicting assemblies");
        }
        if (identities.isEmpty()) {
            if (sawStructural) {
                return excluded(result, PhenopacketExclusionReason.STRUCTURAL_OR_CNV_ONLY_TRUTH,
                        "interpretations", "structural/CNV truth is outside the SNV/small-indel preparation scope");
            }
            if (sawVcfRecord && sawIncomplete) {
                return excluded(result, PhenopacketExclusionReason.INCOMPLETE_VCF_ALLELE,
                        "vcfRecord", "truth VCF record lacks a complete assembly/coordinate/allele");
            }
            if (!sawVcfRecord && !root.findValues("expressions").isEmpty()) {
                return excluded(result, PhenopacketExclusionReason.HGVS_ONLY_TRUTH,
                        "variationDescriptor.expressions", "HGVS-only truth cannot form a canonical allele");
            }
            return excluded(result, PhenopacketExclusionReason.MISSING_TRUTH_VARIANT,
                    "interpretations", "an explicit VCF-like truth allele is required");
        }
        Collections.sort(identities, Comparator.comparing((@NonNull NormalizedVariantKey key) -> key.canonicalId()));
        if (hasText(root.path("interpretationStatus").asText(null))
                && "AMBIGUOUS".equalsIgnoreCase(root.path("interpretationStatus").asText())) {
            return excluded(result, PhenopacketExclusionReason.AMBIGUOUS_TRUTH_INTERPRETATION,
                    "interpretationStatus", "source interpretation is explicitly ambiguous");
        }

        BenchmarkDatasetCase datasetCase = toDatasetCase(root, context, caseId, schema,
                observed, excluded, diseaseIds, diseaseLabels, truthGeneIds, truthGenes, identities);
        PhenopacketCaseData data = new PhenopacketCaseData();
        data.setPhenopacketId(firstText(root, "id"));
        data.setCaseId(caseId);
        data.setDatasetId(context.getDatasetId());
        data.setDatasetVersion(context.getDatasetVersion());
        data.setObservedHpo(observed);
        data.setExcludedHpo(excluded);
        data.setDiseaseIds(diseaseIds);
        data.setDiseaseLabels(diseaseLabels);
        data.setTruthGeneIds(truthGeneIds);
        data.setTruthGenes(truthGenes);
        data.setInheritanceMode(datasetCase.getInheritanceMode());
        data.setDatasetCase(datasetCase);
        result.setCaseData(data);
        result.setStatus(PhenopacketEligibilityStatus.ELIGIBLE);
        return result;
    }

    public PhenopacketImportResult importJson(InputStream input, PhenopacketImportContext context) {
        if (input == null) {
            PhenopacketImportResult result = new PhenopacketImportResult();
            return invalid(result, "MALFORMED_JSON", "$", "Phenopacket input stream is required");
        }
        try {
            return importJson(new String(readAll(input), StandardCharsets.UTF_8), context);
        } catch (IOException ex) {
            PhenopacketImportResult result = new PhenopacketImportResult();
            return invalid(result, "MALFORMED_JSON", "$", "Phenopacket input could not be read");
        }
    }

    private BenchmarkDatasetCase toDatasetCase(JsonNode root, PhenopacketImportContext context,
                                                String caseId, String schema,
                                                List<PhenopacketHpoTerm> observed,
                                                List<PhenopacketHpoTerm> excluded,
                                                List<String> diseaseIds, List<String> diseaseLabels,
                                                List<String> truthGeneIds, List<String> truthGenes,
                                                List<NormalizedVariantKey> identities) {
        BenchmarkDatasetCase value = new BenchmarkDatasetCase();
        value.setCaseId(caseId);
        value.setSourceRecordId(hasText(context.getSourceRecordId())
                ? context.getSourceRecordId() : caseId);
        value.setSourceUri(context.getSourceUri());
        value.setSourceLicense(context.getSourceLicense());
        value.setInclusionStatus("INCLUDED");
        value.setAssembly(identities.get(0).getGenomeAssembly());
        value.setInheritanceMode(explicitInheritance(root));
        value.setTruthGeneIds(new ArrayList<String>(truthGeneIds));
        value.setTruthGenes(new ArrayList<String>(truthGenes));
        List<BenchmarkTruthVariant> variants = new ArrayList<BenchmarkTruthVariant>();
        for (int i = 0; i < identities.size(); i++) {
            BenchmarkTruthVariant variant = new BenchmarkTruthVariant();
            variant.setCanonicalIdentity(identities.get(i));
            variant.setTruthProvenance(DEFAULT_PROVENANCE);
            variant.setConfidence("SOURCE_REPORTED");
            variant.setRelationId("truth-" + (i + 1));
            variants.add(variant);
        }
        value.setTruthVariants(variants);
        value.setTruthProvenance(DEFAULT_PROVENANCE);
        value.setTruthConfidence("SOURCE_REPORTED");
        value.setTruthRelationship(identities.size() == 1 ? "SINGLE_VARIANT" : "MULTI_VARIANT_SOURCE_REPORTED");
        value.setOriginalHpoTerms(ids(observed));
        value.setExcludedHpoTerms(ids(excluded));
        Map<String, String> labels = new LinkedHashMap<String, String>();
        for (PhenopacketHpoTerm term : observed) {
            if (hasText(term.getLabel())) {
                labels.put(term.getId(), term.getLabel());
            }
        }
        for (PhenopacketHpoTerm term : excluded) {
            if (hasText(term.getLabel())) {
                labels.put(term.getId(), term.getLabel());
            }
        }
        value.setHpoTermLabels(labels);
        value.setHpoProvenance("PHENOPACKET_SOURCE");
        value.setPhenopacketId(firstText(root, "id"));
        value.setDiseaseIds(new ArrayList<String>(diseaseIds));
        value.setDiseaseLabels(new ArrayList<String>(diseaseLabels));
        value.setDiagnosisProvenance(DEFAULT_PROVENANCE);

        BenchmarkPreparationProvenance provenance = new BenchmarkPreparationProvenance();
        provenance.setPhenopacketSchemaVersion(schema);
        provenance.setAdapterVersion(context.getAdapterVersion());
        provenance.setEligibilityStatus(PhenopacketEligibilityStatus.ELIGIBLE.name());
        provenance.setSourceHpoFingerprint(sha256(join(ids(observed), "\n")));
        provenance.setTruthFingerprint(sha256(joinIdentity(identities)));
        provenance.setExecutionMode("NOT_EXECUTED");
        provenance.setValidationStatus("NOT_RUN");
        value.setPreparationProvenance(provenance);
        return value;
    }

    private void extractHpo(JsonNode root, List<PhenopacketHpoTerm> observed,
                            List<PhenopacketHpoTerm> excluded, List<PhenopacketImportIssue> issues) {
        Map<String, PhenopacketHpoTerm> positive = new LinkedHashMap<String, PhenopacketHpoTerm>();
        Map<String, PhenopacketHpoTerm> negative = new LinkedHashMap<String, PhenopacketHpoTerm>();
        JsonNode features = root.path("phenotypicFeatures");
        if (!features.isArray()) {
            return;
        }
        for (JsonNode feature : features) {
            JsonNode type = feature.path("type");
            String id = firstText(type, "id");
            if (!hasText(id) || !id.matches("HP:\\d{7}")) {
                issues.add(new PhenopacketImportIssue("WARNING", "NON_HPO_TERM", "phenotypicFeatures",
                        "non-HPO ontology term ignored"));
                continue;
            }
            PhenopacketHpoTerm term = new PhenopacketHpoTerm(id, firstText(type, "label"),
                    feature.path("excluded").asBoolean(false), firstText(feature.path("onset"), "label", "id"));
            if (term.isExcluded()) {
                negative.put(id, term);
            } else {
                positive.put(id, term);
            }
        }
        observed.addAll(positive.values());
        excluded.addAll(negative.values());
        Comparator<PhenopacketHpoTerm> byId = Comparator.comparing((@NonNull PhenopacketHpoTerm term) -> term.getId());
        Collections.sort(observed, byId);
        Collections.sort(excluded, byId);
    }

    private void extractDiseases(JsonNode root, List<String> ids, List<String> labels) {
        for (JsonNode disease : root.path("diseases")) {
            JsonNode term = disease.path("term");
            String id = firstText(term, "id");
            String label = firstText(term, "label");
            if (hasText(id) && !ids.contains(id)) ids.add(id);
            if (hasText(label) && !labels.contains(label)) labels.add(label);
        }
    }

    private void extractGenes(JsonNode root, List<String> ids, List<String> symbols) {
        for (JsonNode gene : root.findValues("gene")) {
            String id = firstText(gene, "id");
            String symbol = firstText(gene, "symbol", "name");
            if (hasText(id) && !ids.contains(id)) ids.add(id);
            if (hasText(symbol) && !symbols.contains(symbol)) symbols.add(symbol);
        }
        for (JsonNode geneContext : root.findValues("geneContext")) {
            String id = firstText(geneContext, "valueId", "id");
            String symbol = firstText(geneContext, "symbol", "name");
            if (hasText(id) && !ids.contains(id)) ids.add(id);
            if (hasText(symbol) && !symbols.contains(symbol)) symbols.add(symbol);
        }
        String direct = firstText(root, "truthGene", "geneSymbol");
        if (hasText(direct) && !symbols.contains(direct)) symbols.add(direct);
    }

    private boolean hasStructuralMarker(JsonNode root) {
        return !root.findValues("copyNumber").isEmpty()
                || !root.findValues("structuralVariant").isEmpty()
                || !root.findValues("structuralType").isEmpty();
    }

    private String explicitInheritance(JsonNode root) {
        String value = firstText(root, "inheritanceMode");
        if (hasText(value)) return value;
        for (JsonNode diagnosis : root.findValues("diagnosis")) {
            value = firstText(diagnosis, "inheritanceMode");
            if (hasText(value)) return value;
        }
        return "UNKNOWN";
    }

    private PhenopacketImportResult excluded(PhenopacketImportResult result,
                                              PhenopacketExclusionReason reason,
                                              String path, String message) {
        result.setStatus(PhenopacketEligibilityStatus.EXCLUDED);
        result.getExclusionReasons().add(reason);
        result.getIssues().add(new PhenopacketImportIssue("ERROR", reason.name(), path, message));
        return result;
    }

    private PhenopacketImportResult invalid(PhenopacketImportResult result,
                                             String code, String path, String message) {
        result.setStatus(PhenopacketEligibilityStatus.INVALID);
        result.getIssues().add(new PhenopacketImportIssue("ERROR", code, path, message));
        return result;
    }

    private boolean supportedSchema(String schema) {
        if (!hasText(schema)) return false;
        String normalized = schema.toLowerCase(Locale.ROOT).replace("-", "").replace(".", "");
        return "2".equals(normalized) || "20".equals(normalized)
                || "202".equals(normalized)
                || normalized.contains("phenopacketv2") || normalized.contains("schemav2");
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && hasText(value.asText())) return value.asText();
        }
        return null;
    }

    private static int firstInt(JsonNode node, String... fields) {
        if (node == null) return 0;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.canConvertToInt()) return value.asInt();
            if (value != null && value.isTextual()) {
                try { return Integer.parseInt(value.asText()); } catch (NumberFormatException ignored) { }
            }
        }
        return 0;
    }

    private static List<String> ids(List<PhenopacketHpoTerm> values) {
        List<String> ids = new ArrayList<String>();
        for (PhenopacketHpoTerm value : values) ids.add(value.getId());
        return ids;
    }

    private static String join(List<String> values, String separator) {
        return String.join(separator, values);
    }

    private static String joinIdentity(List<NormalizedVariantKey> values) {
        List<String> ids = new ArrayList<String>();
        for (NormalizedVariantKey value : values) ids.add(value.canonicalId());
        return join(ids, "\n");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        byte[] buffer = new byte[4096];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
