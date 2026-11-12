package org.nbscn.varvis.variantanalysis.engine;

/** Bounded process outcome; stdout/stderr remain in the job work directory. */
public final class ExomiserProcessResult {

    private final int exitCode;
    private final boolean timedOut;

    public ExomiserProcessResult(int exitCode, boolean timedOut) {
        this.exitCode = exitCode;
        this.timedOut = timedOut;
    }

    public int getExitCode() {
        return exitCode;
    }

    public boolean isTimedOut() {
        return timedOut;
    }
}
