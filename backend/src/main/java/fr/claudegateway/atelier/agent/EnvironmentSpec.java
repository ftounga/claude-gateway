package fr.claudegateway.atelier.agent;

import java.util.List;

/**
 * Spécification d'un environnement Managed Agents à créer (F-28 / Phase 2).
 *
 * @param name                 nom lisible de l'environnement
 * @param allowPackageManagers autorise les gestionnaires de paquets dans le réseau limité du bac à sable
 * @param allowedHosts         hôtes que la politique réseau de l'environnement doit laisser joindre
 *                             (F-31 / SF-31-07) — l'hôte du serveur MCP GitHub y figure, faute de quoi
 *                             le fournisseur refuse toute session déclarant ce serveur. Liste vide
 *                             acceptée : l'environnement reste alors strictement limité
 */
public record EnvironmentSpec(String name, boolean allowPackageManagers, List<String> allowedHosts) {

    public EnvironmentSpec {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }
}
