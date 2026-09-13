package fr.claudegateway.radar.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** F-100 / SF-100-01 — lecture de la réponse du runner et fusion des cases. */
class RadarVerificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-13T20:00:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-09-13T20:01:00Z");

    private static RadarVerification read(String checks, OffsetDateTime at) throws Exception {
        return RadarVerification.fromRunner(MAPPER.readTree("{\"checks\":{" + checks + "}}"), at);
    }

    private static String all(boolean s, boolean c, boolean m, boolean t) {
        return "\"session\":{\"ok\":" + s + ",\"state\":\"LINKED\",\"sentence\":\"s\"},"
                + "\"conversations\":{\"ok\":" + c + ",\"count\":2,\"sentence\":\"c\"},"
                + "\"meetings\":{\"ok\":" + m + ",\"count\":1,\"sentence\":\"m\"},"
                + "\"transcripts\":{\"ok\":" + t + ",\"count\":5,\"reason\":\"" + (t ? "SEEN" : "ACCESS_DENIED")
                + "\",\"sentence\":\"t\"}";
    }

    @Test
    @DisplayName("Une réponse sans cases ou avec une case absente est illisible")
    void unreadable() {
        assertThatThrownBy(() -> RadarVerification.fromRunner(MAPPER.readTree("{}"), T1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> read("\"session\":{\"ok\":true}", T1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RadarVerification.fromRunner(null, T1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Bornes : compteur, phrase, état propre ; la raison d'une transcription devient son état")
    void bounds() throws Exception {
        String longSentence = "x".repeat(900);
        RadarVerification v = read("\"session\":{\"ok\":true,\"state\":\"<script>\",\"sentence\":\"" + longSentence
                + "\"},\"conversations\":{\"ok\":true,\"count\":99999999999},"
                + "\"meetings\":{\"ok\":false,\"count\":-4},"
                + "\"transcripts\":{\"ok\":false,\"reason\":\"access_denied\"}", T1);
        assertThat(v.session().sentence()).hasSize(RadarVerification.MAX_SENTENCE_CHARS);
        assertThat(v.session().state()).isEmpty();
        assertThat(v.conversations().count()).isEqualTo(RadarVerification.MAX_COUNT);
        assertThat(v.meetings().count()).isZero();
        assertThat(v.transcripts().state()).isEqualTo("ACCESS_DENIED");
        assertThat(v.session().okSince()).isEqualTo(T1);
        assertThat(v.meetings().okSince()).isNull();
    }

    @Test
    @DisplayName("Fusion : une case cochée reste cochée avec son instant ; la session n'est jamais collante")
    void merge() throws Exception {
        RadarVerification first = read(all(true, true, false, false), T1);
        RadarVerification second = read(all(true, false, true, true), T2).mergedOnto(first);

        assertThat(second.conversations().ok()).isTrue();
        assertThat(second.conversations().okSince()).isEqualTo(T1);
        assertThat(second.meetings().okSince()).isEqualTo(T2);
        assertThat(second.session().okSince()).isEqualTo(T1);
        assertThat(second.complete()).isTrue();

        RadarVerification third = read(all(false, false, false, false), T2).mergedOnto(second);
        assertThat(third.session().ok()).isFalse();
        assertThat(third.transcripts().ok()).isTrue();
        assertThat(third.complete()).isFalse();
        assertThat(RadarVerification.none().complete()).isFalse();
    }

    @Test
    @DisplayName("L'état enregistré se relit tel quel (JSON)")
    void roundTrip() throws Exception {
        RadarVerification v = read(all(true, true, true, true), T1);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RadarVerification back = mapper.readValue(mapper.writeValueAsString(v), RadarVerification.class);
        assertThat(back.complete()).isTrue();
        assertThat(back.transcripts().state()).isEqualTo("SEEN");
    }
}
