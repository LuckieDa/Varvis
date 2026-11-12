package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BenchmarkManifestValidationException extends IllegalArgumentException {

    private final List<BenchmarkValidationError> errors;

    public BenchmarkManifestValidationException(List<BenchmarkValidationError> errors) {
        super(errors == null ? "Invalid benchmark manifest" : errors.toString());
        this.errors = errors == null
                ? Collections.<BenchmarkValidationError>emptyList()
                : Collections.unmodifiableList(new ArrayList<BenchmarkValidationError>(errors));
    }

    public List<BenchmarkValidationError> getErrors() {
        return errors;
    }
}
