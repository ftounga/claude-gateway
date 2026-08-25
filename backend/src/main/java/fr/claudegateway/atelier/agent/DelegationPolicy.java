package fr.claudegateway.atelier.agent;

/**
 * Politique de <b>délégation</b> d'une session (F-35 / SF-35-01), exprimée <b>dans le domaine</b> :
 * le service dit ce qu'il veut (« l'agent peut confier des sous-tâches à au plus N copies de
 * lui-même »), le provider seul sait comment le fournisseur l'exprime (Provider Independence).
 *
 * <p>Chaque sous-agent consomme <b>sa propre session de bac à sable facturée</b> : le plafond n'est
 * donc pas un réglage de confort, c'est ce qui borne le pire cas de coût d'un run à un multiple
 * connu, plutôt qu'à un nombre décidé par le modèle.</p>
 *
 * @param enabled      vrai si la session peut déléguer ; faux pour le comportement historique, où le
 *                     run est strictement séquentiel
 * @param maxSubagents nombre maximal de sous-agents du roster ({@code 0} quand la délégation est
 *                     désactivée)
 */
public record DelegationPolicy(boolean enabled, int maxSubagents) {

    /** Politique historique : aucune délégation, run séquentiel (comportement d'avant F-35). */
    public static final DelegationPolicy DISABLED = new DelegationPolicy(false, 0);

    public DelegationPolicy {
        if (!enabled || maxSubagents <= 0) {
            // Un roster vide n'est pas une délégation : on ramène les deux champs à l'état désactivé
            // pour qu'il n'existe qu'une seule représentation du « pas de délégation ».
            enabled = false;
            maxSubagents = 0;
        }
    }

    /**
     * Politique correspondant au réglage serveur. Un plafond nul ou négatif vaut « pas de
     * délégation » : mieux vaut un run séquentiel qu'un roster vide envoyé au fournisseur.
     *
     * @param enabled      flag serveur de délégation
     * @param maxSubagents plafond configuré du nombre de sous-agents
     * @return la politique normalisée
     */
    public static DelegationPolicy of(boolean enabled, int maxSubagents) {
        return new DelegationPolicy(enabled, maxSubagents);
    }
}
