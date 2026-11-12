package org.nbscn.varvis.ps1pm5acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.config.VarvisDataSourceConfig;
import org.nbscn.varvis.variantanalysis.service.Ps1Pm5NetworkObservations;
import org.nbscn.varvis.variantanalysis.provider.ClinVarMissenseBusinessRuntime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Test-classpath entry point: real Tomcat and production analysis beans, never MockMvc or fake engines. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "org.nbscn.varvis.variantanalysis", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*VariantAnalysisCleanupService")})
@Import(VarvisDataSourceConfig.class)
public class Ps1Pm5NetworkApplication {
    private static final ObjectMapper MANIFEST_MAPPER = new ObjectMapper();
    private static Map<String, Object> identity = Map.of();
    private static Path processOutput;
    private static boolean jointMode;
    private static boolean publishedReuse;
    private static boolean jointReferenceEvaluation;
    private static boolean readOnlyPage;
    private static final String ADMITTED_REQUEST_ATTRIBUTE =
            Ps1Pm5NetworkApplication.class.getName() + ".publishedArtifactBoundaryAdmitted";

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArguments(args);
        Path server = Path.of("").toAbsolutePath().normalize();
        jointMode = "JOINT".equals(options.get("mode"));
        if (options.containsKey("mode") && !jointMode) throw new IllegalArgumentException("Unknown acceptance mode");
        Path acceptance = jointMode ? Path.of(required(options, "acceptance-root")).toRealPath() : server.resolve("target");
        if (jointMode && acceptance.startsWith(server.getParent())) throw new IllegalArgumentException("Joint evidence must survive worktree cleanup");
        Path allowedRoot = jointMode ? acceptance.resolve("network") : server.resolve("target/ps1-pm5-network");
        Path root = Path.of(required(options, "root")).toAbsolutePath().normalize();
        if (!Objects.equals(root.getParent(), allowedRoot)
                || !root.getFileName().toString().matches("instance-[a-f0-9-]{36}"))
            throw new IllegalArgumentException("Root must be a direct instance-UUID under this worktree target/ps1-pm5-network");
        Path config = Path.of(required(options, "config")).toRealPath();
        if (!config.startsWith((jointMode ? acceptance.resolve("private") : server.resolve("target/ps1-pm5-private")).toRealPath()))
            throw new IllegalArgumentException("Use branch-private resource configuration");
        String head = required(options, "head");
        if (!head.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Full committed source SHA required");
        String branch = required(options, "branch");
        int port = Integer.parseInt(options.getOrDefault("port", jointMode ? "18227" : "18217"));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid isolated port");
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            if (!probe.isBound()) throw new IllegalStateException("Port is not available");
        }
        String instanceId = root.getFileName().toString();
        Path marker = root.resolve("instance-manifest.json");
        Map<String, Object> stable = new LinkedHashMap<>();
        if (jointMode) { stable.put("mode", "JOINT"); stable.put("instanceRoot", root.toString()); }
        stable.put("instanceId", instanceId); stable.put("branch", branch); stable.put("sourceHead", head);
        stable.put("configurationSha256", sha(Files.readAllBytes(config)));
        stable.put("port", port); stable.put("transport", "REAL_NETWORK_HTTP"); stable.put("database", "PRIVATE_H2");
        ObjectNode sourceUpgrade = null;
        publishedReuse = options.containsKey("restore-database");
        readOnlyPage = "true".equals(options.get("read-only-page"));
        jointReferenceEvaluation = "true".equals(options.get("joint-reference-evaluation"));
        if (jointReferenceEvaluation && (!jointMode || !publishedReuse || readOnlyPage))
            throw new IllegalArgumentException("Reference reevaluation requires writable isolated Joint published-source reuse");
        if (jointReferenceEvaluation) stable.put("jointReferenceEvaluation", true);
        if (readOnlyPage && !publishedReuse) throw new IllegalArgumentException("Read-only page requires saved published artifacts");
        if (readOnlyPage) stable.put("readOnlyPage", true);
        Path originalDatabase = null, publishedJobs = null;
        if (publishedReuse) {
            if (!jointMode) throw new IllegalArgumentException("Published reuse requires Joint mode");
            originalDatabase = Path.of(required(options, "restore-database")).toRealPath();
            publishedJobs = Path.of(required(options, "published-jobs-root")).toAbsolutePath().normalize();
            String originalSha = required(options, "restore-database-sha256");
            if (!Files.isDirectory(publishedJobs) || Files.size(originalDatabase) > 128L * 1024 * 1024
                    || !originalSha.equals(sha(Files.readAllBytes(originalDatabase))))
                throw new IllegalArgumentException("Published source identity mismatch");
            stable.put("sourceDatabase", originalDatabase.toString()); stable.put("sourceDatabaseSha256", originalSha);
            stable.put("readOnlyPublishedJobsRoot", publishedJobs.toString());
        }
        if (Files.exists(root)) {
            if (!"true".equals(options.get("restart")) || !Files.isRegularFile(marker))
                throw new IllegalArgumentException("Existing root requires explicit restart with identical instance/source/config identity");
            ObjectNode expected = MANIFEST_MAPPER.valueToTree(stable);
            var original = MANIFEST_MAPPER.readTree(Files.readAllBytes(marker));
            if (!expected.equals(original)) sourceUpgrade = sourceUpgrade(root, expected, jointMode && "true".equals(options.get("upgrade-source")));
            if (!Files.isRegularFile(root.resolve("business-db.mv.db")))
                throw new IllegalArgumentException("Restart requires this instance's existing H2 database");
        } else {
            Files.createDirectories(root);
            Files.write(marker, MANIFEST_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(stable));
            if (originalDatabase != null) Files.copy(originalDatabase, root.resolve("business-db.mv.db"));
        }
        processOutput = Files.createDirectories(root.resolve("process-" + UUID.randomUUID()));
        Properties resourceProperties = new Properties();
        try (var input = Files.newInputStream(config)) { resourceProperties.load(input); }
        Map<String, Object> settings = new LinkedHashMap<>();
        resourceProperties.forEach((key, value) -> {
            if (key.toString().startsWith("variant-analysis.")) settings.put(key.toString(), value.toString());
        });
        settings.put("VARVIS_RESOURCE_ROOT", Path.of(required(options, "resource-root")).toRealPath().toString());
        settings.put("server.address", "127.0.0.1"); settings.put("server.port", port);
        settings.put("variant-analysis.acceptance.read-only-page", readOnlyPage);
        settings.put("spring.jackson.serialization.INDENT_OUTPUT", true);
        // Match the original public source serializer without weakening strict candidate comparison.
        if (publishedReuse) {
            settings.put("spring.jackson.serialization.WRITE_DATES_AS_TIMESTAMPS",
                    Boolean.parseBoolean(resourceProperties.getProperty("variant-analysis.acceptance.published-dates-as-timestamps", "false")));
            settings.put("spring.jackson.deserialization.USE_BIG_DECIMAL_FOR_FLOATS",
                    Boolean.parseBoolean(resourceProperties.getProperty("variant-analysis.acceptance.published-big-decimal-for-floats", "false")));
        }
        settings.put("spring.servlet.multipart.location", Files.createDirectories(root.resolve("multipart")).toString());
        settings.put("spring.servlet.multipart.max-file-size", "512MB");
        settings.put("spring.servlet.multipart.max-request-size", "600MB");
        settings.put("spring.devtools.restart.enabled", false);
        settings.put("varvis.datasource.enabled", true);
        String jdbc = "jdbc:h2:file:" + root.resolve("business-db").toString().replace('\\', '/')
                + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE" + (Files.exists(root.resolve("business-db.mv.db")) ? ";IFEXISTS=TRUE" : "");
        settings.put("varvis.datasource.url", jdbc); settings.put("varvis.datasource.jdbc-url", jdbc);
        settings.put("varvis.datasource.driver-class-name", "org.h2.Driver");
        settings.put("varvis.datasource.username", "sa"); settings.put("varvis.datasource.password", "");
        settings.put("varvis.datasource.maximum-pool-size", 4); settings.put("varvis.datasource.minimum-idle", 1);
        settings.put("variant-analysis.persistence.table-prefix", "");
        settings.put("variant-analysis.persistence.legacy-schema-bootstrap-enabled", true);
        // This private database starts with the complete current DDL, never a legacy production schema.
        // The optional legacy migrator assumes MySQL metadata casing and misreads H2 uppercase tables.
        settings.put("variant-analysis.persistence.legacy-schema-migration-enabled", false);
        settings.put("variant-analysis.storage-root", Files.createDirectories(root.resolve("jobs")).toString());
        // Preserve the registered logical path for source identity; only ACMG writes to private H2 are allowed.
        if (publishedJobs != null) settings.put("variant-analysis.storage-root", publishedJobs.toString());
        settings.put("variant-analysis.acmg-business.working-root", Files.createDirectories(root.resolve("business-work")).toString());
        settings.put("variant-analysis.vep.temp-dir", Files.createDirectories(root.resolve("vep-temp")).toString());
        // All generated outputs are private; configured scientific resources and receipts remain unchanged.
        settings.put("variant-analysis.acmg-business.enabled", true);
        settings.put("varvis.benchmark.real-case.enabled", false);
        settings.put("logging.file.name", processOutput.resolve("application.log").toString());
        // Avoid all default application.yml imports, including the user's actual database-local.properties.
        Path emptyBootstrap = root.resolve("isolated-bootstrap.properties");
        if (!Files.exists(emptyBootstrap)) Files.writeString(emptyBootstrap, "# Private isolated bootstrap.\n", StandardCharsets.UTF_8);
        var profile = ClinVarMissenseBusinessRuntime.loadProfile(MANIFEST_MAPPER);
        stable = new LinkedHashMap<>(stable);
        if (sourceUpgrade != null) stable.put("sourceUpgrade", sourceUpgrade);
        stable.put("profileId", profile.path("profileId").asText());
        stable.put("profileVersion", profile.path("profileVersion").asText());
        stable.put("processId", ProcessHandle.current().pid());
        stable.put("processOutput", processOutput.toString());
        stable.put("businessWorkingRoot", root.resolve("business-work").toString());
        identity = Map.copyOf(stable);
        System.setProperty("java.io.tmpdir", Files.createDirectories(root.resolve("jvm-temp")).toString());
        SpringApplication app = new SpringApplication(Ps1Pm5NetworkApplication.class);
        app.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("isolated-ps1-pm5-enforced-settings", settings)));
        app.run("--spring.config.location=" + emptyBootstrap.toUri());
    }

    @Bean static Ps1Pm5NetworkObservations networkObservations() {
        return new Ps1Pm5NetworkObservations(Objects.requireNonNull(processOutput), jointMode, publishedReuse, jointReferenceEvaluation);
    }

    @Bean static org.springframework.web.servlet.config.annotation.WebMvcConfigurer publishedArtifactBoundary(
            org.nbscn.varvis.variantanalysis.service.AcmgBusinessService service) {
        var savedRunService = Objects.requireNonNull(service);
        var profile = (org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile) Objects.requireNonNull(
                org.springframework.test.util.ReflectionTestUtils.getField(savedRunService, "executionProfile"));
        return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
            @Override public void addInterceptors(@org.springframework.lang.NonNull org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                registry.addInterceptor(publishedArtifactInterceptor(publishedReuse, readOnlyPage,
                        jointReferenceEvaluation, Objects.requireNonNull(profile.document()), savedRunService::read));
            }
        };
    }

    static @org.springframework.lang.NonNull org.springframework.web.servlet.HandlerInterceptor publishedArtifactInterceptor(
            boolean restored, boolean pageOnly, boolean referenceEvaluation,
            @org.springframework.lang.NonNull com.fasterxml.jackson.databind.JsonNode currentProfile,
            @org.springframework.lang.NonNull java.util.function.BiFunction<String, String, ObjectNode> savedRun) {
        return new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(@org.springframework.lang.NonNull jakarta.servlet.http.HttpServletRequest request,
                    @org.springframework.lang.NonNull jakarta.servlet.http.HttpServletResponse response,
                    @org.springframework.lang.NonNull Object handler) throws java.io.IOException {
                if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ERROR) {
                    if ("/error".equals(request.getRequestURI())
                            && Boolean.TRUE.equals(request.getAttribute(ADMITTED_REQUEST_ATTRIBUTE))) return true;
                } else {
                    boolean originalRequest = request.getDispatcherType() == jakarta.servlet.DispatcherType.REQUEST;
                    if (originalRequest) request.removeAttribute(ADMITTED_REQUEST_ATTRIBUTE);
                    boolean admitted = allowsRequest(restored, pageOnly, request.getMethod(), request.getRequestURI());
                    if (!admitted && referenceEvaluation && !pageOnly && "POST".equals(request.getMethod())
                            && request.getRequestURI().matches("/variant-analyses/[a-f0-9-]{36}/acmg/runs/[a-f0-9-]{36}/functional-reviews")) {
                        String[] parts = request.getRequestURI().split("/");
                        var saved = Objects.requireNonNull(savedRun.apply(parts[2], parts[5]));
                        admitted = allowsFunctionalReview(saved.path("executionProfile"), currentProfile);
                    }
                    if (admitted) {
                        // Servlet ERROR dispatch retains server attributes; clients cannot supply this marker.
                        if (originalRequest) request.setAttribute(ADMITTED_REQUEST_ATTRIBUTE, Boolean.TRUE);
                        return true;
                    }
                }
                response.sendError(403, "Published analysis artifacts are read-only; only explicit ACMG evaluation is enabled");
                return false;
            }
        };
    }

    static boolean allowsRequest(boolean restored, boolean pageOnly, String method, String uri) {
        return !restored || "GET".equals(method) || !pageOnly && "POST".equals(method)
                && uri.matches("/variant-analyses/[a-f0-9-]{36}/acmg/runs");
    }

    static boolean allowsFunctionalReview(com.fasterxml.jackson.databind.JsonNode savedProfile,
                                          com.fasterxml.jackson.databind.JsonNode currentProfile) {
        return "acmg-explicit-business-joint-v8".equals(currentProfile.path("profileId").asText())
                && savedProfile.equals(currentProfile);
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "variant-analysis.acceptance.read-only-page", havingValue = "true")
    @Import({org.nbscn.varvis.controller.variant.VariantOverviewController.class,
            org.nbscn.varvis.service.variant.impl.VariantOverviewServiceImpl.class})
    static class PageReadOnlyQueries { }

    @RestController
    static final class InstanceController {
        private final Map<String, Object> metadata;
        private final Ps1Pm5NetworkObservations observations;
        InstanceController(Ps1Pm5NetworkObservations observations, org.nbscn.varvis.variantanalysis.service.AcmgBusinessService service) {
            var values = new LinkedHashMap<String, Object>(Ps1Pm5NetworkApplication.identity);
            if (jointMode) {
                var profile = (org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile) Objects.requireNonNull(
                        org.springframework.test.util.ReflectionTestUtils.getField(Objects.requireNonNull(service), "executionProfile"));
                var actual = profile.document();
                if (!Set.of("acmg-explicit-business-joint-v1", "acmg-explicit-business-joint-v2",
                        "acmg-explicit-business-joint-v3", "acmg-explicit-business-joint-v4", "acmg-explicit-business-joint-v5", "acmg-explicit-business-joint-v6", "acmg-explicit-business-joint-v8").contains(actual.path("profileId").asText())
                        || actual.path("automaticTrigger").asBoolean(true) || actual.path("classificationEnabled").asBoolean(true)
                        || profile.criteria().size() != 11 || !Set.copyOf(profile.criteria()).equals(Set.of(
                        "ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4", "ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BP7",
                        "ACMG_AMP_2015:PM2", "ACMG_AMP_2015:PS1", "ACMG_AMP_2015:PM5", "ACMG_AMP_2015:PVS1", "ACMG_AMP_2015:BS1", "ACMG_AMP_2015:PS3", "ACMG_AMP_2015:PM1"))) throw new IllegalArgumentException("Joint production manifest mismatch");
                values.put("profileId", actual.path("profileId").asText());
                values.put("profileVersion", actual.path("profileVersion").asText());
                values.put("executionProfile", actual);
            }
            this.metadata = Map.copyOf(values); this.observations = observations;
        }
        @GetMapping("/__varvis_ps1pm5_instance") public Map<String, Object> identity() { return metadata; }
        @GetMapping("/__varvis_ps1pm5_calls") public Map<String, Integer> calls() { return observations.snapshot(); }
    }

    static ObjectNode sourceUpgrade(Path root, ObjectNode expected, boolean allowNew) throws Exception {
        byte[] originalBytes = Files.readAllBytes(root.resolve("instance-manifest.json"));
        var parsed = MANIFEST_MAPPER.readTree(originalBytes);
        if (!(parsed instanceof ObjectNode original)) throw new IllegalArgumentException("Invalid original manifest");
        String previous = original.path("sourceHead").asText(), next = expected.path("sourceHead").asText();
        ObjectNode comparable = expected.deepCopy().put("sourceHead", previous);
        if (!previous.matches("[a-f0-9]{40}") || !next.matches("[a-f0-9]{40}") || previous.equals(next) || !comparable.equals(original))
            throw new IllegalArgumentException("Source upgrade cannot change instance, configuration, port or database identity");
        ObjectNode receipt = MANIFEST_MAPPER.createObjectNode().put("previousSourceHead", previous)
                .put("originalManifestSha256", sha(originalBytes)).put("originalManifestRetained", true);
        receipt.set("identity", expected.deepCopy());
        Path path = root.resolve("instance-source-" + next + ".json");
        if (Files.exists(path)) {
            if (!receipt.equals(MANIFEST_MAPPER.readTree(Files.readAllBytes(path)))) throw new IllegalArgumentException("Source upgrade receipt changed");
        } else {
            if (!allowNew) throw new IllegalArgumentException("Explicit source upgrade required");
            Files.write(path, MANIFEST_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        return receipt;
    }

    static Map<String, String> parseArguments(String[] args) {
        Set<String> allowed = Set.of("config", "root", "resource-root", "head", "branch", "port", "restart", "upgrade-source", "mode", "acceptance-root",
                "restore-database", "restore-database-sha256", "published-jobs-root", "read-only-page", "joint-reference-evaluation");
        Map<String, String> values = new LinkedHashMap<>();
        if (args.length % 2 != 0) throw new IllegalArgumentException("Expected --key value pairs");
        for (int index = 0; index < args.length; index += 2) {
            String key = args[index].replaceFirst("^--", "");
            if (!args[index].startsWith("--") || !allowed.contains(key) || values.putIfAbsent(key, args[index + 1]) != null)
                throw new IllegalArgumentException("Unknown or repeated option: " + args[index]);
        }
        return values;
    }
    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + name + " is required");
        return value;
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
