package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierConfirmRequest;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierConfirmResolved;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierSteer;
import fr.claudegateway.atelier.dto.AtelierChatRequest;
import fr.claudegateway.atelier.dto.AtelierSteerResponse;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.runner.relay.RelayTurnSource;

/**
 * <b>Un message envoyé pendant un tour devient une précision</b> (F-84 / SF-84-06).
 *
 * <p>Décision du PO du 2026-09-13 : « Ajoute comme précision ». Avant elle, un envoi sur un projet
 * dont le tour tournait encore <b>remplaçait</b> le tour — deux boucles ont tourné en parallèle en
 * production (constat de SF-84-04).</p>
 */
class AtelierChatControllerSteerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID PROJET = UUID.randomUUID();

    private AtelierChatService chatService;
    private AtelierThreadService threadService;
    private CurrentUser currentUser;
    private AtelierAccessService access;
    private LiveTurnRegistry liveTurns;
    private final Deque<RecordingEmitter> emitters = new ArrayDeque<>();
    private final AtomicReference<UUID> caller = new AtomicReference<>(ALICE);

    @BeforeEach
    void setUp() {
        chatService = Mockito.mock(AtelierChatService.class);
        threadService = Mockito.mock(AtelierThreadService.class);
        currentUser = Mockito.mock(CurrentUser.class);
        access = Mockito.mock(AtelierAccessService.class);
        liveTurns = new LiveTurnRegistry(new ObjectMapper());
        when(currentUser.requireId()).thenAnswer(invocation -> caller.get());
        when(access.hasTerminalAccess(any())).thenReturn(true);
    }

    @Test
    void renvoyerLaDemandeAuRetourSurLEcranNOuvrePasDeSecondTour() {
        RecordingEmitter premier = emitter();
        RecordingEmitter retour = emitter();
        AtomicReference<List<AtelierSteer>> lues = new AtomicReference<>();
        when(chatService.chatStreaming(any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            AtelierProgressListener listener = invocation.getArgument(4);
            listener.onText("je travaille");
            // L'écran est revenu, n'a pas vu le tour (rebranchement retenu par un proxy) et renvoie.
            controller().stream(PROJET, new AtelierChatRequest("lance les tests"));
            assertThat(liveTurns.find(ALICE, PROJET)).as("le tour vivant n'a pas été remplacé")
                    .isPresent();
            lues.set(listener.takeSteers());
            lues.get().forEach(steer -> listener.onSteerApplied(steer, 2));
            return result("fait");
        });

        controller().stream(PROJET, new AtelierChatRequest("lance les tests"));

        verify(chatService, times(1)).chatStreaming(any(), any(), any(), any(), any(), anyBoolean());
        assertThat(lues.get()).extracting(AtelierSteer::text).containsExactly("lance les tests");
        assertThat(retour.names())
                .as("l'aparté qui dit la précision, le rejeu complet du tour, puis son direct")
                .containsExactly("steered", "started", "text", LiveTurn.STEER_QUEUED,
                        LiveTurn.STEER_APPLIED, "done");
        assertThat(retour.payloads().get(0)).contains("\"steerId\":\"" + lues.get().get(0).steerId());
        assertThat(retour.payloads().get(4)).contains("\"step\":2");
        assertThat(retour.completed).as("le flux du renvoi se clôt avec le tour").isTrue();
        assertThat(premier.names()).containsExactly("started", "text", LiveTurn.STEER_QUEUED,
                LiveTurn.STEER_APPLIED, "done");
    }

    @Test
    void unePrecisionArriveePendantLaReponseFinaleOuvreUnTourDeSuite() {
        RecordingEmitter ecran = emitter();
        AtomicInteger appels = new AtomicInteger();
        when(chatService.chatStreaming(any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            if (appels.incrementAndGet() == 1) {
                // Déposée pendant l'appel qui rend la réponse finale : plus d'étape pour la lire.
                AtelierSteerResponse steer =
                        controller().steer(PROJET, new AtelierChatRequest("et ajoute un test"));
                assertThat(steer.steerId()).isNotBlank();
                return result("premier");
            }
            return result("suite");
        });

        controller().stream(PROJET, new AtelierChatRequest("corrige le bug"));

        verify(chatService).chatStreaming(eq(ALICE), eq(PROJET), eq("corrige le bug"), any(), any(), anyBoolean());
        verify(chatService).chatStreaming(eq(ALICE), eq(PROJET), eq("et ajoute un test"), any(), any(), anyBoolean());
        assertThat(ecran.names()).containsExactly("started", LiveTurn.STEER_QUEUED, "done",
                LiveTurn.STEER_FOLLOWUP, "done");
        assertThat(ecran.payloads().get(2)).contains("\"followUp\":true").contains("premier");
        assertThat(ecran.payloads().get(4)).contains("\"followUp\":false").contains("suite");
        assertThat(ecran.completed).isTrue();
        assertThatThrownBy(() -> controller().steer(PROJET, new AtelierChatRequest("trop tard")))
                .as("le tour est fini : une précision n'a plus de tour où atterrir")
                .isInstanceOf(NoLiveTurnException.class);
    }

    @Test
    void unePrecisionPendantUneAutorisationNeVautNiAccordNiRefus() {
        emitter();
        AtomicReference<List<AtelierSteer>> lues = new AtomicReference<>();
        when(chatService.chatStreaming(any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            AtelierProgressListener listener = invocation.getArgument(4);
            listener.onConfirmRequest(new AtelierConfirmRequest("call-1", "bash", "rm -rf build",
                    120_000L));
            controller().steer(PROJET, new AtelierChatRequest("garde le dossier dist"));
            LiveTurn turn = liveTurns.find(ALICE, PROJET).orElseThrow();
            assertThat(turn.pendingApproval()).as("l'attente reste en attente").isPresent();
            // La décision arrive ensuite, par sa propre route ; l'étape suivante lit la précision.
            listener.onConfirmResolved(new AtelierConfirmResolved("call-1", "allow"));
            lues.set(listener.takeSteers());
            return result("fait");
        });

        controller().stream(PROJET, new AtelierChatRequest("nettoie"));

        verify(chatService, times(1)).chatStreaming(any(), any(), any(), any(), any(), anyBoolean());
        assertThat(lues.get()).extracting(AtelierSteer::text).containsExactly("garde le dossier dist");
    }

    @Test
    void unTourInterrompuNOuvrePasDeTourDeSuiteEtDitLaPrecisionNonPriseEnCompte() {
        RecordingEmitter ecran = emitter();
        when(chatService.chatStreaming(any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            controller().steer(PROJET, new AtelierChatRequest("et ensuite déploie"));
            return result(AtelierChatService.INTERRUPTED_REPLY);
        });

        controller().stream(PROJET, new AtelierChatRequest("construis"));

        verify(chatService, times(1)).chatStreaming(any(), any(), any(), any(), any(), anyBoolean());
        assertThat(ecran.names()).containsExactly("started", LiveTurn.STEER_QUEUED,
                LiveTurn.STEERS_DROPPED, "done");
    }

    @Test
    void unTourEnErreurDitLaPrecisionNonPriseEnCompte() {
        RecordingEmitter ecran = emitter();
        when(chatService.chatStreaming(any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            controller().steer(PROJET, new AtelierChatRequest("et ensuite déploie"));
            throw new fr.claudegateway.ai.AIProviderUnavailableException("panne");
        });

        controller().stream(PROJET, new AtelierChatRequest("construis"));

        assertThat(ecran.names()).containsExactly("started", LiveTurn.STEER_QUEUED,
                LiveTurn.STEERS_DROPPED, "error");
    }

    @Test
    void lEnvoiDeBobNeToucheJamaisLeTourDAlice() {
        emitter();
        emitter();
        AtomicInteger appels = new AtomicInteger();
        AtomicReference<List<AtelierSteer>> chezAlice = new AtomicReference<>();
        when(chatService.chatStreaming(any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            if (appels.incrementAndGet() > 1) {
                return result("tour de bob");
            }
            AtelierProgressListener listener = invocation.getArgument(4);
            caller.set(BOB);
            controller().stream(PROJET, new AtelierChatRequest("je précise"));
            caller.set(ALICE);
            chezAlice.set(listener.takeSteers());
            return result("tour d'alice");
        });

        controller().stream(PROJET, new AtelierChatRequest("travaille"));

        verify(chatService).chatStreaming(eq(BOB), eq(PROJET), eq("je précise"), any(), any(), anyBoolean());
        assertThat(chezAlice.get()).as("aucune précision de BOB chez ALICE").isEmpty();
    }

    @Test
    void preciserSansTourVivantEstUnConflitNomme() {
        assertThatThrownBy(() -> controller().steer(PROJET, new AtelierChatRequest("hop")))
                .isInstanceOf(NoLiveTurnException.class);
    }

    @Test
    void preciserVerifieLAppartenanceDuProjetDAbord() {
        Mockito.doThrow(new WorkspaceNotFoundException("introuvable")).when(chatService)
                .requireSteerable(ALICE, PROJET);
        liveTurns.open(ALICE, PROJET);

        assertThatThrownBy(() -> controller().steer(PROJET, new AtelierChatRequest("hop")))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(liveTurns.find(ALICE, PROJET).orElseThrow().takeSteers()).isEmpty();
    }

    /**
     * SF-121-11 — le refus vient désormais du <b>volume</b> en attente, pas du nombre : au-delà du
     * cap de comptage, les précisions se fondent (cf. {@code LiveTurnSteerTest}).
     */
    @Test
    void uneFilePleineRefuseLaPrecisionSansToucherAuTour() {
        LiveTurn turn = liveTurns.open(ALICE, PROJET);
        turn.offerSteer("x".repeat(LiveTurn.MAX_PENDING_STEER_CHARS));

        assertThatThrownBy(() -> controller().steer(PROJET, new AtelierChatRequest("une de trop")))
                .isInstanceOf(TooManySteersException.class);

        RecordingEmitter ecran = emitter();
        controller().stream(PROJET, new AtelierChatRequest("une de trop"));
        assertThat(ecran.names()).containsExactly("error");
        assertThat(ecran.payloads().get(0)).contains("too_many_steers");
        assertThat(turn.live()).isTrue();
        verify(chatService, times(0)).chatStreaming(any(), any(), any(), any(), any(), anyBoolean());
    }

    // ------------------------------------------------------------------ outillage

    private RecordingEmitter emitter() {
        RecordingEmitter emitter = new RecordingEmitter();
        emitters.addLast(emitter);
        return emitter;
    }

    private static AtelierChatResult result(String reply) {
        return new AtelierChatResult(reply, List.of(), UUID.randomUUID());
    }

    /** Contrôleur synchrone ; chaque flux ouvert prend l'émetteur suivant de la file. */
    private AtelierChatController controller() {
        return new AtelierChatController(chatService, threadService, currentUser, access,
                Runnable::run, Runnable::run, liveTurns, RelayTurnSource.disabled(),
                new fr.claudegateway.quota.TurnCostView(
                        org.mockito.Mockito.mock(fr.claudegateway.admin.AdminService.class),
                        new fr.claudegateway.quota.ProviderPricingProperties(
                                null, null, null, null, null, null))) {
            @Override
            SseEmitter newEmitter() {
                RecordingEmitter next = emitters.pollFirst();
                return next == null ? new RecordingEmitter() : next;
            }
        };
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> raw = new ArrayList<>();
        private boolean completed;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            StringBuilder line = new StringBuilder();
            builder.build().forEach(part -> {
                Object data = part.getData();
                line.append(data instanceof byte[] bytes
                        ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                        : String.valueOf(data));
            });
            raw.add(line.toString());
        }

        @Override
        public void complete() {
            completed = true;
        }

        List<String> names() {
            return raw.stream().map(line -> between(line, "event:")).toList();
        }

        List<String> payloads() {
            return raw.stream().map(line -> between(line, "data:")).toList();
        }

        private static String between(String line, String prefix) {
            int start = line.indexOf(prefix);
            if (start < 0) {
                return "";
            }
            int from = start + prefix.length();
            int end = line.indexOf('\n', from);
            return end < 0 ? line.substring(from) : line.substring(from, end);
        }
    }
}
