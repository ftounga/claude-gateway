package fr.claudegateway.atelier;

import java.util.UUID;

/**
 * <b>Persistance du mode et du plan par thread</b> (F-121 / SF-121-10), vue par la boucle d'atelier.
 *
 * <p>Le mode du tour (F-120 / SF-120-02) et le plan de travail (F-39 / SF-39-13) étaient jusqu'ici
 * jetés à la fin de chaque tour. On les persiste désormais sur le workspace — le « thread » —, à côté
 * du résumé de compaction (F-117). Cela restaure le sélecteur de mode à l'ouverture du projet et
 * réinjecte le dernier plan encore actif au tour suivant.</p>
 *
 * <p>{@link #NONE} rend la boucle d'<b>avant</b> SF-121-10 : rien n'est persisté, aucun accès base.
 * C'est le défaut pour toutes les formes historiques (tests) qui ne branchent pas le store.</p>
 *
 * <p>Isolation multi-tenant : l'implémentation filtre {@code user_id} — un mode/plan n'est jamais
 * écrit pour un workspace qu'on ne possède pas.</p>
 */
public interface AtelierThreadStateStore {

    /**
     * Persiste, en fin de tour, le mode et le dernier plan encore actif du thread. Best-effort : un
     * échec de persistance ne doit jamais casser un tour dont la réponse est déjà prête.
     *
     * @param userId      propriétaire (filtre d'isolation obligatoire)
     * @param workspaceId le thread
     * @param mode        {@code null} ⇒ efface (vaut {@code ACT}) ; sinon le nom de l'enum
     * @param planJson    {@code null} ⇒ efface ; sinon le JSON du plan ({@link AtelierPlan#toJson()})
     */
    void persist(UUID userId, UUID workspaceId, String mode, String planJson);

    /** Repli inerte : rien n'est persisté, comportement d'avant SF-121-10. */
    AtelierThreadStateStore NONE = (userId, workspaceId, mode, planJson) -> {
        // Rien : le mode et le plan restent per-tour, jetés en fin de tour.
    };
}
