package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.thread.JarvisLinkService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-272 / SF-272-01 — les délégations de Jarvis partagées entre les pods : deux registres (deux pods) sur la même base.
 * Le cerveau rapide offre sur le pod A, le tour Opus prend sur le pod B ; une seule prise ; isolation ; balayage des
 * offres jamais prises et de leur ligne-lien.
 */
@SpringBootTest
@ActiveProfiles("test")
class JarvisDelegationStoreIntegrationTest {

    @Autowired private JarvisDelegationRepository repository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AtelierMessageRepository messages;
    @Autowired private JarvisLinkService links;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-18T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private JarvisDelegationStore store;
    private JarvisDelegationRegistry podA;
    private JarvisDelegationRegistry podB;
    private UUID alice;
    private UUID bob;
    private UUID subject;

    @BeforeEach
    void setUp() {
        now.set(Instant.now());
        store = new JarvisDelegationStore(repository, clock);
        store.setLinks(links);
        podA = new JarvisDelegationRegistry(clock, null);
        podA.setStore(store);
        podB = new JarvisDelegationRegistry(clock, null);
        podB.setStore(store);
        alice = seedUser("deleg-alice-" + UUID.randomUUID() + "@ex.com");
        bob = seedUser("deleg-bob-" + UUID.randomUUID() + "@ex.com");
        subject = workspaceRepository.save(Workspace.builder().userId(alice).name("data-platform").build()).getId();
    }

    @Test
    @DisplayName("offerte sur le pod A, la demande reformulée est prise par le tour du pod B, une seule fois")
    void offeredOnOnePodTakenOnTheOther() {
        UUID delegation = UUID.randomUUID();
        UUID link = links.open(alice, subject, null, "Dessiner l'architecture Airflow");
        podA.offer(alice, subject, null, delegation, "Dessine l'architecture Airflow de data-platform", "Airflow",
                link);
        podA.started(alice, delegation, subject, "data-platform", null, "Airflow");

        JarvisDelegationRegistry.Reformulation taken = podB.take(alice, subject, null);

        assertThat(taken).isNotNull();
        assertThat(taken.demande()).isEqualTo("Dessine l'architecture Airflow de data-platform");
        assertThat(taken.delegationId()).isEqualTo(delegation);
        assertThat(taken.linkId()).isEqualTo(link);
        assertThat(podA.take(alice, subject, null)).isNull();
        assertThat(podB.take(alice, subject, null)).isNull();
    }

