package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.AcmgRunControl;
import org.springframework.lang.Nullable;

/** Run-scoped source routing and reuse of the existing sealed population observations. */
public final class PopulationRuntimeRouting {
    public static final String VERSION = "population-policy-source-routing-v1";
    public static final String ACTIVATION = "POLICY_ROUTED_POPULATION_V1";
    private final ObjectMapper mapper;
    private final Map<String, Source> sources;
    private final Map<String, ObjectNode> observations = new LinkedHashMap<>();
    private final Map<String, ObjectNode> failures = new LinkedHashMap<>();
    private int providerCalls;

    private static final class Source {
        final GnomadPopulationInputProfile profile;
        final Supplier<VariantEvidenceProvider> factory;
        final ObjectNode identity;
        final boolean enabled, populationInputEnabled;
        @Nullable VariantEvidenceProvider provider;
        Source(GnomadPopulationInputProfile profile, Supplier<VariantEvidenceProvider> factory,
                ObjectNode identity, boolean enabled, boolean populationInputEnabled) {
            this.profile=profile; this.factory=factory; this.identity=identity;
            this.enabled=enabled; this.populationInputEnabled=populationInputEnabled;
        }
        VariantEvidenceProvider provider() {
            var existing=provider;
            if (existing != null) return existing;
            var created=Objects.requireNonNull(factory.get(), "population provider"); provider=created; return created;
        }
    }
    private PopulationRuntimeRouting(ObjectMapper mapper, Source exomes, Source joint) {
        this.mapper=Objects.requireNonNull(mapper); sources=Map.of("EXOMES",exomes,"JOINT",joint);
    }

    static PopulationRuntimeRouting configured(ObjectMapper mapper, VariantAnalysisProperties exomes,
            VariantAnalysisProperties joint) {
        var local=exomesSettings(exomes); var remote=copySettings(joint);
        var lp=GnomadPopulationInputProfile.loadLocalBa1(); var jp=GnomadPopulationInputProfile.loadExecutableV2();
        return new PopulationRuntimeRouting(mapper,
                new Source(lp,()->Ba1LocalExomes.provider(local),sourceIdentity(mapper,lp,local),
                        local.getGnomad().isEnabled(),local.getGnomad().isPopulationInputEnabled()),
                new Source(jp,()->new GnomadTabixProvider(remote,jp,true,true),sourceIdentity(mapper,jp,remote),
                        remote.getGnomad().isEnabled(),remote.getGnomad().isPopulationInputEnabled()));
    }
    static PopulationRuntimeRouting development(ObjectMapper mapper, VariantEvidenceProvider exomes, VariantEvidenceProvider joint) {
        var lp=GnomadPopulationInputProfile.loadLocalBa1(); var jp=GnomadPopulationInputProfile.loadExecutableV2();
        return new PopulationRuntimeRouting(mapper,developmentSource(mapper,lp,exomes),developmentSource(mapper,jp,joint));
    }
    private static Source developmentSource(ObjectMapper mapper, GnomadPopulationInputProfile profile, VariantEvidenceProvider provider) {
        Objects.requireNonNull(provider);
        var config=mapper.createObjectNode().put("kind","EXPLICIT_DEVELOPMENT_PROVIDER")
                .put("providerClass",provider.getClass().getName()).put("provider",provider.source()).put("version",provider.version());
        var identity=binding(mapper,profile,config).put("developmentOnly",true);
        return new Source(profile,()->provider,identity,true,true);
    }

