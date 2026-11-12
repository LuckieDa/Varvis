package org.nbscn.varvis.variantanalysis.lineage;

import java.io.IOException;
import java.util.Objects;

/**
 * Sanitized storage-boundary failure for the synthetic lineage sidecar publisher.
 *
 * <p>The exception deliberately has no underlying cause. Filesystem exceptions commonly embed
 * absolute paths, account names, or attacker-controlled file names in their messages and
 * {@code toString()} output. Callers receive only a stable code and an explicit publication
 * state.</p>
 */
final class LineageSidecarStorageException extends IOException {

    enum Code {
        WORKSPACE_REQUIRED,
        WORKSPACE_INVALID,
        FILESYSTEM_UNSUPPORTED,
        INDIRECT_PATH_FORBIDDEN,
        WORKSPACE_NOT_PRIVATE,
        TARGET_MISSING,
        TARGET_CONFLICT,
        TEMP_CREATE_FAILED,
        WRITE_FAILED,
        FILE_SYNC_FAILED,
        TEMP_VERIFICATION_FAILED,
        EXPECTED_DOCUMENT_MISMATCH,
        ATOMIC_PUBLISH_UNSUPPORTED,
        PUBLISH_FAILED,
        DIRECTORY_SYNC_UNSUPPORTED,
        DIRECTORY_SYNC_FAILED
    }

    /**
     * Highest persistence milestone that this publisher is known to have reached before failing.
     *
     * <p>This is historical operation state, not a live query of what the current target path
     * resolves to. A same-UID actor may rename or replace entries after a milestone, while the
     * reported state continues to describe the publication whose durability was already proved.</p>
     */
    enum PublicationState {
        NOT_PUBLISHED,
        PRESENT_DURABILITY_UNCONFIRMED,
        PUBLISHED_DURABLE
    }

    private final Code code;
    private final PublicationState publicationState;

    LineageSidecarStorageException(Code code, PublicationState publicationState) {
        super(message(code, publicationState));
        this.code = Objects.requireNonNull(code, "code");
        this.publicationState = Objects.requireNonNull(publicationState, "publicationState");
    }

    Code code() {
        return code;
    }

    PublicationState publicationState() {
        return publicationState;
    }

    @Override
    public String toString() {
        return "LineageSidecarStorageException[" + code.name() + ":"
                + publicationState.name() + "]";
    }

    private static String message(Code code, PublicationState publicationState) {
        return Objects.requireNonNull(code, "code").name() + ":"
                + Objects.requireNonNull(publicationState, "publicationState").name();
    }
}
