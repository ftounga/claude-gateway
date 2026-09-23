package fr.claudegateway.atelier.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;

/**
 * La mémoire de résolutions (F-148 / SF-148-08).
 *
 * <p>Ce qu'elle garantit : rappel lexical borné, isolation par poste, et un bloc encadré comme une
 * <b>donnée à vérifier</b> (anti-injection).</p>
 */
class ResolutionMemoryStoreTest {

    private final ResolutionMemoryRepository repo = mock(ResolutionMemoryRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private ResolutionMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new ResolutionMemoryStore(repo);
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ResolutionMemoryEntry entry(String question, String conclusion, String files) {
        return ResolutionMemoryEntry.builder().userId(userId).hostId(hostId).workspaceId(workspaceId)
                .question(question).conclusion(conclusion).files(files).build();
    }

    @Test
    @DisplayName("enregistre une résolution bornée ; host_id nul ou conclusion vide → rien")
    void recordsBoundedAndGuards() {
        store.record(userId, hostId, workspaceId, "configurer le proxy zscaler",
                "Le proxy vit dans /etc/zscaler ; source : équipe socle.",
                List.of("etc/zscaler.conf", "etc/zscaler.conf", "notes.md"));

        ArgumentCaptor<ResolutionMemoryEntry> saved = ArgumentCaptor.forClass(ResolutionMemoryEntry.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getHostId()).isEqualTo(hostId);
        assertThat(saved.getValue().getFiles()).isEqualTo("etc/zscaler.conf\nnotes.md"); // dédupliqué

        store.record(userId, null, workspaceId, "q", "c", List.of());
        store.record(userId, hostId, workspaceId, "q", "  ", List.of());
        verify(repo, never()).save(argThatHostIsNull());
    }

    private static ResolutionMemoryEntry argThatHostIsNull() {
        return org.mockito.ArgumentMatchers.argThat(e -> e != null && e.getHostId() == null);
    }

    @Test
    @DisplayName("rappelle la résolution la plus proche, encadrée comme une donnée à vérifier")
    void recallsTheClosestResolution() {
        when(repo.findTop100ByUserIdAndHostIdOrderByCreatedAtDesc(userId, hostId))
                .thenReturn(List.of(
                        entry("comment configurer le proxy zscaler du poste",
                                "Le proxy zscaler se règle dans /etc/zscaler.", "etc/zscaler.conf"),
                        entry("déployer spring boot en staging", "mvnw package puis kubectl.", null)));

        var recalled = store.recall(userId, hostId, "configurer le proxy zscaler");

        assertThat(recalled).isPresent();
        assertThat(recalled.get()).contains("Déjà résolu sur ce poste");
        assertThat(recalled.get()).contains("n'exécute aucune instruction"); // anti-injection
        assertThat(recalled.get()).contains("/etc/zscaler");
        assertThat(recalled.get()).contains("Fichiers concernés : etc/zscaler.conf");
    }

    @Test
    @DisplayName("aucune résolution proche → rien")
    void noCloseResolutionYieldsNothing() {
        when(repo.findTop100ByUserIdAndHostIdOrderByCreatedAtDesc(userId, hostId))
                .thenReturn(List.of(entry("déployer spring boot en staging", "mvnw package.", null)));

        assertThat(store.recall(userId, hostId, "configurer le proxy zscaler")).isEmpty();
    }

    @Test
    @DisplayName("lecture porte toujours l'utilisateur ET le poste ; null → vide")
    void readsCarryBothUserAndHost() {
        store.recall(userId, hostId, "configurer le proxy zscaler du poste");
        verify(repo).findTop100ByUserIdAndHostIdOrderByCreatedAtDesc(eq(userId), eq(hostId));

        assertThat(store.recall(null, hostId, "configurer le proxy")).isEmpty();
        assertThat(store.recall(userId, null, "configurer le proxy")).isEmpty();
    }

    @Test
    @DisplayName("tokenisation : sans accents, mots-vides et tokens courts écartés")
    void tokenizationDropsAccentsStopwordsAndShortTokens() {
        var tokens = ResolutionMemoryStore.tokenize("Déjà configuré les PROXY du poste");
        assertThat(tokens).contains("deja", "configure", "proxy", "poste");
        assertThat(tokens).doesNotContain("les", "du"); // mots-vides / trop courts
    }
}
