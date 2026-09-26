package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.atelier.dto.AtelierResumeResponse;
import fr.claudegateway.bilan.BilanTrigger;
import fr.claudegateway.bilan.SessionBilanTriggerService;
import fr.claudegateway.bilan.SessionLedger;
import fr.claudegateway.bilan.SessionSuggestion;
import fr.claudegateway.bilan.SessionSuggestionService;

/**
 * <b>Le bilan arrive jusqu'à l'écran</b> (F-155 / SF-155-07).
 *
 * <p>Ce que ces tests tiennent, et qui est toute la valeur de la subfeature : le nouveau départ ne
 * rend plus seulement le <b>nom</b> du déclencheur, mais le <b>contenu</b> du bilan. Le 2026-09-26,
 * le PO a coupé le contexte d'une session à 152,58 € et n'a rien vu : le bilan était calculé, gardé
 * en base, et l'écran n'en recevait qu'une étiquette qu'il jetait.</p>
 *
 * <p>Deuxième garantie : le <b>nom du projet</b> part avec la décision. Sans lui, tous les bilans
 * gardés depuis SF-155-04 portent une colonne {@code workspace_name} vide — vérifié en production.</p>
 */
class AtelierThreadServiceBilanReportTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final AtelierMessageRepository messageRepository = mock(AtelierMessageRepository.class);
    private final SessionBilanTriggerService bilan = mock(SessionBilanTriggerService.class);
    private final AdminService adminService = mock(AdminService.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private AtelierThreadService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new AtelierThreadService(workspaceService, workspaceRepository, messageRepository);
        service.setBilan(bilan, adminService);
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setName("agenor");
        workspace.setChatThreadStartedAt(OffsetDateTime.parse("2026-09-23T14:22:18Z"));
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(adminService.isAdmin()).thenReturn(true);
    }

    /** Les chiffres réels de la session KPMG, ceux que le PO n'a pas pu lire. */
    private static SessionLedger kpmgLedger() {
        return new SessionLedger(
                OffsetDateTime.parse("2026-09-23T14:22:18Z"),
                OffsetDateTime.parse("2026-09-26T01:27:47Z"),
                113, Duration.ofMinutes(936), 412, 31, 47,
                new BigDecimal("152.58"),
                42_976_876L, 300_000L, 37_085_919L, 5_886_951L,
                86, 0, "claude-opus-5", Duration.ofMinutes(210), List.of(), List.of());
    }

    private static SessionSuggestionService.Verdict verdict() {
        return new SessionSuggestionService.Verdict(List.of(
                new SessionSuggestion(SessionSuggestion.Kind.OUTIL_DOMINANT,
                        SessionSuggestion.Axis.TEMPS,
                        "bash concentre l'attente de la session.",
                        "61 % du temps d'outils sur 412 appels.", 22, null),
                new SessionSuggestion(SessionSuggestion.Kind.ECHECS_REPETES,
                        SessionSuggestion.Axis.COUT,
                        "Le poste a décroché plusieurs fois.",
                        "31 appels d'outils en échec sur 412.", 11,
                        new BigDecimal("9.10"))),
                1);
    }

    private void decides(BilanTrigger trigger, SessionLedger ledger,
                         SessionSuggestionService.Verdict verdict, UUID keptId) {
        when(bilan.decide(eq(userId), eq(workspaceId), any(), anyBoolean(), any(), any()))
                .thenReturn(new SessionBilanTriggerService.Decision(trigger, ledger, verdict, keptId));
    }

    @Test
    @DisplayName("le CONTENU du bilan arrive à l'écran, pas seulement le nom du déclencheur")
    void theContentReachesTheScreen() {
        decides(BilanTrigger.AUTOMATIQUE, kpmgLedger(), verdict(), UUID.randomUUID());

        AtelierResumeResponse.BilanReport report = service.restart(userId, workspaceId).bilanReport();

        assertThat(report).isNotNull();
        assertThat(report.kept()).as("un bilan AUTOMATIQUE est gardé").isTrue();
        assertThat(report.workspaceName()).isEqualTo("agenor");
        assertThat(report.turns()).isEqualTo(113);
        assertThat(report.costEur()).isEqualByComparingTo("152.58");
        assertThat(report.cacheShare()).isEqualTo(86);
        assertThat(report.elapsedMinutes()).isEqualTo(936);
        assertThat(report.toolCalls()).isEqualTo(412);
        assertThat(report.failedTools()).isEqualTo(31);
        assertThat(report.filesWritten()).isEqualTo(47);
        assertThat(report.model()).isEqualTo("claude-opus-5");
        assertThat(report.discarded()).as("les suggestions écartées sont DITES").isEqualTo(1);
    }

    @Test
    @DisplayName("chaque suggestion garde SA MESURE — sans elle, ce n'est qu'un avis")
    void everySuggestionKeepsItsMeasure() {
        decides(BilanTrigger.AUTOMATIQUE, kpmgLedger(), verdict(), UUID.randomUUID());

        List<AtelierResumeResponse.BilanSuggestion> suggestions =
                service.restart(userId, workspaceId).bilanReport().suggestions();

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.get(0).kind()).isEqualTo("OUTIL_DOMINANT");
        assertThat(suggestions.get(0).axis()).isEqualTo("TEMPS");
        assertThat(suggestions.get(0).measure()).contains("61 %");
        assertThat(suggestions.get(0).gainEur()).isNull();
        assertThat(suggestions.get(1).gainEur()).isEqualByComparingTo("9.10");
    }

    @Test
    @DisplayName("un bilan PROPOSÉ est montré mais n'est PAS gardé — d'où le contenu et non un identifiant")
    void aProposedBilanIsShownButNotKept() {
        // C'est le cas qui tranche l'arbitrage : sans identifiant, seul le contenu peut voyager.
        decides(BilanTrigger.PROPOSE, kpmgLedger(), verdict(), null);

        AtelierResumeResponse.BilanReport report = service.restart(userId, workspaceId).bilanReport();

        assertThat(report).isNotNull();
        assertThat(report.kept()).isFalse();
    }

    @Test
    @DisplayName("AUCUN : rien à montrer, et l'écran se comporte exactement comme avant")
    void nothingToShowChangesNothing() {
        decides(BilanTrigger.AUCUN, null, null, null);

        AtelierResumeResponse response = service.restart(userId, workspaceId);

        assertThat(response.bilan()).isEqualTo("AUCUN");
        assertThat(response.bilanReport()).isNull();
    }

    @Test
    @DisplayName("sans bilan branché, aucun rapport — le nouveau départ est celui d'avant")
    void withoutTheBilanNoReport() {
        AtelierThreadService bare = new AtelierThreadService(
                workspaceService, workspaceRepository, messageRepository);

        AtelierResumeResponse response = bare.restart(userId, workspaceId);

        assertThat(response.bilan()).isEqualTo("AUCUN");
        assertThat(response.bilanReport()).isNull();
    }

    @Test
    @DisplayName("LE NOM DU PROJET part avec la décision — la colonne était vide en production")
    void theProjectNameTravels() {
        decides(BilanTrigger.AUTOMATIQUE, kpmgLedger(), verdict(), UUID.randomUUID());

        service.restart(userId, workspaceId);

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(bilan)
                .decide(eq(userId), eq(workspaceId), name.capture(), eq(true), any(), any());
        assertThat(name.getValue()).isEqualTo("agenor");
    }

    @Test
    @DisplayName("un relevé présent sans verdict ne fait pas tomber la reprise")
    void aLedgerWithoutVerdictIsSurvivable() {
        // La garantie « une erreur ne casse pas le geste » vit dans le try/catch du DÉCLENCHEUR
        // (SessionBilanTriggerServiceTest la tient) : elle ne se rejoue pas ici, où le double ne
        // passerait pas par ce catch. Ce qui se vérifie ICI est la projection : une décision
        // incomplète rend `null`, jamais une NullPointerException sur le chemin du nouveau départ.
        decides(BilanTrigger.AUTOMATIQUE, kpmgLedger(), null, UUID.randomUUID());

        AtelierResumeResponse response = service.restart(userId, workspaceId);

        assertThat(response.bilan()).isEqualTo("AUTOMATIQUE");
        assertThat(response.bilanReport()).isNull();
    }
}
