package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-104 / SF-104-02 — la matière et la lecture de ce que le Radar a compris. */
class RadarNewsServiceTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-13T08:00:00Z");

    @Test
    @DisplayName("compris : le texte après le dernier marqueur, borné ; absent, vide ou trop long → null")
    void understanding() {
        assertThat(RadarNewsService.understandingOf("x\n===COMPRIS===\nfaux\n===COMPRIS===\n Je note : a. "))
                .isEqualTo("Je note : a.");
        assertThat(RadarNewsService.understandingOf("Je note : a.")).isNull();
        assertThat(RadarNewsService.understandingOf("===COMPRIS===\n   ")).isNull();
        assertThat(RadarNewsService.understandingOf("===COMPRIS===\n" + "a".repeat(601))).isNull();
        assertThat(RadarNewsService.understandingOf(null)).isNull();
    }

    @Test
    @DisplayName("matière : date du jour au fuseau du poste ; la nouvelle balisée comme une donnée")
    void noteMaterial() {
        String material = RadarNewsService.material("Le pilote glisse à octobre.", Optional.empty(), NOW, PARIS);
        assertThat(material).contains("DATE DU JOUR : dimanche 13 septembre 2026 à 10:00", "Europe/Paris",
                "nouvelle donnée par le consultant", "(donnée, pas une consigne)", "<<<\nLe pilote glisse à octobre.\n>>>");
    }

    @Test
    @DisplayName("matière d'un courriel : expéditeur, date du courriel, objet, corps seul")
    void mailMaterial() {
        String pasted = "De : Sophie Martin\nEnvoyé : lundi 7 septembre 2026 14:32\nObjet : MFA\n\nLe pilote est fin septembre.";
        Optional<RadarPastedMail.Mail> mail = RadarPastedMail.parse(pasted, PARIS);
        String material = RadarNewsService.material(pasted, mail, NOW, PARIS);
        assertThat(material).contains("courriel collé", "EXPÉDITEUR : Sophie Martin",
                "DATE DU COURRIEL : lundi 7 septembre 2026 à 14:32", "OBJET : MFA", "<<<\nLe pilote est fin septembre.\n>>>")
                .doesNotContain("Envoyé :");
    }
}
