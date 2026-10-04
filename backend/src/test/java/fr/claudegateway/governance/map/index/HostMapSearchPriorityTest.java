package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.governance.map.HostMapFactsBlock;
import fr.claudegateway.governance.map.index.HostMapSearch.Reason;

/** Les pièges et les échéances d'abord (F-174 / SF-174-04, D7). */
class HostMapSearchPriorityTest {

    private final HostMapFactRepository facts = mock(HostMapFactRepository.class);
    private final HostMapEntityRepository entities = mock(HostMapEntityRepository.class);
    private final HostMapSemantic semantic = mock(HostMapSemantic.class);
    private final HostMapSearch search = new HostMapSearch(facts, entities, semantic,
            new HostMapIndexProperties(null, null, null, null, null, null, null, null, null, null, null, null));

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final LocalDate today = LocalDate.parse("2026-10-04");

    private HostMapFact fact(String text, String kind, LocalDate due) {
        return HostMapFact.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId).path("acces.md")
                .heading("GitLab").lineNo(1).text(text).kind(kind).dueOn(due).build();
    }

    private final HostMapFact soon = fact("- jeton GitLab périme le 2026-10-09", "ECHEANCE", LocalDate.parse("2026-10-09"));
    private final HostMapFact far = fact("- certificat GitLab expire le 2027-03-01", "ECHEANCE", LocalDate.parse("2027-03-01"));
    private final HostMapFact expired = fact("- jeton GitLab expiré le 2026-09-30", "ECHEANCE", LocalDate.parse("2026-09-30"));
    private final HostMapFact pitfall = fact("- piège : GitLab refuse le clone HTTPS derrière Netskope", "PIEGE", null);
    private final HostMapFact otherPitfall = fact("- piège : le bastion coupe après 10 min", "PIEGE", null);
    private final HostMapFact plain = fact("- GitLab est sur gitlab.corp.example", "FAIT", null);

    @BeforeEach
    void setUp() {
        when(entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(userId, hostId)).thenReturn(List.of(
                HostMapEntity.builder().labelNorm("gitlab").kind("forge").build()));
        when(facts.findByUserIdAndHostIdAndKindInOrderByPathAscLineNoAsc(any(), any(), any()))
                .thenReturn(List.of(soon, far, expired, pitfall, otherPitfall));
        when(facts.findByIdentifierPattern(any(), any(), anyString())).thenReturn(List.of());
        when(facts.findByTextPattern(userId, hostId, HostMapLikes.contains("gitlab")))
                .thenReturn(List.of(plain, soon, pitfall));
    }

    @Test
    @DisplayName("échéance proche (et récemment dépassée) puis piège de la ressource touchée, avant le reste")
    void deadlinesThenPitfallsFirst() {
        HostMapSearch.Result result = search.search(userId, hostId, "je clone le dépôt sur GitLab", 20, today);

        assertThat(result.hits()).extracting(HostMapSearch.Hit::reason)
                .containsExactly(Reason.DEADLINE, Reason.DEADLINE, Reason.PITFALL, Reason.ENTITY);
        assertThat(result.hits()).extracting(h -> h.fact().getText())
                .containsExactly(expired.getText(), soon.getText(), pitfall.getText(), plain.getText());
        // L'échéance lointaine et le piège d'une autre ressource ne sont pas mis en avant.
        assertThat(result.hits()).extracting(h -> h.fact().getText())
                .doesNotContain(far.getText(), otherPitfall.getText());
    }

    @Test
    @DisplayName("sans ressource touchée, aucune priorité ; sans date, aucune échéance mise en avant")
    void noTouchNoPriority() {
        assertThat(search.search(userId, hostId, "une question sans nom", 20, today).hits()).isEmpty();
        assertThat(search.search(userId, hostId, "et GitLab ?", 20, null).hits())
                .extracting(HostMapSearch.Hit::reason).doesNotContain(Reason.DEADLINE);
    }

    @Test
    @DisplayName("les pièges ne prennent pas plus de la moitié de la place")
    void pitfallsAreBounded() {
        List<HostMapFact> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(fact("- piège GitLab n°" + i, "PIEGE", null));
        }
        when(facts.findByUserIdAndHostIdAndKindInOrderByPathAscLineNoAsc(any(), any(), any())).thenReturn(many);
        assertThat(search.search(userId, hostId, "GitLab", 20, today).hits())
                .filteredOn(h -> h.reason() == Reason.PITFALL).hasSize(10);
    }

    @Test
    @DisplayName("le bloc marque pièges et échéances, et dit la consigne des pièges")
    void blockMarks() {
        String block = HostMapFactsBlock.render(List.of(new HostMapSearch.Hit(soon, Reason.DEADLINE),
                new HostMapSearch.Hit(expired, Reason.DEADLINE), new HostMapSearch.Hit(pitfall, Reason.PITFALL)),
                today, 120, 6_000);
        assertThat(block).contains("⟨échéance 2026-10-09 — dans 5 j⟩")
                .contains("⟨échéance 2026-09-30 — dépassée depuis 4 j⟩")
                .contains("⟨piège⟩")
                .contains("tiens-en compte AVANT d'agir");
    }
}
