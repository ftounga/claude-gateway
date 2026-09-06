package fr.claudegateway.runner.browse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/** L'explorateur lit la machine — il ne la copie pas (F-38 / SF-38-17). */
@ExtendWith(MockitoExtension.class)
class RunnerWorkspaceBrowserTest {

    @Mock private RunnerToolGateway gateway;
    @Mock private RunnerAuditService auditService;

    private RunnerWorkspaceBrowser browser;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        browser = new RunnerWorkspaceBrowser(gateway, auditService);
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(UUID.randomUUID());
        workspace.setSource(WorkspaceSource.LOCAL);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    @Test
    void readsTheTreeFromTheMachine() {
        when(gateway.listFiles(eq(workspace.getId()), any())).thenReturn(ok("a.txt\nsrc/App.java"));

        assertThat(browser.tree(workspace)).containsExactly("a.txt", "src/App.java");
    }

    @Test
    void returnsAnEmptyTreeForAnEmptyFolder() {
        // Un dossier vide est un état normal : c'est même le point de départ d'un projet neuf.
        when(gateway.listFiles(eq(workspace.getId()), any())).thenReturn(ok(""));

        assertThat(browser.tree(workspace)).isEmpty();
    }

    @Test
    void saysTheProjectIsOfflineRatherThanShowingAnEmptyTree() {
        when(gateway.listFiles(eq(workspace.getId()), any()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));

        assertThatThrownBy(() -> browser.tree(workspace))
                .isInstanceOf(RunnerBrowseException.class)
                .hasMessageContaining("hors ligne");
    }

    @Test
    void keepsTheProjectReachableWhenTheMachineIsAsleep() {
        // Le détail du projet porte aussi son nom, sa source et sa cible : une machine éteinte ne
        // doit pas rendre la page inaccessible. L'écran, lui, sait déjà dire « hors ligne ».
        when(gateway.listFiles(eq(workspace.getId()), any()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));

        assertThat(browser.treeOrEmpty(workspace)).isEmpty();
    }

    @Test
    void reportsARefusalWithTheReasonGivenByTheRunner() {
        // Fichier exclu par .runnerignore : la garde est celle du runner, pas une seconde règle ici.
        when(gateway.readFile(eq(workspace.getId()), any(), eq(".env")))
                .thenReturn(RunnerCallResult.backendError("excluded", "Chemin exclu : .env"));

        assertThatThrownBy(() -> browser.readFile(workspace, ".env"))
                .isInstanceOf(RunnerBrowseException.class)
                .hasMessageContaining(".env");
    }

    @Test
    void readsAFileAndMarksTruncation() {
        when(gateway.readFile(eq(workspace.getId()), any(), eq("a.txt"))).thenReturn(ok("contenu"));
        assertThat(browser.readFile(workspace, "a.txt")).isEqualTo("contenu");

        when(gateway.readFile(eq(workspace.getId()), any(), eq("gros.txt")))
                .thenReturn(new RunnerCallResult(true, "début", true, null, 5L, null, null, null, "", false));
        assertThat(browser.readFile(workspace, "gros.txt")).contains("tronqué");
    }

    @Test
    void auditsEveryScreenReadUnderItsOwnToolName() {
        when(gateway.listFiles(eq(workspace.getId()), any())).thenReturn(ok("a.txt"));
        when(gateway.readFile(eq(workspace.getId()), any(), eq("a.txt"))).thenReturn(ok("x"));

        browser.tree(workspace);
        browser.readFile(workspace, "a.txt");

        // Le journal doit distinguer ce que l'ÉCRAN a lu de ce que l'AGENT a décidé de lire.
        verify(auditService).recordCall(eq(workspace.getUserId()), eq(workspace.getId()), any(),
                eq(RunnerWorkspaceBrowser.SCREEN_LIST), eq(null), any());
        verify(auditService).recordCall(eq(workspace.getUserId()), eq(workspace.getId()), any(),
                eq(RunnerWorkspaceBrowser.SCREEN_READ), eq("a.txt"), any());
    }

    @Test
    void saysWhenTheListingIsIncompleteRatherThanShowingAnAmputedProject() {
        // Le banc d'essai : 40 590 fichiers, une liste coupée à 4 829 lignes, et l'utilisateur
        // cherchant dix minutes un dossier que le système savait ne pas lui avoir envoyé.
        when(gateway.listFiles(eq(workspace.getId()), any()))
                .thenReturn(new RunnerCallResult(true, "a.txt\nsrc/App.java", true, null, 5L, null,
                        null, null, "", false));

        assertThat(browser.tree(workspace))
                .contains("a.txt", "src/App.java")
                .anySatisfy(line -> assertThat(line).contains("liste incomplète"));
    }

    @Test
    void doesNotAddTheMarkerOnACompleteListing() {
        when(gateway.listFiles(eq(workspace.getId()), any())).thenReturn(ok("a.txt"));

        assertThat(browser.tree(workspace)).containsExactly("a.txt");
    }
}
