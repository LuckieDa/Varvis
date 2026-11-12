package org.nbscn.varvis.variantanalysis.provider;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mechanical API races only: no real PID reuse, scientific tool or unowned process is launched. */
class WindowsJobMemberIdentityTest {
    private final WindowsJobExecution.Api api = mock(WindowsJobExecution.Api.class);
    private final Pointer member = new Pointer(43);

    private WindowsJobExecution execution(boolean belongs) throws Exception {
        when(api.QueryInformationJobObject(any(), anyInt(), any(), anyInt(), isNull())).thenAnswer(call -> {
            Object result = call.getArgument(2);
            if (result instanceof WindowsJobExecution.ProcessIds ids) {
                ids.assigned = 1; ids.listed = 1; ids.ids[0] = new Pointer(42);
            }
            if (result instanceof WindowsJobExecution.Accounting accounting) accounting.active = 1;
            return true;
        });
        when(api.OpenProcess(anyInt(), eq(false), eq(42))).thenReturn(member);
        when(api.IsProcessInJob(eq(member), any(), any())).thenAnswer(call -> {
            ((IntByReference) call.getArgument(2)).setValue(belongs ? 1 : 0); return true;
        });
        when(api.CloseHandle(any())).thenReturn(true);
        var execution = new WindowsJobExecution(api);
        var job = WindowsJobExecution.class.getDeclaredField("job");
        job.setAccessible(true); job.set(execution, new Pointer(1));
        return execution;
    }

    @Test void reopenedPidOutsideThisJobCannotBecomeAnOwnedMember() throws Exception {
        var execution = execution(false);
        try {
            assertTrue(execution.memberIdentities().isEmpty());
            Map<String, Object> diagnostic = execution.diagnostics("PID_REUSED_OUTSIDE_JOB");
            var row = (Map<?, ?>) ((List<?>) diagnostic.get("members")).get(0);
            assertEquals("NO_LONGER_IN_THIS_JOB", row.get("observation"));
            assertFalse(OwnedQueryExecution.onlyExitedMembers(diagnostic));
            verify(api, never()).GetProcessTimes(any(), any(), any(), any(), any());
            verify(api, never()).WaitForSingleObject(eq(member), anyInt());
            verify(api, times(2)).CloseHandle(member);
            verify(api, never()).TerminateJobObject(any(), anyInt());
        } finally { execution.close(); }
    }

    @Test void samePidWithDifferentCreationTimesKeepsDistinctIdentitiesAndClosesHandles() throws Exception {
        var execution = execution(true);
        var samples = new AtomicInteger();
        when(api.GetProcessTimes(eq(member), any(), any(), any(), any())).thenAnswer(call -> {
            WinBase.FILETIME created = call.getArgument(1);
            created.dwHighDateTime = 5; created.dwLowDateTime = samples.incrementAndGet(); return true;
        });
        try {
            var first = execution.memberIdentities().get(0);
            var second = execution.memberIdentities().get(0);
            assertEquals(42L, first.get("processId")); assertEquals(first.get("processId"), second.get("processId"));
            assertEquals("5:1", first.get("creationIdentity")); assertEquals("5:2", second.get("creationIdentity"));
            assertEquals(true, first.get("jobBound")); assertEquals(true, second.get("jobBound"));
            verify(api, times(2)).CloseHandle(member);
            verify(api, never()).TerminateJobObject(any(), anyInt());
        } finally { execution.close(); }
    }

    @Test void inaccessibleJobMembershipIsAnErrorRatherThanAnEmptyJob() throws Exception {
        var execution = execution(true);
        when(api.QueryInformationJobObject(any(), eq(3), any(), anyInt(), isNull())).thenReturn(false);
        when(api.GetLastError()).thenReturn(5);
        try {
            var failure = assertThrows(IOException.class, execution::memberIdentities);
            assertEquals("WIN32_QueryJobProcessIds_ERROR_5", failure.getMessage());
            verify(api, never()).OpenProcess(anyInt(), anyBoolean(), anyInt());
        } finally { execution.close(); }
    }
}
