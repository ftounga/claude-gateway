package fr.claudegateway.jarvis.portrait;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** F-241 / SF-241-03 — le bloc, la date qui compte, le filet des interdits, le détecteur de livrable. */
class JarvisPortraitNoteTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-03-10T09:00:00Z");

    private static JarvisPortraitService.Snapshot portrait(Map<String, String> sections, List<String> souvenirs) {
        return JarvisPortraitNote.fixture(sections, souvenirs, AT);
    }

    @Test
    void theRulesAreFrozenAndHoldNothingVolatile() {
        assertThat(JarvisPortraitNote.RULES).startsWith("# Ce que vous savez de Monsieur")
                .contains("Avoir l'information ne veut pas dire s'en servir")
                .contains("Au plus UNE allusion personnelle spontanée").contains("À ne pas aborder")
                .contains("destiné à un tiers").doesNotContain("2026").doesNotContain("Maintenant");
        assertThat(JarvisPortraitNote.PRESENTATION_NOTE).contains("Nous en parlerons en privé");
    }

    @Test
    void theBlockIsTheWholePortraitDatedSensitiveMarkedAndStable() {
        JarvisPortraitService.Snapshot p = portrait(Map.of("QUI", "Chef de projet", "ENTOURAGE", "Fille Léa, 6 ans"),
                List.of("Prépare un marathon"));
        String block = JarvisPortraitNote.block(p);
        assertThat(block).startsWith(JarvisPortraitNote.RULES)
                .contains("## Qui vous êtes (mis à jour en mars 2026)\nChef de projet")
                .contains("## Votre entourage — sensible (mis à jour en mars 2026)\nFille Léa, 6 ans")
                .contains("- S1 (noté en mars 2026) : Prépare un marathon");
        assertThat(JarvisPortraitNote.block(p)).isEqualTo(block);
        assertThat(JarvisPortraitNote.appendForOpus("consigne", p)).startsWith("consigne\n\n"
                + JarvisPortraitNote.OPUS_HEADER).endsWith(block);
        assertThat(JarvisPortraitNote.appendForOpus("consigne", portrait(Map.of(), List.of()))).isEqualTo("consigne");
    }

    @Test
    void importantDateTodayOrWithinAWeekAnnualWithoutYear() {
        JarvisPortraitService.Snapshot p = portrait(Map.of("DATES",
                "02/11 — anniversaire de Monsieur\n06/11/2026 — retour de congé de Nadia\n25/12 — Noël\nillisible"),
                List.of());
        String note = JarvisPortraitNote.importantDate(p, LocalDate.of(2026, 11, 2));
        assertThat(note).contains("aujourd'hui : anniversaire de Monsieur")
                .contains("dans 4 jours : retour de congé de Nadia").doesNotContain("Noël");
        assertThat(JarvisPortraitNote.importantDate(p, LocalDate.of(2026, 12, 1))).isNull();
        assertThat(JarvisPortraitNote.importantDate(p, LocalDate.of(2027, 10, 30))).contains("dans 3 jours");
    }

    @Test
    void forbiddenTopicsAreBlockedUnlessThePoRaisesThem() {
        JarvisPortraitService.Snapshot p = portrait(Map.of("INTERDITS", "Le divorce et son ex-femme Sandra."),
                List.of());
        var terms = JarvisPortraitNote.forbiddenTerms(p);
        assertThat(terms).contains("divorce", "femme", "sandra").doesNotContain("son", "le");
        assertThat(JarvisPortraitNote.blocked("Depuis votre divorce, le week-end est calme.", "Mon week-end ?",
                terms)).isTrue();
        assertThat(JarvisPortraitNote.blocked("Les divorcés ont droit au repos.", "Mon week-end ?", terms)).isTrue();
        assertThat(JarvisPortraitNote.blocked("Le divorce avance ?", "Mon avocat parle du divorce.", terms))
                .isFalse();
        assertThat(JarvisPortraitNote.blocked("Un peu de jazz ?", "Mon week-end ?", terms)).isFalse();
    }

    @Test
    void deliverableDetectorFlagsPersonalElementsNotProfessionalOnes() {
        JarvisPortraitService.Snapshot p = portrait(Map.of(
                "QUI", "Chef de projet chez Edenred",
                "ENTOURAGE", "Sa compagne Nadia ; leur fils Léo est né en septembre.",
                "MOMENT", "Nuits courtes depuis la naissance du petit dernier."), List.of());
        assertThat(JarvisPortraitNote.leaks("Bonjour Claire, la recette est décalée à jeudi. Bien à vous, Franck",
                p)).isFalse();
        assertThat(JarvisPortraitNote.leaks("Monsieur, le point Edenred est prêt.", p)).isFalse();
        assertThat(JarvisPortraitNote.leaks("Désolé du retard, Léo m'a tenu éveillé.", p)).isTrue();
        assertThat(JarvisPortraitNote.leaks("Nuits courtes depuis la naissance, désolé.", p)).isTrue();
        assertThat(JarvisPortraitNote.leaks("Texte", portrait(Map.of(), List.of()))).isFalse();
    }
}
