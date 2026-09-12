package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.runner.host.HostProjectExistsException;
import fr.claudegateway.runner.host.InvalidProjectPathException;

/**
 * <b>Ouvrir un projet sur un dossier du poste</b> (F-72 / SF-72-01) : la création et le
 * rattachement en un seul geste, le nom pris au dossier, et le refus du doublon.
 *
 * <p>Le doublon est le cœur : c'est lui qui a produit les <b>deux entités nommées EDENRED</b> que
 * le PO a vues en testant.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceServiceHostProjectTest {

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceStorage storage;
    @Mock private AtelierMessageRepository messageRepository;

    private WorkspaceService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(workspaceRepository, storage,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true),
                messageRepository,
                org.mockito.Mockito.mock(fr.claudegateway.runner.audit.RunnerAuditRepository.class),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
        when(workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(userId, hostId)).thenReturn(List.of());
    }

    private Workspace occupying(String path, String name) {
        Workspace existing = new Workspace();
        existing.setId(UUID.randomUUID());
        existing.setUserId(userId);
        existing.setHostId(hostId);
        existing.setName(name);
        existing.setProjectPath(path);
        return existing;
    }

    @Test
    void theProjectTakesTheNameOfItsFolder() {
        Workspace created = service.openOnHost(userId, hostId, "clients/EDENRED", "EDENRED");

        // D5 : le nom n'est demandé qu'UNE fois, à la connexion du poste. Ici, c'est le dossier.
        assertThat(created.getName()).isEqualTo("EDENRED");
        assertThat(created.getHostId()).isEqualTo(hostId);
        assertThat(created.getProjectPath()).isEqualTo("clients/EDENRED");
        assertThat(created.getUserId()).isEqualTo(userId);
    }

    @Test
    void theRootTakesTheNameOfTheHost() {
        Workspace created = service.openOnHost(userId, hostId, "", "Poste CAGIP");

        // Un poste peut n'héberger qu'un projet : la racine est un choix légitime, et elle n'a pas
        // de nom de dossier à donner.
        assertThat(created.getName()).isEqualTo("Poste CAGIP");
        assertThat(created.getProjectPath()).isEmpty();
    }

    @Test
    void aNullPathIsTheRootToo() {
        Workspace created = service.openOnHost(userId, hostId, null, "Poste CAGIP");

        assertThat(created.getName()).isEqualTo("Poste CAGIP");
        assertThat(created.getProjectPath()).isEmpty();
    }

    @Test
    void theProjectIsCreatedForTheRunner() {
        Workspace created = service.openOnHost(userId, hostId, "dev/app", "Poste");

        assertThat(created.sourceOrDefault()).isEqualTo(WorkspaceSource.LOCAL);
        assertThat(created.executionTargetOrDefault()).isEqualTo(WorkspaceExecutionTarget.RUNNER);
        // La porte de confirmation reste armée à la création (F-73 / SF-73-02, ADR-019) : un
        // quatrième chemin de création qui l'oublierait désarmerait la porte sans que ça se voie.
        // SF-73-04 : la porte est DÉSARMÉE par défaut, à titre temporaire (décision du PO du
        // 2026-09-12). Armée, elle rendait toute première commande d'un projet neuf
        // impossible : l'invite d'autorisation ne s'affiche pas, et le tour expirait au bout
        // de 120 s. Ce test rebascule le jour où l'affichage est réparé et la porte réarmée.
        assertThat(created.isAgentAskBeforeBash()).isFalse();
        // Un projet qui vit sur la machine n'alloue rien dans le stockage de la gateway.
        verify(storage, never()).putFile(any(), any(), any());
    }

    @Test
    void thePathIsStoredNormalised() {
        Workspace created = service.openOnHost(userId, hostId, "./clients//EDENRED/", "Poste");

        assertThat(created.getProjectPath()).isEqualTo("clients/EDENRED");
        assertThat(created.getName()).isEqualTo("EDENRED");
    }

    @Test
    void anEscapingPathIsRefusedAndNothingIsCreated() {
        assertThatThrownBy(() -> service.openOnHost(userId, hostId, "../etc", "Poste"))
                .isInstanceOf(InvalidProjectPathException.class);

        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void anAlreadyOpenedFolderIsRefusedByName() {
        when(workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(userId, hostId))
                .thenReturn(List.of(occupying("clients/EDENRED", "EDENRED")));

        assertThatThrownBy(() -> service.openOnHost(userId, hostId, "clients/EDENRED", "Poste"))
                .isInstanceOf(HostProjectExistsException.class)
                .hasMessageContaining("EDENRED");

        // C'est le défaut vécu par le PO : rien ne doit être créé une seconde fois.
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void anAlreadyOpenedRootIsRefusedToo() {
        // Un projet à la racine porte un chemin VIDE en base, parfois nul selon son âge : les deux
        // occupent la racine, et les confondre avec « pas de chemin » rouvrirait le doublon.
        Workspace atRoot = occupying(null, "Poste CAGIP");
        when(workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(userId, hostId)).thenReturn(List.of(atRoot));

        assertThatThrownBy(() -> service.openOnHost(userId, hostId, "", "Poste CAGIP"))
                .isInstanceOf(HostProjectExistsException.class);
    }

    @Test
    void aFolderOpenedOnAnotherPathDoesNotBlock() {
        when(workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(userId, hostId))
                .thenReturn(List.of(occupying("clients/AUTRE", "AUTRE")));

        Workspace created = service.openOnHost(userId, hostId, "clients/EDENRED", "Poste");

        assertThat(created.getName()).isEqualTo("EDENRED");
    }

    @Test
    void anotherAccountsProjectIsNeverConsulted() {
        // Isolation : le contrôle de doublon lit les projets de l'APPELANT seulement. Le projet
        // d'un autre compte au même chemin n'est ni vu, ni un obstacle.
        UUID other = UUID.randomUUID();
        when(workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(other, hostId))
                .thenReturn(List.of(occupying("clients/EDENRED", "EDENRED")));

        Workspace created = service.openOnHost(userId, hostId, "clients/EDENRED", "Poste");

        assertThat(created.getUserId()).isEqualTo(userId);
        verify(workspaceRepository).findByUserIdAndHostIdAndHostTerminalFalse(userId, hostId);
    }

    @Test
    void aVeryLongFolderNameIsTruncatedRatherThanRefused() {
        String longName = "a".repeat(400);

        Workspace created = service.openOnHost(userId, hostId, "dev/" + longName, "Poste");

        // Un dossier au nom très long est un dossier légitime : échouer ici ferait échouer un geste
        // que rien n'oblige à refuser.
        assertThat(created.getName()).hasSize(255);
        assertThat(created.getProjectPath()).isEqualTo("dev/" + longName);
    }
}
