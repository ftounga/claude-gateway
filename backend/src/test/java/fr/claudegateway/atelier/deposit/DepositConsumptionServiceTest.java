package fr.claudegateway.atelier.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Consigne des fichiers déposés (F-115 / SF-115-03) : chemins seulement, marquage consommé, rien
 * quand il n'y a aucun dépôt.
 */
@ExtendWith(MockitoExtension.class)
class DepositConsumptionServiceTest {

    @Mock
    private AtelierDepositedFileRepository repository;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private DepositConsumptionService service() {
        return new DepositConsumptionService(repository);
    }

    private AtelierDepositedFile deposit(String path) {
        return AtelierDepositedFile.builder()
                .id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .path(path).sizeBytes(10L).build();
    }

    private AtelierDepositedFile deposit(UUID id, String path) {
        return AtelierDepositedFile.builder()
                .id(id).userId(userId).workspaceId(workspaceId)
                .path(path).sizeBytes(10L).build();
    }

    @Test
    void porteLesCheminsDeposesDansLaConsigneEtLesMarqueConsommes() {
        when(repository.findByUserIdAndWorkspaceIdAndConsumedAtIsNullOrderByCreatedAtAsc(userId, workspaceId))
                .thenReturn(List.of(deposit("entrees/capture.png"), deposit(".atelier/entrees/log.txt")));

        String note = service().consumeForTurn(userId, workspaceId);

        assertThat(note).contains("entrees/capture.png");
        assertThat(note).contains(".atelier/entrees/log.txt");
        assertThat(note).contains("read_file");
        // Marqués consommés (consumedAt renseigné) puis sauvegardés.
        ArgumentCaptor<List<AtelierDepositedFile>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).allSatisfy(file -> assertThat(file.getConsumedAt()).isNotNull());
    }

    @Test
    void rendNullEtNeSauvegardeRienSansDepot() {
        when(repository.findByUserIdAndWorkspaceIdAndConsumedAtIsNullOrderByCreatedAtAsc(userId, workspaceId))
                .thenReturn(List.of());

        assertThat(service().consumeForTurn(userId, workspaceId)).isNull();
        verify(repository, never()).saveAll(any());
    }

    // ----------------------------------------------- F-169 / SF-169-02 : par message

    @Test
    void consumeForMessageAttacheExactementLesDepotsDesignesEtLesMarqueConsommes() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(repository.findByUserIdAndWorkspaceIdAndIdInAndConsumedAtIsNullOrderByCreatedAtAsc(
                userId, workspaceId, List.of(id1, id2)))
                .thenReturn(List.of(deposit(id1, "entrees/capture.png"), deposit(id2, "entrees/notes.txt")));

        DepositConsumptionService.MessageDeposits result =
                service().consumeForMessage(userId, workspaceId, List.of(id1, id2));

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.note()).contains("entrees/capture.png").contains("entrees/notes.txt")
                .contains("read_file").contains("le contenu n'est pas inclus");
        assertThat(result.depositIds()).containsExactly(id1, id2);
        assertThat(result.files()).extracting(AtelierAttachedFile::path)
                .containsExactly("entrees/capture.png", "entrees/notes.txt");
        // Marqués consommés puis sauvegardés.
        ArgumentCaptor<List<AtelierDepositedFile>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).allSatisfy(f -> assertThat(f.getConsumedAt()).isNotNull());
    }

    @Test
    void consumeForMessageNAttacheRienQuandAucunDepotDesigneNEstValide() {
        // Isolation : un id d'autrui / d'un autre workspace / déjà consommé n'est jamais remonté par
        // la requête filtrée → aucun dépôt attaché, aucune sauvegarde.
        UUID foreign = UUID.randomUUID();
        when(repository.findByUserIdAndWorkspaceIdAndIdInAndConsumedAtIsNullOrderByCreatedAtAsc(
                userId, workspaceId, List.of(foreign)))
                .thenReturn(List.of());

        DepositConsumptionService.MessageDeposits result =
                service().consumeForMessage(userId, workspaceId, List.of(foreign));

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.note()).isNull();
        verify(repository, never()).saveAll(any());
    }

    @Test
    void consumeForMessageSansIdEstSansEffet() {
        assertThat(service().consumeForMessage(userId, workspaceId, List.of()).isEmpty()).isTrue();
        verify(repository, never())
                .findByUserIdAndWorkspaceIdAndIdInAndConsumedAtIsNullOrderByCreatedAtAsc(any(), any(), any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void linkToMessagePoseLeMessageIdSurLesDepotsPossedes() {
        UUID id1 = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        AtelierDepositedFile file = deposit(id1, "entrees/capture.png");
        when(repository.findAllById(List.of(id1))).thenReturn(List.of(file));

        service().linkToMessage(userId, workspaceId, List.of(id1), messageId);

        assertThat(file.getMessageId()).isEqualTo(messageId);
        verify(repository).saveAll(anyList());
    }

    @Test
    void linkToMessageIgnoreUnDepotDUnAutreUtilisateur() {
        // Défense en profondeur : même retourné par findAllById, un dépôt d'autrui est filtré.
        UUID id1 = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        AtelierDepositedFile foreign = AtelierDepositedFile.builder()
                .id(id1).userId(UUID.randomUUID()).workspaceId(workspaceId)
                .path("entrees/x.png").sizeBytes(1L).build();
        when(repository.findAllById(List.of(id1))).thenReturn(List.of(foreign));

        service().linkToMessage(userId, workspaceId, List.of(id1), messageId);

        assertThat(foreign.getMessageId()).isNull();
        verify(repository, never()).saveAll(any());
    }

    @Test
    void attachedFilesByMessageRegroupeParMessage() {
        UUID messageId = UUID.randomUUID();
        AtelierDepositedFile file = deposit(UUID.randomUUID(), "entrees/capture.png");
        file.setMessageId(messageId);
        when(repository.findByUserIdAndWorkspaceIdAndMessageIdInOrderByCreatedAtAsc(
                userId, workspaceId, List.of(messageId)))
                .thenReturn(List.of(file));

        Map<UUID, List<AtelierAttachedFile>> map =
                service().attachedFilesByMessage(userId, workspaceId, List.of(messageId));

        assertThat(map).containsOnlyKeys(messageId);
        assertThat(map.get(messageId)).extracting(AtelierAttachedFile::path)
                .containsExactly("entrees/capture.png");
    }

    @Test
    void attachedFilesByMessageSansIdRendUneCarteVide() {
        assertThat(service().attachedFilesByMessage(userId, workspaceId, List.of())).isEmpty();
        verify(repository, never())
                .findByUserIdAndWorkspaceIdAndMessageIdInOrderByCreatedAtAsc(any(), any(), any());
    }
}
