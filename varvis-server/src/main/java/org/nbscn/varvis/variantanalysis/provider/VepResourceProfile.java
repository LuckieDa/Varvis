package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;

import java.nio.file.Path;

public class VepResourceProfile {

    private final GenomeAssembly assembly;
    private final boolean enabled;
    private final String executable;
    private final Path cacheDir;
    private final Path fasta;
    private final Path pluginsDir;
    private final String species;
    private final String version;
    private final String ensemblCacheVersion;
    private final String maneVersion;
    private final String lofteeVersion;
    private final boolean legacyGrch38Profile;

    public VepResourceProfile(GenomeAssembly assembly,
                              boolean enabled,
                              String executable,
                              Path cacheDir,
                              Path fasta,
                              Path pluginsDir,
                              String species,
                              String version,
                              String ensemblCacheVersion,
                              String maneVersion,
                              String lofteeVersion,
                              boolean legacyGrch38Profile) {
        this.assembly = assembly;
        this.enabled = enabled;
        this.executable = executable;
        this.cacheDir = cacheDir;
        this.fasta = fasta;
        this.pluginsDir = pluginsDir;
        this.species = hasText(species) ? species.trim() : "homo_sapiens";
        this.version = version;
        this.ensemblCacheVersion = ensemblCacheVersion;
        this.maneVersion = maneVersion;
        this.lofteeVersion = lofteeVersion;
        this.legacyGrch38Profile = legacyGrch38Profile;
    }

    public static VepResourceProfile fromResourceProfile(
            GenomeAssembly assembly,
            VariantAnalysisProperties.Vep vep,
            VariantAnalysisProperties.Vep.ResourceProfile profile) {
        return new VepResourceProfile(
                assembly,
                profile.isEnabled(),
                hasText(profile.getExecutable()) ? profile.getExecutable() : vep.getExecutable(),
                profile.getCacheDir(),
                profile.getFasta(),
                profile.getPluginsDir() == null ? vep.getPluginsDir() : profile.getPluginsDir(),
                profile.effectiveSpecies(),
                profile.effectiveVersion(vep.getVersion()),
                profile.effectiveEnsemblCacheVersion(vep.getEnsemblCacheVersion()),
                profile.effectiveManeVersion(vep.getManeVersion()),
                profile.effectiveLofteeVersion(vep.getLofteeVersion()),
                false
        );
    }

    public static VepResourceProfile legacyGrch38(VariantAnalysisProperties.Vep vep) {
        return new VepResourceProfile(
                GenomeAssembly.GRCh38,
                vep.isEnabled(),
                vep.getExecutable(),
                vep.getCacheDir(),
                vep.getFasta(),
                vep.getPluginsDir(),
                "homo_sapiens",
                vep.getVersion(),
                vep.getEnsemblCacheVersion(),
                vep.getManeVersion(),
                vep.getLofteeVersion(),
                true
        );
    }

    public GenomeAssembly getAssembly() {
        return assembly;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getExecutable() {
        return executable;
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    public Path getFasta() {
        return fasta;
    }

    public Path getPluginsDir() {
        return pluginsDir;
    }

    public String getSpecies() {
        return species;
    }

    public String getVersion() {
        return version;
    }

    public String getEnsemblCacheVersion() {
        return ensemblCacheVersion;
    }

    public String getManeVersion() {
        return maneVersion;
    }

    public String getLofteeVersion() {
        return lofteeVersion;
    }

    public boolean isLegacyGrch38Profile() {
        return legacyGrch38Profile;
    }

    public String providerIdentity() {
        return hasText(version) && assembly != null ? version + "-" + assembly.name() : version;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
