package fr.claudegateway.atelier.plume;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le classement « demande de vidéo » de Plume (F-215 / SF-215-05). */
class PlumeVideoIntentTest {

    @Test
    @DisplayName("Vidéo : vidéo/video, voix off, motion design, cours animé, série d'épisodes — casse et accents ignorés")
    void detectsVideoRequests() {
        assertThat(PlumeVideoIntent.isVideo("fais-moi une vidéo sur le réseau EKS")).isTrue();
        assertThat(PlumeVideoIntent.isVideo("Une VIDEO de 15 minutes sur l'archi AWS")).isTrue();
        assertThat(PlumeVideoIntent.isVideo("explique avec une voix off")).isTrue();
        assertThat(PlumeVideoIntent.isVideo("un cours animé sur Azure")).isTrue();
        assertThat(PlumeVideoIntent.isVideo("en motion design")).isTrue();
        assertThat(PlumeVideoIntent.isVideo("un tutoriel en série d’épisodes")).isTrue();
        assertThat(PlumeVideoIntent.isVideo("refais l'épisode du cours sur le DNS")).isTrue();
    }

    @Test
    @DisplayName("Autre : demandes de code, d'analyse, vides — F-193 inchangé")
    void otherRequestsAreNotVideo() {
        assertThat(PlumeVideoIntent.isVideo("ajoute la pagination sur l'export")).isFalse();
        assertThat(PlumeVideoIntent.isVideo("corrige le bug de l'épisode 3 de la facturation")).isFalse();
        assertThat(PlumeVideoIntent.isVideo("analyse le module videotheque")).isFalse();
        assertThat(PlumeVideoIntent.isVideo("")).isFalse();
        assertThat(PlumeVideoIntent.isVideo(null)).isFalse();
    }
}
