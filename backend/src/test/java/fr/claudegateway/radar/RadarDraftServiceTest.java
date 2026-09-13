package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-104 / SF-104-05 — la forme du brouillon et le lien de la conversation d'origine. */
class RadarDraftServiceTest {

    @Test
    @DisplayName("brouillon : texte après le dernier marqueur, borné ; absent, vide ou trop long → null")
    void draft() {
        assertThat(RadarDraftService.draftOf("brouillon\n===BROUILLON===\nx\n===BROUILLON===\n Bonjour Julie, où en est le retour ? "))
                .isEqualTo("Bonjour Julie, où en est le retour ?");
        assertThat(RadarDraftService.draftOf("Bonjour Julie")).isNull();
        assertThat(RadarDraftService.draftOf("===BROUILLON===\n  ")).isNull();
        assertThat(RadarDraftService.draftOf("===BROUILLON===\n" + "a".repeat(1_501))).isNull();
        assertThat(RadarDraftService.draftOf(null)).isNull();
    }

    @Test
    @DisplayName("lien : https sur un hôte Teams seulement")
    void teamsLink() {
        assertThat(RadarDraftService.teamsLink("https://teams.microsoft.com/l/message/19:abc/123"))
                .isEqualTo("https://teams.microsoft.com/l/message/19:abc/123");
        assertThat(RadarDraftService.teamsLink("https://teams.cloud.microsoft/l/chat/1")).isNotNull();
        assertThat(RadarDraftService.teamsLink("http://teams.microsoft.com/l/message/1")).isNull();
        assertThat(RadarDraftService.teamsLink("https://teams.microsoft.com.evil.io/l/message/1")).isNull();
        assertThat(RadarDraftService.teamsLink("javascript:alert(1)")).isNull();
        assertThat(RadarDraftService.teamsLink("pas une adresse")).isNull();
        assertThat(RadarDraftService.teamsLink(null)).isNull();
    }
}
