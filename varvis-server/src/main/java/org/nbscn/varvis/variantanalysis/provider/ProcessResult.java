package org.nbscn.varvis.variantanalysis.provider;

public class ProcessResult {

    private final int exitCode;
    private final String output;
    private final boolean timedOut;
    private final java.util.Map<String, Object> executionReceipt;

    public ProcessResult(int exitCode, String output, boolean timedOut) {
        this(exitCode, output, timedOut, java.util.Map.of());
    }

    public ProcessResult(int exitCode, String output, boolean timedOut, java.util.Map<String, Object> receipt) {
        this.exitCode = exitCode;
        this.output = output;
        this.timedOut = timedOut;
        this.executionReceipt = java.util.Map.copyOf(receipt);
    }

    public java.util.Map<String, Object> getExecutionReceipt() { return executionReceipt; }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }
}
