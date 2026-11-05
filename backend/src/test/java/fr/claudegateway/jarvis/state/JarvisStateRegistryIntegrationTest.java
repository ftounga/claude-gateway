package fr.claudegateway.jarvis.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-02 — le registre : upsert en place (Z11), règles, isolation, coupure qui purge, bornes. */
@SpringBootTest
@ActiveProfiles("test")
class JarvisStateRegistryIntegrationTest {

    @Autowired private JarvisStateRegistry registry;
    @Autowired private JarvisStateFactRepository facts;
    @Autowired private JarvisStateSettingsRepository settings;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private UserRepository users;
    @Autowired private JarvisStateSeal seal;
    @Autowired private TransactionTemplate tx;

    private UUID alice;
    private UUID bob;
    private UUID aliceHost;
    private UUID aliceHost2;
    private UUID bobHost;
    private final OffsetDateTime t0 = OffsetDateTime.now().minusHours(2);

    @BeforeEach
    void setUp() {
        alice = user();
        bob = user();
        aliceHost = host(alice, "CAGIP");
        aliceHost2 = host(alice, "BNP");
        bobHost = host(bob, "BOB");
    }

    private UUID user() {
        return users.save(User.builder().email("state-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build()).getId();
    }

    private UUID host(UUID userId, String name) {
        return hosts.save(RunnerHost.builder().userId(userId).name(name).rootName("dev").os("linux").shell("posix")
                .elevated(false).build()).getId();
    }

    private static JarvisStateRegistry.Observation obs(UUID host, String key, String attr, String value,
            OffsetDateTime at, String confidence) {
        return new JarvisStateRegistry.Observation(host, null, "TICKET", key, attr, value, at, "TOUR", "t1",
                confidence);
    }

    @Test
    @DisplayName("Z11 — 1 000 observations d'un même fait donnent 1 ligne ; valeur changée ⇒ précédente gardée")
    void upsertInPlace() {
        List<JarvisStateRegistry.Observation> same = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            same.add(obs(aliceHost, "JIRA:PROJ-1", "status", "En cours", t0.plusSeconds(i), "HAUTE"));
        }
        registry.record(alice, same);
        assertThat(facts.countByUserIdAndHostId(alice, aliceHost)).isEqualTo(1);
        registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-1", "status", "En revue", t0.plusSeconds(2000),
                "HAUTE")));
        JarvisStateRegistry.Fact f = registry.byKey(alice, aliceHost, "JIRA:PROJ-1", 5).get(0);
        assertThat(f.value()).isEqualTo("En revue");
        assertThat(f.previousValue()).isEqualTo("En cours");
        assertThat(f.previousObservedAt().toEpochSecond()).isEqualTo(t0.plusSeconds(999).toEpochSecond());
        assertThat(f.changedAt()).isNotNull();
        assertThat(facts.countByUserIdAndHostId(alice, aliceHost)).isEqualTo(1);
    }

    @Test
    @DisplayName("observation plus ancienne ignorée ; HAUTE pas écrasée par MOYENNE < 5 min ; vocabulaire fermé")
    void rules() {
        registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-2", "status", "Fait", t0, "HAUTE")));
        assertThat(registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-2", "status", "Ouvert",
                t0.minusMinutes(1), "HAUTE")))).isZero();
        assertThat(registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-2", "status", "Ouvert",
                t0.plusMinutes(2), "MOYENNE")))).isZero();
        assertThat(registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-2", "status", "Rouvert",
                t0.plusMinutes(6), "MOYENNE")))).isEqualTo(1);
        assertThat(registry.byKey(alice, aliceHost, "JIRA:PROJ-2", 1).get(0).value()).isEqualTo("Rouvert");
        // hors vocabulaire : rien
        assertThat(registry.record(alice, List.of(new JarvisStateRegistry.Observation(aliceHost, null, "PIGEON",
                "X:1", "status", "v", t0, "TOUR", null, "HAUTE")))).isZero();
        assertThat(registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-3", "humeur", "v", t0, "HAUTE"))))
                .isZero();
        // valeur bornée à 300
        registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-4", "title", "x".repeat(500), t0, "HAUTE")));
        assertThat(registry.byKey(alice, aliceHost, "JIRA:PROJ-4", 1).get(0).value()).hasSize(300);
    }

    @Test
    @DisplayName("isolation : un compte n'écrit ni ne lit le client d'un autre ; deux clients séparés")
    void isolation() {
        assertThat(registry.record(bob, List.of(obs(aliceHost, "JIRA:PROJ-9", "status", "piraté", t0, "HAUTE"))))
                .isZero();
        registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-9", "status", "A", t0, "HAUTE"),
                obs(aliceHost2, "JIRA:PROJ-9", "status", "B", t0, "HAUTE")));
        assertThat(registry.recent(bob, aliceHost, 20)).isEmpty();
        assertThat(registry.byKey(bob, aliceHost, "JIRA:", 20)).isEmpty();
        assertThat(registry.recent(alice, aliceHost, 20)).extracting(JarvisStateRegistry.Fact::value)
                .containsExactly("A");
        assertThat(registry.recent(alice, aliceHost2, 20)).extracting(JarvisStateRegistry.Fact::value)
                .containsExactly("B");
        UUID id = registry.recent(alice, aliceHost, 1).get(0).id();
        assertThat(registry.delete(bob, id)).isFalse();
        assertThat(registry.delete(alice, id)).isTrue();
        assertThat(registry.recent(alice, aliceHost, 20)).isEmpty();
    }

    @Test
    @DisplayName("activé par défaut ; couper un client purge et arrête l'écriture ; famille coupée ; rallumer")
    void disablePurges() {
        assertThat(registry.settingsOf(alice, aliceHost).isEnabled()).isTrue();
        registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-5", "status", "A", t0, "HAUTE")));
        registry.setEnabled(alice, aliceHost, false);
        assertThat(facts.countByUserIdAndHostId(alice, aliceHost)).isZero();
        assertThat(registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-5", "status", "A", t0, "HAUTE"))))
                .isZero();
        JarvisStateSettings s = registry.setEnabled(alice, aliceHost, true);
        s.setDisabledFamilies("TICKET");
        registry.save(s);
        assertThat(registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-5", "status", "A", t0, "HAUTE"))))
                .isZero();
        assertThat(registry.record(alice, List.of(new JarvisStateRegistry.Observation(aliceHost, null, "MR",
                "GITLAB:g/p!1", "status", "opened", t0, "TOUR", null, "HAUTE")))).isEqualTo(1);
    }

    @Test
    @DisplayName("préfixe : les jokers saisis sont neutralisés")
    void prefixIsLiteral() {
        registry.record(alice, List.of(obs(aliceHost, "JIRA:PROJ-6", "status", "A", t0, "HAUTE"),
                obs(aliceHost, "GITLAB:a_b!1", "status", "B", t0, "HAUTE")));
        assertThat(registry.byKey(alice, aliceHost, "%", 20)).isEmpty();
        assertThat(registry.byKey(alice, aliceHost, "GITLAB:a_", 20)).hasSize(1);
        assertThat(registry.byKey(alice, aliceHost, "GITLAB:a%b", 20)).isEmpty();
    }

    @Test
    @DisplayName("plafond par client : au-delà, les plus anciens sont purgés ; purge par rétention")
    void capAndPurge() {
        JarvisStateRegistry small = new JarvisStateRegistry(facts, settings, hosts, seal,
                new JarvisStateProperties(null, null, null, null, null, null, 5), Clock.systemUTC());
        List<JarvisStateRegistry.Observation> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(obs(aliceHost, "JIRA:CAP-" + i, "status", "v" + i, t0.plusMinutes(i), "HAUTE"));
        }
        tx.executeWithoutResult(s -> small.record(alice, many));
        assertThat(facts.countByUserIdAndHostId(alice, aliceHost)).isEqualTo(5);
        assertThat(small.recent(alice, aliceHost, 20)).extracting(JarvisStateRegistry.Fact::objectKey)
                .contains("JIRA:CAP-7").doesNotContain("JIRA:CAP-0");

        registry.record(alice, List.of(obs(aliceHost2, "JIRA:OLD-1", "status", "v", OffsetDateTime.now()
                .minusDays(40), "HAUTE"), obs(aliceHost2, "JIRA:NEW-1", "status", "v", OffsetDateTime.now(),
                "HAUTE")));
        registry.purge();
        assertThat(registry.recent(alice, aliceHost2, 20)).extracting(JarvisStateRegistry.Fact::objectKey)
                .containsExactly("JIRA:NEW-1");
    }

    @Test
    @DisplayName("coupe-circuit général : rien écrit, rien lu")
    void killSwitch() {
        JarvisStateRegistry off = new JarvisStateRegistry(facts, settings, hosts, seal,
                new JarvisStateProperties(false, null, null, null, null, null, null), Clock.systemUTC());
        assertThat(off.record(alice, List.of(obs(aliceHost, "JIRA:K-1", "status", "v", t0, "HAUTE")))).isZero();
        registry.record(alice, List.of(obs(aliceHost, "JIRA:K-1", "status", "v", t0, "HAUTE")));
        assertThat(off.recent(alice, aliceHost, 20)).isEmpty();
        assertThat(tx).isNotNull();
    }
}
