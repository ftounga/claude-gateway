package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import fr.claudegateway.mail.ClientEmail;
import fr.claudegateway.mail.ClientEmailRepository;
import fr.claudegateway.mail.HostMailAddress;
import fr.claudegateway.mail.HostMailAddressRepository;
import fr.claudegateway.radar.analysis.RadarAnalysisBatch;
import fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus;
import fr.claudegateway.radar.sync.RadarMorningMail;

/**
 * F-110 / SF-110-04 — <b>le résumé du matin par courriel</b>, sur base réelle : le réglage par l'API, puis le
 * passage du travailleur après une synchro du soir terminée et analysée.
 */
class RadarMorningMailIntegrationTest extends RadarSyncIntegrationTestBase {

    @Autowired private RadarMorningMail morningMail;
    @Autowired private ClientEmailRepository clientEmails;
    @Autowired private HostMailAddressRepository mailAddresses;

    @Override
    protected void cleanRadarTables() {
        super.cleanRadarTables();
        clientEmails.deleteAll();
        mailAddresses.deleteAll();
    }

    private org.springframework.test.web.servlet.ResultActions putSchedule(RadarScope scope, String token, String json)
            throws Exception {
        return mockMvc.perform(put(url(scope, "/schedule")).contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private RadarSync eveningSync(RadarScope scope, RadarSyncStatus status, RadarSyncTrigger trigger, OffsetDateTime finishedAt) {
        return syncs.save(RadarSync.builder().userId(scope.userId()).hostId(scope.hostId()).status(status)
                .triggerKind(trigger).startedAt(finishedAt.minusMinutes(30)).finishedAt(finishedAt).build());
    }

    private void verifiedAddress(RadarScope scope, String address) {
        mailAddresses.save(HostMailAddress.builder().userId(scope.userId()).hostId(scope.hostId()).address(address)
                .verifiedAt(OffsetDateTime.now()).build());
    }

    private List<ClientEmail> summaries() {
        return clientEmails.findAll().stream().filter(e -> e.getKind() == ClientEmail.Kind.MORNING_SUMMARY).toList();
    }

    @Test
    @DisplayName("Le réglage : morningEmail lu et écrit ; absent = inchangé ; activer ne renvoie pas la synchro passée")
    void theSetting() throws Exception {
        RadarSync past = eveningSync(aliceA, RadarSyncStatus.SUCCEEDED, RadarSyncTrigger.SCHEDULED,
                OffsetDateTime.now().minusHours(1));

        putSchedule(aliceA, aliceToken, "{\"enabled\":true,\"clientAuthorizationConfirmed\":true,\"morningEmail\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.morningEmail").value(true));
        putSchedule(aliceA, aliceToken, "{\"enabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.morningEmail").value(true));
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().getMorningEmailSyncId())
                .isEqualTo(past.getId());

        assertThat(morningMail.runOnce()).as("la synchro déjà passée ne donne pas de résumé").isZero();

        mockMvc.perform(get(url(aliceB, "/schedule")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.morningEmail").value(false));
        putSchedule(aliceA, bobToken, "{\"enabled\":true,\"morningEmail\":false}")
                .andExpect(status().is4xxClientError());
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().isMorningEmail())
                .isTrue();
    }

    @Test
    @DisplayName("Après la synchro du soir analysée : un seul résumé, vers l'adresse vérifiée du client ; rien pour autrui")
    void oneSummaryPerEveningSync() throws Exception {
        putSchedule(aliceA, aliceToken, "{\"enabled\":true,\"clientAuthorizationConfirmed\":true,\"morningEmail\":true}")
                .andExpect(status().isOk());
        verifiedAddress(aliceA, "alice@edenred.fr");
        RadarSync tonight = eveningSync(aliceA, RadarSyncStatus.SUCCEEDED, RadarSyncTrigger.SCHEDULED,
                OffsetDateTime.now().minusMinutes(20));
        RadarAnalysisBatch batch = analysisBatches.save(RadarAnalysisBatch.builder().userId(aliceA.userId())
                .hostId(aliceA.hostId()).syncId(tonight.getId()).batchKey("k1").status(RadarAnalysisBatchStatus.PENDING)
                .receivedAt(OffsetDateTime.now()).expiresAt(OffsetDateTime.now().plusDays(7)).build());
        // Bob a une synchro, mais pas l'option.
        eveningSync(bobScope, RadarSyncStatus.SUCCEEDED, RadarSyncTrigger.SCHEDULED, OffsetDateTime.now().minusMinutes(20));

        assertThat(morningMail.runOnce()).as("l'analyse tourne encore").isZero();

        batch.setStatus(RadarAnalysisBatchStatus.DONE);
        analysisBatches.save(batch);
        assertThat(morningMail.runOnce()).isEqualTo(1);
        assertThat(morningMail.runOnce()).as("une seule fois par synchro").isZero();

        List<ClientEmail> sent = summaries();
        assertThat(sent).hasSize(1);
        ClientEmail summary = sent.get(0);
        assertThat(summary.getUserId()).isEqualTo(alice.getId());
        assertThat(summary.getHostId()).isEqualTo(aliceA.hostId());
        assertThat(summary.getRecipient()).isEqualTo("alice@edenred.fr");
        assertThat(summary.isRecipientVerified()).isTrue();
        assertThat(summary.getSubject()).isEqualTo("Résumé du matin — EDENRED");
        assertThat(summary.getBodyHtml()).contains("Les compteurs", "Les relances dues", "/vigie/" + aliceA.hostId());
    }

    @Test
    @DisplayName("Rien ne part : sans droit Vigie, synchro manuelle, en échec, ou trop ancienne (notée traitée)")
    void nothingLeavesOutsideTheRules() throws Exception {
        putSchedule(aliceA, aliceToken, "{\"enabled\":true,\"clientAuthorizationConfirmed\":true,\"morningEmail\":true}")
                .andExpect(status().isOk());

        eveningSync(aliceA, RadarSyncStatus.SUCCEEDED, RadarSyncTrigger.MANUAL, OffsetDateTime.now().minusMinutes(5));
        assertThat(morningMail.runOnce()).as("synchro manuelle").isZero();

        RadarSync failed = eveningSync(aliceA, RadarSyncStatus.FAILED, RadarSyncTrigger.SCHEDULED,
                OffsetDateTime.now().minusMinutes(4));
        assertThat(morningMail.runOnce()).as("synchro du soir en échec").isZero();
        syncs.delete(failed);

        RadarSync old = eveningSync(aliceA, RadarSyncStatus.PARTIAL, RadarSyncTrigger.CATCH_UP,
                OffsetDateTime.now().minusHours(20));
        assertThat(morningMail.runOnce()).as("trop ancienne").isZero();
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().getMorningEmailSyncId())
                .isEqualTo(old.getId());

        eveningSync(aliceA, RadarSyncStatus.SUCCEEDED, RadarSyncTrigger.SCHEDULED, OffsetDateTime.now().minusMinutes(2));
        when(teamsAccess.hasAccess(any(UUID.class))).thenReturn(false);
        assertThat(morningMail.runOnce()).as("droit Vigie retiré").isZero();

        when(teamsAccess.hasAccess(any(UUID.class))).thenReturn(true);
        assertThat(morningMail.runOnce()).as("droit rendu : le résumé part, vers l'adresse du compte").isEqualTo(1);
        ClientEmail summary = summaries().get(0);
        assertThat(summary.getRecipient()).isEqualTo("alice-radar@example.com");
        assertThat(summary.isRecipientVerified()).isFalse();
        assertThat(summary.getBodyText()).contains("Aucune adresse vérifiée pour EDENRED");
    }
}
