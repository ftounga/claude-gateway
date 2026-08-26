package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.agent.AtelierAgentProperties;
import fr.claudegateway.atelier.git.GitWorkspaceService;
import fr.claudegateway.git.GitHubUnavailableException;

/**
 * Vérifie la résolution des instructions portées par un projet (F-34 / SF-34-01) : priorité de
 * {@code CLAUDE.md} sur son repli, borne du contenu, et caractère <b>best-effort</b> de la lecture —
 * un fichier illisible n'empêche jamais d'ouvrir une session.
 */
@ExtendWith(MockitoExtension.class)
class ProjectInstructionsServiceTest {

    private static final UUID USER = UUID.randomUUID();

    @Mock
    private GitWorkspaceService gitWorkspaceService;

    private ProjectInstructionsService service;
    private Workspace workspace;

    private static AtelierAgentProperties props(Integer maxInstructionsChars) {
        return new AtelierAgentProperties(true, null, null, null, null, null, null, null, null, null,
                null, maxInstructionsChars, null, true, null, null);
    }

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(USER);
        service = new ProjectInstructionsService(gitWorkspaceService, props(null));
    }

    @Test
    void readsClaudeMdAtTheRootOfTheProject() {
        when(gitWorkspaceService.readFile(USER, workspace, "CLAUDE.md")).thenReturn("Règles du projet.");

        Optional<ProjectInstructions> resolved = service.resolve(USER, workspace);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().path()).isEqualTo("CLAUDE.md");
        assertThat(resolved.get().content()).isEqualTo("Règles du projet.");
        assertThat(resolved.get().truncated()).isFalse();
    }

    @Test
    void fallsBackOnTheAtelierInstructionsFileWhenClaudeMdIsAbsent() {
        when(gitWorkspaceService.readFile(USER, workspace, "CLAUDE.md"))
                .thenThrow(new WorkspaceNotFoundException("absent"));
        when(gitWorkspaceService.readFile(USER, workspace, ".atelier/instructions.md"))
                .thenReturn("Consignes de repli.");

        Optional<ProjectInstructions> resolved = service.resolve(USER, workspace);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().path()).isEqualTo(".atelier/instructions.md");
        assertThat(resolved.get().content()).isEqualTo("Consignes de repli.");
    }

    @Test
    void claudeMdWinsOverTheFallbackFile() {
        when(gitWorkspaceService.readFile(USER, workspace, "CLAUDE.md")).thenReturn("Le bon fichier.");

        Optional<ProjectInstructions> resolved = service.resolve(USER, workspace);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().path()).isEqualTo("CLAUDE.md");
        // Le repli n'est même pas lu : deux fichiers concurrents ne sont jamais concaténés.
        org.mockito.Mockito.verify(gitWorkspaceService, org.mockito.Mockito.never())
                .readFile(eq(USER), any(), eq(".atelier/instructions.md"));
    }

    @Test
    void noInstructionFileMeansNoOverrideAtAll() {
        when(gitWorkspaceService.readFile(eq(USER), any(), any()))
                .thenThrow(new WorkspaceNotFoundException("absent"));

        assertThat(service.resolve(USER, workspace)).isEmpty();
    }

    @Test
    void aBlankFileIsTreatedAsNoInstructions() {
        when(gitWorkspaceService.readFile(eq(USER), any(), any())).thenReturn("   \n\n  ");

        assertThat(service.resolve(USER, workspace)).isEmpty();
    }

    @Test
    void contentBeyondTheBoundIsTruncatedAndSaysSo() {
        service = new ProjectInstructionsService(gitWorkspaceService, props(20));
        when(gitWorkspaceService.readFile(USER, workspace, "CLAUDE.md"))
                .thenReturn("A".repeat(500));

        Optional<ProjectInstructions> resolved = service.resolve(USER, workspace);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().truncated()).isTrue();
        assertThat(resolved.get().content()).startsWith("A".repeat(20));
        assertThat(resolved.get().content()).contains("tronquées à 20 caractères");
        assertThat(resolved.get().content()).doesNotContain("A".repeat(21));
    }

    @Test
    void anUnreadableFileNeverBreaksTheSessionOpening() {
        when(gitWorkspaceService.readFile(eq(USER), any(), any()))
                .thenThrow(new GitHubUnavailableException("GitHub injoignable."));

        // Best-effort assumé : mieux vaut travailler sans les consignes que ne pas travailler.
        assertThat(service.resolve(USER, workspace)).isEmpty();
    }

    @Test
    void detectPathReadsTheTreeWithoutAnyFileAccess() {
        assertThat(ProjectInstructions.detectPath(List.of("src/a.txt", "CLAUDE.md")))
                .contains("CLAUDE.md");
        assertThat(ProjectInstructions.detectPath(List.of("src/a.txt", ".atelier/instructions.md")))
                .contains(".atelier/instructions.md");
        assertThat(ProjectInstructions.detectPath(List.of("CLAUDE.md", ".atelier/instructions.md")))
                .contains("CLAUDE.md");
        assertThat(ProjectInstructions.detectPath(List.of("src/a.txt"))).isEmpty();
        assertThat(ProjectInstructions.detectPath(null)).isEmpty();
    }
}
