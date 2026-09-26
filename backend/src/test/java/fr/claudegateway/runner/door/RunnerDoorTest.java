package fr.claudegateway.runner.door;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.RunnerLiveness;

/**
 * La porte d'entrée du runner (F-161 / SF-161-01).
 *
 * <p>Ce que ces tests tiennent : un poste mort ou sans {@code bash} ferme la porte <b>avec un
 * message qui dit quoi faire</b>, un projet hébergé n'est jamais concerné, et — le plus important —
 * <b>une ignorance ne ferme rien</b> : une porte qui bloque faute de savoir est pire que pas de
 * porte.</p>
 */
class RunnerDoorTest {

    private final RunnerLiveness liveness = mock(RunnerLiveness.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private RunnerDoor door;

    @BeforeEach
    void setUp() {
        door = new RunnerDoor(liveness);
    }

    private void alive(boolean value) {
        when(liveness.isAlive(userId, hostId)).thenReturn(value);
    }

    @Test
    @DisplayName("poste vivant et capacités complètes : la porte s'ouvre, rien ne change")
    void aliveAndCapableOpens() {
        alive(true);

        RunnerDoorVerdict verdict =
                door.check(userId, hostId, "CAGIP", Set.of("files","bash","teams"), Set.of("bash", "files"));

        assertThat(verdict.open()).isTrue();
        assertThat(verdict.reason()).isNull();
    }

    @Test
    @DisplayName("poste hors ligne : refus NOMMÉ, qui dit quoi faire et que rien n'a été dépensé")
    void offlineCloses() {
        alive(false);

        RunnerDoorVerdict verdict =
                door.check(userId, hostId, "CAGIP", Set.of("files","bash"), Set.of("bash"));

        assertThat(verdict.open()).isFalse();
        assertThat(verdict.code()).isEqualTo(RunnerDoorVerdict.OFFLINE);
        assertThat(verdict.reason())
                .contains("CAGIP")
                .contains("Relance-le")
                .contains("rien n'a été dépensé");
    }

    @Test
    @DisplayName("runner SANS bash : refus nommé — c'est le cas --no-bash, 6 échecs sur la session mesurée")
    void missingBashCloses() {
        alive(true);

        RunnerDoorVerdict verdict =
                door.check(userId, hostId, "CAGIP", Set.of("files","teams"), Set.of("bash", "files"));

        assertThat(verdict.open()).isFalse();
        assertThat(verdict.code()).isEqualTo(RunnerDoorVerdict.MISSING_CAPABILITY);
        assertThat(verdict.reason())
                .contains("SANS bash")
                .contains("--no-bash")
                .contains("rien n'a été dépensé");
    }

    @Test
    @DisplayName("une autre capacité manquante est nommée telle quelle")
    void anotherMissingCapabilityIsNamed() {
        alive(true);

        RunnerDoorVerdict verdict =
                door.check(userId, hostId, "CAGIP", Set.of("files","bash"), Set.of("teams"));

        assertThat(verdict.open()).isFalse();
        assertThat(verdict.reason()).contains("teams").contains("Relance-le à jour");
    }

    @Test
    @DisplayName("UNE IGNORANCE NE FERME RIEN : capacités jamais déclarées → la porte s'ouvre")
    void unknownCapabilitiesOpen() {
        alive(true);

        assertThat(door.check(userId, hostId, "CAGIP", null, Set.of("bash")).open()).isTrue();
        assertThat(door.check(userId, hostId, "CAGIP", Set.of(), Set.of("bash")).open()).isTrue();
        assertThat(door.check(userId, hostId, "CAGIP", Set.of("files", "bash"), Set.of()).open())
                .isTrue();
        assertThat(door.check(userId, hostId, "CAGIP", Set.of("files", "bash"), null).open())
                .isTrue();
    }

    @Test
    @DisplayName("projet HÉBERGÉ : aucune porte, et la vivacité n'est même pas lue")
    void hostedProjectsAreNeverChecked() {
        RunnerDoorVerdict verdict = door.check(userId, null, null, null, Set.of("bash"));

        assertThat(verdict.open()).isTrue();
        verify(liveness, never()).isAlive(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("un poste sans nom reste lisible dans le message")
    void anUnnamedHostStaysReadable() {
        alive(false);

        assertThat(door.check(userId, hostId, null, Set.of("files","bash"), Set.of("bash")).reason())
                .contains("ce poste");
    }

    @Test
    @DisplayName("l'ancienneté du dernier signe se dit en clair")
    void theAgeIsReadable() {
        OffsetDateTime now = OffsetDateTime.now();

        assertThat(door.sinceLabel(null)).isEqualTo("jamais vu");
        assertThat(door.sinceLabel(now.minusSeconds(20))).isEqualTo("il y a moins d'une minute");
        assertThat(door.sinceLabel(now.minusMinutes(7))).isEqualTo("il y a 7 min");
        assertThat(door.sinceLabel(now.minusHours(3))).isEqualTo("il y a 3 h");
    }

    @Test
    @DisplayName("ISOLATION — la vivacité est lue pour CE compte et CE poste")
    void livenessCarriesTheAccountAndHost() {
        alive(true);

        door.check(userId, hostId, "CAGIP", Set.of("files","bash"), Set.of("bash"));

        verify(liveness).isAlive(userId, hostId);
    }
}
