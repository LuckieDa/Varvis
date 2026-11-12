package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class VepCommandBuilder {

    private final VariantAnalysisProperties properties;
    private final VepResourceProfileResolver profileResolver;

    @Autowired
    public VepCommandBuilder(VariantAnalysisProperties properties) {
        this(properties, new VepResourceProfileResolver(properties));
    }

    public VepCommandBuilder(VariantAnalysisProperties properties,
                             VepResourceProfileResolver profileResolver) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
        this.profileResolver = profileResolver == null
                ? new VepResourceProfileResolver(this.properties)
                : profileResolver;
    }

    public List<String> build(VepCommandOptions options) {
        VepResourceProfileResolution resolution = profileResolver.resolve(GenomeAssembly.GRCh38);
        if (!resolution.isResolved()) {
            throw new IllegalStateException("No VEP GRCh38 resource profile is available: "
                    + resolution.getReason());
        }
        return build(options, resolution.getProfile());
    }

    public List<String> build(VepCommandOptions options, VepResourceProfile profile) {
        VariantAnalysisProperties.Vep vep = properties.getVep();
        if (profile == null || profile.getAssembly() == null) {
            throw new IllegalArgumentException("VEP command requires an assembly-specific resource profile.");
        }
        List<String> command = new ArrayList<String>();
        command.add(profile.getExecutable());
        command.add("--offline");
        command.add("--cache");
        command.add("--assembly");
        command.add(profile.getAssembly().name());
        command.add("--species");
        command.add(profile.getSpecies());
        command.add("--dir_cache");
        command.add(path(effectiveCacheDir(profile)));
        command.add("--fasta");
        command.add(path(profile.getFasta()));
        command.add("--format");
        command.add("vcf");
        command.add("--json");
        command.add("--input_file");
        command.add(path(options.getInputVcf()));
        command.add("--output_file");
        command.add(path(options.getOutputJson()));
        command.add("--force_overwrite");
        command.add("--no_stats");
        command.add("--symbol");
        command.add("--canonical");
        command.add("--mane");
        command.add("--hgvs");
        command.add("--protein");
        command.add("--biotype");
        command.add("--numbers");
        Path pluginsDir = effectivePluginsDir(vep, profile);
        Path positionPluginDir = null;
        if (profile.getAssembly() == GenomeAssembly.GRCh38 && "116.0".equals(profile.getVersion())) {
            positionPluginDir = preparePositionPlugin(options.getOutputJson());
            command.add("--plugin");
            command.add("NearestExonJB");
        }
        if (pluginsDir != null || positionPluginDir != null) {
            command.add("--dir_plugins");
            // VEP 116 uses Perl glob() to resolve multiple plugin search directories.
            command.add(positionPluginDir == null ? path(pluginsDir)
                    : (pluginsDir == null ? "" : globPath(pluginsDir) + " ") + globPath(positionPluginDir));
        }
        if (vep.isLofteeEnabled()) {
            command.add("--plugin");
            command.add(lofteePluginArgument(pluginsDir));
        }
        return command;
    }

    private String globPath(Path directory) {
        // Escape Perl glob metacharacters, not shell quotes: embedded double quotes
        // break Windows .cmd argument forwarding even when ProcessBuilder succeeds.
        return pluginPath(directory).replace(" ", "\\ ").replace("[", "\\[").replace("]", "\\]")
                .replace("{", "\\{").replace("}", "\\}").replace("'", "\\'");
    }

    private Path preparePositionPlugin(Path output) {
        try (var input = getClass().getResourceAsStream("/vep/NearestExonJB.pm")) {
            if (input == null) throw new IllegalStateException("VEP_POSITION_PLUGIN_MISSING");
            byte[] bytes = input.readAllBytes();
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!org.nbscn.varvis.variantanalysis.domain.SynonymousExonPosition.PLUGIN_SHA256.equals(hash))
                throw new IllegalStateException("VEP_POSITION_PLUGIN_IDENTITY_MISMATCH");
            Path parent = output.toAbsolutePath().getParent();
            if (parent == null) throw new IllegalStateException("VEP_OUTPUT_DIRECTORY_MISSING");
            Files.createDirectories(parent);
            Path directory = Files.createTempDirectory(parent, "bp7-position-plugin-");
            Files.write(directory.resolve("NearestExonJB.pm"), bytes);
            return directory;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException invalid) {
            throw new IllegalStateException("VEP_POSITION_PLUGIN_UNAVAILABLE", invalid);
        }
    }

    private Path effectivePluginsDir(VariantAnalysisProperties.Vep vep, VepResourceProfile profile) {
        Path pluginsDir = profile.getPluginsDir();
        if (pluginsDir == null || !vep.isLofteeEnabled()) {
            return pluginsDir;
        }
        if (Files.isRegularFile(pluginsDir.resolve("LoF.pm"))) {
            return pluginsDir;
        }
        Path nestedLoftee = pluginsDir.resolve("loftee");
        if (Files.isRegularFile(nestedLoftee.resolve("LoF.pm"))) {
            return nestedLoftee;
        }
        return pluginsDir;
    }

    private Path effectiveCacheDir(VepResourceProfile profile) {
        Path cacheDir = profile.getCacheDir();
        if (cacheDir == null) {
            return null;
        }
        Path fileName = cacheDir.getFileName();
        String species = profile.getSpecies();
        if (samePathPart(fileName, species)) {
            Path parent = cacheDir.getParent();
            return parent == null ? cacheDir : parent;
        }
        Path parent = cacheDir.getParent();
        if (parent != null && samePathPart(parent.getFileName(), species)) {
            Path cacheRoot = parent.getParent();
            return cacheRoot == null ? cacheDir : cacheRoot;
        }
        return cacheDir;
    }

    private boolean samePathPart(Path pathPart, String expected) {
        return pathPart != null
                && expected != null
                && pathPart.toString().equalsIgnoreCase(expected);
    }

    private String lofteePluginArgument(Path lofteeDir) {
        StringBuilder argument = new StringBuilder("LoF");
        if (lofteeDir != null) {
            argument.append(",loftee_path:").append(pluginPath(lofteeDir));
        }
        argument.append(",human_ancestor_fa:false");
        argument.append(",conservation_file:false");
        argument.append(",use_gerp_end_trunc:0");
        return argument.toString();
    }

    private String path(Path path) {
        return path == null ? "" : path.toString().replace('\\', '/');
    }

    private String pluginPath(Path path) {
        String normalized = path(path);
        if (normalized.length() > 2
                && normalized.charAt(1) == ':'
                && normalized.charAt(2) == '/') {
            return "/" + Character.toLowerCase(normalized.charAt(0)) + normalized.substring(2);
        }
        return normalized;
    }
}
