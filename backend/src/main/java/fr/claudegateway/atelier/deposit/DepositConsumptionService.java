package fr.claudegateway.atelier.deposit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Porte les fichiers déposés dans la <b>consigne du tour</b> (F-115 / SF-115-03) : au début d'un tour,
 * la liste des fichiers déposés <b>depuis le dernier tour</b> (chemins seulement) est ajoutée à la
 * consigne, et l'agent les lit ensuite avec {@code read_file}. Comme Claude Code, on ne réinjecte
 * <b>jamais</b> le binaire — seulement le chemin.
 *
 * <p>Les dépôts sont marqués <b>consommés</b> à la lecture : un tour suivant ne les reverra pas. Un
 * dépôt dont l'écriture a échoué (poste hors ligne, dossier non inscriptible, taille dépassée) n'a
 * jamais créé de ligne (SF-115-01) : il ne pollue donc pas la consigne, et son échec a été nommé à
 * l'utilisateur au moment du dépôt.</p>
 */
@Service
public class DepositConsumptionService {

    private final AtelierDepositedFileRepository repository;

    public DepositConsumptionService(AtelierDepositedFileRepository repository) {
        this.repository = repository;
    }

    /**
     * Consomme les dépôts non lus d'un terminal et rend la <b>note de consigne</b> à ajouter au tour,
     * ou {@code null} s'il n'y en a aucun. Isolation stricte : filtre {@code (user_id, workspace_id)}.
     *
     * @return la note (chemins seulement), ou {@code null}
     */
    @Transactional
    public String consumeForTurn(UUID userId, UUID workspaceId) {
        List<AtelierDepositedFile> pending = repository
                .findByUserIdAndWorkspaceIdAndConsumedAtIsNullOrderByCreatedAtAsc(userId, workspaceId);
        if (pending.isEmpty()) {
            return null;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (AtelierDepositedFile file : pending) {
            file.setConsumedAt(now);
        }
        repository.saveAll(pending);

        String list = pending.stream()
                .map(file -> "- " + file.getPath())
                .collect(Collectors.joining("\n"));
        return "Fichiers déposés dans ce terminal depuis le dernier tour "
                + "(lis-les avec read_file si tu en as besoin ; le contenu n'est pas inclus ici) :\n"
                + list;
    }
}
