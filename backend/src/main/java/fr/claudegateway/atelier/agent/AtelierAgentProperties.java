package fr.claudegateway.atelier.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages des Managed Agents de l'Atelier (F-28 / Phase 2). Externalisés pour être ajustables sans
 * changement de code. Le flag {@code enabled} est <b>faux par défaut</b> : tant qu'il n'est pas activé,
 * aucun appel réseau ni coût runtime (le bootstrap reste inerte).
 *
 * @param enabled              active le bootstrap Managed Agents (défaut {@code false} : dormant)
 * @param environmentName      nom de l'environnement (bac à sable cloud) à provisionner
 * @param agentName            nom de l'agent à provisionner
 * @param model                modèle de l'agent (défaut {@code claude-opus-4-8})
 * @param allowPackageManagers autorise les gestionnaires de paquets dans le bac à sable (défaut {@code true})
 */
@ConfigurationProperties(prefix = "app.atelier.agent")
public record AtelierAgentProperties(
        boolean enabled,
        String environmentName,
        String agentName,
        String model,
        Boolean allowPackageManagers) {

    public AtelierAgentProperties {
        if (environmentName == null || environmentName.isBlank()) {
            environmentName = "claude-gateway-atelier";
        }
        if (agentName == null || agentName.isBlank()) {
            agentName = "claude-gateway-atelier";
        }
        if (model == null || model.isBlank()) {
            model = "claude-opus-4-8";
        }
        if (allowPackageManagers == null) {
            allowPackageManagers = true;
        }
    }
}
