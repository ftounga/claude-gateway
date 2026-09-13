package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.email.ClientMailMessage;
import fr.claudegateway.email.EmailService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** La file et l'état de remise, de bout en bout sur base réelle (F-110 / SF-110-02). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientEmailApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private ClientEmailRepository emailRepository;
    @Autowired private HostMailAddressRepository addressRepository;
    @Autowired private ClientMailTool tool;
    @Autowired private ClientMailOutbox outbox;
    @Autowired private JwtService jwtService;
    @MockitoBean private EmailService emailService;

    private final ObjectMapper mapper = new ObjectMapper();
    private User vera;
    private String veraToken;
    private Workspace veraTerminal;
    private String bobToken;
    private String noraToken;

    @BeforeEach
    void setUp() {
        emailRepository.deleteAll();
        addressRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        vera = seedUser("vera-send@example.com", UserRole.USER);
        veraToken = jwtService.generateToken(vera);
        subscribe(vera, PlanCode.GOLD_VIGIE);
        RunnerHost host = hostRepository.save(RunnerHost.builder().userId(vera.getId()).name("CAGIP").rootName("dev")
                .os("linux").shell("posix").elevated(false).build());
        addressRepository.save(HostMailAddress.builder().userId(vera.getId()).hostId(host.getId())
                .address("vera@cagip.fr").verifiedAt(java.time.OffsetDateTime.now()).build());
        veraTerminal = workspaceRepository.save(Workspace.builder().userId(vera.getId()).name("CAGIP")
                .hostId(host.getId()).projectPath("").teamsTerminal(true)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());

        User bob = seedUser("bob-send@example.com", UserRole.USER);
        bobToken = jwtService.generateToken(bob);
        subscribe(bob, PlanCode.GOLD);

        User nora = seedUser("nora-send@example.com", UserRole.USER);
        noraToken = jwtService.generateToken(nora);
        subscribe(nora, PlanCode.SOLO);
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private void subscribe(User user, PlanCode plan) {
        Subscription subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().userId(user.getId()).build());
        subscription.setPlanCode(plan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setAtelierOptionStatus(null);
        subscription.setTeamsOptionStatus(null);
        subscriptionRepository.save(subscription);
    }

    private ClientMailTool.Outcome send(String subject) throws Exception {
        return tool.send(vera.getId(), veraTerminal, mapper.readTree(
                "{\"subject\":\"" + subject + "\",\"body\":\"# CR\\n\\n- MFA en octobre\",\"to\":\"tiers@ailleurs.fr\"}"));
    }

    @Test
    void queuedThenSentByTheWorkerThenReadAsAcceptedWithoutBody() throws Exception {
        ClientMailTool.Outcome outcome = send("Compte rendu MFA");
        assertThat(outcome.error()).isFalse();
        UUID id = UUID.fromString(outcome.receipt().emailId());

        mockMvc.perform(get("/api/client-emails/" + id).contextPath("/api").header("Authorization", "Bearer " + veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.recipient").value("vera@cagip.fr"));

        assertThat(outbox.runOnce()).isEqualTo(1);

        ArgumentCaptor<ClientMailMessage> message = ArgumentCaptor.forClass(ClientMailMessage.class);
        verify(emailService).sendClientMail(message.capture());
        assertThat(message.getValue().to()).isEqualTo("vera@cagip.fr");
        assertThat(message.getValue().displayName()).isEqualTo("claude-gateway pour CAGIP");
        assertThat(message.getValue().html()).contains("<li>MFA en octobre</li>");

        mockMvc.perform(get("/api/client-emails/" + id).contextPath("/api").header("Authorization", "Bearer " + veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.subject").value("Compte rendu MFA"))
                .andExpect(jsonPath("$.clientName").value("CAGIP"))
                .andExpect(jsonPath("$.bodyText").doesNotExist())
                .andExpect(jsonPath("$.bodyHtml").doesNotExist());
        ClientEmail row = emailRepository.findById(id).orElseThrow();
        assertThat(row.getBodyText()).isNull();
        assertThat(row.getBodyHtml()).isNull();
        assertThat(row.getSizeBytes()).isPositive();
        assertThat(outbox.runOnce()).as("rien à renvoyer").isZero();
    }

    @Test
    void anotherUsersMailIsNotFoundAndTheRightIsRequired() throws Exception {
        UUID id = UUID.fromString(send("CR").receipt().emailId());

        mockMvc.perform(get("/api/client-emails/" + id).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/client-emails/" + id).contextPath("/api").header("Authorization", "Bearer " + noraToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void theDailyLimitHoldsOnTheRealBase() throws Exception {
        for (int i = 0; i < ClientMailTool.DAILY_LIMIT; i++) {
            assertThat(send("CR " + i).error()).isFalse();
        }
        ClientMailTool.Outcome refused = send("CR de trop");
        assertThat(refused.error()).isTrue();
        assertThat(refused.content()).contains("Limite de 50");
        assertThat(emailRepository.count()).isEqualTo(ClientMailTool.DAILY_LIMIT);
    }
}
