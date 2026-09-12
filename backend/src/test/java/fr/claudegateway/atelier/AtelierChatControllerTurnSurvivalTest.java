package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierConfirmRequest;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierStepEvent;
import fr.claudegateway.atelier.dto.AtelierChatRequest;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le test qui prouve F-84</b> (SF-84-01) : un tour en cours dont le flux SSE est fermé
 * <b>brutalement</b> va jusqu'au bout — vérifié <b>côté serveur</b>, jamais à l'écran.
 *
 * <p>Contre le code d'avant F-84, il échoue : le premier envoi lève une {@code IOException},
 * traduite en {@code StreamAbortedException}, qui remonte au travers de la boucle et l'arrête. La
 * boucle n'a alors exécuté qu'une étape sur quatre et n'a jamais rendu son résultat. C'est
 * exactement le défaut rapporté en production le 2026-09-12 : « si je sors du terminal pendant qu'il
 * réfléchit, quand je reviens il a arrêté de réfléchir ».</p>
 *
 * <p>La leçon de SF-79-02 est appliquée : ce garde-fou a été vu <b>rouge</b> avant d'être vu vert.</p>
 */
class AtelierChatControllerTurnSurvivalTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    private AtelierChatService chatService;
    private AtelierThreadService threadService;
    private CurrentUser currentUser;
    private AtelierAccessService access;
    private LiveTurnRegistry liveTurns;

    @BeforeEach
    void setUp() {
        chatService = Mockito.mock(AtelierChatService.class);
        threadService = Mockito.mock(AtelierThreadService.class);
        currentUser = Mockito.mock(CurrentUser.class);
        access = Mockito.mock(AtelierAccessService.class);
        liveTurns = new LiveTurnRegistry(new com.fasterxml.jackson.databind.ObjectMapper());
        when(currentUser.requireId()).thenReturn(USER);
        when(access.hasAccess()).thenReturn(true);
    }

    @Test
    void leTourVaJusquAuBoutQuandLeFluxEstFermeBrutalement() {
        AtomicInteger steps = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean(false);
        when(chatService.chatStreaming(any(), any(), any(), any())).thenAnswer(invocation -> {
            AtelierProgressListener listener = invocation.getArgument(3);
            listener.onText("je commence");
            steps.incrementAndGet();
            listener.onAction(new AtelierStepEvent("read", "pom.xml"));
            steps.incrementAndGet();
            listener.onConfirmRequest(new AtelierConfirmRequest("call-1", "bash", "ls", 120_000L));
            steps.incrementAndGet();
            listener.onText("j'ai fini");
            steps.incrementAndGet();
            finished.set(true);
            return new AtelierChatResult("terminé", List.of(), UUID.randomUUID());
        });

        controller().stream(WORKSPACE, new AtelierChatRequest("lance les tests"));

        assertThat(steps.get())
                .as("le tour doit exécuter ses quatre étapes malgré le flux mort")
                .isEqualTo(4);
        assertThat(finished.get())
                .as("le tour doit rendre son résultat : il a fini, il n'a pas été abandonné")
                .isTrue();
    }

    /** Le contrôleur monté sur un exécuteur synchrone et sur l'émetteur d'un navigateur parti. */
    private AtelierChatController controller() {
        return new AtelierChatController(chatService, threadService, currentUser, access,
                Runnable::run, liveTurns) {
            @Override
            SseEmitter newEmitter() {
                return new DeadEmitter();
            }
        };
    }

    /**
     * L'émetteur d'un navigateur <b>parti</b> : tout envoi échoue, comme après un changement de
     * route qui a détruit le composant et fermé la connexion.
     */
    private static final class DeadEmitter extends SseEmitter {

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("le navigateur est parti");
        }
    }
}
