package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.analysis.RadarAnalysisBatch;
import fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus;
import fr.claudegateway.radar.analysis.RadarAnalysisIntake;
import fr.claudegateway.radar.analysis.RadarAnalysisIntake.IntakeReceipt;
import fr.claudegateway.radar.analysis.RadarAnalysisLease;
import fr.claudegateway.radar.analysis.RadarAnalysisOutcome;
import fr.claudegateway.radar.analysis.RadarAnalysisProperties;
import fr.claudegateway.radar.analysis.RadarAnalysisQueue;
import fr.claudegateway.radar.analysis.RadarAnalysisTokens;
import fr.claudegateway.radar.analysis.RadarBatchAnalyzer;
import fr.claudegateway.radar.analysis.RadarExchangeBatch;

/**
 * F-101 / SF-101-01 — <b>la file d'analyse</b> : idempotente, un poste à la fois, le brut effacé avec
 * l'écriture des faits ou pas du tout, rien de perdu en silence.
 */
class RadarAnalysisQueueIntegrationTest extends RadarIntegrationTestBase {

    @Autowired private RadarAnalysisIntake intake;
    @Autowired private RadarSyncRepository syncRepository;
    @Autowired private RadarAnalysisProperties properties;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    private static final OffsetDateTime AT = OffsetDateTime.now().minusHours(2).withNano(0);

