package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Rien de ce qui part chez un client ne nomme l'outil</b> (F-110 / SF-110-06).
 *
 * <p>Le produit porte déjà cette règle — <i>« rien de ce qui sort du projet ne doit suggérer qu'un
 * modèle l'a produit »</i> — et la fait respecter mécaniquement sur les messages de commit depuis
 * F-52. Elle n'avait jamais été appliquée aux courriels : le nom d'expéditeur annonçait l'outil à
 * chaque envoi, dans la boîte professionnelle d'un client.</p>
 *
 * <p><b>Le test qui compte est {@link #nothingThatLeavesNamesTheTool()}</b> : il balaie ce qui part
 * et échoue si le nom réapparaît. C'est lui qui empêche la régression — pas la vigilance de celui
 * qui relira.</p>
 */
class ClientMailNeverNamesTheToolTest {

    /** Les formes sous lesquelles le nom de l'outil pourrait revenir. */
    private static final List<String> FORBIDDEN =
            List.of("claude-gateway", "claude gateway", "claude portal", "claudegateway");

    private static void assertNeutral(String what, String text) {
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN) {
            assertThat(lowered)
                    .as("%s ne doit nommer aucun outil (« %s »)", what, forbidden)
                    .doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("LE CRITÈRE : rien de ce qui part chez un client ne nomme l'outil")
    void nothingThatLeavesNamesTheTool() {
        ClientMailRenderer.Rendered rendered =
                ClientMailRenderer.render("Le bastion sera migré jeudi.", "CAGIP");

        assertNeutral("le corps texte", rendered.text());
        assertNeutral("le corps HTML", rendered.html());
        assertNeutral("le nom d'expéditeur", new ClientMailIdentity("").senderName());
    }

    @Test
    @DisplayName("l'expéditeur est le nom choisi, et ne cite pas non plus le client")
    void thesenderIsTheChosenName() {
        // « X pour Y » est une tournure d'outil, pas de personne : le destinataire sait qui il est.
        assertThat(new ClientMailIdentity("").senderName()).isEqualTo("NG IT Consulting");
        assertThat(new ClientMailIdentity("  ").senderName())
                .isEqualTo(ClientMailIdentity.DEFAULT_SENDER_NAME);
        assertThat(new ClientMailIdentity(null).senderName())
                .isEqualTo(ClientMailIdentity.DEFAULT_SENDER_NAME);
        assertThat(new ClientMailIdentity("Cabinet Martin").senderName()).isEqualTo("Cabinet Martin");
    }

    @Test
    @DisplayName("un nom trop long est coupé proprement, jamais rejeté")
    void averyLongNameIsTrimmed() {
        // Les clients de messagerie tronquent eux-mêmes, souvent au milieu d'un mot.
        String name = new ClientMailIdentity("N".repeat(200)).senderName();

        assertThat(name).hasSize(ClientMailIdentity.MAX_NAME_CHARS);
    }

    @Test
    @DisplayName("le corps n'a plus de pied de page : il se termine sur son contenu")
    void thebodyHasNoFooter() {
        ClientMailRenderer.Rendered rendered =
                ClientMailRenderer.render("Le bastion sera migré jeudi.", "CAGIP");

        assertThat(rendered.text().strip()).isEqualTo("Le bastion sera migré jeudi.");
        assertThat(rendered.text()).doesNotContain("-- ");
        assertThat(rendered.html()).doesNotContain("<hr");
        assertThat(rendered.html()).doesNotContain("à votre demande");
    }

    @Test
    @DisplayName("le corps écrit par l'agent est rendu intact")
    void theagentsBodyIsRenderedIntact() {
        // On retire le pied, on ne touche pas au message.
        ClientMailRenderer.Rendered rendered =
                ClientMailRenderer.render("# Titre\n\n- un point\n- un autre", "CAGIP");

        assertThat(rendered.html()).contains("<h1>Titre</h1>");
        assertThat(rendered.html()).contains("<li>un point</li>");
    }
}
