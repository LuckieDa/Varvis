package org.nbscn.varvis.variantanalysis.lineage;

import java.io.IOException;
import java.util.Objects;

/**
 * Sanitized I/O failure from the product-detached streaming lineage validator.
 *
 * <p>The originating exception is deliberately not retained because channel/provider messages
 * commonly contain absolute paths, account names or attacker-controlled file names.</p>
 */
final class LineageStreamingException extends IOException {

    enum Code {
        INPUT_READ_FAILED
    }

    private final Code code;

    LineageStreamingException(Code code) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    Code code() {
        return code;
    }

    @Override
    public String toString() {
        return "LineageStreamingException[" + code.name() + "]";
    }
}
