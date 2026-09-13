package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.pages.Page;
import fr.claudegateway.pages.PageNotFoundException;
import fr.claudegateway.pages.PageService;
import fr.claudegateway.radar.RadarExportService;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/** Les pièces jointes : trois sources, noms, secrets, plafond, runner ancien (F-110 / SF-110-03). */
@ExtendWith(MockitoExtension.class)
class ClientMailAttachmentsTest {

    private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");

    @Mock private RunnerToolGateway runner;
    @Mock private RunnerAuditService audit;
    @Mock private PageService pages;
    @Mock private RadarExportService radarExport;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private Workspace workspace;
    private ClientMailAttachments attachments;

    @BeforeEach
    void setUp() {
        workspace = Workspace.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId).name("CAGIP")
                .projectPath("").executionTarget(WorkspaceExecutionTarget.RUNNER).build();
        attachments = new ClientMailAttachments(runner, audit, pages, radarExport, Clock.fixed(NOW, ZoneOffset.UTC),
                "https://portal.example/");
    }

    private ClientMailAttachments.Collected collect(String json) throws Exception {
        JsonNode list = mapper.readTree(json);
        return attachments.collect(userId, workspace, "call-1", list, 1_000);
    }

    private static RunnerCallResult chunk(byte[] part, long total, boolean more) {
        return new RunnerCallResult(true, Base64.getEncoder().encodeToString(part), more, null, 5L, total, null, null,
                "", false);
    }

    private static RunnerCallResult failure(String code, String message) {
        return RunnerCallResult.backendError(code, message);
    }

    // ---------------------------------------------------------------- fichier du poste

    @Test
    void aBinaryFileIsReadInChunksReassembledAndAuditedOnce() throws Exception {
        byte[] pdf = new byte[ClientMailAttachments.CHUNK_BYTES + 10];
        new java.util.Random(7).nextBytes(pdf);
        pdf[0] = 0; // binaire : jamais inspecté comme du texte
        byte[] head = Arrays.copyOfRange(pdf, 0, ClientMailAttachments.CHUNK_BYTES);
        byte[] tail = Arrays.copyOfRange(pdf, ClientMailAttachments.CHUNK_BYTES, pdf.length);
        when(runner.readFileBytes(any(), eq("call-1#pj1.0"), eq("docs/cr.pdf"), eq(0L), anyInt()))
                .thenReturn(chunk(head, pdf.length, true));
        when(runner.readFileBytes(any(), eq("call-1#pj1.1"), eq("docs/cr.pdf"), eq((long) head.length), anyInt()))
                .thenReturn(chunk(tail, pdf.length, false));

        ClientMailAttachments.Collected collected = collect("[{\"path\":\"docs/cr.pdf\"}]");

        assertThat(collected.isRefused()).as(collected.refusal()).isFalse();
        assertThat(collected.attachments()).hasSize(1);
        assertThat(collected.attachments().get(0).name()).isEqualTo("cr.pdf");
        assertThat(collected.attachments().get(0).contentType()).isEqualTo("application/pdf");
        assertThat(collected.attachments().get(0).content()).isEqualTo(pdf);
        verify(audit, times(1)).recordCall(eq(userId), any(RunnerTarget.class), eq("call-1#pj1"),
                eq(ClientMailTool.NAME), eq("docs/cr.pdf"), any());
    }

    @Test
    void anOldRunnerStillAttachesATextFileButRefusesABinary() throws Exception {
        when(runner.readFileBytes(any(), anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(failure("unsupported_tool", "Outil non supporté"));
        when(runner.readFile(any(), anyString(), eq("notes.md")))
                .thenReturn(new RunnerCallResult(true, "# Notes", false, null, 3L, 7L, null, null, "", false));

        ClientMailAttachments.Collected text = collect("[{\"path\":\"notes.md\"}]");
        assertThat(text.isRefused()).isFalse();
        assertThat(new String(text.attachments().get(0).content(), StandardCharsets.UTF_8)).isEqualTo("# Notes");

        ClientMailAttachments.Collected binary = collect("[{\"path\":\"cr.pdf\"}]");
        assertThat(binary.refusal()).contains("trop ancien", "mettre à jour");
        verify(runner, never()).readFile(any(), anyString(), eq("cr.pdf"));
    }

    @Test
    void aFileThatChangesDuringTheReadIsRefused() throws Exception {
        byte[] part = new byte[ClientMailAttachments.CHUNK_BYTES];
        when(runner.readFileBytes(any(), eq("call-1#pj1.0"), anyString(), anyLong(), anyInt()))
                .thenReturn(chunk(part, part.length + 50, true));
        when(runner.readFileBytes(any(), eq("call-1#pj1.1"), anyString(), anyLong(), anyInt()))
                .thenReturn(chunk(new byte[40], part.length + 40, false));

        assertThat(collect("[{\"path\":\"cr.pdf\"}]").refusal()).contains("a changé pendant la lecture");
    }

    @Test
    void anOfflinePosteOrMissingFileIsSaid() throws Exception {
        when(runner.readFileBytes(any(), anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(failure("not_found", "Fichier introuvable : cr.pdf"));

        ClientMailAttachments.Collected collected = collect("[{\"path\":\"cr.pdf\"}]");

        assertThat(collected.refusal()).contains("Fichier illisible", "introuvable", "Aucun courriel");
        verify(audit).recordCall(eq(userId), any(), eq("call-1#pj1"), eq(ClientMailTool.NAME), eq("cr.pdf"), any());
    }

    @Test
    void theCapStopsTheReadAsSoonAsTheAnnouncedSizeExceedsIt() throws Exception {
        when(runner.readFileBytes(any(), eq("call-1#pj1.0"), anyString(), anyLong(), anyInt()))
                .thenReturn(chunk(new byte[ClientMailAttachments.CHUNK_BYTES], 11L * 1024 * 1024, true));

        ClientMailAttachments.Collected collected = collect("[{\"path\":\"video.zip\"}]");

        assertThat(collected.refusal()).contains("trop lourd", "10 Mo", "Propose un lien", "link_only");
        verify(runner, times(1)).readFileBytes(any(), anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    void theCapCountsEveryAttachmentTogether() throws Exception {
        byte[] six = new byte[6 * 1024 * 1024];
        six[0] = 0;
        when(runner.readFileBytes(any(), anyString(), eq("a.bin"), anyLong(), anyInt()))
                .thenReturn(chunk(six, six.length, false));
        when(runner.readFileBytes(any(), anyString(), eq("b.bin"), anyLong(), anyInt()))
                .thenReturn(chunk(six, six.length, false));

        assertThat(collect("[{\"path\":\"a.bin\"},{\"path\":\"b.bin\"}]").refusal()).contains("trop lourd");
    }

    // ---------------------------------------------------------------- secrets

    @Test
    void aSecretContainerIsRefusedByNameWithoutBeingRead() throws Exception {
        for (String path : new String[] {".env", "config/.env.production", "~/.ssh/id_rsa", "certs/client.p12",
                "prod.pem", "C:\\Users\\f\\.git-credentials"}) {
            assertThat(collect("[{\"path\":" + mapper.writeValueAsString(path) + "}]").refusal())
                    .as(path).contains("fichier de secrets");
        }
        verify(runner, never()).readFileBytes(any(), anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    void renamingASecretContainerDoesNotHelpAndNamingAFileAsOneIsRefused() throws Exception {
        when(runner.readFileBytes(any(), anyString(), eq("notes.txt"), anyLong(), anyInt()))
                .thenReturn(chunk("bonjour".getBytes(StandardCharsets.UTF_8), 7, false));

        assertThat(collect("[{\"path\":\".env\",\"name\":\"notes.txt\"}]").refusal()).contains("fichier de secrets");
        assertThat(collect("[{\"path\":\"notes.txt\",\"name\":\"server.key\"}]").refusal()).contains("fichier de secrets");
    }

    @Test
    void aSecretInsideATextAttachmentIsRefusedAndNamedWithoutTheValue() throws Exception {
        byte[] content = "Accès prod\npassword=Hunter2024!\n".getBytes(StandardCharsets.UTF_8);
        when(runner.readFileBytes(any(), anyString(), eq("acces.md"), anyLong(), anyInt()))
                .thenReturn(chunk(content, content.length, false));

        ClientMailAttachments.Collected collected = collect("[{\"path\":\"acces.md\"}]");

        assertThat(collected.refusal()).contains("« acces.md »", "un mot de passe").doesNotContain("Hunter2024");
    }

    @Test
    void aBinaryIsNotInspectedBeyondItsName() {
        byte[] binary = "password=Hunter2024!\u0000".getBytes(StandardCharsets.UTF_8);
        assertThat(ClientMailAttachments.asText(binary)).isEmpty();
        assertThat(ClientMailAttachments.asText(new byte[] {(byte) 0xC3, (byte) 0x28})).isEmpty();
        assertThat(ClientMailAttachments.asText("é".getBytes(StandardCharsets.UTF_8))).contains("é");
    }

    // ---------------------------------------------------------------- pages

    private Page page(UUID pageHost) {
        return Page.builder().id(UUID.randomUUID()).userId(userId).hostId(pageHost).title("Sujet MFA — septembre")
                .currentVersion(2).build();
    }

    @Test
    void aPageOfThisClientIsAttachedAsHtmlWithItsPrivateLink() throws Exception {
        Page page = page(hostId);
        when(pages.require(userId, page.getId())).thenReturn(page);
        when(pages.html(userId, page.getId(), null)).thenReturn(new PageService.PageContent(page, 2,
                "<!doctype html><title>MFA</title>".getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"));

        ClientMailAttachments.Collected collected = collect("[{\"page_id\":\"" + page.getId() + "\"}]");

        assertThat(collected.isRefused()).isFalse();
        assertThat(collected.attachments().get(0).name()).isEqualTo("sujet-mfa-septembre.html");
        assertThat(collected.attachments().get(0).contentType()).startsWith("text/html");
        assertThat(collected.links()).containsExactly(new ClientMailAttachments.PageLink("Sujet MFA — septembre",
                "https://portal.example/pages/" + page.getId()));
        assertThat(ClientMailAttachments.linksSection(collected.links()))
                .contains("<https://portal.example/pages/" + page.getId() + ">", "connexion");
    }

    @Test
    void linkOnlySendsTheLinkWithoutTheFile() throws Exception {
        Page page = page(hostId);
        when(pages.require(userId, page.getId())).thenReturn(page);

        ClientMailAttachments.Collected collected =
                collect("[{\"page_id\":\"" + page.getId() + "\",\"link_only\":true}]");

        assertThat(collected.attachments()).isEmpty();
        assertThat(collected.links()).hasSize(1);
        verify(pages, never()).html(any(), any(), any());
    }

    @Test
    void aPageOfAnotherPosteAnotherAccountOrUnknownIsRefusedAlike() throws Exception {
        Page elsewhere = page(UUID.randomUUID());
        when(pages.require(userId, elsewhere.getId())).thenReturn(elsewhere);
        UUID foreign = UUID.randomUUID();
        when(pages.require(userId, foreign)).thenThrow(new PageNotFoundException());

        String other = collect("[{\"page_id\":\"" + elsewhere.getId() + "\"}]").refusal();
        String unknown = collect("[{\"page_id\":\"" + foreign + "\"}]").refusal();
        String garbage = collect("[{\"page_id\":\"pas-un-uuid\"}]").refusal();

        assertThat(other).contains("page inconnue pour ce client").isEqualTo(unknown).isEqualTo(garbage);
    }

    // ---------------------------------------------------------------- Radar

    @Test
    void theRadarExportOfThisClientIsAttached() throws Exception {
        when(radarExport.export(new RadarScope(userId, hostId), LocalDate.of(2026, 9, 14)))
                .thenReturn(new RadarExportService.Export("radar-cagip-2026-09-14.md", "# Radar — CAGIP", 3));

        ClientMailAttachments.Collected collected = collect("[{\"radar_export\":true}]");

        assertThat(collected.attachments().get(0).name()).isEqualTo("radar-cagip-2026-09-14.md");
        assertThat(collected.attachments().get(0).contentType()).startsWith("text/markdown");
    }

    @Test
    void anEmptyRadarOrASecondExportIsRefused() throws Exception {
        when(radarExport.export(any(), any())).thenReturn(new RadarExportService.Export("r.md", "# Radar", 0));
        assertThat(collect("[{\"radar_export\":true}]").refusal()).contains("vide");

        when(radarExport.export(any(), any())).thenReturn(new RadarExportService.Export("r.md", "# Radar", 2));
        assertThat(collect("[{\"radar_export\":true},{\"radar_export\":true}]").refusal()).contains("qu'une fois");
    }

    // ---------------------------------------------------------------- forme

    @Test
    void theListShapeIsChecked() throws Exception {
        assertThat(collect("{\"path\":\"a.txt\"}").refusal()).contains("liste");
        assertThat(collect("[{}]").refusal()).contains("exactement une source");
        assertThat(collect("[{\"path\":\"a.txt\",\"radar_export\":true}]").refusal()).contains("exactement une source");
        StringBuilder eleven = new StringBuilder("[");
        for (int i = 0; i < 11; i++) {
            eleven.append(i == 0 ? "" : ",").append("{\"path\":\"f").append(i).append(".txt\"}");
        }
        assertThat(collect(eleven.append("]").toString()).refusal()).contains("10 pièces jointes au plus");
        assertThat(collect("[{\"path\":\"a.txt\",\"name\":\"../evil.txt\"}]").refusal()).contains("invalide");
        assertThat(collect("[]").isRefused()).isFalse();
        assertThat(attachments.collect(userId, workspace, "c", null, 0).attachments()).isEmpty();
        verify(runner, never()).readFileBytes(any(), anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    void twoAttachmentsWithTheSameNameAreRefused() throws Exception {
        lenient().when(runner.readFileBytes(any(), anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(chunk("x".getBytes(StandardCharsets.UTF_8), 1, false));

        assertThat(collect("[{\"path\":\"a/cr.txt\"},{\"path\":\"b/CR.txt\"}]").refusal()).contains("Deux pièces jointes");
    }

    @Test
    void contentTypesAreDeducedFromTheName() {
        assertThat(ClientMailAttachments.contentTypeOf("Procédure.DOCX")).contains("wordprocessingml");
        assertThat(ClientMailAttachments.contentTypeOf("inconnu.xyz")).isEqualTo("application/octet-stream");
        assertThat(ClientMailAttachments.baseName("C:\\docs\\cr.pdf")).isEqualTo("cr.pdf");
        assertThat(ClientMailAttachments.baseName("~/docs/cr.pdf")).isEqualTo("cr.pdf");
    }
}
