package fr.claudegateway.atelier;

/**
 * Un appel de l'outil {@code demander} mal formé (F-164 / SF-164-01).
 *
 * <p>Comme les blocs riches (F-89) : on <b>échoue bruyamment</b>. Le message n'est pas un « invalid
 * input », c'est une phrase qui dit au modèle quoi corriger, rendue comme résultat d'outil — le tour
 * n'est jamais figé, le modèle se reprend.</p>
 */
public class AtelierQuestionRejectedException extends RuntimeException {

    public AtelierQuestionRejectedException(String message) {
        super(message);
    }
}
