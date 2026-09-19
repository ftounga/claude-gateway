package fr.claudegateway.teams.meeting.stt;

/**
 * <b>Abstraction du service de transcription (STT)</b> — Provider Independence, comme {@code AIProvider}.
 * Le code métier (worker) ne dépend que de cette interface, jamais d'un fournisseur concret : on peut
 * viser un service hébergé compatible Whisper aujourd'hui, un STT local par client demain, sans
 * réécriture (F-128 / SF-128-04, cadrage D2).
 *
 * <p><b>Relais, pas réimplémentation</b> (Provider-First) : la gateway envoie l'audio et récupère le
 * transcript ; elle ne transcrit pas elle-même.</p>
 */
public interface TranscriptionProvider {

    /**
     * Transcrit l'audio d'une réunion.
     *
     * @param audio        les octets audio (webm/opus, mp3, wav…)
     * @param contentType  le type MIME de l'audio (sert au nom de fichier envoyé au service)
     * @param languageHint langue attendue (ex. {@code fr}) ou {@code null} pour laisser le service décider
     * @return le transcript (texte horodaté best-effort + langue détectée)
     * @throws TranscriptionProviderUnavailableException si le service n'est pas configuré (aucun appel émis)
     * @throws TranscriptionProviderException            si l'appel au service échoue
     */
    Transcript transcribe(byte[] audio, String contentType, String languageHint);

    /** Un transcript relu du service : le texte (horodaté si possible) et la langue détectée. */
    record Transcript(String text, String language) {
    }
}
