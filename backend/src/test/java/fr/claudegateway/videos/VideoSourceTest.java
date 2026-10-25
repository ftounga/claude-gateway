package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** La fiche des sources d'une vidéo (F-215 / SF-215-04). */
class VideoSourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    @DisplayName("Nominal : affirmations sourcées et à confirmer (sans source) ; espaces nettoyés")
    void parsesSheet() throws Exception {
        List<VideoSource> sources = VideoSource.parse(json("""
                [{"affirmation": "Le VPC  est en 10.180.0.0/16", "source": "carte : vpc-prod"},
                 {"affirmation": "L'ALB écoute sur 443", "a_confirmer": true}]"""));
        assertThat(sources).containsExactly(new VideoSource("Le VPC est en 10.180.0.0/16", "carte : vpc-prod", false),
                new VideoSource("L'ALB écoute sur 443", null, true));
        assertThat(VideoSource.toConfirmCount(sources)).isEqualTo(1);
    }

    @Test
    @DisplayName("Absente : liste vide ; JSON aller-retour ; JSON illisible ⇒ vide")
    void emptyAndRoundTrip() throws Exception {
        assertThat(VideoSource.parse(null)).isEmpty();
        assertThat(VideoSource.toJson(List.of())).isNull();
        List<VideoSource> sheet = List.of(new VideoSource("a", "b", false), new VideoSource("c", null, true));
        String raw = VideoSource.toJson(sheet);
        assertThat(raw).contains("\"aConfirmer\":true");
        assertThat(VideoSource.fromJson(raw)).isEqualTo(sheet);
        assertThat(VideoSource.fromJson("{oops")).isEmpty();
    }

    @Test
    @DisplayName("Refus : pas une liste, sans affirmation, sans source ni a_confirmer, trop longue, > 80 lignes")
    void rejects() throws Exception {
        assertThatThrownBy(() -> VideoSource.parse(json("{\"a\":1}"))).isInstanceOf(VideoRejectedException.class);
        assertThatThrownBy(() -> VideoSource.parse(json("[{\"source\":\"x\"}]")))
                .hasMessageContaining("affirmation");
        assertThatThrownBy(() -> VideoSource.parse(json("[{\"affirmation\":\"port 443\"}]")))
                .hasMessageContaining("n'a pas de source");
        String longText = "x".repeat(301);
        assertThatThrownBy(() -> VideoSource.parse(json("[{\"affirmation\":\"" + longText + "\",\"source\":\"s\"}]")))
                .hasMessageContaining("trop longue");
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 81; i++) {
            many.append(i == 0 ? "" : ",").append("{\"affirmation\":\"a\",\"source\":\"s\"}");
        }
        assertThatThrownBy(() -> VideoSource.parse(json(many.append("]").toString())))
                .hasMessageContaining("80 lignes");
    }
}
