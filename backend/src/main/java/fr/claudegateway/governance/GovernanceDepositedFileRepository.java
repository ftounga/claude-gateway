package fr.claudegateway.governance;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Les empreintes de ce qui a été déposé (F-96 / SF-96-01).
 *
 * <p><b>Aucune méthode ne lit sans {@code userId}</b>, y compris celles qui portent déjà un
 * {@code hostId} : un identifiant de poste ne prouve rien tant qu'on n'a pas dit à qui il
 * appartient.</p>
 *
 * <p>Les empreintes d'un poste se lisent <b>en bloc</b>, une fois par dépôt : un dépôt traite N
 * fichiers sur M dossiers, et une requête par case ferait payer un aller-retour SQL par ligne
 * d'annonce.</p>
 */
public interface GovernanceDepositedFileRepository
        extends JpaRepository<GovernanceDepositedFile, UUID> {

    /** Toutes les empreintes qu'un paquet a laissées sur un poste — une lecture pour tout le dépôt. */
    List<GovernanceDepositedFile> findByUserIdAndHostIdAndPackageId(UUID userId, UUID hostId,
            UUID packageId);

    /** Nettoyage à la suppression d'un poste : une empreinte sans machine ne dit plus rien. */
    void deleteByUserIdAndHostId(UUID userId, UUID hostId);
}
