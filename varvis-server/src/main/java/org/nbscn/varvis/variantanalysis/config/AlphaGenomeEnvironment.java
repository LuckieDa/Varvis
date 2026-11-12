package org.nbscn.varvis.variantanalysis.config;

import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class AlphaGenomeEnvironment {

    public static final String API_KEY_ENV = "ALPHAGENOME_API_KEY";

    private AlphaGenomeEnvironment() {
    }

    public static String apiKey() {
        String processValue = System.getenv(API_KEY_ENV);
        if (StringUtils.hasText(processValue)) {
            return processValue;
        }
        return windowsUserEnvironment(API_KEY_ENV);
    }

    public static boolean apiKeyConfigured() {
        return StringUtils.hasText(apiKey());
    }

    private static String windowsUserEnvironment(String name) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("windows")) {
            return null;
        }
        Process process = null;
        try {
            process = new ProcessBuilder("reg", "query", "HKCU\\Environment", "/v", name)
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(2, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return parseRegistryValue(process, name);
        } catch (IOException ex) {
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String parseRegistryValue(Process process, String name) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String[] fields = trimmed.split("\\s+", 3);
                if (fields.length == 3 && StringUtils.hasText(fields[2])) {
                    return fields[2].trim();
                }
            }
        }
        return null;
    }
}
