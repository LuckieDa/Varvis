package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.Path;

public class VepCommandOptions {

    private final Path inputVcf;
    private final Path outputJson;

    public VepCommandOptions(Path inputVcf, Path outputJson) {
        this.inputVcf = inputVcf;
        this.outputJson = outputJson;
    }

    public Path getInputVcf() {
        return inputVcf;
    }

    public Path getOutputJson() {
        return outputJson;
    }
}
