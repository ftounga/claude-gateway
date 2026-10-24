package fr.claudegateway.atelier.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;
import fr.claudegateway.diagrams.DiagramRendererUnavailableException;

/** F-212 / SF-212-03 — le service des captures, sans Spring. */
class ProofCaptureServiceTest {

    private final ProofCaptureRepository repository = mock(ProofCaptureRepository.class);
    private final InMemoryWorkspaceStorage storage = new InMemoryWorkspaceStorage();
    private final UUID user = UUID.randomUUID();
    private final UUID workspace = UUID.randomUUID();

    private ProofCaptureService service(TerminalShotRenderer renderer) {
        when(repository.save(any(ProofCapture.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new ProofCaptureService(repository, storage, renderer, new ObjectMapper());
    }

    private static TerminalShotRenderer shots(int n) {
        return new TerminalShotRenderer() {
            @Override
            public List<Shot> shoot(JsonNode session) {
                return java.util.stream.IntStream.range(0, n)
                        .mapToObj(i -> new Shot(new byte[] {1, 2, 3}, 800, 400)).toList();
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
    }

    private static final String REPORT = "{\"os\":\"macos\",\"host\":\"MacBook\",\"execShell\":\"zsh\",\"commands\":["
            + "{\"command\":\"npm test\",\"status\":\"done\",\"exitCode\":0,\"durationMs\":2500,\"stdout\":\"12 passed\\n\"},"
            + "{\"command\":\"exit 3\",\"status\":\"interrupted\",\"exitCode\":3,\"durationMs\":1},"
            + "{\"command\":\"echo jamais\",\"status\":\"not_run\",\"exitCode\":null}]}";

    @Test
    @DisplayName("une session → images rangées sous preuves/{user}/{workspace}/, bloc et texte court")
    void nominal() {
        ProofCaptureService.Result result = service(shots(2)).capture(user, workspace, "toolu_9", "Tests verts", "2", REPORT);
        assertThat(result.block().captures()).hasSize(2);
        assertThat(result.block().step()).isEqualTo("2");
        assertThat(result.block().os()).isEqualTo("macos");
        assertThat(result.block().commands()).extracting(ProofBlock.Command::status)
                .containsExactly("done", "interrupted", "not_run");
        assertThat(storage.listKeys(ProofCaptureService.prefixOf(user, workspace))).hasSize(2);
        String id = result.block().captures().get(0).id();
        assertThat(result.modelText()).contains(id).contains("preuves=").contains("code 0").contains("2,5 s")
                .contains("interrompue").contains("non exécutée").contains("12 passed");
    }

    @Test
    @DisplayName("service de rendu indisponible : pas de bloc, mais le modèle reçoit les sorties du poste")
    void rendererDown() {
        TerminalShotRenderer down = mock(TerminalShotRenderer.class);
        when(down.shoot(any())).thenThrow(new DiagramRendererUnavailableException("muet"));
        ProofCaptureService.Result result = service(down).capture(user, workspace, "t", "x", null, REPORT);
        assertThat(result.block()).isNull();
        assertThat(result.modelText()).contains("n'ont pas pu être produites").contains("12 passed");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("relevé illisible : aucune capture, dit en clair")
    void unreadableReport() {
        ProofCaptureService.Result result = service(shots(1)).capture(user, workspace, "t", "x", null, "pas du json");
        assertThat(result.block()).isNull();
        assertThat(result.modelText()).contains("aucune capture");
    }

    @Test
    @DisplayName("tête + fin, borne respectée")
    void headAndTail() {
        String out = ProofCaptureService.headAndTail("a".repeat(5_000) + "FIN", 2_000);
        assertThat(out).startsWith("aaa").endsWith("FIN").contains("caractères omis");
        assertThat(out.length()).isLessThan(2_100);
    }

    @Test
    @DisplayName("un identifiant de capture n'est jamais un chemin")
    void idIsNotAPath() {
        assertThat(ProofCaptureService.parseId("../../etc")).isNull();
        assertThat(ProofCaptureService.parseId("0123456789abcdef0123456789ABCDEF")).isNotNull();
    }
}
