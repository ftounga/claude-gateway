package fr.claudegateway.governance.dto;

/**
 * <b>La mesure qui décide</b> : l'application apprend-elle vraiment ? (F-140 / SF-140-01)
 *
 * <p>L'audit du 2026-09-21 a désigné ce chiffre comme le critère de réussite de F-136 et F-137 :
 * <i>« le nombre d'appels d'exploration par tour — s'il baisse à mesure que la carte grossit,
 * l'application apprend vraiment ; sinon, on le sait »</i>. Sans lui, la promesse de tout ce
 * chantier resterait invérifiable, et une régression future passerait inaperçue.</p>
 *
 * <p><b>Deux fenêtres, pas une.</b> Un chiffre seul ne dit rien : c'est l'écart entre le récent et
 * le long qui porte l'information. Les comparer est le travail de celui qui lit, pas d'une formule
 * qui masquerait ses hypothèses.</p>
 *
 * <p><b>{@code null} plutôt que zéro.</b> Sans tour sur la fenêtre, il n'y a pas de ratio — un
 * « 0,0 appel par tour » se lirait comme un succès éclatant alors qu'il ne s'est rien passé.</p>
 *
 * @param recentTurns tours des 7 derniers jours
 * @param recentCalls appels d'outils des 7 derniers jours
 * @param recentCallsPerTurn appels par tour sur 7 jours, ou {@code null} sans tour
 * @param longTurns   tours des 30 derniers jours
 * @param longCalls   appels d'outils des 30 derniers jours
 * @param longCallsPerTurn appels par tour sur 30 jours, ou {@code null} sans tour
 * @param facts       faits actuellement portés par la carte de ce poste
 */
public record HostLearningView(long recentTurns, long recentCalls, Double recentCallsPerTurn,
        long longTurns, long longCalls, Double longCallsPerTurn, int facts) {
}
