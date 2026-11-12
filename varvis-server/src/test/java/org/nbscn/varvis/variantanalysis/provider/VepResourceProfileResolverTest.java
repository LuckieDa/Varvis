package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VepResourceProfileResolverTest {

    @Test
    void legacyConfigurationOnlyResolvesGrch38() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutable("vep");
        properties.getVep().setCacheDir("cache");
        properties.getVep().setFasta("GRCh38.fa");

        VepResourceProfileResolver resolver = new VepResourceProfileResolver(properties);

        assertTrue(resolver.resolve(GenomeAssembly.GRCh38).isResolved());
        VepResourceProfileResolution grch37 = resolver.resolve(GenomeAssembly.GRCh37);
        assertFalse(grch37.isResolved());
        assertEquals("UNAVAILABLE", grch37.getStatus());
        assertEquals("RESOURCE_PROFILE_MISSING", grch37.getReason());
    }

    @Test
    void explicitProfilesResolveByMatchingAssemblyOnly() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        profile(properties, "GRCh38", true);
        profile(properties, "GRCh37", true);

        VepResourceProfileResolver resolver = new VepResourceProfileResolver(properties);

        VepResourceProfileResolution grch37 = resolver.resolve(GenomeAssembly.GRCh37);
        VepResourceProfileResolution grch38 = resolver.resolve(GenomeAssembly.GRCh38);

        assertTrue(grch37.isResolved());
        assertEquals(GenomeAssembly.GRCh37, grch37.getProfile().getAssembly());
        assertTrue(grch38.isResolved());
        assertEquals(GenomeAssembly.GRCh38, grch38.getProfile().getAssembly());
    }

    @Test
    void keyProfileAssemblyMismatchIsRejected() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantAnalysisProperties.Vep.ResourceProfile profile =
                new VariantAnalysisProperties.Vep.ResourceProfile();
        profile.setEnabled(true);
        profile.setAssembly("GRCh38");
        properties.getVep().getResources().put("GRCh37", profile);

        VepResourceProfileResolution resolution =
                new VepResourceProfileResolver(properties).resolve(GenomeAssembly.GRCh37);

        assertFalse(resolution.isResolved());
        assertEquals("UNAVAILABLE", resolution.getStatus());
        assertEquals("RESOURCE_PROFILE_ASSEMBLY_MISMATCH", resolution.getReason());
    }

    @Test
    void grch38MissingExplicitProfileDoesNotFallbackToGrch37() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        profile(properties, "GRCh37", true);

        VepResourceProfileResolution resolution =
                new VepResourceProfileResolver(properties).resolve(GenomeAssembly.GRCh38);

        assertFalse(resolution.isResolved());
        assertEquals("UNAVAILABLE", resolution.getStatus());
        assertEquals("RESOURCE_PROFILE_MISSING", resolution.getReason());
    }

    @Test
    void explicitGrch38ProfileTakesPrecedenceOverLegacyConfiguration() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutable("legacy-vep");
        properties.getVep().setCacheDir("legacy-cache");
        properties.getVep().setFasta("legacy.fa");
        profile(properties, "GRCh38", true);

        VepResourceProfileResolution resolution =
                new VepResourceProfileResolver(properties).resolve(GenomeAssembly.GRCh38);

        assertTrue(resolution.isResolved());
        assertEquals("cache-GRCh38", resolution.getProfile().getCacheDir().toString());
        assertEquals("GRCh38.fa", resolution.getProfile().getFasta().toString());
    }

    @Test
    void disabledExplicitProfileDoesNotFallbackToLegacyConfiguration() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutable("legacy-vep");
        properties.getVep().setCacheDir("legacy-cache");
        properties.getVep().setFasta("legacy.fa");
        profile(properties, "GRCh38", false);

        VepResourceProfileResolution resolution =
                new VepResourceProfileResolver(properties).resolve(GenomeAssembly.GRCh38);

        assertTrue(resolution.isResolved());
        assertFalse(resolution.getProfile().isEnabled());
        assertEquals("cache-GRCh38", resolution.getProfile().getCacheDir().toString());
    }

    private void profile(VariantAnalysisProperties properties, String assembly, boolean enabled) {
        VariantAnalysisProperties.Vep.ResourceProfile profile =
                new VariantAnalysisProperties.Vep.ResourceProfile();
        profile.setEnabled(enabled);
        profile.setAssembly(assembly);
        profile.setExecutable("vep");
        profile.setCacheDir("cache-" + assembly);
        profile.setFasta(assembly + ".fa");
        profile.setVersion("vep-116");
        profile.setEnsemblCacheVersion("116_" + assembly);
        properties.getVep().getResources().put(assembly, profile);
    }
}
