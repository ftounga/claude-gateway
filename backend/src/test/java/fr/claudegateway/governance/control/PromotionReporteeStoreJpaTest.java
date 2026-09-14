package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceSource;

/**
 * F-93 / SF-93-05 — <b>le report de promotion survit à un redémarrage</b>, écrit et relu pour de bon.
 *
 * <p>Le test mémoire ({@link PromotionReporteeTest}) simule le registre ; celui-ci vérifie ce qui ne
 * se simule pas : que la migration <b>106</b> existe et porte la clé unique du triple, qu'un report
 * <b>persiste</b> (une nouvelle instance du store sur la même base — un autre pod, un redémarrage —
 * le retrouve, dans une autre transaction), qu'il est <b>réclamé une seule fois</b> (la ligne est
 * supprimée), qu'il est isolé sur {@code (user, host, projet)}, qu'il <b>cumule</b>, et que la
 * <b>durée de vie</b> l'oublie.</p>
 *
 * <p>Chaque appel du store est joué dans sa propre transaction (le store réel est un bean {@code
 * @Transactional} ; ici l'instance est construite à la main pour maîtriser l'horloge, d'où le {@link
 * TransactionTemplate}). Deux transactions distinctes valent deux tours — voire deux pods.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PromotionReporteeStoreJpaTest {

    @Autowired private PromotionReporteeRepository repository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-09-14T08:00:00Z"));
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

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();
    private final UUID autreHost = UUID.randomUUID();
    private UUID projet;
    private UUID autreProjet;

    /** Une instance neuve du store à chaque appel : rien ne survit en mémoire, tout est en base. */
    private JpaPromotionReporteeStore store() {
        return new JpaPromotionReporteeStore(repository, clock);
    }

    private void reporter(UUID userId, UUID hostId, UUID workspaceId, List<String> elements, int dette) {
        tx.executeWithoutResult(status -> store().reporter(userId, hostId, workspaceId, elements, dette));
    }

    private Optional<PromotionReportee.Report> reclamer(UUID userId, UUID hostId, UUID workspaceId) {
        return inTx(() -> store().reclamer(userId, hostId, workspaceId));
    }

    private boolean estDue(UUID userId, UUID hostId, UUID workspaceId) {
        return Boolean.TRUE.equals(inTx(() -> store().estDue(userId, hostId, workspaceId)));
    }

    private <T> T inTx(Supplier<T> action) {
        return tx.execute(status -> action.get());
    }

    private UUID workspace(UUID owner) {
        Workspace workspace = Workspace.builder()
                .userId(owner)
                .name("projet-" + UUID.randomUUID())
                .source(WorkspaceSource.ARCHIVE)
                .executionTarget(WorkspaceExecutionTarget.SANDBOX)
                .build();
        return workspaceRepository.save(workspace).getId();
    }

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        repository.deleteAll();
        projet = workspace(alice);
        autreProjet = workspace(alice);
    }

    @Test
    @DisplayName("un report posé survit à un redémarrage : une nouvelle instance du store, une autre transaction, le retrouve")
    void survivesRestart() {
        reporter(alice, host, projet, List.of("cluster atlas"), 2);

        // Autre instance du store, autre transaction : un autre pod, ou après un redémarrage.
        assertThat(estDue(alice, host, projet)).isTrue();
        PromotionReportee.Report report = reclamer(alice, host, projet).orElseThrow();
        assertThat(report.elements()).containsExactly("cluster atlas");
        assertThat(report.dette()).isEqualTo(2);
        assertThat(report.reportedAt()).isNotNull();
    }

    @Test
    @DisplayName("réclamé une seule fois : la ligne est supprimée, même vue par une autre instance")
    void claimedOnceAcrossInstances() {
        reporter(alice, host, projet, List.of("vpn nord"), 0);

        assertThat(reclamer(alice, host, projet)).isPresent();
        assertThat(reclamer(alice, host, projet)).isEmpty();
        assertThat(estDue(alice, host, projet)).isFalse();
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("isolation : ni un autre utilisateur, ni un autre poste, ni un autre projet ne réclament")
    void isolation() {
        reporter(alice, host, projet, List.of("cluster atlas"), 0);

        assertThat(reclamer(bob, host, projet)).isEmpty();
        assertThat(reclamer(alice, autreHost, projet)).isEmpty();
        assertThat(reclamer(alice, host, autreProjet)).isEmpty();
        assertThat(reclamer(alice, host, projet)).isPresent();
    }

    @Test
    @DisplayName("des reports successifs se cumulent : éléments sans doublon, dette la plus haute, premier report")
    void reportsAccumulate() {
        reporter(alice, host, projet, List.of("cluster atlas"), 1);
        Instant first = now.get();
        now.set(first.plusSeconds(7200));
        reporter(alice, host, projet, List.of("cluster atlas", "vpn nord"), 0);

        PromotionReportee.Report report = reclamer(alice, host, projet).orElseThrow();
        assertThat(report.elements()).containsExactly("cluster atlas", "vpn nord");
        assertThat(report.dette()).isEqualTo(1);
        assertThat(report.reportedAt()).isCloseTo(first, org.assertj.core.api.Assertions.within(
                1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("un report plus vieux que sa durée de vie est purgé")
    void expires() {
        reporter(alice, host, projet, List.of("cluster atlas"), 0);
        now.set(now.get().plus(PromotionReportee.TTL).plusSeconds(1));

        assertThat(estDue(alice, host, projet)).isFalse();
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("le registre reste sous sa borne d'entrées : les plus anciennes sortent")
    void bounded() {
        reporter(alice, host, projet, List.of("le plus ancien"), 0);
        for (int i = 0; i < PromotionReportee.MAX_ENTRIES; i++) {
            now.set(now.get().plusSeconds(1));
            reporter(UUID.randomUUID(), host, projet, List.of("x"), 0);
        }

        assertThat(repository.count()).isEqualTo(PromotionReportee.MAX_ENTRIES);
        assertThat(estDue(alice, host, projet)).isFalse();
    }
}
