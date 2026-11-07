package fr.claudegateway.jarvis.clientmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.mail.JarvisMailItem;
import fr.claudegateway.jarvis.mail.JarvisMailItemRepository;
import fr.claudegateway.jarvis.mail.JarvisMailToolCatalog;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostLifecycleEvent;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.shared.jobs.AsyncJobQueue;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-249 / SF-249-01 — le dépôt reçu : route du runner (jeton, bornes, formats), lecture {@code .eml}/{@code .msg},
 * dédoublonnage (empreinte, Message-ID), mail ancien, travail {@code MAIL_DEPOSIT} ⇒ tri F-242 (fournisseur bouchonné),
 * lecture sans IMAP (écran, Opus), action refusée, isolation A/B, purges du poste et du compte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientMailDepositIntegrationTest {

    @MockitoBean private RunnerTokenAuthenticator authenticator;
    @MockitoBean private AiAgentProvider agentProvider;

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private JarvisMailItemRepository items;
    @Autowired private ClientMailDepositRepository deposits;
    @Autowired private ClientMailDepositJobHandler handler;
    @Autowired private ClientMailDepositService service;
    @Autowired private AsyncJobQueue queue;
    @Autowired private JarvisMailToolCatalog catalog;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEventPublisher events;

    private User alice;
    private User bob;
    private RunnerHost edenred;
    private RunnerHost bobHost;

    @BeforeEach
    void setUp() {
        alice = user("dep-a");
        bob = user("dep-b");
        edenred = hosts.save(RunnerHost.builder().userId(alice.getId()).name("EDENRED").rootName("dev").os("linux")
                .shell("posix").elevated(false).build());
        bobHost = hosts.save(RunnerHost.builder().userId(bob.getId()).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).build());
        when(authenticator.authenticate(eq("tok-alice")))
                .thenReturn(Optional.of(new RunnerIdentity(UUID.randomUUID(), alice.getId(), edenred.getId())));
        when(authenticator.authenticate(eq("tok-bob")))
                .thenReturn(Optional.of(new RunnerIdentity(UUID.randomUUID(), bob.getId(), bobHost.getId())));
        when(agentProvider.nextTurn(any(AgentTurnRequest.class))).thenReturn(new AgentTurn(
                "[{\"id\":\"m1\",\"classe\":\"A_TRAITER\",\"raison\":\"Rémi confirme le correctif\"}]", List.of(),
                true, 500, 20));
    }

    private User user(String prefix) {
        return users.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    static byte[] eml(String messageId, String subject, OffsetDateTime date) {
        String d = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH).format(date);
        return ("From: \"Rémi Durand\" <remi@edenred.com>\r\nTo: franck@edenred.com\r\nSubject: " + subject
                + "\r\nMessage-ID: " + messageId + "\r\nDate: " + d
                + "\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
                + "Le correctif de l'alarme Fortec est en recette. CORPS-SECRET-42\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.ResultActions deposit(String token, String name, byte[] body)
            throws Exception {
        var req = post("/runner/mail-deposit").contentType(MediaType.APPLICATION_OCTET_STREAM).content(body)
                .header("X-File-Name", name);
        if (token != null) {
            req = req.header("X-Runner-Token", token);
        }
        return mvc.perform(req);
    }

    private void runJobs() {
        for (AsyncJobQueue.Job job : queue.claim(ClientMailDepositService.JOB_KIND, 50, "test")) {
            handler.handle(job);
            queue.complete(job.id(), "test", 1, java.math.BigDecimal.ZERO);
        }
    }

    private List<JarvisMailItem> depositedOf(User u) {
        return items.findByUserIdOrderByReceivedAtDesc(u.getId(), org.springframework.data.domain.PageRequest.of(0, 50))
                .stream().filter(JarvisMailItem::isDeposited).toList();
    }

    @Test
    @DisplayName(".eml : courrier DEPOT du client, scellé, puis trié par le cerveau rapide (travail MAIL_DEPOSIT)")
    void emlAcceptedSealedAndTriaged() throws Exception {
        deposit("tok-alice", "RE Alarme.eml", eml("<m1@edenred.com>", "RE: Alarme Fortec", OffsetDateTime.now()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ACCEPTED"));
        List<JarvisMailItem> mine = depositedOf(alice);
        assertThat(mine).hasSize(1);
        JarvisMailItem item = mine.get(0);
        assertThat(item.getHostId()).isEqualTo(edenred.getId());
        assertThat(item.getAccountId()).isNull();
        assertThat(item.getTriage()).isEqualTo(JarvisMailItem.Triage.EN_ATTENTE);
        // Rien en clair au repos : ni l'objet, ni le corps.
        String raw = jdbc.queryForObject("select concat(sealed, coalesce(body_sealed, '')) from jarvis_mail_items "
                + "where id = ?", String.class, item.getId());
        assertThat(raw).doesNotContain("Alarme", "CORPS-SECRET-42");

        runJobs();
        JarvisMailItem triaged = items.findById(item.getId()).orElseThrow();
        assertThat(triaged.getTriage()).isEqualTo(JarvisMailItem.Triage.A_TRAITER);
        assertThat(triaged.getTriageReason()).isEqualTo("Rémi confirme le correctif");
    }

    @Test
    @DisplayName(".msg : lu par POI, même pipeline")
    void msgAccepted() throws Exception {
        deposit("tok-alice", "Fortec.msg", OutlookMsgReaderTest.simple("<msg-" + UUID.randomUUID() + "@x>",
                "RE: Alarme Fortec")).andExpect(jsonPath("$.outcome").value("ACCEPTED"));
        assertThat(depositedOf(alice)).hasSize(1);
    }

    @Test
    @DisplayName("Doublons : même fichier ⇒ DUPLICATE ; autre fichier, même Message-ID ⇒ DUPLICATE ; rien de retraité")
    void duplicates() throws Exception {
        byte[] file = eml("<dup@edenred.com>", "Sujet", OffsetDateTime.now());
        deposit("tok-alice", "a.eml", file).andExpect(jsonPath("$.outcome").value("ACCEPTED"));
        deposit("tok-alice", "a.eml", file).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        deposit("tok-alice", "b.eml", eml("<dup@edenred.com>", "Sujet (copie)", OffsetDateTime.now().minusMinutes(1)))
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        assertThat(depositedOf(alice)).hasSize(1);
        assertThat(queue.claim(ClientMailDepositService.JOB_KIND, 50, "dup")).hasSize(1);
    }

    @Test
    @DisplayName("Mail de plus de 30 jours ⇒ IGNORED, rien en cache")
    void tooOld() throws Exception {
        deposit("tok-alice", "old.eml", eml("<old@x>", "Vieux", OffsetDateTime.now(ZoneOffset.UTC).minusDays(40)))
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
        assertThat(depositedOf(alice)).isEmpty();
    }

    @Test
    @DisplayName("Refus : 401 sans jeton, 400 vide, 415 format, 422 illisible")
    void refusals() throws Exception {
        deposit(null, "a.eml", eml("<x@x>", "x", OffsetDateTime.now())).andExpect(status().isUnauthorized());
        deposit("faux", "a.eml", eml("<x@x>", "x", OffsetDateTime.now())).andExpect(status().isUnauthorized());
        deposit("tok-alice", "a.eml", new byte[0]).andExpect(status().isBadRequest());
        deposit("tok-alice", "a.pdf", new byte[] {1, 2}).andExpect(status().isUnsupportedMediaType());
        deposit("tok-alice", "a.msg", "pas outlook".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnprocessableEntity());
        assertThat(depositedOf(alice)).isEmpty();
    }

    @Test
    @DisplayName("Lecture sans IMAP : écran et courriel_lire rendent le corps scellé ; répondre est refusé ; B : 404")
    void readWithoutImapAndIsolation() throws Exception {
        deposit("tok-alice", "a.eml", eml("<read@x>", "RE: Alarme Fortec", OffsetDateTime.now()))
                .andExpect(status().isOk());
        JarvisMailItem item = depositedOf(alice).get(0);
        mvc.perform(get("/jarvis/mail/items/" + item.getId()).header("Authorization",
                "Bearer " + jwt.generateToken(alice))).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("CORPS-SECRET-42")))
                .andExpect(jsonPath("$.item.account").value("Outlook EDENRED"));
        mvc.perform(get("/jarvis/mail/items/" + item.getId()).header("Authorization",
                "Bearer " + jwt.generateToken(bob))).andExpect(status().isNotFound());

        Workspace thread = Workspace.builder().id(UUID.randomUUID()).userId(alice.getId()).name("Jarvis")
                .jarvisThreadUserId(alice.getId()).build();
        assertThat(catalog.toolsFor(alice.getId(), thread)).isNotEmpty();
        com.fasterxml.jackson.databind.node.ObjectNode in = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("courriel", "courriel:" + item.getId());
        JarvisMailToolCatalog.Result read = catalog.execute(alice.getId(), thread, JarvisMailToolCatalog.READ, in);
        assertThat(read.error()).isFalse();
        assertThat(read.content()).contains("CORPS-SECRET-42", "Outlook EDENRED", "courriel_non_fiable");
        JarvisMailToolCatalog.Result reply = catalog.execute(alice.getId(), thread, JarvisMailToolCatalog.REPLY,
                in.put("corps", "Merci"));
        assertThat(reply.error()).isTrue();
        assertThat(reply.content()).contains("depuis Outlook");
        Workspace bobThread = Workspace.builder().id(UUID.randomUUID()).userId(bob.getId()).name("Jarvis")
                .jarvisThreadUserId(bob.getId()).build();
        assertThat(catalog.execute(bob.getId(), bobThread, JarvisMailToolCatalog.READ,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                        .put("courriel", "courriel:" + item.getId())).error()).isTrue();
    }

    @Test
    @DisplayName("Isolation : le même fichier chez B est un dépôt de B, sans croisement du dédoublonnage")
    void isolationOfDeduplication() throws Exception {
        byte[] file = eml("<shared@x>", "Commun", OffsetDateTime.now());
        deposit("tok-alice", "a.eml", file).andExpect(jsonPath("$.outcome").value("ACCEPTED"));
        deposit("tok-bob", "a.eml", file).andExpect(jsonPath("$.outcome").value("ACCEPTED"));
        assertThat(depositedOf(alice)).hasSize(1);
        assertThat(depositedOf(bob)).hasSize(1);
        assertThat(depositedOf(bob).get(0).getHostId()).isEqualTo(bobHost.getId());
    }

    @Test
    @DisplayName("Purges : poste supprimé ⇒ ses courriers et dépôts ; compte supprimé ⇒ tout")
    void purges() throws Exception {
        deposit("tok-alice", "a.eml", eml("<p1@x>", "Un", OffsetDateTime.now())).andExpect(status().isOk());
        deposit("tok-bob", "a.eml", eml("<p2@x>", "Deux", OffsetDateTime.now())).andExpect(status().isOk());
        events.publishEvent(RunnerHostLifecycleEvent.deleted(alice.getId(), edenred.getId()));
        assertThat(depositedOf(alice)).isEmpty();
        assertThat(deposits.existsByUserIdAndHostIdAndDepositedAtGreaterThanEqual(alice.getId(), edenred.getId(),
                OffsetDateTime.now().minusDays(1))).isFalse();
        service.purgeUser(bob.getId());
        assertThat(depositedOf(bob)).isEmpty();
    }
}