    /** Only a validated policy determines the fixed profile; no request/result/sex-based choice exists. */
    public static ObjectNode route(ObjectMapper mapper, JsonNode policy) {
        var result=mapper.createObjectNode().put("schemaVersion",VERSION);
        String issue=Bs1Policy.issue(policy);
        if (issue != null) return blocked(result,issue);
        String metric=policy.at("/statistic/metricId").asText(); String dataset;
        if (Set.of("gnomad.exomes.faf95max",Bs1FrequencyStatistic.METRIC,Bs1SexChromosomePolicy.AF,Bs1SexChromosomePolicy.FAF95).contains(metric)) dataset="EXOMES";
        else if (Set.of("gnomad.joint.faf95max",Bs1SexChromosomePolicy.JOINT_AF,Bs1SexChromosomePolicy.JOINT_FAF95).contains(metric)) dataset="JOINT";
        else return blocked(result,"BS1_POPULATION_METRIC_ROUTE_UNSUPPORTED");
        String required=policy.at("/scope/sexChromosome/requiredDataset").asText();
        String declared=policy.at("/dataScope/dataset").asText();
        if (!required.isEmpty() && !dataset.equals(required) || !declared.isEmpty() && !dataset.equals(declared))
            return blocked(result,"BS1_POPULATION_DATASET_CONFLICT");
        var profile="JOINT".equals(dataset) ? GnomadPopulationInputProfile.loadExecutableV2() : GnomadPopulationInputProfile.loadLocalBa1();
        var resource=profile.getDocument().path("resources").path(0);
        if (!"COMPATIBLE".equals(policy.at("/resourceCompatibility/status").asText())) return blocked(result,"BS1_RESOURCE_COMPATIBILITY_PENDING");
        if (!resource.path("resourceVersion").equals(policy.at("/resourceCompatibility/resourceVersion"))
                || !resource.path("identityFingerprintSha256").equals(policy.at("/resourceCompatibility/resourceSha256")))
            return blocked(result,"BS1_RESOURCE_PAIR_MISMATCH");
        var adapter=new RuntimeEvidenceV2Adapter(mapper);
        result.put("status","READY").put("dataset",dataset).put("metricId",metric)
                .put("policyId",policy.path("policyId").asText()).put("policySha256",adapter.canonicalSha256(policy))
                .put("profileId",profile.getProfileId()).put("profileSha256",profile.getProfileSha256());
        result.set("resourceContract",resource.deepCopy());
        result.put("routeId","population-route-sha256-"+adapter.canonicalSha256(result)); return result;
    }
    public ObjectNode identity() {
        var result=mapper.createObjectNode().put("schemaVersion",VERSION).put("cacheScope","ONE_RUNTIME_RUN")
                .put("selectionBasis","VALIDATED_POLICY_DATASET_METRIC_RESOURCE").put("componentSummation",false);
        var entries=result.putArray("sources");
        for (String dataset:List.of("EXOMES","JOINT")) entries.add(sources.get(dataset).identity.deepCopy());
        result.put("bindingSha256",new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(result)); return result;
    }
    public int providerCalls() { return providerCalls; }

