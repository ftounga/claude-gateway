package fr.claudegateway.jarvis;

/** Le plafond mensuel de la voix de Jarvis est atteint (F-231 / SF-231-01) : aucun appel ne part. */
public class JarvisVoiceBudgetException extends RuntimeException {

    public JarvisVoiceBudgetException() {
        super("Le plafond mensuel de la voix de Jarvis est atteint.");
    }
}
