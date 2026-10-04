package fr.claudegateway.governance.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;
import fr.claudegateway.governance.map.index.HostMapLookupJournal;

/** Chaque tour d'un poste à carte est compté (F-174 / SF-174-01). */
class HostMapKnowledgeProviderJournalTest {

    private final HostMapStore store = mock(HostMapStore.class);
    private final GovernanceHostScope scope = mock(GovernanceHostScope.class);
    private final HostMapLookupJournal journal = mock(HostMapLookupJournal.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private HostMapKnowledgeProvider provider() {
        return new HostMapKnowledgeProvider(store, scope, Runnable::run, Clock.systemUTC(), 120, journal);
    }

    @Test
    @DisplayName("les faits joints au tour sont journalisés, et rendus inchangés")
    void turnFactsAreJournaled() {
        when(scope.hostOf(userId, workspaceId)).thenReturn(GovernanceHostRef.of(hostId));
        HostMapFile file = HostMapFile.builder().userId(userId).hostId(hostId).path("acces.md")
                .content("## Bastions\n- bastion-lzi.cagip.fr ouvert\n").build();
        when(store.filesOf(userId, hostId)).thenReturn(List.of(file));

        String block = provider().factsFor(userId, workspaceId, "et bastion-lzi.cagip.fr ?");

        assertThat(block).contains("bastion-lzi.cagip.fr ouvert");
        verify(journal).record(eq(userId), eq(hostId), eq(workspaceId), eq("TURN"), eq("LEXICAL"),
                eq(block));
    }

    @Test
    @DisplayName("un projet sans poste n'est pas journalisé")
    void noHostNoJournal() {
        when(scope.hostOf(userId, workspaceId)).thenThrow(new IllegalStateException("pas de poste"));
        assertThat(provider().factsFor(userId, workspaceId, "et bastion-lzi.cagip.fr ?")).isNull();
        verify(journal, never()).record(any(), any(), any(), anyString(), anyString(), any());
    }
}
