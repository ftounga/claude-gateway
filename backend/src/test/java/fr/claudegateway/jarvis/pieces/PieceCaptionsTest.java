package fr.claudegateway.jarvis.pieces;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-237 / SF-237-01 — ce que Jarvis dit d'une pièce : uniquement ce qui est déjà écrit, dans l'ordre §11. */
class PieceCaptionsTest {

    @Test
    @DisplayName("dépôt : la phrase autour de SON marqueur, puis le message entier, puis rien")
    void depositPriority() {
        String text = "Voici l'erreur [Image #1] du matin. Et la démo [Vidéo #2] du déploiement ! Merci.";
        assertThat(PieceCaptions.deposit(text, 0).text()).isEqualTo("Voici l'erreur [Image #1] du matin.");
        assertThat(PieceCaptions.deposit(text, 1).text()).isEqualTo("Et la démo [Vidéo #2] du déploiement !");
        assertThat(PieceCaptions.deposit(text, 1).source()).isEqualTo(Piece.CaptionSource.MESSAGE);
        // pas de marqueur à cette position : le message entier
        assertThat(PieceCaptions.deposit("Regarde ce fichier", 0).text()).isEqualTo("Regarde ce fichier");
        assertThat(PieceCaptions.deposit(text, 5).text()).isEqualTo(text);
        assertThat(PieceCaptions.deposit(null, 0)).isNull();
        assertThat(PieceCaptions.deposit("   ", 0)).isNull();
        assertThat(PieceCaptions.deposit("x".repeat(400), 0).text()).hasSize(PieceCaptions.MAX_DEPOSIT);
    }

    @Test
    @DisplayName("preuve : titre, étape, bilan des commandes — jamais leur sortie")
    void proof() {
        assertThat(PieceCaptions.proof("Santé", "Étape 4", List.of("done", "done"), List.of(0, 0)).text())
                .isEqualTo("Santé — étape : Étape 4 — 2 commandes, toutes réussies");
        assertThat(PieceCaptions.proof("Santé", null, List.of("done", "done"), Arrays.asList(0, 2)).text())
                .isEqualTo("Santé — 2 commandes, 1 en échec");
        assertThat(PieceCaptions.proof(null, null, List.of("done"), List.of(0)).text())
                .isEqualTo("Preuve — 1 commande, réussie");
        assertThat(PieceCaptions.proof("P", null, List.of(), List.of()).text()).isEqualTo("P");
    }

    @Test
    @DisplayName("envoi : objet, destinataire, extrait")
    void sent() {
        assertThat(PieceCaptions.sent("Livraison", "Zahi", "Bonjour,\n la v2 est en ligne").text())
                .isEqualTo("« Livraison » à Zahi — Bonjour, la v2 est en ligne");
        assertThat(PieceCaptions.sent(null, null, null)).isNull();
    }

    @Test
    @DisplayName("première source non vide, dans l'ordre")
    void firstNonBlank() {
        PieceCaptions.Caption c = PieceCaptions.first(PieceCaptions.of(" ", Piece.CaptionSource.REFORMULATION),
                PieceCaptions.of("La description", Piece.CaptionSource.DESCRIPTION),
                PieceCaptions.of("Titre", Piece.CaptionSource.TITLE));
        assertThat(c.source()).isEqualTo(Piece.CaptionSource.DESCRIPTION);
        assertThat(PieceCaptions.first()).isNull();
        assertThat(PieceCaptions.clip("abcdef", 4)).isEqualTo("abc…");
    }
}
