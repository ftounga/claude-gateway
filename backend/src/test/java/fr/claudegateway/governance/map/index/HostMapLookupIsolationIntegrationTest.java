package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Le journal des consultations, en base (F-174 / SF-174-01) : isolation {@code user_id} et purges
 * nommées (D10).
 */
@SpringBootTest
@ActiveProfiles("test")
class HostMapLookupIsolationIntegrationTest {

    @Autowired private HostMapLookupRepository repository;
    @Autowired private HostMapIndexPurge purge;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID aliceHost = UUID.randomUUID();
    private final UUID aliceOtherHost = UUID.randomUUID();
    private final UUID bobHost = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        save(alice, aliceHost);
        save(alice, aliceOtherHost);
        save(bob, bobHost);
    }

    private void save(UUID userId, UUID hostId) {
        repository.save(HostMapLookup.builder().userId(userId).hostId(hostId).kind("TURN")
                .strategy("LEXICAL").factsCount(2).chars(100).createdAt(OffsetDateTime.now()).build());
    }

    @Test
    @DisplayName("un compte ne lit que ses propres consultations")
    void readsAreIsolated() {
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(repository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(alice,
                now.minusHours(1), now.plusHours(1))).hasSize(2)
                .allMatch(l -> l.getUserId().equals(alice));
    }

    @Test
    @DisplayName("la suppression d'un poste ne purge que ce poste, celle d'un compte que ce compte")
    void purgesAreScoped() {
        purge.purgeHost(alice, aliceHost);
        assertThat(repository.findAll()).extracting(HostMapLookup::getHostId)
                .containsExactlyInAnyOrder(aliceOtherHost, bobHost);
        purge.purgeUser(alice);
        assertThat(repository.findAll()).extracting(HostMapLookup::getUserId).containsExactly(bob);
        // Le poste d'un autre compte ne se purge pas en passant le sien.
        purge.purgeHost(alice, bobHost);
        assertThat(repository.findAll()).hasSize(1);
    }
}
