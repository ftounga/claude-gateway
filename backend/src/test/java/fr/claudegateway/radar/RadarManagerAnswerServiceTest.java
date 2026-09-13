package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.radar.dto.RadarSubjectPageViews.AskView;
import fr.claudegateway.radar.dto.RadarSubjectPageViews.ManagerAnswerView;
import fr.claudegateway.radar.dto.RadarSubjectPageViews.UnknownKind;
import fr.claudegateway.radar.dto.RadarSubjectPageViews.UnknownView;
import fr.claudegateway.radar.dto.RadarViews.CommitmentView;
import fr.claudegateway.radar.dto.RadarViews.PersonRef;
import fr.claudegateway.radar.dto.RadarViews.RoleView;
import fr.claudegateway.radar.dto.RadarViews.SentenceView;
import fr.claudegateway.radar.dto.RadarViews.SubjectDetail;

/** F-103 / SF-103-03 — la réponse au manager : matière, appel, forme, décompte, refus avant appel. */
class RadarManagerAnswerServiceTest {

    private final RadarScope scope = new RadarScope(UUID.randomUUID(), UUID.randomUUID());
    private final UUID subjectId = UUID.randomUUID();

    private RadarReadService readService;
    private RadarUnknownsService unknownsService;
    private AIProvider aiProvider;
    private ByokKeyService byok;
    private QuotaService quota;
    private RadarManagerAnswerService service;

    private SubjectDetail subject;
    private List<UnknownView> unknowns;

    @BeforeEach
    void setUp() {
        readService = mock(RadarReadService.class);
        unknownsService = mock(RadarUnknownsService.class);
        aiProvider = mock(AIProvider.class);
        ModelCatalog catalog = mock(ModelCatalog.class);
        when(catalog.fastModel()).thenReturn("modele-rapide");
        byok = mock(ByokKeyService.class);
        when(byok.resolveActiveApiKey(scope.userId())).thenReturn(Optional.empty());
        quota = mock(QuotaService.class);
        service = new RadarManagerAnswerService(readService, unknownsService, aiProvider, catalog, byok, quota,
                Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC));

