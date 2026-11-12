package org.nbscn.varvis.variantanalysis.provider;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Windows x64 only. All calls, including DLL loading/CloseHandle, run on the ticket's owner. */
final class WindowsJobExecution implements OwnedQueryExecution.NativeExecution {
    interface Api extends StdCallLibrary {
        Pointer CreateJobObjectW(Pointer attributes, WString name);
        boolean SetInformationJobObject(Pointer job, int kind, Structure value, int size);
        boolean QueryInformationJobObject(Pointer job, int kind, Structure value, int size, Pointer returned);
        boolean TerminateJobObject(Pointer job, int code);
        boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, LongByReference size);
        boolean UpdateProcThreadAttribute(Pointer list, int flags, long attribute, Pointer value, long size, Pointer previous, Pointer returned);
        void DeleteProcThreadAttributeList(Pointer list);
        boolean CreateProcessW(WString executable, char[] command, Pointer processSecurity, Pointer threadSecurity,
                               boolean inherit, int flags, Pointer environment, WString cwd, StartupEx startup,
                               WinBase.PROCESS_INFORMATION process);
        Pointer CreateFileW(WString name, int access, int sharing, WinBase.SECURITY_ATTRIBUTES security,
                            int creation, int flags, Pointer template);
        boolean IsProcessInJob(Pointer process, Pointer job, IntByReference result);
        int ResumeThread(Pointer thread);
        int WaitForSingleObject(Pointer handle, int millis);
        boolean GetExitCodeProcess(Pointer process, IntByReference code);
        boolean GetProcessTimes(Pointer process, WinBase.FILETIME created, WinBase.FILETIME exited,
                                WinBase.FILETIME kernel, WinBase.FILETIME user);
        boolean CloseHandle(Pointer handle);
        int GetLastError();
        int GetSystemDirectoryW(char[] buffer, int size);
        Pointer OpenProcess(int access, boolean inherit, int pid);
        boolean QueryFullProcessImageNameW(Pointer process, int flags, char[] image, IntByReference length);
    }
    @Structure.FieldOrder({"startup", "attributes"})
    public static class StartupEx extends Structure {
        public WinBase.STARTUPINFO startup = new WinBase.STARTUPINFO();
        public Pointer attributes;
    }
    @Structure.FieldOrder({"processTime", "jobTime", "flags", "minWorking", "maxWorking", "activeLimit", "affinity", "priority", "scheduling"})
    public static class BasicLimit extends Structure {
        public long processTime, jobTime; public int flags; public long minWorking, maxWorking;
        public int activeLimit; public long affinity; public int priority, scheduling;
    }
    @Structure.FieldOrder({"basic", "io", "processMemory", "jobMemory", "peakProcessMemory", "peakJobMemory"})
    public static class ExtendedLimit extends Structure {
        public BasicLimit basic = new BasicLimit(); public long[] io = new long[6];
        public long processMemory, jobMemory, peakProcessMemory, peakJobMemory;
    }
    @Structure.FieldOrder({"user", "kernel", "periodUser", "periodKernel", "faults", "total", "active", "terminated"})
    public static class Accounting extends Structure {
        public long user, kernel, periodUser, periodKernel; public int faults, total, active, terminated;
    }
    @Structure.FieldOrder({"assigned", "listed", "ids"})
    public static class ProcessIds extends Structure {
        public int assigned, listed;
        public Pointer[] ids = new Pointer[64];
    }
    private final Api api;
    private Pointer job, process, thread, stdin, stdout, stderr;
    private Memory attributes, jobList, handleList, environment;
    private boolean attributesInitialized;
    private long pid;
    private String creationIdentity = "";
    private Integer releasedRootExitCode;
    WindowsJobExecution() {
        this(Binding.API);
    }
    private static final class Binding { private static final Api API = Native.load("kernel32", Api.class); }
    WindowsJobExecution(Api api) {
        if (!System.getProperty("os.name").startsWith("Windows") || Native.POINTER_SIZE != 8)
            throw new IllegalStateException("UNSUPPORTED_EXECUTION_TOPOLOGY");
        this.api = api;
        if (new StartupEx().size() != 112 || new ExtendedLimit().size() != 144 || new Accounting().size() != 48)
            throw new IllegalStateException("WIN32_STRUCTURE_SIZE_MISMATCH");
    }
    @Override public void launch(OwnedQueryExecution.CommandSpec spec) throws IOException {
        var command = windowsCommand(spec.argv());
        job = requireHandle(api.CreateJobObjectW(null, null), "CreateJobObjectW");
        var limit = new ExtendedLimit(); limit.basic.flags = 0x2000; // KILL_ON_JOB_CLOSE, no breakaway flags.
        check(api.SetInformationJobObject(job, 9, limit, limit.size()), "SetInformationJobObject");
        var security = new WinBase.SECURITY_ATTRIBUTES(); security.bInheritHandle = true;
        stdin = requireHandle(api.CreateFileW(new WString("NUL"), 0x80000000, 7, security, 3, 0, null), "CreateFileW(stdin)");
        stdout = requireHandle(api.CreateFileW(new WString(spec.output().toString()), 0x40000000, 7, security, 2, 0, null), "CreateFileW(stdout)");
        stderr = requireHandle(api.CreateFileW(new WString(spec.error().toString()), 0x40000000, 7, security, 2, 0, null), "CreateFileW(stderr)");
        var size = new LongByReference(); api.InitializeProcThreadAttributeList(null, 2, 0, size);
        if (size.getValue() <= 0 || size.getValue() > 65536) throw new IOException("WIN32_ATTRIBUTE_SIZE_INVALID");
        attributes = new Memory(size.getValue());
        check(api.InitializeProcThreadAttributeList(attributes, 2, 0, size), "InitializeProcThreadAttributeList"); attributesInitialized = true;
        jobList = new Memory(8); jobList.setPointer(0, job);
        handleList = new Memory(24); handleList.setPointer(0, stdin); handleList.setPointer(8, stdout); handleList.setPointer(16, stderr);
        check(api.UpdateProcThreadAttribute(attributes, 0, 0x2000d, jobList, 8, null, null), "JOB_LIST");
        check(api.UpdateProcThreadAttribute(attributes, 0, 0x20002, handleList, 24, null, null), "HANDLE_LIST");
        var startup = new StartupEx(); startup.startup.cb = new DWORD(startup.size());
        startup.startup.dwFlags = 0x100; startup.startup.hStdInput = new HANDLE(stdin);
        startup.startup.hStdOutput = new HANDLE(stdout); startup.startup.hStdError = new HANDLE(stderr); startup.attributes = attributes;
        var variables = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER); variables.putAll(System.getenv()); variables.putAll(spec.envDelta());
        StringBuilder block = new StringBuilder(); variables.forEach((key, value) -> block.append(key).append('=').append(value).append('\0')); block.append('\0');
        environment = new Memory((long) block.length() * 2); environment.setWideString(0, block.substring(0, block.length() - 1));
        var info = new WinBase.PROCESS_INFORMATION();
        check(api.CreateProcessW(new WString(command.executable()), (command.line() + '\0').toCharArray(), null, null, true,
                0x80000 | 0x4 | 0x400 | 0x08000000, environment, new WString(spec.cwd().toString()), startup, info), "CreateProcessW");
        process = info.hProcess.getPointer(); thread = info.hThread.getPointer(); pid = Integer.toUnsignedLong(info.dwProcessId.intValue());
        var bound = new IntByReference(); check(api.IsProcessInJob(process, job, bound), "IsProcessInJob");
        if (bound.getValue() == 0) throw new IOException("WIN32_JOB_BINDING_MISSING");
        var created = new WinBase.FILETIME();
        check(api.GetProcessTimes(process, created, new WinBase.FILETIME(), new WinBase.FILETIME(), new WinBase.FILETIME()), "GetProcessTimes");
        creationIdentity = Integer.toUnsignedString(created.dwHighDateTime) + ":" + Integer.toUnsignedString(created.dwLowDateTime);
    }
    @Override public void resume() throws IOException { if (api.ResumeThread(thread) == -1) throw failure("ResumeThread"); }
    @Override public boolean rootExited(int millis) throws IOException {
        if (process == null) return releasedRootExitCode != null;
        int status = api.WaitForSingleObject(process, millis);
        if (status == -1) throw failure("WaitForSingleObject"); return status == 0;
    }
    @Override public int exitCode() throws IOException {
        if (releasedRootExitCode != null) return releasedRootExitCode;
        var result = new IntByReference(); check(api.GetExitCodeProcess(process, result), "GetExitCodeProcess"); return result.getValue();
    }
    @Override public int activeProcesses() throws IOException {
        if (job == null) return 0;
        var result = new Accounting(); check(api.QueryInformationJobObject(job, 1, result, result.size(), null), "QueryInformationJobObject");
        return result.active;
    }
    @Override public void terminate() throws IOException { if (job != null) check(api.TerminateJobObject(job, 1), "TerminateJobObject"); }
    @Override public Map<String, Object> identity() { return Map.of("processId", pid, "rootCreationIdentity", creationIdentity); }
    @Override public List<Map<String, Object>> memberIdentities() throws IOException {
        return members(false);
    }
    private List<Map<String, Object>> members(boolean detailed) throws IOException {
        var result = new ProcessIds();
        check(api.QueryInformationJobObject(job, 3, result, result.size(), null), "QueryJobProcessIds");
        if (result.listed < 0 || result.listed > 64) throw new IOException("OWNED_PROCESS_IDENTITY_LIMIT");
        var identities = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < result.listed; i++) {
            long memberId = Pointer.nativeValue(result.ids[i]);
            Pointer member = api.OpenProcess(detailed ? 0x101000 : 0x1000, false, (int) memberId);
            if (member == null) {
                if (detailed) identities.add(Map.of("processId", memberId, "observation", "OPEN_PROCESS_RACE_OR_FAILURE", "win32Error", api.GetLastError()));
                continue;
            }
            try {
                var bound = new IntByReference();
                check(api.IsProcessInJob(member, job, bound), "IsMemberInJob");
                if (bound.getValue() == 0) {
                    if (detailed) identities.add(Map.of("processId", memberId, "observation", "NO_LONGER_IN_THIS_JOB"));
                    continue;
                }
                var created = new WinBase.FILETIME();
                check(api.GetProcessTimes(member, created, new WinBase.FILETIME(), new WinBase.FILETIME(), new WinBase.FILETIME()), "GetMemberTimes");
                var observation = new LinkedHashMap<String, Object>();
                observation.put("processId", memberId); observation.put("jobBound", true);
                observation.put("creationIdentity", Integer.toUnsignedString(created.dwHighDateTime) + ":" + Integer.toUnsignedString(created.dwLowDateTime));
                if (detailed) {
                    int wait = api.WaitForSingleObject(member, 0);
                    if (wait == -1) throw failure("WaitForMember");
                    observation.put("waitResult", wait); observation.put("signaled", wait == 0);
                    var code = new IntByReference(); check(api.GetExitCodeProcess(member, code), "GetMemberExitCode");
                    observation.put("exitCodeObserved", wait == 0 ? code.getValue() : "UNKNOWN_RUNNING");
                    char[] image = new char[32768]; var length = new IntByReference(image.length);
                    if (api.QueryFullProcessImageNameW(member, 0, image, length)) {
                        String fullName = new String(image, 0, length.getValue());
                        observation.put("executableImage", Path.of(fullName).getFileName().toString());
                    } else {
                        // Image metadata can disappear during process teardown. Keep verified Job/creation/wait
                        // facts and the exact diagnostic failure; never reinterpret a running member as exited.
                        observation.put("executableImage", "UNKNOWN");
                        observation.put("imageQueryReason", "QUERY_MEMBER_IMAGE_UNAVAILABLE");
                        observation.put("imageQueryWin32Error", api.GetLastError());
                    }
                    observation.put("sampledAt", java.time.Instant.now().toString());
                    observation.put("diagnosticHandleReleasedBeforeReturn", true);
                }
                identities.add(observation);
            } finally { check(api.CloseHandle(member), "CloseMemberHandle"); }
        }
        return identities;
    }
    @Override public void releaseExitedRootReferences() throws IOException {
        if (process == null) return;
        if (!rootExited(0)) throw new IOException("ROOT_NOT_SIGNALED");
        releasedRootExitCode = exitCode();
        // Single owner: save scalar exit facts before releasing process/thread references.
        // Keep the Job handle, output handles and inheritance attributes until Job empty.
        if (thread != null) { check(api.CloseHandle(thread), "CloseExitedThread"); thread = null; }
        check(api.CloseHandle(process), "CloseExitedRoot"); process = null;
    }
    @Override public Map<String, Object> diagnostics(String stage) throws IOException {
        var result = new LinkedHashMap<String, Object>(); result.put("stage", stage);
        result.put("accountingSampledAt", java.time.Instant.now().toString());
        result.put("activeProcesses", activeProcesses());
        result.put("pidListSampledAt", java.time.Instant.now().toString());
        result.put("members", job == null ? List.of() : members(true));
        result.put("rootProcessHandleHeld", process != null); result.put("rootThreadHandleHeld", thread != null);
        result.put("jobControlHandleHeld", job != null); result.put("diagnosticHandlesHeld", 0);
        if (process != null) {
            int wait = api.WaitForSingleObject(process, 0);
            if (wait == -1) throw failure("WaitForRootDiagnostic");
            result.put("rootWaitResult", wait); result.put("rootExitCodeObserved", wait == 0 ? exitCode() : "UNKNOWN_RUNNING");
        } else if (releasedRootExitCode != null) {
            result.put("rootWaitResult", 0); result.put("rootExitCodeObserved", releasedRootExitCode);
        }
        return result;
    }
    @Override public void close() throws IOException {
        // Clear each field only after that handle closes. A failed close keeps the owner slot reserved.
        if (thread != null) { check(api.CloseHandle(thread), "CloseHandle(thread)"); thread = null; }
        if (process != null) { check(api.CloseHandle(process), "CloseHandle(process)"); process = null; }
        if (stdin != null) { check(api.CloseHandle(stdin), "CloseHandle(stdin)"); stdin = null; }
        if (stdout != null) { check(api.CloseHandle(stdout), "CloseHandle(stdout)"); stdout = null; }
        if (stderr != null) { check(api.CloseHandle(stderr), "CloseHandle(stderr)"); stderr = null; }
        if (attributesInitialized) { api.DeleteProcThreadAttributeList(attributes); attributesInitialized = false; }
        if (job != null) { check(api.CloseHandle(job), "CloseHandle(job)"); job = null; }
        for (Memory value : new Memory[]{attributes, jobList, handleList, environment}) if (value != null) value.close();
        attributes = null; jobList = null; handleList = null; environment = null;
    }
    private Pointer requireHandle(Pointer value, String operation) throws IOException {
        if (value == null || Pointer.nativeValue(value) == -1) throw failure(operation); return value;
    }
    private void check(boolean ok, String operation) throws IOException { if (!ok) throw failure(operation); }
    private IOException failure(String operation) { return new IOException("WIN32_" + operation + "_ERROR_" + api.GetLastError()); }

    record WindowsCommand(String executable, String line) { }
    static WindowsCommand windowsCommand(List<String> argv) throws IOException {
        if (argv.isEmpty()) throw new IOException("EMPTY_EXECUTION_COMMAND");
        Path executable = Path.of(argv.get(0));
        if (!executable.isAbsolute()) {
            executable = resolveExecutable(argv.get(0));
        }
        executable = executable.toAbsolutePath().normalize();
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Set.of("wsl.exe", "ssh.exe", "docker.exe", "wmic.exe", "wsl", "ssh", "docker").contains(name))
            throw new IOException("UNSUPPORTED_EXECUTION_TOPOLOGY_" + name);
        for (String argument : argv) if (argument.indexOf('\0') >= 0) throw new IOException("INVALID_EXECUTION_ARGUMENT");
        if (name.endsWith(".cmd") || name.endsWith(".bat")) {
            // Existing local fixture scripts require cmd.exe. Reject expansion/control syntax in argv.
            var script = new ArrayList<>(argv); script.set(0, executable.toString());
            for (String argument : script) if (argument.matches("(?s).*[\"&|<>^%!\\r\\n].*"))
                throw new IOException("UNSUPPORTED_SHELL_ARGUMENT");
            char[] system = new char[32768];
            int length = Binding.API.GetSystemDirectoryW(system, system.length);
            if (length <= 0 || length >= system.length) throw new IOException("SYSTEM_DIRECTORY_UNAVAILABLE");
            Path shellPath = Path.of(new String(system, 0, length), "cmd.exe");
            if (!Files.isRegularFile(shellPath)) throw new IOException("SYSTEM_CMD_UNAVAILABLE");
            String shell = shellPath.toString();
            String body = String.join(" ", script.stream().map(WindowsJobExecution::quote).toList());
            return new WindowsCommand(shell, quote(shell) + " /d /v:off /s /c \"" + body + "\"");
        }
        var resolved = new ArrayList<>(argv); resolved.set(0, executable.toString());
        return new WindowsCommand(executable.toString(), String.join(" ", resolved.stream().map(WindowsJobExecution::quote).toList()));
    }
    private static Path resolveExecutable(String name) throws IOException {
        for (String directory : Objects.toString(System.getenv("PATH"), "").split(";")) {
            for (String suffix : List.of("", ".exe")) {
                Path path = Path.of(directory, name + suffix); if (Files.isRegularFile(path)) return path;
            }
        }
        throw new IOException("EXECUTABLE_NOT_FOUND");
    }
    /** CommandLineToArgvW/CRT quoting, including empty arguments and trailing backslashes. */
    static String quote(String value) {
        var result = new StringBuilder("\""); int slashes = 0;
        for (char c : value.toCharArray()) {
            if (c == '\\') { slashes++; continue; }
            result.append("\\".repeat(c == '"' ? slashes * 2 + 1 : slashes)).append(c); slashes = 0;
        }
        return result.append("\\".repeat(slashes * 2)).append('"').toString();
    }
}
