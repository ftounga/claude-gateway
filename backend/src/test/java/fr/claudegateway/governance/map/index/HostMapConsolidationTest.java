package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La carte se tient (F-174 / SF-174-06, D9) : propositions, jamais d'écriture. */
class HostMapConsolidationTest {

    private final HostMapFactRepository facts = mock(HostMapFactRepository.class);
    private final HostMapSectionRepository sections = mock(HostMapSectionRepository.class);
    private final HostMapEntityRepository entities = mock(HostMapEntityRepository.class);
    private final HostMapConsolidation consolidation = new HostMapConsolidation(facts, sections, entities, 120);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final LocalDate today = LocalDate.parse("2026-10-04");
    private final UUID causeA = UUID.randomUUID();
    private final UUID causeB = UUID.randomUUID();

    private HostMapFact fact(String path, String heading, int line, String text, String kind,
            LocalDate observed, LocalDate due) {
        return HostMapFact.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId).path(path)
                .heading(heading).lineNo(line).text(text).kind(kind).observedOn(observed).dueOn(due).build();
    }

    @BeforeEach
    void setUp() {
        when(facts.findByUserIdAndHostIdOrderByPathAscLineNoAsc(userId, hostId)).thenReturn(List.of(
                fact("acces.md", "Proxy", 3, "- Le proxy Netskope coupe les websockets, constaté le 2026-09-01",
                        "PIEGE", LocalDate.parse("2026-09-01"), null),
                fact("exploitation.md", "Incidents", 40, "- le proxy netskope coupe les websockets.", "FAIT",
                        null, null),
                fact("acces.md", "Bastions", 9, "- bastion lzi ouvert, constaté le 2026-01-10", "FAIT",
                        LocalDate.parse("2026-01-10"), null),
                fact("acces.md", "Jetons", 12, "- jeton GitLab périme le 2026-09-20", "ECHEANCE", null,
                        LocalDate.parse("2026-09-20"))));
        when(sections.findByUserIdAndHostIdOrderByPathAscOrdinalAsc(userId, hostId)).thenReturn(List.of(
                HostMapSection.builder().id(causeA).path("exploitation.md").heading("Cause réelle").build(),
                HostMapSection.builder().id(causeB).path("exploitation.md").heading("Cause  réelle").build(),
                HostMapSection.builder().id(UUID.randomUUID()).path("acces.md").heading("Proxy").build()));
        when(facts.findBySectionIdOrderByLineNoAsc(any())).thenReturn(List.of());
        when(entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(userId, hostId)).thenReturn(List.of(
                HostMapEntity.builder().label("api.cagip.fr").labelNorm("api.cagip.fr").state("joignable")
                        .path("acces.md").build(),
                HostMapEntity.builder().label("api.cagip.fr").labelNorm("api.cagip.fr").state("injoignable")
                        .path("exploitation.md").build()));
    }

    @Test
    @DisplayName("doublon, sections de même titre, états contradictoires, faits périmés, échéance dépassée")
    void proposesEachFamily() {
        HostMapConsolidation.View view = consolidation.proposals(userId, hostId, today);

        assertThat(view.indexed()).isTrue();
        assertThat(view.proposals()).extracting(HostMapConsolidation.Proposal::kind)
                .containsExactly("DOUBLON", "CONTRADICTION", "CONTRADICTION", "PERIME", "ECHEANCE_DEPASSEE");
        HostMapConsolidation.Proposal duplicate = view.proposals().get(0);
        assertThat(duplicate.facts()).hasSize(2);
        assertThat(duplicate.request()).contains("Montre-moi le changement avant de l'écrire");
        assertThat(view.proposals().get(1).summary()).contains("Cause réelle").contains("2 fois");
        assertThat(view.proposals().get(2).summary()).contains("joignable et injoignable");
        assertThat(view.proposals().get(3).facts()).extracting(HostMapConsolidation.FactRef::lineNo)
                .containsExactly(9);
        assertThat(view.proposals().get(4).summary()).contains("2026-09-20");
    }

    @Test
    @DisplayName("une carte non indexée ne propose rien ; un poste absent non plus")
    void nothingWithoutIndex() {
        when(facts.findByUserIdAndHostIdOrderByPathAscLineNoAsc(userId, hostId)).thenReturn(List.of());
        assertThat(consolidation.proposals(userId, hostId, today).indexed()).isFalse();
        assertThat(consolidation.proposals(null, hostId, today).proposals()).isEmpty();
    }

    @Test
    @DisplayName("la normalisation ignore puce, date de constat, casse, espaces et ponctuation finale")
    void normalizes() {
        assertThat(HostMapConsolidation.normalize("- Le  Proxy coupe, constaté le 2026-09-01."))
                .isEqualTo(HostMapConsolidation.normalize("le proxy coupe"));
    }
}