        subject = detail(null);
        unknowns = new ArrayList<>(List.of(new UnknownView(UnknownKind.DUE_DATE, "Aucune échéance n'est connue.",
                new AskView(UUID.randomUUID(), "Sophie Laurent", null, RadarRole.DRIVES, "pilote le sujet"),
                List.of(), null)));
        when(readService.subject(scope, subjectId)).thenAnswer(i -> subject);
        when(unknownsService.unknowns(eq(scope), any(SubjectDetail.class))).thenAnswer(i -> unknowns);
        when(unknownsService.zone(scope)).thenReturn(ZoneId.of("Europe/Paris"));
    }

    private SubjectDetail detail(UUID mergedInto) {
        PersonRef paul = new PersonRef(UUID.randomUUID(), "Paul Martin");
        CommitmentView open = new CommitmentView(UUID.randomUUID(), subjectId, "MFA", RadarCommitmentDirection.OTHER_TO_ME,
                "Valider le périmètre", paul, null, null, LocalDate.of(2026, 9, 20), true, RadarCommitmentStatus.OPEN,
                RadarCertainty.PROBABLE, false, false, List.of(), null, null, false, null, null);
        CommitmentView kept = new CommitmentView(UUID.randomUUID(), subjectId, "MFA", RadarCommitmentDirection.ME_TO_OTHER,
                "Envoyer la note tenue", null, null, null, null, false, RadarCommitmentStatus.KEPT,
                RadarCertainty.CERTAIN, false, false, List.of(), null, null, false, null, null);
        return new SubjectDetail(subjectId, "MFA prestataires", RadarSubjectState.ADVANCING, "Lancer le pilote", null,
                null, null, mergedInto, null, null, List.of(), null, null, null, List.of(), false, false, false, false,
                List.of(), List.of(), List.of(), List.of(),
                List.of(new SentenceView(UUID.randomUUID(), 0, "Paul a validé le périmètre de 340 comptes.", List.of())),
                List.of(new RoleView(UUID.randomUUID(), paul.id(), "Paul Martin", "Manager sécurité", RadarRole.DECIDES, List.of())),
                List.of(open, kept), List.of());
    }

    private void answers(String content) {
        when(aiProvider.complete(any())).thenReturn(new ChatCompletionResult(content, "m", 900, 120, 0, 0));
    }

    @Test
    @DisplayName("nominal : la matière du seul sujet, la consigne, le modèle rapide, la réponse après le marqueur")
    void preparesTheAnswer() {
        answers("Je réfléchis.\n===REPONSE===\n  Le périmètre MFA est validé ; je demande l'échéance à Sophie.  ");

        ManagerAnswerView view = service.prepare(scope, subjectId);

        assertThat(view.text()).isEqualTo("Le périmètre MFA est validé ; je demande l'échéance à Sophie.");
        assertThat(view.unknownsCount()).isEqualTo(1);
        assertThat(view.coverageIncomplete()).isFalse();
        ArgumentCaptor<ChatCompletionRequest> request = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(request.capture());
        assertThat(request.getValue().model()).isEqualTo("modele-rapide");
        assertThat(request.getValue().maxTokens()).isEqualTo(RadarManagerAnswerService.MAX_TOKENS);
        assertThat(request.getValue().system()).isEqualTo(RadarManagerAnswerService.CONSIGNE);
        assertThat(request.getValue().apiKey()).isNull();
        String material = request.getValue().messages().get(0).content();
        assertThat(material)
                .contains("SUJET : MFA prestataires", "ÉTAT : avance", "PROCHAINE ÉTAPE : Lancer le pilote",
                        "ÉCHÉANCE : aucune connue", "- Paul a validé le périmètre de 340 comptes.",
                        "- attendu de Paul Martin : Valider le périmètre ; échéance 20 septembre 2026 (déduite) ; probable",
                        "- Paul Martin (Manager sécurité) : décide",
                        "- Aucune échéance n'est connue. À demander à Sophie Laurent, qui pilote le sujet.")
                .doesNotContain("Envoyer la note tenue");
        verify(quota).assertWithinQuota(scope.userId());
        verify(quota).recordUsage(eq(scope.userId()), any(TurnTokens.class), isNull(), isNull(), eq(scope.hostId()));
    }

    @Test
    @DisplayName("BYOK : la clé de l'utilisateur sert l'appel")
    void byokKey() {
        when(byok.resolveActiveApiKey(scope.userId())).thenReturn(Optional.of("sk-client"));
        answers("===REPONSE===\nOK.");

        service.prepare(scope, subjectId);

        ArgumentCaptor<ChatCompletionRequest> request = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(request.capture());
        assertThat(request.getValue().apiKey()).isEqualTo("sk-client");
    }

    @Test
    @DisplayName("couverture incomplète : dite dans la matière et dans la réponse rendue")
    void coverage() {
        unknowns.add(0, new UnknownView(UnknownKind.COVERAGE, "La dernière synchro n'a pas tout lu.", null, List.of(), null));
        answers("===REPONSE===\nOK.");

        assertThat(service.prepare(scope, subjectId).coverageIncomplete()).isTrue();
    }

    @Test
    @DisplayName("sortie illisible : 502, et les jetons consommés sont quand même décomptés")
    void unreadable() {
        answers("Voici la réponse sans marqueur.");

        assertThatThrownBy(() -> service.prepare(scope, subjectId)).isInstanceOf(RadarAnswerUnreadableException.class);
        verify(quota).recordUsage(eq(scope.userId()), any(TurnTokens.class), isNull(), isNull(), eq(scope.hostId()));
    }

    @Test
    @DisplayName("la forme : dernier marqueur, vide refusé, trop long refusé")
    void answerForm() {
        assertThat(RadarManagerAnswerService.answerOf("a ===REPONSE=== faux ===REPONSE===\nvrai")).isEqualTo("vrai");
        assertThat(RadarManagerAnswerService.answerOf("===REPONSE===\n   ")).isNull();
        assertThat(RadarManagerAnswerService.answerOf(null)).isNull();
        assertThat(RadarManagerAnswerService.answerOf("===REPONSE===" + "x".repeat(1_501))).isNull();
        assertThat(RadarManagerAnswerService.answerOf("===REPONSE===" + "x".repeat(1_500))).hasSize(1_500);
    }

    @Test
    @DisplayName("quota atteint : refus avant tout appel au fournisseur")
    void quotaRefusedBeforeCall() {
        doThrow(new QuotaExceededException("Quota atteint.")).when(quota).assertWithinQuota(scope.userId());

        assertThatThrownBy(() -> service.prepare(scope, subjectId)).isInstanceOf(QuotaExceededException.class);
        verify(aiProvider, never()).complete(any());
    }

    @Test
    @DisplayName("sujet fusionné ou d'ailleurs : refus avant tout appel, sans décompte")
    void refusedSubjects() {
        subject = detail(UUID.randomUUID());
        assertThatThrownBy(() -> service.prepare(scope, subjectId)).isInstanceOf(RadarSubjectMergedException.class);

        UUID elsewhere = UUID.randomUUID();
        when(readService.subject(scope, elsewhere)).thenThrow(new RadarNotFoundException("Sujet introuvable."));
        assertThatThrownBy(() -> service.prepare(scope, elsewhere)).isInstanceOf(RadarNotFoundException.class);

        verify(aiProvider, never()).complete(any());
        verify(quota, never()).assertWithinQuota(any());
    }

    @Test
    @DisplayName("les textes de la matière sont bornés et mis à plat")
    void boundedMaterial() {
        SubjectDetail long_ = new SubjectDetail(subjectId, "Nom\nsur deux lignes", RadarSubjectState.NEW, "x".repeat(900),
                null, null, null, null, null, null, List.of(), null, null, null, List.of(), false, false, false, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        String material = RadarManagerAnswerService.material(long_, List.of(), ZoneId.of("Europe/Paris"));

        assertThat(material).contains("SUJET : Nom sur deux lignes", "x".repeat(500) + "…", "- (pas encore de résumé)",
                "- (aucun)", "- (aucune)", "- (rien de signalé)");
        assertThat(material).doesNotContain("x".repeat(501));
    }
}
