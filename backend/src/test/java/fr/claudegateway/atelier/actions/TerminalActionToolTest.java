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
 * L'agent inscrit le blocage (F-154 / SF-154-02).
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
    @DisplayName("sans le droit d'espace, ni outil ni guide ; avec, les TROIS outils ensemble")
    void theToolExistsOnlyUnderItsGuard() {
        TerminalActionToolCatalog catalog = new TerminalActionToolCatalog(entitlements);

        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(false);
        assertThat(catalog.isOpenFor(userId, workspace)).isFalse();
        assertThat(catalog.toolsFor(userId, workspace)).isEmpty();

        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        assertThat(catalog.isOpenFor(userId, workspace)).isTrue();
        assertThat(catalog.toolsFor(userId, workspace))
                .extracting(AgentTool::name)
                .containsExactly(TerminalActionToolCatalog.RECORD, TerminalActionToolCatalog.UPDATE,
                        TerminalActionToolCatalog.CLOSE);
        assertThat(TerminalActionToolCatalog.isTerminalActionTool("update_blocker")).isTrue();
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
    @DisplayName("le guide dit quand NE PAS inscrire, et de lire la liste avant d'inscrire")
    void theGuideSaysWhenNotToRecord() {
        assertThat(TerminalActionToolCatalog.GUIDE)
                .contains("dépendance HUMAINE")
                .contains("Pas une liste de choses à faire")
                .contains("NE REDEMANDE")
                .contains("REGARDE-LA AVANT D'INSCRIRE")
                .contains("update_blocker");
    }

    @Test
    @DisplayName("SF-175-02 : le guide dit que close_blocker PROPOSE, et jamais sur une supposition")
    void theGuideSaysCloseIsAProposal() {
        assertThat(TerminalActionToolCatalog.GUIDE)
                .contains("close_blocker")
                .contains("tu PROPOSES")
                .contains("LA RAISON, C'EST SA PAROLE")
                .contains("NE FERME JAMAIS SUR UNE SUPPOSITION")
                .contains("cancelled=true");
    }

    // --- La fermeture proposée (SF-175-02, évolution de SF-154-04) ----------------------------

    @Test
    @DisplayName("SF-175-02 : close_blocker PROPOSE — l'attente reste ouverte, la parole est gardée")
    void closeIsAProposalThatKeepsTheActionOpen() {
        TerminalAction open = existing(TerminalActionStatus.A_FAIRE, "acces-vpn-karim");
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "acces-vpn-karim"))
                .thenReturn(Optional.of(open));

        TerminalActionToolExecutor.Outcome outcome = executor.close(userId, workspace, input("""
                {"key":"acces-vpn-karim","reason":"Karim a ouvert l'accès ce matin."}"""));

        assertThat(outcome.error()).isFalse();
        assertThat(open.getStatus()).isEqualTo(TerminalActionStatus.A_FAIRE); // toujours ouverte
        assertThat(open.getProposedStatus()).isEqualTo(TerminalActionStatus.FAIT);
        assertThat(open.getProposedReason()).isEqualTo("Karim a ouvert l'accès ce matin.");
        assertThat(outcome.content()).contains("PROPOSÉE").contains("reste OUVERTE")
                .contains("Ne dis pas qu'elle est fermée");
    }

    @Test
    @DisplayName("SF-175-02 : [Confirmer] applique la proposition ; [Pas encore] l'écarte")
    void confirmAndDismiss() {
        TerminalAction open = existing(TerminalActionStatus.DEMANDE, "acces-vpn-karim");
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "acces-vpn-karim"))
                .thenReturn(Optional.of(open));
        when(repository.findByIdAndUserIdAndWorkspaceId(open.getId(), userId, workspaceId))
                .thenReturn(Optional.of(open));

        executor.close(userId, workspace, input("""
                {"key":"acces-vpn-karim","reason":"On passe par le bastion.","cancelled":true}"""));
        service.dismissProposal(userId, workspaceId, open.getId());
        assertThat(open.hasProposal()).isFalse();
        assertThat(open.getStatus()).isEqualTo(TerminalActionStatus.DEMANDE);

        executor.close(userId, workspace, input("""
                {"key":"acces-vpn-karim","reason":"On passe par le bastion.","cancelled":true}"""));
        service.confirmProposal(userId, workspaceId, open.getId());
        assertThat(open.getStatus()).isEqualTo(TerminalActionStatus.ANNULE);
        assertThat(open.getClosedReason()).isEqualTo("On passe par le bastion.");
        assertThat(open.hasProposal()).isFalse();
    }

    @Test
    @DisplayName("SF-175-02 : update_blocker fait passer à « Demandé », à qui et par où")
    void updateMarksRequested() {
        TerminalAction open = existing(TerminalActionStatus.A_FAIRE, "acces-vpn-karim");
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "acces-vpn-karim"))
                .thenReturn(Optional.of(open));
        when(repository.findByIdAndUserIdAndWorkspaceId(open.getId(), userId, workspaceId))
                .thenReturn(Optional.of(open));

        TerminalActionToolExecutor.Outcome outcome = executor.update(userId, workspace, input("""
                {"key":"acces-vpn-karim","status":"DEMANDE","requested_to":"Karim","channel":"Teams"}"""));

        assertThat(outcome.error()).isFalse();
        assertThat(open.getStatus()).isEqualTo(TerminalActionStatus.DEMANDE);
        assertThat(open.getRequestedTo()).isEqualTo("Karim");
        assertThat(outcome.content()).contains("Demandé").contains("ne redemande pas");

        assertThat(executor.update(userId, workspace, input("""
                {"key":"acces-vpn-karim","status":"FAIT"}""")).error()).isTrue();
    }

    @Test
    @DisplayName("SF-175-02 : par id, une attente d'un AUTRE poste est introuvable")
    void anIdFromAnotherHostIsUnknown() {
        UUID hostId = UUID.randomUUID();
        workspace.setHostId(hostId);
        TerminalAction foreign = existing(TerminalActionStatus.A_FAIRE, "k");
        foreign.setWorkspaceId(UUID.randomUUID());
        foreign.setHostId(UUID.randomUUID());
        when(repository.findByIdAndUserId(foreign.getId(), userId)).thenReturn(Optional.of(foreign));

        TerminalActionToolExecutor.Outcome outcome = executor.close(userId, workspace,
                input("{\"id\":\"" + foreign.getId() + "\"}"));
        assertThat(outcome.content()).contains("Aucune attente");
        assertThat(foreign.hasProposal()).isFalse();

        foreign.setHostId(hostId); // même poste : trouvée
        executor.close(userId, workspace, input("{\"id\":\"" + foreign.getId() + "\"}"));
        assertThat(foreign.hasProposal()).isTrue();
    }

    @Test
    @DisplayName("clé inconnue : on le dit, on n'écrit rien, et l'agent n'insiste pas")
    void anUnknownKeySaysSo() {
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "inconnue"))
                .thenReturn(Optional.empty());

        TerminalActionToolExecutor.Outcome outcome = executor.close(userId, workspace, input("""
                {"key":"inconnue"}"""));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("Aucune attente").contains("N'insiste pas");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("une attente déjà fermée garde sa raison d'origine — rien n'est réécrit")
    void anAlreadyClosedActionKeepsItsOriginalReason() {
        TerminalAction done = existing(TerminalActionStatus.FAIT, "acces-vpn-karim");
        done.setClosedReason("la première raison");
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "acces-vpn-karim"))
                .thenReturn(Optional.of(done));

        TerminalActionToolExecutor.Outcome outcome = executor.close(userId, workspace, input("""
                {"key":"acces-vpn-karim","reason":"une autre raison"}"""));

        assertThat(outcome.content()).contains("était déjà fermée").contains("Rien n'a changé");
        assertThat(done.getClosedReason()).isEqualTo("la première raison");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("sans clé, et base en panne : résultat en ERREUR, jamais une exception")
    void closingFailuresAreToolResults() {
        assertThat(executor.close(userId, workspace, input("{}")).error()).isTrue();

        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(any(), any(), any()))
                .thenThrow(new IllegalStateException("base HS"));
        TerminalActionToolExecutor.Outcome outage = executor.close(userId, workspace, input("""
                {"key":"acces-vpn-karim"}"""));
        assertThat(outage.error()).isTrue();
        assertThat(outage.content()).contains("n'a pas pu être proposée");
    }

    @Test
    @DisplayName("ISOLATION — la proposition porte sur le compte et le projet DU TOUR")
    void closingNeverReadsAnIdentifierFromTheInput() {
        UUID intruder = UUID.randomUUID();
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "k"))
                .thenReturn(Optional.empty());

        executor.close(userId, workspace, input("""
                {"key":"k","user_id":"%s","workspace_id":"%s"}""".formatted(intruder, UUID.randomUUID())));

        verify(repository).findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "k");
        verify(repository, never()).findByUserIdAndWorkspaceIdAndDedupKey(eq(intruder), any(), any());
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
        assertThat(saved.getValue().getStatus()).isEqualTo(TerminalActionStatus.A_FAIRE);
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
                .thenReturn(Optional.of(existing(TerminalActionStatus.A_FAIRE, "acces-vpn-karim")));

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
                .thenReturn(Optional.of(existing(TerminalActionStatus.ANNULE, "acces-vpn-karim")));

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
                .thenReturn(Optional.of(existing(TerminalActionStatus.FAIT, "acces-vpn-karim")));

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
        when(repository.countByUserIdAndWorkspaceIdAndStatusIn(userId, workspaceId,
                TerminalActionStatus.OPEN_STATES)).thenReturn(TerminalActionService.MAX_OPEN_PER_WORKSPACE);
        assertThat(executor.execute(userId, workspace, input("""
                {"description":"encore une"}""")).content()).contains("actions ouvertes");

        when(repository.countByUserIdAndWorkspaceIdAndStatusIn(any(), any(), any()))
                .thenThrow(new IllegalStateException("base HS"));
        TerminalActionToolExecutor.Outcome outage = executor.execute(userId, workspace, input("""
                {"description":"demander l'accès"}"""));
        assertThat(outage.error()).isTrue();
        assertThat(outage.content()).contains("n'a pas pu être inscrite");
    }

    // --- Les cartes du fil (F-175 / SF-175-05) ------------------------------------------------

    @Test
    @DisplayName("SF-175-05 : inscrite → carte ADDED ; déjà là → ALREADY (avec la manière) ; annulée → aucune")
    void recordingCards() {
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "neuve"))
                .thenReturn(Optional.empty());
        TerminalActionToolExecutor.Outcome added = executor.execute(userId, workspace,
                input("{\"description\":\"Obtenir le VPN\",\"key\":\"neuve\"}"));
        assertThat(added.card().kind()).isEqualTo(AttenteBlock.ADDED);
        assertThat(added.card().description()).isEqualTo("Obtenir le VPN");

        TerminalAction asked = existing(TerminalActionStatus.DEMANDE, "deja");
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "deja"))
                .thenReturn(Optional.of(asked));
        TerminalActionToolExecutor.Outcome already = executor.execute(userId, workspace,
                input("{\"description\":\"x\",\"key\":\"deja\"}"));
        assertThat(already.card().kind()).isEqualTo(AttenteBlock.ALREADY);
        assertThat(already.card().match()).isEqualTo("KEY");
        assertThat(already.card().status()).isEqualTo(TerminalActionStatus.DEMANDE);

        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "refusee"))
                .thenReturn(Optional.of(existing(TerminalActionStatus.ANNULE, "refusee")));
        assertThat(executor.execute(userId, workspace, input("{\"description\":\"x\",\"key\":\"refusee\"}"))
                .card()).isNull();
    }

    @Test
    @DisplayName("SF-175-05 : proposition → carte PROPOSED ; passage à Demandé → carte REQUESTED")
    void proposalAndRequestCards() {
        TerminalAction open = existing(TerminalActionStatus.A_FAIRE, "k");
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "k")).thenReturn(Optional.of(open));
        when(repository.findByIdAndUserIdAndWorkspaceId(open.getId(), userId, workspaceId))
                .thenReturn(Optional.of(open));

        TerminalActionToolExecutor.Outcome requested = executor.update(userId, workspace,
                input("{\"key\":\"k\",\"status\":\"DEMANDE\",\"requested_to\":\"Zahi\"}"));
        assertThat(requested.card().kind()).isEqualTo(AttenteBlock.REQUESTED);
        assertThat(requested.card().requestedTo()).isEqualTo("Zahi");

        TerminalActionToolExecutor.Outcome proposed = executor.close(userId, workspace,
                input("{\"key\":\"k\",\"reason\":\"Zahi a créé le compte\"}"));
        assertThat(proposed.card().kind()).isEqualTo(AttenteBlock.PROPOSED);
        assertThat(proposed.card().proposedStatus()).isEqualTo(TerminalActionStatus.FAIT);
        assertThat(proposed.card().proposedReason()).isEqualTo("Zahi a créé le compte");
        assertThat(proposed.card().workspaceId()).isEqualTo(workspaceId);
    }

    // --- Déjà demandé (F-175 / SF-175-03) -----------------------------------------------------

    @Test
    @DisplayName("SF-175-03 : la même clé OUVERTE dans un autre terminal du poste n'est pas réinscrite")
    void theSameKeyElsewhereOnTheHostIsRecognised() {
        UUID hostId = UUID.randomUUID();
        workspace.setHostId(hostId);
        TerminalAction elsewhere = existing(TerminalActionStatus.DEMANDE, "compte-forge-capfm");
        elsewhere.setWorkspaceId(UUID.randomUUID());
        elsewhere.setHostId(hostId);
        elsewhere.setRequestedTo("Zahi");
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "compte-forge-capfm"))
                .thenReturn(Optional.empty());
        when(repository.findByUserIdAndHostIdAndDedupKeyOrderByCreatedAtDesc(userId, hostId, "compte-forge-capfm"))
                .thenReturn(java.util.List.of(elsewhere));

        TerminalActionToolExecutor.Outcome outcome = executor.execute(userId, workspace, input("""
                {"description":"Demander le compte forge CAPFM","key":"compte-forge-capfm"}"""));

        assertThat(outcome.content()).contains("DÉJÀ SUR LE POSTE").contains("DÉJÀ DEMANDÉ")
                .contains("demandé à Zahi").contains("En attente depuis");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("SF-175-03 : une clé FERMÉE ailleurs sur le poste n'empêche pas une nouvelle demande")
    void aClosedKeyElsewhereDoesNotBlock() {
        UUID hostId = UUID.randomUUID();
        workspace.setHostId(hostId);
        TerminalAction done = existing(TerminalActionStatus.FAIT, "k");
        done.setWorkspaceId(UUID.randomUUID());
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, "k")).thenReturn(Optional.empty());
        when(repository.findByUserIdAndHostIdAndDedupKeyOrderByCreatedAtDesc(userId, hostId, "k"))
                .thenReturn(java.util.List.of(done));

        TerminalActionToolExecutor.Outcome outcome = executor.execute(userId, workspace, input("""
                {"description":"Refaire la demande","key":"k"}"""));

        assertThat(outcome.content()).contains("Action inscrite");
        verify(repository).save(any());
    }

    @Test
    @DisplayName("SF-175-03 : la même demande DITE AUTREMENT (par le sens) n'est pas réinscrite")
    void theSameRequestSaidOtherwiseIsRecognised() {
        UUID hostId = UUID.randomUUID();
        workspace.setHostId(hostId);
        TerminalAction similar = existing(TerminalActionStatus.A_FAIRE, "compte-forge-capfm");
        similar.setHostId(hostId);
        TerminalActionSemanticDedup dedup = mock(TerminalActionSemanticDedup.class);
        when(dedup.findSimilarOpen(userId, hostId, workspaceId, "Obtenir l'accès à la forge pour CAPFM"))
                .thenReturn(Optional.of(new TerminalActionSemanticDedup.Match(similar.getId(), 0.08)));
        when(repository.findByIdAndUserId(similar.getId(), userId)).thenReturn(Optional.of(similar));
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findByUserIdAndHostIdAndDedupKeyOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(java.util.List.of());
        service.setSemanticDedup(dedup);

        TerminalActionToolExecutor.Outcome outcome = executor.execute(userId, workspace, input("""
                {"description":"Obtenir l'accès à la forge pour CAPFM","key":"acces-forge"}"""));

        assertThat(outcome.content()).contains("QUI DIT LA MÊME CHOSE").contains("attend déjà");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("SF-175-03 : rien de proche → inscrite, et son vecteur est demandé")
    void nothingSimilarRecordsAndEmbeds() {
        TerminalActionSemanticDedup dedup = mock(TerminalActionSemanticDedup.class);
        when(dedup.findSimilarOpen(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            TerminalAction a = invocation.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        service.setSemanticDedup(dedup);

        executor.execute(userId, workspace, input("{\"description\":\"Valider le budget avec Habib\"}"));

        verify(dedup).embedAsync(any(UUID.class), eq("Valider le budget avec Habib"));
    }
}
