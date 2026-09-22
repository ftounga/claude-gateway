package fr.claudegateway.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/** L'exécution de {@code generate_image} (F-142 / SF-142-04) : dépôt hébergé/poste, erreurs nommées. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageToolExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private ImageGenerationService imageService;
    @Mock private WorkspaceService workspaceService;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private RunnerAuditService runnerAuditService;
    @Mock private Workspace workspace;

    private ImageToolExecutor executor;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        executor = new ImageToolExecutor(imageService, workspaceService, runnerToolGateway, runnerAuditService);
        when(workspace.getId()).thenReturn(workspaceId);
        when(workspace.getHostId()).thenReturn(hostId);
        when(workspace.isTeamsTerminal()).thenReturn(false);
    }

    private static ObjectNode input(String prompt, String size, String filename) {
        ObjectNode node = MAPPER.createObjectNode();
        if (prompt != null) {
            node.put("prompt", prompt);
        }
        if (size != null) {
            node.put("size", size);
        }
        if (filename != null) {
            node.put("filename", filename);
        }
        return node;
    }

    private GeneratedImage image() {
        GeneratedImage i = GeneratedImage.builder().userId(userId).space(ImageSpace.FORGE).hostId(hostId)
                .workspaceId(workspaceId).prompt("p").size("1024x1024").status(GeneratedImageStatus.READY)
                .build();
        i.setId(UUID.randomUUID());
        return i;
    }

    @Test
    @DisplayName("prompt absent → erreur nommée, aucune génération")
    void missingPrompt() {
        ImageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1", input(null, null, null));
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("prompt");
        verify(imageService, never()).generate(any(), any(), any());
    }

    @Test
    @DisplayName("projet hébergé (SANDBOX) : dépôt via depositHostedFile, chemin rendu")
    void depositsOnSandbox() {
        when(workspace.isRunnerTarget()).thenReturn(false);
        GeneratedImage img = image();
        when(imageService.generate(any(), eq("une couverture bleue"), eq(ImageSize.LANDSCAPE)))
                .thenReturn(new ImageGenerationService.Generated(img, new byte[] {1, 2, 3}));
        when(workspaceService.depositHostedFile(eq(userId), eq(workspaceId), eq("couverture.png"),
                any(), eq("image/png"))).thenReturn("entrees/couverture.png");

        ImageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-2",
                input("une couverture bleue", "1536x1024", "couverture.png"));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("entrees/couverture.png").contains(img.getId().toString())
                .containsIgnoringCase("décoratif");
        assertThat(outcome.published()).isSameAs(img);
        verify(runnerToolGateway, never()).writeFileBytes(any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("projet sur poste (RUNNER) : dépôt binaire via write_file_bytes, tracé")
    void depositsOnRunner() {
        when(workspace.isRunnerTarget()).thenReturn(true);
        when(workspace.getProjectPath()).thenReturn(null);
        GeneratedImage img = image();
        when(imageService.generate(any(), any(), any()))
                .thenReturn(new ImageGenerationService.Generated(img, new byte[] {1, 2, 3, 4}));
        when(runnerToolGateway.writeFileBytes(any(RunnerTarget.class), any(), eq("ambiance.png"), any(),
                anyLong())).thenReturn(ok());

        ImageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-3",
                input("ambiance", null, "ambiance.png"));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("ambiance.png");
        verify(runnerToolGateway).writeFileBytes(any(RunnerTarget.class), any(), eq("ambiance.png"), any(),
                eq(0L));
        verify(runnerAuditService).recordCall(eq(userId), any(), eq("call-3"), eq(ImageToolCatalog.GENERATE),
                eq("ambiance.png"), any());
        verify(workspaceService, never()).depositHostedFile(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("fournisseur non configuré → erreur nommée, pas de dépôt")
    void providerUnavailable() {
        when(workspace.isRunnerTarget()).thenReturn(false);
        when(imageService.generate(any(), any(), any()))
                .thenThrow(new ImageProviderUnavailableException("non configuré"));

        ImageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-4",
                input("x", null, null));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).containsIgnoringCase("non configurée");
        verify(workspaceService, never()).depositHostedFile(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("dépôt hébergé en échec → erreur nommée (image rangée mais non déposée)")
    void depositFailure() {
        when(workspace.isRunnerTarget()).thenReturn(false);
        GeneratedImage img = image();
        when(imageService.generate(any(), any(), any()))
                .thenReturn(new ImageGenerationService.Generated(img, new byte[] {1}));
        when(workspaceService.depositHostedFile(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("stockage indisponible"));

        ImageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-5",
                input("x", null, null));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).containsIgnoringCase("dépôt");
    }

    @Test
    @DisplayName("nom de fichier : sanitisé, extension .png, dérivé de l'id si absent")
    void fileNameSanitisation() {
        UUID id = UUID.randomUUID();
        assertThat(ImageToolExecutor.fileName("Ma Couverture!.png", id)).isEqualTo("Ma-Couverture.png");
        assertThat(ImageToolExecutor.fileName("../evil/x", id)).isEqualTo("x.png");
        assertThat(ImageToolExecutor.fileName(null, id)).startsWith("image-").endsWith(".png");
        assertThat(ImageToolExecutor.fileName("   ", id)).startsWith("image-").endsWith(".png");
    }

    private static RunnerCallResult ok() {
        return new RunnerCallResult(true, "", false, null, 0L, 4L, null, null, "", false);
    }
}
