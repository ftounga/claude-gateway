package fr.claudegateway.governance.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import fr.claudegateway.governance.map.index.HostMapIndexProperties;
import fr.claudegateway.governance.map.index.HostMapLookupJournal;
import fr.claudegateway.governance.map.index.HostMapSearch;
import fr.claudegateway.governance.map.index.HostMapSearchTool;

/** {@code carte_chercher} côté source de savoir : offert, journalisé, replié (F-174 / SF-174-05). */
class HostMapKnowledgeProviderToolTest {

    private final HostMapStore store = mock(HostMapStore.class);
    private final GovernanceHostScope scope = mock(GovernanceHostScope.class);
    private final HostMapLookupJournal journal = mock(HostMapLookupJournal.class);
    private final HostMapSearchTool tool = mock(HostMapSearchTool.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private HostMapKnowledgeProvider provider(boolean enabled) {
        HostMapKnowledgeProvider provider = new HostMapKnowledgeProvider(store, scope, Runnable::run,
                Clock.systemUTC(), 120, journal, mock(HostMapSearch.class), new HostMapIndexProperties(enabled,
                        null, null, null, null, null, null, null, null, null, null, null));
        provider.setSearchTool(tool);
        return provider;
    }

    @BeforeEach
    void setUp() {
        when(scope.hostOf(userId, workspaceId)).thenReturn(GovernanceHostRef.of(hostId));
    }

    @Test
    @DisplayName("offert sur un poste réel avec l'index allumé ; jamais sur « Hébergé » ni éteint")
    void availability() {
        assertThat(provider(true).mapSearchAvailable(userId, workspaceId)).isTrue();
        assertThat(provider(false).mapSearchAvailable(userId, workspaceId)).isFalse();
        when(scope.hostOf(userId, workspaceId)).thenReturn(GovernanceHostRef.HOSTED);
        assertThat(provider(true).mapSearchAvailable(userId, workspaceId)).isFalse();
    }

    @Test
    @DisplayName("la réponse de l'index est rendue, et seuls ses FAITS sont comptés (journal TOOL / HYBRID)")
    void answersAndJournals() {
        String answer = "Carte de ce poste — « x » : 1 fait(s), 1 ressource(s).\n\nRessources :\n- a (cluster)  [p.md]\n"
                + "\nFaits :\n- fait  [p.md § S]\n" + HostMapSearchTool.class.getSimpleName();
        when(tool.run(eq(userId), eq(hostId), eq("x"), any(), any(), any())).thenReturn(answer);

        assertThat(provider(true).searchMap(userId, workspaceId, "x", null, null)).isEqualTo(answer);
        verify(journal).record(eq(userId), eq(hostId), eq(workspaceId), eq("TOOL"), eq("HYBRID"),
                eq(answer.substring(answer.indexOf("\nFaits :\n"))));
    }

    @Test
    @DisplayName("index pas encore construit : la recherche lexicale dans les fichiers (journal LEXICAL)")
    void fallsBackToFiles() {
        when(tool.run(any(), any(), anyString(), any(), any(), any())).thenReturn(null);
        when(store.filesOf(userId, hostId)).thenReturn(List.of(HostMapFile.builder().path("acces.md")
                .content("## Bastions\n- bastion-lzi.cagip.fr ouvert\n").build()));

        String answer = provider(true).searchMap(userId, workspaceId, "bastion-lzi.cagip.fr", null, null);

        assertThat(answer).contains("bastion-lzi.cagip.fr ouvert");
        verify(journal).record(eq(userId), eq(hostId), eq(workspaceId), eq("TOOL"), eq("LEXICAL"), eq(answer));
    }
}
