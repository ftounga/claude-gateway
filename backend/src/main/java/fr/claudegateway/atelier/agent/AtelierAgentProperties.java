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
 * @param maxTranscriptChars   borne de la transcription persistée dans l'historique (défaut
 *                             {@code 100000}, F-30 SF-30-09) : un tour qui installe un projet entier
 *                             ne doit pas faire gonfler l'historique sans limite
 * @param maxToolOutputChars   borne de la sortie d'outil relayée au frontend (défaut {@code 10000},
 *                             F-30 SF-30-01) : un {@code npm install} produit des dizaines de milliers
 *                             de lignes, qui satureraient le flux SSE et le navigateur
 * @param confirmTimeout       délai laissé à l'utilisateur pour autoriser ou refuser une commande
 *                             (défaut {@code PT2M}, F-33 SF-33-02) : passé ce délai, la commande est
 *                             <b>refusée</b> — le silence ne vaut pas autorisation. Borné de fait par
 *                             {@code sessionTimeout}, qui plafonne le run entier
 * @param maxInstructionsChars borne du fichier d'instructions du projet injecté à l'ouverture de
 *                             session (défaut {@code 20000}, F-34 SF-34-01) : au-delà le contenu est
 *                             tronqué avec mention — un fichier démesuré consommerait à chaque
 *                             session le contexte utile au travail
 * @param subagentsEnabled     autorise l'agent à <b>déléguer</b> des sous-tâches à des copies de
 *                             lui-même (défaut {@code true}, F-35 SF-35-01, révision D1 du
 *                             2026-08-26) : les sous-agents sont des threads de la même session, donc
 *                             sans multiplication du coût de bac à sable, et le budget de session
 *                             (F-36) les borne tous à la fois. Le flag reste pour <b>couper sans
 *                             redéployer</b> si l'usage révélait une dérive
 * @param maxSubagents         plafond du nombre de sous-agents par session (défaut {@code 3},
 *                             F-35 SF-35-01) : borne le parallélisme, pas la dépense — celle-ci est
 *                             bornée par le budget partagé de la session
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
        Duration pollDelay,
        Integer maxToolOutputChars,
        Integer maxTranscriptChars,
        Integer maxInstructionsChars,
        Duration confirmTimeout,
        Boolean subagentsEnabled,
        Integer maxSubagents) {

    public AtelierAgentProperties {
        if (environmentName == null || environmentName.isBlank()) {
            environmentName = "claude-gateway-atelier";
        }
        if (agentName == null || agentName.isBlank()) {
            agentName = "claude-gateway-atelier";
        }
        if (maxToolOutputChars == null || maxToolOutputChars <= 0) {
            maxToolOutputChars = 10_000;
        }
        if (maxTranscriptChars == null || maxTranscriptChars <= 0) {
            maxTranscriptChars = 100_000;
        }
        if (maxInstructionsChars == null || maxInstructionsChars <= 0) {
            maxInstructionsChars = 20_000;
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
        if (confirmTimeout == null || confirmTimeout.isZero() || confirmTimeout.isNegative()) {
            // Deux minutes : le temps de lire une commande et de décider, sans laisser un bac à sable
            // réservé attendre indéfiniment (il est facturé pendant ce temps).
            confirmTimeout = Duration.ofMinutes(2);
        }
        if (subagentsEnabled == null) {
            // Activée par défaut (révision D1) : une capacité livrée mais éteinte n'est jamais testée.
            subagentsEnabled = true;
        }
        if (maxSubagents == null || maxSubagents <= 0) {
            // Trois : le gain de parallélisme est déjà là, sans rendre la transcription illisible.
            maxSubagents = 3;
        }
        if (pollDelay == null || pollDelay.isNegative()) {
            // 0 explicitement autorisé (tests déterministes sans sleep réel).
            pollDelay = Duration.ofSeconds(1);
        }
    }
}
