package fr.claudegateway.stt.utterance;

import java.util.List;

/**
 * <b>Retranscription d'un énoncé complet</b> (F-239 / SF-239-04, D1) — Provider Independence, comme
 * {@code TranscriptionProvider} (dictée, réunions) et {@code StreamingTranscriptionProvider} (écoute en flux).
 *
 * <p>Un énoncé court (quelques secondes), transcrit d'un coup à la fin du tour par un modèle plus précis que celui du
 * flux, avec un <b>vocabulaire</b> (noms propres, termes métier) qui oriente la reconnaissance. Interface séparée :
 * l'énoncé de Jarvis et la dictée du terminal peuvent être servis par deux fournisseurs différents.</p>
 */
public interface UtteranceTranscriptionProvider {

    /** Le nom de configuration du fournisseur (ex. {@code elevenlabs}), en minuscules. */
    String name();

    /** Vrai si le fournisseur est appelable (clé fournie). */
    boolean isConfigured();

    /**
     * Transcrit l'énoncé.
     *
     * @param audio        les octets audio (wav, webm/opus…)
     * @param contentType  le type MIME de l'audio, ou {@code null}
     * @param languageHint la langue attendue (ex. {@code fr}), ou {@code null}
     * @param vocabulary   les termes vers lesquels orienter la reconnaissance (déjà bornés par l'appelant)
     * @return le texte transcrit (vide si rien n'a été dit)
     * @throws fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException non configuré (aucun appel)
     * @throws fr.claudegateway.teams.meeting.stt.TranscriptionProviderException            échec de l'appel
     */
    String transcribe(byte[] audio, String contentType, String languageHint, List<String> vocabulary);
}
