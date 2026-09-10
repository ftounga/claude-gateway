package fr.claudegateway.governance.dto;

/**
 * Une ligne de l'annonce (F-51 / SF-51-03) : un fichier, l'endroit exact où il ira, et ce qui va lui
 * arriver.
 *
 * @param path   chemin <b>relatif au projet</b>, tel qu'il sera écrit
 * @param kind   {@code SKILL} ou {@code TEMPLATE}
 * @param action {@code CREATE}, {@code KEEP} ou {@code UNKNOWN}
 */
public record GovernanceDepositEntry(String path, String kind, GovernanceDepositAction action) {
}
