package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** F-111 / SF-111-04 — ce que le runner est en train de faire, pour qu'une mise à jour attende. */
class RunnerActivityTest {

    @AfterEach
    void tearDown() {
        RunnerActivity.reset();
    }

    @Test
    void sondesEtBlocsDisentCeQuiTourne() {
        RunnerActivity.reset();
        AtomicBoolean capture = new AtomicBoolean(false);
        RunnerActivity.probe(RunnerActivity.CAPTURE, capture::get);
        assertTrue(RunnerActivity.busy().isEmpty(), "calme");

        capture.set(true);
        try (RunnerActivity.Scope ignored = RunnerActivity.begin(RunnerActivity.DOWNLOAD)) {
            assertEquals(List.of(RunnerActivity.CAPTURE, RunnerActivity.DOWNLOAD), RunnerActivity.busy());
        }
        assertEquals(List.of(RunnerActivity.CAPTURE), RunnerActivity.busy(), "le bloc fermé ne compte plus");
    }

    @Test
    void uneSondeReposeeRemplaceLaPrecedenteEtUneSondeQuiLeveSeTait() {
        RunnerActivity.reset();
        RunnerActivity.probe(RunnerActivity.COMMAND, () -> true);
        RunnerActivity.probe(RunnerActivity.COMMAND, () -> false);
        RunnerActivity.probe(RunnerActivity.SYNC, () -> {
            throw new IllegalStateException("sonde cassée");
        });

        assertTrue(RunnerActivity.busy().isEmpty());
    }

    @Test
    void unAppelDOutilEnVolRendLeRunnerOccupe() throws Exception {
        RunnerActivity.reset();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (FrameSender sender = new FrameSender(new Console());
                ToolDispatcher dispatcher = new ToolDispatcher((tool, input, context) -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolOutcome.ok("");
                }, sender, new Console())) {
            com.fasterxml.jackson.databind.node.ObjectNode call = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createObjectNode().put("id", "toolu_1").put("tool", "read").put("timeoutMs", 5000);
            dispatcher.onToolCall(call);

            assertEquals(List.of(RunnerActivity.COMMAND), RunnerActivity.busy());
            release.countDown();
        }
    }
}
