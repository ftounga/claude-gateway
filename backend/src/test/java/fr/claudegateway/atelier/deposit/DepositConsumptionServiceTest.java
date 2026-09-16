package fr.claudegateway.atelier.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
}
