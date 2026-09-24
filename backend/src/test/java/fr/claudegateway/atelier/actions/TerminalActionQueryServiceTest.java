package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * La section « Ailleurs » du menu (F-154 / SF-154-03) : les actions ouvertes des AUTRES projets du
 * compte, avec le nom de leur projet — et jamais celles d'un autre compte.
 */
class TerminalActionQueryServiceTest {

    private final TerminalActionRepository repository = mock(TerminalActionRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID current = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private TerminalActionQueryService service;

    @BeforeEach
    void setUp() {
        service = new TerminalActionQueryService(repository, workspaces);
        Workspace agenor = new Workspace();
        agenor.setName("AGENOR");
        when(workspaces.findByIdAndUserId(other, userId)).thenReturn(Optional.of(agenor));
    }

    private TerminalAction at(UUID workspaceId, String description) {
        return TerminalAction.builder()
                .id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .description(description).kind(TerminalActionKind.ACTION)
                .status(TerminalActionStatus.OPEN)
                .createdAt(OffsetDateTime.parse("2026-09-20T08:00:00Z"))
                .updatedAt(OffsetDateTime.parse("2026-09-20T08:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("retire le terminal courant — il est déjà listé au-dessus — et nomme les autres")
    void excludesTheCurrentTerminalAndNamesTheOthers() {
        when(repository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN))
                .thenReturn(List.of(at(current, "ici"), at(other, "Valider le RSSI")));

        List<TerminalActionElsewhereResponse> elsewhere =
                service.openElsewhere(userId, current.toString());

        assertThat(elsewhere).hasSize(1);
        assertThat(elsewhere.get(0).description()).isEqualTo("Valider le RSSI");
        assertThat(elsewhere.get(0).workspaceName()).isEqualTo("AGENOR");
    }

    @Test
    @DisplayName("ne relit le nom d'un projet qu'une fois, même pour dix actions")
    void readsEachProjectNameOnce() {
        when(repository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN))
                .thenReturn(List.of(at(other, "une"), at(other, "deux"), at(other, "trois")));

        assertThat(service.openElsewhere(userId, current.toString())).hasSize(3);
        verify(workspaces, times(1)).findByIdAndUserId(other, userId);
    }

    @Test
    @DisplayName("ISOLATION — le nom est relu SOUS l'isolation ; un projet d'ailleurs reste sans nom")
    void theNameIsReadUnderIsolation() {
        UUID foreign = UUID.randomUUID();
        when(workspaces.findByIdAndUserId(foreign, userId)).thenReturn(Optional.empty());
        when(repository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN))
                .thenReturn(List.of(at(foreign, "orpheline")));

        assertThat(service.openElsewhere(userId, current.toString()))
                .singleElement()
                .extracting(TerminalActionElsewhereResponse::workspaceName)
                .isEqualTo("Projet supprimé");
        verify(workspaces).findByIdAndUserId(foreign, userId); // jamais par identifiant seul
    }

    @Test
    @DisplayName("un paramètre d'exclusion illisible ne fait pas 500 : il est ignoré")
    void anUnreadableExcludeIsIgnored() {
        when(repository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN))
                .thenReturn(List.of(at(other, "Valider le RSSI")));

        assertThat(service.openElsewhere(userId, "pas-un-uuid")).hasSize(1);
        assertThat(service.openElsewhere(userId, null)).hasSize(1);
    }

    @Test
    @DisplayName("au-delà de la borne, on coupe — « ailleurs » n'est pas un inventaire")
    void cutsBeyondTheBound() {
        List<TerminalAction> many = java.util.stream.IntStream
                .range(0, TerminalActionQueryService.MAX_ELSEWHERE + 20)
                .mapToObj(i -> at(other, "action " + i))
                .toList();
        when(repository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN))
                .thenReturn(many);

        assertThat(service.openElsewhere(userId, current.toString()))
                .hasSize(TerminalActionQueryService.MAX_ELSEWHERE);
    }
}
