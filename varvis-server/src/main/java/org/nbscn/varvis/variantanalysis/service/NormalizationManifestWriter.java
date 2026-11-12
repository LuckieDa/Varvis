package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.domain.NormalizationRunManifest;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class NormalizationManifestWriter {

    public static final String FILE_NAME = "normalization-manifest.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private NormalizationManifestWriter() {
    }

    public static Path write(Path workspace, NormalizationRunManifest manifest) throws IOException {
        if (workspace == null || manifest == null) {
            throw new IllegalArgumentException("Workspace and normalization manifest are required");
        }
        Files.createDirectories(workspace);
        Path destination = workspace.resolve(FILE_NAME);
        Path temporary = workspace.resolve(FILE_NAME + ".tmp");
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest);
        try {
            return Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ex) {
            return Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
