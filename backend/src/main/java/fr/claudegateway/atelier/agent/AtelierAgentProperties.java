package fr.claudegateway.atelier.agent;

import java.time.Duration;

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
 * @param maxSessionFiles      nombre maximal de fichiers montés dans une session (défaut {@code 300})
 * @param sessionTimeout       délai dur d'attente de complétion (défaut {@code PT10M} — garde-fou coût)
 * @param maxPolls             nombre maximal de tours de polling d'events (défaut {@code 600})
 * @param pollDelay            attente entre deux tours de polling (défaut {@code PT1S} ; {@code 0} en test)
 */
@ConfigurationProperties(prefix = "app.atelier.agent")
public record AtelierAgentProperties(
        boolean enabled,
        String environmentName,
        String agentName,
        String model,
        Boolean allowPackageManagers,
        Integer maxSessionFiles,
        Duration sessionTimeout,
        Integer maxPolls,
        Duration pollDelay) {

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
        if (maxSessionFiles == null || maxSessionFiles <= 0) {
            maxSessionFiles = 300;
        }
        if (sessionTimeout == null || sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            sessionTimeout = Duration.ofMinutes(10);
        }
        if (maxPolls == null || maxPolls <= 0) {
            maxPolls = 600;
        }
        if (pollDelay == null || pollDelay.isNegative()) {
            // 0 explicitement autorisé (tests déterministes sans sleep réel).
            pollDelay = Duration.ofSeconds(1);
        }
    }
}
