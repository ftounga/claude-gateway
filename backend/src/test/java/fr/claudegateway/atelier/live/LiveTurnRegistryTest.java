package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Le registre des tours vivants d'un pod (F-84 / SF-84-01) : isolation et unicité. */
class LiveTurnRegistryTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID PROJET = UUID.randomUUID();

    private final LiveTurnRegistry registry = new LiveTurnRegistry(new ObjectMapper());

    @Test
    void leTourDunAutreUtilisateurNestJamaisTrouve() {
        registry.open(ALICE, PROJET);

        assertThat(registry.find(BOB, PROJET))
                .as("on ne se rebranche JAMAIS sur le tour d'autrui")
                .isEmpty();
        assertThat(registry.find(ALICE, PROJET)).isPresent();
    }

    @Test
    void ouvrirUnNouveauTourFermeLePrecedent() {
        LiveTurn premier = registry.open(ALICE, PROJET);
        LiveTurn second = registry.open(ALICE, PROJET);

        assertThat(premier.live()).isFalse();
        assertThat(second.live()).isTrue();
        assertThat(registry.find(ALICE, PROJET)).contains(second);
    }

    @Test
    void clore_un_tour_perime_ne_touche_pas_celui_qui_la_remplace() {
        LiveTurn premier = registry.open(ALICE, PROJET);
        LiveTurn second = registry.open(ALICE, PROJET);

        registry.close(premier);

        assertThat(registry.find(ALICE, PROJET))
                .as("on ne ferme jamais le tour d'un autre message")
                .contains(second);
    }

    @Test
    void unTourTermineNestPlusVivant() {
        LiveTurn turn = registry.open(ALICE, PROJET);

        registry.close(turn);

        assertThat(registry.find(ALICE, PROJET)).isEmpty();
    }

    // ─── Le compte des tours à drainer (F-84 / SF-84-08) ─────────────────────────────────────
    // C'est ce compte que l'arrêt du pod consulte pour dire ce qu'il risque d'emporter. Un compte
    // faux serait pire que pas de compte : il ferait croire à un drainage inutile, ou à une perte
    // qui n'a pas eu lieu.

    @Test
    void unRegistreVideNaRienADrainer() {
        assertThat(registry.liveCount()).isZero();
    }

    @Test
    void leCompteCouvreTousLesUtilisateursDuPod() {
        registry.open(ALICE, PROJET);
        registry.open(BOB, PROJET);
        registry.open(BOB, UUID.randomUUID());

        assertThat(registry.liveCount())
                .as("l'arrêt du pod draine le travail de TOUS ses utilisateurs")
                .isEqualTo(3);
    }

    @Test
    void unTourFermeNestPlusCompte() {
        LiveTurn turn = registry.open(ALICE, PROJET);
        registry.open(BOB, PROJET);

        registry.close(turn);

        assertThat(registry.liveCount())
                .as("retarder chaque arrêt pour un tour déjà fini serait payer sans rien sauver")
                .isEqualTo(1);
    }

    @Test
    void remplacerLeTourDunProjetNeDoublePasLeCompte() {
        registry.open(ALICE, PROJET);
        registry.open(ALICE, PROJET);

        assertThat(registry.liveCount())
                .as("un projet n'exécute qu'un tour à la fois : le précédent est fermé, pas ajouté")
                .isEqualTo(1);
    }
}
