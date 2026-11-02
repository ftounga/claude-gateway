package fr.claudegateway.jarvis.oralbench;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Le banc de la parole, partie pure (F-240 / SF-240-02, D12) : jeu, lecture d'une réponse, porte. */
class JarvisOralBenchTest {

    private static final List<JarvisOralBench.Scenario> SET = JarvisOralBench.load();

    @Test
    void theSetHasTheEightDelegationScenariosOfTheCadrage() {
        assertThat(SET).hasSize(8);
        assertThat(SET).extracting(JarvisOralBench.Scenario::category).containsExactly("POINT_CLIENT",
                "ETAT_CHANTIER", "DIAGNOSTIC", "CONSEIL_AB", "TICKET", "LONG_TABLEAU", "CONFIRMATION", "ANNEXE");
        assertThat(SET.get(6).invitation()).isEqualTo("forbidden");
        assertThat(SET.get(6).confirmation()).isTrue();
        assertThat(SET.get(7).invitation()).isEqualTo("required");
        assertThat(SET.get(7).annexKeyword()).isEqualTo("Helios");
    }

    @Test
    void theMessageCarriesTheFrozenContextThenTheRealOralNote() {
        String message = JarvisOralBench.message(SET.get(1), "parole\n\nconsigne");
        assertThat(message).startsWith("[Banc de la parole").contains("Migration Keycloak").endsWith("parole\n\nconsigne");
    }

    @Test
    void aReplyIsReadWithoutModel() {
        JarvisOralBench.Reading r = JarvisOralBench.read("""
                🎙 Le déploiement de CAGIP est terminé et sain, **Monsieur**.
                ↪ Il y a aussi un point sur Helios, je vous le dis ?
                <<essentiel>>
                - Déploiement terminé à 10:42
                - Santé au vert
                - Certificat Helios : expire le 05/11
                <</essentiel>>
                Détail…""");
        assertThat(r.oral()).isEqualTo("Le déploiement de CAGIP est terminé et sain, Monsieur.");
        assertThat(r.words()).isEqualTo(9);
        assertThat(r.invitation()).isEqualTo("Il y a aussi un point sur Helios, je vous le dis ?");
        assertThat(r.genericInvitation()).isFalse();
        assertThat(r.essentialLines()).isEqualTo(3);
        assertThat(r.asksConfirmation()).isFalse();

        JarvisOralBench.Reading c = JarvisOralBench.read("🎙 J'envoie le compte rendu à Paul, vous confirmez ?");
        assertThat(c.asksConfirmation()).isTrue();
        assertThat(c.invitation()).isNull();
        assertThat(JarvisOralBench.read("Pas de ligne orale.").oral()).isNull();
    }

    @Test
    void genericInvitationsAreRecognised() {
        assertThat(JarvisOralBench.isGenericInvitation("Voulez-vous plus de détails, Monsieur ?")).isTrue();
        assertThat(JarvisOralBench.isGenericInvitation("Je peux développer si vous le souhaitez ?")).isTrue();
        assertThat(JarvisOralBench.isGenericInvitation("Souhaitez-vous que je détaille ?")).isTrue();
        assertThat(JarvisOralBench.isGenericInvitation("Il y a aussi un point sur Helios, je vous le dis ?")).isFalse();
    }

    private static JarvisOralBench.Reading reading(int words, String invitation, boolean confirm) {
        String oral = "mot ".repeat(words).strip() + (confirm ? " vous confirmez ?" : ".");
        return new JarvisOralBench.Reading(oral, words + (confirm ? 2 : 0), invitation,
                invitation != null && JarvisOralBench.isGenericInvitation(invitation), 4, confirm);
    }

    private static List<JarvisOralBench.Reading> green() {
        List<JarvisOralBench.Reading> readings = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            readings.add(reading(24, null, false));
        }
        readings.add(reading(18, null, true));
        readings.add(reading(20, "Il y a aussi un point sur Helios, je vous le dis ?", false));
        return readings;
    }

    @Test
    void theGateIsGreenWhenEveryCriterionHolds() {
        assertThat(JarvisOralBench.gate(SET, green(), 8, 7)).isEmpty();
    }

    @Test
    void theGateFailsOnLengthVerdictInvitationAndConfirmation() {
        List<JarvisOralBench.Reading> tooLong = green();
        tooLong.set(0, reading(45, null, false));
        assertThat(JarvisOralBench.gate(SET, tooLong, 8, 8)).anyMatch(f -> f.startsWith("p90 des mots dits 45"));
        assertThat(JarvisOralBench.gate(SET, green(), 8, 6)).anyMatch(f -> f.startsWith("verdict d'abord 6/8"));

        List<JarvisOralBench.Reading> generic = green();
        generic.set(1, reading(20, "Voulez-vous plus de détails ?", false));
        assertThat(JarvisOralBench.gate(SET, generic, 8, 8)).contains("O2 : invitation générique");

        List<JarvisOralBench.Reading> noInvite = green();
        noInvite.set(7, reading(20, null, false));
        assertThat(JarvisOralBench.gate(SET, noInvite, 8, 8)).contains("O8 : invitation ciblée absente");

        List<JarvisOralBench.Reading> inviteOnConfirm = green();
        inviteOnConfirm.set(6, reading(18, "Il y a aussi Helios, je vous le dis ?", true));
        assertThat(JarvisOralBench.gate(SET, inviteOnConfirm, 8, 8)).contains("O7 : invitation sur une confirmation");

        List<JarvisOralBench.Reading> noConfirm = green();
        noConfirm.set(6, reading(18, null, false));
        assertThat(JarvisOralBench.gate(SET, noConfirm, 8, 8)).contains("O7 : confirmation non demandée");

        assertThat(JarvisOralBench.gate(SET, green().subList(0, 5), 5, 5)).anyMatch(f -> f.contains("non joué"));
        assertThat(JarvisOralBench.gate(SET, green(), 7, 7)).anyMatch(f -> f.contains("non jugé"));
    }
}
