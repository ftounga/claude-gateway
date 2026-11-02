package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-237 / SF-237-15 — la garde « preuve sans oui ». */
class JarvisProofGuardTest {

    private static final String CONTEXT = """
            Maintenant : mardi 3 novembre 2026, 10:15
            Sujet courant : Edenred-Portail
            Chantier courant : 3

            ## Pièces
            chantier 5 : besoin initial, 1 entrée, 0 preuve
            chantier 3 : besoin initial, 2 entrées, 2 preuves, 1 page, 2 vidéos, 1 architecture
            sujet (sans chantier) : 0 entrée, 1 preuve, 1 image""";

    @Test
    void doubtsAreGuarded() {
        assertThat(JarvisProofGuard.guards("Tu es sûr que le chantier 5 est fini ?")).isTrue();
        assertThat(JarvisProofGuard.guards("Le cache Redis du chantier 5, tu as vérifié ?")).isTrue();
        assertThat(JarvisProofGuard.guards("T'as vérifié le chantier 3 ?")).isTrue();
        assertThat(JarvisProofGuard.guards("Prouve-moi que le portail répond.")).isTrue();
        assertThat(JarvisProofGuard.guards("C'est vérifié ?")).isTrue();
    }

    @Test
    void consentsOrdersAndOtherWordsAreNotGuarded() {
        assertThat(JarvisProofGuard.guards("Oui, vas-y.")).isFalse();
        assertThat(JarvisProofGuard.guards("Non, pas maintenant.")).isFalse();
        assertThat(JarvisProofGuard.guards("Tu es sûr ? Génère la preuve.")).isFalse();
        assertThat(JarvisProofGuard.guards("Tu es sûr ? Vérifie.")).isFalse();
        assertThat(JarvisProofGuard.guards("Tu as vérifié ? Alors teste-le.")).isFalse();
        assertThat(JarvisProofGuard.guards("Montre-moi les preuves du chantier 3.")).isFalse();
        assertThat(JarvisProofGuard.guards("Envoie la preuve à Paul.")).isFalse();
        assertThat(JarvisProofGuard.guards("Où en est le chantier 3 ?")).isFalse();
        assertThat(JarvisProofGuard.guards("")).isFalse();
    }

    @Test
    void theChantierIsNamedOrCurrent() {
        assertThat(JarvisProofGuard.chantier("Tu es sûr pour le chantier 5 ?", CONTEXT, null)).isEqualTo(5);
        assertThat(JarvisProofGuard.chantier("Tu es sûr ?", CONTEXT, 9)).isEqualTo(3);
        assertThat(JarvisProofGuard.chantier("Tu es sûr ?", "Maintenant : mardi", 9)).isEqualTo(9);
        assertThat(JarvisProofGuard.chantier("Tu es sûr ?", "", null)).isNull();
    }

    @Test
    void proofsAreReadInThePiecesBlock() {
        assertThat(JarvisProofGuard.proofs(CONTEXT, 5)).isZero();
        assertThat(JarvisProofGuard.proofs(CONTEXT, 3)).isEqualTo(2);
        assertThat(JarvisProofGuard.proofs(CONTEXT, 7)).isZero(); // bloc présent, aucune pièce pour ce chantier
        assertThat(JarvisProofGuard.proofs("Maintenant : mardi", 3)).isNull(); // bloc absent : inconnu
        assertThat(JarvisProofGuard.proofs(CONTEXT, null)).isNull();
        // Un bloc suivant (« ## Écran ») n'est pas lu comme des pièces.
        assertThat(JarvisProofGuard.proofs("## Pièces\nchantier 3 : 1 preuve\n\n## Écran\nchantier 4 : 9 preuves", 4))
                .isZero();
    }

    @Test
    void beforeTheCallOnlyAChantierWithoutProofIsAnsweredAtOnce() {
        JarvisProofGuard.Decision none = JarvisProofGuard.before("Tu es sûr que le chantier 5 est fini ?", CONTEXT,
                null);
        assertThat(none).isNotNull();
        assertThat(none.said()).isEqualTo(JarvisProofGuard.propose(5));
        assertThat(none.show()).isNull();
        assertThat(JarvisProofGuard.before("Tu es sûr que le chantier 3 est fini ?", CONTEXT, null)).isNull();
        assertThat(JarvisProofGuard.before("Tu es sûr ?", "Maintenant : mardi", 3)).isNull();
        assertThat(JarvisProofGuard.before("Où en est le chantier 5 ?", CONTEXT, null)).isNull();
    }

    @Test
    void afterADelegationTheProofsAreShownOrProposed() {
        JarvisProofGuard.Decision shown = JarvisProofGuard.instead("Prouve-moi que le portail répond.", CONTEXT, null,
                null);
        assertThat(shown.said()).isEqualTo("Voici les preuves du chantier 3.");
        assertThat(shown.show().action()).isEqualTo("galerie");
        assertThat(shown.show().reason()).isEqualTo("appui");
        assertThat(shown.show().scope().chantier()).isEqualTo(3);
        assertThat(shown.show().scope().category()).isEqualTo("preuves");

        assertThat(JarvisProofGuard.instead("Tu as vérifié le chantier 5 ?", CONTEXT, null, null).said())
                .isEqualTo(JarvisProofGuard.propose(5));
        assertThat(JarvisProofGuard.instead("Tu es sûr ?", "", null, null).said())
                .isEqualTo(JarvisProofGuard.PROPOSE_CHECK);
        assertThat(JarvisProofGuard.instead("Oui, vas-y.", CONTEXT, null, null)).isNull();
    }
}
