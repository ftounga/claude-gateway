package fr.claudegateway.governance.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;
import fr.claudegateway.governance.map.index.HostMapFact;
import fr.claudegateway.governance.map.index.HostMapIndexProperties;
import fr.claudegateway.governance.map.index.HostMapLookupJournal;
import fr.claudegateway.governance.map.index.HostMapSearch;

/** Le choix hybride / repli F-137 (F-174 / SF-174-03). */
class HostMapKnowledgeProviderHybridTest {

    private final HostMapStore store = mock(HostMapStore.class);
    private final GovernanceHostScope scope = mock(GovernanceHostScope.class);
    private final HostMapLookupJournal journal = mock(HostMapLookupJournal.class);
    private final HostMapSearch search = mock(HostMapSearch.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final String question = "et bastion-lzi.cagip.fr ?";

    private HostMapKnowledgeProvider provider(boolean enabled) {
        return new HostMapKnowledgeProvider(store, scope, Runnable::run, Clock.systemUTC(), 120, journal,
                search, new HostMapIndexProperties(enabled, null, null, null, null, null, null, null, null,
                        null, null, null));
    }

    @BeforeEach
    void setUp() {
        when(scope.hostOf(userId, workspaceId)).thenReturn(GovernanceHostRef.of(hostId));
        when(store.filesOf(userId, hostId)).thenReturn(List.of(HostMapFile.builder().userId(userId)
                .hostId(hostId).path("acces.md").content("## Bastions\n- bastion-lzi.cagip.fr ouvert\n").build()));
    }

    private static HostMapSearch.Result found() {
        return new HostMapSearch.Result(List.of(new HostMapSearch.Hit(HostMapFact.builder().id(UUID.randomUUID())
                .path("acces.md").heading("Bastions").lineNo(2).text("- bastion-lzi.cagip.fr ouvert").kind("FAIT")
                .build(), HostMapSearch.Reason.IDENTIFIER)), List.of());
    }

    @Test
    @DisplayName("l'index nourri répond : bloc hybride, cité fichier § section, journal HYBRID")
    void hybridWhenIndexed() {
        when(search.hasIndex(userId, hostId)).thenReturn(true);
        when(search.search(eq(userId), eq(hostId), anyString(), eq(20), any())).thenReturn(found());

        String block = provider(true).factsFor(userId, workspaceId, question);

        assertThat(block).contains("- bastion-lzi.cagip.fr ouvert  [acces.md § Bastions]");
        verify(journal).record(eq(userId), eq(hostId), eq(workspaceId), eq("TURN"), eq("HYBRID"), eq(block));
    }

    @Test
    @DisplayName("index éteint, vide, muet ou en panne : le bloc F-137 d'avant, à l'identique")
    void fallsBackToLexical() {
        String lexical = HostFactLookup.factsFor(store.filesOf(userId, hostId), question,
                java.time.LocalDate.now(), 120);

        assertThat(provider(false).factsFor(userId, workspaceId, question)).isEqualTo(lexical);
        verify(search, never()).search(any(), any(), anyString(), anyInt(), any());

        when(search.hasIndex(userId, hostId)).thenReturn(false);
        assertThat(provider(true).factsFor(userId, workspaceId, question)).isEqualTo(lexical);

        when(search.hasIndex(userId, hostId)).thenReturn(true);
        when(search.search(any(), any(), anyString(), anyInt(), any()))
                .thenReturn(new HostMapSearch.Result(List.of(), List.of()));
        assertThat(provider(true).factsFor(userId, workspaceId, question)).isEqualTo(lexical);

        when(search.search(any(), any(), anyString(), anyInt(), any())).thenThrow(new IllegalStateException("db"));
        assertThat(provider(true).factsFor(userId, workspaceId, question)).isEqualTo(lexical);
    }

    @Test
    @DisplayName("« Hébergé » n'a pas de carte : rien n'est joint ni journalisé")
    void hostedHasNoMap() {
        when(scope.hostOf(userId, workspaceId)).thenReturn(GovernanceHostRef.HOSTED);
        assertThat(provider(true).factsFor(userId, workspaceId, question)).isNull();
        verify(journal, never()).record(any(), any(), any(), anyString(), anyString(), any());
    }
}
