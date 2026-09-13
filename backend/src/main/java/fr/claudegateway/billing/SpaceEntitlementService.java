package fr.claudegateway.billing;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import fr.claudegateway.access.AccessGrantService;

/**
 * Porte la règle du <b>droit d'accès à un espace</b> — Forge ou Vigie (F-107 / SF-107-02).
 *
 * <p>Absorbe les deux services de droit qui l'ont précédé, <b>sans changer une seule de leurs
 * réponses</b> : {@code AtelierEntitlementService} (F-40, F-62, F-107 SF-107-01/06) pour la Forge,
 * {@code TeamsEntitlementService} (F-89, F-106) pour la Vigie. Ils dupliquaient la même mécanique ;
 * l'offre par espace (SF-107-03) l'aurait écrite une troisième fois.</p>
 *
 * <p>Le droit à un espace est ouvert dans exactement quatre cas, évalués <b>dans cet ordre</b> :</p>
 * <ol>
 *   <li>le compte est <b>administrateur</b> (SF-107-06, {@link AdministratorEntitlement}) : il a tout,
 *       aucun abonnement n'est consulté — la règle vit ici pour que tout lecteur du droit, en requête
 *       ou hors requête (synchro de nuit, relance du runner), en hérite ;</li>
 *   <li>le <b>plan</b> actif ({@code ACTIVE}/{@code PAST_DUE}) <b>inclut</b> l'espace ;</li>
 *   <li>l'<b>option</b> de l'espace est en cours <b>et</b> portée par un plan porteur lui-même en
 *       cours — une option seule ne tient pas, c'est un supplément ;</li>
 *   <li>un <b>accès offert</b> (F-62) est en cours, grâce de tour comprise.</li>
 * </ol>
 *
 * <p>Toute autre situation est refusée (fail-closed). <b>Un droit n'est jamais un jeton</b> : aucun
 * quota n'est lu ni modifié ici.</p>
 *
 * <h2>Les règles par espace</h2>
 *
 * <p>Elles tiennent dans une table, {@link #RULES}, et c'est le seul endroit du produit qui dit quel
 * plan comprend quel espace.</p>
 */
@Service
public class SpaceEntitlementService {

