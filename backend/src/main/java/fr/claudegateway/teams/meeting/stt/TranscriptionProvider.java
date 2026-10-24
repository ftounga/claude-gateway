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

    /**
     * Transcrit l'audio <b>mot par mot</b>, chaque mot avec son instant de début et de fin
     * (F-215 / SF-215-01 : caler les animations d'une vidéo sur la voix off).
     *
     * <p>Méthode par défaut : un fournisseur qui ne sait pas horodater les mots le dit par
     * {@link UnsupportedOperationException} — l'appelant continue sans horodatage.</p>
     *
     * @throws TranscriptionProviderUnavailableException si le service n'est pas configuré (aucun appel émis)
     * @throws TranscriptionProviderException            si l'appel au service échoue
     */
    default WordTranscript transcribeWords(byte[] audio, String contentType, String languageHint) {
        throw new UnsupportedOperationException("Horodatage par mot non pris en charge par ce fournisseur.");
    }

    /** Un transcript relu du service : le texte (horodaté si possible) et la langue détectée. */
    record Transcript(String text, String language) {
    }

    /** Un mot horodaté, en secondes depuis le début de l'audio. */
    record TimedWord(String word, double start, double end) {
    }

    /** Une transcription mot par mot : les mots dans l'ordre, la durée totale (secondes), la langue. */
    record WordTranscript(java.util.List<TimedWord> words, double durationSeconds, String language) {

        public WordTranscript {
            words = words == null ? java.util.List.of() : java.util.List.copyOf(words);
        }
    }
}
