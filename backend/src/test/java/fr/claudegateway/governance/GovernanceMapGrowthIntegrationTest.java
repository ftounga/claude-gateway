package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.governance.GovernanceMapGrowthService.Observation;
import fr.claudegateway.governance.dto.GovernanceMapGrowthView;

/**
 * F-93 / SF-93-02 — <b>ce que la carte a gagné</b>, écrit et relu pour de bon.
 *
 * <p>Le test unitaire simule le dépôt ; celui-ci vérifie ce qui ne se simule pas : que la migration
 * <b>078</b> existe et porte les colonnes attendues, que la transaction à part
 * ({@code REQUIRES_NEW}) écrit réellement, et que l'<b>index d'unicité</b>
 * {@code (user_id, host_id, path)} sépare bien deux comptes observant le même chemin.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class GovernanceMapGrowthIntegrationTest {

    @Autowired private GovernanceMapGrowthService service;
    @Autowired private GovernanceMapGrowthRepository growth;

    private final UUID alice = UUID.randomUUID();
    private final UUID mallory = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        growth.deleteAll();
    }

    @Test
    @DisplayName("deux lectures successives : la seconde dit CE QUE LA CARTE A GAGNÉ, et c'est écrit")
    void asecondReadingTellsWhatTheMapGained() {
        GovernanceMapGrowthView first = service.observe(alice, host,
                List.of(new Observation("acces.md", "Accès", 2)));

        assertThat(first.gained()).isZero();
        assertThat(first.recent()).isEmpty();

        GovernanceMapGrowthView second = service.observe(alice, host,
                List.of(new Observation("acces.md", "Accès", 5)));

        assertThat(second.sinceFacts()).isEqualTo(2);
        assertThat(second.gained()).isEqualTo(3);
        assertThat(second.recent()).singleElement()
                .satisfies(gain -> {
                    assertThat(gain.path()).isEqualTo("acces.md");
                    assertThat(gain.gained()).isEqualTo(3);
                    assertThat(gain.gainedAt()).isNotNull();
                });

        List<GovernanceMapGrowth> rows = growth.findByUserIdAndHostId(alice, host.hostId());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getFacts()).isEqualTo(5);
            assertThat(row.getFirstFacts()).isEqualTo(2);
            assertThat(row.getLastGain()).isEqualTo(3);
            assertThat(row.getFirstSeenAt()).isNotNull();
            assertThat(row.getObservedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("ISOLATION : le même chemin, le même poste, deux comptes — deux lignes distinctes")
    void twoAccountsNeverShareARow() {
        service.observe(alice, host, List.of(new Observation("acces.md", "Accès", 2)));

        GovernanceMapGrowthView view = service.observe(mallory, host,
                List.of(new Observation("acces.md", "Accès", 40)));

        // Pour Mallory, c'est une PREMIÈRE observation : elle n'hérite pas de la référence d'Alice.
        assertThat(view.gained()).isZero();
        assertThat(view.sinceFacts()).isEqualTo(40);
        assertThat(growth.findByUserIdAndHostId(alice, host.hostId())).singleElement()
                .satisfies(row -> assertThat(row.getFacts()).isEqualTo(2));
        assertThat(growth.findByUserIdAndHostId(mallory, host.hostId())).hasSize(1);
    }
}
