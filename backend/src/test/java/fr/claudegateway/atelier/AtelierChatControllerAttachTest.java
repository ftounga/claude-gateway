package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.dto.AtelierTurnStateResponse;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.RemoteTurnSource;
import fr.claudegateway.atelier.live.TurnSubscriber;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.runner.relay.RelayTurnSource;

/**
 * Se rebrancher sur un tour en cours (F-84 / SF-84-02) : rejeu depuis un curseur, même suite pour
 * deux vues, dégradation quand rien ne tourne, et isolation {@code user_id}.
 */
class AtelierChatControllerAttachTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID PROJET = UUID.randomUUID();

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
        liveTurns = new LiveTurnRegistry(new ObjectMapper());
        when(currentUser.requireId()).thenReturn(ALICE);
        when(access.hasAccess()).thenReturn(true);
    }

    @Test
    void seRebrancherRejoueCeQuiAEteManquePuisPasseAuDirect() {
        LiveTurn turn = liveTurns.open(ALICE, PROJET);
        turn.publish("text", new Payload("un"));
        turn.publish("text", new Payload("deux"));
        long vu = turn.cursor();
        turn.publish("text", new Payload("trois"));

        RecordingEmitter ecran = new RecordingEmitter();
        controller(ecran, RelayTurnSource.disabled()).attach(PROJET, vu);

        assertThat(ecran.names()).as("l'aparté de branchement, puis le seul événement manqué")
                .containsExactly("attached", "text");
        assertThat(ecran.payloads().get(1)).contains("trois");

        turn.publish("text", new Payload("quatre"));
        assertThat(ecran.names()).as("puis le direct")
                .containsExactly("attached", "text", "text");
        assertThat(ecran.payloads().get(2)).contains("quatre");
    }

    @Test
    void deuxVuesSurLeMemeTourRecoiventLaMemeSuite() {
        LiveTurn turn = liveTurns.open(ALICE, PROJET);
        RecordingEmitter premiere = new RecordingEmitter();
        RecordingEmitter seconde = new RecordingEmitter();
        controller(premiere, RelayTurnSource.disabled()).attach(PROJET, 0L);
        controller(seconde, RelayTurnSource.disabled()).attach(PROJET, 0L);

        turn.publish("text", new Payload("un"));
        turn.publish("action", new Payload("deux"));

        assertThat(premiere.names()).containsExactly("attached", "text", "action");
        assertThat(premiere.names()).isEqualTo(seconde.names());
        assertThat(premiere.payloads().subList(1, 3)).isEqualTo(seconde.payloads().subList(1, 3));
    }

    @Test
    void sansTourVivantLeFluxDitIdleEtSeClot() {
        RecordingEmitter ecran = new RecordingEmitter();

        controller(ecran, RelayTurnSource.disabled()).attach(PROJET, 0L);

        assertThat(ecran.names()).containsExactly("idle");
        assertThat(ecran.payloads().get(0)).contains("\"live\":false");
        assertThat(ecran.completed).isTrue();
    }

    @Test
    void onNeSeRebrancheJamaisSurLeTourDautrui() {
        liveTurns.open(BOB, PROJET).publish("text", new Payload("secret de Bob"));
        RecordingEmitter ecran = new RecordingEmitter();

        controller(ecran, RelayTurnSource.disabled()).attach(PROJET, 0L);

        assertThat(ecran.names()).as("le tour d'un autre est INTROUVABLE, pas « refusé »")
                .containsExactly("idle");
        assertThat(String.join("", ecran.payloads())).doesNotContain("secret de Bob");
    }

    @Test
    void unEcranSansAccesRecoitSonRefusDansLeFlux() {
        when(access.hasAccess()).thenReturn(false);
        liveTurns.open(ALICE, PROJET);
        RecordingEmitter ecran = new RecordingEmitter();

        controller(ecran, RelayTurnSource.disabled()).attach(PROJET, 0L);

        assertThat(ecran.names()).containsExactly("error");
        assertThat(ecran.payloads().get(0)).contains("forbidden");
    }

    @Test
    void lEtatDuTourDitSilEstVivantEtSonCurseur() {
        assertThat(controller(new RecordingEmitter(), RelayTurnSource.disabled())
                .turnState(PROJET).live()).isFalse();

        LiveTurn turn = liveTurns.open(ALICE, PROJET);
        turn.publish("text", new Payload("un"));

        AtelierTurnStateResponse state = controller(new RecordingEmitter(),
                RelayTurnSource.disabled()).turnState(PROJET);
        assertThat(state.live()).isTrue();
        assertThat(state.turnId()).isEqualTo(turn.turnId());
        assertThat(state.cursor()).isEqualTo(1L);
    }

    @Test
    void unTourDetenuParUnPairEstVivantEtSonFluxEstRelaye() {
        RemoteTurnSource pair = new FakeRemoteTurn(UUID.randomUUID());
        RecordingEmitter ecran = new RecordingEmitter();

        AtelierTurnStateResponse state = controller(ecran, pair).turnState(PROJET);
        assertThat(state.live()).as("un tour qui tourne ailleurs est un tour qui tourne").isTrue();

        controller(ecran, pair).attach(PROJET, 0L);
        assertThat(ecran.names()).containsExactly("attached", "text");
        assertThat(ecran.payloads().get(1)).contains("relayé");
    }

    @Test
    void sansRelaisPossibleOnDegradeVersIdle() {
        RecordingEmitter ecran = new RecordingEmitter();

        // `RelayTurnSource.disabled()` est exactement le pod mono-instance : aucun pair, aucun appel.
        controller(ecran, RelayTurnSource.disabled()).attach(PROJET, 0L);

        assertThat(ecran.names()).containsExactly("idle");
    }

    // ------------------------------------------------------------------ montage

    private AtelierChatController controller(SseEmitter emitter, RemoteTurnSource remote) {
        return new AtelierChatController(chatService, threadService, currentUser, access,
                Runnable::run, Runnable::run, liveTurns, remote) {
            @Override
            SseEmitter newEmitter() {
                return emitter;
            }
        };
    }

    private record Payload(String text) {
    }

    /** Un pair qui détient le tour et en relaie un événement. */
    private record FakeRemoteTurn(UUID turnId) implements RemoteTurnSource {

        @Override
        public Optional<RemoteTurnState> findRemoteTurn(UUID userId, UUID workspaceId) {
            return Optional.of(new RemoteTurnState(turnId, 7L, 1_000L));
        }

        @Override
        public boolean streamRemoteTurn(UUID userId, UUID workspaceId, long cursor,
                TurnSubscriber subscriber) {
            subscriber.deliver(new fr.claudegateway.atelier.live.TurnEvent(8L, "text",
                    "{\"text\":\"relayé\"}"));
            return true;
        }
    }

    /** Un émetteur qui note ce qui part, au lieu d'écrire sur une connexion. */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> raw = new ArrayList<>();
        private boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
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
            return raw.stream().map(RecordingEmitter::name).toList();
        }

        List<String> payloads() {
            return raw.stream().map(RecordingEmitter::payload).toList();
        }

        /** Le nom de l'événement, tel que le protocole SSE l'écrit : {@code event:<nom>\n}. */
        private static String name(String line) {
            return between(line, "event:");
        }

        /** La charge utile, qui part en octets UTF-8 depuis F-84. */
        private static String payload(String line) {
            return between(line, "data:");
        }

        /** Ce qui suit un préfixe jusqu'au saut de ligne — le découpage du protocole SSE. */
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
