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
     * Le nom du fournisseur (F-233 / SF-233-06), en minuscules : {@code openai} par défaut. Sert à Jarvis pour
     * choisir sa voix par configuration et à la mesure (SF-233-01).
     */
    default String name() {
        return "openai";
    }

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

    /**
     * Lit un texte à voix haute <b>en flux</b> (F-231 / SF-231-01) : les octets mp3 sont écrits dans {@code out}
     * au fil de leur arrivée, pour que la voix de Jarvis commence avant la fin de la synthèse.
     *
     * <p>Méthode par défaut : un fournisseur qui ne sait pas diffuser rend tout l'audio d'un coup (même
     * résultat, premier octet plus tardif). Les exceptions sont celles de {@link #synthesize}.</p>
     *
     * @return le nombre d'octets écrits
     */
    default long synthesizeStream(String text, String voice, String instructions, java.io.OutputStream out)
            throws java.io.IOException {
        byte[] bytes = synthesize(text, voice, instructions).bytes();
        out.write(bytes);
        out.flush();
        return bytes.length;
    }

    /** Un audio rendu par le fournisseur. */
    record SpeechAudio(byte[] bytes, String contentType) {
    }
}
