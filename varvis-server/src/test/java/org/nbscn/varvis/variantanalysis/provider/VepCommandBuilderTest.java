package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VepCommandBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsOfflineGrch38JsonCommandWithConfiguredResources() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setExecutable("vep");
        properties.getVep().setCacheDir(tempDir.resolve("cache").toString());
        properties.getVep().setFasta(tempDir.resolve("GRCh38.fa").toString());
        Path plugins = tempDir.resolve("plugins");
        Files.createDirectories(plugins.resolve("loftee"));
        Files.write(plugins.resolve("loftee").resolve("LoF.pm"), new byte[] {1});
        properties.getVep().setPluginsDir(plugins.toString());
        properties.getVep().setLofteeEnabled(true);

        List<String> command = new VepCommandBuilder(properties).build(new VepCommandOptions(
                tempDir.resolve("input.vcf"),
                tempDir.resolve("output.json")
        ));

        assertTrue(command.contains("--offline"));
        assertTrue(command.contains("--cache"));
        assertTrue(command.contains("GRCh38"));
        assertTrue(command.contains("--json"));
        assertTrue(command.contains("--symbol"));
        assertTrue(command.contains("--canonical"));
        assertTrue(command.contains("--mane"));
        assertTrue(command.contains("--hgvs"));
        assertTrue(command.contains("--protein"));
        assertTrue(command.contains("--biotype"));
        assertTrue(command.contains("--numbers"));
        assertTrue(command.contains("--dir_cache"));
        assertTrue(command.contains("--fasta"));
        assertTrue(command.contains("--dir_plugins"));
        assertTrue(command.contains("--plugin"));
        assertTrue(command.contains(path(plugins.resolve("loftee"))));
        assertTrue(command.stream().anyMatch(token -> token.startsWith("LoF,")
                && token.contains("loftee_path:" + pluginPath(plugins.resolve("loftee")))
                && token.contains("human_ancestor_fa:false")
                && token.contains("conservation_file:false")
                && token.contains("use_gerp_end_trunc:0")));
    }

    @Test
    void buildsGrch37CommandFromExplicitProfileWithoutGrch38Resources() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setExecutable("vep");
        Path grch37Cache = tempDir.resolve("cache-grch37");
        Path grch37Fasta = tempDir.resolve("GRCh37.fa");
        Path grch38Cache = tempDir.resolve("cache-grch38");
        Path grch38Fasta = tempDir.resolve("GRCh38.fa");
        Files.createDirectories(grch37Cache);
        Files.write(grch37Fasta, new byte[] {1});

        VepResourceProfile profile = new VepResourceProfile(
                GenomeAssembly.GRCh37,
                true,
                "vep",
                grch37Cache,
                grch37Fasta,
                null,
                "homo_sapiens",
                "vep-116",
                "116_GRCh37",
                "MANE-1.4",
                "loftee-1.0",
                false
        );

        List<String> command = new VepCommandBuilder(properties).build(new VepCommandOptions(
                tempDir.resolve("input.vcf"),
                tempDir.resolve("output.json")
        ), profile);

        assertTrue(command.contains("--assembly"));
        assertTrue(command.contains("GRCh37"));
        assertTrue(command.contains("--species"));
        assertTrue(command.contains("homo_sapiens"));
        assertTrue(command.contains(path(grch37Cache)));
        assertTrue(command.contains(path(grch37Fasta)));
        assertFalse(command.contains(path(grch38Cache)));
        assertFalse(command.contains(path(grch38Fasta)));
    }

    @Test
    void passesVepCacheRootWhenProfilePointsAtSpeciesVersionDirectory() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        Path cacheRoot = tempDir.resolve("cache");
        Path speciesVersionCache = cacheRoot.resolve("homo_sapiens").resolve("116_GRCh37");
        Files.createDirectories(speciesVersionCache);
        Path fasta = tempDir.resolve("GRCh37.fa");
        Files.write(fasta, new byte[] {1});
        VepResourceProfile profile = new VepResourceProfile(
                GenomeAssembly.GRCh37,
                true,
                "vep",
                speciesVersionCache,
                fasta,
                null,
                "homo_sapiens",
                "116.0",
                "116_GRCh37",
                "MANE-1.4",
                "loftee-1.0",
                false
        );

        List<String> command = new VepCommandBuilder(properties).build(new VepCommandOptions(
                tempDir.resolve("input.vcf"),
                tempDir.resolve("output.json")
        ), profile);

        assertTrue(command.contains("--dir_cache"));
        assertTrue(command.contains(path(cacheRoot)));
        assertFalse(command.contains(path(speciesVersionCache)));
    }

    @Test
    void normalizesWindowsPathsForPerlVepRuntime() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setExecutable("D:/LSD/Project/Tool/vep/bin/varvis-vep.cmd");
        properties.getVep().setCacheDir("D:\\LSD\\Project\\Tool\\vep\\cache");
        properties.getVep().setFasta("D:\\LSD\\Project\\Tool\\vep\\Homo_sapiens.GRCh38.fa");
        properties.getVep().setPluginsDir("D:\\LSD\\Project\\Tool\\vep\\plugins");

        List<String> command = new VepCommandBuilder(properties).build(new VepCommandOptions(
                Paths.get("D:\\LSD\\Project\\Tool\\vep\\tmp\\input.vcf"),
                Paths.get("D:\\LSD\\Project\\Tool\\vep\\tmp\\output.json")
        ));

        assertTrue(command.contains("D:/LSD/Project/Tool/vep/cache"));
        assertTrue(command.contains("D:/LSD/Project/Tool/vep/Homo_sapiens.GRCh38.fa"));
        assertTrue(command.contains("D:/LSD/Project/Tool/vep/plugins"));
        assertTrue(command.contains("D:/LSD/Project/Tool/vep/tmp/input.vcf"));
        assertTrue(command.contains("D:/LSD/Project/Tool/vep/tmp/output.json"));
        assertFalse(command.stream().anyMatch(token -> token.startsWith("D:\\") && token.contains("\\Project\\")));
    }

    private String path(Path path) {
        return path.toString().replace('\\', '/');
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
