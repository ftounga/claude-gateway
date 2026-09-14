package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le critère du reliquat</b> (F-109 / SF-109-06) : une page avec <b>deux captures de la machine</b> se range
 * et se sert — chaque capture avec {@code image/png} et la même politique de sécurité qu'une page. Le runner est
 * simulé (aucune machine connectée), le reste de la chaîne est réel (rangement, stockage, service).
 */
@SpringBootTest
@ActiveProfiles("test")
class PageToolImageIntegrationTest {

    @Autowired private PageToolExecutor executor;
    @Autowired private PageService pageService;
    @Autowired private PageRepository pageRepository;
    @Autowired private PageVersionRepository versionRepository;
    @Autowired private UserRepository userRepository;

    @MockBean private RunnerToolGateway runner;
    @MockBean private RunnerAuditService audit;

    private final ObjectMapper mapper = new ObjectMapper();
    private UUID alice;

    @BeforeEach
    void setUp() {
        versionRepository.deleteAll();
        pageRepository.deleteAll();
        alice = userRepository.findByEmail("alice-page-image@example.com").map(User::getId)
                .orElseGet(() -> userRepository.save(User.builder().email("alice-page-image@example.com")
                        .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build()).getId());
    }

    private static RunnerCallResult imageChunk(byte[] pixels) {
        return new RunnerCallResult(true, Base64.getEncoder().encodeToString(pixels), false, null, 2L,
                (long) pixels.length, null, null, "", false);
    }

    @Test
    @DisplayName("CA11 — une page avec deux captures de la machine se range et chaque image est servie en image/png")
    void twoMachineCapturesArePublishedAndServed() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(alice);
        workspace.setHostId(UUID.randomUUID());
        workspace.setProjectPath("");
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        RunnerTarget target = new RunnerTarget(workspace.getHostId(), workspace.getId(), "");

        byte[] first = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        byte[] second = {(byte) 0x89, 'P', 'N', 'G', 4, 5, 6, 7};
        when(runner.readFile(eq(target), anyString(), eq("report.html")))
                .thenReturn(new RunnerCallResult(true,
                        "<h1>CR</h1><img src=\"cap-1.png\"><img src=\"cap-2.png\">", false, null, 1L, 40L, null, null,
                        "", false));
        when(runner.readFileBytes(eq(target), anyString(), eq("shots/one.png"), anyLong(), anyInt()))
                .thenReturn(imageChunk(first));
        when(runner.readFileBytes(eq(target), anyString(), eq("shots/two.png"), anyLong(), anyInt()))
                .thenReturn(imageChunk(second));

        ObjectNode input = mapper.createObjectNode().put("title", "CR réunion").put("path", "report.html");
        ArrayNode attachments = input.putArray("attachments");
        attachments.addObject().put("name", "cap-1.png").put("path", "shots/one.png");
        attachments.addObject().put("name", "cap-2.png").put("path", "shots/two.png");

        PageToolExecutor.Outcome outcome = executor.execute(alice, workspace, "call-1", input);

        assertThat(outcome.error()).isFalse();
        UUID pageId = outcome.published().page().getId();
        assertThat(pageService.versions(alice, pageId)).hasSize(1);
        assertThat(outcome.published().version().getAttachmentCount()).isEqualTo(2);

        Optional<PageService.PageContent> capOne = pageService.attachment(alice, pageId, null, "cap-1.png");
        Optional<PageService.PageContent> capTwo = pageService.attachment(alice, pageId, null, "cap-2.png");
        assertThat(capOne).isPresent();
        assertThat(capOne.get().contentType()).isEqualTo("image/png");
        assertThat(capOne.get().content()).isEqualTo(first);
        assertThat(capTwo).isPresent();
        assertThat(capTwo.get().contentType()).isEqualTo("image/png");
        assertThat(capTwo.get().content()).isEqualTo(second);

        // Servie avec la même politique de sécurité qu'une page (origine opaque, CSP sandbox, nosniff).
        assertThat(PageContentPolicy.headers("image/png").getFirst("Content-Security-Policy"))
                .contains("sandbox");
    }

    @Test
    @DisplayName("le lien de la page pointe une image absente sans erreur (nom inconnu) : 404 propre côté service")
    void unknownAttachmentIsEmpty() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(alice);
        workspace.setHostId(UUID.randomUUID());
        workspace.setProjectPath("");
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(runner.readFile(any(), anyString(), eq("p.html")))
                .thenReturn(new RunnerCallResult(true, "<h1>x</h1>", false, null, 1L, 8L, null, null, "", false));

        ObjectNode input = mapper.createObjectNode().put("title", "Sans image").put("path", "p.html");
        UUID pageId = executor.execute(alice, workspace, "c", input).published().page().getId();

        assertThat(pageService.attachment(alice, pageId, null, "absente.png")).isEmpty();
    }
}
