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
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
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
    private PageToolExecutor executor;
    private Workspace workspace;
    private RunnerTarget target;

    @BeforeEach
    void setUp() {
        pageService = mock(PageService.class);
        runner = mock(RunnerToolGateway.class);
        audit = mock(RunnerAuditService.class);
        executor = new PageToolExecutor(pageService, runner, audit);
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
    @DisplayName("pièce jointe binaire ou au nom invalide : refus sans lecture")
    void binaryAttachmentRefused() throws Exception {
        PageToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c", json(
                "{\"title\":\"P\",\"html\":\"<p/>\",\"attachments\":[{\"name\":\"logo.png\",\"path\":\"logo.png\"}]}"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("data:");
        verify(runner, never()).readFile(any(), anyString(), anyString());
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
