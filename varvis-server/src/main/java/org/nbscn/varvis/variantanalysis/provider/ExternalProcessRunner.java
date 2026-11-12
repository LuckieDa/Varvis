package org.nbscn.varvis.variantanalysis.provider;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface ExternalProcessRunner {

    ProcessResult run(List<String> command, Path workingDirectory, Duration timeout) throws IOException;
}
