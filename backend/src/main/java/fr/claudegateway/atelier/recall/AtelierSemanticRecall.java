package fr.claudegateway.atelier.recall;

import java.util.List;
import java.util.UUID;

/**
 * La <b>recherche sémantique du {@code recall}</b> (F-162 / SF-162-06), branchée sur la boucle d'atelier.
 * Deux capacités : <b>embeddre</b> un message à l'écriture (asynchrone, best-effort) et <b>chercher</b>
 * par le sens les messages les plus proches d'une requête.
 *
 * <p><b>Repli total par {@link #NONE}</b> (patron {@code ResolutionMemory.NONE}) : quand aucune impl
 * n'est branchée (formes historiques, tests de la boucle), {@code recall} se comporte exactement comme
 * SF-162-01 — recherche mot-clé, aucun embedding. Le mot-clé reste ainsi le filet dans tous les cas.</p>
 *
 * <p><b>Isolation</b> : {@link #search} filtre {@code user_id} ET {@code workspace_id}. Un utilisateur ne
 * rappelle jamais les messages d'un autre, ni d'un autre workspace, même en sémantique.</p>
 */
public interface AtelierSemanticRecall {

    /** Repli : sémantique éteint. {@code recall} reste en mot-clé, l'embedding à l'écriture est ignoré. */
    AtelierSemanticRecall NONE = new AtelierSemanticRecall() {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void embedAsync(UUID messageId, String content) {
            // rien : pas de sémantique branché.
        }

        @Override
        public List<UUID> search(UUID userId, UUID workspaceId, String query, int topN) {
            return List.of();
        }
    };

    /** Vrai si le sémantique est réellement appelable (coupe-circuit armé + clé présente). */
    boolean isEnabled();

    /**
     * Planifie le calcul et le rangement de l'embedding d'un message, <b>de façon asynchrone et
     * best-effort</b> : ne bloque jamais le tour, ne lève jamais. Un échec laisse simplement le message
     * sans embedding (le backfill le rattrapera).
     */
    void embedAsync(UUID messageId, String content);

    /**
     * Ids des messages les plus proches de {@code query} par le sens, du plus proche au plus lointain,
     * <b>isolés {@code user_id} + {@code workspace_id}</b>, bornés à {@code topN}. Renvoie une liste vide si
     * le sémantique est éteint, si l'embedding de la requête échoue, ou s'il n'y a aucun voisin — ce qui
     * déclenche le repli mot-clé côté appelant.
     */
    List<UUID> search(UUID userId, UUID workspaceId, String query, int topN);

    /**
     * Rappel <b>à portée poste</b> (F-178 / SF-178-01) : comme {@link #search}, sur un ensemble de fils
     * (le terminal du poste et ses sujets), <b>toujours filtré {@code user_id}</b>. Par défaut : vide
     * (repli mot-clé) — une implémentation sans cette capacité ne casse rien.
     */
    default List<UUID> searchAcross(UUID userId, java.util.Collection<UUID> workspaceIds, String query,
            int topN) {
        return List.of();
    }
}
