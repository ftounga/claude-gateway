package fr.claudegateway.bilan;

/**
 * <b>Un motif</b> (F-155 / SF-155-05) : la même suggestion revenue assez souvent pour que ce ne
 * soit plus une habitude à corriger.
 *
 * <p>Quand un genre revient séance après séance, le remède n'est plus un conseil : c'est que
 * <b>l'application</b> laisse le défaut se reproduire, et cela demande une <b>feature</b> — c'est
 * le sujet de F-156. Sans ce renvoi, le bilan répéterait indéfiniment un conseil déjà lu, et
 * deviendrait exactement le bruit qu'il est censé éviter.</p>
 *
 * @param kind    le genre qui revient
 * @param seen    combien de fois sur la fenêtre observée
 * @param window  combien de bilans ont été regardés
 * @param lead    ce que le diagnostic du produit irait chercher
 */
public record SessionPattern(SessionSuggestion.Kind kind, int seen, int window, String lead) {
}
