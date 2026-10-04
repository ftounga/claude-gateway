package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Le plan de la carte (F-173 / SF-173-01), sur un index simulé. */
class HostMapGraphTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    private final UUID user = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();
    private final UUID s1 = UUID.randomUUID();
    private final UUID s2 = UUID.randomUUID();

    private final List<HostMapEntity> entityRows = new ArrayList<>();
    private final List<HostMapRelation> relationRows = new ArrayList<>();
    private final List<HostMapFact> factRows = new ArrayList<>();

    private HostMapGraph graph;

    @BeforeEach
    void setUp() {
        HostMapEntityRepository entities = mock(HostMapEntityRepository.class);
        HostMapRelationRepository relations = mock(HostMapRelationRepository.class);
        HostMapFactRepository facts = mock(HostMapFactRepository.class);
        HostMapSectionRepository sections = mock(HostMapSectionRepository.class);
        when(entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(user, host)).thenReturn(entityRows);
        when(relations.findByUserIdAndHostId(user, host)).thenReturn(relationRows);
        when(facts.findByUserIdAndHostIdOrderByPathAscLineNoAsc(user, host)).thenReturn(factRows);
        when(sections.findByUserIdAndHostIdOrderByPathAscOrdinalAsc(user, host)).thenReturn(List.of(
                HostMapSection.builder().id(s1).userId(user).hostId(host).path("infra.md").heading("Clusters")
                        .ordinal(0).fingerprint("a").status("DONE")
                        .createdAt(OffsetDateTime.parse("2026-10-01T10:00:00Z"))
                        .extractedAt(OffsetDateTime.parse("2026-10-02T10:00:00Z")).build()));
        when(sections.countByUserIdAndHostIdAndStatus(any(), any(), any())).thenReturn(0L);
        graph = new HostMapGraph(entities, relations, facts, sections, new ObjectMapper(), 120);
    }

    private void entity(UUID section, String origin, String kind, String label, String identifiers,
            String attributes, String state, LocalDate observed) {
        entityRows.add(HostMapEntity.builder().id(UUID.randomUUID()).userId(user).hostId(host).sectionId(section)
                .path("infra.md").heading("Clusters").kind(kind).label(label)
                .labelNorm(label.toLowerCase()).identifiers(identifiers).attributes(attributes).state(state)
                .observedOn(observed).origin(origin).build());
    }

    private void relation(String from, String to, String nature) {
        relationRows.add(HostMapRelation.builder().id(UUID.randomUUID()).userId(user).hostId(host).sectionId(s1)
                .path("infra.md").fromLabel(from).toLabel(to).nature(nature).build());
    }

    private HostMapFact fact(UUID section, int line, String text, String kind, LocalDate observed, LocalDate due,
            String identifiers) {
        HostMapFact fact = HostMapFact.builder().id(UUID.randomUUID()).userId(user).hostId(host).sectionId(section)
                .path("infra.md").heading("Clusters").lineNo(line).text(text).kind(kind).observedOn(observed)
                .dueOn(due).identifiers(identifiers).build();
        factRows.add(fact);
        return fact;
    }

    private HostMapGraph.Node node(HostMapGraph.View view, String label) {
        return view.nodes().stream().filter(n -> n.label().equalsIgnoreCase(label)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("une ressource citée deux fois est un seul nœud ; un motif porté par une ressource s'y range")
    void mergesByLabelAndIdentifier() {
        entity(s1, HostMapEntity.MODELE, "compte_aws", "compte prod", "\n123456789012\n",
                "{\"domaine\":\"paiement\",\"environnement\":\"prod\"}", "actif", TODAY.minusDays(3));
        entity(s2, HostMapEntity.MODELE, "compte_aws", "Compte prod", null, null, null, null);
        entity(s1, HostMapEntity.MOTIF, "compte_aws", "123456789012", "\n123456789012\n", null, null, null);

        HostMapGraph.View view = graph.graph(user, host, TODAY);

        assertThat(view.indexed()).isTrue();
        assertThat(view.nodes()).hasSize(1);
        HostMapGraph.Node node = view.nodes().get(0);
        assertThat(node.kind()).isEqualTo("compte_aws");
        assertThat(node.domain()).isEqualTo("paiement");
        assertThat(node.environment()).isEqualTo("prod");
        assertThat(node.identifiers()).containsExactly("123456789012");
        assertThat(node.id()).matches("[0-9a-f]{16}").isEqualTo(HostMapGraph.nodeId("compte prod"));
        assertThat(view.indexedAt()).isEqualTo(OffsetDateTime.parse("2026-10-02T10:00:00Z"));
    }

    @Test
    @DisplayName("« A dans B » range A sous B ; « A heberge B » range B sous A ; un cycle ne boucle pas")
    void hierarchyWithoutCycle() {
        entity(s1, HostMapEntity.MODELE, "cluster", "lzi-prod", null, null, null, null);
        entity(s1, HostMapEntity.MODELE, "compte_aws", "compte prod", null, null, null, null);
        entity(s1, HostMapEntity.MODELE, "service", "api", null, null, null, null);
        relation("lzi-prod", "compte prod", "dans");
        relation("lzi-prod", "api", "heberge");
        relation("compte prod", "api", "dans"); // fermerait le cycle compte → api → lzi → compte

        HostMapGraph.View view = graph.graph(user, host, TODAY);

        HostMapGraph.Node compte = node(view, "compte prod");
        HostMapGraph.Node cluster = node(view, "lzi-prod");
        HostMapGraph.Node api = node(view, "api");
        assertThat(compte.parentId()).isNull();
        assertThat(compte.depth()).isZero();
        assertThat(compte.children()).isEqualTo(1);
        assertThat(cluster.parentId()).isEqualTo(compte.id());
        assertThat(api.parentId()).isEqualTo(cluster.id());
        assertThat(api.depth()).isEqualTo(2);
        assertThat(view.edges()).hasSize(3);
    }

    @Test
    @DisplayName("un lien vers une ressource jamais nommée crée un nœud « autre »")
    void unknownLinkEndBecomesNode() {
        entity(s1, HostMapEntity.MODELE, "cluster", "lzi-prod", null, null, null, null);
        relation("lzi-prod", "bastion", "accede_a");

        HostMapGraph.View view = graph.graph(user, host, TODAY);

        assertThat(node(view, "bastion").kind()).isEqualTo("autre");
        assertThat(view.edges()).singleElement().extracting(HostMapGraph.Edge::nature).isEqualTo("accede_a");
    }

    @Test
    @DisplayName("un nœud porte ses pièges, son constat le plus récent, « périmé » et sa prochaine échéance")
    void signals() {
        entity(s1, HostMapEntity.MODELE, "cluster", "lzi-prod", "\nlzi.cagip.fr\n", null, "a_cartographier",
                TODAY.minusDays(300));
        fact(s1, 3, "- ⚠ lzi-prod coupe les websockets", HostMapFact.PIEGE, TODAY.minusDays(200), null, null);
        fact(s1, 4, "- le jeton de lzi-prod expire le 2026-11-02", HostMapFact.ECHEANCE, null,
                LocalDate.of(2026, 11, 2), null);
        fact(s1, 5, "- rien à voir", HostMapFact.FAIT, null, null, null);
        // Un fait d'une autre section qui porte l'identifiant exact.
        fact(s2, 9, "- DNS de la plateforme", HostMapFact.FAIT, null, null, "\nlzi.cagip.fr\n");
        fact(s2, 10, "- le registre Harbor reste à cartographier", HostMapFact.FAIT, null, null, null);
        fact(s2, 11, "- certificat expiré le 2026-09-01", HostMapFact.ECHEANCE, null, LocalDate.of(2026, 9, 1), null);

        HostMapGraph.View view = graph.graph(user, host, TODAY);

        HostMapGraph.Node node = node(view, "lzi-prod");
        assertThat(node.facts()).isEqualTo(3);
        assertThat(node.traps()).isEqualTo(1);
        assertThat(node.observedOn()).isEqualTo(TODAY.minusDays(200));
        assertThat(node.stale()).isTrue();
        assertThat(node.nextDue()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(node.toMap()).isTrue();

        assertThat(view.deadlines()).extracting(HostMapGraph.Deadline::dueOn)
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 2));
        assertThat(view.deadlines().get(0).overdue()).isTrue();
        assertThat(view.deadlines().get(0).nodeId()).isNull();
        assertThat(view.deadlines().get(1).nodeId()).isEqualTo(node.id());
        assertThat(view.toMap()).extracting(HostMapGraph.ToMap::label).contains("lzi-prod");
        assertThat(view.toMap()).extracting(HostMapGraph.ToMap::text)
                .contains("- le registre Harbor reste à cartographier");
    }

    @Test
    @DisplayName("la fiche met les pièges en tête et voit les relations des deux sens")
    void card() {
        entity(s1, HostMapEntity.MODELE, "cluster", "lzi-prod", null, null, null, null);
        entity(s1, HostMapEntity.MODELE, "compte_aws", "compte prod", null, null, null, null);
        entity(s1, HostMapEntity.MODELE, "proxy", "netskope", null, null, null, null);
        relation("lzi-prod", "compte prod", "dans");
        relation("netskope", "lzi-prod", "accede_a");
        fact(s1, 1, "- lzi-prod a 3 nœuds", HostMapFact.FAIT, null, null, null);
        fact(s1, 2, "- ne jamais redémarrer lzi-prod à la main", HostMapFact.PIEGE, null, null, null);

        HostMapGraph.Card card = graph.card(user, host, HostMapGraph.nodeId("lzi-prod"), TODAY).orElseThrow();

        assertThat(card.totalFacts()).isEqualTo(2);
        assertThat(card.facts().get(0).kind()).isEqualTo(HostMapFact.PIEGE);
        assertThat(card.relations()).extracting(HostMapGraph.CardRelation::direction, HostMapGraph.CardRelation::otherLabel)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("out", "compte prod"),
                        org.assertj.core.groups.Tuple.tuple("in", "netskope"));
        assertThat(card.sources()).singleElement().extracting(HostMapGraph.Source::path).isEqualTo("infra.md");
    }

    @Test
    @DisplayName("une fiche inconnue ou mal formée est vide")
    void unknownCard() {
        entity(s1, HostMapEntity.MODELE, "cluster", "lzi-prod", null, null, null, null);
        assertThat(graph.card(user, host, "0000000000000000", TODAY)).isEmpty();
        assertThat(graph.card(user, host, "../../etc", TODAY)).isEmpty();
        assertThat(graph.card(null, host, HostMapGraph.nodeId("lzi-prod"), TODAY)).isEmpty();
    }

    @Test
    @DisplayName("au-delà de la borne, les ressources les plus riches sont gardées et le plan le dit")
    void truncates() {
        for (int i = 0; i < HostMapGraph.MAX_NODES + 5; i++) {
            entity(s1, HostMapEntity.MOTIF, "ip", "10.0.0." + i, null, null, null, null);
        }
        entity(s1, HostMapEntity.MODELE, "cluster", "lzi-prod", null, null, null, null);
        fact(s1, 1, "- lzi-prod", HostMapFact.FAIT, null, null, null);

        HostMapGraph.View view = graph.graph(user, host, TODAY);

        assertThat(view.truncated()).isTrue();
        assertThat(view.totalNodes()).isEqualTo(HostMapGraph.MAX_NODES + 6);
        assertThat(view.nodes()).hasSize(HostMapGraph.MAX_NODES);
        assertThat(view.nodes()).extracting(HostMapGraph.Node::label).contains("lzi-prod");
    }

    @Test
    @DisplayName("une carte sans index rend un plan vide, non indexé")
    void empty() {
        HostMapGraph.View view = graph.graph(user, host, TODAY);
        assertThat(view.indexed()).isFalse();
        assertThat(view.nodes()).isEmpty();
        assertThat(graph.graph(null, host, TODAY).indexed()).isFalse();
    }
}
