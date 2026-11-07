package fr.claudegateway.jarvis.clientmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.mail.JarvisMailItemService;
import fr.claudegateway.jarvis.notices.JarvisNoticeService;
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
 * F-249 / SF-249-03 — rattacher (mots, fil, fait F-247), annoncer en cumulant, jamais de message dans le fil, récap du
 * matin, routes vue/dite, isolation A/B (compte et client).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientMailNoticesIntegrationTest {

    @MockitoBean private RunnerTokenAuthenticator authenticator;
    @MockitoBean private AiAgentProvider agentProvider;
    /** La file durable est traitée par le test lui-même : le travailleur planifié ne doit pas la lui prendre. */
    @MockitoBean private fr.claudegateway.shared.jobs.AsyncJobWorker jobWorker;

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private ClientMailDepositJobHandler handler;
    @Autowired private AsyncJobQueue queue;
    @Autowired private JarvisNoticeService notices;
    @Autowired private ClientMailRecap recap;
    @Autowired private JarvisMailItemService itemService;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;

    private User alice;
    private User bob;
    private RunnerHost edenred;
    private RunnerHost cagip;
    private Workspace fortec;
    private Workspace fortecChezCagip;
    private Workspace sso;

    @BeforeEach
    void setUp() {
        alice = user("ann-a");
        bob = user("ann-b");
        edenred = host(alice, "EDENRED");
        cagip = host(alice, "CAGIP");
        RunnerHost bobHost = host(bob, "EDENRED");
        fortec = subject(alice, edenred, "Alarme Fortec");
        fortecChezCagip = subject(alice, cagip, "Alarme Fortec");
        sso = subject(alice, edenred, "Bascule SSO");
        subject(bob, bobHost, "Alarme Fortec");
        identity("tok-edenred", alice, edenred);
        identity("tok-cagip", alice, cagip);
        identity("tok-bob", bob, bobHost);
        when(agentProvider.nextTurn(any(AgentTurnRequest.class))).thenReturn(new AgentTurn(
                "[{\"id\":\"m1\",\"classe\":\"A_LIRE\",\"raison\":\"Information\"}]", List.of(), true, 100, 10));
    }

    private User user(String p) {
        return users.save(User.builder().email(p + "-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    private RunnerHost host(User u, String name) {
        return hosts.save(RunnerHost.builder().userId(u.getId()).name(name).rootName("dev").os("linux")
                .shell("posix").elevated(false).build());
    }

    private Workspace subject(User u, RunnerHost h, String name) {
        return workspaces.save(Workspace.builder().userId(u.getId()).name(name).hostId(h.getId())
                .executionTarget(WorkspaceExecutionTarget.RUNNER).createdAt(OffsetDateTime.now()).build());
    }

    private void identity(String token, User u, RunnerHost h) {
        when(authenticator.authenticate(eq(token)))
                .thenReturn(Optional.of(new RunnerIdentity(UUID.randomUUID(), u.getId(), h.getId())));
    }

    private void deposit(String token, String messageId, String inReplyTo, String subject, String body)
            throws Exception {
        String eml = "From: Rémi <remi@client.com>\r\nSubject: " + subject + "\r\nMessage-ID: " + messageId + "\r\n"
                + (inReplyTo == null ? "" : "In-Reply-To: " + inReplyTo + "\r\n")
                + "Date: " + java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(java.util.Locale.ENGLISH)
                        .format(OffsetDateTime.now()) + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body;
        mvc.perform(post("/runner/mail-deposit").contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(eml.getBytes(java.nio.charset.StandardCharsets.UTF_8)).header("X-File-Name", "m.eml")
                .header("X-Runner-Token", token)).andExpect(status().isOk());
        for (AsyncJobQueue.Job job : queue.claim(ClientMailDepositService.JOB_KIND, 50, "t")) {
            handler.handle(job);
            queue.complete(job.id(), "t", 1, java.math.BigDecimal.ZERO);
        }
    }

    private List<UUID> linksOf(Workspace w) {
        return jdbc.queryForList("select item_id from jarvis_mail_subject_links where workspace_id = ?", UUID.class,
                w.getId());
    }

    @Test
    @DisplayName("Mots : rattaché au sujet du client du dépôt, jamais à l'homonyme d'un autre client ou compte ; "
            + "annonce cumulée ; aucun message dans le fil")
    void linkAnnounceAndNoMessage() throws Exception {
        Integer messagesBefore = jdbc.queryForObject("select count(*) from atelier_messages where workspace_id = ?",
                Integer.class, fortec.getId());
        deposit("tok-edenred", "<a1@x>", null, "RE: Alarme Fortec", "corrigée");
        deposit("tok-edenred", "<a2@x>", null, "Fortec : alarme toujours là ?", "");
        assertThat(linksOf(fortec)).hasSize(2);
        assertThat(linksOf(fortecChezCagip)).isEmpty();
        assertThat(linksOf(sso)).isEmpty();

        List<JarvisNoticeService.NoticeView> open = notices.open(alice.getId());
        assertThat(open).hasSize(1);
        assertThat(open.get(0).text()).isEqualTo("2 mails sur « Alarme Fortec » (EDENRED)");
        assertThat(open.get(0).speech()).isEqualTo("Deux nouveaux mails sur « Alarme Fortec », chez EDENRED.");
        assertThat(open.get(0).link()).isEqualTo("/atelier/" + fortec.getId());
        assertThat(jdbc.queryForObject("select count(*) from atelier_messages where workspace_id = ?", Integer.class,
                fortec.getId())).isEqualTo(messagesBefore);
        assertThat(notices.open(bob.getId())).isEmpty();

        // Le libellé du courrier dit le client et le sujet.
        assertThat(itemService.list(alice.getId(), null, 10).get(0).account())
                .isEqualTo("Outlook EDENRED · « Alarme Fortec »");

        // Vue, puis un nouveau mail : l'annonce repart à 1.
        mvc.perform(post("/jarvis/notices/" + open.get(0).id() + "/seen").header("Authorization",
                "Bearer " + jwt.generateToken(alice))).andExpect(status().isOk());
        deposit("tok-edenred", "<a3@x>", null, "Alarme Fortec clos", "");
        assertThat(notices.open(alice.getId()).get(0).text()).isEqualTo("1 mail sur « Alarme Fortec » (EDENRED)");
    }

    @Test
    @DisplayName("Fil : la réponse hérite du sujet même sans le nom ; fait F-247 : la clé rattache")
    void threadAndFacts() throws Exception {
        deposit("tok-edenred", "<t1@x>", null, "Alarme Fortec", "");
        deposit("tok-edenred", "<t2@x>", "<t1@x>", "Re: question", "ok pour moi");
        assertThat(linksOf(fortec)).hasSize(2);

        jdbc.update("insert into jarvis_state_facts (id, user_id, host_id, workspace_id, object_kind, object_key, "
                + "attribute, value_sealed, observed_at, source, confidence, created_at, updated_at) values "
                + "(?,?,?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), alice.getId(), edenred.getId(), sso.getId(),
                "TICKET", "JIRA:IDP-77", "status", "x", Timestamp.from(java.time.Instant.now()), "RELEVE", "HAUTE",
                Timestamp.from(java.time.Instant.now()), Timestamp.from(java.time.Instant.now()));
        deposit("tok-edenred", "<t3@x>", null, "Ticket IDP-77 mis à jour", "");
        assertThat(linksOf(sso)).hasSize(1);
    }

    @Test
    @DisplayName("À ignorer : ni rattaché ni annoncé")
    void ignoredIsNotAnnounced() throws Exception {
        when(agentProvider.nextTurn(any(AgentTurnRequest.class))).thenReturn(new AgentTurn(
                "[{\"id\":\"m1\",\"classe\":\"A_IGNORER\",\"raison\":\"Publicité\"}]", List.of(), true, 100, 10));
        deposit("tok-edenred", "<i1@x>", null, "Alarme Fortec en promo", "");
        assertThat(linksOf(fortec)).isEmpty();
        assertThat(notices.open(alice.getId())).isEmpty();
    }

    @Test
    @DisplayName("Récap du matin : comptes par client et par sujet ; une seule fois ; rien sans dépôt")
    void morningRecap() throws Exception {
        deposit("tok-edenred", "<r1@x>", null, "Alarme Fortec", "");
        deposit("tok-edenred", "<r2@x>", null, "Divers", "");
        deposit("tok-cagip", "<r3@x>", null, "Autre", "");
        assertThat(recap.recap(alice.getId(), OffsetDateTime.now())).isNotNull();
        assertThat(recap.recap(alice.getId(), OffsetDateTime.now())).isNull();
        JarvisNoticeService.NoticeView v = notices.open(alice.getId()).stream()
                .filter(n -> n.kind().equals("MORNING_RECAP")).findFirst().orElseThrow();
        assertThat(v.text()).isEqualTo(
                "Hier : 3 mails déposés (EDENRED 2, CAGIP 1), dont 1 sur vos sujets : « Alarme Fortec » (1).");
        assertThat(v.speech()).contains("trois mails déposés", "« Alarme Fortec »");
        assertThat(recap.recap(bob.getId(), OffsetDateTime.now())).isNull();
    }

    @Test
    @DisplayName("Isolation : B ne ferme ni ne marque l'annonce de A (404) ; la liste de B est vide")
    void isolation() throws Exception {
        deposit("tok-edenred", "<s1@x>", null, "Alarme Fortec", "");
        UUID id = notices.open(alice.getId()).get(0).id();
        String bobBearer = "Bearer " + jwt.generateToken(bob);
        mvc.perform(post("/jarvis/notices/" + id + "/seen").header("Authorization", bobBearer))
                .andExpect(status().isNotFound());
        mvc.perform(post("/jarvis/notices/" + id + "/spoken").header("Authorization", bobBearer))
                .andExpect(status().isNotFound());
        mvc.perform(get("/jarvis/notices").header("Authorization", bobBearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/jarvis/notices/" + id + "/spoken").header("Authorization",
                "Bearer " + jwt.generateToken(alice))).andExpect(jsonPath("$.spoken").value(true));
        mvc.perform(get("/jarvis/notices").header("Authorization", "Bearer " + jwt.generateToken(alice)))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