    ObjectNode observe(JsonNode policy, JsonNode context, VariantInterpretation candidate, AnalysisContext analysis,
            String sourceSnapshot, @Nullable JsonNode shared) {
        var route=route(mapper,policy);
        if (!"READY".equals(route.path("status").asText())) return route;
        if (sourceSnapshot == null || !sourceSnapshot.matches("[0-9a-f]{64}"))
            return blocked(route,"BS1_POPULATION_SOURCE_SNAPSHOT_INVALID");
        var source=sources.get(route.path("dataset").asText());
        if (source == null) return blocked(route,"BS1_POPULATION_SOURCE_NOT_CONFIGURED");
        route.set("effectiveSource",source.identity.deepCopy());
        var adapter=new RuntimeEvidenceV2Adapter(mapper);
        route.put("executionRouteId","population-execution-route-sha256-"+adapter.canonicalSha256(route));
        if (!source.profile.getDocument().at("/resources/0/assembly").equals(context.at("/variant/evidenceQueryAllele/assembly")))
            return blocked(route,"BS1_POPULATION_QUERY_ASSEMBLY_UNSUPPORTED");
        var identity=mapper.createObjectNode().put("profileSha256",source.profile.getProfileSha256())
                .put("effectiveSourceSha256",source.identity.path("configurationSha256").asText())
                .put("sourceSnapshot",sourceSnapshot).put("dataClass",context.path("dataClass").asText())
                .put("alleleId",context.at("/variant/alleleId").asText());
        identity.set("queryAllele",context.at("/variant/evidenceQueryAllele").deepCopy());
        String key=adapter.canonicalSha256(identity);
        ObjectNode adopted=null; boolean fromBa1=false;
        try {
            if (shared != null && PopulationObservation.VERSION.equals(shared.path("schemaVersion").asText())
                    && sameProfileClaim(shared,source.profile)) {
                PopulationObservation.verify(mapper,shared,context,sourceSnapshot,source.profile);
                adopted=((ObjectNode)shared).deepCopy();
            } else if (shared != null && "EXOMES".equals(route.path("dataset").asText())
                    && "BA1_LOCAL_EXOMES_V1".equals(shared.at("/dataScope/policyId").asText())) {
                adopted=Bs1LocalExomes.sharedObservation(mapper,context,shared,sourceSnapshot); fromBa1=true;
            }
        } catch (IllegalArgumentException invalid) {
            var failure=blocked(route,"BS1_SHARED_POPULATION_VERIFICATION_FAILED");
            failures.put(key,failure.deepCopy()); return failure;
        }
        var failed=failures.get(key);
        if (failed != null) return blocked(route,failed.path("reasonCode").asText()).put("observationReusedWithinRun",true);
        var cached=observations.get(key);
        if (cached != null) {
            if (adopted != null && !sameFacts(cached,adopted)) {
                var failure=blocked(route,"BS1_SHARED_POPULATION_CONFLICT"); failures.put(key,failure.deepCopy()); return failure;
            }
            PopulationObservation.verify(mapper,cached,context,sourceSnapshot,source.profile);
            return observed(route,cached,true,fromBa1);
        }
        if (adopted != null) {
            observations.put(key,adopted.deepCopy()); return observed(route,adopted,false,fromBa1);
        }
        if (!source.enabled) return rememberFailure(key,blocked(route,"BS1_POPULATION_SOURCE_DISABLED"));
        if (!source.populationInputEnabled) return rememberFailure(key,blocked(route,"BS1_POPULATION_INPUT_DISABLED"));
        VariantEvidenceProvider provider;
        try { provider=source.provider(); }
        catch (RuntimeException failure) {
            if (failure instanceof AcmgRunControl.Stopped stopped) throw stopped;
            return rememberFailure(key,blocked(route,"BS1_POPULATION_SOURCE_CONFIGURATION_INVALID"));
        }
        try {
            providerCalls++;
            var acquired=PopulationObservation.acquire(mapper,provider,candidate,analysis,source.profile,sourceSnapshot,context.path("dataClass").asText());
            PopulationObservation.verify(mapper,acquired,context,sourceSnapshot,source.profile);
            observations.put(key,acquired.deepCopy()); return observed(route,acquired,false,false);
        } catch (RuntimeException failure) {
            if (failure instanceof AcmgRunControl.Stopped stopped) throw stopped;
            return rememberFailure(key,blocked(route,failure instanceof IllegalArgumentException
                    ? "BS1_OBSERVATION_VERIFICATION_FAILED" : "BS1_POPULATION_SOURCE_EXECUTION_FAILED"));
        }
    }
    private ObjectNode rememberFailure(String key, ObjectNode result) { failures.put(key,result.deepCopy()); return result; }
    private ObjectNode observed(ObjectNode route, JsonNode observation, boolean reused, boolean fromBa1) {
        var result=route.deepCopy(); result.set("observation",observation.deepCopy());
        return result.put("observationReusedWithinRun",reused).put("observationReusedFromBa1",fromBa1);
    }
    private static boolean sameProfileClaim(JsonNode shared,GnomadPopulationInputProfile profile) {
        var resource=profile.getDocument().path("resources").path(0);
        return profile.getProfileSha256().equals(shared.path("profileSha256").asText())
                || resource.path("identityFingerprintSha256").equals(shared.at("/resourceContract/identityFingerprintSha256"))
                || resource.path("identityFingerprintSha256").equals(shared.at("/carrier/source/resourceSha256"));
    }
    private static boolean sameFacts(JsonNode a,JsonNode b) {
        if (!a.path("rawRecordSha256").equals(b.path("rawRecordSha256"))) return false;
        for (String axis:List.of("resourceExecution","resourceAvailability","queryOutcome","resourceApplicability"))
            if (!a.path("carrier").path(axis).path("status").equals(b.path("carrier").path(axis).path("status"))) return false;
        return a.at("/carrier/measurements").equals(b.at("/carrier/measurements"));
    }
    private static ObjectNode blocked(ObjectNode value,String reason) { return value.put("status","BLOCKED").put("reasonCode",reason); }

