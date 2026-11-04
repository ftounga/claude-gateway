package fr.claudegateway.jarvis.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-242 / SF-242-05 — les outils de lecture d'Opus contre un vrai serveur IMAP (GreenMail) : offerts dans le fil Jarvis
 * seulement (et avec une boîte), définitions figées, lecture et recherche balisées non fiables, isolation A/B (même avec
 * une référence forgée), refus hors fil Jarvis, note jointe au seul fil Jarvis.
 */
@SpringBootTest
@ActiveProfiles("test")
class JarvisMailToolsIntegrationTest {

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        MailTestServer.register(registry);
    }

    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JarvisMailAccountService accountService;
    @Autowired private JarvisMailPoller poller;
    @Autowired private JarvisMailToolCatalog catalog;
    @Autowired private JarvisMailTriageService realTriage;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void withoutTriage() {
        poller.setTriage(null);
    }

    @AfterEach
    void restoreTriage() {
        poller.setTriage(realTriage);
    }

    private record Box(UUID userId, String address, UUID accountId, Workspace thread, Workspace subject) {
    }

    private Box box(String prefix) {
        User user = userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        String password = "pw" + UUID.randomUUID().toString().substring(0, 12);
        String address = MailTestServer.newUser(prefix, password);
        UUID id = accountService.link(user.getId(), address, "Pro", password).id();
        Workspace thread = Workspace.builder().id(UUID.randomUUID()).userId(user.getId()).name("Jarvis")
                .jarvisThreadUserId(user.getId()).build();
        Workspace subject = Workspace.builder().id(UUID.randomUUID()).userId(user.getId()).name("Helios").build();
        return new Box(user.getId(), address, id, thread, subject);
    }

    private String cachedRef(UUID userId) {
        return "courriel:" + jdbc.queryForObject("select id from jarvis_mail_items where user_id = ?", String.class,
                userId);
    }

    @Test
    void toolsOnlyInTheJarvisThreadWithABoxAndFrozen() {
        Box b = box("tools");
        assertThat(catalog.toolsFor(b.userId(), b.thread())).extracting("name")
                .containsExactly(JarvisMailToolCatalog.READ, JarvisMailToolCatalog.SEARCH, JarvisMailToolCatalog.DRAFT,
                        JarvisMailToolCatalog.REPLY, JarvisMailToolCatalog.SEND, JarvisMailToolCatalog.ARCHIVE,
                        JarvisMailToolCatalog.DELETE);
        assertThat(catalog.toolsFor(b.userId(), b.subject())).isEmpty();
        assertThat(catalog.toolsFor(UUID.randomUUID(), b.thread())).isEmpty();
        assertThat(catalog.toolsFor(b.userId(), b.thread())).isEqualTo(catalog.toolsFor(b.userId(), b.thread()));
        assertThat(JarvisMailToolCatalog.isMailTool("courriel_m_envoyer")).isFalse();
        // La note : fil Jarvis seulement, consigne inchangée ailleurs.
        assertThat(catalog.withNote(b.userId(), b.thread(), "consigne")).startsWith("consigne\n\n[Courrier");
        assertThat(catalog.withNote(b.userId(), b.subject(), "consigne")).isEqualTo("consigne");
    }

    @Test
    void readAndSearchAreTaggedUntrustedAndBounded() throws Exception {
        Box b = box("read");
        MailTestServer.deliver(b.address(), MailTestServer.raw("Helios <devis@helios.example>", b.address(),
                "Le devis de septembre", "Bonjour, voici le devis. " + "y".repeat(10_000)));
        poller.poll(b.accountId());

        JarvisMailToolCatalog.Result read = catalog.execute(b.userId(), b.thread(), JarvisMailToolCatalog.READ,
                json.readTree("{\"courriel\":\"" + cachedRef(b.userId()) + "\"}"));
        assertThat(read.error()).isFalse();
        assertThat(read.content()).contains(UntrustedMail.NOTE).contains(UntrustedMail.OPEN)
                .contains("Le devis de septembre").contains("voici le devis").contains("[… la suite est coupée]");
        assertThat(read.content().length()).isLessThan(9_500);

        JarvisMailToolCatalog.Result search = catalog.execute(b.userId(), b.thread(), JarvisMailToolCatalog.SEARCH,
                json.readTree("{\"boite\":\"\",\"de\":\"\",\"objet\":\"septembre\",\"texte\":\"\",\"depuis\":\"\",\"jusqu_a\":\"\"}"));
        assertThat(search.error()).isFalse();
        assertThat(search.content()).contains("1 courriel(s)").contains(UntrustedMail.OPEN).contains("imap:");
        String ref = search.content().replaceAll("(?s).*?(imap:[^ ]+).*", "$1");
        JarvisMailToolCatalog.Result again = catalog.execute(b.userId(), b.thread(), JarvisMailToolCatalog.READ,
                json.readTree("{\"courriel\":\"" + ref + "\"}"));
        assertThat(again.error()).isFalse();
        assertThat(again.content()).contains("Le devis de septembre");

        JarvisMailToolCatalog.Result empty = catalog.execute(b.userId(), b.thread(), JarvisMailToolCatalog.SEARCH,
                json.readTree("{\"boite\":\"\",\"de\":\"\",\"objet\":\"\",\"texte\":\"\",\"depuis\":\"\",\"jusqu_a\":\"\"}"));
        assertThat(empty.error()).isTrue();
        JarvisMailToolCatalog.Result badDate = catalog.execute(b.userId(), b.thread(), JarvisMailToolCatalog.SEARCH,
                json.readTree("{\"boite\":\"\",\"de\":\"x\",\"objet\":\"\",\"texte\":\"\",\"depuis\":\"hier\",\"jusqu_a\":\"\"}"));
        assertThat(badDate.error()).isTrue();
    }

    @Test
    void anotherAccountCannotReadEvenWithAForgedReferenceAndNothingOutsideTheThread() throws Exception {
        Box a = box("iso-a");
        Box b = box("iso-b");
        MailTestServer.deliver(a.address(), MailTestServer.raw("x@x.example", a.address(), "Secret de A", "A seul"));
        poller.poll(a.accountId());
        String cached = cachedRef(a.userId());
        String forged = MailRef.imap(a.accountId(), false, 1, 1);

        for (String ref : new String[] {cached, forged}) {
            JarvisMailToolCatalog.Result r = catalog.execute(b.userId(), b.thread(), JarvisMailToolCatalog.READ,
                    json.readTree("{\"courriel\":\"" + ref + "\"}"));
            assertThat(r.error()).isTrue();
            assertThat(r.content()).contains("introuvable").doesNotContain("Secret de A");
        }
        JarvisMailToolCatalog.Result outside = catalog.execute(a.userId(), a.subject(), JarvisMailToolCatalog.READ,
                json.readTree("{\"courriel\":\"" + cached + "\"}"));
        assertThat(outside.error()).isTrue();
        assertThat(outside.content()).doesNotContain("Secret de A");
    }

    @Test
    void searchCriteriaAreCombinedWithAnd() {
        jakarta.mail.search.SearchTerm one = JarvisMailBox.term(new JarvisMailBox.Criteria("helios", "", "", null, null));
        assertThat(one).isInstanceOf(jakarta.mail.search.FromStringTerm.class);
        jakarta.mail.search.SearchTerm all = JarvisMailBox.term(new JarvisMailBox.Criteria("helios", "devis", "signé",
                java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30)));
        assertThat(all).isInstanceOf(jakarta.mail.search.AndTerm.class);
        assertThat(((jakarta.mail.search.AndTerm) all).getTerms()).hasSize(5);
    }

    @Test
    void referencesAreStrict() {
        UUID id = UUID.randomUUID();
        assertThat(MailRef.cachedId("courriel:" + id)).isEqualTo(id);
        assertThat(MailRef.cachedId("courriel:../x")).isNull();
        assertThat(MailRef.parseImap(MailRef.imap(id, true, 7, 42))).isEqualTo(new MailRef(null, id, true, 7, 42));
        assertThat(MailRef.parseImap("imap:" + id + ":Z:7:42")).isNull();
        assertThat(MailRef.parseImap("imap:pas-un-uuid:A:1:1")).isNull();
    }
}
