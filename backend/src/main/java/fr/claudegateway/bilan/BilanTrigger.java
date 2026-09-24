package fr.claudegateway.bilan;

/**
 * Ce que la fermeture d'une session a décidé du bilan (F-155 / SF-155-03).
 *
 * <p>Trois issues, et le silence est l'une d'elles : un bilan qui apparaît à chaque nouveau départ
 * devient un bruit qu'on cesse de lire.</p>
 */
public enum BilanTrigger {

    /** La session méritait un bilan : il est produit sans rien demander. */
    AUTOMATIQUE,

    /** Elle n'en valait pas le prix, mais il y a quelque chose à dire : proposé d'un clic. */
    PROPOSE,

    /** Rien à montrer — ou l'utilisateur n'est pas l'administrateur. */
    AUCUN
}
