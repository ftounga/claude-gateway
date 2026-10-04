package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'outil {@code carte_chercher} côté gateway (F-174 / SF-174-05, D8). */
class HostMapSearchToolTest {

    private final HostMapSearch search = mock(HostMapSearch.class);
    private final HostMapFactRepository facts = mock(HostMapFactRepository.class);
    private final HostMapEntityRepository entities = mock(HostMapEntityRepository.class);
    private final HostMapRelationRepository relations = mock(HostMapRelationRepository.class);
    private final HostMapSearchTool tool = new HostMapSearchTool(search, facts, entities, relations);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final LocalDate today = LocalDate.parse("2026-10-04");

    private final HostMapEntity cluster = HostMapEntity.builder().kind("cluster").label("eks-prod-1")
            .labelNorm("eks-prod-1").identifiers("\neks-prod-1\n").state("actif").path("plateformes.md")
            .heading("Clusters").build();
    private final HostMapFact pitfall = HostMapFact.builder().id(UUID.randomUUID()).path("acces.md")
            .heading("Proxy").lineNo(4).text("- piège : eks-prod-1 refuse les websockets via Netskope")
            .kind("PIEGE").build();

    @Test
    @DisplayName("rend ressources, liens et faits sourcés avec leurs marques")
    void answersWithResourcesLinksAndFacts() {
        when(search.hasIndex(userId, hostId)).thenReturn(true);
        when(search.search(eq(userId), eq(hostId), eq("eks-prod-1"), anyInt(), eq(today)))
                .thenReturn(new HostMapSearch.Result(List.of(new HostMapSearch.Hit(pitfall,
                        HostMapSearch.Reason.PITFALL)), List.of(cluster)));
        when(relations.findByUserIdAndHostId(userId, hostId)).thenReturn(List.of(HostMapRelation.builder()
                .fromLabel("eks-prod-1").toLabel("123456789012").nature("dans").path("plateformes.md").build()));

        String answer = tool.run(userId, hostId, "eks-prod-1", null, null, today);

        assertThat(answer).contains("Ressources :").contains("- eks-prod-1 (cluster) — état : actif  [plateformes.md § Clusters]")
                .contains("Liens :").contains("eks-prod-1 —dans→ 123456789012")
                .contains("Faits :").contains("[acces.md § Proxy]  ⟨piège⟩")
                .endsWith(HostMapSearchTool.FOOTER);
    }

    @Test
    @DisplayName("par type : liste les ressources de ce type et les faits qui les nomment")
    void listsByType() {
        when(search.hasIndex(userId, hostId)).thenReturn(true);
        when(entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(userId, hostId)).thenReturn(List.of(cluster,
                HostMapEntity.builder().kind("compte_aws").label("123456789012").labelNorm("123456789012").build()));
        when(facts.findByTextPattern(userId, hostId, HostMapLikes.contains("eks-prod-1"))).thenReturn(List.of(pitfall));
        when(relations.findByUserIdAndHostId(userId, hostId)).thenReturn(List.of());

        String answer = tool.run(userId, hostId, null, "cluster", null, today);

        assertThat(answer).contains("eks-prod-1 (cluster)").doesNotContain("(compte_aws)").contains("⟨piège⟩");
        verify(search, never()).search(any(), any(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("par identifiant exact ; sans index, rien (l'appelant retombe sur F-137)")
    void byIdentifierAndWithoutIndex() {
        when(search.hasIndex(userId, hostId)).thenReturn(true);
        when(facts.findByIdentifierPattern(userId, hostId, HostMapLikes.exactIdentifier("123456789012")))
                .thenReturn(List.of(pitfall));
        when(search.search(any(), any(), anyString(), anyInt(), any()))
                .thenReturn(new HostMapSearch.Result(List.of(), List.of()));
        assertThat(tool.run(userId, hostId, null, null, "123456789012", today)).contains("1 fait(s)");

        when(search.hasIndex(userId, hostId)).thenReturn(false);
        assertThat(tool.run(userId, hostId, "x", null, null, today)).isNull();
    }
}
