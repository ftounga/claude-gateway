package fr.claudegateway.jarvis.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-242 / SF-242-04 — le bref (une fois, sans appel au fournisseur, rien en présentation), le bloc « Courrier » du
 * cerveau rapide, et le corps relu par IMAP en texte seul (aucune image distante), isolé par compte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisMailBriefIntegrationTest {

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        MailTestServer.register(registry);
    }

    @MockitoBean private AiAgentProvider agentProvider;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JarvisMailAccountService accountService;
    @Autowired private JarvisMailPoller poller;
    @Autowired private JarvisMailBriefService briefs;
    @Autowired private JarvisMailTriageService realTriage;

    @BeforeEach
    void withoutTriage() {
        poller.setTriage(null);
    }

    @AfterEach
    void restoreTriage() {
        poller.setTriage(realTriage);
    }

    private record Box(User user, String bearer, String address, UUID accountId) {
    }

    private Box box(String prefix) {
        User user = userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        String password = "pw" + UUID.randomUUID().toString().substring(0, 12);
        String address = MailTestServer.newUser(prefix, password);
        UUID id = accountService.link(user.getId(), address, "Pro", password).id();
        return new Box(user, "Bearer " + jwtService.generateToken(user), address, id);
    }

    @Test
    void briefOnceWithoutAnyCallAndNothingInPresentation() throws Exception {
        Box b = box("brief");
        MailTestServer.deliver(b.address(), MailTestServer.raw("Helios <devis@helios.example>", b.address(),
                "Le devis", "Pouvez-vous signer ?"));
        MailTestServer.deliver(b.address(), MailTestServer.raw("banque@banque.example", b.address(),
                "Signature", "Merci de signer."));
        poller.poll(b.accountId());
        jdbc.update("update jarvis_mail_items set triage = 'A_TRAITER', triage_reason = 'Helios demande le devis signé' "
                + "where user_id = ? and sealed is not null and uid = (select min(uid) from jarvis_mail_items where user_id = ?)",
                b.user().getId(), b.user().getId());
        jdbc.update("update jarvis_mail_items set triage = 'A_TRAITER', triage_reason = 'Signature attendue' "
                + "where user_id = ? and triage = 'EN_ATTENTE'", b.user().getId());

        // En présentation : rien, et rien n'est marqué.
        mockMvc.perform(get("/api/jarvis/mail/brief?presentation=true").contextPath("/api")
                .header("Authorization", b.bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.text").value(""));

        String text = mockMvc.perform(get("/api/jarvis/mail/brief").contextPath("/api").header("Authorization", b.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(text).contains("Deux mails à traiter").contains("Helios demande le devis signé");
        // Rien de neuf depuis : bref vide.
        mockMvc.perform(get("/api/jarvis/mail/brief").contextPath("/api").header("Authorization", b.bearer()))
                .andExpect(jsonPath("$.text").value(""));
        verify(agentProvider, never()).nextTurn(any(AgentTurnRequest.class));

        // Le bloc « Courrier » du cerveau rapide : balisé non fiable ; en présentation, indisponible.
        String block = briefs.contextBlock(b.user().getId(), false);
        assertThat(block).contains("## Courrier").contains(UntrustedMail.OPEN).contains("courriel:")
                .contains("Helios").contains("à traiter");
        assertThat(briefs.contextBlock(b.user().getId(), true)).contains("Indisponible en présentation")
                .doesNotContain("Helios");
        assertThat(briefs.contextBlock(UUID.randomUUID(), false)).isEmpty();
    }

    @Test
    void bodyIsPlainTextWithoutRemoteImagesAndIsolated() throws Exception {
        Box a = box("body-a");
        Box b = box("body-b");
        String html = "From: Helios <devis@helios.example>\r\nTo: " + a.address() + "\r\nSubject: Devis\r\n"
                + "Message-ID: <" + UUID.randomUUID() + "@t>\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
                + "<html><body><p>Bonjour,</p><img src=\"https://espion.example/pixel.gif\">"
                + "<script>fetch('https://x')</script><p>Voici le devis.</p></body></html>\r\n";
        MailTestServer.deliver(a.address(), html);
        poller.poll(a.accountId());
        String id = jdbc.queryForObject("select id from jarvis_mail_items where user_id = ?", String.class,
                a.user().getId());

        String body = mockMvc.perform(get("/api/jarvis/mail/items/" + id).contextPath("/api")
                        .header("Authorization", a.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.item.subject").value("Devis"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Bonjour").contains("Voici le devis").doesNotContain("espion")
                .doesNotContain("<img").doesNotContain("fetch(");

        mockMvc.perform(get("/api/jarvis/mail/items/" + id).contextPath("/api").header("Authorization", b.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jarvis/mail/items/" + id + "?presentation=true").contextPath("/api")
                .header("Authorization", a.bearer())).andExpect(status().isConflict());
    }
}