    // Preserve the caller gates; after they pass, use the existing local-only BA1 transport settings.
    private static VariantAnalysisProperties exomesSettings(VariantAnalysisProperties original) {
        var result=new VariantAnalysisProperties(); var target=result.getGnomad(); var source=original.getGnomad();
        target.setEnabled(source.isEnabled()); target.setPopulationInputEnabled(source.isPopulationInputEnabled()); target.setVersion("gnomAD exomes v4.1 GRCh38");
        target.setGrch38Files(source.getGrch38Files().stream().map(mappedItem -> mappedItem.toString()).collect(Collectors.joining(",")));
        target.setTabixExecutable(source.getTabixExecutable()); target.setTimeout(source.getTimeout());
        target.setLocalPopulationInProcessEnabled(source.isLocalPopulationInProcessEnabled()); target.setParallelism(1);
        target.setAllowFullVcfFallback(false); target.setAllowVcfInfoFallback(false); return result;
    }
    private static VariantAnalysisProperties copySettings(VariantAnalysisProperties original) {
        var result=new VariantAnalysisProperties(); var t=result.getGnomad(); var s=original.getGnomad();
        t.setEnabled(s.isEnabled()); t.setPopulationInputEnabled(s.isPopulationInputEnabled()); t.setVersion(s.getVersion());
        t.setTabixExecutable(s.getTabixExecutable()); t.setBcftoolsExecutable(s.getBcftoolsExecutable());
        t.setGrch38Files(s.getGrch38Files().stream().map(mappedItem -> mappedItem.toString()).collect(Collectors.joining(",")));
        t.setGrch38CompactFiles(s.getGrch38CompactFiles().stream().map(mappedItem -> mappedItem.toString()).collect(Collectors.joining(",")));
        t.setAllowFullVcfFallback(s.isAllowFullVcfFallback()); t.setAllowVcfInfoFallback(s.isAllowVcfInfoFallback());
        t.setLocalPopulationInProcessEnabled(s.isLocalPopulationInProcessEnabled()); t.setBa1LocalExomesEnabled(s.isBa1LocalExomesEnabled());
        t.setAlleleFrequencyFields(String.join(",",s.getAlleleFrequencyFields())); t.setTimeout(s.getTimeout());
        t.setQueryWindowBp(s.getQueryWindowBp()); t.setQueryMergeGapBp(s.getQueryMergeGapBp()); t.setTabixCacheMb(s.getTabixCacheMb()); t.setParallelism(s.getParallelism());
        t.setRemoteMaxAttempts(s.getRemoteMaxAttempts()); if (s.getRemoteTotalTimeout()!=null) t.setRemoteTotalTimeout(s.getRemoteTotalTimeout());
        var cache=s.getRemoteIndexCacheDir(); if (cache!=null) t.setRemoteIndexCacheDir(cache.toString());
        t.setRemoteTransport(s.getRemoteTransport()); t.setRangeBindingsFile(s.getRangeBindingsFile()); t.setBa1NativeTimeoutSeconds(s.getBa1NativeTimeoutSeconds()); return result;
    }
    private static ObjectNode sourceIdentity(ObjectMapper mapper,GnomadPopulationInputProfile profile,VariantAnalysisProperties settings) {
        var g=settings.getGnomad(); var config=mapper.createObjectNode().put("providerClass",GnomadTabixProvider.class.getName())
                .put("enabled",g.isEnabled()).put("populationInputEnabled",g.isPopulationInputEnabled()).put("version",g.getVersion())
                .put("localPopulationInProcessEnabled",g.isLocalPopulationInProcessEnabled()).put("remoteTransport",g.getRemoteTransport())
                .put("fullVcfFallback",g.isAllowFullVcfFallback()).put("vcfInfoFallback",g.isAllowVcfInfoFallback())
                .put("ba1LocalExomesEnabled",g.isBa1LocalExomesEnabled()).put("timeout",Objects.toString(g.getTimeout(),"UNSPECIFIED"))
                .put("remoteTotalTimeout",Objects.toString(g.getRemoteTotalTimeout(),"UNSPECIFIED")).put("remoteMaxAttempts",g.getRemoteMaxAttempts())
                .put("queryWindowBp",g.getQueryWindowBp()).put("queryMergeGapBp",g.getQueryMergeGapBp()).put("tabixCacheMb",g.getTabixCacheMb()).put("parallelism",g.getParallelism())
                .put("tabixCommandSha256",hash(g.getTabixExecutable())).put("bcftoolsCommandSha256",hash(g.getBcftoolsExecutable()))
                .put("remoteIndexCacheLocationSha256",hash(Objects.toString(g.getRemoteIndexCacheDir(),"UNCONFIGURED")))
                .put("rangeBindingsLocationSha256",hash(Objects.toString(g.getRangeBindingsFile(),"UNCONFIGURED")))
                .put("ba1NativeTimeoutSeconds",Objects.toString(g.getBa1NativeTimeoutSeconds(),"UNSPECIFIED"));
        config.set("alleleFrequencyFields",mapper.valueToTree(g.getAlleleFrequencyFields()));
        config.set("localPopulationTransport",mapper.valueToTree(GnomadTabixProvider.localPopulationTransportIdentity()));
        var full=config.putArray("fullSitesFiles"); for (Path path:g.getGrch38Files()) full.add(fileMetadata(mapper,path,false));
        var compact=config.putArray("compactFiles"); for (Path path:g.getGrch38CompactFiles()) compact.add(fileMetadata(mapper,path,false));
        String manifest=g.getRangeBindingsFile(); if (manifest!=null && !manifest.isBlank()) config.set("rangeBindings",fileMetadata(mapper,Path.of(manifest),true));
        return binding(mapper,profile,config).put("enabled",g.isEnabled()).put("populationInputEnabled",g.isPopulationInputEnabled());
    }
    private static ObjectNode binding(ObjectMapper mapper,GnomadPopulationInputProfile profile,JsonNode config) {
        var resource=profile.getDocument().path("resources").path(0);
        return mapper.createObjectNode().put("dataset",profile.getDatasetType()).put("profileSha256",profile.getProfileSha256())
                .put("resourceSha256",resource.path("identityFingerprintSha256").asText())
                .put("configurationSha256",new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(config));
    }
    private static ObjectNode fileMetadata(ObjectMapper mapper,Path path,boolean digestSmallFile) {
        var result=mapper.createObjectNode().put("locationSha256",hash(path.toAbsolutePath().normalize().toString()));
        try {
            if (!Files.isRegularFile(path)) return result.put("status","MISSING");
            result.put("bytes",Files.size(path)).put("mtime",Files.getLastModifiedTime(path).toString());
            if (digestSmallFile && Files.size(path)<=4*1024*1024) result.put("contentSha256",hash(Files.readAllBytes(path)));
            if (!digestSmallFile) { Path index=Path.of(path+".tbi"); if (Files.isRegularFile(index) && Files.size(index)<=4*1024*1024) result.put("indexSha256",hash(Files.readAllBytes(index))); }
        } catch (IOException | RuntimeException unavailable) { result.put("status","UNAVAILABLE"); }
        return result;
    }
    private static String hash(String value) { return hash(Objects.requireNonNullElse(value,"UNSPECIFIED").getBytes(StandardCharsets.UTF_8)); }
    private static String hash(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
