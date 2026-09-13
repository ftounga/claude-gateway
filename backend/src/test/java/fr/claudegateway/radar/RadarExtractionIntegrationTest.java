package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.radar.RadarRegistry.SummarySentence;
import fr.claudegateway.radar.analysis.RadarAnalysisBatch;
import fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus;
import fr.claudegateway.radar.analysis.RadarAnalysisIntake;
import fr.claudegateway.radar.analysis.RadarAnalysisQueue;
import fr.claudegateway.radar.analysis.RadarExchangeBatch;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * F-101 / SF-101-03 — <b>de bout en bout</b> : un lot déposé, trié, extrait, écrit au registre par la
 * file ; tout fait sourcé dans un message lu ; une sortie illisible n'écrit rien ; un poste ne voit
 * jamais un autre poste.
 */
class RadarExtractionIntegrationTest extends RadarIntegrationTestBase {

    @MockitoBean private AIProvider aiProvider;
    @MockitoBean private TeamsAccessService teamsAccess;

    @Autowired private RadarAnalysisIntake intake;
    @Autowired private RadarAnalysisQueue queue;
    @Autowired private RadarSyncRepository syncRepository;

    private static final OffsetDateTime AT = OffsetDateTime.now().minusHours(3).withNano(0);

    private final List<ChatCompletionRequest> requests = new ArrayList<>();
    private String triageAnswer;
    private String extractionAnswer;

    @BeforeEach
    void provider() {
        requests.clear();
        when(teamsAccess.hasAccess(any(UUID.class))).thenReturn(true);
        when(aiProvider.complete(any())).thenAnswer(invocation -> {
            ChatCompletionRequest request = invocation.getArgument(0);
            requests.add(request);
            boolean triage = request.system().contains("===TRI===");
            return new ChatCompletionResult(triage ? triageAnswer : extractionAnswer, "m",
                    triage ? 200 : 3000, triage ? 20 : 400, 0, 0);
        });
    }

    private static RadarExchangeBatch.Message msg(String ref, String author, String key, boolean me, String text,
            int minutes) {
        return new RadarExchangeBatch.Message(ref, AT.plusMinutes(minutes), key, author, null, me, text,
                "https://teams.microsoft.com/l/message/" + ref);
    }

    private RadarExchangeBatch batch(String key) {
        return new RadarExchangeBatch(key, List.of(
                new RadarExchangeBatch.Exchange(RadarEvidenceSource.TEAMS_MESSAGE, "19:mfa", "MFA presta", null, List.of(
                        msg(key + "-1", "Marc Durand", "marc@client.fr", false,
                                "La double auth des presta est bloquée tant que la licence n'est pas signée.", 0),
                        msg(key + "-2", "Léa Martin", "lea@client.fr", false, "Je valide le pilote, on démarre le 2 octobre.", 1))),
                new RadarExchangeBatch.Exchange(RadarEvidenceSource.TEAMS_MESSAGE, "19:cafe", "Café", null, List.of(
                        msg(key + "-3", null, null, true, "Merci à tous !", 2)))));
    }

    private UUID submit(RadarScope scope, String key) {
        RadarSync sync = registry.startSync(scope);
        return intake.submit(scope, sync.getId(), batch(key)).batchId();
    }

    private RadarAnalysisBatch reload(UUID id) {
        return analysisBatches.findById(id).orElseThrow();
    }

    /** Un sujet suivi, avec une phrase de résumé sourcée, et une valeur d'état corrigée par l'utilisateur. */
    private RadarSubject seedMfa(RadarScope scope) {
        RadarEvidence old = proof(scope, "On lance un pilote MFA.", AT.minusDays(5));
        RadarSubject mfa = registry.createSubject(scope, "Pilote MFA", RadarSubjectState.ADVANCING, ids(old));
        registry.replaceSummary(scope, mfa.getId(), List.of(new SummarySentence("Un pilote est lancé.", ids(old))));
        return mfa;
    }

