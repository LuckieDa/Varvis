package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;

/** Explicit CLI acceptance probe, never run by Maven tests. Public predeclared tiny VCF only; no ranking claim. */
public final class Ba1CatalogPublicRuntimeProbe {
    private Ba1CatalogPublicRuntimeProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 8 && args.length != 9) throw new IllegalArgumentException("catalog catalogSHA publicVCF vepJSONL fasta fastaSHA tabix output [publicContextsJSON]");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var catalog = Ba1SpecificationCatalog.load(mapper, Path.of(args[0]), args[1]);
        if (!"READY".equals(catalog.identity().path("status").asText())) throw new IllegalArgumentException(catalog.identity().toString());
        Path input = Path.of(args[2]), annotation = Path.of(args[3]), output = Path.of(args[7]);
        if (Files.size(input) > 65536 || Files.size(annotation) > 4 * 1024 * 1024 || Files.exists(output))
            throw new IllegalArgumentException("Probe requires bounded inputs and a new output file");
        if (!args[5].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Verified reference manifest SHA required");
        var parsed = new VepJsonOutputParser(mapper).parseByVariantId(annotation, "VEP_CACHED", "116.0");
        if (!parsed.getWarnings().isEmpty()) throw new IllegalArgumentException(parsed.getWarnings().toString());
        var declaredContexts = mapper.createObjectNode();
        if (args.length == 9) {
            Path contextFile = Path.of(args[8]);
            if (Files.size(contextFile) > 65536) throw new IllegalArgumentException("Public context input too large");
            var loaded = mapper.readTree(contextFile.toFile());
            if (!(loaded instanceof ObjectNode contexts)) throw new IllegalArgumentException("Public contexts must be keyed by public VCF ID");
            declaredContexts = contexts;
            for (var contextsForAllele : contexts) {
                if (!contextsForAllele.isArray()) throw new IllegalArgumentException("Context list required");
                for (var context : contextsForAllele) {
                    if (!"SYNTHETIC".equals(context.path("dataClass").asText())) throw new IllegalArgumentException("Public acceptance uses synthetic patient context only");
                    adapter.validateCompleteContext(context);
                }
            }
        }
        var settings = new VariantAnalysisProperties();
        settings.getGnomad().setEnabled(true);
        settings.getGnomad().setPopulationInputEnabled(true);
        settings.getGnomad().setVersion(GnomadPopulationInputProfile.loadExecutableV2().getDocument().path("resources").path(0).path("resourceVersion").asText());
        settings.getGnomad().setTabixExecutable(args[6]);
        settings.getGnomad().setParallelism(1);
        int timeoutSeconds = Integer.parseInt(System.getProperty("varvis.ba1.probe.timeoutSeconds", "60"));
        if (timeoutSeconds < 1 || timeoutSeconds > 120) throw new IllegalArgumentException("Public probe query budget must be 1-120 seconds");
        int nativeSeconds = Integer.parseInt(System.getProperty("varvis.ba1.probe.nativeTimeoutSeconds", "60"));
        settings.getGnomad().setBa1NativeTimeoutSeconds(nativeSeconds);
        settings.getGnomad().setTimeout(Duration.ofSeconds(timeoutSeconds));
        settings.getGnomad().setRemoteTotalTimeout(Duration.ofSeconds(timeoutSeconds));
        settings.getGnomad().setRemoteMaxAttempts(1);
        settings.getGnomad().setRemoteIndexCacheDir(System.getProperty("varvis.ba1.probe.remoteIndexCacheDir"));
        settings.getGnomad().setAllowFullVcfFallback(false);
        settings.getGnomad().setAllowVcfInfoFallback(false);
        var runtime = new Ba1AlleleRuntime(mapper, settings, catalog);
        ObjectNode report = mapper.createObjectNode().put("schemaVersion", "a01-ba1-public-runtime-probe-v1")
                .put("scope", "ACTUAL_PUBLIC_REFERENCE_VEP_GNOMAD_SAME_PRODUCTION_RUNTIME; NOT_TOP_N_OR_PATIENT_ACCEPTANCE")
                .put("startedAt", java.time.Instant.now().toString()).put("referenceSha256FromVerifiedManifest", args[5]);
        report.set("runtimeIdentity", runtime.identity());
        report.putObject("transportConfiguration").put("perQueryTimeoutSeconds", timeoutSeconds).put("nativePerQueryTimeoutSeconds", nativeSeconds).put("maximumAttempts", 1)
                .put("explicitIndexCacheConfigured", settings.getGnomad().getRemoteIndexCacheDir() != null);
        var results = report.putArray("results");
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) if (!line.startsWith("#") && !line.isBlank()) rows.add(line.split("\t", -1));
        if (rows.isEmpty() || rows.size() > 10) throw new IllegalArgumentException("Public probe supports one to ten predeclared rows");
        try (var reference = new IndexedFastaSequenceFile(Path.of(args[4]))) {
            for (String[] row : rows) {
                int position = Integer.parseInt(row[1]);
                String actualRef = new String(reference.getSubsequenceAt(row[0], position, position + row[3].length() - 1).getBases(), StandardCharsets.US_ASCII);
                // Reference FASTA soft masking changes letter case, not nucleotide identity.
                if (!actualRef.equalsIgnoreCase(row[3])) throw new IllegalArgumentException("Reference mismatch: " + row[2]);
                String chromosome = row[0].replaceFirst("^chr", "");
                var key = new NormalizedVariantKey("GRCh38", chromosome, position, row[3], row[4]);
                var candidate = new VariantInterpretation(row[2], new VariantQcObservation(key, "public-no-patient",
                        null, null, null, null, null, "PASS", "SNV", "PUBLIC_PREDECLARED_PROBE"), List.of(), List.of());
                var annotations = parsed.getAnnotationsByVariantId().get(row[2]);
                if (annotations == null || annotations.isEmpty()) throw new IllegalArgumentException("No actual VEP annotation for " + row[2]);
                candidate.applyTranscriptAnnotations(annotations);
                ObjectNode allele = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", chromosome)
                        .put("position", position).put("reference", row[3]).put("alternate", row[4]);
                ObjectNode normalization = mapper.createObjectNode().put("method", "NATIVE_SNV_IDENTITY_REF_CHECK")
                        .put("methodVersion", "1.0.0").put("referenceSequenceSha256", args[5]).put("refValidation", "MATCH");
                var contexts = declaredContexts.path(row[2]);
                if (contexts.isArray()) for (var context : contexts)
                    if (!allele.equals(context.at("/variant/evidenceQueryAllele"))) throw new IllegalArgumentException("Public context allele does not match input");
                var result = runtime.evaluate(adapter.canonicalAlleleId(allele, normalization), candidate,
                        new AnalysisContext("a01-public-runtime", GenomeAssembly.GRCh38, AnalysisMode.WES), null, null,
                        contexts.isArray() ? contexts : mapper.createArrayNode());
                var record = results.addObject().put("publicId", row[2]);
                record.set("allele", allele); record.set("normalization", normalization);
                record.set("vepAnnotations", mapper.valueToTree(annotations)); record.set("ba1", result);
                if (contexts.isArray()) record.set("declaredPublicContexts", contexts.deepCopy());
                report.set("queryDiagnostics", mapper.valueToTree(runtime.queryDiagnostics()));
                Files.writeString(output, report.toPrettyString(), StandardCharsets.UTF_8);
                System.out.println(row[2] + " " + result.at("/evaluation/evaluationResult/status").asText()
                        + " " + result.at("/evaluation/evaluationResult/reasonCode").asText());
            }
        }
        report.put("completedAt", java.time.Instant.now().toString());
        Files.writeString(output, report.toPrettyString(), StandardCharsets.UTF_8);
    }
}
