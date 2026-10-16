package fr.claudegateway.governance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-177 / SF-177-04 — « ce qui s'applique vraiment » : un paquet activé jamais déposé (EDENRED) et un
 * paquet en retard (CAGIP) se voient ; un autre compte reçoit un « introuvable ».
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GovernanceEffectiveApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private GovernancePackageRepository packages;
    @Autowired private GovernancePackageFileRepository packageFiles;
    @Autowired private GovernanceSelectionRepository selections;
    @Autowired private GovernanceActivationRepository activations;
    @Autowired private GovernanceDepositedFileRepository deposited;
    @Autowired private JwtService jwtService;
    @Autowired private fr.claudegateway.atelier.promptsource.PromptSourceFileRepository promptFiles;

    private String aliceToken;
    private String bobToken;
    private UUID edenred;
    private UUID cagip;
    private UUID aliceId;
    private UUID webId;

    @BeforeEach
    void setUp() {
        promptFiles.deleteAll();
        deposited.deleteAll();
        activations.deleteAll();
        selections.deleteAll();
        packageFiles.deleteAll();
        packages.deleteAll();
        workspaceRepository.deleteAll();
        hosts.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-eff@example.com");
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(seedUser("bob-eff@example.com"));
        edenred = hosts.save(RunnerHost.builder().userId(alice.getId()).name("EDENRED").build()).getId();
        cagip = hosts.save(RunnerHost.builder().userId(alice.getId()).name("CAGIP").build()).getId();
        aliceId = alice.getId();
        Workspace web = workspaceRepository.save(Workspace.builder()
                .userId(alice.getId()).name("web").hostId(cagip).projectPath("web").build());
        webId = web.getId();

        UUID pkg = packages.save(GovernancePackage.builder()
                .slug("savoir").name("Le savoir durable").version(17).published(true)
                .rules("Promouvoir le durable.").build()).getId();
        activations.save(GovernanceActivation.builder().userId(alice.getId()).hostId(edenred).packageId(pkg)
                .appliedVersion(17).status(GovernanceActivationStatus.PENDING).build());
        activations.save(GovernanceActivation.builder().userId(alice.getId()).hostId(cagip).packageId(pkg)
                .appliedVersion(17).status(GovernanceActivationStatus.APPLIED).build());
        for (int version : new int[] {2, 6}) {
            deposited.save(GovernanceDepositedFile.builder().userId(alice.getId()).hostId(cagip)
                    .workspaceId(web.getId()).packageId(pkg).path("STATE-" + version + ".md")
                    .digest("a".repeat(GovernanceDigest.LENGTH)).packageVersion(version).build());
        }
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    @Test
    void edenredIsNeverDeposited() throws Exception {
        mockMvc.perform(get("/api/governance/hosts/" + edenred + "/effective").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packages[0].state").value("JAMAIS_DEPOSE"))
                .andExpect(jsonPath("$.packages[0].depositedFiles").value(0));
    }

    @Test
    void cagipIsLate() throws Exception {
        mockMvc.perform(get("/api/governance/hosts/" + cagip + "/effective").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packages[0].state").value("EN_RETARD"))
                .andExpect(jsonPath("$.packages[0].message").value("En retard : fichiers en v2 à v6, paquet en v17."))
                .andExpect(jsonPath("$.subjects[0].name").value("web"));
    }

    /** F-182 / SF-182-01 — les agents nommés d'un sujet, tels que rangés au dernier tour, validés. */
    @Test
    void cagipShowsTheSubjectAgentsFromTheCache() throws Exception {
        cache("/__prompt_source_tree__", "README.md\n.claude/agents/relecteur.md\n.claude/agents/testeur.md");
        cache(".claude/agents/relecteur.md", "---\ndescription: Relit.\n---\nCONSIGNE_SECRETE");
        cache(".claude/agents/testeur.md", "---\noutils: ecriture\nmodele: claude-haiku-5\n---\nTeste.");

        mockMvc.perform(get("/api/governance/hosts/" + cagip + "/effective").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[0].name").value("relecteur"))
                .andExpect(jsonPath("$.agents[0].origin").value("SUJET"))
                .andExpect(jsonPath("$.agents[0].subjectName").value("web"))
                .andExpect(jsonPath("$.agents[0].access").value("LECTURE"))
                .andExpect(jsonPath("$.agents[1].name").value("testeur"))
                .andExpect(jsonPath("$.agents[1].access").value("ECRITURE"))
                .andExpect(jsonPath("$.agents[1].model").doesNotExist())
                .andExpect(jsonPath("$.agents[1].warnings[0]").value(org.hamcrest.Matchers.containsString("justification")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("CONSIGNE_SECRETE"))));
    }

    private void cache(String path, String content) {
        promptFiles.save(fr.claudegateway.atelier.promptsource.PromptSourceFile.builder().userId(aliceId)
                .hostId(cagip).workspaceId(webId).path(path).content(content).digest("d" + content.hashCode())
                .observedAt(java.time.OffsetDateTime.now()).build());
    }

    @Test
    void anotherAccountCannotReadIt() throws Exception {
        mockMvc.perform(get("/api/governance/hosts/" + cagip + "/effective").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }
}
