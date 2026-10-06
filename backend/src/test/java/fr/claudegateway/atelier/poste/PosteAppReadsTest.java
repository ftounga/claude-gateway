package fr.claudegateway.atelier.poste;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.mcp.McpSecretFilter;
import fr.claudegateway.pages.Page;
import fr.claudegateway.pages.PageService;
import fr.claudegateway.pages.PageSpace;
import fr.claudegateway.quota.UsageReportService;
import fr.claudegateway.radar.RadarBriefService;
import fr.claudegateway.radar.RadarReadService;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpaceService;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>Les lectures de l'application au terminal du poste</b> (F-178 / SF-178-03) : adaptées des outils MCP
 * F-112 (mêmes noms, mêmes services), déclarées au terminal du poste seulement, le Radar seulement s'il
 * existe ; périmètre = poste du terminal possédé ; secrets masqués ; contenus tiers marqués.
 */
class PosteAppReadsTest {

    private final RadarBriefService brief = mock(RadarBriefService.class);
    private final RadarReadService radarRead = mock(RadarReadService.class);
    private final RadarScopeResolver scopes = mock(RadarScopeResolver.class);
    private final PageService pages = mock(PageService.class);
    private final UsageReportService usage = mock(UsageReportService.class);
    private final TeamsAccessService teams = mock(TeamsAccessService.class);
    private final HostSpaceService spaces = mock(HostSpaceService.class);
    private final SubjectsStateService subjectsState = mock(SubjectsStateService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private PosteAppReadService reads;
    private PosteToolCatalog catalog;
    private PosteToolExecutor executor;

    @BeforeEach
    void setUp() {
        reads = new PosteAppReadService(brief, radarRead, scopes, pages, usage, teams, spaces, new McpSecretFilter(),
                mapper);
        catalog = new PosteToolCatalog(reads);
        executor = new PosteToolExecutor(catalog, subjectsState, reads);
    }

    private Workspace hostTerminal() {
        Workspace w = new Workspace();
        w.setId(UUID.randomUUID());
        w.setUserId(userId);
        w.setHostId(hostId);
        w.setHostTerminal(true);
        return w;
    }

    private Workspace subject() {
        Workspace w = hostTerminal();
        w.setHostTerminal(false);
        return w;
    }

    private List<String> names(Workspace w) {
        return catalog.toolsFor(userId, w).stream().map(AgentTool::name).toList();
    }

    private void radarOn() {
        when(teams.hasAccess(userId)).thenReturn(true);
        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenReturn(true);
        when(scopes.requireInVigie(userId, hostId)).thenReturn(new RadarScope(userId, hostId));
    }

    @Test
    @DisplayName("au terminal du poste : pages + consommation ; Radar seulement si le poste a un Radar")
    void declaration() {
        assertThat(names(hostTerminal())).containsExactly("sujets_etat", "pages_lister", "page_lire",
                "compte_consommation");
        radarOn();
        assertThat(names(hostTerminal())).containsExactly("sujets_etat", "radar_resume", "radar_sujets",
                "pages_lister", "page_lire", "compte_consommation");
        assertThat(names(subject())).isEmpty();
    }

    @Test
    @DisplayName("radar_resume relaie le brief du poste du terminal, marqué non fiable")
    void radarResume() {
        radarOn();
        when(brief.brief(any())).thenReturn(null);
        RadarToolOutcomeCheck.ok(executor.execute(userId, hostTerminal(), "radar_resume", null),
                "Contenu tiers");
        verify(scopes).requireInVigie(userId, hostId);
    }

    @Test
    @DisplayName("radar_* sans Radar : refusé, rien n'est lu")
    void radarRefusedWithoutRadar() {
        PosteToolExecutor.Outcome out = executor.execute(userId, hostTerminal(), "radar_sujets", null);
        assertThat(out.error()).isTrue();
        verify(radarRead, never()).subjects(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("dans un sujet : toute lecture refusée, rien n'est lu")
    void refusedInASubject() {
        assertThat(executor.execute(userId, subject(), "compte_consommation", null).error()).isTrue();
        verify(usage, never()).buildReport(any());
    }

    @Test
    @DisplayName("pages_lister : les pages de CE poste, espace FORGE par défaut")
    void pagesLister() {
        Page page = new Page();
        page.setId(UUID.randomUUID());
        page.setTitle("Architecture cible");
        when(pages.list(userId, hostId, PageSpace.FORGE)).thenReturn(List.of(page));

        PosteToolExecutor.Outcome out = executor.execute(userId, hostTerminal(), "pages_lister", null);

        assertThat(out.error()).isFalse();
        assertThat(out.content()).contains("Architecture cible").contains("1 page(s) FORGE");
    }

    @Test
    @DisplayName("page_lire : texte de la page (balises retirées), secrets masqués ; page d'un autre poste refusée")
    void pageLire() throws Exception {
        UUID pageId = UUID.randomUUID();
        Page page = new Page();
        page.setId(pageId);
        page.setTitle("Runbook");
        page.setHostId(hostId);
        when(pages.require(userId, pageId)).thenReturn(page);
        String html = "<html><head><style>x{}</style></head><body><h1>Jeton</h1>"
                + "<p>clé sk-ant-abcdefghijklmnop</p><script>alert(1)</script></body></html>";
        when(pages.html(userId, pageId, null)).thenReturn(
                new PageService.PageContent(page, 3, html.getBytes(StandardCharsets.UTF_8), "text/html"));

        PosteToolExecutor.Outcome out = executor.execute(userId, hostTerminal(), "page_lire",
                mapper.readTree("{\"page_id\":\"" + pageId + "\"}"));

        assertThat(out.error()).isFalse();
        assertThat(out.content()).contains("Jeton").contains(McpSecretFilter.MASK).contains("version 3")
                .doesNotContain("<p>").doesNotContain("alert").doesNotContain("sk-ant-abc");

        page.setHostId(UUID.randomUUID());
        assertThat(executor.execute(userId, hostTerminal(), "page_lire",
                mapper.readTree("{\"page_id\":\"" + pageId + "\"}")).error()).isTrue();
        assertThat(executor.execute(userId, hostTerminal(), "page_lire",
                mapper.readTree("{\"page_id\":\"pas-un-uuid\"}")).error()).isTrue();
    }

    @Test
    @DisplayName("compte_consommation relaie le rapport du compte du tour")
    void consommation() {
        when(usage.buildReport(userId)).thenReturn(null);
        assertThat(executor.execute(userId, hostTerminal(), "compte_consommation", null).error()).isFalse();
        verify(usage).buildReport(eq(userId));
    }

    /** Petit utilitaire d'assertion. */
    private static final class RadarToolOutcomeCheck {
        static void ok(PosteToolExecutor.Outcome out, String contains) {
            assertThat(out.error()).as(out.content()).isFalse();
            assertThat(out.content()).contains(contains);
        }
    }
}
