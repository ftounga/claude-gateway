package fr.claudegateway.jarvis.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-242 / SF-242-02 — la relève contre un vrai serveur IMAP (GreenMail) : rien de neuf ⇒ relève vide, trois mails ⇒
 * trois lignes sans doublon, {@code UIDVALIDITY} changée ⇒ pas de doublon, bail concurrent, refus Google ⇒
 * {@code AUTH_FAILED} et une seule notification, corps jamais stocké, isolation, purge et révocation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisMailPollIntegrationTest {

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        MailTestServer.register(registry);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JarvisMailAccountService accountService;
    @Autowired private JarvisMailAccountRepository accounts;
    @Autowired private JarvisMailPoller poller;
    @Autowired private JarvisMailItemRepository items;
    @Autowired private PushNotificationService realPush;
    @Autowired private JarvisMailTriageService realTriage;

    @org.junit.jupiter.api.BeforeEach
    void withoutTriage() {
        // La relève seule : le tri (SF-242-03, asynchrone) est testé à part.
        poller.setTriage(null);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreTriage() {
        poller.setTriage(realTriage);
    }

    private record Box(User user, String address, String password, UUID accountId) {
        String bearer(JwtService jwt) {
            return "Bearer " + jwt.generateToken(user);
        }
    }

    private Box box(String prefix) {
        User user = userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        String password = "pw" + UUID.randomUUID().toString().substring(0, 12);
        String address = MailTestServer.newUser(prefix, password);
        UUID id = accountService.link(user.getId(), address, "Perso", password).id();
        return new Box(user, address, password, id);
    }

    private long rows(UUID userId) {
        return jdbc.queryForObject("select count(*) from jarvis_mail_items where user_id = ?", Long.class, userId);
    }

    @Test
    void emptyBoxThenThreeMailsThenNoDuplicate() throws Exception {
        Box b = box("poll");
        // Premier passage sur une boîte vide : resynchronisation (7 jours), rien.
        JarvisMailPoller.Outcome first = poller.poll(b.accountId());
        assertThat(first.polled()).isTrue();
        assertThat(first.fetched()).isZero();
        // Rien n'est arrivé : relève VIDE (STATUS seul, aucun FETCH — prouvé par InboxReaderTest).
        JarvisMailPoller.Outcome empty = poller.poll(b.accountId());
        assertThat(empty.empty()).isTrue();
        assertThat(empty.fetched()).isZero();

        String longBody = "Début du corps. " + "x".repeat(9000) + " FIN-DU-CORPS-SECRETE";
        MailTestServer.deliver(b.address(), MailTestServer.raw("Helios <devis@helios.example>", b.address(),
                "Le devis de septembre", longBody));
        MailTestServer.deliver(b.address(), MailTestServer.raw("banque@banque.example", b.address(),
                "Signature attendue", "Merci de signer."));
        MailTestServer.deliver(b.address(), MailTestServer.raw("news@shop.example", b.address(),
                "Promo", "Soldes !", "List-Unsubscribe: <mailto:unsub@shop.example>"));

        JarvisMailPoller.Outcome three = poller.poll(b.accountId());
        assertThat(three.empty()).isFalse();
        assertThat(three.fetched()).isEqualTo(3);
        assertThat(rows(b.user().getId())).isEqualTo(3);
        // La relève suivante ne crée aucun doublon et ne voit rien de neuf.
        JarvisMailPoller.Outcome again = poller.poll(b.accountId());
        assertThat(again.empty()).isTrue();
        assertThat(rows(b.user().getId())).isEqualTo(3);

        // Le corps complet n'est jamais stocké : l'extrait est borné, et aucune colonne ne porte le clair.
        List<Map<String, Object>> raw = jdbc.queryForList("select * from jarvis_mail_items where user_id = ?",
                b.user().getId());
        assertThat(raw).allSatisfy(r -> assertThat(r.values().stream().map(String::valueOf))
                .noneMatch(v -> v.contains("FIN-DU-CORPS-SECRETE") || v.contains("Le devis de septembre")));
        assertThat(raw.stream().filter(r -> Boolean.TRUE.equals(r.get("bulk")))).hasSize(1);
        assertThat(raw).allSatisfy(r -> assertThat(r.get("triage")).isEqualTo("EN_ATTENTE"));

        // Lecture du cache par l'API : en-têtes descellés, extrait borné à 2 000.
        mockMvc.perform(get("/api/jarvis/mail/items").contextPath("/api").header("Authorization", b.bearer(jwtService)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.subject == 'Le devis de septembre')].fromName").value("Helios"))
                .andExpect(jsonPath("$[?(@.subject == 'Le devis de septembre')].from").value("devis@helios.example"));
        String body = mockMvc.perform(get("/api/jarvis/mail/items").contextPath("/api")
                        .header("Authorization", b.bearer(jwtService)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("FIN-DU-CORPS-SECRETE");

        Map<String, Object> day = jdbc.queryForMap("select mail_polls, mail_polls_empty, mail_fetched "
                + "from jarvis_usage_days where user_id = ?", b.user().getId());
        assertThat(((Number) day.get("mail_polls")).intValue()).isEqualTo(4);
        assertThat(((Number) day.get("mail_polls_empty")).intValue()).isEqualTo(2);
        assertThat(((Number) day.get("mail_fetched")).intValue()).isEqualTo(3);
    }

    @Test
    void changedUidValidityResyncsWithoutDuplicates() {
        Box b = box("resync");
        MailTestServer.deliver(b.address(), MailTestServer.raw("a@a.example", b.address(), "Un", "1"));
        MailTestServer.deliver(b.address(), MailTestServer.raw("a@a.example", b.address(), "Deux", "2"));
        assertThat(poller.poll(b.accountId()).fetched()).isEqualTo(2);
        // Le serveur annonce une autre UIDVALIDITY (simulée en changeant celle que la passerelle connaît).
        jdbc.update("update jarvis_mail_accounts set inbox_uidvalidity = inbox_uidvalidity + 1000 where id = ?",
                b.accountId());
        JarvisMailPoller.Outcome resync = poller.poll(b.accountId());
        assertThat(resync.empty()).isFalse();
        assertThat(resync.fetched()).isZero();
        assertThat(rows(b.user().getId())).isEqualTo(2);
    }

    @Test
    void twoPodsNeverPollTheSameBoxAtOnce() {
        Box b = box("lease");
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(accounts.claim(b.accountId(), now, now.plusMinutes(2))).isEqualTo(1);
        // Un second pod : le bail est tenu.
        assertThat(accounts.claim(b.accountId(), now, now.plusMinutes(2))).isZero();
        assertThat(poller.poll(b.accountId()).polled()).isFalse();
        // Bail échu : de nouveau réservable.
        jdbc.update("update jarvis_mail_accounts set lease_until = ? where id = ?", now.minusSeconds(1), b.accountId());
        assertThat(poller.poll(b.accountId()).polled()).isTrue();
    }

    @Test
    void revokedPasswordGivesAuthFailedAndASingleNotification() {
        Box b = box("auth");
        PushNotificationService push = mock(PushNotificationService.class);
        poller.setPush(push);
        try {
            MailTestServer.get().setUser(b.address(), b.address(), "revoque-chez-google");
            JarvisMailPoller.Outcome failed = poller.poll(b.accountId());
            assertThat(failed.authFailed()).isTrue();
            JarvisMailAccount account = accounts.findById(b.accountId()).orElseThrow();
            assertThat(account.getStatus()).isEqualTo(JarvisMailAccount.Status.AUTH_FAILED);
            assertThat(account.getAuthFailures()).isEqualTo(1);
            assertThat(account.getNextPollAt()).isAfter(OffsetDateTime.now().plusMinutes(4));
            // Un second refus : retard doublé, AUCUNE nouvelle notification.
            poller.poll(b.accountId());
            assertThat(accounts.findById(b.accountId()).orElseThrow().getAuthFailures()).isEqualTo(2);
            verify(push, times(1)).notify(eq(b.user().getId()), any(), eq(PushEvent.MAIL_AUTH_FAILED), eq("/jarvis"));
        } finally {
            poller.setPush(realPush);
        }
    }

    @Test
    void isolationRevocationAndPurge() throws Exception {
        Box a = box("iso-a");
        Box b = box("iso-b");
        MailTestServer.deliver(a.address(), MailTestServer.raw("x@x.example", a.address(), "Pour A", "a"));
        poller.poll(a.accountId());
        poller.poll(b.accountId());
        // Les lignes sont écrites sous le user_id de la boîte, et B ne voit rien.
        assertThat(rows(a.user().getId())).isEqualTo(1);
        assertThat(rows(b.user().getId())).isZero();
        mockMvc.perform(get("/api/jarvis/mail/items").contextPath("/api").header("Authorization", b.bearer(jwtService)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/jarvis/mail/items?triage=NIMPORTE").contextPath("/api")
                .header("Authorization", a.bearer(jwtService))).andExpect(status().isBadRequest());

        // Purge à 30 jours.
        jdbc.update("update jarvis_mail_items set received_at = ? where user_id = ?",
                OffsetDateTime.now().minusDays(31), a.user().getId());
        assertThat(items.purgeBefore(OffsetDateTime.now().minusDays(30))).isGreaterThanOrEqualTo(1);
        assertThat(rows(a.user().getId())).isZero();

        // Révoquer efface les courriers de la boîte.
        MailTestServer.deliver(a.address(), MailTestServer.raw("x@x.example", a.address(), "Encore", "a"));
        poller.poll(a.accountId());
        assertThat(rows(a.user().getId())).isEqualTo(1);
        mockMvc.perform(delete("/api/jarvis/mail/accounts/" + a.accountId()).contextPath("/api")
                .header("Authorization", a.bearer(jwtService))).andExpect(status().isNoContent());
        assertThat(rows(a.user().getId())).isZero();
    }
}
