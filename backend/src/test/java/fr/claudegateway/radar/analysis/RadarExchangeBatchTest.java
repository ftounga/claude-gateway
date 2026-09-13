package fr.claudegateway.radar.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import fr.claudegateway.radar.InvalidRadarInputException;
import fr.claudegateway.radar.RadarEvidenceSource;

/** F-101 / SF-101-01 — le contrat d'entrée : ce qui entre dans la file est borné et propre. */
class RadarExchangeBatchTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-13T18:00:00Z");

    static RadarExchangeBatch.Message msg(String ref, String text) {
        return new RadarExchangeBatch.Message(ref, AT, "marc@client.fr", "Marc", "RSSI", false, text, null);
    }

    static RadarExchangeBatch batch(String key, List<RadarExchangeBatch.Message> messages) {
        return new RadarExchangeBatch(key, List.of(new RadarExchangeBatch.Exchange(
                RadarEvidenceSource.TEAMS_MESSAGE, "19:abc", "Chantier MFA", null, messages)));
    }

    @Test
    void downloadBlockedIsReadFromTheRunnerAndKeptThroughNormalizationAndThePayload() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules();
        String json = "{\"batchKey\":\"meeting:1\",\"exchanges\":[{\"source\":\"TEAMS_MEETING\",\"conversationRef\":"
                + "\"MTG-1\",\"title\":\"Comité\",\"downloadBlocked\":true,\"messages\":[{\"sourceRef\":\"MTG-1/1\","
                + "\"occurredAt\":\"2026-09-10T09:01:05Z\",\"authorName\":\"Claire\",\"fromMe\":false,"
                + "\"text\":\"Le plan est prêt.\"}]}]}";

        RadarExchangeBatch clean = mapper.readValue(json, RadarExchangeBatch.class).normalized();
        RadarExchangeBatch replayed = mapper.readValue(mapper.writeValueAsString(clean), RadarExchangeBatch.class);

        assertThat(clean.downloadBlocked()).isTrue();
        assertThat(replayed.downloadBlocked()).isTrue();
        assertThat(RadarMaterial.exchangeHeader("E1", clean.exchanges().get(0))).contains("téléchargement bloqué");
        RadarExchangeBatch free = batch("lot", List.of(msg("m1", "x"))).normalized();
        assertThat(free.downloadBlocked()).isFalse();
        assertThat(free.exchanges().get(0).downloadBlocked()).isNull();
        assertThat(RadarMaterial.exchangeHeader("E1", free.exchanges().get(0))).doesNotContain("téléchargement");
    }

    @Test
    void normalizesAndTruncates() {
        String longText = "x".repeat(RadarExchangeBatch.MAX_MESSAGE_CHARS + 50);
        RadarExchangeBatch clean = batch("  lot-1 ", List.of(
                new RadarExchangeBatch.Message(" m1 ", AT, "  ", " Marc ", null, true, "  bonjour  ", " "),
                msg("m2", longText))).normalized();

        assertThat(clean.batchKey()).isEqualTo("lot-1");
        RadarExchangeBatch.Message first = clean.exchanges().get(0).messages().get(0);
        assertThat(first.sourceRef()).isEqualTo("m1");
        assertThat(first.text()).isEqualTo("bonjour");
        assertThat(first.authorKey()).isNull();
        assertThat(first.authorName()).isEqualTo("Marc");
        assertThat(first.deepLink()).isNull();
        assertThat(first.fromMe()).isTrue();
        assertThat(clean.exchanges().get(0).messages().get(1).text()).hasSize(RadarExchangeBatch.MAX_MESSAGE_CHARS);
        assertThat(clean.messageCount()).isEqualTo(2);
    }

    @Test
    void refusesWhatBreaksTheContract() {
        assertThatThrownBy(() -> new RadarExchangeBatch("k", List.of()).normalized())
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> batch(" ", List.of(msg("m1", "a"))).normalized())
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> batch("k", List.of(msg("m1", "a"), msg("m1", "b"))).normalized())
                .isInstanceOf(InvalidRadarInputException.class)
                .hasMessageContaining("double");
        assertThatThrownBy(() -> batch("k", List.of(msg("m1", "  "))).normalized())
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> batch("k", List.of(new RadarExchangeBatch.Message("m1", null, null, null, null,
                false, "a", null))).normalized())
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> new RadarExchangeBatch("k", List.of(new RadarExchangeBatch.Exchange(
                RadarEvidenceSource.USER_NOTE, "c", null, null, List.of(msg("m1", "a"))))).normalized())
                .isInstanceOf(InvalidRadarInputException.class);
    }

    @Test
    void boundsTheBatch() {
        List<RadarExchangeBatch.Exchange> tooMany = IntStream.range(0, RadarExchangeBatch.MAX_EXCHANGES + 1)
                .mapToObj(i -> new RadarExchangeBatch.Exchange(RadarEvidenceSource.TEAMS_MESSAGE, "c" + i, null,
                        null, List.of(msg("m" + i, "a"))))
                .toList();
        assertThatThrownBy(() -> new RadarExchangeBatch("k", tooMany).normalized())
                .isInstanceOf(InvalidRadarInputException.class);

        List<RadarExchangeBatch.Exchange> heavy = new ArrayList<>();
        for (int i = 0; i < 49; i++) {
            List<RadarExchangeBatch.Message> messages = new ArrayList<>();
            for (int j = 0; j < 2; j++) {
                messages.add(msg("m" + i + "-" + j, "y".repeat(RadarExchangeBatch.MAX_MESSAGE_CHARS)));
            }
            heavy.add(new RadarExchangeBatch.Exchange(RadarEvidenceSource.TEAMS_MESSAGE, "c" + i, null, null,
                    messages));
        }
        assertThatThrownBy(() -> new RadarExchangeBatch("k", heavy).normalized())
                .isInstanceOf(InvalidRadarInputException.class)
                .hasMessageContaining("découpez");
    }
}
