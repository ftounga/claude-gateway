package fr.claudegateway.governance.map.index;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Ce que F-174 range sur la carte d'un client, et qui part avec lui</b> (D10).
 *
 * <p>Un seul point de purge pour toutes les tables de F-174 : le journal des consultations
 * (SF-174-01) et, à partir de SF-174-02, l'index de la carte. Aucune clé étrangère vers
 * {@code users} ni vers les postes : rien ne tombe en cascade, la purge doit être nommée.</p>
 */
@Service
public class HostMapIndexPurge {

    private final HostMapLookupRepository lookups;

    public HostMapIndexPurge(HostMapLookupRepository lookups) {
        this.lookups = lookups;
    }

    /** À la suppression du compte. */
    @Transactional
    public void purgeUser(UUID userId) {
        if (userId == null) {
            return;
        }
        lookups.purgeUser(userId);
    }

    /** À la suppression d'un poste. */
    @Transactional
    public void purgeHost(UUID userId, UUID hostId) {
        if (userId == null || hostId == null) {
            return;
        }
        lookups.purgeHost(userId, hostId);
    }
}
