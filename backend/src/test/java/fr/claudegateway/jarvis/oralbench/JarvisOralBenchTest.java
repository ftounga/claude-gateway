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
        assertThat(SET).hasSize(10);
        assertThat(SET).extracting(JarvisOralBench.Scenario::category).containsExactly("POINT_CLIENT",
                "ETAT_CHANTIER", "DIAGNOSTIC", "CONSEIL_AB", "TICKET", "LONG_TABLEAU", "CONFIRMATION", "ANNEXE", "PORTRAIT_F241", "PORTRAIT_F241");
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
        readings.add(reading(22, null, false)); // O9 — F-241, portrait fictif
        readings.add(reading(22, null, false)); // O10 — F-241, portrait fictif
        return readings;
    }

    @Test
    void theGateIsGreenWhenEveryCriterionHolds() {
        assertThat(JarvisOralBench.gate(SET, green(), 10, 7)).isEmpty();
    }

    @Test
    void theGateFailsOnLengthVerdictInvitationAndConfirmation() {
        List<JarvisOralBench.Reading> tooLong = green();
        tooLong.set(0, reading(45, null, false));
        tooLong.set(1, reading(45, null, false)); // dix scénarios (F-241) : deux réponses longues font le p90
        assertThat(JarvisOralBench.gate(SET, tooLong, 10, 8)).anyMatch(f -> f.startsWith("p90 des mots dits 45"));
        assertThat(JarvisOralBench.gate(SET, green(), 10, 6)).anyMatch(f -> f.startsWith("verdict d'abord 6/10"));

        List<JarvisOralBench.Reading> generic = green();
        generic.set(1, reading(20, "Voulez-vous plus de détails ?", false));
        assertThat(JarvisOralBench.gate(SET, generic, 10, 8)).contains("O2 : invitation générique");

        List<JarvisOralBench.Reading> noInvite = green();
        noInvite.set(7, reading(20, null, false));
        assertThat(JarvisOralBench.gate(SET, noInvite, 10, 8)).contains("O8 : invitation ciblée absente");

        List<JarvisOralBench.Reading> inviteOnConfirm = green();
        inviteOnConfirm.set(6, reading(18, "Il y a aussi Helios, je vous le dis ?", true));
        assertThat(JarvisOralBench.gate(SET, inviteOnConfirm, 10, 8)).contains("O7 : invitation sur une confirmation");

        List<JarvisOralBench.Reading> noConfirm = green();
        noConfirm.set(6, reading(18, null, false));
        assertThat(JarvisOralBench.gate(SET, noConfirm, 10, 8)).contains("O7 : confirmation non demandée");

        assertThat(JarvisOralBench.gate(SET, green().subList(0, 5), 5, 5)).anyMatch(f -> f.contains("non joué"));
        assertThat(JarvisOralBench.gate(SET, green(), 7, 7)).anyMatch(f -> f.contains("non jugé"));
    }

    // ---- F-240 / SF-240-06 : un banc indépendant de la date du jour ----

    @Test
    void theSetCarriesNoAbsoluteDateOnlyTokensRelativeToTheDayOfTheRun() {
        java.util.regex.Pattern absolute = java.util.regex.Pattern.compile(
                "\\b\\d{1,2}/\\d{1,2}\\b|\\b(janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|"
                        + "novembre|décembre)\\b");
        for (JarvisOralBench.Scenario s : SET) {
            assertThat(absolute.matcher(s.said()).find()).as(s.id() + " demande").isFalse();
            assertThat(absolute.matcher(s.context()).find()).as(s.id() + " contexte").isFalse();
        }
    }

    @Test
    void o7OnTheThirdOfNovemberSpeaksOfYesterdaysMeeting() {
        JarvisOralBench.Scenario o7 = JarvisOralBench.load(java.time.LocalDate.of(2026, 11, 3)).get(6);
        assertThat(o7.said()).contains("réunion d'hier");
        assertThat(o7.context()).contains("réunion du lundi 02/11").contains("avant le vendredi 06/11")
                .doesNotContain("{");
        // Un autre jour : la même relation (hier), d'autres dates.
        JarvisOralBench.Scenario later = JarvisOralBench.load(java.time.LocalDate.of(2026, 12, 15)).get(6);
        assertThat(later.context()).contains("réunion du lundi 14/12");
    }

    @Test
    void tokensResolveAgainstTodayCalendarWorkingDaysAndMonths() {
        java.time.LocalDate monday = java.time.LocalDate.of(2026, 11, 2);
        assertThat(JarvisOralBench.dated("{J-1:jour} {J-1}, {J} et {J+3}", monday))
                .isEqualTo("dimanche 01/11, 02/11 et 05/11");
        assertThat(JarvisOralBench.dated("{O-1:jour} {O-1} puis {O+1:jour} {O+5}", monday))
                .isEqualTo("vendredi 30/10 puis mardi 09/11");
        assertThat(JarvisOralBench.dated("{M-1}, {M-2} et le {M-1:14}", monday))
                .isEqualTo("octobre, septembre et le 14/10");
        assertThat(JarvisOralBench.dated("{M+1:31}", java.time.LocalDate.of(2027, 1, 20))).isEqualTo("28/02");
        assertThat(JarvisOralBench.dated("sans jeton", monday)).isEqualTo("sans jeton");
    }

    @Test
    void anUnknownTokenIsRefusedNeverSpokenAsIs() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JarvisOralBench.dated("le {X+1}", today))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JarvisOralBench.dated("le {J+1:mois}", today))
                .isInstanceOf(IllegalStateException.class);
        assertThat(JarvisOralBench.load(today)).allSatisfy(s -> {
            assertThat(s.said()).doesNotContain("{");
            assertThat(s.context()).doesNotContain("{");
        });
    }
}
