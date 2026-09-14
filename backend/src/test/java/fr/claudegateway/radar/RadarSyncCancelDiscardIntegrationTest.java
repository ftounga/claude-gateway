package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fr.claudegateway.radar.analysis.RadarAnalysisBatch;
import fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus;
import fr.claudegateway.radar.analysis.RadarAnalysisIntake;
import fr.claudegateway.radar.analysis.RadarExchangeBatch;
import fr.claudegateway.radar.sync.RadarSyncControlService;
import fr.claudegateway.radar.sync.RadarSyncLauncher;
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * F-100 / SF-100-08 — <b>les lots d'une synchro annulée</b> : à l'annulation, les lots pas encore analysés
 * sont écartés ({@code DISCARDED}, brut effacé), les lots déjà {@code DONE} conservés, et rien d'un autre poste
 * n'est touché.
 */
class RadarSyncCancelDiscardIntegrationTest extends RadarSyncIntegrationTestBase {

    @Autowired private RadarSyncLauncher launcher;
    @Autowired private RadarSyncControlService control;
    @Autowired private RadarAnalysisIntake intake;

    private RadarSync running(RadarScope scope) {
        enable(scope, "22:00", "Europe/Paris", null);
        when(liveness.isAlive(any(), any())).thenReturn(true);
        runnerAcceptsSyncs();
        return launcher.start(scope, RadarSyncTrigger.MANUAL, null);
    }

    private static RadarExchangeBatch batch(String key, String text) {
        return new RadarExchangeBatch(key, List.of(new RadarExchangeBatch.Exchange(
                RadarEvidenceSource.TEAMS_MESSAGE, "19:abc", "MFA", null,
                List.of(new RadarExchangeBatch.Message(key + "/m0", OffsetDateTime.now().minusHours(1),
                        "marc@client.fr", "Marc", null, false, text, null)))));
    }

    @Test
    @DisplayName("Annulation : lots non terminaux écartés (brut effacé), lot DONE conservé, autre poste intact ; "
            + "la couverture compte les lots écartés")
    void cancelDiscardsUnfinishedBatchesOnly() throws Exception {
        RadarSync sync = running(aliceA);
        UUID pending = intake.submit(aliceA, sync.getId(), batch("lot-1", "Je m'en charge.")).batchId();
        UUID keep = intake.submit(aliceA, sync.getId(), batch("lot-2", "Autre.")).batchId();
        RadarAnalysisBatch analysed = analysisBatches.findById(keep).orElseThrow();
        analysed.setStatus(RadarAnalysisBatchStatus.DONE);
        analysed.deleteRaw(OffsetDateTime.now());
        analysisBatches.save(analysed);

        // Isolation : un lot d'un autre poste, sur sa propre synchro, ne doit pas bouger.
        RadarSync other = running(aliceB);
        UUID otherBatch = intake.submit(aliceB, other.getId(), batch("lot-1", "Chez B.")).batchId();

        when(router.call(any(RunnerTarget.class), anyString(), eq(RadarSyncControlService.CANCEL), any(), anyLong()))
                .thenReturn(ok("{}"));
        control.cancel(aliceA, sync.getId());

        RadarAnalysisBatch discarded = analysisBatches.findById(pending).orElseThrow();
        assertThat(discarded.getStatus()).isEqualTo(RadarAnalysisBatchStatus.DISCARDED);
        assertThat(discarded.getPayload()).isNull();
        assertThat(discarded.getRawDeletedAt()).isNotNull();
        assertThat(analysisBatches.findById(keep).orElseThrow().getStatus())
                .isEqualTo(RadarAnalysisBatchStatus.DONE);
        assertThat(analysisBatches.findById(otherBatch).orElseThrow().getStatus())
                .isEqualTo(RadarAnalysisBatchStatus.PENDING);

        // La couverture le dit : GET /syncs → analysis.batches.DISCARDED compte le lot écarté.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(url(aliceA, "/syncs")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].analysis.batches.DISCARDED").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].analysis.batches.DONE").value(1));
    }
}
