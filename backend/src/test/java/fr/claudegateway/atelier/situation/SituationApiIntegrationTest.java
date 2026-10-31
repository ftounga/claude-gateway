package fr.claudegateway.atelier.situation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.sends.MessageBlockSendRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-225 / SF-225-01 — « où on en était » : la dernière demande et la réponse, les encarts mail envoyés ou non avec
 * l'attente liée, les attentes, les suites ; et <b>le terminal d'Alice introuvable pour Bob</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SituationApiIntegrationTest {

    private static final String REPLY = "<<essentiel>>Le mail à Zahi est prêt.<</essentiel>>\n\n"
            + "```mail attente=acces-forge\nÀ : Zahi\nObjet : Compte forge\n\nBonjour Zahi\n```\n\n"
            + "```message\nSalut l'équipe\n```\n";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private TerminalActionRepository actionRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private MessageBlockSendRepository sendRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        sendRepository.deleteAll();
        messageRepository.deleteAll();
        actionRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        aliceToken = tokenFor("alice-situation@ex.com");
        aliceId = userRepository.findByEmail("alice-situation@ex.com").orElseThrow().getId();
        bobToken = tokenFor("bob-situation@ex.com");
        workspaceId = createWorkspace(aliceToken, "Connecteur AGENOR");
    }

    @Test
    @DisplayName("le point de situation compose demande, réponse, encarts (envoyés ou non), attentes et suites")
    void composesTheSituation() throws Exception {
        UUID ws = UUID.fromString(workspaceId);
        messageRepository.save(AtelierMessage.builder().workspaceId(ws).userId(aliceId).role("USER")
                .content("Prépare le mail pour Zahi").createdAt(OffsetDateTime.now().minusHours(6)).build());
        UUID reply = messageRepository.save(AtelierMessage.builder().workspaceId(ws).userId(aliceId)
                .role("ASSISTANT").content(REPLY).createdAt(OffsetDateTime.now().minusHours(5))
                .terminalJson("{\"nextSteps\":[{\"title\":\"Relancer Zahi\",\"request\":\"Relance Zahi\"}]}")
                .build()).getId();
        UUID actionId = attente("acces-forge");
        perform(aliceToken, post("/api/workspaces/" + workspaceId + "/messages/" + reply + "/sends/1"))
                .andExpect(status().isOk());

        String body = perform(aliceToken, get("/api/workspaces/" + workspaceId + "/situation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("LIBRE"))
                .andExpect(jsonPath("$.idleHours").value(0))
                .andExpect(jsonPath("$.exchange.request").value("Prépare le mail pour Zahi"))
                .andExpect(jsonPath("$.exchange.reply").value(org.hamcrest.Matchers.containsString("<<essentiel>>")))
                .andExpect(jsonPath("$.envois.length()").value(2))
                .andExpect(jsonPath("$.attentes.length()").value(1))
                .andExpect(jsonPath("$.attentes[0].id").value(actionId.toString()))
                .andExpect(jsonPath("$.suites[0].title").value("Relancer Zahi"))
                .andExpect(jsonPath("$.suites[0].request").value("Relance Zahi"))
                .andReturn().getResponse().getContentAsString();
        java.util.List<Object> mailSent = JsonPath.read(body, "$.envois[?(@.kind == 'MAIL')].sentAt");
        java.util.List<String> mailTo = JsonPath.read(body, "$.envois[?(@.kind == 'MAIL')].recipient");
        java.util.List<String> mailAttente = JsonPath.read(body, "$.envois[?(@.kind == 'MAIL')].attenteStatus");
        java.util.List<Object> messageSent = JsonPath.read(body, "$.envois[?(@.kind == 'MESSAGE')].sentAt");
        org.assertj.core.api.Assertions.assertThat(mailSent).containsExactly((Object) null);
        org.assertj.core.api.Assertions.assertThat(mailTo).containsExactly("Zahi");
        java.util.List<String> excerpt = JsonPath.read(body, "$.envois[?(@.kind == 'MAIL')].excerpt");
        org.assertj.core.api.Assertions.assertThat(excerpt).containsExactly("Bonjour Zahi");
        org.assertj.core.api.Assertions.assertThat(mailAttente).containsExactly("A_FAIRE");
        org.assertj.core.api.Assertions.assertThat(messageSent).hasSize(1).doesNotContainNull();
    }

    @Test
    @DisplayName("un terminal neuf : rien à dire, sans erreur ; Bob → 404")
    void emptyAndIsolated() throws Exception {
        perform(aliceToken, get("/api/workspaces/" + workspaceId + "/situation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastActivityAt").doesNotExist())
                .andExpect(jsonPath("$.exchange").doesNotExist())
                .andExpect(jsonPath("$.chantiers.length()").value(0))
                .andExpect(jsonPath("$.envois.length()").value(0));
        perform(bobToken, get("/api/workspaces/" + workspaceId + "/situation")).andExpect(status().isNotFound());
    }

    // ---- outillage ----

    private ResultActions perform(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + token));
    }

    private UUID attente(String key) throws Exception {
        String body = perform(aliceToken, post("/api/workspaces/" + workspaceId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Demander le compte forge\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        TerminalAction action = actionRepository.findById(id).orElseThrow();
        action.setDedupKey(key);
        actionRepository.save(action);
        return id;
    }

    private String tokenFor(String email) {
        User user = userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        subscriptionRepository.save(Subscription.builder().userId(user.getId())
                .planCode(PlanCode.GOLD).status(SubscriptionStatus.ACTIVE).build());
        return jwtService.generateToken(user);
    }

    private String createWorkspace(String token, String name) throws Exception {
        String body = mockMvc.perform(multipart("/api/workspaces").file(zip()).contextPath("/api")
                        .param("name", name)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static MockMultipartFile zip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("README.md"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return new MockMultipartFile("file", "project.zip", "application/zip", out.toByteArray());
    }
}
