package org.nbscn.varvis.variantanalysis.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Injectable process boundary; tests never need to launch Exomiser. */
public interface ExomiserProcessRunner {

    ExomiserProcessResult run(List<String> command,
                              Path workingDirectory,
                              Path stdout,
                              Path stderr,
                              Map<String, String> environment,
                              Duration timeout) throws IOException, InterruptedException;
}
