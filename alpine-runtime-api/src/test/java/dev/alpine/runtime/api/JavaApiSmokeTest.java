package dev.alpine.runtime.api;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import org.junit.Test;

public final class JavaApiSmokeTest {
    @Test
    public void javaHostCanConstructCoreContracts() {
        RuntimeState state = new RuntimeState(RuntimeLifecycleState.READY);
        RuntimeCommandRequest command = new RuntimeCommandRequest(
                "/bin/echo",
                Collections.singletonList("hello"),
                "/workspace",
                Collections.emptyMap(),
                1_000L);

        assertEquals(RuntimeLifecycleState.READY, state.getLifecycle());
        assertEquals("/bin/echo", command.getExecutable());
    }
}
