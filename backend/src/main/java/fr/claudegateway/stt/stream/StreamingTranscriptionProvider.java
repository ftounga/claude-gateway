package fr.claudegateway.stt.stream;

import java.time.Instant;

/**
 * <b>Abstraction du fournisseur de transcription en flux</b> (F-233 / SF-233-04, D5) — Provider Independence, comme
 * {@code TranscriptionProvider} (batch) et {@code SpeechProvider}.
 *
 * <p>La passerelle n'écoute rien elle-même : elle ouvre une <b>session</b> chez le fournisseur et rend au navigateur
 * de quoi lui parler directement (URL + <b>jeton éphémère</b>). La clé du fournisseur ne quitte jamais le serveur.</p>
 *
 * <p>Interface séparée de {@code TranscriptionProvider} : le batch (dictée, réunions) et le flux (Jarvis) peuvent
 * être servis par deux fournisseurs différents sans deux beans du même type.</p>
 */
public interface StreamingTranscriptionProvider {

    /** Le nom de configuration du fournisseur (ex. {@code elevenlabs}), en minuscules. */
    String name();

    /** Vrai si le fournisseur est appelable (clé fournie). */
    boolean isConfigured();

    /**
     * Ouvre une session : obtient un jeton éphémère et fixe les paramètres de session.
     *
     * @param languageHint  langue attendue (ex. {@code fr}) ou {@code null}
     * @param silenceSeconds silence de fin de tour côté fournisseur (D6)
     * @param vocabulary     F-239 / SF-239-04 — les termes vers lesquels orienter la reconnaissance (bornés), ou vide
     * @throws fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException non configuré (aucun appel)
     * @throws fr.claudegateway.teams.meeting.stt.TranscriptionProviderException            échec de l'appel
     */
    StreamingSession openStreamingSession(String languageHint, double silenceSeconds, java.util.List<String> vocabulary);

    /**
     * Une session prête : l'URL WebSocket <b>sans</b> le jeton (l'écran l'ajoute), le jeton, son échéance et le format
     * audio attendu (PCM 16 bits mono à {@code sampleRate} Hz).
     */
    record StreamingSession(String provider, String url, String token, Instant expiresAt, int sampleRate) {
    }
}
