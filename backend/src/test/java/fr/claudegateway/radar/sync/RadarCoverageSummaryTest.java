package fr.claudegateway.radar.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncStatus;
import fr.claudegateway.radar.RadarSyncTrigger;

/** F-100 / SF-100-04 — la phrase de tête de la couverture : jamais rassurante à tort. */
class RadarCoverageSummaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private static RadarSync sync(RadarSyncStatus status) {
        return RadarSync.builder().status(status).startedAt(OffsetDateTime.parse("2026-09-13T20:00:00Z"))
                .finishedAt(OffsetDateTime.parse("2026-09-13T20:41:00Z")).triggerKind(RadarSyncTrigger.SCHEDULED).build();
    }

    private static JsonNode json(String value) throws Exception {
        return MAPPER.readTree(value);
    }

    @Test
    @DisplayName("Complète : ce qui a été lu ; faite avec des manques : jamais « complète »")
    void succeeded() throws Exception {
        RadarCoverageSummary complete = RadarCoverageSummary.of(sync(RadarSyncStatus.SUCCEEDED),
                json("{\"conversations\":{\"read\":38},\"meetings\":{\"transcribed\":2}}"), null, Map.of(), PARIS);
        assertThat(complete.headline()).isEqualTo("Synchro complète : 38 conversations lues et 2 réunions transcrites.");

        RadarCoverageSummary withGaps = RadarCoverageSummary.of(sync(RadarSyncStatus.SUCCEEDED),
                json("{\"conversations\":{\"read\":1},\"channels\":{\"unreadActive\":6},\"meetings\":{\"noTranscript\":1}}"),
                null, Map.of(), PARIS);
        assertThat(withGaps.headline()).startsWith("Synchro faite").doesNotContain("complète")
                .contains("6 canaux actifs non lus").contains("1 réunion sans transcription");

        assertThat(RadarCoverageSummary.of(sync(RadarSyncStatus.SUCCEEDED), json("{}"), null, Map.of(), PARIS).headline())
                .isEqualTo("Synchro complète : rien de nouveau.");
    }

    @Test
    @DisplayName("Partielle : les compteurs non nuls seuls, dans l'ordre où ils comptent")
    void partial() throws Exception {
        RadarCoverageSummary summary = RadarCoverageSummary.of(sync(RadarSyncStatus.PARTIAL),
                json("{\"conversations\":{\"partial\":2,\"failed\":1,\"deferred\":0},\"discovery\":{\"complete\":false},"
                        + "\"channels\":{\"unreadActive\":6},\"meetings\":{\"noTranscript\":0,\"denied\":1}}"),
                null, Map.of(), PARIS);
        assertThat(summary.headline()).isEqualTo("Synchro partielle : 3 fils non entièrement lus, liste des conversations "
                + "peut-être incomplète, 6 canaux actifs non lus, 1 transcription refusée.");
        assertThat(RadarCoverageSummary.of(sync(RadarSyncStatus.PARTIAL), null, null, Map.of(), PARIS).headline())
                .isEqualTo("Synchro partielle : tout n'a pas pu être lu.");
    }

    @Test
    @DisplayName("Échec : la phrase de l'échec et le geste ; annulée : l'heure du poste ; en cours : la progression")
    void failedCancelledRunning() throws Exception {
        RadarCoverageSummary failed = RadarCoverageSummary.of(sync(RadarSyncStatus.FAILED),
                json("{\"failure\":{\"code\":\"SESSION_EXPIRED\",\"sentence\":\"Session Microsoft expirée : rien n'a été "
                        + "synchronisé.\",\"remedy\":\"Rouvrez Teams dans Chrome.\"}}"), null, Map.of(), PARIS);
        assertThat(failed.headline()).isEqualTo("Session Microsoft expirée : rien n'a été synchronisé.");
        assertThat(failed.remedy()).isEqualTo("Rouvrez Teams dans Chrome.");

        assertThat(RadarCoverageSummary.of(sync(RadarSyncStatus.CANCELLED), json("{\"cancelled\":true}"), null, Map.of(),
                PARIS).headline()).isEqualTo("Synchro annulée à 22 h 41 : ce qui avait été lu est conservé.");

        assertThat(RadarCoverageSummary.of(sync(RadarSyncStatus.RUNNING), null,
                json("{\"phase\":\"conversations\",\"done\":12,\"total\":40}"), Map.of(), PARIS).headline())
                .isEqualTo("Synchro en cours : 12 conversations sur 40.");
        assertThat(RadarCoverageSummary.of(sync(RadarSyncStatus.RUNNING), null, null, Map.of(), PARIS).headline())
                .isEqualTo("Synchro en cours.");
    }

    @Test
    @DisplayName("Rattrapage : « Synchro d'hier soir non faite, rattrapée à 8 h 12. » en tête")
    void catchUp() throws Exception {
        RadarSync caughtUp = RadarSync.builder().status(RadarSyncStatus.SUCCEEDED)
                .triggerKind(RadarSyncTrigger.CATCH_UP)
                .scheduledFor(OffsetDateTime.parse("2026-09-13T20:00:00Z"))
                .startedAt(OffsetDateTime.parse("2026-09-14T06:12:00Z")).build();
        assertThat(RadarCoverageSummary.of(caughtUp, json("{\"conversations\":{\"read\":3}}"), null, Map.of(), PARIS)
                .headline()).isEqualTo("Synchro d'hier soir non faite, rattrapée à 8 h 12. Synchro complète : 3 conversations lues.");
    }

    @Test
    @DisplayName("Manques : gestes possibles par nature, et la règle déjà posée")
    void itemsAndActions() throws Exception {
        RadarCoverageSummary summary = RadarCoverageSummary.of(sync(RadarSyncStatus.PARTIAL), json("{\"threads\":["
                + "{\"ref\":\"19:canal\",\"label\":\"Migration\",\"kind\":\"CHANNEL\",\"status\":\"UNREAD_CHANNEL\"},"
                + "{\"ref\":\"19:fil\",\"label\":\"Paul\",\"kind\":\"CONVERSATION\",\"status\":\"PARTIAL\",\"detail\":\"incomplet\"},"
                + "{\"ref\":\"MTG-1\",\"label\":\"Comité\",\"kind\":\"MEETING\",\"status\":\"NO_TRANSCRIPT\"}]}"),
                null, Map.of("19:fil", "IGNORE"), PARIS);
        assertThat(summary.items()).hasSize(3);
        assertThat(summary.items().get(0).actions()).containsExactly("READ_CHANNEL", "IGNORE");
        assertThat(summary.items().get(1).actions()).containsExactly("IGNORE");
        assertThat(summary.items().get(1).rule()).isEqualTo("IGNORE");
        assertThat(summary.items().get(1).detail()).isEqualTo("incomplet");
        assertThat(summary.items().get(2).actions()).isEmpty();
    }
}
