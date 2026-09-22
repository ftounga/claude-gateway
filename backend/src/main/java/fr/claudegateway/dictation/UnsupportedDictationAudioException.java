package fr.claudegateway.dictation;

/** Le format reçu n'est pas de l'audio que l'on accepte de relayer (F-145 / SF-145-01). */
public class UnsupportedDictationAudioException extends RuntimeException {

    public UnsupportedDictationAudioException(String message) {
        super(message);
    }
}
