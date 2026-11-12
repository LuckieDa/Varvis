package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.springframework.test.util.ReflectionTestUtils;

/** Explicit synthetic test-only policy/observation transport; actual PM1 and PM2 cores execute.
 * The copied VHL comparator shape is scoped to invented HBB/GJB2 controls, never installed or
 * represented as a biological gene-specific rule or a reviewed production PM2 release.
 */
public final class MainPm1PeerBusinessMaterials {
    private MainPm1PeerBusinessMaterials() { }
    public record Scope(String gene, String hgnc, String disease, String transcript) { }
    public static final List<Scope> SCOPES = List.of(
            new Scope("HBB", "HGNC:4827", "MONDO:0013517", "ENST00000647020.1"),
            new Scope("GJB2", "HGNC:4284", "MONDO:0019497", "ENST00000382848.5"));

    public static Path writeKnowledge(ObjectMapper mapper, Path path) throws Exception {
        var template = new PublishedCriterionKnowledgeTest().document(); var out = template.deepCopy();
        out.put("policyId", "SYNTHETIC_PM1_CURRENT_PEER_BUSINESS_ONLY").put("policyVersion", "1.0.0");
        out.putArray("records"); out.putArray("specifications"); var hasher = new RuntimeEvidenceV2Adapter(mapper);
        for (int index = 0; index < SCOPES.size(); index++) {
            var scope = SCOPES.get(index); var spec = (ObjectNode) template.at("/specifications/0").deepCopy();
            String id = "GN" + (990 + index), key = id + "@1.0";
            spec.put("specificationKey", key); var original = (ObjectNode) spec.path("original"); original.put("entId", id);
            var gene = (ObjectNode) original.at("/ld/Gene/0/entContent/HGNC"); gene.put("symbol", scope.gene()).put("hgnc_id", scope.hgnc());
            var group = (ObjectNode) original.at("/ld/RuleSet/0/entContent/genes/0"); group.put("gene", scope.gene()).put("preferredTranscript", scope.transcript());
            ((ObjectNode) group.at("/diseases/0")).put("preferredMondoId", scope.disease()).put("preferredModeOfInheritance", "Autosomal recessive inheritance");
            var clause = (ObjectNode) original.at("/ld/CriteriaCode/0/entContent");
            clause.putArray("gene").add(scope.gene()); clause.putArray("disease").add(scope.disease());
            String text = "Located in a mutational hot spot and/or critical and well-established functional domain (_e.g._ active site of an enzyme) without benign variation "
                    + "* The variant must be rare (meeting PM2_Supporting) in order to be considered for PM1. "
                    + "* Met by a rare variant within the pore helix (amino acids 3 to 3). This region is known to be a critical region of " + scope.gene()
                    + " and has been confirmed to show an absence of likely benign or benign variants listed in gnomAD.";
            var descriptor = clause.putArray("strengthDescriptor").addObject().put("applicability", "Applicable").put("status", "approved")
                    .put("strength", "Moderate").put("text", text);
            var row = (ObjectNode) template.at("/records/0").deepCopy(); row.put("recordId", "synthetic-peer-" + index).put("specificationKey", key);
            row.withObject("/scope").put("hgncId", scope.hgnc()).put("mondoId", scope.disease()).put("moi", "AUTOSOMAL_RECESSIVE").put("transcript", scope.transcript());
            var interpretation = row.putObject("interpretation").put("type", "PUBLISHED_PM1_PROTEIN_LOCATIONS").put("version", "1.0.0")
                    .put("qualificationBasis", "PUBLISHED_EXPERT_SPECIFICATION").put("consequenceScope", "PURE_MISSENSE_SNV");
            interpretation.putObject("proteinIdentity").put("mode", "SOURCE_TRANSCRIPT_DEFINED");
            interpretation.putArray("residualConditions"); interpretation.putArray("precedence");
            String pointer = "/ld/CriteriaCode/0/entContent/strengthDescriptor/0", pin = hasher.canonicalSha256(descriptor);
            var rule = interpretation.putArray("rules").addObject().put("ruleId", "synthetic-residue-three").put("start", 3).put("end", 3)
                    .put("regionType", "CRITICAL_RESIDUE").put("effect", "SUPPORT").put("strength", "MODERATE")
                    .put("sourcePointer", pointer).put("sourceSha256", pin);
            rule.putArray("referenceResidues").addObject().put("position", 3).put("aminoAcid", "P");
            interpretation.putArray("crossCriterionRelations").addObject().put("criterionId", "ACMG_AMP_2015:PM2")
                    .put("relation", "REQUIRE_MET_AT_LEAST").put("minimumStrength", "SUPPORTING").put("sourcePointer", pointer).put("sourceSha256", pin);
            spec.put("entitySha256", hasher.canonicalSha256(original)); row.put("clauseSha256", hasher.canonicalSha256(original.at("/ld/CriteriaCode/0")));
            out.withArray("records").add(row); out.withArray("specifications").add(spec);
        }
        Files.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(out)); return path;
    }
    private static Pm2LocalExomesProfile profile(ObjectMapper mapper) {
        var policies = new ArrayList<ObjectNode>();
        for (var scope : SCOPES) {
            var policy = new Pm2LocalExomesTest().policy(0); policy.put("policyId", "SYNTHETIC_CURRENT_PM1_PEER_" + scope.gene());
            policy.withObject("/gene").put("hgncId", scope.hgnc()); policy.withObject("/disease").put("mondoId", scope.disease());
            policy.put("moi", "AUTOSOMAL_RECESSIVE"); policies.add(policy);
        }
        return Pm2LocalExomesProfile.synthetic(mapper, policies);
    }
    private static Pm2LocalExomesRuntime population(ObjectMapper mapper, Pm2LocalExomesProfile policy, AtomicInteger calls) {
        var facts = GnomadPopulationInputProfile.loadLocalBa1(); var resource = facts.getDocument().path("resources").get(0);
        VariantEvidenceProvider provider = new VariantEvidenceProvider() {
            public String source() { return "GNOMAD"; }
            public String version() { return resource.path("resourceVersion").asText(); }
            public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> variants, AnalysisContext analysis) {
                calls.incrementAndGet(); var result = new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
                for (var variant : variants) {
                    var key = variant.getObservation().getKey(); boolean rare = "11".equals(key.getChromosome());
                    var values = new Pm2LocalExomesTest().info("1", "100000", rare ? "0.0000001" : "0.00001");
                    String raw = "chr" + key.getChromosome() + "\t" + key.getPosition() + "\t.\t" + key.getReference() + "\t" + key.getAlternate()
                            + "\t.\tPASS\t" + String.join(";", values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
                    var details = new LinkedHashMap<String, Object>();
                    details.put("rawPopulationRecord", raw); details.put("populationMeasurements", facts.parse("PASS", values, 0, 1).getMeasurements());
                    details.put("populationResourceId", resource.path("resourceId").asText());
                    details.put("populationResourceIdentityFingerprintSha256", resource.path("identityFingerprintSha256").asText());
                    details.put("populationInputProfileId", facts.getProfileId()); details.put("populationInputProfileVersion", facts.getProfileVersion()); details.put("populationInputProfileSha256", facts.getProfileSha256());
                    details.put("resourceExecutionStatus", "SUCCEEDED"); details.put("resourceAvailabilityStatus", "AVAILABLE");
                    details.put("queryOutcomeStatus", "HIT"); details.put("resourceApplicabilityStatus", "APPLICABLE");
                    result.put(key, List.of(new EvidenceItem(source(), version(), "POPULATION", "HIT", null, "Synthetic observed HIT",
                            "Invented numeric transport for current peer binding; no clinical source adoption", details)));
                }
                return result;
            }
        };
        return new Pm2LocalExomesRuntime(mapper, policy, provider);
    }
    @SuppressWarnings("unchecked")
    public static LocalEvidenceBatchRuntime withSyntheticPopulationTransport(ObjectMapper mapper, LocalEvidenceBatchRuntime nativeBatch, AtomicInteger calls) {
        var policy = profile(mapper); var identity = nativeBatch.identity(); identity.set("syntheticTestPopulationProfile", policy.document());
        // Factories remain the actual native PM1/material implementations. Only PM2's source
        // transport and explicitly synthetic policy are injected; no criterion result is mocked.
        return new LocalEvidenceBatchRuntime(mapper, identity, () -> population(mapper, policy, calls),
                (Supplier<SpliceAiLocalObservationSession>) ReflectionTestUtils.getField(nativeBatch, "spliceFactory"),
                (Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime>) ReflectionTestUtils.getField(nativeBatch, "predictionFactory"),
                (LocalEvidenceBatchRuntime.ReferenceSetup) ReflectionTestUtils.getField(nativeBatch, "reference"),
                (Bp7SelectedBatch) ReflectionTestUtils.getField(nativeBatch, "bp7"),
                (Supplier<GeneralPm4Runtime>) ReflectionTestUtils.getField(nativeBatch, "pm4Factory"),
                (Supplier<Pvs1SelectedBatch>) ReflectionTestUtils.getField(nativeBatch, "pvs1Factory"),
                (Supplier<GeneralBp3Runtime>) ReflectionTestUtils.getField(nativeBatch, "bp3Factory"),
                (Supplier<GeneralPm1Runtime>) ReflectionTestUtils.getField(nativeBatch, "pm1Factory"),
                (Supplier<GeneralPp2Runtime>) ReflectionTestUtils.getField(nativeBatch, "pp2Factory"),
                (Supplier<GeneralBp1Runtime>) ReflectionTestUtils.getField(nativeBatch, "bp1Factory"), nativeBatch.identity().path("pp2GermlineDeclared").asBoolean());
    }
}
