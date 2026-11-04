package fr.claudegateway.jarvis.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * F-243 / SF-243-05 (amendement de D11) — le résultat de l'outil {@code agenda} vu par un public : les agendas
 * personnels sont retirés avant le modèle, les agendas pro restent.
 */
class JarvisCalendarReaderTest {

    static final String HEADER = "Agenda, heure de Paris — aujourd'hui, lundi 2 novembre. Maintenant : lundi 2 novembre "
            + "09:15. À jour à 09:05.";

    static String text(String... lines) {
        return HEADER + "\n" + JarvisCalendarReader.NOTE + "\n" + JarvisCalendarReader.OPEN + "\n"
                + String.join("\n", lines) + "\n" + JarvisCalendarReader.CLOSE;
    }

    @Test
    void personalCalendarsDisappearAndWorkCalendarsStay() {
        String shown = JarvisCalendarReader.forPresentation(text(
                "- 09:00–09:30 — Point hebdo équipe — agenda Pro [en cours]",
                "- 11:00–12:00 — Revue de recette — lieu : Visio — agenda Pro",
                "- 18:30–19:30 — Cours de piano de Léa — agenda Perso",
                "- 20:00–21:00 — Dîner — agenda Privé — à confirmer",
                "- 21:00–22:00 — Anniversaire — agenda Famille Dupont [prochain]",
                "- 07:00–08:00 — Course — agenda Personal"));
        assertThat(shown).startsWith(HEADER + "\n" + JarvisCalendarReader.PRESENTATION_NOTE)
                .contains("Point hebdo équipe").contains("Revue de recette").doesNotContain("piano")
                .doesNotContain("Dîner").doesNotContain("Anniversaire").doesNotContain("Course")
                .contains(JarvisCalendarReader.OPEN).endsWith(JarvisCalendarReader.CLOSE);
    }

    @Test
    void onlyPersonalAppointmentsLeaveNoAppointment() {
        String shown = JarvisCalendarReader.forPresentation(text("- 18:30–19:30 — Piano — agenda perso"));
        assertThat(shown).doesNotContain("Piano").contains(JarvisCalendarReader.OPEN + "\nAucun rendez-vous.\n"
                + JarvisCalendarReader.CLOSE);
    }

    @Test
    void anEmptyDayStaysEmptyOnce() {
        String shown = JarvisCalendarReader.forPresentation(text("Aucun rendez-vous."));
        assertThat(shown.indexOf("Aucun rendez-vous.")).isEqualTo(shown.lastIndexOf("Aucun rendez-vous."))
                .isPositive();
    }

    @Test
    void aWordThatOnlyContainsPersoIsNotPersonal() {
        // « Persona » ou un titre qui dit « perso » ne suffit pas : seul le libellé de l'agenda compte.
        String shown = JarvisCalendarReader.forPresentation(text(
                "- 10:00–11:00 — Atelier persona — agenda Persona Pro",
                "- 12:00–13:00 — Point perso avec Paul — agenda Pro"));
        assertThat(shown).contains("Atelier persona").contains("Point perso avec Paul");
    }

    @Test
    void anythingElseIsReturnedUntouched() {
        assertThat(JarvisCalendarReader.forPresentation(JarvisCalendarReader.NO_FEED))
                .isEqualTo(JarvisCalendarReader.NO_FEED);
        String out = "Hors de la fenêtre lue (de la veille à 60 jours) : la question se délègue.";
        assertThat(JarvisCalendarReader.forPresentation(out)).isEqualTo(out);
        assertThat(JarvisCalendarReader.forPresentation(null)).isNull();
    }
}
