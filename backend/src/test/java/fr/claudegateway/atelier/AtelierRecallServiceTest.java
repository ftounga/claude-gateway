package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import fr.claudegateway.atelier.dto.ThreadRecallResponse;
import fr.claudegateway.atelier.recall.AtelierSemanticRecall;

/**
 * Le rappel à la demande (F-165 / SF-165-06) : recherche sémantique puis repli mot-clé, extraits bornés,
 * et — surtout — l'<b>isolation</b> : un fil d'autrui ne rend jamais d'extrait (404 propagé, aucune
 * recherche). Réutilise les briques de recherche de F-162, jamais un tour modèle.
 */
@ExtendWith(MockitoExtension.class)
class AtelierRecallServiceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private AtelierSemanticRecall semanticRecall;

    private AtelierRecallService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AtelierRecallService(workspaceService, messageRepository, semanticRecall);
    }

    private Workspace ownedWorkspace() {
        Workspace workspace = mock(Workspace.class);
        lenient().when(workspace.getId()).thenReturn(workspaceId);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private AtelierMessage message(String role, String content) {
        AtelierMessage message = mock(AtelierMessage.class);
        lenient().when(message.getRole()).thenReturn(role);
        lenient().when(message.getContent()).thenReturn(content);
        lenient().when(message.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-09-30T10:00:00Z"));
        return message;
    }

    @Test
    void repliMotCleQuandLeSemantiqueEstEteint() {
        ownedWorkspace();
        AtelierMessage match = message("user", "  Comment configurer le  VPC ?  ");
        when(semanticRecall.isEnabled()).thenReturn(false);
        when(messageRepository.searchByContent(eq(workspaceId), eq(userId), any(String.class),
                any(Pageable.class)))
                .thenReturn(List.of(match));

        ThreadRecallResponse response = service.search(userId, workspaceId, "  VPC  ");

        assertThat(response.query()).isEqualTo("VPC");
        assertThat(response.semantic()).isFalse();
        assertThat(response.extracts()).hasSize(1);
        // Espaces normalisés dans l'extrait.
        assertThat(response.extracts().get(0).excerpt()).isEqualTo("Comment configurer le VPC ?");
        assertThat(response.extracts().get(0).role()).isEqualTo("user");
        // Isolation par construction : la recherche est bornée à (workspaceId, userId).
        verify(messageRepository).searchByContent(eq(workspaceId), eq(userId), any(String.class),
                any(Pageable.class));
    }

    @Test
    void semantiqueActifRendLesExtraitsReordonnes() {
        ownedWorkspace();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(semanticRecall.isEnabled()).thenReturn(true);
        when(semanticRecall.search(userId, workspaceId, "adressage réseau", 5))
                .thenReturn(List.of(a, b));
        AtelierMessage ma = message("assistant", "Le VPC CIDR est 10.0.0.0/16");
        AtelierMessage mb = message("user", "et le sous-réseau ?");
        when(ma.getId()).thenReturn(a);
        when(mb.getId()).thenReturn(b);
        when(messageRepository.findByWorkspaceIdAndUserIdAndIdIn(workspaceId, userId, List.of(a, b)))
                .thenReturn(List.of(mb, ma)); // ordre base ≠ ordre similarité

        ThreadRecallResponse response = service.search(userId, workspaceId, "adressage réseau");

        assertThat(response.semantic()).isTrue();
        // Réordonné selon l'ordre de similarité (a puis b), pas l'ordre du dépôt.
        assertThat(response.extracts()).extracting(ThreadRecallResponse.Extract::role)
                .containsExactly("assistant", "user");
        // Pas de repli mot-clé quand le sémantique a répondu.
        verify(messageRepository, never()).searchByContent(any(), any(), any(), any());
    }

    @Test
    void termeVideRendVideSansRecherche() {
        ownedWorkspace();

        ThreadRecallResponse response = service.search(userId, workspaceId, "   ");

        assertThat(response.extracts()).isEmpty();
        verify(messageRepository, never()).searchByContent(any(), any(), any(), any());
    }

    @Test
    void unFilDAutruiNeRendJamaisDExtrait() {
        when(workspaceService.requireOwned(userId, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));

        assertThatThrownBy(() -> service.search(userId, workspaceId, "VPC"))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verify(messageRepository, never()).searchByContent(any(), any(), any(), any());
        verify(semanticRecall, never()).search(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