    /** Statuts qui valent « en cours » : l'actif, et le sursis de paiement. */
    private static final Set<SubscriptionStatus> LIVE_STATUSES =
            EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);

    /**
     * La règle de chaque espace.
     *
     * <ul>
     *   <li><b>Forge</b> : incluse dans Gold Forge ({@code GOLD}, ADR-012) et Gold complet ; option
     *       portée par Solo, Pro, BYOK (SF-107-01) et Gold Vigie ; état dans {@code atelier_option_status}.</li>
     *   <li><b>Vigie</b> : incluse dans Gold Vigie et Gold complet (F-107 / SF-107-03) ; option portée
     *       par Solo, Pro, BYOK et Gold Forge ; état dans {@code teams_option_status} — l'option Vigie
     *       remplace l'option Teams (cadrage F-107 §3).</li>
     * </ul>
     *
     * <p>{@code DAILY} ne porte rien : un pass journée ne porte pas un abonnement mensuel.</p>
     */
    private static final Map<EntitlementSpace, SpaceRule> RULES = rules();

    private final SubscriptionService subscriptionService;
    private final AccessGrantService accessGrantService;
    private final AdministratorEntitlement administratorEntitlement;

    public SpaceEntitlementService(SubscriptionService subscriptionService,
            AccessGrantService accessGrantService, AdministratorEntitlement administratorEntitlement) {
        this.subscriptionService = subscriptionService;
        this.accessGrantService = accessGrantService;
        this.administratorEntitlement = administratorEntitlement;
    }

    /**
     * Indique si l'utilisateur a le droit d'accès à l'espace.
     *
     * @param userId utilisateur du contexte de sécurité ou du tour (isolation : jamais un paramètre client)
     * @param space  espace demandé
     * @return {@code true} si administrateur, plan incluant l'espace, option en cours sur un plan porteur
     *         en cours, ou accès offert en cours
     */
    public boolean isEntitled(UUID userId, EntitlementSpace space) {
        SpaceRule rule = rule(space);
        if (isGrantedByRole(userId)) {
            return true; // L'administrateur a tout : aucun abonnement n'est consulté.
        }
        return isEntitled(subscriptionService.getOrCreateForUser(userId), rule);
    }

    /**
     * Même règle, appliquée à un abonnement déjà chargé (l'écran de facturation l'a en main).
     *
     * @param subscription abonnement de l'utilisateur (jamais {@code null})
     * @param space        espace demandé
     * @return {@code true} si le droit est ouvert
     */
    public boolean isEntitled(Subscription subscription, EntitlementSpace space) {
        SpaceRule rule = rule(space);
        return isGrantedByRole(subscription.getUserId()) || isEntitled(subscription, rule);
    }

    /**
     * Vrai si le droit vient du <b>rôle administrateur</b> (SF-107-06) : l'écran dit « incluse
     * (administrateur) » plutôt que de proposer un achat.
     *
     * @param userId propriétaire de l'abonnement (jamais un paramètre client)
     * @return {@code true} si l'utilisateur est administrateur
     */
    public boolean isGrantedByRole(UUID userId) {
        return administratorEntitlement.isAdministrator(userId);
    }

    /**
     * Vrai si un <b>accès offert</b> (F-62) est en cours, <b>grâce de tour comprise</b> : le contrôle est
     * rejoué à chaque requête d'un tour, et fermer la porte à la seconde du terme couperait un tour engagé.
     *
     * @param userId propriétaire de l'abonnement (jamais un paramètre client)
     * @return {@code true} si un accès offert est en cours
     */
    public boolean isGrantedByAccessCode(UUID userId) {
        return accessGrantService.isGrantedWithGrace(userId);
    }

    /**
     * Vrai si le droit vient du <b>plan lui-même</b> : l'option de cet espace serait alors sans objet.
     * Lecture d'abonnement pure — elle dit <i>d'où</i> vient le droit, pas s'il est ouvert.
     *
     * @param subscription abonnement de l'utilisateur
     * @param space        espace demandé
     * @return {@code true} si le plan en cours inclut l'espace
     */
    public boolean isIncludedInPlan(Subscription subscription, EntitlementSpace space) {
        return isIncludedInPlan(subscription, rule(space));
    }

    /**
     * Vrai si le droit vient de l'<b>option</b> de l'espace : option en cours <i>et</i> plan porteur
     * lui-même en cours. Lecture d'abonnement pure.
     *
     * @param subscription abonnement de l'utilisateur
     * @param space        espace demandé
     * @return {@code true} si l'option ouvre le droit
     */
    public boolean isGrantedByOption(Subscription subscription, EntitlementSpace space) {
        return isGrantedByOption(subscription, rule(space));
    }

    /**
     * Vrai si ce plan peut porter l'option de l'espace. Source unique de la liste, relue par les services
     * de souscription d'option : deux listes finiraient par diverger.
     *
     * @param planCode plan de l'abonnement, éventuellement {@code null} (essai)
     * @param space    espace dont on veut l'option
     * @return {@code true} si le plan est un porteur de l'option
     */
    public boolean isOptionCarrier(PlanCode planCode, EntitlementSpace space) {
        return planCode != null && rule(space).optionCarriers().contains(planCode);
    }

    private boolean isEntitled(Subscription subscription, SpaceRule rule) {
        return isIncludedInPlan(subscription, rule)
                || isGrantedByOption(subscription, rule)
                || isGrantedByAccessCode(subscription.getUserId());
    }

    private static boolean isIncludedInPlan(Subscription subscription, SpaceRule rule) {
        return subscription.getPlanCode() != null
                && rule.includingPlans().contains(subscription.getPlanCode())
                && isLive(subscription.getStatus());
    }

    private static boolean isGrantedByOption(Subscription subscription, SpaceRule rule) {
        return isLive(rule.optionStatus().apply(subscription))
                && subscription.getPlanCode() != null
                && rule.optionCarriers().contains(subscription.getPlanCode())
                && isLive(subscription.getStatus());
    }

    private static SpaceRule rule(EntitlementSpace space) {
        if (space == null) {
            throw new IllegalArgumentException("Espace de droit absent");
        }
        return RULES.get(space);
    }

    private static boolean isLive(SubscriptionStatus status) {
        return status != null && LIVE_STATUSES.contains(status);
    }

    private static Map<EntitlementSpace, SpaceRule> rules() {
        Map<EntitlementSpace, SpaceRule> rules = new EnumMap<>(EntitlementSpace.class);
        rules.put(EntitlementSpace.FORGE, new SpaceRule(
                EnumSet.of(PlanCode.GOLD, PlanCode.GOLD_COMPLETE),
                EnumSet.of(PlanCode.SOLO, PlanCode.PRO, PlanCode.BYOK, PlanCode.GOLD_VIGIE),
                Subscription::getAtelierOptionStatus));
        rules.put(EntitlementSpace.VIGIE, new SpaceRule(
                EnumSet.of(PlanCode.GOLD_VIGIE, PlanCode.GOLD_COMPLETE),
                EnumSet.of(PlanCode.SOLO, PlanCode.PRO, PlanCode.GOLD, PlanCode.BYOK),
                Subscription::getTeamsOptionStatus));
        return Map.copyOf(rules);
    }

    /**
     * La règle d'un espace.
     *
     * @param includingPlans plans qui comprennent l'espace par eux-mêmes
     * @param optionCarriers plans sur lesquels l'option de l'espace peut se greffer
     * @param optionStatus   lecture de l'état de l'option dans l'abonnement
     */
    private record SpaceRule(
            Set<PlanCode> includingPlans,
            Set<PlanCode> optionCarriers,
            Function<Subscription, SubscriptionStatus> optionStatus) {

        private SpaceRule {
            Objects.requireNonNull(optionStatus);
            includingPlans = Set.copyOf(includingPlans);
            optionCarriers = Set.copyOf(optionCarriers);
        }
    }
}
