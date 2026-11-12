package org.nbscn.varvis.variantanalysis.lineage;

import java.io.IOException;
import java.util.Objects;

/**
 * Sanitized failure raised by the private bounded lineage sort spool.
 *
 * <p>No filesystem exception is retained as a cause because provider messages commonly contain
 * absolute paths, account names, or attacker-controlled file names. Callers receive only a
 * stable machine code.</p>
 */
final class LineageSpoolException extends IOException {

    enum Code {
        WORKSPACE_REQUIRED,
        WORKSPACE_INVALID,
        FILESYSTEM_UNSUPPORTED,
        INDIRECT_PATH_FORBIDDEN,
        WORKSPACE_NOT_PRIVATE,
        LIMIT_CONFIGURATION_INVALID,
        ENTRY_INVALID,
        SPOOL_LIMIT_EXCEEDED,
        SESSION_CREATE_FAILED,
        RUN_CREATE_FAILED,
        RUN_WRITE_FAILED,
        RUN_SYNC_FAILED,
        RUN_READ_FAILED,
        RUN_CORRUPT,
        STATE_INVALID,
        CLEANUP_INCOMPLETE
    }

    private final Code code;

    LineageSpoolException(Code code) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    Code code() {
        return code;
    }

    @Override
    public String toString() {
        return "LineageSpoolException[" + code.name() + "]";
    }
}