    @Test
    @DisplayName("Tri puis extraction : sujet rattaché et enrichi, sujet créé, preuves tirées des messages, brut effacé")
    void endToEnd() {
        RadarSubject mfa = seedMfa(aliceA);
        seedMfa(aliceB); // l'autre poste d'Alice : ne doit jamais apparaître
        UUID batchId = submit(aliceA, "lot-1");
        triageAnswer = "===TRI===\n{\"retenus\": [\"E1\"]}";
        extractionAnswer = """
                Le premier message parle du MFA sous un autre nom.
                ===RADAR===
                {"sujets": [
                  {"sujet": "S1", "preuves": ["M1", "M2"], "alias": ["la double auth des presta"],
                   "etat": {"valeur": "bloque", "preuves": ["M1"]},
                   "prochaine_etape": {"texte": "Signer la licence", "preuves": ["M1"]},
                   "echeance": {"date": "2026-10-02", "preuves": ["M2"]},
                   "resume": [{"reprise": "S1.1"}, {"phrase": "Bloqué par la licence non signée.", "preuves": ["M1"]}],
                   "roles": [{"personne": "P2", "role": "decide", "preuves": ["M2"]}],
                   "citations": {"M1": "bloquée tant que la licence n'est pas signée", "M2": "phrase inventée"}},
                  {"sujet": "nouveau", "nom": "Licences prestataires", "preuves": ["M1"]}
                ]}
                """;

        assertThat(queue.runOnce()).isEqualTo(1);

        RadarAnalysisBatch done = reload(batchId);
        assertThat(done.getStatus()).isEqualTo(RadarAnalysisBatchStatus.DONE);
        assertThat(done.getPayload()).isNull();
        assertThat(done.getRetainedCount()).isEqualTo(1);
        assertThat(done.getSubjectsAttached()).isEqualTo(1);
        assertThat(done.getSubjectsCreated()).isEqualTo(1);
        assertThat(done.getTriageInputTokens()).isEqualTo(200);
        assertThat(done.getExtractionInputTokens()).isEqualTo(3000);
        assertThat(syncRepository.findById(done.getSyncId()).orElseThrow().getConsumedTokens()).isEqualTo(3620);

        RadarSubject updated = subjects.findById(mfa.getId()).orElseThrow();
        assertThat(updated.getState()).isEqualTo(RadarSubjectState.BLOCKED);
        assertThat(updated.getNextStep()).isEqualTo("Signer la licence");
        assertThat(updated.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(aliases.findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtAsc(aliceA.userId(), aliceA.hostId(), mfa.getId()))
                .extracting(RadarSubjectAlias::getAlias).containsExactly("la double auth des presta");
        assertThat(facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(aliceA.userId(), aliceA.hostId(), mfa.getId()))
                .extracting(RadarSubjectFact::getText)
                .containsExactly("Un pilote est lancé.", "Bloqué par la licence non signée.");

        List<RadarEvidence> proofs = evidence.findAll().stream()
                .filter(e -> e.getHostId().equals(aliceA.hostId()) && e.getSourceRef().startsWith("lot-1")).toList();
        assertThat(proofs).extracting(RadarEvidence::getQuote).containsExactlyInAnyOrder(
                "bloquée tant que la licence n'est pas signée",
                "Je valide le pilote, on démarre le 2 octobre.");
        assertThat(proofs).allMatch(e -> e.getDeepLink().startsWith("https://teams.microsoft.com/l/message/"));
        assertThat(people.findByUserIdAndHostIdOrderByDisplayNameAsc(aliceA.userId(), aliceA.hostId()))
                .extracting(RadarPerson::getDisplayName).containsExactly("Léa Martin", "Marc Durand");
        assertThat(roles.findByUserIdAndHostId(aliceA.userId(), aliceA.hostId())).singleElement()
                .extracting(RadarSubjectRole::getRole).isEqualTo(RadarRole.DECIDES);
        assertThat(subjects.findByUserIdAndHostId(aliceA.userId(), aliceA.hostId()))
                .extracting(RadarSubject::getName).containsExactlyInAnyOrder("Pilote MFA", "Licences prestataires");
        RadarSubject created = subjects.findByUserIdAndHostId(aliceA.userId(), aliceA.hostId()).stream()
                .filter(s -> s.getName().equals("Licences prestataires")).findFirst().orElseThrow();
        assertThat(created.getState()).isEqualTo(RadarSubjectState.NEW);

        // Ce que le modèle a vu : le seul registre du poste A, en consigne mise en cache.
        ChatCompletionRequest extraction = requests.get(1);
        assertThat(extraction.cacheSystem()).isTrue();
        assertThat(requests.get(0).cacheSystem()).isFalse();
        assertThat(extraction.system()).contains("S1 — Pilote MFA").doesNotContain("S2 —");
        assertThat(extraction.messages().get(0).content()).contains("[M1]").contains("[M2]").doesNotContain("Merci à tous");
        // Rien n'a touché le poste B.
        assertThat(subjects.findByUserIdAndHostId(aliceB.userId(), aliceB.hostId())).singleElement()
                .extracting(RadarSubject::getState).isEqualTo(RadarSubjectState.ADVANCING);
    }

    @Test
    @DisplayName("Une sortie illisible n'écrit rien : le lot est retenté, son brut conservé")
    void unreadableWritesNothing() {
        RadarSubject mfa = seedMfa(aliceA);
        UUID batchId = submit(aliceA, "lot-1");
        triageAnswer = "===TRI===\n{\"retenus\": [\"E1\"]}";
        extractionAnswer = """
                ===RADAR===
                {"sujets": [
                  {"sujet": "S1", "preuves": ["M1"], "etat": {"valeur": "bloque", "preuves": ["M1"]}},
                  {"sujet": "S2", "preuves": ["M1"]}
                ]}
                """;
        long evidenceBefore = evidence.count();

        queue.runOnce();

        RadarAnalysisBatch retried = reload(batchId);
        assertThat(retried.getStatus()).isEqualTo(RadarAnalysisBatchStatus.PENDING);
        assertThat(retried.getFailureCode()).isEqualTo("EXTRACTION_UNREADABLE");
        assertThat(retried.getPayload()).isNotNull();
        assertThat(retried.getExtractionInputTokens()).isEqualTo(3000);
        assertThat(subjects.findById(mfa.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.ADVANCING);
        assertThat(evidence.count()).isEqualTo(evidenceBefore);
    }

    @Test
    @DisplayName("Tri sans échange retenu : DONE sans écriture ni extraction ; tri illisible : retenté")
    void nothingRetained() {
        UUID batchId = submit(aliceA, "lot-1");
        triageAnswer = "===TRI===\n{\"retenus\": []}";
        queue.runOnce();
        assertThat(reload(batchId).getStatus()).isEqualTo(RadarAnalysisBatchStatus.DONE);
        assertThat(reload(batchId).getPayload()).isNull();
        assertThat(requests).hasSize(1);
        assertThat(subjects.count()).isZero();

        UUID second = submit(aliceA, "lot-2");
        triageAnswer = "Je ne sais pas.";
        queue.runOnce();
        assertThat(reload(second).getStatus()).isEqualTo(RadarAnalysisBatchStatus.PENDING);
        assertThat(reload(second).getFailureCode()).isEqualTo("TRIAGE_UNREADABLE");
    }

    @Test
    @DisplayName("Un état corrigé par l'utilisateur n'est pas réécrit ; un sujet clos cité se réveille sans rouvrir")
    void sovereigntyAndWake() {
        RadarSubject mfa = seedMfa(aliceA);
        correctionService.correctSubject(aliceA, mfa.getId(), new fr.claudegateway.radar.dto.RadarCorrectionRequests.SubjectCorrectionRequest(
                RadarCorrectionAction.SET_STATE, null, RadarSubjectState.WAITING, null, null));
        RadarEvidence oldProof = proof(aliceA, "Audit terminé.", AT.minusDays(30));
        RadarSubject audit = registry.createSubject(aliceA, "Audit 2025", null, ids(oldProof));
        closureService.close(aliceA, audit.getId());
        RadarSubject closed = subjects.findById(audit.getId()).orElseThrow();
        closed.setClosedAt(AT.minusDays(1)); // clos avant les messages du lot
        subjects.save(closed);
        submit(aliceA, "lot-1");
        triageAnswer = "===TRI===\n{\"retenus\": [\"E1\"]}";
        extractionAnswer = """
                ===RADAR===
                {"sujets": [
                  {"sujet": "S1", "preuves": ["M1"], "etat": {"valeur": "bloque", "preuves": ["M1"]}},
                  {"sujet": "S2", "preuves": ["M2"]}
                ]}
                """;

        queue.runOnce();

        assertThat(subjects.findById(mfa.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.WAITING);
        RadarSubject woke = subjects.findById(audit.getId()).orElseThrow();
        assertThat(woke.getState()).isEqualTo(RadarSubjectState.CLOSED);
        assertThat(woke.getWokeAt()).isNotNull();
    }

    @Test
    @DisplayName("Sans droit : le lot est reporté, aucun appel au fournisseur")
    void noEntitlement() {
        when(teamsAccess.hasAccess(any(UUID.class))).thenReturn(false);
        UUID batchId = submit(bobScope, "lot-1");

        queue.runOnce();

        assertThat(reload(batchId).getStatus()).isEqualTo(RadarAnalysisBatchStatus.DEFERRED);
        assertThat(reload(batchId).getFailureCode()).isEqualTo("NO_ENTITLEMENT");
        verify(aiProvider, never()).complete(any());
    }

    @Autowired private RadarCorrectionService correctionService;
    @Autowired private RadarClosureService closureService;
}
