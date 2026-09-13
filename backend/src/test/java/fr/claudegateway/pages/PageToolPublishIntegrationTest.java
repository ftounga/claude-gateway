package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** {@code page_publish} range réellement : v1, v2, et jamais sur la page d'un autre (F-109 / SF-109-02). */
@SpringBootTest
@ActiveProfiles("test")
class PageToolPublishIntegrationTest {

    @Autowired private PageToolExecutor executor;
    @Autowired private PageService pageService;
    @Autowired private PageRepository pageRepository;
    @Autowired private UserRepository userRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        pageRepository.deleteAll();
        alice = seed("alice-page-tool@example.com");
        bob = seed("bob-page-tool@example.com");
    }

    private UUID seed(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder().email(email)
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build())).getId();
    }

    private Workspace terminal(UUID userId) {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(userId);
        workspace.setHostId(UUID.randomUUID());
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        return workspace;
    }

    private ObjectNode call(String title, String html, UUID pageId) {
        ObjectNode input = mapper.createObjectNode().put("title", title).put("html", html);
        if (pageId != null) {
            input.put("page_id", pageId.toString());
        }
        return input;
    }

    @Test
    @DisplayName("CA7 — republier avec page_id crée la version 2 de la même page")
    void republishCreatesVersionTwo() {
        Workspace workspace = terminal(alice);
        PageToolExecutor.Outcome first = executor.execute(alice, workspace, "c1", call("Maquette", "<h1>1</h1>", null));
        UUID pageId = first.published().page().getId();

        PageToolExecutor.Outcome second = executor.execute(alice, workspace, "c2", call("Maquette", "<h1>2</h1>", pageId));

        assertThat(second.error()).isFalse();
        assertThat(second.published().version().getVersion()).isEqualTo(2);
        assertThat(pageService.require(alice, pageId).getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(pageService.versions(alice, pageId)).hasSize(2);
    }

    @Test
    @DisplayName("CA10 — republier sur la page d'un autre compte : refusé comme un identifiant inconnu")
    void anotherAccountsPageIsUnknown() {
        UUID alicePage = executor.execute(alice, terminal(alice), "c1", call("A", "<p>a</p>", null))
                .published().page().getId();

        PageToolExecutor.Outcome stolen = executor.execute(bob, terminal(bob), "c2", call("B", "<p>b</p>", alicePage));

        assertThat(stolen.error()).isTrue();
        assertThat(stolen.content()).contains("page_id inconnu");
        assertThat(pageService.versions(alice, alicePage)).hasSize(1);
    }
}
