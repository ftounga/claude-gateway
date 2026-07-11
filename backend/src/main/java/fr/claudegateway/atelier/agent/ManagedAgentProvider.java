package fr.claudegateway.atelier.agent;

/**
 * Abstraction fournisseur pour les <b>Managed Agents</b> (F-28 / Phase 2, ADR-013). Parallèle de
 * {@code AIProvider} : le code métier ne dépend que de cette interface, jamais directement
 * d'Anthropic (Provider Independence). Deux opérations de fondation, sans exécution ni session
 * (aucun coût runtime) :
 *
 * <ul>
 *   <li>{@link #createEnvironment(EnvironmentSpec)} — provisionne un bac à sable cloud versionné ;</li>
 *   <li>{@link #createAgent(AgentSpec)} — provisionne une définition d'agent versionnée.</li>
 * </ul>
 *
 * <p>Distinct du package {@code fr.claudegateway.agent} (abstraction tool-use de la Phase 1) : ce
 * provider cible l'API Managed Agents d'Anthropic (Environments/Agents/Sessions).</p>
 */
public interface ManagedAgentProvider {

    /**
     * Crée un environnement d'exécution (bac à sable cloud) chez le fournisseur.
     *
     * @param spec caractéristiques de l'environnement à créer
     * @return l'environnement créé (identifiant fournisseur)
     */
    ManagedEnvironment createEnvironment(EnvironmentSpec spec);

    /**
     * Crée une définition d'agent versionnée chez le fournisseur.
     *
     * @param spec caractéristiques de l'agent à créer
     * @return la définition d'agent créée (identifiant + version fournisseur)
     */
    ManagedAgentDefinition createAgent(AgentSpec spec);
}
