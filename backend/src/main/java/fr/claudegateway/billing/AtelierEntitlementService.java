package fr.claudegateway.billing;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.access.AccessGrantService;

/**
 * Porte la règle du <b>droit d'accès à l'Atelier</b> (F-40). Jusqu'ici ce droit était un test de
 * <b>plan</b> ({@code PlanCode == GOLD}) écrit dans le paquet {@code atelier} ; il devient un test
 * de <b>droit</b>, et il vit dans le paquet {@code billing}, à côté de l'abonnement qu'il lit.
 *
 * <p>Le droit est ouvert dans exactement quatre cas :</p>
 * <ol>
 *   <li>le <b>plan Gold</b> est actif ({@code ACTIVE}/{@code PAST_DUE}) — <i>strictement</i> le
 *       comportement d'avant F-40 : aucune régression de droit n'est acceptable ;</li>
 *   <li>le <b>plan BYOK</b> est actif (F-41) : le client paie la plateforme et apporte ses propres
 *       jetons ; l'Atelier fait partie de la plateforme qu'il paie, et lui vendre en plus le droit
 *       d'Atelier reviendrait à facturer deux fois la même chose ;</li>
 *   <li>l'<b>option Atelier</b> est active ({@code ACTIVE}/{@code PAST_DUE}) <b>et</b> le plan qui
 *       la porte est un {@link PlanCode#SOLO} ou {@link PlanCode#PRO} lui-même actif ;</li>
 *   <li>un <b>accès offert</b> est en cours (F-62) : un code d'accès à durée limitée a été consommé
 *       et son terme n'est pas atteint.</li>
 * </ol>
 *
 * <p>Toute autre situation est refusée (fail-closed, cohérent avec {@code EntitlementService}).
 * L'option ouvre un <b>droit</b>, jamais un jeton : aucun quota n'est lu ni modifié ici.</p>
 *
 * <p><b>F-62 — l'accès offert est une quatrième source de droit, pas un quatrième plan.</b> Un code
 * n'écrit rien dans l'abonnement : le plan reste celui que la facturation a inscrit, et c'est
 * précisément ce qui fait qu'il n'y a <b>rien à restaurer</b> au terme. Le droit se ferme parce que
 * {@code now} a dépassé le terme — une comparaison, pas un job planifié qui pourrait ne pas
 * tourner. Et comme l'option de F-40, il n'ajoute <b>aucun jeton</b>.</p>
 */
@Service
public class AtelierEntitlementService {

    /** Statuts qui valent « en cours » : l'actif, et le sursis de paiement (règle déjà celle de Gold). */
    private static final Set<SubscriptionStatus> LIVE_STATUSES =
            EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);

    /**
     * Plans sur lesquels l'option Atelier peut se greffer. {@code DAILY} en est exclu : un pass
     * journée ne porte pas un abonnement mensuel. {@code GOLD} n'a pas besoin de l'option — il
     * ouvre déjà le droit par lui-même.
     */
    private static final Set<PlanCode> OPTION_CARRIER_PLANS = EnumSet.of(PlanCode.SOLO, PlanCode.PRO);

    /**
     * Plans qui comprennent l'Atelier par eux-mêmes : {@code GOLD} (ADR-012) et, depuis F-41,
     * {@code BYOK} — le client y paie la plateforme entière et apporte ses propres jetons.
     */
    private static final Set<PlanCode> PLANS_INCLUDING_ATELIER =
            EnumSet.of(PlanCode.GOLD, PlanCode.BYOK);

    private final SubscriptionService subscriptionService;
    private final AccessGrantService accessGrantService;

    public AtelierEntitlementService(SubscriptionService subscriptionService,
            AccessGrantService accessGrantService) {
        this.subscriptionService = subscriptionService;
        this.accessGrantService = accessGrantService;
    }

    /**
     * Indique si l'utilisateur a le droit d'accès à l'Atelier.
     *
     * @param userId utilisateur du contexte de sécurité (isolation : jamais un paramètre client)
     * @return {@code true} si le plan Gold est actif, si l'option Atelier est active sur un plan
     *         porteur actif, ou si un accès offert (F-62) est en cours ; {@code false} sinon
     */
    public boolean isEntitled(UUID userId) {
        return isEntitled(subscriptionService.getOrCreateForUser(userId));
    }

    /**
     * Même règle, appliquée à un abonnement déjà chargé (évite une relecture quand l'appelant l'a
     * déjà en main, par exemple pour construire la réponse de l'écran de facturation).
     *
     * @param subscription abonnement de l'utilisateur (jamais {@code null})
     * @return {@code true} si le droit est ouvert
     */
    public boolean isEntitled(Subscription subscription) {
        return isIncludedInPlan(subscription)
                || isGrantedByOption(subscription)
                || isGrantedByAccessCode(subscription.getUserId());
    }

    /**
     * Vrai si le droit vient d'un <b>accès offert</b> (F-62) encore ouvert, <b>grâce de tour
     * comprise</b>.
     *
     * <p>La grâce n'est pas une largesse : ce contrôle est rejoué à chaque requête d'un tour — les
     * relances du runner, le flux d'événements — et fermer la porte à la seconde exacte du terme
     * couperait un tour engagé en plein milieu, ce que F-62 interdit. Elle laisse finir ce qui était
     * commencé ; elle n'ajoute aucun jeton, puisqu'un code n'en a jamais ajouté.</p>
     *
     * @param userId propriétaire de l'abonnement (jamais un paramètre client)
     * @return {@code true} si un accès offert est en cours
     */
    public boolean isGrantedByAccessCode(UUID userId) {
        return accessGrantService.isGrantedWithGrace(userId);
    }

    /**
     * Vrai si le droit vient du <b>plan lui-même</b> (Gold, ou BYOK depuis F-41) — l'option serait
     * alors inutile. Sert à l'écran de facturation pour dire « incluse dans votre offre » plutôt que
     * de proposer un achat sans objet.
     *
     * @param subscription abonnement de l'utilisateur
     * @return {@code true} si le plan actif inclut l'Atelier
     */
    public boolean isIncludedInPlan(Subscription subscription) {
        return PLANS_INCLUDING_ATELIER.contains(subscription.getPlanCode())
                && isLive(subscription.getStatus());
    }

    /**
     * Vrai si le droit vient de l'<b>option</b> : option en cours <i>et</i> plan porteur (Solo/Pro)
     * lui-même en cours. Une option seule ne tient pas : elle est un supplément, pas un plan — sans
     * plan actif l'utilisateur n'a aucun jeton pour faire tourner l'Atelier qu'il paie.
     *
     * @param subscription abonnement de l'utilisateur
     * @return {@code true} si l'option ouvre le droit
     */
    public boolean isGrantedByOption(Subscription subscription) {
        return isLive(subscription.getAtelierOptionStatus())
                && OPTION_CARRIER_PLANS.contains(subscription.getPlanCode())
                && isLive(subscription.getStatus());
    }

    private static boolean isLive(SubscriptionStatus status) {
        return status != null && LIVE_STATUSES.contains(status);
    }
}
