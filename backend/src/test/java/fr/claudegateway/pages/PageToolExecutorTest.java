package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/** Exécuter {@code page_publish} (F-109 / SF-109-02) : lecture du poste, rangement, messages à l'agent. */
class PageToolExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private PageService pageService;
    private RunnerToolGateway runner;
    private RunnerAuditService audit;
    private WorkspaceService workspaceService;
    private PageToolExecutor executor;
    private Workspace workspace;
    private RunnerTarget target;

    @BeforeEach
    void setUp() {
        pageService = mock(PageService.class);
        runner = mock(RunnerToolGateway.class);
        audit = mock(RunnerAuditService.class);
        workspaceService = mock(WorkspaceService.class);
        executor = new PageToolExecutor(pageService, runner, audit, workspaceService, PageLimits.defaults());
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        target = new RunnerTarget(hostId, workspace.getId(), "projet");
        Page page = Page.builder().id(UUID.randomUUID()).userId(userId).title("Maquette").build();
        PageVersion version = PageVersion.builder().version(1).build();
        when(pageService.publish(any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new PageService.PublishedPage(page, version));
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    private static RunnerCallResult read(String content, boolean truncated) {
        return new RunnerCallResult(true, content, truncated, null, 3L, (long) content.length(), null, null, "", false);
    }

    /** Une tranche binaire : {@code content} en Base64, {@code bytes} la taille totale annoncée du fichier. */
    private static RunnerCallResult bytes(byte[] chunk, long totalBytes, boolean truncated) {
        String base64 = Base64.getEncoder().encodeToString(chunk);
        return new RunnerCallResult(true, base64, truncated, null, 3L, totalBytes, null, null, "", false);
    }

    @Test
    @DisplayName("CA5 — html fourni : rangé au lieu du terminal, l'agent reçoit le page_id et la version")
    void htmlIsPublishedAtTheTerminalPlace() throws Exception {
        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1",
                json("{\"title\":\"Maquette\",\"description\":\"Une phrase.\",\"html\":\"<h1>x</h1>\"}"));

        ArgumentCaptor<PagePlace> place = ArgumentCaptor.forClass(PagePlace.class);
        verify(pageService).publish(place.capture(), isNull(), eq("Maquette"), eq("Une phrase."), eq("<h1>x</h1>"),
                eq(Map.of()));
        assertThat(place.getValue()).isEqualTo(new PagePlace(userId, PageSpace.FORGE, hostId, workspace.getId()));
        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("version 1").contains("page_id");
        verify(runner, never()).readFile(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("un terminal Teams range la page dans la Vigie")
    void teamsTerminalIsVigie() throws Exception {
        workspace.setTeamsTerminal(true);
        executor.execute(userId, workspace, "call-1", json("{\"title\":\"CR\",\"html\":\"<p>x</p>\"}"));

        ArgumentCaptor<PagePlace> place = ArgumentCaptor.forClass(PagePlace.class);
        verify(pageService).publish(place.capture(), any(), any(), any(), any(), anyMap());
        assertThat(place.getValue().space()).isEqualTo(PageSpace.VIGIE);
    }

    @Test
    @DisplayName("CA6 — path : lu par le runner, tracé, rangé ; pièce jointe texte aussi")
    void pathIsReadAndAudited() throws Exception {
        when(runner.readFile(target, "call-1", "docs/page.html")).thenReturn(read("<h1>poste</h1>", false));
        when(runner.readFile(target, "call-1#1", "docs/style.css")).thenReturn(read("h1{}", false));

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1", json(
                "{\"title\":\"P\",\"path\":\"docs/page.html\",\"attachments\":[{\"name\":\"style.css\",\"path\":\"docs/style.css\"}]}"));

        assertThat(outcome.error()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> files = ArgumentCaptor.forClass(Map.class);
        verify(pageService).publish(any(), isNull(), eq("P"), any(), eq("<h1>poste</h1>"), files.capture());
        assertThat(new String(files.getValue().get("style.css"))).isEqualTo("h1{}");
        verify(audit).recordCall(eq(userId), eq(target), eq("call-1"), eq(PageToolCatalog.PUBLISH),
                eq("docs/page.html"), any());
        verify(audit).recordCall(eq(userId), eq(target), eq("call-1#1"), eq(PageToolCatalog.PUBLISH),
                eq("docs/style.css"), any());
    }

    @Test
    @DisplayName("CA6 — fichier tronqué par le runner : refus, rien n'est rangé")
    void truncatedFileIsRefused() throws Exception {
        when(runner.readFile(target, "call-1", "big.html")).thenReturn(read("<h1>", true));

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1",
                json("{\"title\":\"P\",\"path\":\"big.html\"}"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("trop volumineux");
        verify(pageService, never()).publish(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("fichier illisible : le motif du runner est rendu")
    void unreadableFile() throws Exception {
        when(runner.readFile(target, "call-1", "absent.html"))
                .thenReturn(RunnerCallResult.backendError("not_found", "Fichier introuvable"));

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1",
                json("{\"title\":\"P\",\"path\":\"absent.html\"}"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("Fichier introuvable");
    }

    @Test
    @DisplayName("ni html ni path, ou les deux : refus")
    void exactlyOneSource() throws Exception {
        assertThat(executor.execute(userId, workspace, "c", json("{\"title\":\"P\"}")).error()).isTrue();
        assertThat(executor.execute(userId, workspace, "c",
                json("{\"title\":\"P\",\"html\":\"<p/>\",\"path\":\"a.html\"}")).content()).contains("exactement un");
        verify(pageService, never()).publish(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("pièce jointe d'extension non servie (pdf) ou au nom invalide : refus sans lecture")
    void unservedAttachmentRefused() throws Exception {
        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c", json(
                "{\"title\":\"P\",\"html\":\"<p/>\",\"attachments\":[{\"name\":\"note.pdf\",\"path\":\"note.pdf\"}]}"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("refusée");
        verify(runner, never()).readFile(any(), anyString(), anyString());
        verify(runner, never()).readFileBytes(any(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("CA4 — image de la machine : lue en binaire par le runner, tracée, octets intacts")
    void imageIsReadAsBytesAndAudited() throws Exception {
        byte[] pixels = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2, 3};
        when(runner.readFile(target, "call-1", "page.html")).thenReturn(read("<img src=\"cap.png\">", false));
        when(runner.readFileBytes(target, "call-1#1.0", "shots/cap.png", 0L, PageToolExecutor.CHUNK_BYTES))
                .thenReturn(bytes(pixels, pixels.length, false));

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1", json(
                "{\"title\":\"P\",\"path\":\"page.html\","
                        + "\"attachments\":[{\"name\":\"cap.png\",\"path\":\"shots/cap.png\"}]}"));

        assertThat(outcome.error()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> files = ArgumentCaptor.forClass(Map.class);
        verify(pageService).publish(any(), isNull(), eq("P"), any(), eq("<img src=\"cap.png\">"), files.capture());
        assertThat(files.getValue().get("cap.png")).isEqualTo(pixels);
        verify(audit).recordCall(eq(userId), eq(target), eq("call-1#1"), eq(PageToolCatalog.PUBLISH),
                eq("shots/cap.png"), any());
        verify(runner, never()).readFile(eq(target), anyString(), eq("shots/cap.png"));
    }

    @Test
    @DisplayName("CA9 — image annoncée au-delà de 8 Mo : refus, rien n'est rangé")
    void oversizedImageRefused() throws Exception {
        long tooBig = PageLimits.DEFAULT_MAX_PAGE_BYTES + 1;
        when(runner.readFileBytes(target, "call-1#1.0", "huge.png", 0L, PageToolExecutor.CHUNK_BYTES))
                .thenReturn(bytes(new byte[]{1, 2, 3}, tooBig, true));

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1", json(
                "{\"title\":\"P\",\"html\":\"<p/>\",\"attachments\":[{\"name\":\"huge.png\",\"path\":\"huge.png\"}]}"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("trop volumineuse");
        verify(pageService, never()).publish(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("CA10 — runner trop ancien pour read_file_bytes : refus nommé pour l'image")
    void oldRunnerCannotReadImage() throws Exception {
        when(runner.readFileBytes(target, "call-1#1.0", "cap.png", 0L, PageToolExecutor.CHUNK_BYTES))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL, "non supporté"));

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1", json(
                "{\"title\":\"P\",\"html\":\"<p/>\",\"attachments\":[{\"name\":\"cap.png\",\"path\":\"cap.png\"}]}"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("trop ancien").contains("data:");
    }

    @Test
    @DisplayName("réponse binaire illisible (Base64 invalide) : refus")
    void invalidBase64Refused() throws Exception {
        RunnerCallResult broken = new RunnerCallResult(true, "pas du base64 !!", false, null, 1L, 3L, null, null, "",
                false);
        when(runner.readFileBytes(target, "call-1#1.0", "cap.png", 0L, PageToolExecutor.CHUNK_BYTES))
                .thenReturn(broken);

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1", json(
                "{\"title\":\"P\",\"html\":\"<p/>\",\"attachments\":[{\"name\":\"cap.png\",\"path\":\"cap.png\"}]}"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("réponse du runner invalide");
    }

    @Test
    @DisplayName("CA5 — projet hébergé : html rangé depuis le stockage, sans jamais appeler le runner")
    void sandboxPublishesWithoutRunner() throws Exception {
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1",
                json("{\"title\":\"Hébergée\",\"html\":\"<h1>x</h1>\"}"));

        assertThat(outcome.error()).isFalse();
        verify(pageService).publish(any(), isNull(), eq("Hébergée"), any(), eq("<h1>x</h1>"), eq(Map.of()));
        verify(runner, never()).readFile(any(), anyString(), anyString());
        verify(runner, never()).readFileBytes(any(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("CA6 — projet hébergé : l'image est lue depuis le stockage, pas depuis le runner")
    void sandboxImageIsReadFromStorage() throws Exception {
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        byte[] pixels = {1, 2, 3, 4};
        when(workspaceService.readFileBytes(userId, workspace.getId(), "cap.png")).thenReturn(pixels);

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1", json(
                "{\"title\":\"P\",\"html\":\"<img src=\\\"cap.png\\\">\","
                        + "\"attachments\":[{\"name\":\"cap.png\",\"path\":\"cap.png\"}]}"));

        assertThat(outcome.error()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> files = ArgumentCaptor.forClass(Map.class);
        verify(pageService).publish(any(), isNull(), eq("P"), any(), any(), files.capture());
        assertThat(files.getValue().get("cap.png")).isEqualTo(pixels);
        verify(runner, never()).readFileBytes(any(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("projet hébergé : un fichier absent du stockage rend un refus lisible")
    void sandboxMissingFileRefused() throws Exception {
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        when(workspaceService.readFile(userId, workspace.getId(), "absent.html"))
                .thenThrow(new WorkspaceNotFoundException("Fichier introuvable : absent.html"));

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1",
                json("{\"title\":\"P\",\"path\":\"absent.html\"}"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("Fichier introuvable");
    }

    @Test
    @DisplayName("CA10 — page_id mal formé ou d'un autre compte : même refus")
    void unknownPageId() throws Exception {
        assertThat(executor.execute(userId, workspace, "c",
                json("{\"title\":\"P\",\"html\":\"<p/>\",\"page_id\":\"pas-un-uuid\"}")).content())
                .contains("page_id inconnu");

        when(pageService.publish(any(), any(), any(), any(), any(), anyMap())).thenThrow(new PageNotFoundException());
        assertThat(executor.execute(userId, workspace, "c",
                json("{\"title\":\"P\",\"html\":\"<p/>\",\"page_id\":\"" + UUID.randomUUID() + "\"}")).content())
                .contains("page_id inconnu");
    }

    @Test
    @DisplayName("un refus du service (titre, taille, quota) est relayé tel quel")
    void serviceRefusalIsRelayed() throws Exception {
        when(pageService.publish(any(), any(), any(), any(), any(), anyMap()))
                .thenThrow(new PageQuotaExceededException(500L * 1024 * 1024));

        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c",
                json("{\"title\":\"P\",\"html\":\"<p/>\"}"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("500 Mo");
    }
}
