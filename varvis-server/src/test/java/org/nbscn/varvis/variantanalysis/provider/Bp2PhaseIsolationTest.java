package org.nbscn.varvis.variantanalysis.provider;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
class Bp2PhaseIsolationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private void archive(String name, ObjectNode input, ObjectNode result) throws Exception {
        Path dir=Path.of(System.getProperty("varvis.ps2.artifacts"),"bp2-isolation"); java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve(name+"-input.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(input));
        java.nio.file.Files.write(dir.resolve(name+"-result.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
    }
    @Test @SuppressWarnings("removal") // JDK 21 test-only fail-closed network/exec probe; no product or null-analysis suppression.
    void realCoreRunsUnderFailingNetworkExecAndScientificDependencyGuards() throws Exception {
        Path actual = Ps2TestDirectory.create("execution-probe");
        assertTrue(Path.of(System.getProperty("java.io.tmpdir")).toRealPath().startsWith(actual.getParent().getParent()));
        ObjectNode b = Bp2TestMaterials.base("alpha"); byte[] bytes = mapper.writeValueAsBytes(b);
        String raw = FamilyDeNovoJson.sha256(bytes), canonical = adapter.canonicalSha256(b);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        SecurityManager previous = System.getSecurityManager();
        SecurityManager guard = new SecurityManager() {
            @Override public void checkPermission(java.security.Permission permission) { }
            @Override public void checkConnect(String host, int port) { attempts.incrementAndGet(); throw new SecurityException("BP2_NETWORK_FORBIDDEN"); }
            @Override public void checkExec(String command) { attempts.incrementAndGet(); throw new SecurityException("BP2_EXEC_FORBIDDEN"); }
        };
        // Load each production core class in this child; all other project suppliers/DB classes are denied.
        var allowed = java.util.Set.of("FamilyDeNovoJson", "FamilyDeNovoPrerequisites", "Bp2PhaseBundle", "Bp2PhasePolicy", "Bp2PhaseCriterionEvaluator", "AcmgSpecificationSelector", "RuntimeEvidenceV2Adapter", "CaseFamilyObservation");
        var deniedLoads = new java.util.concurrent.atomic.AtomicInteger();
        try (var isolated = new java.net.URLClassLoader(new java.net.URL[] { getClass().getProtectionDomain().getCodeSource().getLocation() }, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("java.sql.") || name.startsWith("javax.sql.")) { deniedLoads.incrementAndGet(); throw new ClassNotFoundException("DB forbidden"); }
                if (!name.startsWith("org.nbscn.varvis.")) return super.loadClass(name, resolve);
                String simple = name.substring(name.lastIndexOf('.') + 1).split("\\$")[0];
                if (!allowed.contains(simple)) { deniedLoads.incrementAndGet(); throw new ClassNotFoundException("Scientific/project supplier forbidden: " + name); }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name); if (loaded == null) loaded = findClass(name); if (resolve) resolveClass(loaded); return loaded;
                }
            }
        }) {
            assertThrows(ClassNotFoundException.class, () -> isolated.loadClass("org.nbscn.varvis.variantanalysis.provider.CodingMissenseResolver"));
            assertThrows(ClassNotFoundException.class, () -> isolated.loadClass("java.sql.DriverManager"));
            assertEquals(2, deniedLoads.get());
            System.setSecurityManager(guard);
            assertThrows(SecurityException.class, () -> guard.checkConnect("127.0.0.1", 1));
            assertThrows(SecurityException.class, () -> guard.checkExec("TEST_ONLY_NOT_EXECUTED"));
            assertEquals(2, attempts.get());
            Class<?> bundleClass = isolated.loadClass("org.nbscn.varvis.variantanalysis.provider.Bp2PhaseBundle");
            Object bundle = bundleClass.getMethod("load", ObjectMapper.class, byte[].class, String.class, String.class).invoke(null, mapper, bytes, raw, canonical);
            Class<?> evaluator = isolated.loadClass("org.nbscn.varvis.variantanalysis.provider.Bp2PhaseCriterionEvaluator");
            Object instance = evaluator.getConstructor(ObjectMapper.class).newInstance(mapper);
            ObjectNode result = (ObjectNode) evaluator.getMethod("evaluate", bundleClass).invoke(instance, bundle);
            assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
            assertEquals(2, attempts.get()); assertEquals(2, deniedLoads.get());
            archive("guarded-real-core", b, result);
        } finally { System.setSecurityManager(previous); }
    }
}
