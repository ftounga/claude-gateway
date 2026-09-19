package fr.claudegateway.teams.meeting.stt;

/**
 * Le service de transcription (STT) n'est <b>pas configuré</b> (F-128 / SF-128-04) : aucune base URL /
 * clé fournie. <b>Aucun appel sortant n'est émis</b> — l'audio ne quitte pas le poste/la gateway. Le
 * fournisseur lève cette exception nommée plutôt que d'appeler dans le vide.
 */
public class TranscriptionProviderUnavailableException extends RuntimeException {

    public TranscriptionProviderUnavailableException(String message) {
        super(message);
    }
}
