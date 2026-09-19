package fr.claudegateway.teams.meeting.stt;

/** L'appel au service de transcription (STT) a échoué (F-128 / SF-128-04). Réessayable. */
public class TranscriptionProviderException extends RuntimeException {

    public TranscriptionProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    public TranscriptionProviderException(String message) {
        super(message);
    }
}
