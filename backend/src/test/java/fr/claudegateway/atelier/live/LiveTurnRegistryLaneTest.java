package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** F-221 / SF-221-01 — un tour vivant par fil : le commun et chaque chantier ont le leur. */
class LiveTurnRegistryLaneTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID SUJET = UUID.randomUUID();

    private final LiveTurnRegistry registry = new LiveTurnRegistry(new ObjectMapper());

    @Test
    @DisplayName("deux chantiers du même sujet ont chacun leur tour vivant, en même temps")
    void twoLanesLiveTogether() {
        LiveTurn sept = registry.open(ALICE, SUJET, 7);
        LiveTurn huit = registry.open(ALICE, SUJET, 8);

        assertThat(sept.live()).as("ouvrir le fil 8 ne ferme pas le fil 7").isTrue();
        assertThat(huit.live()).isTrue();
        assertThat(registry.find(ALICE, SUJET, 7)).contains(sept);
        assertThat(registry.find(ALICE, SUJET, 8)).contains(huit);
        assertThat(registry.find(ALICE, SUJET)).as("le fil commun est libre").isEmpty();
        assertThat(sept.chantier()).isEqualTo(7);
        assertThat(sept.summary().chantier()).isEqualTo(7);
        assertThat(registry.liveOf(ALICE, SUJET)).containsExactlyInAnyOrder(sept, huit);
        assertThat(registry.anyLive(ALICE, SUJET)).isTrue();
    }

    @Test
    @DisplayName("un envoi au fil 8 pendant la course du fil 7 ouvre un tour — jamais une précision du 7")
    void sendOnAnotherLaneNeverSteers() {
        LiveTurn sept = registry.openIfIdle(ALICE, SUJET, 7, "course du chantier 7").orElseThrow();

        LiveTurnRegistry.Entry entry = registry.openOrSteer(ALICE, SUJET, 8, "planifie le chantier 8");

        assertThat(entry.receipt()).as("aucune précision").isNull();
        assertThat(entry.turn()).isNotSameAs(sept);
        assertThat(entry.turn().chantier()).isEqualTo(8);
        assertThat(sept.takeSteers()).as("la course du 7 n'a rien reçu").isEmpty();

        LiveTurnRegistry.Entry same = registry.openOrSteer(ALICE, SUJET, 7, "précise le 7");
        assertThat(same.steered()).as("dans SON fil, l'envoi reste une précision").isTrue();
        assertThat(same.turn()).isSameAs(sept);
    }

    @Test
    @DisplayName("fermer un fil ne touche pas l'autre ; le tour d'autrui reste introuvable")
    void closeAndIsolation() {
        LiveTurn sept = registry.open(ALICE, SUJET, 7);
        LiveTurn huit = registry.open(ALICE, SUJET, 8);

        registry.close(sept);

        assertThat(registry.find(ALICE, SUJET, 7)).isEmpty();
        assertThat(registry.find(ALICE, SUJET, 8)).contains(huit);
        assertThat(registry.find(BOB, SUJET, 8)).isEmpty();
        assertThat(registry.anyLive(BOB, SUJET)).isFalse();
        assertThat(registry.openIfIdle(ALICE, SUJET, 8, "x")).as("le fil 8 est pris").isEmpty();
        assertThat(registry.openIfIdle(ALICE, SUJET, 7, "x")).as("le fil 7 est libre").isPresent();
    }
}
