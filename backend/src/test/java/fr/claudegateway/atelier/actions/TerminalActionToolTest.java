package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;

/**
 * L'agent inscrit le blocage (F-151 / SF-151-02).
 *
 * <p>Ce que ces tests tiennent : l'outil n'existe que sous sa garde, le même blocage ne fait pas
 * deux lignes, une action <b>annulée</b> n'est jamais recréée, et toute erreur est un
 * <b>résultat d'outil</b> — le tour continue.</p>
 */
class TerminalActionToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TerminalActionRepository repository = mock(TerminalActionRepository.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final SpaceEntitlementService entitlements = mock(SpaceEntitlementService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private Workspace workspace;
    private TerminalActionService service;
    private TerminalActionToolExecutor executor;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(workspaceId);
        when(workspaces.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new TerminalActionService(repository, workspaces, clock);
        executor = new TerminalActionToolExecutor(service);
    }

    private JsonNode input(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TerminalAction existing(TerminalActionStatus status, String key) {
        return TerminalAction.builder()
                .id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .description("Demander l'accès VPN à Karim")
                .kind(TerminalActionKind.MESSAGE).status(status).dedupKey(key)
                .createdAt(clock.instant().atOffset(ZoneOffset.UTC))
                .updatedAt(clock.instant().atOffset(ZoneOffset.UTC))
                .build();
    }

    // --- La garde -----------------------------------------------------------------------------

    @Test
    @DisplayName("sans le droit d'espace, ni outil ni guide ; avec, les deux")
    void theToolExistsOnlyUnderItsGuard() {
        TerminalActionToolCatalog catalog = new TerminalActionToolCatalog(entitlements);

        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(false);
        assertThat(catalog.isOpenFor(userId, workspace)).isFalse();
        assertThat(catalog.toolsFor(userId, workspace)).isEmpty();

        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        assertThat(catalog.isOpenFor(userId, workspace)).isTrue();
        assertThat(catalog.toolsFor(userId, workspace))
                .extracting(AgentTool::name)
                .containsExactly(TerminalActionToolCatalog.RECORD);
    }

    @Test
    @DisplayName("un abonnement illisible ferme la garde, il ne l'ouvre pas")
    void anUnreadableSubscriptionClosesTheGuard() {
        TerminalActionToolCatalog catalog = new TerminalActionToolCatalog(entitlements);
        when(entitlements.isEntitled(any(UUID.class), any(EntitlementSpace.class)))
                .thenThrow(new IllegalStateException("base HS"));
        assertThat(catalog.isOpenFor(userId, workspace)).isFalse();

        assertThat(TerminalActionToolCatalog.none().isOpenFor(userId, workspace)).isFalse();
    }

    @Test
    @DisplayName("le guide dit quand NE PAS inscrire — c'est ce qui protège la liste")
    void theGuideSaysWhenNotToRecord() {
        assertThat(TerminalActionToolCatalog.GUIDE)
                .contains("dépendance HUMAINE")
                .contains("Pas une liste de choses à faire")
                .contains("NE REDEMANDE");
    }

    // --- L'inscription ------------------------------------------------------------------------

    @Test
    @DisplayName("un appel nominal inscrit une action ouverte, sur le projet DU TOUR")
    void recordsAnOpenAction() {
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "acces-vpn-karim"))
                .thenReturn(Optional.empty());

        TerminalActionToolExecutor.Outcome outcome = executor.execute(userId, workspace, input("""
                {"description":"Demander l'accès VPN à Karim",
                 "blocks":"le déploiement du connecteur",
                 "person":"Karim","kind":"MESSAGE","key":"acces-vpn-karim"}"""));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("Action inscrite").contains("Continue ton tour");

        ArgumentCaptor<TerminalAction> saved = ArgumentCaptor.forClass(TerminalAction.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(saved.getValue().getDedupKey()).isEqualTo("acces-vpn-karim");
        assertThat(saved.getValue().getStatus()).isEqualTo(TerminalActionStatus.OPEN);
    }

    @Test
    @DisplayName("ISOLATION — le projet et le compte écrits sont ceux DU TOUR, pas ceux des paramètres")
    void neverReadsAnIdentifierFromTheToolInput() {
        UUID intruder = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();

        executor.execute(userId, workspace, input("""
                {"description":"Demander l'accès","user_id":"%s","workspace_id":"%s","userId":"%s"}"""
                .formatted(intruder, otherWorkspace, intruder)));

        ArgumentCaptor<TerminalAction> saved = ArgumentCaptor.forClass(TerminalAction.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(workspaceId);
        verify(workspaces).requireOwned(userId, workspaceId);
        verify(workspaces, never()).requireOwned(eq(intruder), any());
    }

    // --- Le dédoublonnage ---------------------------------------------------------------------

    @Test
    @DisplayName("le même blocage rappelé ne fait pas une seconde ligne")
    void theSameBlockerDoesNotMakeTwoLines() {
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "acces-vpn-karim"))
                .thenReturn(Optional.of(existing(TerminalActionStatus.OPEN, "acces-vpn-karim")));

        TerminalActionToolExecutor.Outcome outcome = executor.execute(userId, workspace, input("""
                {"description":"Demander l'accès VPN à Karim","key":"acces-vpn-karim"}"""));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("attend déjà").contains("n'en refais pas la demande");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("une action ANNULÉE par l'utilisateur n'est jamais recréée — sa parole prime")
    void aCancelledActionIsNeverRecreated() {
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "acces-vpn-karim"))
                .thenReturn(Optional.of(existing(TerminalActionStatus.CANCELLED, "acces-vpn-karim")));

        TerminalActionToolExecutor.Outcome outcome = executor.execute(userId, workspace, input("""
                {"description":"Demander l'accès VPN à Karim","key":"acces-vpn-karim"}"""));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("ANNULÉ").contains("Ne la redemande pas");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("une action déjà faite le dit, et invite à chercher une autre cause")
    void anAlreadyDoneActionSaysSo() {
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "acces-vpn-karim"))
                .thenReturn(Optional.of(existing(TerminalActionStatus.DONE, "acces-vpn-karim")));

        assertThat(executor.execute(userId, workspace, input("""
                {"description":"Demander l'accès VPN à Karim","key":"acces-vpn-karim"}""")).content())
                .contains("déjà été faite").contains("une autre cause");
    }

    @Test
    @DisplayName("sans clé, elle est dérivée de l'énoncé — jamais vide, sinon rien ne dédoublonne")
    void theKeyIsDerivedWhenAbsent() {
        assertThat(TerminalActionService.normalizeKey("Demander l'accès VPN à Karim !"))
                .isEqualTo("demander-l-acces-vpn-a-karim");
        assertThat(TerminalActionService.normalizeKey("   ***   ")).isEqualTo("action");
        assertThat(TerminalActionService.normalizeKey("x".repeat(400)))
                .hasSize(TerminalActionService.MAX_KEY);
    }

    // --- Les erreurs, qui ne tuent jamais le tour ----------------------------------------------

    @Test
    @DisplayName("énoncé vide, kind inconnu, borne dépassée : résultat en ERREUR, jamais une exception")
    void everyFailureIsAToolResult() {
        assertThat(executor.execute(userId, workspace, input("""
                {"description":"  "}""")).error()).isTrue();

        assertThat(executor.execute(userId, workspace, input("""
                {"description":"faire","kind":"URGENT"}""")).content()).contains("ACTION ou MESSAGE");

        TerminalActionToolExecutor.Outcome tooLong = executor.execute(userId, workspace, input("""
                {"description":"%s"}""".formatted("x".repeat(TerminalActionService.MAX_DESCRIPTION + 1))));
        assertThat(tooLong.error()).isTrue();
        assertThat(tooLong.action()).isNull();
    }

    @Test
    @DisplayName("la liste saturée le dit ; la base en panne aussi — le tour continue dans les deux cas")
    void saturationAndOutageAreToldNotThrown() {
        when(repository.countByUserIdAndWorkspaceIdAndStatus(userId, workspaceId,
                TerminalActionStatus.OPEN)).thenReturn(TerminalActionService.MAX_OPEN_PER_WORKSPACE);
        assertThat(executor.execute(userId, workspace, input("""
                {"description":"encore une"}""")).content()).contains("actions ouvertes");

        when(repository.countByUserIdAndWorkspaceIdAndStatus(any(), any(), any()))
                .thenThrow(new IllegalStateException("base HS"));
        TerminalActionToolExecutor.Outcome outage = executor.execute(userId, workspace, input("""
                {"description":"demander l'accès"}"""));
        assertThat(outage.error()).isTrue();
        assertThat(outage.content()).contains("n'a pas pu être inscrite");
    }
}
