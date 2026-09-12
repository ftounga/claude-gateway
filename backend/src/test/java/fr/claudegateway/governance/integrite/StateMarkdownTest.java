package fr.claudegateway.governance.integrite;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.governance.integrite.StateMarkdown.Lecture;
import fr.claudegateway.governance.integrite.StateMarkdown.Statut;

/**
 * F-95 / SF-95-01 — le statut d'un sujet et sa dette, lus dans son {@code STATE.md}.
 *
 * <p>Deux pièges, et ce sont eux que ces tests gardent : le <b>gabarit livré</b> porte une case
 * d'exemple dans un bloc de code — la compter ferait déclarer une dette à un projet qui n'a rien
 * écrit —, et un {@code STATE.md} <b>sans section Statut</b> doit être lu « en cours », sans quoi la
 * première inspection bloquerait tous les postes déjà gouvernés.</p>
 */
class StateMarkdownTest {

    @Test
    @DisplayName("le gabarit livré ne déclare ni clôture ni dette")
    void theShippedTemplateOwesNothing() throws IOException {
        Lecture lecture = StateMarkdown.of(ressource("governance/savoir-durable/STATE.md"));

        assertThat(lecture.statut()).isEqualTo(Statut.EN_COURS);
        assertThat(lecture.casesOuvertes()).isZero();
    }

    @Test
    @DisplayName("la section Statut fait foi, sous ses quatre formes de clôture")
    void theStatusSectionIsAuthoritative() {
        assertThat(StateMarkdown.of("## Statut\n\n`clos`\n").statut()).isEqualTo(Statut.CLOS);
        assertThat(StateMarkdown.of("## Statut\n\nclôturé\n").statut()).isEqualTo(Statut.CLOS);
        assertThat(StateMarkdown.of("## Statut\n\n**Terminé**\n").statut()).isEqualTo(Statut.CLOS);
        assertThat(StateMarkdown.of("## Statut\n\nfini le 2026-09-13\n").statut())
                .isEqualTo(Statut.CLOS);
        assertThat(StateMarkdown.of("## Statut\n\n`en cours`\n").statut())
                .isEqualTo(Statut.EN_COURS);
    }

    @Test
    @DisplayName("une ligne « statut : clos » vaut déclaration, où qu'elle soit")
    void anInlineStatusLineCounts() {
        assertThat(StateMarkdown.of("# Sujet\n\n**Statut** : clos\n").statut())
                .isEqualTo(Statut.CLOS);
        assertThat(StateMarkdown.of("# Sujet\n\n- statut: CLOS\n").statut()).isEqualTo(Statut.CLOS);
    }

    @Test
    @DisplayName("une phrase qui contient « terminé » ne clôt rien")
    void aSentenceIsNotADeclaration() {
        assertThat(StateMarkdown.of("# Sujet\n\nLe transfert est terminé côté client.\n").statut())
                .isEqualTo(Statut.EN_COURS);
    }

    @Test
    @DisplayName("sans section Statut, le sujet est en cours")
    void theDefaultIsOpen() {
        assertThat(StateMarkdown.of("# Sujet\n\n## Promotions\n\n- [ ] bastion\n").statut())
                .isEqualTo(Statut.EN_COURS);
    }

    @Test
    @DisplayName("les cases ouvertes se comptent, les cochées non")
    void openBoxesAreCountedAndTickedOnesAreNot() {
        Lecture lecture = StateMarkdown.of("""
                ## Promotions

                - [ ] cluster « atlas »
                - [x] VPN client -> promu dans acces.md
                - [ ] bastion bst-01
                * [ ] plage 10.0.4.0/24
                - un élément sans case
                """);

        assertThat(lecture.casesOuvertes()).isEqualTo(3);
        assertThat(lecture.aDeLaDette()).isTrue();
    }

    @Test
    @DisplayName("les cases d'un bloc de code ou d'une citation ne comptent pas")
    void examplesAndQuotesDoNotCount() {
        Lecture lecture = StateMarkdown.of("""
                ## Promotions

                > - [ ] exemple de consigne

                ```
                - [ ] cluster « atlas » (10.0.4.0/24)
                ```

                - [ ] le seul vrai
                """);

        assertThat(lecture.casesOuvertes()).isEqualTo(1);
    }

    @Test
    @DisplayName("un bloc de code non fermé ne fabrique pas de dette")
    void anUnclosedFenceNeverInventsDebt() {
        Lecture lecture = StateMarkdown.of("""
                ## Promotions

                ```
                - [ ] cluster « atlas »
                - [ ] bastion
                """);

        assertThat(lecture.casesOuvertes()).isZero();
    }

    @Test
    @DisplayName("un contenu nul ou vide ne lève pas et ne doit rien")
    void nullContentIsNeutral() {
        assertThat(StateMarkdown.of(null)).isEqualTo(StateMarkdown.NEUTRE);
        assertThat(StateMarkdown.of("   ")).isEqualTo(StateMarkdown.NEUTRE);
    }

    private static String ressource(String chemin) throws IOException {
        try (InputStream stream =
                StateMarkdownTest.class.getClassLoader().getResourceAsStream(chemin)) {
            assertThat(stream).as("ressource « %s »", chemin).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
