package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.dto.ThreadHandoffResponse;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.RemoteTurnSource;
import fr.claudegateway.atelier.live.RemoteTurnSource.RemoteTurnState;

/** La passation du fil (F-117 / SF-117-09) : lecture, correction et refus. */
class AtelierThreadHandoffServiceTest {

    static final String SUMMARY = """
            ## Objectif
            Livrer la passation.
            ## Fichiers
            —
            ## Décisions et faits établis
            Pas de nouvelle table.
            ## État courant
            En cours.
            ## Prochaines étapes
            Merger.""";

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private WorkspaceService workspaceService;
    private WorkspaceRepository workspaceRepository;
    private LiveTurnRegistry liveTurns;
    private RemoteTurnSource remoteTurns;
    private Workspace workspace;
    private AtelierThreadHandoffService service;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        liveTurns = mock(LiveTurnRegistry.class);
        remoteTurns = mock(RemoteTurnSource.class);
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setChatThreadSummary(SUMMARY);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(liveTurns.find(userId, workspaceId)).thenReturn(Optional.empty());
        when(remoteTurns.findRemoteTurn(userId, workspaceId)).thenReturn(Optional.empty());
        service = new AtelierThreadHandoffService(workspaceService, workspaceRepository, liveTurns,
                remoteTurns);
    }

    @Test
    void litLaPassationDuProprietaire() {
        ThreadHandoffResponse response = service.read(userId, workspaceId);
        assertThat(response.summary()).isEqualTo(SUMMARY);
        assertThat(response.length()).isEqualTo(SUMMARY.length());
    }

    @Test
    void sansResumeLaPassationEstNulle() {
        workspace.setChatThreadSummary("  ");
        ThreadHandoffResponse response = service.read(userId, workspaceId);
        assertThat(response.summary()).isNull();
        assertThat(response.length()).isZero();
    }

    @Test
    void unProjetDAutruiRend404() {
        UUID other = UUID.randomUUID();
        when(workspaceService.requireOwned(other, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));
        assertThatThrownBy(() -> service.read(other, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> service.correct(other, workspaceId, SUMMARY))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void laCorrectionRemplaceLeResumeReinjecte() {
        String corrected = "\n" + SUMMARY.replace("Merger.", "Merger puis déployer.") + "  \n";
        ThreadHandoffResponse response = service.correct(userId, workspaceId, corrected);

        assertThat(response.summary()).isEqualTo(corrected.strip());
        assertThat(workspace.getChatThreadSummary()).isEqualTo(corrected.strip());
        verify(workspaceRepository).save(workspace);
        // Le tour suivant réinjecte le texte corrigé.
        assertThat(AtelierCompactionService.summaryPrefix(workspace).toString())
                .contains("Merger puis déployer.");
    }

    @Test
    void refuseUnTexteVide() {
        assertThatThrownBy(() -> service.correct(userId, workspaceId, "   "))
                .isInstanceOf(InvalidThreadHandoffException.class);
        assertThatThrownBy(() -> service.correct(userId, workspaceId, null))
                .isInstanceOf(InvalidThreadHandoffException.class);
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void refuseUnTexteTropLong() {
        String tooLong = SUMMARY + "x".repeat(AtelierThreadHandoffService.MAX_LENGTH);
        assertThatThrownBy(() -> service.correct(userId, workspaceId, tooLong))
                .isInstanceOf(InvalidThreadHandoffException.class)
                .hasMessageContaining("trop longue");
    }

    @Test
    void refuseUnTexteHorsGabarit() {
        String sansEtapes = SUMMARY.replace("## Prochaines étapes", "## Suite");
        assertThatThrownBy(() -> service.correct(userId, workspaceId, sansEtapes))
                .isInstanceOf(InvalidThreadHandoffException.class)
                .hasMessageContaining("cinq rubriques");
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void refuseDeCreerUnePassationQuiNExistePas() {
        workspace.setChatThreadSummary(null);
        assertThatThrownBy(() -> service.correct(userId, workspaceId, SUMMARY))
                .isInstanceOfSatisfying(ThreadHandoffConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("no_handoff"));
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void refusePendantUnTourVivantLocal() {
        when(liveTurns.find(userId, workspaceId)).thenReturn(Optional.of(mock(LiveTurn.class)));
        assertThatThrownBy(() -> service.correct(userId, workspaceId, SUMMARY))
                .isInstanceOfSatisfying(ThreadHandoffConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("turn_in_progress"));
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void refusePendantUnTourVivantChezUnPair() {
        when(remoteTurns.findRemoteTurn(userId, workspaceId))
                .thenReturn(Optional.of(new RemoteTurnState(UUID.randomUUID(), 0L, 0L, null)));
        assertThatThrownBy(() -> service.correct(userId, workspaceId, SUMMARY))
                .isInstanceOf(ThreadHandoffConflictException.class);
        verify(workspaceRepository, never()).save(any());
    }
}
