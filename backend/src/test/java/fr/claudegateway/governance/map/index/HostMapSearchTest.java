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

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.governance.map.index.HostMapFactEmbeddingStore.ScoredFact;
import fr.claudegateway.governance.map.index.HostMapSearch.Reason;

/** La recherche hybride (F-174 / SF-174-03, D5) : l'ordre des maillons, les seuils, l'isolation. */
class HostMapSearchTest {

    private final HostMapFactRepository facts = mock(HostMapFactRepository.class);
    private final HostMapEntityRepository entities = mock(HostMapEntityRepository.class);
    private final HostMapSemantic semantic = mock(HostMapSemantic.class);
    private final HostMapIndexProperties properties =
            new HostMapIndexProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    private final HostMapSearch search = new HostMapSearch(facts, entities, semantic, properties);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private final HostMapFact account = fact("- compte 123456789012 prod");
    private final HostMapFact cluster = fact("- eks-prod-1 tourne dans le compte prod");
    private final HostMapFact proxy = fact("- le proxy coupe les websockets");
    private final HostMapFact lexical = fact("- Netskope inspecte le TLS");

    private HostMapFact fact(String text) {
        return HostMapFact.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId)
                .path("plateformes.md").heading("Comptes").lineNo(1).text(text).kind("FAIT").build();
    }

    @BeforeEach
    void setUp() {
        when(facts.findByIdentifierPattern(any(), any(), anyString())).thenReturn(List.of());
        when(facts.findByTextPattern(any(), any(), anyString())).thenReturn(List.of());
        when(entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(userId, hostId)).thenReturn(List.of());
    }

    @Test
    @DisplayName("identifiant exact d'abord, puis entité nommée, puis sémantique, puis lexical")
    void linksInOrder() {
        when(facts.findByIdentifierPattern(userId, hostId, HostMapLikes.exactIdentifier("123456789012")))
                .thenReturn(List.of(account));
        when(entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(userId, hostId)).thenReturn(List.of(
                HostMapEntity.builder().labelNorm("eks-prod-1").identifiers("\neks-prod-1\n").kind("cluster").build()));
        when(facts.findByTextPattern(userId, hostId, HostMapLikes.contains("eks-prod-1")))
                .thenReturn(List.of(cluster, account));
        when(semantic.isEnabled()).thenReturn(true);
        when(semantic.nearest(eq(userId), eq(hostId), anyString(), anyInt()))
                .thenReturn(List.of(new ScoredFact(proxy.getId(), 0.30), new ScoredFact(lexical.getId(), 0.90)));
        when(facts.findByUserIdAndHostIdAndIdIn(eq(userId), eq(hostId), any())).thenReturn(List.of(proxy));
        when(facts.findByTextPattern(userId, hostId, HostMapLikes.contains("netskope")))
                .thenReturn(List.of(lexical));

        HostMapSearch.Result result = search.search(userId, hostId,
                "le compte 123456789012 et eks-prod-1 passent-ils par NetSkope ?", 20);

        assertThat(result.hits()).extracting(HostMapSearch.Hit::reason)
                .containsExactly(Reason.IDENTIFIER, Reason.ENTITY, Reason.SEMANTIC, Reason.LEXICAL);
        assertThat(result.hits()).extracting(h -> h.fact().getText())
                .containsExactly(account.getText(), cluster.getText(), proxy.getText(), lexical.getText());
        assertThat(result.touched()).extracting(HostMapEntity::getLabelNorm).containsExactly("eks-prod-1");
    }

    @Test
    @DisplayName("au-delà du seuil sémantique, rien n'est joint ; la borne de faits est tenue")
    void thresholdAndLimit() {
        when(semantic.isEnabled()).thenReturn(true);
        when(semantic.nearest(eq(userId), eq(hostId), anyString(), anyInt()))
                .thenReturn(List.of(new ScoredFact(proxy.getId(), 0.95)));
        assertThat(search.search(userId, hostId, "une question vague", 20).hits()).isEmpty();
        verify(facts, never()).findByUserIdAndHostIdAndIdIn(any(), any(), any());

        when(facts.findByIdentifierPattern(userId, hostId, HostMapLikes.exactIdentifier("123456789012")))
                .thenReturn(List.of(account, cluster, proxy));
        assertThat(search.search(userId, hostId, "compte 123456789012", 2).hits()).hasSize(2);
    }

    @Test
    @DisplayName("un nom trop fréquent décrit la carte, pas la question : ses faits ne remontent pas")
    void tooFrequentLabelIsIgnored() {
        when(entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(userId, hostId)).thenReturn(List.of(
                HostMapEntity.builder().labelNorm("cagip").kind("equipe").build()));
        HostMapFact[] many = new HostMapFact[9];
        for (int i = 0; i < many.length; i++) {
            many[i] = fact("- cagip " + i);
        }
        when(facts.findByTextPattern(userId, hostId, HostMapLikes.contains("cagip"))).thenReturn(List.of(many));
        assertThat(search.search(userId, hostId, "chez cagip ?", 20).hits()).isEmpty();
    }

    @Test
    @DisplayName("un nom n'est reconnu que comme mot entier")
    void wholeWordsOnly() {
        assertThat(HostMapSearch.containsWord("le cluster eks-prod-1 tombe", "eks-prod-1")).isTrue();
        assertThat(HostMapSearch.containsWord("le cluster eks-prod-12 tombe", "eks-prod-1")).isFalse();
        assertThat(HostMapSearch.containsWord("prodution", "prod")).isFalse();
    }

    @Test
    @DisplayName("sans propriétaire ou sans question, aucune lecture")
    void noOwnerNoRead() {
        assertThat(search.search(null, hostId, "compte 123456789012", 20).isEmpty()).isTrue();
        assertThat(search.search(userId, hostId, " ", 20).isEmpty()).isTrue();
        verify(facts, never()).findByIdentifierPattern(any(), any(), anyString());
    }
}
