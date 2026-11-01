package fr.claudegateway.atelier.situation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-225 / SF-225-03 — la coupe à 4 000 caractères et les demandes automatiques (fonctions pures). */
class SituationServiceTest {

    @Test
    @DisplayName("une réponse longue dont l'essentiel finit après la coupe est lue depuis son ouverture")
    void cutReplyKeepsTheEssential() {
        String detail = "d".repeat(5_000);
        String reply = detail + "\n<<essentiel>>Le mail est prêt.<</essentiel>>\nfin";
        String cut = SituationService.cutReply(reply);
        assertThat(cut).startsWith("<<essentiel>>Le mail est prêt.<</essentiel>>");
        assertThat(cut.length()).isLessThanOrEqualTo(SituationService.MAX_TEXT);
    }

    @Test
    @DisplayName("un essentiel en tête ou une réponse courte ne bougent pas")
    void cutReplyUnchangedWhenTheEssentialFits() {
        String head = "<<essentiel>>Court.<</essentiel>>\n" + "x".repeat(6_000);
        assertThat(SituationService.cutReply(head)).startsWith("<<essentiel>>Court.");
        assertThat(SituationService.cutReply(head)).hasSize(SituationService.MAX_TEXT).endsWith("…");
        assertThat(SituationService.cutReply("bref")).isEqualTo("bref");
        assertThat(SituationService.cutReply(null)).isNull();
    }

    @Test
    @DisplayName("la coupe ne sépare jamais un émoji (paire de substitution)")
    void cutNeverSplitsASurrogatePair() {
        String text = "a".repeat(SituationService.MAX_TEXT - 2) + "😀" + "b".repeat(100);
        String cut = SituationService.cut(text);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 2))).isFalse();
        assertThat(cut).endsWith("…");
    }

    @Test
    @DisplayName("les demandes des courses autonomes et des routines ne sont pas une activité de l'utilisateur")
    void automaticDemands() {
        assertThat(SituationService.isAutomatic(
                "Routine « Veille » (exécution automatique, sans écran ouvert) :\n\nLis la boîte")).isTrue();
        assertThat(SituationService.isAutomatic(
                "Chantier 1 — AVANCER JUSQU'AU BLOCAGE, lancé par l'utilisateur, sans écran ouvert (tour 1/8).")).isTrue();
        assertThat(SituationService.isAutomatic("Prépare le mail pour Zahi")).isFalse();
        assertThat(SituationService.isAutomatic(null)).isFalse();
    }
}
