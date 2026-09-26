package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.dto.AtelierChatRequest;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>« Demander quand même » doit survivre au changement de thread</b> (F-161 / SF-161-01).
 *
 * <p>La porte du runner n'est acceptable que parce qu'elle a une échappatoire. Or le relais SSE —
 * <b>le seul chemin qu'emprunte l'écran</b> — ne tourne pas sur le thread de la requête : il est
 * remis à un pool. Une variante portée par une variable de thread y serait <b>invisible</b>, et
 * l'échappatoire ne marcherait que sur le chemin synchrone, celui que personne n'utilise.</p>
 *
 * <p>C'est pourquoi l'exécuteur de ce test est un <b>vrai pool</b>, sur un <b>autre thread</b> :
 * un {@code Runnable::run} synchrone laisserait passer exactement le défaut qu'on veut interdire.
 * Contre la première version de SF-161-01 (drapeau en {@code ThreadLocal}), ce test échoue.</p>
 */
class AtelierChatControllerForceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    private AtelierChatService chatService;
    private AtelierThreadService threadService;
    private CurrentUser currentUser;
    private AtelierAccessService access;
    private LiveTurnRegistry liveTurns;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        chatService = Mockito.mock(AtelierChatService.class);
        threadService = Mockito.mock(AtelierThreadService.class);
        currentUser = Mockito.mock(CurrentUser.class);
        access = Mockito.mock(AtelierAccessService.class);
        liveTurns = new LiveTurnRegistry(new com.fasterxml.jackson.databind.ObjectMapper());
        pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "sse-pool-de-test"));
        when(currentUser.requireId()).thenReturn(USER);
        when(access.hasTerminalAccess(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void leDrapeauDemanderQuandMemeArriveJusquALaBoucleSurLAutreThread() throws Exception {
        Capture capture = capture();

        controller().stream(WORKSPACE, forceRequest(true));

        assertThat(capture.latch.await(5, TimeUnit.SECONDS))
                .as("la boucle doit avoir été appelée")
                .isTrue();
        assertThat(capture.force.get())
                .as("« demander quand même » doit traverser le pool SSE")
                .isTrue();
        assertThat(capture.sameThread.get())
                .as("le test ne prouve rien si la boucle tourne sur le thread de la requête")
                .isFalse();
    }

    @Test
    void sansLeDrapeauLaPorteResteEnPlace() throws Exception {
        Capture capture = capture();

        controller().stream(WORKSPACE, new AtelierChatRequest("lance les tests"));

        assertThat(capture.latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(capture.force.get())
                .as("une requête ordinaire ne force rien : la porte doit s'appliquer")
                .isFalse();
    }

    /** Une requête portant explicitement le drapeau, comme l'écran l'envoie après un refus. */
    private static AtelierChatRequest forceRequest(boolean force) {
        return new AtelierChatRequest("lance les tests", null, force);
    }

    private Capture capture() {
        Capture capture = new Capture();
        Thread requestThread = Thread.currentThread();
        when(chatService.chatStreaming(any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> {
                    capture.force.set(invocation.getArgument(5));
                    capture.sameThread.set(Thread.currentThread() == requestThread);
                    capture.latch.countDown();
                    return new AtelierChatResult("terminé", List.of(), UUID.randomUUID());
                });
        return capture;
    }

    /** Ce que la boucle a réellement reçu, et sur quel thread. */
    private static final class Capture {
        private final AtomicReference<Boolean> force = new AtomicReference<>();
        private final AtomicBoolean sameThread = new AtomicBoolean(true);
        private final CountDownLatch latch = new CountDownLatch(1);
    }

    /** Le contrôleur monté sur un <b>vrai</b> pool : c'est la condition du test. */
    private AtelierChatController controller() {
        return new AtelierChatController(chatService, threadService, currentUser, access,
                pool, Runnable::run, liveTurns,
                fr.claudegateway.runner.relay.RelayTurnSource.disabled(),
                new fr.claudegateway.quota.TurnCostView(
                        Mockito.mock(fr.claudegateway.admin.AdminService.class),
                        new fr.claudegateway.quota.ProviderPricingProperties(
                                null, null, null, null, null, null)));
    }
}
