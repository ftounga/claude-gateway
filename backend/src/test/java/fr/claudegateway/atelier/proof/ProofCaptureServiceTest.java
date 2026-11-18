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

    // ------------------------------------------------------------------ F-268 : la preuve fichier

    private static final String TF = String.join("\n",
            "resource \"aws_s3_bucket\" \"logs\" {",          // 1
            "  bucket = \"pbi-logs\"",                           // 2
            "  acl    = \"public-read\"",                        // 3
            "  password = \"S3cr3t-Value\"",                     // 4
            "  token    = var.api_token",                         // 5
            "  key = \"AKIAABCDEFGHIJKLMNOP\"",                  // 6
            "}") + "\n";

    /** Un rendu qui garde la demande et rend une image par fichier. */
    private static final class FileShots implements TerminalShotRenderer {
        JsonNode lastFiles;

        @Override
        public List<Shot> shoot(JsonNode session) {
            return List.of(new Shot(new byte[] {7}, 900, 500));
        }

        @Override
        public List<Shot> shootFiles(JsonNode request) {
            lastFiles = request;
            return java.util.stream.IntStream.range(0, request.path("files").size())
                    .mapToObj(i -> new Shot(new byte[] {9}, 1200, 600)).toList();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private static ProofCaptureService.FileSource file(String path, String content, List<Integer> hl, String note) {
        return new ProofCaptureService.FileSource(path, content, false, null, null, hl, note, null);
    }

    @Test
    @DisplayName("F-268 : un fichier → une image rangée sous le compte, bloc « files », texte avec la ligne surlignée")
    void fileNominal() {
        FileShots renderer = new FileShots();
        ProofCaptureService.Result result = service(renderer).capture(user, workspace, "toolu_f", "Bucket public",
                null, null, List.of(file("infra/s3.tf", TF, List.of(3, 99), "Lisible par tous")));
        assertThat(result.block()).isNotNull();
        assertThat(result.block().captures()).hasSize(1);
        assertThat(result.block().commands()).isEmpty();
        ProofBlock.FileShot shot = result.block().files().get(0);
        assertThat(shot.captureId()).isEqualTo(result.block().captures().get(0).id());
        assertThat(shot.path()).isEqualTo("infra/s3.tf");
        assertThat(shot.firstLine()).isEqualTo(1);
        assertThat(shot.lastLine()).isEqualTo(7);
        assertThat(shot.totalLines()).isEqualTo(7);
        assertThat(shot.highlights()).containsExactly(3);
        assertThat(storage.listKeys(ProofCaptureService.prefixOf(user, workspace))).hasSize(1);
        JsonNode sent = renderer.lastFiles.path("files").get(0);
        assertThat(sent.path("firstLine").asInt()).isEqualTo(1);
        assertThat(sent.path("lines")).hasSize(7);
        assertThat(sent.path("note").asText()).isEqualTo("Lisible par tous");
        assertThat(result.modelText()).contains("surlignée 3 : acl    = \"public-read\"")
                .contains("preuves=" + shot.captureId()).doesNotContain("S3cr3t");
    }

    @Test
    @DisplayName("F-268 : secrets masqués ligne à ligne (numéros justes) ; une référence reste visible")
    void fileSecretsMasked() {
        FileShots renderer = new FileShots();
        service(renderer).capture(user, workspace, "t", "x", null, null, List.of(file("s3.tf", TF, List.of(), null)));
        String lines = renderer.lastFiles.path("files").get(0).path("lines").toString();
        assertThat(lines).doesNotContain("S3cr3t-Value").doesNotContain("AKIAABCDEFGHIJKLMNOP")
                .contains("var.api_token").contains("public-read");
        String pem = "a\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nxyz\n-----END RSA PRIVATE KEY-----\nb";
        List<String> masked = new ProofCaptureService(repository, storage, renderer, new ObjectMapper()).maskedLines(pem);
        assertThat(masked).hasSize(6);
        assertThat(masked.get(0)).isEqualTo("a");
        assertThat(masked.subList(1, 5)).allMatch(l -> l.equals(fr.claudegateway.mcp.McpSecretFilter.MASK));
        assertThat(masked.get(5)).isEqualTo("b");
    }

    @Test
    @DisplayName("F-268 : fenêtre — plage demandée, sinon contexte autour du surlignage, sinon le début ; 80 lignes au plus")
    void fileWindow() {
        assertThat(ProofCaptureService.windowOf(300, 10, 30, List.of())).isEqualTo(new ProofCaptureService.Window(10, 30));
        assertThat(ProofCaptureService.windowOf(300, 10, 500, List.of())).isEqualTo(new ProofCaptureService.Window(10, 89));
        assertThat(ProofCaptureService.windowOf(300, null, null, List.of(100, 104)))
                .isEqualTo(new ProofCaptureService.Window(94, 110));
        assertThat(ProofCaptureService.windowOf(300, null, null, List.of())).isEqualTo(new ProofCaptureService.Window(1, 40));
        assertThat(ProofCaptureService.windowOf(300, null, null, List.of(10, 200)))
                .isEqualTo(new ProofCaptureService.Window(7, 86));
        assertThat(ProofCaptureService.windowOf(5, 50, 60, List.of())).isEqualTo(new ProofCaptureService.Window(5, 5));
    }

    @Test
    @DisplayName("F-268 : terminal ET fichiers dans un même bloc, identifiants dans l'ordre, tous joignables")
    void mixedTerminalAndFiles() {
        ProofCaptureService.Result result = service(new FileShots()).capture(user, workspace, "t", "Mix", null, REPORT,
                List.of(file("a.tf", "x = 1\n", List.of(1), null), file("b.yaml", "k: v\n", List.of(), null)));
        assertThat(result.block().captures()).hasSize(3);
        List<String> ids = result.block().captures().stream().map(ProofBlock.Capture::id).toList();
        assertThat(result.block().files()).extracting(ProofBlock.FileShot::captureId).containsExactly(ids.get(1), ids.get(2));
        assertThat(result.block().commands()).hasSize(3);
        assertThat(result.modelText()).contains("preuves=" + String.join(",", ids));
    }

    @Test
    @DisplayName("F-268 : fichier non lu ou rendu indisponible — dit en clair, rien de rangé")
    void fileErrors() {
        ProofCaptureService.Result unread = service(new FileShots()).capture(user, workspace, "t", "x", null, null,
                List.of(new ProofCaptureService.FileSource("absent.tf", null, false, null, null, List.of(), null,
                        "Fichier introuvable.")));
        assertThat(unread.block()).isNull();
        assertThat(unread.modelText()).contains("absent.tf").contains("non lu").contains("introuvable");
        TerminalShotRenderer down = mock(TerminalShotRenderer.class);
        when(down.shootFiles(any())).thenThrow(new DiagramRendererUnavailableException("muet"));
        ProofCaptureService.Result failed = service(down).capture(user, workspace, "t", "x", null, null,
                List.of(file("a.tf", "x = 1", List.of(), null)));
        assertThat(failed.block()).isNull();
        assertThat(failed.modelText()).contains("n'ont pas pu être produites");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("F-268 : isolation — l'image d'un fichier n'est servie qu'à son compte et son terminal")
    void fileIsolation() {
        ProofCaptureService svc = service(new FileShots());
        ProofCaptureService.Result result = svc.capture(user, workspace, "t", "x", null, null,
                List.of(file("a.tf", "x = 1", List.of(), null)));
        String id = result.block().captures().get(0).id();
        UUID uuid = ProofCaptureService.parseId(id);
        when(repository.findByIdAndUserIdAndWorkspaceId(uuid, user, workspace))
                .thenReturn(java.util.Optional.of(new ProofCapture()));
        assertThat(svc.image(user, workspace, id)).isPresent();
        assertThat(svc.image(UUID.randomUUID(), workspace, id)).isEmpty();
    }
}
