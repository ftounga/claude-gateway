package fr.claudegateway.billing;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.access.AccessGrantService;

/**
 * Porte la règle du <b>droit d'accès au volet Teams</b> (F-89 / SF-89-01, décision D5 du cadrage).
 *
 * <p>Le motif est celui de F-40 : <b>un droit découplé du plan</b>. Il vit dans le paquet
 * {@code billing}, à côté de l'abonnement qu'il lit, et les appelants ne connaissent que la
 * question — « ce compte peut-il lire Teams ? » — jamais la réponse en termes de plans.</p>
 *
 * <p>Le droit est ouvert dans exactement deux cas :</p>
 * <ol>
 *   <li>l'<b>option Teams</b> est en cours ({@code ACTIVE}/{@code PAST_DUE}) <b>et</b> le plan qui
 *       la porte est lui-même en cours ;</li>
 *   <li>un <b>accès offert</b> est en cours (F-62) : c'est l'essai, et D5 le nomme —
 *       <i>« un essai se donne par code d'accès, le mécanisme existe déjà »</i>.</li>
 * </ol>
 *
 * <h2>Deux différences avec le droit d'Atelier, et elles sont voulues</h2>
 *
 * <p><b>Aucun plan n'inclut Teams.</b> Gold comprend l'Atelier (BYOK plus depuis F-107) ; aucun ne comprend le
 * volet Teams, qui est une option et rien d'autre (D5). Le dire autrement reviendrait à décider
 * d'un prix — et le montant de l'option est <b>à confirmer par le PO</b>.</p>
 *
 * <p><b>Tout plan mensuel en cours peut porter l'option</b> — {@link PlanCode#SOLO},
 * {@link PlanCode#PRO}, {@link PlanCode#GOLD}, {@link PlanCode#BYOK} — précisément parce qu'aucun ne
 * la comprend déjà. F-40 limitait les porteurs à Solo/Pro pour la raison inverse : Gold n'avait
 * aucun besoin d'une option qu'il contenait. {@link PlanCode#DAILY} reste exclu : un pass journée ne
 * porte pas un abonnement mensuel.</p>
 *
 * <p><b>L'option ouvre l'accès, elle n'ajoute pas de jetons</b> (D5, doctrine F-40 reprise par
 * F-62) : aucun quota n'est lu ni modifié ici, et la consommation d'un tour Teams tombe sur le quota
 * existant de l'utilisateur.</p>
 *
 * <p>Toute autre situation est refusée (fail-closed).</p>
 */
@Service
public class TeamsEntitlementService {

    /** Statuts qui valent « en cours » : l'actif, et le sursis de paiement (règle de F-40). */
    private static final Set<SubscriptionStatus> LIVE_STATUSES =
            EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);

    /**
     * Plans sur lesquels l'option Teams peut se greffer : <b>tous les plans mensuels</b>.
     * {@code DAILY} en est exclu (un pass journée ne porte pas un abonnement mensuel), et l'absence
     * de plan — un essai sans offre — aussi.
     */
    private static final Set<PlanCode> OPTION_CARRIER_PLANS =
            EnumSet.of(PlanCode.SOLO, PlanCode.PRO, PlanCode.GOLD, PlanCode.BYOK);

    private final SubscriptionService subscriptionService;
    private final AccessGrantService accessGrantService;

    public TeamsEntitlementService(SubscriptionService subscriptionService,
            AccessGrantService accessGrantService) {
        this.subscriptionService = subscriptionService;
        this.accessGrantService = accessGrantService;
    }

    /**
     * Indique si l'utilisateur a le droit d'accès au volet Teams.
     *
     * @param userId utilisateur du contexte de sécurité (isolation : jamais un paramètre client)
     * @return {@code true} si l'option Teams est en cours sur un plan porteur en cours, ou si un
     *         accès offert (F-62) est en cours ; {@code false} sinon
     */
    public boolean isEntitled(UUID userId) {
        return isGrantedByOption(subscriptionService.getOrCreateForUser(userId))
                || accessGrantService.isGrantedWithGrace(userId);
    }

    /**
     * Même règle, appliquée à un abonnement déjà chargé — mais <b>sans</b> l'accès offert, qui ne
     * se lit pas dans l'abonnement. Sert à l'écran de facturation, qui veut dire « option souscrite »
     * plutôt que « accès en cours ».
     *
     * @param subscription abonnement de l'utilisateur (jamais {@code null})
     * @return {@code true} si l'option ouvre le droit
     */
    public boolean isGrantedByOption(Subscription subscription) {
        return isLive(subscription.getTeamsOptionStatus())
                && OPTION_CARRIER_PLANS.contains(subscription.getPlanCode())
                && isLive(subscription.getStatus());
    }

    private static boolean isLive(SubscriptionStatus status) {
        return status != null && LIVE_STATUSES.contains(status);
    }
}
