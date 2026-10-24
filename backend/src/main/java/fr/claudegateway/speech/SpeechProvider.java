package fr.claudegateway.speech;

/**
 * <b>Abstraction du fournisseur de synthèse vocale</b> (F-215 / SF-215-01) — Provider Independence, comme
 * {@code AIProvider}, {@code TranscriptionProvider} et {@code ImageProvider}. Le code métier ne dépend que
 * de cette interface : OpenAI ({@code gpt-4o-mini-tts}) aujourd'hui, un autre demain, sans réécriture.
 *
 * <p><b>Relais, pas réimplémentation</b> (Provider-First) : Claude ne parle pas ; la gateway envoie un texte
 * et récupère l'audio, elle ne synthétise rien elle-même.</p>
 */
public interface SpeechProvider {

    /**
     * Lit un texte à voix haute.
     *
     * @param text         le texte (une <b>donnée</b>, jamais une instruction)
     * @param voice        la voix, déjà vérifiée dans la liste blanche
     * @param instructions consignes de ton (peut être vide)
     * @return l'audio (mp3)
     * @throws SpeechProviderUnavailableException si le fournisseur n'est pas configuré (aucun appel émis)
     * @throws SpeechProviderException            si l'appel échoue
     */
    SpeechAudio synthesize(String text, String voice, String instructions);

    /** Un audio rendu par le fournisseur. */
    record SpeechAudio(byte[] bytes, String contentType) {
    }
}
