package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonPropertyOrder({
        "assembly", "normalizationMode", "normalizationProfile", "hpoOntologyVersion",
        "phen2GeneVersion", "vepVersion", "clinvarVersion", "gnomadVersion", "caddVersion",
        "candidateCap", "vepCap", "featureFlags", "configurationFingerprint"
})
public class BenchmarkRunConfiguration {

    private String assembly;
    private String normalizationMode;
    private String normalizationProfile;
    private String hpoOntologyVersion;
    private String phen2GeneVersion;
    private String vepVersion;
    private String clinvarVersion;
    private String gnomadVersion;
    private String caddVersion;
    private Integer candidateCap;
    private Integer vepCap;
    private Map<String, Boolean> featureFlags = new LinkedHashMap<String, Boolean>();
    private String configurationFingerprint;

    public BenchmarkRunConfiguration() {
    }

    public String getAssembly() { return assembly; }
    public void setAssembly(String assembly) { this.assembly = assembly; }
    public String getNormalizationMode() { return normalizationMode; }
    public void setNormalizationMode(String normalizationMode) { this.normalizationMode = normalizationMode; }
    public String getNormalizationProfile() { return normalizationProfile; }
    public void setNormalizationProfile(String normalizationProfile) { this.normalizationProfile = normalizationProfile; }
    public String getHpoOntologyVersion() { return hpoOntologyVersion; }
    public void setHpoOntologyVersion(String hpoOntologyVersion) { this.hpoOntologyVersion = hpoOntologyVersion; }
    public String getPhen2GeneVersion() { return phen2GeneVersion; }
    public void setPhen2GeneVersion(String phen2GeneVersion) { this.phen2GeneVersion = phen2GeneVersion; }
    public String getVepVersion() { return vepVersion; }
    public void setVepVersion(String vepVersion) { this.vepVersion = vepVersion; }
    public String getClinvarVersion() { return clinvarVersion; }
    public void setClinvarVersion(String clinvarVersion) { this.clinvarVersion = clinvarVersion; }
    public String getGnomadVersion() { return gnomadVersion; }
    public void setGnomadVersion(String gnomadVersion) { this.gnomadVersion = gnomadVersion; }
    public String getCaddVersion() { return caddVersion; }
    public void setCaddVersion(String caddVersion) { this.caddVersion = caddVersion; }
    public Integer getCandidateCap() { return candidateCap; }
    public void setCandidateCap(Integer candidateCap) { this.candidateCap = candidateCap; }
    public Integer getVepCap() { return vepCap; }
    public void setVepCap(Integer vepCap) { this.vepCap = vepCap; }
    public Map<String, Boolean> getFeatureFlags() { return featureFlags; }
    public void setFeatureFlags(Map<String, Boolean> featureFlags) { this.featureFlags = featureFlags; }
    public String getConfigurationFingerprint() { return configurationFingerprint; }
    public void setConfigurationFingerprint(String configurationFingerprint) { this.configurationFingerprint = configurationFingerprint; }
}
