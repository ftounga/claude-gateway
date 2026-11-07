package fr.claudegateway.jarvis.clientmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.mail.JarvisMailItem;
import fr.claudegateway.jarvis.mail.JarvisMailItemRepository;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.shared.jobs.AsyncJobQueue;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-249 / SF-249-06 — les mails répétitifs : repérés sans IA, proposés une fois, acceptés ⇒ écartés avant toute IA,
 * retirés ⇒ retriés ; isolation par compte et par client.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientMailIgnoreIntegrationTest {

    @MockitoBean private RunnerTokenAuthenticator authenticator;
    @MockitoBean private AiAgentProvider agentProvider;
    /** La file durable est traitée par le test lui-même : le travailleur planifié ne doit pas la lui prendre. */
    @MockitoBean private fr.claudegateway.shared.jobs.AsyncJobWorker jobWorker;

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private ClientMailDepositJobHandler handler;
    @Autowired private AsyncJobQueue queue;
    @Autowired private ClientMailIgnoreService ignore;
    @Autowired private JarvisMailItemRepository items;
    @Autowired private JwtService jwt;

    private User alice;
    private User bob;
    private RunnerHost edenred;
    private RunnerHost cagip;

    @BeforeEach
    void setUp() {
        alice = user("ign-a");
        bob = user("ign-b");
        edenred = host(alice, "EDENRED");
        cagip = host(alice, "CAGIP");
        when(authenticator.authenticate(eq("tok-e")))
                .thenReturn(Optional.of(new RunnerIdentity(UUID.randomUUID(), alice.getId(), edenred.getId())));
        when(authenticator.authenticate(eq("tok-c")))
                .thenReturn(Optional.of(new RunnerIdentity(UUID.randomUUID(), alice.getId(), cagip.getId())));
        when(agentProvider.nextTurn(any(AgentTurnRequest.class))).thenReturn(new AgentTurn(
                "[{\"id\":\"m1\",\"classe\":\"A_LIRE\",\"raison\":\"Info\"}]", List.of(), true, 100, 10));
    }

    private User user(String p) {
        return users.save(User.builder().email(p + "-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    private RunnerHost host(User u, String name) {
        return hosts.save(RunnerHost.builder().userId(u.getId()).name(name).rootName("dev").os("linux")
                .shell("posix").elevated(false).build());
    }

    private void deposit(String token, String from, String subject, boolean bulk) throws Exception {
        String eml = "From: " + from + "\r\nSubject: " + subject + "\r\nMessage-ID: <" + UUID.randomUUID() + "@x>\r\n"
                + (bulk ? "List-Unsubscribe: <mailto:u@x>\r\n" : "")
                + "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH).format(OffsetDateTime.now())
                + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\ncorps " + UUID.randomUUID();
        mvc.perform(post("/runner/mail-deposit").contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(eml.getBytes(StandardCharsets.UTF_8)).header("X-File-Name", "m.eml")
                .header("X-Runner-Token", token)).andExpect(status().isOk());
        for (AsyncJobQueue.Job job : queue.claim(ClientMailDepositService.JOB_KIND, 50, "t")) {
            handler.handle(job);
            queue.complete(job.id(), "t", 1, java.math.BigDecimal.ZERO);
        }
    }

    private List<JarvisMailItem> of(RunnerHost h) {
        return items.findByUserIdAndHostIdOrderByReceivedAtDesc(alice.getId(), h.getId(),
                org.springframework.data.domain.PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("Repérage à 10 dépôts ; accepter ⇒ écartés, le suivant sans appel au modèle ; retirer ⇒ retriés")
    void detectAcceptRemove() throws Exception {
        for (int i = 0; i < 4; i++) {
            deposit("tok-e", "alertes@monitoring.edenred.com", "[PROD] Job " + (1000 + i) + " KO", false);
        }
        for (String subject : List.of("Revue budget", "Point équipe", "Demande accès", "Livraison lot",
                "Question SSO", "Planning sprint")) {
            deposit("tok-e", "remi@edenred.com", subject, false);
        }
        String a = "Bearer " + jwt.generateToken(alice);
        String b = "Bearer " + jwt.generateToken(bob);
        String body = mvc.perform(get("/jarvis/client-mail").header("Authorization", a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.offers.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String senderOffer = com.jayway.jsonpath.JsonPath.read(body, "$.offers[?(@.kind=='SENDER')].id").toString()
                .replaceAll("[\\[\\]\"]", "");
        mvc.perform(post("/jarvis/client-mail/offers/" + senderOffer + "/accept").header("Authorization", b))
                .andExpect(status().isNotFound());
        mvc.perform(post("/jarvis/client-mail/offers/" + senderOffer + "/accept").header("Authorization", a))
                .andExpect(status().isOk());
        mvc.perform(post("/jarvis/client-mail/offers/" + senderOffer + "/accept").header("Authorization", a))
                .andExpect(status().isConflict());
        assertThat(of(edenred).stream().filter(i -> i.getTriageReason() != null
                && i.getTriageReason().equals(ClientMailIgnoreService.RULE_REASON))).hasSize(4);

        clearInvocations(agentProvider);
        deposit("tok-e", "alertes@monitoring.edenred.com", "[PROD] Job 2000 KO", false);
        verify(agentProvider, never()).nextTurn(any(AgentTurnRequest.class));
        assertThat(of(edenred).get(0).getTriage()).isEqualTo(JarvisMailItem.Triage.A_IGNORER);

        // Une règle d'EDENRED ne touche pas CAGIP.
        deposit("tok-c", "alertes@monitoring.edenred.com", "[PROD] Job 3000 KO", false);
        assertThat(of(cagip).get(0).getTriageReason()).isNotEqualTo(ClientMailIgnoreService.RULE_REASON);

        String rules = mvc.perform(get("/jarvis/client-mail").header("Authorization", a))
                .andReturn().getResponse().getContentAsString();
        String ruleId = com.jayway.jsonpath.JsonPath.read(rules, "$.rules[0].id");
        mvc.perform(delete("/jarvis/client-mail/rules/" + ruleId).header("Authorization", b))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/jarvis/client-mail/rules/" + ruleId).header("Authorization", a))
                .andExpect(status().isNoContent());
        assertThat(of(edenred).stream().filter(i -> ClientMailIgnoreService.RULE_REASON.equals(i.getTriageReason())))
                .isEmpty();
    }

    @Test
    @DisplayName("Garder : jamais reproposé ; moins de 10 courriers : rien")
    void declineAndThreshold() throws Exception {
        for (int i = 0; i < 9; i++) {
            deposit("tok-e", "noreply@jira.edenred.com", "Ticket mis à jour", true);
        }
        assertThat(ignore.pendingOffers(alice.getId())).isEmpty();
        deposit("tok-e", "noreply@jira.edenred.com", "Ticket mis à jour", true);
        List<ClientMailIgnoreService.OfferView> offers = ignore.pendingOffers(alice.getId());
        assertThat(offers).isNotEmpty();
        offers.forEach(o -> ignore.decline(alice.getId(), o.id()));
        assertThat(ignore.detectNow(alice.getId(), edenred.getId())).isZero();
        assertThat(ignore.pendingOffers(alice.getId())).isEmpty();
    }
}
