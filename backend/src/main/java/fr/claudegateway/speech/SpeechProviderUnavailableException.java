package fr.claudegateway.speech;

/**
 * La voix off n'est <b>pas ouverte</b> (F-215 / SF-215-01) : coupe-circuit éteint ou clé absente. Aucun
 * appel sortant n'est émis.
 */
public class SpeechProviderUnavailableException extends RuntimeException {

    public SpeechProviderUnavailableException(String message) {
        super(message);
    }
}
