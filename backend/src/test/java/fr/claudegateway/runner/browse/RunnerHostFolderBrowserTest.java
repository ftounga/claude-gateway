package fr.claudegateway.runner.browse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.host.InvalidProjectPathException;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostNotFoundException;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.runner.host.dto.HostFoldersResponse;
import fr.claudegateway.runner.host.dto.HostFoldersResponse.HostFolder;

/**
 * Les sous-dossiers d'un poste (F-71 / SF-71-02) : ce qu'on <b>clique</b> au lieu de le taper.
 *
 * <p>Ce qui est vérifié ici tient en trois questions : <b>quels dossiers</b> la machine expose,
 * <b>lesquels sont déjà pris</b>, et surtout <b>ce qui est dit</b> quand la machine n'est pas
 * joignable — le cas où une liste vide serait un mensonge.</p>
 */
@ExtendWith(MockitoExtension.class)
class RunnerHostFolderBrowserTest {

    @Mock private RunnerToolGateway gateway;
    @Mock private RunnerAuditService auditService;
    @Mock private RunnerHostService hostService;
    @Mock private WorkspaceService workspaceService;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private RunnerHostFolderBrowser browser() {
        return new RunnerHostFolderBrowser(gateway, auditService, hostService, workspaceService);
    }

    private void owned() {
        when(hostService.requireOwned(alice, hostId))
                .thenReturn(RunnerHost.builder().id(hostId).userId(alice).name("Poste").build());
    }

    private void listing(String content, boolean truncated) {
        when(gateway.listFiles(any(), any())).thenReturn(
                new RunnerCallResult(true, content, truncated, null, 5L, null, null, null, "",
                        false));
    }

    private void refuses(String errorCode, String message) {
        when(gateway.listFiles(any(), any()))
                .thenReturn(RunnerCallResult.backendError(errorCode, message));
    }

    private Workspace project(String path) {
        return Workspace.builder().id(UUID.randomUUID()).userId(alice).name(path).hostId(hostId)
                .projectPath(path).build();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void derivesTheImmediateSubfoldersOfTheRoot() {
        owned();
        listing(String.join("\n",
                "README.md",
                "clients/EDENRED/pom.xml",
                "clients/CAGIP/src/Main.java",
                "perso/notes.txt",
                "clients/EDENRED/src/App.java"), false);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());

        HostFoldersResponse response = browser().folders(alice, hostId, null);

        // Deux dossiers, sans doublon, triés — et jamais le fichier de la racine.
        assertThat(response.folders()).extracting(HostFolder::name)
                .containsExactly("clients", "perso");
        assertThat(response.folders()).extracting(HostFolder::path)
                .containsExactly("clients", "perso");
        assertThat(response.path()).isEmpty();
        assertThat(response.parentPath()).isNull();
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void navigatesIntoASubfolderAndKnowsHowToGoBack() {
        owned();
        listing("EDENRED/pom.xml\nCAGIP/build.gradle", false);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());

        HostFoldersResponse response = browser().folders(alice, hostId, "clients");

        assertThat(response.folders()).extracting(HostFolder::path)
                .containsExactly("clients/CAGIP", "clients/EDENRED");
        assertThat(response.path()).isEqualTo("clients");
        assertThat(response.parentPath()).isEmpty();
        // Le confinement part au runner : c'est LUI qui referme la garde sur le dossier visité.
        ArgumentCaptor<RunnerTarget> target = ArgumentCaptor.forClass(RunnerTarget.class);
        verify(gateway).listFiles(target.capture(), any());
        assertThat(target.getValue().projectPath()).isEqualTo("clients");
        assertThat(target.getValue().hostId()).isEqualTo(hostId);
        // Aucun projet n'est concerné : un appel de POSTE ne porte pas de projet.
        assertThat(target.getValue().workspaceId()).isNull();
    }

    @Test
    void aFolderAlreadyOpenedIsMarked() {
        // Le défaut vécu : ouvrir deux fois le même dossier a produit deux entités du même nom.
        owned();
        listing("EDENRED/pom.xml\nCAGIP/pom.xml", false);
        when(workspaceService.listByHost(alice, hostId))
                .thenReturn(List.of(project("clients/EDENRED")));

        List<HostFolder> folders = browser().folders(alice, hostId, "clients").folders();

        assertThat(folders).extracting(HostFolder::name).containsExactly("CAGIP", "EDENRED");
        assertThat(folders).extracting(HostFolder::used).containsExactly(false, true);
    }

    @Test
    void hiddenFoldersAreNotProposed() {
        // .git et .claude sont de l'outillage, jamais un projet : les proposer ferait cliquer dessus.
        owned();
        listing(".git/config\n.claude/skills/x.md\napi/pom.xml", false);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());

