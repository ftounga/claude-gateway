package fr.claudegateway.atelier.agent;

/**
 * Spécification d'un environnement Managed Agents à créer (F-28 / Phase 2).
 *
 * @param name                 nom lisible de l'environnement
 * @param allowPackageManagers autorise les gestionnaires de paquets dans le réseau limité du bac à sable
 */
public record EnvironmentSpec(String name, boolean allowPackageManagers) {
}
