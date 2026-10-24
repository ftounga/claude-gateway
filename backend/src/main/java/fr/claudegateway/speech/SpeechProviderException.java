package fr.claudegateway.speech;

/** L'appel au fournisseur de synthèse vocale a échoué (F-215 / SF-215-01). Réessayable. */
public class SpeechProviderException extends RuntimeException {

    public SpeechProviderException(String message) {
        super(message);
    }

    public SpeechProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
