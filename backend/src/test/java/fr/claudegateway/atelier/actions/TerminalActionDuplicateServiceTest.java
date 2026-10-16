package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * Les doublons proposés à la fusion (F-180 / SF-180-01) : même clé, mêmes mots, même sens ; la plus
 * ancienne gardée ; la fusion transfère la demande ; « pas pareil » ne repropose plus ; rien hors portée.
 */
class TerminalActionDuplicateServiceTest {

    private final TerminalActionRepository repository = mock(TerminalActionRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final List<TerminalAction> open = new ArrayList<>();

    private TerminalActionDuplicateService service;

    @BeforeEach
    void setUp() {
        service = new TerminalActionDuplicateService(repository, workspaces, clock, 0.30);
        Workspace terminal = new Workspace();
        terminal.setId(workspaceId);
        terminal.setHostId(hostId);
        terminal.setName("data-platform");
        when(workspaces.findByIdAndUserId(workspaceId, userId)).thenReturn(Optional.of(terminal));
        when(repository.findByUserIdAndHostIdAndStatusIn(eq(userId), eq(hostId), any())).thenReturn(open);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private TerminalAction action(String description, String key, int ageDays) {
        OffsetDateTime at = OffsetDateTime.now(clock).minusDays(ageDays);
        TerminalAction a = TerminalAction.builder().id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .hostId(hostId).description(description).dedupKey(key).kind(TerminalActionKind.ACTION)
                .status(TerminalActionStatus.A_FAIRE).createdAt(at).updatedAt(at).build();
        open.add(a);
        when(repository.findByIdAndUserId(a.getId(), userId)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    @DisplayName("même clé : proposée, la plus ancienne est gardée")
    void sameKey() {
        TerminalAction older = action("Demander le compte forge", "acces-forge", 5);
        TerminalAction newer = action("Compte sur la forge CAPFM", "acces-forge", 1);

        var pairs = service.duplicates(userId, workspaceId);

        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).keep().id()).isEqualTo(older.getId());
        assertThat(pairs.get(0).duplicate().id()).isEqualTo(newer.getId());
        assertThat(pairs.get(0).reason()).isEqualTo(TerminalActionDuplicateService.Reason.KEY);
    }

    @Test
    @DisplayName("mêmes mots significatifs : proposée ; mots différents : rien")
    void sameWords() {
        action("Demander l'accès VPN Karim", "k1", 3);
        action("Obtenir accès VPN pour Karim", "k2", 1);
        action("Valider le budget du trimestre", "k3", 1);

        var pairs = service.duplicates(userId, workspaceId);

        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).reason()).isEqualTo(TerminalActionDuplicateService.Reason.WORDS);
    }

    @Test
    @DisplayName("Jaccard : les mots vides et courts ne comptent pas")
    void wordsIgnoreStopWords() {
        assertThat(TerminalActionDuplicateService.words("Demander l'accès à la forge pour CAPFM"))
                .containsExactly("acces", "forge", "capfm");
        assertThat(TerminalActionDuplicateService.jaccard(
                TerminalActionDuplicateService.words("accès forge"),
                TerminalActionDuplicateService.words("accès proxy"))).isLessThan(0.6);
    }

    @Test
    @DisplayName("même sens (sémantique actif) : proposée en MEANING")
    void sameMeaning() {
        TerminalAction older = action("Compte forge CAPFM", "k1", 4);
        TerminalAction newer = action("Jeton d'API de la plateforme", "k2", 1);
        TerminalActionSemanticDedup semantic = mock(TerminalActionSemanticDedup.class);
        when(semantic.closePairs(eq(userId), eq(hostId), eq(workspaceId), anyDouble(), anyInt()))
                .thenReturn(List.of(new TerminalActionEmbeddingStore.Pair(older.getId(), newer.getId(), 0.18)));
        service.setSemantic(semantic);

        var pairs = service.duplicates(userId, workspaceId);

        assertThat(pairs).singleElement().satisfies(p ->
                assertThat(p.reason()).isEqualTo(TerminalActionDuplicateService.Reason.MEANING));
    }

    @Test
    @DisplayName("« ce n'est pas pareil » : l'attente n'est plus proposée")
    void dismissedIsNotProposedAgain() {
        action("Demander le compte forge", "acces-forge", 5);
        TerminalAction newer = action("Compte forge", "acces-forge", 1);

        service.dismiss(userId, workspaceId, newer.getId());

        assertThat(newer.getDuplicateDismissedAt()).isNotNull();
        assertThat(service.duplicates(userId, workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("fusion : la doublonne est annulée avec sa raison, la gardée hérite de la demande")
    void mergeTransfersRequest() {
        TerminalAction keep = action("Demander le compte forge", "acces-forge", 5);
        TerminalAction dup = action("Compte forge", "acces-forge", 1);
        dup.setStatus(TerminalActionStatus.DEMANDE);
        dup.setRequestedAt(OffsetDateTime.now(clock).minusDays(1));
        dup.setRequestedTo("Rémi");
        dup.setChannel("Teams");
        dup.setBlocks("le déploiement");

        TerminalAction merged = service.merge(userId, workspaceId, dup.getId(), keep.getId());

        assertThat(merged.getStatus()).isEqualTo(TerminalActionStatus.ANNULE);
        assertThat(merged.getMergedIntoId()).isEqualTo(keep.getId());
        assertThat(merged.getClosedReason()).startsWith("Fusionnée avec « Demander le compte forge");
        assertThat(keep.getStatus()).isEqualTo(TerminalActionStatus.DEMANDE);
        assertThat(keep.getRequestedTo()).isEqualTo("Rémi");
        assertThat(keep.getChannel()).isEqualTo("Teams");
        assertThat(keep.getBlocks()).isEqualTo("le déploiement");
    }

    @Test
    @DisplayName("fusion refusée : soi-même, attente fermée, hors portée")
    void mergeRefusals() {
        TerminalAction keep = action("Demander le compte forge", "acces-forge", 5);
        TerminalAction dup = action("Compte forge", "acces-forge", 1);

        assertThatThrownBy(() -> service.merge(userId, workspaceId, dup.getId(), dup.getId()))
                .isInstanceOf(InvalidTerminalActionException.class);

        keep.setStatus(TerminalActionStatus.FAIT);
        assertThatThrownBy(() -> service.merge(userId, workspaceId, dup.getId(), keep.getId()))
                .isInstanceOf(InvalidTerminalActionException.class);

        TerminalAction foreign = TerminalAction.builder().id(UUID.randomUUID()).userId(userId)
                .workspaceId(UUID.randomUUID()).hostId(UUID.randomUUID()).description("x")
                .status(TerminalActionStatus.A_FAIRE).build();
        when(repository.findByIdAndUserId(foreign.getId(), userId)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.merge(userId, workspaceId, dup.getId(), foreign.getId()))
                .isInstanceOf(TerminalActionNotFoundException.class);
    }

    @Test
    @DisplayName("terminal d'un autre compte : introuvable")
    void foreignTerminal() {
        assertThatThrownBy(() -> service.duplicates(UUID.randomUUID(), workspaceId))
                .isInstanceOf(TerminalActionNotFoundException.class);
    }
}
