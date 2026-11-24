package fr.claudegateway.atelier;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Où vivent les rubans envoyés (F-276 / SF-276-02), à part de l'entité {@link AtelierMessage} : un ruban
 * pèse jusqu'à plusieurs centaines de kilo-octets, et ni l'affichage du fil ni la compaction n'en ont
 * besoin. Seul le rejeu le lit, et seulement pour les tours de sa fenêtre tracée. Chaque accès est filtré
 * par {@code user_id}.
 */
public interface SentRibbonStore {

    /** Vrai si le rejeu lit les rubans. À {@code false} (coupe-circuit), rejeu d'avant ; le rangement continue. */
    boolean replayEnabled();

    /** Range le ruban du message assistant {@code messageId}. Jamais d'exception. */
    void save(UUID userId, UUID messageId, String json);

    /** Les rubans des messages {@code messageIds} de cet utilisateur, par identifiant. Vide sur panne. */
    Map<UUID, String> load(UUID userId, Collection<UUID> messageIds);

    /** Efface les rubans des messages d'avant {@code frontier} (compaction) de ce terminal. Rend le nombre effacé. */
    int purgeBefore(UUID userId, UUID workspaceId, OffsetDateTime frontier);
}
