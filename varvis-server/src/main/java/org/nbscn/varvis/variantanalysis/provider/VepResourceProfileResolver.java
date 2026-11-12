package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class VepResourceProfileResolver {

    private final VariantAnalysisProperties properties;

    public VepResourceProfileResolver(VariantAnalysisProperties properties) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
    }

    public VepResourceProfileResolution resolve(GenomeAssembly assembly) {
        if (assembly == null) {
            return VepResourceProfileResolution.unsupported("VEP resource routing requires a known assembly.");
        }
        VariantAnalysisProperties.Vep vep = properties.getVep();
        List<String> warnings = new ArrayList<String>();
        String mismatch = profileMismatchForAssembly(vep, assembly);
        if (mismatch != null) {
            return VepResourceProfileResolution.unavailable(
                    assembly,
                    "RESOURCE_PROFILE_ASSEMBLY_MISMATCH",
                    Collections.singletonList(mismatch)
            );
        }
        VariantAnalysisProperties.Vep.ResourceProfile configured = profileByAssembly(vep, assembly, warnings);
        if (configured != null) {
            String normalizedProfileAssembly = configured.normalizedAssembly();
            if (!assembly.name().equals(normalizedProfileAssembly)) {
                return VepResourceProfileResolution.unavailable(
                        assembly,
                        "RESOURCE_PROFILE_ASSEMBLY_MISMATCH",
                        Collections.singletonList("VEP resource profile assembly does not match requested assembly.")
                );
            }
            return VepResourceProfileResolution.resolved(
                    assembly,
                    VepResourceProfile.fromResourceProfile(assembly, vep, configured),
                    warnings
            );
        }
        if (hasExplicitResourceProfiles(vep)) {
            return VepResourceProfileResolution.unavailable(
                    assembly,
                    "RESOURCE_PROFILE_MISSING",
                    Collections.singletonList("No VEP resource profile is configured for " + assembly.name() + ".")
            );
        }
        if (assembly == GenomeAssembly.GRCh38 && legacyGrch38Configured(vep)) {
            return VepResourceProfileResolution.resolved(
                    assembly,
                    VepResourceProfile.legacyGrch38(vep),
                    warnings
            );
        }
        return VepResourceProfileResolution.unavailable(
                assembly,
                "RESOURCE_PROFILE_MISSING",
                Collections.singletonList("No VEP resource profile is configured for " + assembly.name() + ".")
        );
    }

    private VariantAnalysisProperties.Vep.ResourceProfile profileByAssembly(
            VariantAnalysisProperties.Vep vep,
            GenomeAssembly assembly,
            List<String> warnings) {
        Map<String, VariantAnalysisProperties.Vep.ResourceProfile> resources = vep.getResources();
        if (resources == null || resources.isEmpty()) {
            return null;
        }
        VariantAnalysisProperties.Vep.ResourceProfile result = null;
        for (Map.Entry<String, VariantAnalysisProperties.Vep.ResourceProfile> entry : resources.entrySet()) {
            String keyAssembly = normalizeAssembly(entry.getKey());
            VariantAnalysisProperties.Vep.ResourceProfile profile = entry.getValue();
            if (profile == null) {
                continue;
            }
            String profileAssembly = profile.normalizedAssembly();
            if (!StringUtils.hasText(profileAssembly)) {
                warnings.add("Ignoring VEP resource profile with missing assembly for key " + entry.getKey() + ".");
                continue;
            }
            if (!StringUtils.hasText(keyAssembly) || !keyAssembly.equals(profileAssembly)) {
                warnings.add("Ignoring VEP resource profile with key/profile assembly mismatch: "
                        + entry.getKey() + " -> " + profileAssembly + ".");
                continue;
            }
            if (assembly.name().equals(keyAssembly)) {
                if (result != null) {
                    warnings.add("Duplicate VEP resource profile for " + assembly.name()
                            + "; first matching profile is used.");
                    continue;
                }
                result = profile;
            }
        }
        return result;
    }

    private String profileMismatchForAssembly(VariantAnalysisProperties.Vep vep, GenomeAssembly assembly) {
        Map<String, VariantAnalysisProperties.Vep.ResourceProfile> resources = vep.getResources();
        if (resources == null || resources.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, VariantAnalysisProperties.Vep.ResourceProfile> entry : resources.entrySet()) {
            String keyAssembly = normalizeAssembly(entry.getKey());
            VariantAnalysisProperties.Vep.ResourceProfile profile = entry.getValue();
            if (profile == null) {
                continue;
            }
            String profileAssembly = profile.normalizedAssembly();
            if (!StringUtils.hasText(keyAssembly) || !StringUtils.hasText(profileAssembly)
                    || keyAssembly.equals(profileAssembly)) {
                continue;
            }
            if (assembly.name().equals(keyAssembly) || assembly.name().equals(profileAssembly)) {
                return "VEP resource profile key/profile assembly mismatch: "
                        + entry.getKey() + " -> " + profileAssembly + ".";
            }
        }
        return null;
    }

    private boolean hasExplicitResourceProfiles(VariantAnalysisProperties.Vep vep) {
        Map<String, VariantAnalysisProperties.Vep.ResourceProfile> resources = vep.getResources();
        return resources != null && !resources.isEmpty();
    }

    private boolean legacyGrch38Configured(VariantAnalysisProperties.Vep vep) {
        return true;
    }

    private String normalizeAssembly(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        if ("grch38".equals(normalized) || "hg38".equals(normalized)) {
            return "GRCh38";
        }
        if ("grch37".equals(normalized) || "hg19".equals(normalized) || "b37".equals(normalized)) {
            return "GRCh37";
        }
        return value.trim();
    }
}