    @Test
    @DisplayName("deux tours concurrents (deux pods) : un seul obtient la reformulation")
    void onlyOneConcurrentTakeWins() throws Exception {
        podA.offer(alice, subject, 3, UUID.randomUUID(), "Lister les tickets bloqués", "tickets", null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<JarvisDelegationRegistry.Reformulation> a = () -> podA.take(alice, subject, 3);
            Callable<JarvisDelegationRegistry.Reformulation> b = () -> podB.take(alice, subject, 3);
            Future<JarvisDelegationRegistry.Reformulation> fa = pool.submit(a);
            Future<JarvisDelegationRegistry.Reformulation> fb = pool.submit(b);
            long winners = java.util.stream.Stream.of(fa.get(), fb.get()).filter(java.util.Objects::nonNull).count();
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("isolation : ni un autre compte, ni un autre chantier ne prennent la reformulation")
    void isolation() {
        podA.offer(alice, subject, 3, UUID.randomUUID(), "x", "y", null);

        assertThat(podB.take(bob, subject, 3)).isNull();
        assertThat(podB.take(alice, subject, null)).isNull();
        assertThat(podB.take(alice, subject, 4)).isNull();
        assertThat(podB.running(bob)).isEmpty();
        assertThat(podB.take(alice, subject, 3)).isNotNull();
    }

    @Test
    @DisplayName("en cours sur tous les pods : lancée sur A, vue sur B ; prise puis finie, elle n'y est plus")
    void runningIsSharedAndEndsWithTheTurn() {
        UUID delegation = UUID.randomUUID();
        podA.offer(alice, subject, null, delegation, "Regarde les tickets LDIC", "tickets LDIC", null);
        podA.started(alice, delegation, subject, "data-platform", null, "tickets LDIC");

        assertThat(podB.running(alice)).extracting(JarvisDelegationRegistry.Running::label)
                .containsExactly("tickets LDIC");
        assertThat(podB.running(alice).get(0).subjectName()).isEqualTo("data-platform");

        assertThat(podB.take(alice, subject, null)).isNotNull();
        now.set(now.get().plus(Duration.ofMinutes(5)));
        assertThat(podA.running(alice)).hasSize(1); // prise : le tour tourne encore, au-delà de la grâce

        podB.finished(alice, delegation);
        assertThat(podA.running(alice)).isEmpty();
    }

    @Test
    @DisplayName("une offre jamais prise n'est plus prise après 2 min, et le balayage clôt sa ligne-lien « en cours »")
    void sweepClosesAnOrphanAndItsLink() {
        UUID delegation = UUID.randomUUID();
        UUID link = links.open(alice, subject, null, "Coupe le déploiement");
        podA.offer(alice, subject, null, delegation, "Dessine l'architecture", "architecture", link);
        now.set(now.get().plus(JarvisDelegationRegistry.REFORMULATION_TTL).plusSeconds(1));

        assertThat(podB.take(alice, subject, null)).isNull();
        // Base partagée avec les autres tests : le balayage (tous comptes) en clôt au moins une.
        assertThat(store.sweep(JarvisDelegationRegistry.REFORMULATION_TTL, JarvisDelegationRegistry.MAX_AGE))
                .isGreaterThanOrEqualTo(1);
        AtelierMessage line = messages.findByIdAndUserId(link, alice).orElseThrow();
        assertThat(line.getLinkStatus()).isEqualTo(AtelierMessage.LINK_FAILED);
        assertThat(repository.findByUserIdAndDelegationId(alice, delegation).orElseThrow().getFinishedAt())
                .isNotNull();
        assertThat(podA.running(alice)).isEmpty();
    }

    @Test
    @DisplayName("une délégation prise mais jamais finie (pod arrêté) est close après 2 h, sa ligne « interrompu »")
    void sweepClosesAStaleTakenDelegation() {
        UUID delegation = UUID.randomUUID();
        UUID link = links.open(alice, subject, null, "tickets");
        podA.offer(alice, subject, null, delegation, "Regarde les tickets", "tickets", link);
        assertThat(podB.take(alice, subject, null)).isNotNull();
        now.set(now.get().plus(JarvisDelegationRegistry.MAX_AGE).plusSeconds(1));

        // Base partagée avec les autres tests : le balayage (tous comptes) en clôt au moins une.
        assertThat(store.sweep(JarvisDelegationRegistry.REFORMULATION_TTL, JarvisDelegationRegistry.MAX_AGE))
                .isGreaterThanOrEqualTo(1);
        AtelierMessage line = messages.findByIdAndUserId(link, alice).orElseThrow();
        assertThat(line.getLinkStatus()).isEqualTo(AtelierMessage.LINK_FAILED);
        assertThat(line.getContent()).contains("interrompu");
    }

    @Test
    @DisplayName("la suppression du compte purge ses délégations, pas celles d'un autre")
    void purgeUser() {
        podA.offer(alice, subject, null, UUID.randomUUID(), "a", "a", null);
        UUID bobSubject = workspaceRepository.save(Workspace.builder().userId(bob).name("waf").build()).getId();
        podA.offer(bob, bobSubject, null, UUID.randomUUID(), "b", "b", null);

        store.purgeUser(alice);

        assertThat(podB.take(alice, subject, null)).isNull();
        assertThat(podB.take(bob, bobSubject, null)).isNotNull();
    }

    private UUID seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build()).getId();
    }
}
