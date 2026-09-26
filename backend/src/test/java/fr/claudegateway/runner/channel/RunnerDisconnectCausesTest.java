package fr.claudegateway.runner.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.rupture.RunnerDisconnectCause;
import fr.claudegateway.runner.rupture.RunnerDisconnectJournal;
import fr.claudegateway.runner.rupture.RunnerTransport;

/**
 * <b>Chaque cause est bien celle qu'on croit</b> (F-161 / SF-161-03).
 *
 * <p><b>Ce test vit dans le paquet des canaux</b>, et non dans celui du journal : le balayage
 * d'inactivité n'est délibérément pas public, et élargir sa visibilité pour la commodité d'un test
 * exposerait à tout le code un déclencheur qui ne regarde que l'ordonnanceur.</p>
 *
 * <p>Tout l'intérêt du journal tient à cette distinction. Si un canal <b>remplacé</b> — un poste
 * qui <i>revient</i> — était compté comme une coupure, le total serait gonflé de toutes les
 * reconnexions et l'enquête partirait dans le mur. Un journal qui se trompe de cause est pire
 * qu'un journal absent : il donne l'assurance sans le savoir.</p>
 */
class RunnerDisconnectCausesTest {

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();
    private final RunnerIdentity identity = new RunnerIdentity(tokenId, userId, workspaceId);

    private RunnerPollingSessions sessions;
    private RecordingJournal journal;

    /** Un journal qui retient au lieu d'écrire — ce qu'on vérifie est la CAUSE, pas la persistance. */
    private static final class RecordingJournal extends RunnerDisconnectJournal {

        private final List<RunnerDisconnectCause> causes = new ArrayList<>();
        private final List<RunnerTransport> transports = new ArrayList<>();
        private final List<Integer> inFlight = new ArrayList<>();

        RecordingJournal() {
            super(null, true);
        }

        @Override
        public void record(UUID userId, UUID hostId, RunnerDisconnectCause cause,
                           RunnerTransport transport, java.time.Instant connectedAt,
                           java.time.Instant lastSeenAt, String closeStatus, int callsInFlight) {
            causes.add(cause);
            transports.add(transport);
            inFlight.add(callsInFlight);
        }
    }

    @BeforeEach
    void setUp() {
        InMemoryRunnerRegistry registry = new InMemoryRunnerRegistry();
        RunnerCallDispatcher dispatcher = new RunnerCallDispatcher(registry, new ObjectMapper(),
                (id, shell) -> { }, (id, version) -> { },
                new fr.claudegateway.runner.ServedRunnerVersion("", ""),
                fr.claudegateway.runner.RunnerLivenessStubs.alwaysAlive(), 100L);
        sessions = new RunnerPollingSessions(registry, dispatcher, 60_000L);
        journal = new RecordingJournal();
        sessions.setJournal(journal);
    }

    @Test
    @DisplayName("POST /runner/disconnect → ARRET_PROPRE : le cas sain, à ne pas compter comme subi")
    void aCleanShutdownIsNamedAsSuch() {
        sessions.open(identity);

        assertThat(sessions.close(identity)).isTrue();

        assertThat(journal.causes).containsExactly(RunnerDisconnectCause.ARRET_PROPRE);
        assertThat(journal.transports).containsExactly(RunnerTransport.POLLING);
    }

    @Test
    @DisplayName("une reconnexion → REMPLACE : le poste REVIENT, il ne part pas")
    void areconnectionIsNotABreak() {
        sessions.open(identity);

        // Même poste, jeton différent : le canal précédent est remplacé.
        sessions.open(new RunnerIdentity(UUID.randomUUID(), userId, workspaceId));

        assertThat(journal.causes)
                .as("compter ceci comme une coupure gonflerait le total de toutes les reconnexions")
                .containsExactly(RunnerDisconnectCause.REMPLACE);
    }

    @Test
    @DisplayName("canal plus interrogé → INACTIVITE")
    void anIdleChannelIsNamedAsSuch() throws Exception {
        InMemoryRunnerRegistry registry = new InMemoryRunnerRegistry();
        RunnerCallDispatcher dispatcher = new RunnerCallDispatcher(registry, new ObjectMapper(),
                (id, shell) -> { }, (id, version) -> { },
                new fr.claudegateway.runner.ServedRunnerVersion("", ""),
                fr.claudegateway.runner.RunnerLivenessStubs.alwaysAlive(), 100L);
        // Tolérance nulle : le canal est périmé dès l'instant suivant son ouverture.
        RunnerPollingSessions impatient = new RunnerPollingSessions(registry, dispatcher, 1L);
        impatient.setJournal(journal);
        impatient.open(identity);
        Thread.sleep(5);

        impatient.sweepIdleChannels();

        assertThat(journal.causes).containsExactly(RunnerDisconnectCause.INACTIVITE);
    }

    @Test
    @DisplayName("sans journal branché, tout se ferme EXACTEMENT comme avant")
    void withoutTheJournalNothingChanges() {
        InMemoryRunnerRegistry registry = new InMemoryRunnerRegistry();
        RunnerCallDispatcher dispatcher = new RunnerCallDispatcher(registry, new ObjectMapper(),
                (id, shell) -> { }, (id, version) -> { },
                new fr.claudegateway.runner.ServedRunnerVersion("", ""),
                fr.claudegateway.runner.RunnerLivenessStubs.alwaysAlive(), 100L);
        RunnerPollingSessions bare = new RunnerPollingSessions(registry, dispatcher, 60_000L);

        bare.open(identity);

        assertThat(bare.close(identity)).isTrue();
        assertThat(journal.causes).isEmpty();
    }

    @Test
    @DisplayName("les appels en vol sont comptés AVANT la fermeture — après, il n'y aurait rien")
    void inFlightCallsAreCountedBeforeTheClose() {
        sessions.open(identity);

        sessions.close(identity);

        // Aucun appel ici, mais le chiffre est bien relevé : c'est le point de mesure qui compte.
        assertThat(journal.inFlight).containsExactly(0);
    }
}