    private RadarAnalysisQueue queue(RadarBatchAnalyzer analyzer) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (analyzer != null) {
            factory.addBean("analyzer", analyzer);
        }
        return new RadarAnalysisQueue(analysisBatches, analysisLeases, syncRepository,
                factory.getBeanProvider(RadarBatchAnalyzer.class), properties, objectMapper, transactionManager,
                clock);
    }

    private static RadarExchangeBatch batch(String key, String... texts) {
        List<RadarExchangeBatch.Message> messages = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            messages.add(new RadarExchangeBatch.Message(key + "/m" + i, AT.plusMinutes(i), "marc@client.fr",
                    "Marc Durand", null, false, texts[i], null));
        }
        return new RadarExchangeBatch(key, List.of(new RadarExchangeBatch.Exchange(
                fr.claudegateway.radar.RadarEvidenceSource.TEAMS_MESSAGE, "19:abc", "MFA", null, messages)));
    }

    private RadarAnalysisBatch reload(UUID id) {
        return analysisBatches.findById(id).orElseThrow();
    }

    /** Rend un lot à nouveau prenable, comme si son échéance était passée. */
    private void makeDue(UUID id) {
        RadarAnalysisBatch b = reload(id);
        b.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1));
        analysisBatches.save(b);
    }

    @Test
    @DisplayName("Un même lot déposé deux fois : une ligne ; un lot échoué redéposé repart")
    void intakeIsIdempotent() {
        RadarSync sync = registry.startSync(aliceA);
        IntakeReceipt first = intake.submit(aliceA, sync.getId(), batch("lot-1", "Je m'en charge."));
        IntakeReceipt second = intake.submit(aliceA, sync.getId(), batch("lot-1", "Autre texte."));

        assertThat(first.duplicate()).isFalse();
        assertThat(first.status()).isEqualTo(RadarAnalysisBatchStatus.PENDING);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.batchId()).isEqualTo(first.batchId());
        assertThat(analysisBatches.count()).isEqualTo(1);
        assertThat(reload(first.batchId()).getPayload()).contains("Je m'en charge.");
        assertThat(reload(first.batchId()).getExpiresAt()).isAfter(OffsetDateTime.now().plusDays(6));

        RadarAnalysisBatch failed = reload(first.batchId());
        failed.setStatus(RadarAnalysisBatchStatus.FAILED);
        failed.setAttempts(3);
        analysisBatches.save(failed);
        IntakeReceipt again = intake.submit(aliceA, sync.getId(), batch("lot-1", "Nouveau texte."));
        assertThat(again.duplicate()).isFalse();
        assertThat(reload(first.batchId()).getStatus()).isEqualTo(RadarAnalysisBatchStatus.PENDING);
        assertThat(reload(first.batchId()).getAttempts()).isZero();
        assertThat(reload(first.batchId()).getPayload()).contains("Nouveau texte.");
    }

    @Test
    @DisplayName("Un lot ne vise jamais la synchro d'un autre poste ; une synchro annulée ne reçoit rien")
    void intakeIsScoped() {
        RadarSync otherHost = registry.startSync(aliceB);
        RadarSync bobs = registry.startSync(bobScope);
        assertThatThrownBy(() -> intake.submit(aliceA, otherHost.getId(), batch("lot-x", "a")))
                .isInstanceOf(RadarNotFoundException.class);
        assertThatThrownBy(() -> intake.submit(aliceA, bobs.getId(), batch("lot-x", "a")))
                .isInstanceOf(RadarNotFoundException.class);

        RadarSync cancelled = registry.startSync(aliceA);
        registry.finishSync(aliceA, cancelled.getId(), RadarSyncStatus.CANCELLED, null, 0);
        assertThatThrownBy(() -> intake.submit(aliceA, cancelled.getId(), batch("lot-y", "a")))
                .isInstanceOf(RadarStateConflictException.class);
        assertThat(analysisBatches.count()).isZero();
    }

    @Test
    @DisplayName("Sans analyseur déclaré, aucun lot n'est pris")
    void noAnalyzerTakesNothing() {
        RadarSync sync = registry.startSync(aliceA);
        IntakeReceipt receipt = intake.submit(aliceA, sync.getId(), batch("lot-1", "a"));

        assertThat(queue(null).runOnce()).isZero();
        assertThat(reload(receipt.batchId()).getStatus()).isEqualTo(RadarAnalysisBatchStatus.PENDING);
        assertThat(reload(receipt.batchId()).getAttempts()).isZero();
    }

    @Test
    @DisplayName("DONE : les faits sont écrits et le brut effacé dans la même transaction ; la consommation est cumulée")
    void doneWritesAndDeletesRaw() {
        RadarSync sync = registry.startSync(aliceA);
        IntakeReceipt receipt = intake.submit(aliceA, sync.getId(), batch("lot-1", "Le pilote MFA démarre."));
        RadarBatchAnalyzer analyzer = (scope, syncId, batchId, b) -> RadarAnalysisOutcome.done(
                new RadarAnalysisTokens(100, 10, 1000, 200, 50, 5), 1, 0, 1,
                () -> registry.createSubject(scope, "Pilote MFA", null, ids(proof(scope, "Le pilote MFA démarre."))));

        assertThat(queue(analyzer).runOnce()).isEqualTo(1);

        RadarAnalysisBatch done = reload(receipt.batchId());
        assertThat(done.getStatus()).isEqualTo(RadarAnalysisBatchStatus.DONE);
        assertThat(done.getPayload()).isNull();
        assertThat(done.getRawDeletedAt()).isNotNull();
        assertThat(done.getSubjectsCreated()).isEqualTo(1);
        assertThat(done.tokens().total()).isEqualTo(1365);
        assertThat(subjects.findByUserIdAndHostId(aliceA.userId(), aliceA.hostId())).hasSize(1);
        assertThat(syncRepository.findById(sync.getId()).orElseThrow().getConsumedTokens()).isEqualTo(1365);

        // La fin de la collecte ne baisse pas la consommation cumulée par l'analyse.
        registry.finishSync(aliceA, sync.getId(), RadarSyncStatus.SUCCEEDED, null, 10);
        assertThat(syncRepository.findById(sync.getId()).orElseThrow().getConsumedTokens()).isEqualTo(1365);
    }

    @Test
    @DisplayName("Écriture refusée par le registre : rien n'est écrit, le brut reste, le lot sera retenté")
    void rejectedWriteKeepsRaw() {
        RadarSync sync = registry.startSync(aliceA);
        IntakeReceipt receipt = intake.submit(aliceA, sync.getId(), batch("lot-1", "a"));
        RadarBatchAnalyzer analyzer = (scope, syncId, batchId, b) -> RadarAnalysisOutcome.done(
                new RadarAnalysisTokens(0, 0, 10, 1, 0, 0), 1, 0, 1, () -> {
                    registry.upsertPerson(scope, "marc@client.fr", "Marc", null);
                    registry.createSubject(scope, "Sans preuve", null, List.of()); // refusé : pas de fait sans preuve
                });

        queue(analyzer).runOnce();

        RadarAnalysisBatch retried = reload(receipt.batchId());
        assertThat(retried.getStatus()).isEqualTo(RadarAnalysisBatchStatus.PENDING);
        assertThat(retried.getFailureCode()).isEqualTo("WRITE_REJECTED");
        assertThat(retried.getPayload()).isNotNull();
        assertThat(retried.getNextAttemptAt()).isAfter(OffsetDateTime.now());
        assertThat(retried.tokens().total()).isEqualTo(11);
        assertThat(people.findByUserIdAndHostIdOrderByDisplayNameAsc(aliceA.userId(), aliceA.hostId())).isEmpty();
    }

    @Test
    @DisplayName("F-89 / SF-89-06 : transcription au téléchargement bloqué — le drapeau traverse la file, "
            + "l'analyse le voit, et un abandon efface le brut tout de suite")
    void blockedTranscriptIsNeverKeptWhole() {
        RadarSync sync = registry.startSync(aliceA);
        List<RadarExchangeBatch.Message> messages = List.of(new RadarExchangeBatch.Message("mtg/1", AT,
                "paul@client.fr", "Paul Durand", null, false, "Je m'occupe de la partie MFA.", null));
        RadarExchangeBatch blocked = new RadarExchangeBatch("lot-bloque", List.of(new RadarExchangeBatch.Exchange(
                fr.claudegateway.radar.RadarEvidenceSource.TEAMS_MEETING, "MTG-1", "Comité", null, messages, true)));
        IntakeReceipt receipt = intake.submit(aliceA, sync.getId(), blocked);
        java.util.concurrent.atomic.AtomicBoolean seenBlocked = new java.util.concurrent.atomic.AtomicBoolean();
        RadarAnalysisQueue failing = queue((scope, syncId, batchId, b) -> {
            seenBlocked.set(b.downloadBlocked() && b.exchanges().get(0).blocked());
            throw new IllegalStateException("fournisseur en panne");
        });

        failing.runOnce();
        assertThat(seenBlocked).isTrue();
        assertThat(reload(receipt.batchId()).getPayload()).isNotNull(); // en attente d'une nouvelle tentative
        makeDue(receipt.batchId());
        failing.runOnce();
        makeDue(receipt.batchId());
        failing.runOnce();

        RadarAnalysisBatch failed = reload(receipt.batchId());
        assertThat(failed.getStatus()).isEqualTo(RadarAnalysisBatchStatus.FAILED);
        assertThat(failed.getPayload()).isNull();
        assertThat(failed.getRawDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Trois échecs : FAILED, brut conservé ; un report ne consomme pas de tentative")
    void retriesThenFails() {
        RadarSync sync = registry.startSync(aliceA);
        IntakeReceipt receipt = intake.submit(aliceA, sync.getId(), batch("lot-1", "a"));
        RadarAnalysisQueue failing = queue((scope, syncId, batchId, b) -> {
            throw new IllegalStateException("fournisseur en panne");
        });

        failing.runOnce();
        assertThat(reload(receipt.batchId()).getStatus()).isEqualTo(RadarAnalysisBatchStatus.PENDING);
        assertThat(failing.runOnce()).isZero(); // pas encore échu
        makeDue(receipt.batchId());
        failing.runOnce();
        makeDue(receipt.batchId());
        failing.runOnce();

        RadarAnalysisBatch failed = reload(receipt.batchId());
        assertThat(failed.getStatus()).isEqualTo(RadarAnalysisBatchStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getFailureCode()).isEqualTo("ANALYZER_ERROR");
        assertThat(failed.getPayload()).isNotNull();

        IntakeReceipt deferredReceipt = intake.submit(aliceA, sync.getId(), batch("lot-2", "b"));
        queue((scope, syncId, batchId, b) -> RadarAnalysisOutcome.defer(RadarAnalysisTokens.NONE, "RESERVE_EXHAUSTED",
                null)).runOnce();
        RadarAnalysisBatch deferred = reload(deferredReceipt.batchId());
        assertThat(deferred.getStatus()).isEqualTo(RadarAnalysisBatchStatus.DEFERRED);
        assertThat(deferred.getAttempts()).isZero();
        assertThat(deferred.getFailureCode()).isEqualTo("RESERVE_EXHAUSTED");
        assertThat(deferred.getNextAttemptAt()).isAfter(OffsetDateTime.now().plusMinutes(30));
    }

    @Test
    @DisplayName("Les lots d'un poste passent dans l'ordre de réception ; un bail tenu ailleurs bloque le poste")
    void orderAndLease() {
        RadarSync sync = registry.startSync(aliceA);
        UUID first = intake.submit(aliceA, sync.getId(), batch("lot-a", "a")).batchId();
        UUID second = intake.submit(aliceA, sync.getId(), batch("lot-b", "b")).batchId();
        List<String> seen = new ArrayList<>();
        RadarBatchAnalyzer recording = (scope, syncId, batchId, b) -> {
            seen.add(b.batchKey());
            return RadarAnalysisOutcome.done(RadarAnalysisTokens.NONE, 0, 0, 0, null);
        };

        analysisLeases.save(RadarAnalysisLease.builder().userId(aliceA.userId()).hostId(aliceA.hostId())
                .owner("un-autre-pod").leasedUntil(OffsetDateTime.now().plusMinutes(10)).build());
        assertThat(queue(recording).runOnce()).isZero();
        assertThat(seen).isEmpty();

        analysisLeases.deleteAll();
        assertThat(queue(recording).runOnce()).isEqualTo(2);
        assertThat(seen).containsExactly("lot-a", "lot-b");
        assertThat(reload(first).getStatus()).isEqualTo(RadarAnalysisBatchStatus.DONE);
        assertThat(reload(second).getStatus()).isEqualTo(RadarAnalysisBatchStatus.DONE);
        // Le bail est rendu : un autre passage peut reprendre le poste.
        assertThat(analysisLeases.findAll()).allMatch(l -> l.getLeasedUntil().isBefore(OffsetDateTime.now()));
    }

    @Test
    @DisplayName("Au terme de la rétention, le brut disparaît et le lot jamais analysé est EXPIRED")
    void expiry() {
        RadarSync sync = registry.startSync(aliceA);
        UUID pending = intake.submit(aliceA, sync.getId(), batch("lot-old", "a")).batchId();
        UUID fresh = intake.submit(aliceA, sync.getId(), batch("lot-new", "b")).batchId();
        RadarAnalysisBatch old = reload(pending);
        old.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        analysisBatches.save(old);

        assertThat(queue(null).expireRaw()).isEqualTo(1);
        assertThat(reload(pending).getStatus()).isEqualTo(RadarAnalysisBatchStatus.EXPIRED);
        assertThat(reload(pending).getPayload()).isNull();
        assertThat(reload(fresh).getPayload()).isNotNull();
        // Un lot expiré n'est plus jamais pris.
        AtomicInteger calls = new AtomicInteger();
        queue((scope, syncId, batchId, b) -> {
            calls.incrementAndGet();
            return RadarAnalysisOutcome.done(RadarAnalysisTokens.NONE, 0, 0, 0, null);
        }).runOnce();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Isolation : la purge d'un poste n'efface que ses lots ; GET /syncs ne compte que les siens")
    void isolation() throws Exception {
        RadarSync syncA = registry.startSync(aliceA);
        RadarSync syncB = registry.startSync(aliceB);
        RadarSync syncBob = registry.startSync(bobScope);
        intake.submit(aliceA, syncA.getId(), batch("lot-1", "a", "b"));
        intake.submit(aliceB, syncB.getId(), batch("lot-1", "c"));
        intake.submit(bobScope, syncBob.getId(), batch("lot-1", "d"));

        mockMvc.perform(get(url(aliceA, "/syncs")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].analysis.batches.PENDING").value(1))
                .andExpect(jsonPath("$[0].analysis.batches.DONE").value(0))
                .andExpect(jsonPath("$[0].analysis.messages").value(2))
                .andExpect(jsonPath("$[0].analysis.exchanges").value(1));

        mockMvc.perform(get(url(aliceA, "/syncs")).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        purgeService.purge(aliceA, RadarPurgeReason.USER_REQUEST);
        assertThat(analysisBatches.findAll()).extracting(RadarAnalysisBatch::getHostId)
                .containsExactlyInAnyOrder(aliceB.hostId(), bobScope.hostId());
    }

    @Autowired private RadarPurgeService purgeService;
}
