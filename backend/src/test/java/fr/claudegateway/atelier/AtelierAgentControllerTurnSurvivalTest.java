package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.agent.AtelierAgentListener;
import fr.claudegateway.atelier.agent.AtelierAgentProperties;
import fr.claudegateway.atelier.agent.AtelierSessionResult;
import fr.claudegateway.atelier.agent.AtelierSessionService;
import fr.claudegateway.atelier.dto.AtelierAgentRequest;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.auth.CurrentUser;

/**
 * Le même garde-fou que {@link AtelierChatControllerTurnSurvivalTest}, sur le <b>second</b>
 * contrôleur (F-84 / SF-84-01).
 *
 * <p>Le cadrage l'exigeait explicitement : {@code AtelierAgentController} portait la même mécanique
 * et les mêmes {@code StreamAbortedException}. Les deux sont traités, et les deux le prouvent.</p>
 */
class AtelierAgentControllerTurnSurvivalTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    private AtelierSessionService sessionService;
    private AtelierAccessService access;
    private CurrentUser currentUser;
    private LiveTurnRegistry liveTurns;

    @BeforeEach
    void setUp() {
        sessionService = Mockito.mock(AtelierSessionService.class);
        access = Mockito.mock(AtelierAccessService.class);
        currentUser = Mockito.mock(CurrentUser.class);
        liveTurns = new LiveTurnRegistry(new ObjectMapper());
        when(currentUser.requireId()).thenReturn(USER);
        when(access.hasAccess()).thenReturn(true);
    }

    @Test
    void leRunVaJusquAuBoutQuandLeFluxEstFermeBrutalement() {
        AtomicInteger steps = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean(false);
        when(sessionService.runTaskStreaming(any(), any(), any(), any())).thenAnswer(invocation -> {
            AtelierAgentListener listener = invocation.getArgument(3);
            listener.onStatus("running");
            steps.incrementAndGet();
            listener.onAgentText("je regarde le projet");
            steps.incrementAndGet();
            listener.onAction("bash", "call-1", "ls", null);
            steps.incrementAndGet();
            listener.onConfirmationRequest("bash", "call-1", "rm -rf build");
            steps.incrementAndGet();
            listener.onAgentText("c'est fait");
            steps.incrementAndGet();
            finished.set(true);
            return new AtelierSessionResult("terminé", List.of(), 0L, 0L, 0L, false, false,
                    List.of());
        });

        controller().stream(WORKSPACE, new AtelierAgentRequest("lance les tests"));

        assertThat(steps.get())
                .as("le run doit exécuter ses cinq étapes malgré le flux mort")
                .isEqualTo(5);
        assertThat(finished.get())
                .as("le run doit rendre son résultat : il a fini, il n'a pas été abandonné")
                .isTrue();
    }

    private AtelierAgentController controller() {
        AtelierAgentProperties properties = new AtelierAgentProperties(true, null, null, null, null,
                null, null, null, null, null, null, null, null, true, null, null, Duration.ZERO);
        return new AtelierAgentController(sessionService, access, properties, currentUser,
                Runnable::run, liveTurns) {
            @Override
            SseEmitter newEmitter() {
                return new DeadEmitter();
            }
        };
    }

    /** L'émetteur d'un navigateur parti : tout envoi échoue. */
    private static final class DeadEmitter extends SseEmitter {

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("le navigateur est parti");
        }
    }
}
