package org.nbscn.varvis.variantanalysis.benchmark.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.NonNull;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetManifest;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkManifestCodec;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkResourceReference;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthVariant;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketBenchmarkAdapter;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketEligibilityStatus;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketImportContext;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketImportResult;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Freezes a minimal public development-smoke manifest without running analysis. */
public class PublicBenchmarkSmokeSetFreezer {
    public static final String DATASET_ID = "phenopacket-store-0.1.27-grch38-singleton-smoke-v1";
    private final PhenopacketBenchmarkAdapter adapter = new PhenopacketBenchmarkAdapter();
    private final PublicBenchmarkSmokeSetSelector selector = new PublicBenchmarkSmokeSetSelector();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public Map<String, Object> freeze(Path phenopacketRoot, Path bedPath, Path outputDirectory,
                                      String archiveSha256, String bedSha256,
                                      String backgroundFingerprint) throws IOException {
        BedIndex bed = BedIndex.read(bedPath);
        List<Path> files;
        try (Stream<Path> stream = Files.walk(phenopacketRoot)) {
            files = stream.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> logical(phenopacketRoot.relativize(path))))
                    .collect(Collectors.toList());
        }
        Map<String, Integer> adapterCounts = new LinkedHashMap<String, Integer>();
        Map<String, Integer> exclusionCounts = new LinkedHashMap<String, Integer>();
        List<PublicBenchmarkSmokeCandidate> candidates = new ArrayList<PublicBenchmarkSmokeCandidate>();
        for (Path file : files) {
            String relative = logical(phenopacketRoot.relativize(file));
            PhenopacketImportContext context = new PhenopacketImportContext(DATASET_ID, "1.0", relative,
                    "https://github.com/monarch-initiative/phenopacket-store/releases/tag/0.1.27", "BSD-3-Clause");
            PhenopacketImportResult imported = adapter.importJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), context);
            increment(adapterCounts, imported.getStatus().name());
            imported.getExclusionReasons().forEach(reason -> increment(exclusionCounts, reason.name()));
            if (imported.getStatus() == PhenopacketEligibilityStatus.ELIGIBLE) {
                PublicBenchmarkSmokeCandidate candidate = new PublicBenchmarkSmokeCandidate();
                candidate.setImported(imported); candidate.setSourceRelativePath(relative);
                candidate.setSourceSha256(sha256(file));
                candidate.setAllTruthInsideBenchmarkRegion(allInside(imported, bed));
                candidates.add(candidate);
            }
        }
        SmokeSetSelectionResult selection = selector.select(candidates);
        List<SmokeSetSelectionDecision> selected = selection.getDecisions().stream()
                .filter(decision -> decision.getStatus() == SmokeSelectionStatus.SELECTED)
                .sorted(Comparator.comparing((@NonNull SmokeSetSelectionDecision decision) -> decision.getCaseId()))
                .collect(Collectors.toList());

        BenchmarkDatasetManifest dataset = dataset(selected, archiveSha256, bedSha256, Files.size(bedPath), backgroundFingerprint,
                selection.getAlgorithmVersion(), selection.getAlgorithmFingerprint());
        SmokeLockedTestExclusionRegistry registry = registry(selected);
        Map<String, Object> report = report(files.size(), adapterCounts, exclusionCounts, candidates,
                selection, selected, bedSha256);
        Files.createDirectories(outputDirectory);
        write(outputDirectory.resolve("dataset-manifest.json"), new BenchmarkManifestCodec().toJson(dataset));
        write(outputDirectory.resolve("locked-test-exclusion-registry.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(registry));
        write(outputDirectory.resolve("selection-acceptance.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        write(outputDirectory.resolve("g0-g1-g2-input-plan.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(inputPlan(selected, backgroundFingerprint)));
        return report;
    }

    private BenchmarkDatasetManifest dataset(List<SmokeSetSelectionDecision> selected, String archiveSha,
                                              String bedSha, long bedByteSize, String backgroundFingerprint,
                                              String algorithm, String algorithmFingerprint) {
        BenchmarkDatasetManifest dataset = new BenchmarkDatasetManifest();
        dataset.setDatasetId(DATASET_ID); dataset.setDatasetVersion("1.0"); dataset.setProtocolVersion("1.0");
        dataset.setCreatedAt("2026-07-15T00:00:00Z"); dataset.setStatus("FROZEN");
        dataset.setPurpose("DEVELOPMENT_SMOKE"); dataset.setEligibleForLockedTest(Boolean.FALSE);
        dataset.setDescription("DEVELOPMENT_SMOKE only; excluded from every future locked test set");
        dataset.setPhenopacketStoreVersion("0.1.27");
        dataset.setOfficialSourceUri("https://github.com/monarch-initiative/phenopacket-store/releases/tag/0.1.27");
        dataset.setLicense("BSD-3-Clause"); dataset.setArchiveFileName("all_phenopackets.zip");
        dataset.setArchiveByteSize(19431098L); dataset.setArchiveSha256(archiveSha);
        dataset.setSelectionRuleVersion(algorithm + ":" + algorithmFingerprint);
        dataset.setBackgroundDatasetId("1000-genomes-grch38-20190312-na12878-exome-like");
        dataset.setBackgroundDatasetVersion("20190312-gencode-v47-pad20"); dataset.setBackgroundAssembly("GRCh38");
        dataset.setBackgroundSampleId("NA12878"); dataset.setBackgroundSelectionBasis("frozen singleton exome-like derivation");
        dataset.setChromosomeScope(Arrays.asList("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","X"));
        BenchmarkResourceReference region = new BenchmarkResourceReference();
        region.setResourceId("gencode-v47-protein-coding-exon-union-pad20"); region.setResourceType("BENCHMARK_BED");
        region.setVersion("gencode-v47-exome-v1"); region.setAssembly("GRCh38");
        region.setSourceUri("https://www.gencodegenes.org/human/release_47.html");
        region.setLocalRelativePath("regions/gencode_v47_exome_v1/derived/gencode_v47_grch38_protein_coding_exon_union_pad20.bed");
        region.setFileName("gencode_v47_grch38_protein_coding_exon_union_pad20.bed");
        region.setByteSize(bedByteSize);
        region.setChecksumAlgorithm("SHA-256"); region.setChecksumValue(bedSha); region.setStatus("ACCEPTED");
        region.setDerivationProvenance("GENCODE v47 protein-coding transcript exon union with fixed +/-20 bp padding");
        dataset.setBackgroundResources(Collections.singletonList(region)); dataset.setImmutableBackgroundFingerprint(backgroundFingerprint);
        List<BenchmarkDatasetCase> cases = new ArrayList<BenchmarkDatasetCase>();
        for (SmokeSetSelectionDecision decision : selected) cases.add(decision.getCandidate().getImported().getCaseData().getDatasetCase());
        dataset.setCases(cases); dataset.setSingleton(true); dataset.setMonogenic(true); dataset.setSmallVariantsOnly(true);
        dataset.setAssemblyRequirement("GRCh38 native only; no liftOver");
        dataset.setTruthAcceptancePolicy("explicit assembly-aware VCF allele with full REF span inside frozen BED");
        dataset.setExclusionPolicy("development smoke cases are permanently excluded from locked test selection");
        return dataset;
    }

    private SmokeLockedTestExclusionRegistry registry(List<SmokeSetSelectionDecision> selected) {
        SmokeLockedTestExclusionRegistry registry = new SmokeLockedTestExclusionRegistry(); registry.setDatasetId(DATASET_ID);
        for (SmokeSetSelectionDecision decision : selected) {
            PublicBenchmarkSmokeCandidate candidate = decision.getCandidate(); registry.getCaseIds().add(decision.getCaseId());
            registry.getPhenopacketSha256().add(candidate.getSourceSha256());
            for (BenchmarkTruthVariant truth : candidate.getImported().getCaseData().getDatasetCase().getTruthVariants()) registry.getTruthCanonicalIdentities().add(truth.getCanonicalIdentity().canonicalId());
        }
        Collections.sort(registry.getCaseIds()); Collections.sort(registry.getPhenopacketSha256()); Collections.sort(registry.getTruthCanonicalIdentities());
        return registry;
    }

    private Map<String, Object> report(int total, Map<String,Integer> adapterCounts, Map<String,Integer> exclusions,
                                       List<PublicBenchmarkSmokeCandidate> candidates,
                                       SmokeSetSelectionResult selection, List<SmokeSetSelectionDecision> selected,
                                       String bedSha) {
        Map<String,Object> report=new LinkedHashMap<String,Object>();
        report.put("schemaVersion","benchmark-smoke-selection-acceptance-v1"); report.put("datasetId",DATASET_ID);
        report.put("purpose","DEVELOPMENT_SMOKE"); report.put("eligibleForLockedTest",false);
        report.put("inputJsonCount",total); report.put("adapterStatusCounts",adapterCounts); report.put("adapterExclusionReasonCounts",exclusions);
        report.put("selectorAlgorithmVersion",selection.getAlgorithmVersion()); report.put("selectorAlgorithmFingerprint",selection.getAlgorithmFingerprint());
        report.put("benchmarkRegionVersion","gencode-v47-exome-v1-pad20"); report.put("benchmarkRegionSha256",bedSha);
        report.put("selectionComplete",selection.isComplete());
        Map<String,Integer> selectorCounts=new LinkedHashMap<String,Integer>(); for(SmokeSetSelectionDecision d:selection.getDecisions())increment(selectorCounts,d.getStatus().name()); report.put("selectorStatusCounts",selectorCounts);
        Map<String,Integer> selectorReasons=new LinkedHashMap<String,Integer>(); for(SmokeSetSelectionDecision d:selection.getDecisions())for(SmokeSetExclusionReason reason:d.getReasons())increment(selectorReasons,reason.name()); report.put("selectorExclusionReasonCounts",selectorReasons);
        report.put("adapterEligibleInsideFrozenBed",candidates.stream().filter((@NonNull PublicBenchmarkSmokeCandidate candidate) -> candidate.isAllTruthInsideBenchmarkRegion()).count());
        report.put("adapterEligibleOutsideFrozenBed",candidates.stream().filter(candidate->!candidate.isAllTruthInsideBenchmarkRegion()).count());
        report.put("insideBedSnvCandidateCount",candidates.stream().filter((@NonNull PublicBenchmarkSmokeCandidate candidate) -> candidate.isAllTruthInsideBenchmarkRegion()).filter(candidate->containsType(candidate,"SNV")).count());
        report.put("insideBedSmallIndelCandidateCount",candidates.stream().filter((@NonNull PublicBenchmarkSmokeCandidate candidate) -> candidate.isAllTruthInsideBenchmarkRegion()).filter(candidate->containsType(candidate,"SMALL_INDEL")).count());
        Map<String,Integer> chromosomeCounts=new java.util.TreeMap<String,Integer>(Comparator.comparingInt((@NonNull String chromosome) -> chromosomeOrder(chromosome)).thenComparing(Comparator.naturalOrder())); for(PublicBenchmarkSmokeCandidate candidate:candidates)if(candidate.isAllTruthInsideBenchmarkRegion())for(BenchmarkTruthVariant truth:candidate.getImported().getCaseData().getDatasetCase().getTruthVariants())increment(chromosomeCounts,truth.getCanonicalIdentity().getChromosome()); report.put("insideBedTruthChromosomeCounts",chromosomeCounts);
        List<Map<String,Object>> cases=new ArrayList<Map<String,Object>>();
        for(SmokeSetSelectionDecision d:selected){PublicBenchmarkSmokeCandidate c=d.getCandidate(); BenchmarkDatasetCase dc=c.getImported().getCaseData().getDatasetCase(); Map<String,Object> item=new LinkedHashMap<String,Object>(); item.put("caseId",d.getCaseId());item.put("sourceRelativePath",c.getSourceRelativePath());item.put("phenopacketSha256",c.getSourceSha256());item.put("diseaseIds",c.getImported().getCaseData().getDiseaseIds());item.put("geneIds",c.getImported().getCaseData().getTruthGeneIds());item.put("genes",c.getImported().getCaseData().getTruthGenes());item.put("truthCanonicalIdentities",dc.getTruthVariants().stream().map(t->t.getCanonicalIdentity().canonicalId()).collect(Collectors.toList()));item.put("variantTypes",dc.getTruthVariants().stream().map(t->type(t.getCanonicalIdentity())).collect(Collectors.toList()));item.put("observedHpoCount",c.getImported().getCaseData().getObservedHpo().size());item.put("excludedHpoCount",c.getImported().getCaseData().getExcludedHpo().size());item.put("observedHpoIds",hpoIds(c,false));item.put("excludedHpoIds",hpoIds(c,true));item.put("truthRegionStatus","INSIDE_FROZEN_BED");item.put("g0Status","READY");item.put("g1Status","READY");item.put("g2Status","HUMAN_REVIEW_PENDING");cases.add(item);} report.put("cases",cases);
        report.put("rankingFieldsUsed",false); report.put("publicBenchmarkExecuted",false); return report;
    }

    Map<String, Object> inputPlan(List<SmokeSetSelectionDecision> selected, String backgroundFingerprint) {
        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("schemaVersion", "benchmark-smoke-input-plan-v1");
        plan.put("datasetId", DATASET_ID);
        plan.put("purpose", "DEVELOPMENT_SMOKE_PREPARATION_ONLY");
        List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
        for (SmokeSetSelectionDecision decision : selected) {
            PublicBenchmarkSmokeCandidate candidate = decision.getCandidate();
            List<String> sourceHpo = hpoIds(candidate, false);
            String shared = sha256Text(DATASET_ID + "|" + decision.getCaseId() + "|GRCh38|" + backgroundFingerprint);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("caseId", decision.getCaseId());
            item.put("sharedNonHpoConfigurationFingerprint", shared);
            List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
            groups.add(group("G0", "READY", "NONE", Collections.<String>emptyList(), sha256Text(""), "frozen no-HPO arm"));
            groups.add(group("G1", "READY", "SOURCE_PROVIDED", sourceHpo,
                    sha256Text(String.join(",", sourceHpo)), "Phenopacket observed HPO only"));
            groups.add(group("G2", "HUMAN_REVIEW_PENDING", "HUMAN_REVIEWED",
                    Collections.<String>emptyList(), null, "must be supplied and confirmed by a human before execution"));
            item.put("groups", groups);
            cases.add(item);
        }
        plan.put("cases", cases);
        plan.put("onlyAllowedArmDifference", "HPO_INPUT");
        plan.put("executable", false);
        plan.put("publicBenchmarkExecuted", false);
        return plan;
    }

    private Map<String, Object> group(String id, String status, String mode, List<String> terms,
                                      String checksum, String provenance) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("groupId", id); group.put("status", status); group.put("hpoInputMode", mode);
        group.put("hpoTerms", terms); group.put("hpoInputChecksum", checksum); group.put("hpoProvenance", provenance);
        return group;
    }

    private List<String> hpoIds(PublicBenchmarkSmokeCandidate candidate, boolean excluded) {
        List<String> ids = (excluded ? candidate.getImported().getCaseData().getExcludedHpo()
                : candidate.getImported().getCaseData().getObservedHpo()).stream()
                .map(term -> term.getId()).distinct().sorted().collect(Collectors.toList());
        return ids;
    }

    private boolean containsType(PublicBenchmarkSmokeCandidate candidate, String expected) { for(BenchmarkTruthVariant truth:candidate.getImported().getCaseData().getDatasetCase().getTruthVariants())if(expected.equals(type(truth.getCanonicalIdentity())))return true;return false; }
    private int chromosomeOrder(String chromosome){String normalized=chromosome.replaceFirst("(?i)^chr","");if("X".equalsIgnoreCase(normalized))return 23;if("Y".equalsIgnoreCase(normalized))return 24;try{return Integer.parseInt(normalized);}catch(NumberFormatException e){return 100;}}

    private boolean allInside(PhenopacketImportResult imported, BedIndex bed) { for(BenchmarkTruthVariant truth:imported.getCaseData().getDatasetCase().getTruthVariants()){NormalizedVariantKey k=truth.getCanonicalIdentity();if(!bed.contains(k.getChromosome(),k.getPosition()-1,k.getPosition()-1+k.getReference().length()))return false;}return true; }
    private String type(NormalizedVariantKey key){return key.getReference().length()==1&&key.getAlternate().length()==1?"SNV":"SMALL_INDEL";}
    private void increment(Map<String,Integer> map,String key){map.put(key,map.containsKey(key)?map.get(key)+1:1);}
    private String logical(Path path){return path.toString().replace('\\','/');}
    private String sha256(Path path)throws IOException{return hex(digest(Files.readAllBytes(path)));}
    private String sha256Text(String value){return hex(digest(value.getBytes(StandardCharsets.UTF_8)));}
    private byte[] digest(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception e){throw new IllegalStateException(e);}}
    private String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b));return s.toString();}
    private void write(Path path,String text)throws IOException{String normalized=text.endsWith("\n")?text:text+System.lineSeparator();byte[] content=normalized.getBytes(StandardCharsets.UTF_8);if(Files.exists(path)){if(Arrays.equals(Files.readAllBytes(path),content))return;throw new IOException("Refusing to overwrite a different frozen artifact: "+path.getFileName());}Path temp=path.resolveSibling(path.getFileName()+".temporary");Files.write(temp,content);try{Files.move(temp,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temp,path);}}

    static class BedIndex {
        private final Map<String,List<int[]>> intervals=new HashMap<String,List<int[]>>();
        static BedIndex read(Path path)throws IOException{BedIndex index=new BedIndex();for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)){if(line.trim().isEmpty()||line.startsWith("#"))continue;String[] p=line.split("\\t");if(p.length<3)continue;index.intervals.computeIfAbsent(p[0].replaceFirst("(?i)^chr",""),k->new ArrayList<int[]>()).add(new int[]{Integer.parseInt(p[1]),Integer.parseInt(p[2])});}return index;}
        boolean contains(String chromosome,int start,int end){List<int[]> list=intervals.get(chromosome.replaceFirst("(?i)^chr",""));if(list==null)return false;int lo=0,hi=list.size()-1;while(lo<=hi){int mid=(lo+hi)>>>1;int[] v=list.get(mid);if(end<=v[0])hi=mid-1;else if(start>=v[1])lo=mid+1;else return start>=v[0]&&end<=v[1];}return false;}
    }
}
