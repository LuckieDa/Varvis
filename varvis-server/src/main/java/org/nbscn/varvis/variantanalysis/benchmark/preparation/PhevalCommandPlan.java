package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({"executableReference", "argumentTokens", "environmentAllowList", "workingDirectory", "expectedOutputs", "fingerprint"})
public class PhevalCommandPlan {

    private String executableReference;
    private List<String> argumentTokens = new ArrayList<String>();
    private List<String> environmentAllowList = new ArrayList<String>();
    private String workingDirectory;
    private List<String> expectedOutputs = new ArrayList<String>();
    private String fingerprint;

    public PhevalCommandPlan() {
    }

    public PhevalCommandPlan(String executableReference, List<String> argumentTokens,
                             List<String> environmentAllowList, String workingDirectory,
                             List<String> expectedOutputs) {
        this.executableReference = executableReference;
        this.argumentTokens = copy(argumentTokens);
        this.environmentAllowList = copy(environmentAllowList);
        this.workingDirectory = workingDirectory;
        this.expectedOutputs = copy(expectedOutputs);
        validate();
        this.fingerprint = sha256(canonicalText());
    }

    public String getExecutableReference() { return executableReference; }
    public List<String> getArgumentTokens() { return argumentTokens; }
    public List<String> getEnvironmentAllowList() { return environmentAllowList; }
    public String getWorkingDirectory() { return workingDirectory; }
    public List<String> getExpectedOutputs() { return expectedOutputs; }
    public String getFingerprint() { return fingerprint; }
    public void setExecutableReference(String value) { executableReference = value; }
    public void setArgumentTokens(List<String> value) { argumentTokens = value; }
    public void setEnvironmentAllowList(List<String> value) { environmentAllowList = value; }
    public void setWorkingDirectory(String value) { workingDirectory = value; }
    public void setExpectedOutputs(List<String> value) { expectedOutputs = value; }
    public void setFingerprint(String value) { fingerprint = value; }

    public void validate() {
        PhevalPathSafety.requireLogicalRelative(executableReference, "executableReference");
        PhevalPathSafety.requireLogicalRelative(workingDirectory, "workingDirectory");
        for (String output : expectedOutputs) PhevalPathSafety.requireLogicalRelative(output, "expectedOutputs");
        for (String name : environmentAllowList) {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("INVALID_ENVIRONMENT_ALLOW_LIST");
            }
        }
        if (argumentTokens == null) throw new IllegalArgumentException("ARGUMENT_TOKENS_REQUIRED");
        for (String token : argumentTokens) if (token == null) throw new IllegalArgumentException("NULL_ARGUMENT_TOKEN");
    }

    private String canonicalText() {
        return executableReference + "\n" + String.join("\n", argumentTokens) + "\n"
                + String.join("\n", environmentAllowList) + "\n" + workingDirectory + "\n"
                + String.join("\n", expectedOutputs);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? new ArrayList<String>() : new ArrayList<String>(values);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest) value.append(String.format("%02x", b & 0xff));
            return value.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
