package fr.claudegateway.governance.juge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-94 / SF-94-01 — <b>le bloc de verdict</b>.
 *
 * <p>Ce que ces tests protègent, et c'est la substance de la feature :</p>
 * <ul>
 *   <li><b>seul ce qui suit le marqueur est lu</b> — le prompt d'origine note que l'inverse a coûté
 *       un bug : un juge bavard concluant « aucun » dans son raisonnement était pris pour une
 *       alerte ;</li>
 *   <li><b>le repli alerte</b> — marqueur absent, bloc vide, bloc incompréhensible : illisible,
 *       jamais « rien à signaler ». Le filet doit échouer bruyamment ;</li>
 *   <li>la <b>forme</b> est tolérée, le <b>fond</b> ne l'est pas.</li>
 * </ul>
 */
class JugeVerdictTest {

    @Test
    @DisplayName("AUCUN dans le bloc : lisible, et rien à signaler")
    void aucunDansLeBloc() {
        JugeVerdict verdict = JugeVerdict.parse("""
                J'ai comparé la carte et les notes. Tout ce que je vois y figure déjà.

                ===VERDICT===
                AUCUN
                """);

        assertThat(verdict.lisible()).isTrue();
        assertThat(verdict.rienASignaler()).isTrue();
        assertThat(verdict.elements()).isEmpty();
    }

    @Test
    @DisplayName("LE BUG DU PROMPT : « AUCUN » hors du bloc n'est pas un silence")
    void aucunHorsDuBlocEstIllisible() {
        JugeVerdict verdict = JugeVerdict.parse("""
                Après analyse, aucun élément cité dans les notes ne manque à la carte.
                Rien à signaler, donc : AUCUN.
                """);

        assertThat(verdict.lisible()).isFalse();
        assertThat(verdict.rienASignaler()).isFalse();
    }

    @Test
    @DisplayName("Un élément avec sa source")
    void unElementAvecSaSource() {
        JugeVerdict verdict = JugeVerdict.parse("""
                ===VERDICT===
                - bastion « bst-01 » — cité dans migration-dns/STATE.md
                """);

        assertThat(verdict.lisible()).isTrue();
        assertThat(verdict.elements()).hasSize(1);
        assertThat(verdict.elements().get(0).element()).isEqualTo("bastion « bst-01 »");
        assertThat(verdict.elements().get(0).source()).isEqualTo("migration-dns/STATE.md");
        assertThat(verdict.cited()).contains("bastion « bst-01 » — cité dans migration-dns/STATE.md");
    }

    @Test
    @DisplayName("Les trois séparateurs et les deux puces sont acceptés")
    void formesTolerees() {
        JugeVerdict verdict = JugeVerdict.parse("""
                ===VERDICT===
                - serveur alpha — cité dans a/STATE.md
                * VPN client -- cité dans b/STATE.md
                - plage 10.0.4.0/24 - dans c/PLAN-ACTION.md
                """);

        assertThat(verdict.elements()).extracting(JugeVerdict.Element::element)
                .containsExactly("serveur alpha", "VPN client", "plage 10.0.4.0/24");
        assertThat(verdict.elements()).extracting(JugeVerdict.Element::source)
                .containsExactly("a/STATE.md", "b/STATE.md", "c/PLAN-ACTION.md");
    }

    @Test
    @DisplayName("Marqueur absent : illisible")
    void marqueurAbsent() {
        assertThat(JugeVerdict.parse("une réponse sans verdict").lisible()).isFalse();
        assertThat(JugeVerdict.parse("").lisible()).isFalse();
        assertThat(JugeVerdict.parse(null).lisible()).isFalse();
    }

    @Test
    @DisplayName("Bloc vide : illisible — un blanc n'est pas un silence")
    void blocVide() {
        assertThat(JugeVerdict.parse("raisonnement…\n===VERDICT===\n").lisible()).isFalse();
        assertThat(JugeVerdict.parse("raisonnement…\n===VERDICT===\n```\n```\n").lisible()).isFalse();
    }

    @Test
    @DisplayName("Une phrase libre dans le bloc n'est pas un élément — et on alerte")
    void phraseLibreDansLeBloc() {
        JugeVerdict verdict = JugeVerdict.parse("""
                ===VERDICT===
                Je pense qu'il manque peut-être quelque chose mais je n'en suis pas sûr.
                """);

        assertThat(verdict.lisible()).isFalse();
        assertThat(verdict.elements()).isEmpty();
    }

    @Test
    @DisplayName("Deux marqueurs : le dernier fait foi")
    void leDernierFaitFoi() {
        JugeVerdict verdict = JugeVerdict.parse("""
                La consigne demande un bloc ===VERDICT=== suivi de AUCUN ou d'une liste.
                Voici le mien.

                ===VERDICT===
                - cluster atlas — cité dans p/STATE.md
                """);

        assertThat(verdict.lisible()).isTrue();
        assertThat(verdict.elements()).hasSize(1);
        assertThat(verdict.elements().get(0).element()).isEqualTo("cluster atlas");
    }

    @Test
    @DisplayName("Une liste l'emporte sur un « AUCUN » contradictoire : le filet signale")
    void listeEtAucun() {
        JugeVerdict verdict = JugeVerdict.parse("""
                ===VERDICT===
                - serveur alpha — cité dans a/STATE.md
                AUCUN autre élément
                """);

        assertThat(verdict.lisible()).isTrue();
        assertThat(verdict.elements()).hasSize(1);
    }

    @Test
    @DisplayName("Bornes : nombre d'éléments, longueurs, doublons")
    void bornes() {
        StringBuilder reponse = new StringBuilder("===VERDICT===\n");
        for (int i = 0; i < JugeVerdict.MAX_ELEMENTS + 10; i++) {
            reponse.append("- element-").append(i).append(" — cité dans p/STATE.md\n");
        }
        assertThat(JugeVerdict.parse(reponse.toString()).elements())
                .hasSize(JugeVerdict.MAX_ELEMENTS);

        String long_ = "x".repeat(500);
        JugeVerdict trop = JugeVerdict.parse(
                "===VERDICT===\n- " + long_ + " — cité dans " + long_ + ".md\n");
        assertThat(trop.elements().get(0).element()).hasSize(JugeVerdict.MAX_ELEMENT_CHARS);
        assertThat(trop.elements().get(0).source()).hasSize(JugeVerdict.MAX_SOURCE_CHARS);

        JugeVerdict doublons = JugeVerdict.parse("""
                ===VERDICT===
                - Serveur Alpha — cité dans a/STATE.md
                - serveur alpha — cité dans b/STATE.md
                """);
        assertThat(doublons.elements()).hasSize(1);
    }

    @Test
    @DisplayName("Un élément sans source reste une piste à vérifier")
    void elementSansSource() {
        JugeVerdict verdict = JugeVerdict.parse("===VERDICT===\n- serveur alpha\n");

        assertThat(verdict.elements()).hasSize(1);
        assertThat(verdict.elements().get(0).source()).isEmpty();
        assertThat(verdict.elements().get(0).cited()).isEqualTo("serveur alpha");
    }
}
