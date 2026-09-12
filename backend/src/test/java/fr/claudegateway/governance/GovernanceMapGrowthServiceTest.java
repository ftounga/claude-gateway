package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.governance.GovernanceMapGrowthService.Observation;
import fr.claudegateway.governance.dto.GovernanceMapGrowthView;

/**
 * F-93 / SF-93-02 — <b>ce que la carte a gagné</b>.
 *
 * <p>Ce que ces tests protègent :</p>
 * <ul>
 *   <li><b>une première observation n'est pas un gain</b> — une carte déjà pleine le premier jour
 *       n'a rien gagné, elle était là, et la présenter comme un gain serait faux ;</li>
 *   <li><b>une diminution ne rend pas un chiffre négatif</b> — une carte qu'on élague a été rangée,
 *       pas appauvrie, et un « -3 » découragerait le ménage ;</li>
 *   <li><b>une panne n'empêche jamais de lire la carte</b> — perdre une trace de croissance coûte
 *       une phrase, perdre la carte coûterait la feature.</li>
 * </ul>
 *
 * <p>Le dépôt est simulé par une table en mémoire : c'est le comportement d'accumulation qu'on
 * teste, lecture après lecture, et non la persistance.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceMapGrowthServiceTest {

    @Mock
    private GovernanceMapGrowthRepository repository;

    private GovernanceMapGrowthService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);

    /** La « base » : une ligne par (utilisateur, poste, chemin), comme l'index d'unicité. */
    private final Map<String, GovernanceMapGrowth> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new GovernanceMapGrowthService(new GovernanceMapGrowthRecorder(repository));
        when(repository.findByUserIdAndHostId(any(), any())).thenAnswer(invocation -> {
            UUID user = invocation.getArgument(0);
            UUID currentHost = invocation.getArgument(1);
            List<GovernanceMapGrowth> rows = new ArrayList<>();
            for (GovernanceMapGrowth row : stored.values()) {
                if (row.getUserId().equals(user) && row.getHostId().equals(currentHost)) {
                    rows.add(row);
                }
            }
            return rows;
        });
        when(repository.save(any(GovernanceMapGrowth.class))).thenAnswer(invocation -> {
            GovernanceMapGrowth row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            stored.put(row.getUserId() + "|" + row.getHostId() + "|" + row.getPath(), row);
            return row;
        });
    }

    private GovernanceMapGrowthView observe(int acces, int reseau) {
        return service.observe(alice, host, List.of(new Observation("acces.md", "Accès", acces),
                new Observation("reseau.md", "Réseau", reseau)));
    }

    // -------------------------------------------------------- la référence

    @Test
    @DisplayName("la PREMIÈRE lecture pose une référence et ne rend AUCUN gain")
    void theFirstReadingIsAReferenceNotAGain() {
        GovernanceMapGrowthView view = observe(4, 2);

        assertThat(view).isNotNull();
        assertThat(view.sinceFacts()).isEqualTo(6);
        assertThat(view.gained()).isZero();
        assertThat(view.recent()).isEmpty();
        assertThat(view.since()).isNotNull();
    }

    @Test
    @DisplayName("une seconde lecture qui a plus de faits rend le gain, daté, par fichier")
    void asecondReadingWithMoreFactsShowsTheGain() {
        observe(4, 2);

        GovernanceMapGrowthView view = observe(7, 2);

        assertThat(view.sinceFacts()).isEqualTo(6);
        assertThat(view.gained()).isEqualTo(3);
        assertThat(view.recent()).hasSize(1);
        assertThat(view.recent().get(0).path()).isEqualTo("acces.md");
        assertThat(view.recent().get(0).title()).isEqualTo("Accès");
        assertThat(view.recent().get(0).gained()).isEqualTo(3);
        assertThat(view.recent().get(0).gainedAt()).isNotNull();
    }

    @Test
    @DisplayName("la référence NE BOUGE PAS : elle dit d'où l'on est parti")
    void thereferenceNeverMoves() {
        observe(4, 2);
        observe(7, 2);

        GovernanceMapGrowthView view = observe(9, 5);

        assertThat(view.sinceFacts()).isEqualTo(6);
        assertThat(view.gained()).isEqualTo(8);
    }

    @Test
    @DisplayName("autant de faits : rien de nouveau, et l'ancien gain reste ce qu'il était")
    void anequalReadingChangesNothing() {
        observe(4, 2);
        observe(7, 2);

        GovernanceMapGrowthView view = observe(7, 2);

        assertThat(view.gained()).isEqualTo(3);
        assertThat(view.recent()).hasSize(1);
    }

    // ------------------------------------------------------- la diminution

    @Test
    @DisplayName("MOINS de faits ne rend jamais un gain négatif : une carte rangée n'a rien perdu")
    void afewerFactsReadingNeverGoesNegative() {
        observe(4, 2);

        GovernanceMapGrowthView view = observe(1, 2);

        assertThat(view.gained()).isZero();
        assertThat(view.recent()).isEmpty();
    }

    @Test
    @DisplayName("après une diminution, un gain se compte depuis le NOUVEAU compte, pas l'ancien")
    void again_afterAFewerReading_countsFromTheNewValue() {
        observe(4, 2);
        observe(1, 2);

        GovernanceMapGrowthView view = observe(3, 2);

        assertThat(view.recent()).hasSize(1);
        assertThat(view.recent().get(0).gained()).isEqualTo(2);
    }

    // ------------------------------------------------------------ le bornage

    @Test
    @DisplayName("les gains récents sont bornés, les plus récents d'abord")
    void recentGainsAreBoundedAndOrdered() {
        List<Observation> first = new ArrayList<>();
        List<Observation> second = new ArrayList<>();
        for (int i = 0; i < GovernanceMapGrowthService.MAX_RECENT + 3; i++) {
            first.add(new Observation("fichier-" + i + ".md", "Fichier " + i, 0));
            second.add(new Observation("fichier-" + i + ".md", "Fichier " + i, i + 1));
        }
        service.observe(alice, host, first);

        GovernanceMapGrowthView view = service.observe(alice, host, second);

        assertThat(view.recent()).hasSize(GovernanceMapGrowthService.MAX_RECENT);
        assertThat(view.recent()).isSortedAccordingTo(
                java.util.Comparator.comparing(
                        fr.claudegateway.governance.dto.GovernanceMapGainView::gainedAt).reversed());
    }

    // ---------------------------------------------------------- les refus

    @Test
    @DisplayName("aucune observation : aucun bloc, et aucune écriture")
    void nothingObservedYieldsNothing() {
        assertThat(service.observe(alice, host, List.of())).isNull();
        assertThat(service.observe(alice, host, null)).isNull();
        assertThat(service.observe(null, host, List.of(new Observation("a.md", "A", 1)))).isNull();
        assertThat(service.observe(alice, null, List.of(new Observation("a.md", "A", 1)))).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("une panne d'écriture N'EMPÊCHE PAS de rendre la carte : le bloc est simplement nul")
    void awriteFailureNeverBreaksTheReading() {
        when(repository.save(any(GovernanceMapGrowth.class)))
                .thenThrow(new IllegalStateException("base indisponible"));

        assertThat(observe(4, 2)).isNull();
    }

    @Test
    @DisplayName("un chemin plus long que la borne est ignoré, sans faire échouer le reste")
    void anoverlongPathIsSkipped() {
        String overlong = "x".repeat(GovernanceMapGrowth.MAX_PATH_LENGTH + 1) + ".md";

        GovernanceMapGrowthView view = service.observe(alice, host,
                List.of(new Observation(overlong, "Trop long", 9),
                        new Observation("acces.md", "Accès", 3)));

        assertThat(view.sinceFacts()).isEqualTo(3);
    }

    // ------------------------------------------------------------ isolation

    @Test
    @DisplayName("ce qu'Alice a observé n'est JAMAIS lu pour un autre : même poste, même chemin")
    void anotherUserSeesNothingOfAlicesObservations() {
        observe(4, 2);
        UUID mallory = UUID.randomUUID();

        GovernanceMapGrowthView view = service.observe(mallory, host,
                List.of(new Observation("acces.md", "Accès", 40)));

        // Pour Mallory, c'est une PREMIÈRE observation : aucun gain, et sa référence est la sienne.
        assertThat(view.gained()).isZero();
        assertThat(view.sinceFacts()).isEqualTo(40);
        assertThat(stored).hasSize(3);
    }

    @Test
    @DisplayName("deux postes du même utilisateur ne se mélangent pas")
    void twoHostsOfTheSameUserStaySeparate() {
        observe(4, 2);
        GovernanceHostRef other = GovernanceHostRef.of(UUID.randomUUID());

        GovernanceMapGrowthView view = service.observe(alice, other,
                List.of(new Observation("acces.md", "Accès", 11)));

        assertThat(view.gained()).isZero();
        assertThat(view.sinceFacts()).isEqualTo(11);
    }

    /** Une date d'observation existe toujours : elle est ce qui date le constat. */
    @Test
    @DisplayName("chaque ligne retenue porte sa date d'observation")
    void everyRowIsDated() {
        observe(4, 2);

        for (GovernanceMapGrowth row : stored.values()) {
            assertThat(row.getObservedAt()).isNotNull().isBeforeOrEqualTo(OffsetDateTime.now());
            assertThat(row.getFirstSeenAt()).isNotNull();
        }
    }
}
