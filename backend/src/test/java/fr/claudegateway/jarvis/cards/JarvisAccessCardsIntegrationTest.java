package fr.claudegateway.jarvis.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.actions.AccesObtenu;
import fr.claudegateway.atelier.actions.AccesObtenuRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.calendar.JarvisCalendarFeed;
import fr.claudegateway.jarvis.calendar.JarvisCalendarFeedRepository;
import fr.claudegateway.jarvis.calendar.JarvisCalendarGrant;
import fr.claudegateway.jarvis.calendar.JarvisCalendarGrantRepository;
import fr.claudegateway.jarvis.mail.JarvisMailAccount;
import fr.claudegateway.jarvis.mail.JarvisMailAccountRepository;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateTool;
import fr.claudegateway.runner.RunnerToken;
import fr.claudegateway.runner.RunnerTokenRepository;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-248 / SF-248-09 — les accès : compte, type, validité, état ; <b>corpus « aucun secret » (100 %)</b> sur tout ce qui
 * quitte le serveur (événement, résultat d'outil, plein écran) ; isolation à deux utilisateurs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisAccessCardsIntegrationTest {

    /** Les secrets semés dans chaque source : aucun ne doit sortir, même partiellement. */
    static final List<String> SECRETS = List.of("CIPHERMAILXYZ", "DATAKEYMAILXYZ", "IVMAILXYZ", "Q9Z7", "CIPHERCALXYZ",
            "DATAKEYCALXYZ", "IVCALXYZ", "CIPHERICSXYZ", "K8W2", "urlhash0123456789abcdef", "tokhash0123456789abcdef",
            "glpat-abcdefghijklmnop1234", "ghp_abcdefghijklmnopqrstuvwxyz0123", "AKIAABCDEFGHIJKLMNOP",
            "hunter2hunter2");

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private JarvisMailAccountRepository mails;
    @Autowired private JarvisCalendarGrantRepository grants;
    @Autowired private JarvisCalendarFeedRepository feeds;
    @Autowired private RunnerTokenRepository tokens;
    @Autowired private AccesObtenuRepository obtained;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private JarvisStateTool tool;
    @Autowired private JarvisAccessCards accesses;
    @Autowired private JwtService jwt;

    private User alice;
    private User bob;
    private UUID host;
    /** L'empreinte du jeton (unique en base) : un secret de plus, propre au test. */
    private final String tokenHash = "tokhash" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void setUp() {
        alice = user();
        bob = user();
        OffsetDateTime now = OffsetDateTime.now();
        host = hosts.save(RunnerHost.builder().userId(alice.getId()).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).build()).getId();
        JarvisMailAccount mail = new JarvisMailAccount();
        mail.setUserId(alice.getId());
        mail.setAddress("alice@ex.com");
        mail.setLabel("Pro");
        mail.setEncryptedDataKey("DATAKEYMAILXYZ");
        mail.setCipherIv("IVMAILXYZ");
        mail.setCiphertext("CIPHERMAILXYZ");
        mail.setSecretLast4("Q9Z7");
        mail.setStatus(JarvisMailAccount.Status.AUTH_FAILED);
        mail.setCreatedAt(now);
        mails.save(mail);
        JarvisCalendarGrant grant = new JarvisCalendarGrant();
        grant.setUserId(alice.getId());
        grant.setAccount("alice@ex.com");
        grant.setEncryptedDataKey("DATAKEYCALXYZ");
        grant.setCipherIv("IVCALXYZ");
        grant.setCiphertext("CIPHERCALXYZ");
        grant.setScope("https://www.googleapis.com/auth/calendar.readonly");
        grant.setGrantedAt(now);
        grants.save(grant);
        JarvisCalendarFeed feed = new JarvisCalendarFeed();
        feed.setUserId(alice.getId());
        feed.setLabel("Équipe");
        feed.setUrlHash("urlhash0123456789abcdef");
        feed.setEncryptedDataKey("k");
        feed.setCipherIv("i");
        feed.setCiphertext("CIPHERICSXYZ");
        feed.setSecretLast4("K8W2");
        feed.setCreatedAt(now);
        feeds.save(feed);
        tokens.save(RunnerToken.builder().userId(alice.getId()).hostId(host).tokenHash(tokenHash)
                .expiresAt(now.plusDays(3)).createdAt(now).build());
        obtained.save(AccesObtenu.builder().userId(alice.getId()).hostId(host).workspaceId(UUID.randomUUID())
                .system("VPN").normalizedSystem("vpn").account("remi").obtainedAt(LocalDate.now().minusDays(30))
                .expiresAt(LocalDate.now().minusDays(1)).note("mot de passe : hunter2hunter2").createdAt(now)
                .updatedAt(now).build());
        // Défense en profondeur : des faits ACCESS qui porteraient un secret (l'extraction les écarte déjà).
        registry.record(alice.getId(), List.of(
                obs("GITLAB:gitlab.ex", "account", "rdupont"), obs("GITLAB:gitlab.ex", "identity_type", "jeton GitLab"),
                obs("GITLAB:gitlab.ex", "validity", "valide"), obs("GITLAB:gitlab.ex", "value",
                        "glpat-abcdefghijklmnop1234"), obs("GITLAB:gitlab.ex", "status", "connecté")));
    }

    @AfterEach
    void close() {
        JarvisCardCatalog.close();
    }

    private JarvisStateRegistry.Observation obs(String key, String attr, String value) {
        return new JarvisStateRegistry.Observation(host, null, "ACCESS", key, attr, value,
                OffsetDateTime.now().minusMinutes(30), "TOUR", null, "HAUTE");
    }

    private User user() {
        return users.save(User.builder().email("cards-a-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    @Test
    @DisplayName("« mes accès » : boîte refusée, droit OAuth (portée lisible), lien ICS, jeton qui expire, accès expiré, identité du poste")
    void accessesRead() {
        JarvisCardCatalog catalog = JarvisCardCatalog.open();
        String text = tool.read(alice.getId(), null, new ObjectMapper().createObjectNode().put("famille", "access"),
                false).text();
        List<JarvisCard> cards = catalog.cards();
        assertThat(cards).allMatch(JarvisCard::targeted).allMatch(c -> c.type().equals("ACCES"));
        assertThat(cards).extracting(c -> c.status().word()).contains("refusé", "valide", "expire dans 3 j", "expiré",
                "connecté");
        assertThat(cards).filteredOn(c -> c.status().word().equals("refusé")).first()
                .satisfies(c -> assertThat(c.status().tone()).isEqualTo("alerte"));
        assertThat(cards).filteredOn(c -> c.status().word().startsWith("expire dans")).first()
                .satisfies(c -> assertThat(c.status().tone()).isEqualTo("attend"));
        assertThat(cards.toString()).contains("calendar.readonly").doesNotContain("googleapis");
        assertThat(text).contains("Accès Boîte alice@ex.com").contains("jeton du runner");
        // Ce que les pancartes ajoutent au résultat lu (lignes d'accès) et les fiches : aucun secret, aucune empreinte.
        String linked = String.join("\n", accesses.accesses(alice.getId(), List.of()).stream()
                .map(JarvisAccessCards.Access::line).toList());
        List<String> everything = new ArrayList<>(List.of(linked, cards.toString()));
        assertThat(everything).noneMatch(x -> x.contains(tokenHash));
        for (String secret : SECRETS) {
            assertThat(everything).as(secret).noneMatch(s -> s.contains(secret));
        }
        for (JarvisCard c : cards) {
            assertThat(c.fields()).extracting(JarvisCard.Field::label).isSubsetOf("Compte", "Type", "Validité", "État",
                    "Portée");
        }
    }

    @Test
    @DisplayName("plein écran d'un accès et d'une identité : liste blanche, aucun secret ; Bob ne voit rien d'Alice")
    void detailAndIsolation() throws Exception {
        String aliceToken = jwt.generateToken(alice);
        String bobToken = jwt.generateToken(bob);
        List<JarvisCard> cards = accesses.accesses(alice.getId(), List.of()).stream()
                .map(JarvisAccessCards.Access::card).toList();
        assertThat(cards).hasSize(5);
        List<String> ids = new ArrayList<>(cards.stream().map(JarvisCard::id).toList());
        ids.add(JarvisStateCards.id(host, "GITLAB:gitlab.ex"));
        for (String id : ids) {
            String body = mvc.perform(get("/api/jarvis/cards/" + id).contextPath("/api")
                    .header("Authorization", "Bearer " + aliceToken)).andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString();
            for (String secret : SECRETS) {
                assertThat(body).as(id + " / " + secret).doesNotContain(secret);
            }
            assertThat(body).doesNotContain(tokenHash);
            mvc.perform(get("/api/jarvis/cards/" + id).contextPath("/api").header("Authorization", "Bearer "
                    + bobToken)).andExpect(status().isNotFound());
        }
        assertThat(accesses.accesses(bob.getId(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("validité : « valide », « expire dans N j » (≤ 7 j), « expiré » ; portée OAuth sans URL")
    void validity() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-05T10:00:00Z");
        assertThat(JarvisAccessCards.validity(null, now)).isEqualTo("valide");
        assertThat(JarvisAccessCards.validity(LocalDate.parse("2026-11-08"), now)).isEqualTo("expire dans 3 j");
        assertThat(JarvisAccessCards.validity(LocalDate.parse("2026-12-08"), now)).isEqualTo("valide");
        assertThat(JarvisAccessCards.validity(LocalDate.parse("2026-11-04"), now)).isEqualTo("expiré");
        assertThat(JarvisAccessCards.scope("https://www.googleapis.com/auth/calendar.readonly openid"))
                .isEqualTo("calendar.readonly, openid");
        assertThat(JarvisCardTemplates.fields("ACCES", Map.of("value", "x", "token", "y"))).isEmpty();
    }
}
