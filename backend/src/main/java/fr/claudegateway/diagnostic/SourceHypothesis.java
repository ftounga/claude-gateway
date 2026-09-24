package fr.claudegateway.diagnostic;

/**
 * <b>Une hypothèse tirée du code</b> (F-157 / SF-157-04) — <b>jamais un verdict</b>.
 *
 * <p>Le type lui-même porte le mot : une hypothèse qui se déguise en constat est pire qu'un
 * silence, parce qu'on développerait sur une supposition. Tout ce qui l'affiche doit le dire.</p>
 *
 * @param capabilityId la capacité examinée
 * @param text         ce que la lecture suggère
 * @param truncated    vrai quand le code envoyé a été coupé — un extrait qu'on croit complet ferait
 *                     raisonner de travers
 * @param model        le modèle qui a répondu
 * @param inputTokens  jetons d'entrée consommés
 * @param outputTokens jetons de sortie consommés
 */
public record SourceHypothesis(
        String capabilityId,
        String text,
        boolean truncated,
        String model,
        int inputTokens,
        int outputTokens) {

    /** Le mot qui doit accompagner toute restitution. */
    public static final String CAVEAT =
            "Hypothèse tirée de la lecture du code — à vérifier, ce n'est pas un constat mesuré.";
}
