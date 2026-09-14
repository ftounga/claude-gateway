package fr.claudegateway.governance.control;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Où vivent les promotions reportées faute de poste (F-93 / SF-93-04, persistées en SF-93-05).
 *
 * <p>Deux implémentations, une seule sémantique (fusion, durée de vie, bornes, réclamation une fois,
 * isolation sur le triple) partagée via les aides statiques de {@link PromotionReportee} :</p>
 *
 * <ul>
 *   <li>{@link InMemoryPromotionReporteeStore} — mémoire du processus, pour les tests et comme repli ;
 *   c'était le seul support en SF-93-04 ;</li>
 *   <li>{@link JpaPromotionReporteeStore} — base de données, le bean de production : un report survit
 *   à un redémarrage et à un changement de pod.</li>
 * </ul>
 *
 * <p><b>Isolation.</b> Toute opération porte le triple complet {@code (userId, hostId, workspaceId)} :
 * un report n'est jamais réclamé chez un autre utilisateur, un autre poste ou un autre projet.</p>
 */
public interface PromotionReporteeStore {

    /**
     * Retient un report. Les reports successifs du même triple se <b>cumulent</b> : éléments sans
     * doublon (bornés), dette la plus haute, date du premier report.
     */
    void reporter(UUID userId, UUID hostId, UUID workspaceId, Collection<String> elements, int dette);

    /**
     * <b>Réclame</b> le report d'un triple : le rend, et le retire. Réclamé une seule fois — le
     * réclamer à chaque tour recréerait la boucle à l'échelle des tours (SF-93-04, D3).
     */
    Optional<PromotionReportee.Report> reclamer(UUID userId, UUID hostId, UUID workspaceId);

    /** Vrai si un report est dû pour ce triple (sans le réclamer). */
    boolean estDue(UUID userId, UUID hostId, UUID workspaceId);
}