        assertThat(browser().folders(alice, hostId, "").folders())
                .extracting(HostFolder::name).containsExactly("api");
    }

    @Test
    void aRootWithOnlyFilesIsAnHonestEmptyList() {
        owned();
        listing("README.md\npom.xml", false);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());

        HostFoldersResponse response = browser().folders(alice, hostId, "");

        assertThat(response.folders()).isEmpty();
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void aTruncatedListingSaysSo() {
        // SF-38-21 : une liste incomplète se DIT. Un dossier manquant en silence, c'est dix minutes
        // à chercher ce que le système savait ne pas avoir envoyé.
        owned();
        listing("api/pom.xml", true);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());

        assertThat(browser().folders(alice, hostId, "").truncated()).isTrue();
    }

    @Test
    void tooManyFoldersAreCappedAndSaid() {
        owned();
        String many = IntStream.range(0, RunnerHostFolderBrowser.MAX_FOLDERS + 10)
                .mapToObj(i -> String.format("dossier-%04d/fichier.txt", i))
                .collect(Collectors.joining("\n"));
        listing(many, false);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());

        HostFoldersResponse response = browser().folders(alice, hostId, "");

        assertThat(response.folders()).hasSize(RunnerHostFolderBrowser.MAX_FOLDERS);
        assertThat(response.truncated()).isTrue();
    }

    @Test
    void anOfflineRunnerIsSaidAndNeverAnEmptyList() {
        // LE cas du PO : sans machine, on ne peut pas lister — et on le dit, au lieu d'offrir un
        // champ vide (ou pire, une liste vide qui ferait croire à une racine sans sous-dossier).
        owned();
        refuses(RunnerErrorCodes.RUNNER_UNAVAILABLE, null);

        assertThatThrownBy(() -> browser().folders(alice, hostId, ""))
                .isInstanceOf(RunnerBrowseException.class)
                .hasMessageContaining("n'est pas connecté");
    }

    @Test
    void aRunnerOnAnotherNodeIsAlsoSaid() {
        owned();
        refuses(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE, null);

        assertThatThrownBy(() -> browser().folders(alice, hostId, ""))
                .isInstanceOf(RunnerBrowseException.class)
                .hasMessageContaining("n'est pas connecté");
    }

    @Test
    void aRefusalFromTheMachineKeepsItsOwnMessage() {
        // « Dossier introuvable » et « machine éteinte » appellent deux gestes différents.
        owned();
        refuses("not_found", "Dossier de projet introuvable : clients/EDENRED");

        assertThatThrownBy(() -> browser().folders(alice, hostId, "clients/EDENRED"))
                .isInstanceOf(RunnerBrowseException.class)
                .hasMessageContaining("Dossier de projet introuvable");
    }

    @Test
    void everyReadIsJournaledUnderItsOwnToolName() {
        owned();
        listing("api/pom.xml", false);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());

        browser().folders(alice, hostId, "");

        verify(auditService).recordCall(eq(alice), any(), any(),
                eq(RunnerHostFolderBrowser.SCREEN_LIST_FOLDERS), eq("la racine"), any());
    }

    @Test
    void aRefusedReadIsJournaledToo() {
        owned();
        refuses(RunnerErrorCodes.RUNNER_UNAVAILABLE, null);

        assertThatThrownBy(() -> browser().folders(alice, hostId, ""))
                .isInstanceOf(RunnerBrowseException.class);

        verify(auditService).recordCall(eq(alice), any(), any(),
                eq(RunnerHostFolderBrowser.SCREEN_LIST_FOLDERS), any(), any());
    }

    @Test
    void anImpossiblePathNeverReachesTheMachine() {
        owned();

        assertThatThrownBy(() -> browser().folders(alice, hostId, "../etc"))
                .isInstanceOf(InvalidProjectPathException.class);

        verify(gateway, never()).listFiles(any(), any());
    }

    @Test
    void anotherAccountsHostIsRefusedBeforeAnyCall() {
        // Isolation : l'appartenance est vérifiée AVANT tout — aucun appel ne part vers la machine
        // d'un autre compte, et le refus ne dit pas si le poste existe.
        when(hostService.requireOwned(alice, hostId))
                .thenThrow(new RunnerHostNotFoundException("Poste introuvable"));

        assertThatThrownBy(() -> browser().folders(alice, hostId, ""))
                .isInstanceOf(RunnerHostNotFoundException.class);

        verify(gateway, never()).listFiles(any(), any());
        verify(auditService, never()).recordCall(any(), any(), any(), any(), any(), any());
    }
}
