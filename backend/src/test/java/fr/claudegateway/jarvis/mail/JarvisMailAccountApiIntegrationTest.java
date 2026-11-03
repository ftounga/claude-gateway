package fr.claudegateway.jarvis.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.account.AccountService;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.byok.ByokKeyCipher;
import fr.claudegateway.byok.EncryptedKey;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-242 / SF-242-01 — {@code /api/jarvis/mail/accounts} contre un vrai serveur IMAP + SMTP (GreenMail) : relier,
 * refus sans écriture, chiffrement en base, remplacement du secret, borne de deux boîtes, test, révocation, isolation
 * A/B, purge du compte, 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisMailAccountApiIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "abcd efgh ijkl mnop";

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        MailTestServer.register(registry);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accountService;
    @Autowired private ByokKeyCipher cipher;

    private record Account(UUID id, String bearer) {
    }

    private Account account(String prefix) {
        User user = userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        return new Account(user.getId(), "Bearer " + jwtService.generateToken(user));
    }

    private ResultActions link(Account a, String address, String password) throws Exception {
        return mockMvc.perform(post("/api/jarvis/mail/accounts").contextPath("/api")
                .header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("address", address, "label", "Perso", "password", password))));
    }

    private static JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void goodPasswordLinksEncryptsAndNeverReturnsTheSecret() throws Exception {
        Account a = account("mail-ok");
        String address = MailTestServer.newUser("ok", SECRET.replace(" ", ""));

        MvcResult r = link(a, address.toUpperCase(), SECRET).andExpect(status().isCreated())
                .andExpect(jsonPath("$.address").value(address))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.secretLast4").value("mnop"))
                .andExpect(jsonPath("$.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.lastOkAt").isNotEmpty())
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("abcdefghijklmnop").doesNotContain("cipher");

        Map<String, Object> row = jdbc.queryForMap("select * from jarvis_mail_accounts where user_id = ?", a.id());
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(v -> v.contains("abcdefghijklmnop"));
        assertThat(cipher.decrypt(new EncryptedKey((String) row.get("encrypted_data_key"), (String) row.get("cipher_iv"),
                (String) row.get("ciphertext")))).isEqualTo("abcdefghijklmnop");

        mockMvc.perform(get("/api/jarvis/mail/accounts").contextPath("/api").header("Authorization", a.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Perso"));
    }

    @Test
    void wrongPasswordFailsClearlyAndWritesNothing() throws Exception {
        Account a = account("mail-ko");
        String address = MailTestServer.newUser("ko", "lebonmotdepasse0");

        link(a, address, "mauvaismotdepass").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("mail_auth_failed"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("mot de passe")));
        assertThat(jdbc.queryForObject("select count(*) from jarvis_mail_accounts where user_id = ?", Long.class,
                a.id())).isZero();
    }

    @Test
    void invalidInputsAreRefusedBeforeAnyNetworkCall() throws Exception {
        Account a = account("mail-invalid");
        link(a, "pas-une-adresse", SECRET).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_mail_account"));
        link(a, "x@example.com", "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_mail_account"));
    }

    @Test
    void relinkingTheSameAddressReplacesTheSecretAndAThirdBoxIsRefused() throws Exception {
        Account a = account("mail-full");
        String first = MailTestServer.newUser("b1", "secretnumero0001");
        String second = MailTestServer.newUser("b2", "secretnumero0002");
        String third = MailTestServer.newUser("b3", "secretnumero0003");

        link(a, first, "secretnumero0001").andExpect(status().isCreated());
        link(a, second, "secretnumero0002").andExpect(status().isCreated());
        // Relier à nouveau la même adresse : une seule ligne, secret remplacé (geste de réparation).
        MailTestServer.get().setUser(first, first, "secretnumero0009");
        link(a, first, "secretnumero0009").andExpect(status().isCreated())
                .andExpect(jsonPath("$.secretLast4").value("0009"));
        assertThat(jdbc.queryForObject("select count(*) from jarvis_mail_accounts where user_id = ?", Long.class,
                a.id())).isEqualTo(2L);

        link(a, third, "secretnumero0003").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("mail_accounts_full"));
    }

    @Test
    void testRevokeAndIsolation() throws Exception {
        Account a = account("mail-a");
        Account b = account("mail-b");
        String address = MailTestServer.newUser("iso", "secretisolation1");
        String id = json(link(a, address, "secretisolation1").andExpect(status().isCreated()).andReturn())
                .get("id").asText();

        // B ne voit rien et reçoit 404 sur tester / révoquer.
        mockMvc.perform(get("/api/jarvis/mail/accounts").contextPath("/api").header("Authorization", b.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/jarvis/mail/accounts/" + id + "/test").contextPath("/api")
                .header("Authorization", b.bearer())).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/jarvis/mail/accounts/" + id).contextPath("/api")
                .header("Authorization", b.bearer())).andExpect(status().isNotFound());

        // A teste : OK, dossiers rendus.
        mockMvc.perform(post("/api/jarvis/mail/accounts/" + id + "/test").contextPath("/api")
                        .header("Authorization", a.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.folders").exists());

        // Google révoque le mot de passe : le test rend AUTH_FAILED sans erreur HTTP.
        MailTestServer.get().setUser(address, address, "autremotdepasse1");
        mockMvc.perform(post("/api/jarvis/mail/accounts/" + id + "/test").contextPath("/api")
                        .header("Authorization", a.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.lastError").isNotEmpty());

        // A révoque : la ligne disparaît.
        mockMvc.perform(delete("/api/jarvis/mail/accounts/" + id).contextPath("/api")
                .header("Authorization", a.bearer())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from jarvis_mail_accounts where user_id = ?", Long.class,
                a.id())).isZero();
    }

    @Test
    void accountDeletionPurgesTheBoxes() throws Exception {
        Account a = account("mail-purge");
        link(a, MailTestServer.newUser("purge", "secretpurge00001"), "secretpurge00001")
                .andExpect(status().isCreated());
        accountService.deleteAccount(a.id());
        List<Map<String, Object>> rows = jdbc.queryForList("select id from jarvis_mail_accounts where user_id = ?",
                a.id());
        assertThat(rows).isEmpty();
    }

    @Test
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(get("/api/jarvis/mail/accounts").contextPath("/api")).andExpect(status().isUnauthorized());
    }
}
